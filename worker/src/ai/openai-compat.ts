import type { AIProvider, AIChatResult, ChatMessage, ToolSchema } from "../types";
import type { Env } from "../env";
import { parseAnyResponse } from "./workers-ai";

/**
 * OpenAI-compatible provider — for OpenRouter, Groq, OpenAI, Ollama, vLLM, or
 * Cloudflare's OpenAI-compatible REST endpoint (/ai/v1). Requires secret:
 *   OPENAI_API_KEY    provider API key
 * Base URL is a public endpoint, set as a plain var:
 *   OPENAI_BASE_URL   e.g. https://openrouter.ai/api/v1
 */
export class OpenAICompatProvider implements AIProvider {
  name = "openai-compat";

  constructor(private env: Env) {}

  async chat(modelId: string, messages: ChatMessage[], tools: ToolSchema[]): Promise<AIChatResult> {
    const baseUrl = (this.env.OPENAI_BASE_URL ?? "").replace(/\/$/, "");
    const apiKey = this.env.OPENAI_API_KEY ?? "";
    if (!baseUrl || !apiKey) {
      throw new Error("AI_PROVIDER_MISCONFIGURED: set OPENAI_BASE_URL and OPENAI_API_KEY secrets");
    }

    // Convert canonical messages to OpenAI chat format.
    const out: any[] = [];
    for (const m of messages) {
      if (m.role === "system" || m.role === "user") {
        out.push({ role: m.role, content: m.content });
      } else if (m.role === "assistant") {
        const msg: any = { role: "assistant", content: m.content ?? null };
        if (m.tool_calls?.length) {
          msg.tool_calls = m.tool_calls.map((tc) => ({
            id: tc.id,
            type: "function",
            function: { name: tc.name, arguments: JSON.stringify(tc.args ?? {}) },
          }));
        }
        out.push(msg);
      } else if (m.role === "tool") {
        out.push({ role: "tool", tool_call_id: m.tool_call_id, content: m.content });
      }
    }

    const body: any = {
      model: modelId || this.env.OPENAI_MODEL_FALLBACK || "gpt-4o-mini",
      messages: out,
      stream: false,
    };
    if (tools.length) body.tools = tools;

    const headers: Record<string, string> = {
      "content-type": "application/json",
      authorization: `Bearer ${apiKey}`,
    };
    // OpenRouter app attribution (optional but recommended by their docs).
    if (/openrouter\.ai/.test(baseUrl)) {
      headers["HTTP-Referer"] = this.env.OPENROUTER_SITE_URL ?? "https://droidpilot-ai.turkjgastroenterol-org.workers.dev";
      headers["X-Title"] = this.env.OPENROUTER_SITE_NAME ?? "DroidPilot AI";
    }

    const res = await fetch(`${baseUrl}/chat/completions`, {
      method: "POST",
      headers,
      body: JSON.stringify(body),
    });

    if (!res.ok) {
      const text = await res.text();
      throw new Error(`AI_HTTP_${res.status}: ${text.slice(0, 300)}`);
    }
    const data: any = await res.json();
    return { ...parseAnyResponse(data), style: "openai-compat" };
  }
}
