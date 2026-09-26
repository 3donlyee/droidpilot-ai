import type { AIProvider, AIChatResult, ChatMessage, ToolSchema } from "../types";
import type { Env } from "../env";
import { getModel } from "../models";
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

export type { AIProvider, AIChatResult, ChatMessage, ToolSchema };
