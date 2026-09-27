import type { AIProvider, AIChatResult, ChatMessage, ToolSchema } from "../types";
import type { Env } from "../env";
import { getModel, defaultModel, MODELS } from "../models";
import { WorkersAIProvider } from "./workers-ai";
import { OpenAICompatProvider } from "./openai-compat";

/**
 * AI PROVIDER ABSTRACTION
 * =======================
 * The agent engine only talks to this interface. Swapping or adding providers
 * (Workers AI today; Groq / OpenAI / Ollama / local AI later) requires zero
 * changes to the agent loop.
 */
export function getProvider(env: Env, modelId?: string): AIProvider {
  let preferred = env.AI_PROVIDER ?? "workers-ai";

  // A model's registry entry can route to a specific provider.
  if (modelId) {
    const entry = getModel(modelId);
    if (entry) preferred = entry.provider;
  }

  if (preferred === "openai-compat") {
    return new OpenAICompatProvider(env);
  }
  return new WorkersAIProvider(env);
}

/**
 * FAILOVER CHAIN — keeps aMiNo answering even when one provider is exhausted:
 *   requested model → other usable OpenRouter free models → Workers AI models.
 * Throws the LAST error only when every candidate failed.
 */
export async function chatWithFailover(
  env: Env,
  modelId: string | undefined,
  messages: ChatMessage[],
  tools: ToolSchema[]
): Promise<AIChatResult> {
  const primary = (modelId && getModel(modelId) && modelId) || defaultModel(env).id;
  const candidates: string[] = [primary];

  for (const m of MODELS) {
    if (m.id === primary || !m.enabled) continue;
    if (m.requiresKey && !env.OPENAI_API_KEY) continue;
    candidates.push(m.id);
  }

  let lastErr: any;
  const errs: string[] = [];
  for (const id of candidates) {
    try {
      const provider = getProvider(env, id);
      const result = await provider.chat(id, messages, tools);
      if (id !== primary) {
        result.style = `${result.style ?? ""}|failover→${id}`;
      }
      return result;
    } catch (e) {
      lastErr = e;
      // Aggregate every candidate's error so the caller can see WHICH providers
      // failed (e.g. OpenRouter 429 daily quota + Cloudflare 4006 neurons) and
      // craft an honest Arabic message instead of blaming the wrong provider.
      errs.push(`[${id}] ${String((e as any)?.message ?? e).slice(0, 300)}`);
    }
  }
  throw new Error(errs.join(" || ") || String(lastErr));
}

export type { AIProvider, AIChatResult, ChatMessage, ToolSchema };
