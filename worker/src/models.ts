/**
 * MODEL REGISTRY
 * ==============
 * The ONLY place where AI models are declared. Nothing in the system
 * hard-codes a model name. Add a new entry here and it automatically
 * appears in the Web UI model selector.
 *
 * provider "cloudflare"     → executed through the env.AI binding (no API key needed)
 * provider "openai-compat"  → executed through any OpenAI-compatible REST API
 *                             (OpenRouter, Groq, Ollama, OpenAI, vLLM, CF REST…)
 *                             Requires OPENAI_API_KEY secret; OPENAI_BASE_URL is
 *                             a public endpoint (set as a plain var).
 *
 * CURRENT ACTIVE: openai/gpt-4o via OpenRouter. Cloudflare models are kept
 * disabled below — flip "enabled" to use them again without any other change.
 */
export interface ModelEntry {
  id: string;
  displayName: string;
  provider: "cloudflare" | "openai-compat";
  supportsTools: boolean;
  supportsVision: boolean;
  reasoning: boolean;
  enabled: boolean;
  default?: boolean;
}

export const MODELS: ModelEntry[] = [
  {
    id: "openai/gpt-4o",
    displayName: "GPT-4o (OpenRouter)",
    provider: "openai-compat",
    supportsTools: true,
    supportsVision: true,
    reasoning: false,
    enabled: true,
    default: true,
  },
  {
    // Regional fallback: some OpenAI models are region-blocked depending on the
    // Cloudflare colo that egresses the request. Llama on OpenRouter is not.
    id: "meta-llama/llama-3.3-70b-instruct",
    displayName: "Llama 3.3 70B (OpenRouter)",
    provider: "openai-compat",
    supportsTools: true,
    supportsVision: false,
    reasoning: false,
    enabled: true,
  },
  // --- Cloudflare Workers AI (disabled — switched to OpenRouter) ---
  {
    id: "@cf/openai/gpt-oss-20b",
    displayName: "GPT-OSS 20B",
    provider: "cloudflare",
    supportsTools: true,
    supportsVision: false,
    reasoning: true,
    enabled: false,
  },
  {
    id: "@cf/meta/llama-3.3-70b-instruct-fp8-fast",
    displayName: "Llama 3.3 70B (fast)",
    provider: "cloudflare",
    supportsTools: true,
    supportsVision: false,
    reasoning: false,
    enabled: false,
  },
  {
    id: "@cf/meta/llama-4-scout-17b-16e-instruct",
    displayName: "Llama 4 Scout",
    provider: "cloudflare",
    supportsTools: true,
    supportsVision: true,
    reasoning: false,
    enabled: false,
  },
];

export function getModel(id: string): ModelEntry | undefined {
  return MODELS.find((m) => m.id === id);
}

export function defaultModel(): ModelEntry {
  return MODELS.find((m) => m.default && m.enabled) ?? MODELS.find((m) => m.enabled)!;
}

/** Public projection — never leaks internals. */
export function publicModels(): any[] {
  return MODELS.map((m) => ({
    id: m.id,
    displayName: m.displayName,
    provider: m.provider,
    capabilities: {
      reasoning: m.reasoning,
      supportsTools: m.supportsTools,
      supportsVision: m.supportsVision,
    },
    status: m.enabled ? "available" : "disabled",
    default: !!m.default,
  }));
}
