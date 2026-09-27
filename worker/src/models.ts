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
 * CURRENT ACTIVE (100% FREE): Cloudflare Workers AI models only — no API keys,
 * no OpenRouter, no paid plans. Workers AI free tier = daily Neuron allocation.
 * Paid/OpenRouter entries are kept disabled for one-flag re-enable later.
 * aMiNo branding: Core (smart), Fast (speed), Vision (screenshots).
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
    // PAID via OpenRouter — user opted out of paid models. One-flag re-enable.
    id: "openai/gpt-4o",
    displayName: "GPT-4o (OpenRouter) [مدفوع]",
    provider: "openai-compat",
    supportsTools: true,
    supportsVision: true,
    reasoning: false,
    enabled: false,
    default: false,
  },
  {
    // OpenRouter fallback for gpt-4o — disabled together with it (free tier of
    // OpenRouter is rate-limited to ~50 req/day; Workers AI is more generous).
    id: "meta-llama/llama-3.3-70b-instruct",
    displayName: "Llama 3.3 70B (OpenRouter)",
    provider: "openai-compat",
    supportsTools: true,
    supportsVision: false,
    reasoning: false,
    enabled: false,
  },
  // --- aMiNo engines (100% FREE — Cloudflare Workers AI, no keys) ---
  {
    id: "@cf/openai/gpt-oss-20b",
    displayName: "aMiNo Core",
    provider: "cloudflare",
    supportsTools: true,
    supportsVision: false,
    reasoning: true,
    enabled: true,
    default: true,
  },
  {
    id: "@cf/meta/llama-3.3-70b-instruct-fp8-fast",
    displayName: "aMiNo Fast",
    provider: "cloudflare",
    supportsTools: true,
    supportsVision: false,
    reasoning: false,
    enabled: true,
  },
  {
    id: "@cf/meta/llama-4-scout-17b-16e-instruct",
    displayName: "aMiNo Vision",
    provider: "cloudflare",
    supportsTools: true,
    supportsVision: true,
    reasoning: false,
    enabled: true,
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
