import type { Env } from "../env";
import type { AIProvider, AIResponse, ChatOptions, ToolCall } from "./AIProvider";
import { logger } from "../utils/logger";

/**
 * Workers AI binding-backed provider.
 *
 * Uses `env.AI.run(modelId, { messages, tools, ... })` which returns a JSON
 * object that can be in one of two shapes:
 *
 *   1. Native CF shape (older models / legacy):
 *        { response?: string, tool_calls?: [{name, arguments}], usage?: {...} }
 *
 *   2. OpenAI-compatible `choices[]` shape (newer models):
 *        { choices: [{ message: { content, tool_calls }, finish_reason }], usage: {...} }
 *
 * This provider normalizes both into the unified `AIResponse` shape.
 */
export class CloudflareAIProvider implements AIProvider {
  readonly name = "cloudflare";

  constructor(private env: Env) {}

  async chat(opts: ChatOptions): Promise<AIResponse> {
    const params: Record<string, unknown> = {
      messages: opts.messages,
    };
    if (opts.tools && opts.tools.length > 0) {
      params.tools = opts.tools;
    }
    if (typeof opts.temperature === "number") {
      params.temperature = opts.temperature;
    }
    if (typeof opts.maxTokens === "number") {
      params.max_tokens = opts.maxTokens;
    }

    logger.debug("cf-ai request", {
      model: opts.model,
      messageCount: opts.messages.length,
      toolCount: opts.tools?.length ?? 0,
    });

    // Workers AI accepts a permissive options object. We cast loosely and
    // type-narrow the response below.
    const raw = (await this.env.AI.run(opts.model as never, params as never)) as unknown;
    return mapCFResponse(raw);
  }
}

/** Shape of a native CF Workers AI tool call (name + arguments). */
interface CFNativeToolCall {
  name: string;
  arguments: Record<string, unknown> | string;
}

/** Shape of an OpenAI-style tool call (id + function.{name,arguments}). */
interface CFChoicesToolCall {
  id?: string;
  type?: "function";
  function: { name: string; arguments: string };
}

interface CFResponse {
  response?: string;
  tool_calls?: CFNativeToolCall[];
  result?: { response?: string; tool_calls?: CFNativeToolCall[] };
  choices?: Array<{
    message: {
      content: string | null;
      tool_calls?: CFChoicesToolCall[];
    };
    finish_reason?: string;
  }>;
  usage?: {
    prompt_tokens?: number;
    completion_tokens?: number;
    total_tokens?: number;
  };
}

function mapCFResponse(raw: unknown): AIResponse {
  if (!raw || typeof raw !== "object") {
    return { content: null, finish_reason: "stop" };
  }
  const r = raw as CFResponse;

  const usage = r.usage
    ? {
        prompt_tokens: r.usage.prompt_tokens ?? 0,
        completion_tokens: r.usage.completion_tokens ?? 0,
      }
    : undefined;

  // OpenAI-compatible choices[] shape
  if (r.choices && r.choices.length > 0) {
    const choice = r.choices[0];
    const toolCalls: ToolCall[] | undefined = choice.message.tool_calls?.map((tc, idx) => ({
      id: tc.id || `call_${Date.now()}_${idx}`,
      type: "function" as const,
      function: {
        name: tc.function.name,
        arguments: tc.function.arguments ?? "{}",
      },
    }));
    const hasTools = !!toolCalls && toolCalls.length > 0;
    return {
      content: choice.message.content ?? null,
      tool_calls: hasTools ? toolCalls : undefined,
      finish_reason: hasTools ? "tool_calls" : "stop",
      usage,
    };
  }

  // Native CF shape (some endpoints nest under `result`)
  const root = r.result ?? r;
  const nativeCalls = root.tool_calls;
  const toolCalls: ToolCall[] | undefined = nativeCalls?.map((tc, idx) => ({
    id: `call_${Date.now()}_${idx}`,
    type: "function" as const,
    function: {
      name: tc.name,
      arguments:
        typeof tc.arguments === "string" ? tc.arguments : JSON.stringify(tc.arguments ?? {}),
    },
  }));
  const hasTools = !!toolCalls && toolCalls.length > 0;

  return {
    content: root.response ?? null,
    tool_calls: hasTools ? toolCalls : undefined,
    finish_reason: hasTools ? "tool_calls" : "stop",
    usage,
  };
}
