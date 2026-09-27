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
    // FIX: without max_tokens some OpenRouter :free models default to a tiny
    // budget → truncated replies (finish_reason "length") surfaced as an
    // "(empty response)" final. Also pin tool_choice so tool-calling models
    // actually emit tool_calls.
    const maxTokens = Number(this.env.OPENAI_MAX_TOKENS ?? "") || 2000;
    body.max_tokens = maxTokens;
    if (tools.length) {
      body.tools = tools;
      body.tool_choice = "auto";
    }

    const headers: Record<string, string> = {
      "content-type": "application/json",
      authorization: `Bearer ${apiKey}`,
    };
    // OpenRouter app attribution (optional but recommended by their docs).
    if (/openrouter\.ai/.test(baseUrl)) {
      headers["HTTP-Referer"] = this.env.OPENROUTER_SITE_URL ?? "https://droidpilot-ai.droidpilot.workers.dev";
      headers["X-Title"] = this.env.OPENROUTER_SITE_NAME ?? "aMiNo";
    }

    const res = await fetch(`${baseUrl}/chat/completions`, {
      method: "POST",
      headers,
      body: JSON.stringify(body),
    });

    if (!res.ok) {
      const text = await res.text();
      // Some models (e.g. OpenAI via OpenRouter) are region-blocked depending on
      // which Cloudflare colo egresses the request. Retry once with the regional
      // fallback model (set OPENAI_REGION_FALLBACK var) so the agent keeps working.
      if (res.status === 403 && /not available in your region/i.test(text)) {
        const fb = this.env.OPENAI_REGION_FALLBACK;
        if (fb && fb !== modelId) {
          const retry = await fetch(`${baseUrl}/chat/completions`, {
            method: "POST",
            headers,
            body: JSON.stringify({ ...body, model: fb }),
          });
          if (!retry.ok) {
            const t2 = await retry.text();
            throw new Error(`AI_HTTP_${retry.status}: ${t2.slice(0, 300)}`);
          }
          const d2: any = await retry.json();
          return { ...parseAnyResponse(d2), style: `openai-compat→fallback:${fb}` };
        }
      }
      throw new Error(`AI_HTTP_${res.status}: ${text.slice(0, 300)}`);
    }
    const data: any = await res.json();
    const parsed = parseAnyResponse(data);
    // FIX: a length-truncated response with no tool calls used to end the turn
    // as "(empty response)". Throw so chatWithFailover retries/fails over.
    const finish = data?.choices?.[0]?.finish_reason;
    if (!parsed.toolCalls.length && !parsed.text && finish === "length") {
      throw new Error(`AI_TRUNCATED: model hit max_tokens (${maxTokens}) — raise OPENAI_MAX_TOKENS or failover`);
    }
    return { ...parsed, style: "openai-compat" };
  }
}
