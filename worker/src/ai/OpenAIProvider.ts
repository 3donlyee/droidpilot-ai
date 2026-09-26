import type {
  AIProvider,
  AIResponse,
  ChatOptions,
  ToolCall,
} from "./AIProvider";
import { logger } from "../utils/logger";

/**
 * OpenAI-compatible chat-completions provider.
 *
 * Works for:
 *   - OpenAI itself          (baseURL: https://api.openai.com/v1)
 *   - Groq                   (baseURL: https://api.groq.com/openai/v1)
 *   - Ollama (OpenAI mode)  (baseURL: http://localhost:11434/v1)
 *   - Any other OpenAI-compatible endpoint
 *
 * The API key is supplied via the OPENAI_API_KEY secret (also reused for Groq).
 */
export interface OpenAIProviderOptions {
  apiKey: string;
  /** Base URL WITHOUT trailing slash. Defaults to OpenAI's. */
  baseURL?: string;
  /** Display name for logs (e.g. "openai" | "groq" | "ollama"). */
  name?: string;
}

export class OpenAIProvider implements AIProvider {
  readonly name: string;
  private readonly apiKey: string;
  private readonly baseURL: string;

  constructor(opts: OpenAIProviderOptions) {
    if (!opts.apiKey) {
      throw new Error("OpenAIProvider: apiKey is required");
    }
    this.apiKey = opts.apiKey;
    this.baseURL = (opts.baseURL ?? "https://api.openai.com/v1").replace(/\/+$/, "");
    this.name = opts.name ?? "openai";
  }

  async chat(opts: ChatOptions): Promise<AIResponse> {
    const body: Record<string, unknown> = {
      model: opts.model,
      messages: opts.messages,
    };
    if (opts.tools && opts.tools.length > 0) {
      body.tools = opts.tools;
      body.tool_choice = "auto";
    }
    if (typeof opts.temperature === "number") {
      body.temperature = opts.temperature;
    }
    if (typeof opts.maxTokens === "number") {
      body.max_tokens = opts.maxTokens;
    }

    logger.debug(`${this.name} request`, {
      model: opts.model,
      messageCount: opts.messages.length,
      toolCount: opts.tools?.length ?? 0,
    });

    const resp = await fetch(`${this.baseURL}/chat/completions`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${this.apiKey}`,
      },
      body: JSON.stringify(body),
    });

    if (!resp.ok) {
      const errText = await resp.text().catch(() => "");
      let message = `HTTP ${resp.status}`;
      try {
        const j = JSON.parse(errText) as { error?: { message?: string } };
        if (j.error?.message) message = `${message}: ${j.error.message}`;
      } catch {
        if (errText) message = `${message}: ${errText.slice(0, 200)}`;
      }
      throw new Error(`OpenAIProvider(${this.name}) chat failed: ${message}`);
    }

    const data = (await resp.json()) as OpenAIResponseShape;
    const choice = data.choices?.[0];
    if (!choice) {
      return { content: null, finish_reason: "stop" };
    }

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
      usage: data.usage
        ? {
            prompt_tokens: data.usage.prompt_tokens ?? 0,
            completion_tokens: data.usage.completion_tokens ?? 0,
          }
        : undefined,
    };
  }
}

interface OpenAIResponseShape {
  choices?: Array<{
    message: {
      content: string | null;
      tool_calls?: Array<{
        id: string;
        type: "function";
        function: { name: string; arguments: string };
      }>;
    };
    finish_reason?: string;
  }>;
  usage?: {
    prompt_tokens?: number;
    completion_tokens?: number;
    total_tokens?: number;
  };
  error?: { message: string; type?: string; code?: string };
}
