import type { Env } from "../env";
import type { ModelDefinition } from "../models/types";
import type { AIProvider, ChatMessage, ToolCall } from "./AIProvider";
import { buildSystemPrompt } from "../systemPrompt";
import { TOOL_SCHEMAS } from "../tools/schemas";
import type { CommandEnvelope, ResultEnvelope } from "../device/DeviceRegistry";
import { logger } from "../utils/logger";

/**
 * AgentEngine — the orchestrator.
 *
 * Loop:
 *   1. Build system prompt describing role + available tools.
 *   2. Push user message.
 *   3. Call provider.chat with tools.
 *   4. If response.tool_calls → for each (respect MAX_TOOL_CALLS_PER_TURN):
 *        - emit "tool_call" event
 *        - route command to device via DeviceRegistry.sendCommand, await result
 *        - emit "tool_result" event
 *        - append tool result message to conversation
 *      Then loop back to step 3.
 *   5. If response has no tool_calls → emit "assistant" with the final text, then "done".
 *
 * Safety:
 *   - Hard cap: MAX_TOOL_CALLS_PER_TURN (env, default 20).
 *   - If the same tool+args is called 3× in a row with the same failing result,
 *     inject a system message asking the AI to try a different strategy or
 *     request user confirmation; reset the counter so we don't loop forever.
 *   - All exceptions are caught and emitted as an "error" event.
 */
export interface AgentRunOptions {
  deviceId: string;
  /** Sends a command to the device and resolves when the result envelope arrives (or times out). */
  sendCommand: (deviceId: string, command: CommandEnvelope) => Promise<ResultEnvelope>;
  onToolCall?: (call: ToolCall) => void;
  onToolResult?: (
    callId: string,
    success: boolean,
    data: unknown,
    error?: { code: string; message: string },
  ) => void;
  onAssistantMessage?: (text: string) => void;
  /** If set, the engine checks `aborted` between iterations and stops if true. */
  signal?: { aborted: boolean };
}

export type AgentEvent =
  | { type: "tool_call"; data: { call: ToolCall } }
  | {
      type: "tool_result";
      data: {
        callId: string;
        toolName: string;
        success: boolean;
        data: unknown;
        error?: { code: string; message: string };
      };
    }
  | { type: "assistant"; data: { text: string } }
  | {
      type: "done";
      data: {
        reason: "stop" | "max_calls" | "aborted";
        totalToolCalls: number;
        finalText?: string;
      };
    }
  | { type: "error"; data: { message: string } };

export class AgentEngine {
  constructor(
    private env: Env,
    private provider: AIProvider,
    private model: ModelDefinition,
  ) {}

