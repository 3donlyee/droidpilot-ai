import type { Env } from "../env";
import type { ModelDefinition } from "./types";

/**
 * The single source of truth for available AI models.
 *
 * Adding a model = adding one entry here. The Web UI's Model Selector
 * reads `/api/models` which returns `getEnabledModels()`; the AgentEngine
 * resolves a model by id via `getModelById(id)`.
 *
 * External providers (openai, groq, ...) are listed as `enabled: false` by
 * default — flip to `true` and set the corresponding API key secret to enable.
 */
export const MODEL_REGISTRY: ModelDefinition[] = [
  {
    id: "@cf/openai/gpt-oss-20b",
    displayName: "GPT-OSS 20B (Cloudflare)",
    provider: "cloudflare",
    supportsTools: true,
    supportsVision: false,
    enabled: true,
    contextWindow: 128_000,
    description:
      "Default. Open-source GPT-OSS model running on Workers AI. Strong tool-calling and reasoning.",
  },
  {
    id: "@cf/meta/llama-3.1-8b-instruct",
    displayName: "Llama 3.1 8B Instruct (Cloudflare)",
    provider: "cloudflare",
    supportsTools: true,
    supportsVision: false,
    enabled: true,
    contextWindow: 128_000,
    description:
      "Fast, lightweight Llama. Good default fallback when latency matters more than reasoning depth.",
  },
  {
    id: "@cf/meta/llama-3.2-11b-vision-instruct",
    displayName: "Llama 3.2 11B Vision (Cloudflare)",
    provider: "cloudflare",
    supportsTools: true,
    supportsVision: true,
    enabled: true,
    contextWindow: 128_000,
    description:
      "Multimodal Llama for screenshot analysis. Use when take_screenshot() is needed and the text-only models cannot interpret the screen.",
  },
  {
    id: "@cf/qwen/qwen2.5-coder-32b-instruct",
    displayName: "Qwen 2.5 Coder 32B (Cloudflare)",
    provider: "cloudflare",
    supportsTools: true,
    supportsVision: false,
    enabled: true,
    contextWindow: 32_768,
    description:
      "Code-focused Qwen model. Strong at structured tool calls and JSON-shaped arguments.",
  },
  {
    id: "gpt-4o-mini",
    displayName: "GPT-4o mini (OpenAI)",
    provider: "openai",
    supportsTools: true,
    supportsVision: true,
    enabled: false,
    contextWindow: 128_000,
    description:
      "EXAMPLE external provider. Requires OPENAI_API_KEY secret. Flip `enabled: true` and set the secret to use.",
  },
  {
    id: "llama-3.3-70b-versatile",
    displayName: "Llama 3.3 70B Versatile (Groq)",
    provider: "groq",
    supportsTools: true,
    supportsVision: false,
    enabled: false,
    contextWindow: 128_000,
    description:
      "EXAMPLE external provider hosted on Groq (OpenAI-compatible). Requires GROQ_API_KEY or OPENAI_API_KEY secret + baseURL override.",
  },
];

/** Return only enabled models (for `/api/models`). */
export function getEnabledModels(): ModelDefinition[] {
  return MODEL_REGISTRY.filter((m) => m.enabled);
}

/** Resolve a model by id (enabled or not). */
export function getModelById(id: string): ModelDefinition | undefined {
  return MODEL_REGISTRY.find((m) => m.id === id);
}

/** Resolve the default model from `env.DEFAULT_MODEL_ID`, falling back to the first enabled. */
export function getDefaultModel(env: Env): ModelDefinition {
  const byEnv = env.DEFAULT_MODEL_ID ? getModelById(env.DEFAULT_MODEL_ID) : undefined;
  if (byEnv) return byEnv;
  const firstEnabled = getEnabledModels()[0];
  if (firstEnabled) return firstEnabled;
  // Last-resort fallback (should never happen — registry always has entries).
  return MODEL_REGISTRY[0];
}
