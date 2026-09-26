import type { AIProvider, AIChatResult, ChatMessage, ToolCall, ToolSchema } from "../types";
import type { Env } from "../env";
import { safeJsonParse } from "../util";

type Style = "responses" | "responses-flat" | "chat";

/**
 * Workers AI provider — executes models through the env.AI binding.
 * No API key required.
 *
 * gpt-oss models on Workers AI use the Responses-style schema. Some
 * deployments reject replayed function_call/function_call_output items, so
 * this provider tries, in order:
 *   1. "responses"      — full Responses items (function_call + _output)
 *   2. "responses-flat" — tool results folded into plain messages (always valid)
 *   3. "chat"           — chat-completions style (llama-family models)
 * The winning style is cached per model.
 */
export class WorkersAIProvider implements AIProvider {
  name = "workers-ai";
  private styleCache = new Map<string, Style>();

  constructor(private env: Env) {}

  async chat(modelId: string, messages: ChatMessage[], tools: ToolSchema[]): Promise<AIChatResult> {
    const order: Style[] = ["responses", "responses-flat", "chat"];
    const preferred = this.styleCache.get(modelId);
    const tries: Style[] = preferred ? [preferred, ...order.filter((s) => s !== preferred)] : order;

    const errors: string[] = [];
    for (const style of tries) {
      try {
        const r = await this.call(style, modelId, messages, tools);
        this.styleCache.set(modelId, style);
        return r;
      } catch (e: any) {
        errors.push(`[${style}] ${String(e?.message ?? e).slice(0, 400)}`);
      }
    }
    throw new Error(`AI_PROVIDER_FAILED: ${errors.join(" | ")}`);
  }

  private async call(style: Style, modelId: string, messages: ChatMessage[], tools: ToolSchema[]): Promise<AIChatResult> {
    let res: any;
    if (style === "chat") {
      const payload: any = { messages: toChatMessages(messages), stream: false };
      if (tools.length) payload.tools = tools;
      res = await (this.env.AI as any).run(modelId, payload);
    } else {
      const instructions = messages
        .filter((m) => m.role === "system")
        .map((m) => (m as any).content)
        .join("\n\n");

      const input =
        style === "responses"
          ? toResponsesInput(messages)
          : toFlatInput(messages);

      const payload: any = { input, stream: false };
      if (instructions) payload.instructions = instructions;
      if (tools.length) {
        payload.tools = tools.map((t) => ({
          type: "function",
          name: t.function.name,
          description: t.function.description,
          parameters: t.function.parameters,
        }));
      }
      res = await (this.env.AI as any).run(modelId, payload);
    }
    return { ...parseAnyResponse(res), style };
  }
}

/** Strict chat-completions conversion (Workers AI validators are picky). */
function toChatMessages(messages: ChatMessage[]): any[] {
  const out: any[] = [];
  for (const m of messages) {
    if (m.role === "system" || m.role === "user") {
      out.push({ role: m.role, content: m.content ?? "" });
    } else if (m.role === "assistant") {
      const msg: any = { role: "assistant", content: m.content ?? "" };
      if (m.tool_calls?.length) {
        msg.tool_calls = m.tool_calls.map((tc) => ({
          id: tc.id,
          type: "function",
          function: { name: tc.name, arguments: JSON.stringify(tc.args ?? {}) },
        }));
      }
      out.push(msg);
    } else if (m.role === "tool") {
      out.push({ role: "tool", tool_call_id: m.tool_call_id, content: m.content ?? "" });
    }
  }
  return out;
}

/** Full Responses-style conversion with function_call / function_call_output items. */
function toResponsesInput(messages: ChatMessage[]): any[] {
  const input: any[] = [];
  for (const m of messages) {
    if (m.role === "system") continue;
    if (m.role === "user") {
      input.push({ role: "user", content: m.content });
    } else if (m.role === "assistant") {
      if (m.content) input.push({ role: "assistant", content: m.content });
      for (const tc of m.tool_calls ?? []) {
        input.push({
          type: "function_call",
          name: tc.name,
          arguments: typeof tc.args === "string" ? tc.args : JSON.stringify(tc.args ?? {}),
          call_id: tc.id,
        });
      }
    } else if (m.role === "tool") {
      input.push({ type: "function_call_output", call_id: m.tool_call_id, output: m.content });
    }
  }
  return input;
}

/**
 * Flat conversion — tool activity rendered as plain messages. This format is
 * accepted by every model and keeps the full history visible to the agent.
 */
function toFlatInput(messages: ChatMessage[]): any[] {
  const input: any[] = [];
  for (const m of messages) {
    if (m.role === "system") continue;
    if (m.role === "user") {
      input.push({ role: "user", content: m.content });
    } else if (m.role === "assistant") {
      let text = m.content ?? "";
      for (const tc of m.tool_calls ?? []) {
        const args = typeof tc.args === "string" ? tc.args : JSON.stringify(tc.args ?? {});
        text += (text ? "\n" : "") + `I will use the tool ${tc.name} with arguments ${args}.`;
      }
      if (text.trim()) input.push({ role: "assistant", content: text });
    } else if (m.role === "tool") {
      input.push({ role: "user", content: `Tool result: ${m.content}` });
    }
  }
  return input;
}

/**
 * Normalizes every response shape we have seen from Workers AI bindings
 * into { text, toolCalls }.
 */
export function parseAnyResponse(res: any): { text: string | null; toolCalls: ToolCall[]; raw?: any } {
  const toolCalls: ToolCall[] = [];
  let text: string | null = null;

  const pushTC = (tc: any) => {
    if (!tc) return;
    const name = tc.function?.name ?? tc.name;
    if (!name) return;
    toolCalls.push({
      id: tc.call_id ?? tc.id ?? `call_${Math.random().toString(36).slice(2, 10)}`,
      name,
      args: safeJsonParse(tc.function?.arguments ?? tc.arguments ?? {}),
    });
  };

  if (res == null) {
    // empty
  } else if (typeof res === "string") {
    text = res;
  } else if (Array.isArray(res.output)) {
    // Responses style: output items are message / function_call / reasoning
    for (const item of res.output) {
      if (item?.type === "function_call") pushTC(item);
      else if (item?.type === "message") {
        const c = item.content;
        if (Array.isArray(c)) {
          text = (text ?? "") + c.map((p: any) => (p?.type === "output_text" || p?.type === "text" ? p?.text ?? "" : "")).join("");
        } else if (typeof c === "string") {
          text = (text ?? "") + c;
        }
      }
      // type "reasoning" items are intentionally ignored
    }
    if (!text && typeof res.output_text === "string") text = res.output_text;
  } else if (typeof res.output_text === "string") {
    text = res.output_text;
  } else if (res.response !== undefined) {
    if (typeof res.response === "string") text = res.response;
    else {
      (res.response?.tool_calls ?? []).forEach(pushTC);
      if (!text && typeof res.response?.content === "string") text = res.response.content;
    }
  }
  if (Array.isArray(res.tool_calls)) res.tool_calls.forEach(pushTC);
  if (Array.isArray(res.choices)) {
    // OpenAI-compat style, just in case
    const msg = res.choices[0]?.message;
    if (msg) {
      if (typeof msg.content === "string") text = (text ?? "") + msg.content;
      (msg.tool_calls ?? []).forEach(pushTC);
    }
  }

  return { text: text && text.length ? text : null, toolCalls, raw: res };
}