  async *run(userMessage: string, opts: AgentRunOptions): AsyncGenerator<AgentEvent> {
    const maxCalls = parseMaxCalls(this.env.MAX_TOOL_CALLS_PER_TURN);
    const messages: ChatMessage[] = [
      { role: "system", content: buildSystemPrompt() },
      { role: "user", content: userMessage },
    ];

    const tools = this.model.supportsTools ? TOOL_SCHEMAS : undefined;

    // Loop-break detection: track consecutive failures per (tool+args) key.
    const failureCounts = new Map<string, number>();
    const FAILURE_THRESHOLD = 3;

    let totalToolCalls = 0;
    let lastAssistantText: string | undefined;

    try {
      while (true) {
        if (opts.signal?.aborted) {
          yield {
            type: "done",
            data: { reason: "aborted", totalToolCalls, finalText: lastAssistantText },
          };
          return;
        }

        const response = await this.provider.chat({
          model: this.model.id,
          messages,
          tools,
          temperature: 0.4,
          maxTokens: 1024,
        });

        if (response.usage) {
          logger.debug("agent usage", {
            model: this.model.id,
            prompt_tokens: response.usage.prompt_tokens,
            completion_tokens: response.usage.completion_tokens,
            total_so_far: totalToolCalls,
          });
        }

        // No tool calls → final assistant message.
        if (!response.tool_calls || response.tool_calls.length === 0) {
          const text = (response.content ?? "").trim() ||
            "I have completed the requested actions.";
          lastAssistantText = text;
          messages.push({ role: "assistant", content: text });
          opts.onAssistantMessage?.(text);
          yield { type: "assistant", data: { text } };
          yield {
            type: "done",
            data: { reason: "stop", totalToolCalls, finalText: text },
          };
          return;
        }

        // Append the assistant turn WITH tool_calls (OpenAI / CF format).
        messages.push({
          role: "assistant",
          content: response.content ?? "",
          tool_calls: response.tool_calls,
        });

        // Process each tool call sequentially. (Parallelizing is possible
        // but complicates failure handling — keep it simple for now.)
        for (const call of response.tool_calls) {
          if (totalToolCalls >= maxCalls) {
            logger.warn("agent max_tool_calls reached", { maxCalls });
            yield {
              type: "done",
              data: { reason: "max_calls", totalToolCalls, finalText: lastAssistantText },
            };
            return;
          }
          totalToolCalls++;

          const argsRaw = call.function.arguments || "{}";
          const argsKey = `${call.function.name}:${argsRaw}`;
          let parsedArgs: Record<string, unknown> = {};
          try {
            parsedArgs = JSON.parse(argsRaw) as Record<string, unknown>;
          } catch {
            parsedArgs = { _raw: argsRaw };
          }

          opts.onToolCall?.(call);
          yield { type: "tool_call", data: { call } };

          const command: CommandEnvelope = {
            type: "command",
            id: call.id,
            tool: call.function.name,
            arguments: parsedArgs,
          };

          let result: ResultEnvelope;
          try {
            result = await opts.sendCommand(opts.deviceId, command);
          } catch (e) {
            const msg = e instanceof Error ? e.message : String(e);
            result = {
              type: "result",
              id: call.id,
              success: false,
              error: { code: "TRANSPORT_ERROR", message: msg },
            };
          }

          const success = !!result.success;
          opts.onToolResult?.(call.id, success, result.data ?? null, result.error);
          yield {
            type: "tool_result",
            data: {
              callId: call.id,
              toolName: call.function.name,
              success,
              data: result.data ?? null,
              error: result.error,
            },
          };

          // Append the tool-result message (OpenAI / CF format).
          messages.push({
            role: "tool",
            content: JSON.stringify({
              success,
              data: result.data ?? null,
              error: result.error ?? null,
            }),
            tool_call_id: call.id,
            name: call.function.name,
          });

          // Loop-break detection.
          if (!success) {
            const c = (failureCounts.get(argsKey) ?? 0) + 1;
            failureCounts.set(argsKey, c);
            if (c >= FAILURE_THRESHOLD) {
              logger.warn("agent loop-break triggered", {
                tool: call.function.name,
                attempts: c,
              });
              messages.push({
                role: "system",
                content:
                  "You have attempted the same action 3 times with the same arguments and it keeps failing. " +
                  "Stop retrying the same call. Either try a different strategy (re-read the screen via " +
                  "get_screen_nodes, target a different element, or take a screenshot), or ask the user " +
                  "for clarification before continuing.",
              });
              // Reset to avoid spamming the same warning.
              failureCounts.set(argsKey, 0);
            }
          } else {
            failureCounts.delete(argsKey);
          }
        }

        // Loop continues: next iteration calls provider.chat with the updated
        // messages array (which now contains the tool results).
      }
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      logger.error("agent loop error", { error: msg, model: this.model.id });
      yield { type: "error", data: { message: msg } };
    }
  }
}

function parseMaxCalls(envValue: string | undefined): number {
  const n = parseInt(envValue ?? "20", 10);
  if (!Number.isFinite(n) || n < 1) return 20;
  if (n > 100) return 100; // hard ceiling
  return n;
}
