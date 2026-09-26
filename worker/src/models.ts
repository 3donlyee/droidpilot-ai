/**
 * MODEL REGISTRY
 * ==============
 * The ONLY place where AI models are declared. Nothing in the system
 * hard-codes a model name. Add a new entry here and it automatically
 * appears in the Web UI model selector.
 *
 * provider "cloudflare"     → executed through the env.AI binding (no API key needed)
 * provider "openai-compat"  → executed through any OpenAI-compatible REST API
 *                             (Groq, Ollama, OpenAI, vLLM, Cloudflare REST…)
 *                             Requires OPENAI_BASE_URL + OPENAI_API_KEY secrets.
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
    id: "@cf/openai/gpt-oss-20b",
    displayName: "GPT-OSS 20B",
    provider: "cloudflare",
    supportsTools: true,
    supportsVision: false,
    reasoning: true,
    enabled: true,
    default: true,
  },
  {
    id: "@cf/meta/llama-3.3-70b-instruct-fp8-fast",
    displayName: "Llama 3.3 70B (fast)",
    provider: "cloudflare",
    supportsTools: true,
    supportsVision: false,
    reasoning: false,
    enabled: true,
  },
  {
    id: "@cf/meta/llama-4-scout-17b-16e-instruct",
    displayName: "Llama 4 Scout",
    provider: "cloudflare",
    supportsTools: true,
    supportsVision: true,
    reasoning: false,
    enabled: false, // enable when you need a vision-capable model
  },
  // Example for a future external provider (fill secrets first):
  // {
  //   id: "llama-3.3-70b-versatile",
  //   displayName: "Llama 3.3 70B (Groq)",
  //   provider: "openai-compat",
  //   supportsTools: true,
  //   supportsVision: false,
  //   reasoning: false,
  //   enabled: false,
  // },
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
