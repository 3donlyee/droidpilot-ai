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
 * TIERING (anti-quota design):
 *   Tier 1 — OpenRouter :free models (requiresKey). When the user's OpenRouter
 *           key is set as a secret these activate automatically and take the
 *           default slot → ZERO Cloudflare Neurons consumed.
 *   Tier 2 — aMiNo Core/Fast/Vision on Workers AI free tier (fallback when
 *           OpenRouter rate-limits or the key is absent).
 *   chatWithFailover() walks this chain automatically per call.
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
  /** When true the model only activates if the OPENAI_API_KEY secret exists. */
  requiresKey?: boolean;
}

export const MODELS: ModelEntry[] = [
  // --- Tier 1: OpenRouter FREE models (activate automatically once the key secret exists) ---
  {
    id: "nvidia/nemotron-3-super-120b-a12b:free",
    displayName: "aMiNo Ultra (مجاني عبر OpenRouter)",
    provider: "openai-compat",
    supportsTools: true,
    supportsVision: false,
    reasoning: false,
    enabled: true,
    default: true,
    requiresKey: true,
  },
  {
    id: "qwen/qwen3.8-27b:free",
    displayName: "aMiNo عربي (مجاني عبر OpenRouter)",
    provider: "openai-compat",
    supportsTools: true,
    supportsVision: false,
    reasoning: false,
    enabled: true,
    requiresKey: true,
  },
  {
    id: "google/gemma-4-31b-it:free",
    displayName: "aMiNo Lite (مجاني عبر OpenRouter)",
    provider: "openai-compat",
    supportsTools: true,
    supportsVision: false,
    reasoning: false,
    enabled: true,
    requiresKey: true,
  },
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
  // --- Tier 2: aMiNo engines (100% FREE — Cloudflare Workers AI, no keys) ---
  {
    id: "@cf/openai/gpt-oss-20b",
    displayName: "aMiNo Core",
    provider: "cloudflare",
    supportsTools: true,
    supportsVision: false,
    reasoning: true,
    enabled: true,
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

function keyAvailable(env?: { OPENAI_API_KEY?: string }): boolean {
  return !!(env && env.OPENAI_API_KEY && env.OPENAI_API_KEY.length > 0);
}

export function isModelUsable(m: ModelEntry, env?: { OPENAI_API_KEY?: string }): boolean {
  return m.enabled && (!m.requiresKey || keyAvailable(env));
}

/**
 * Default model — key-aware: an OpenRouter free model is the default when the
 * key secret exists; otherwise aMiNo Core (Workers AI) stays the default.
 */
export function defaultModel(env?: { OPENAI_API_KEY?: string }): ModelEntry {
  const def = MODELS.find((m) => m.default && isModelUsable(m, env));
  if (def) return def;
  return MODELS.find((m) => isModelUsable(m, env) && !m.requiresKey) ?? MODELS.find((m) => isModelUsable(m, env))!;
}

/** Public projection — never leaks internals. */
export function publicModels(env?: { OPENAI_API_KEY?: string }): any[] {
  return MODELS.map((m) => ({
    id: m.id,
    displayName: m.displayName,
    provider: m.provider,
    capabilities: {
      reasoning: m.reasoning,
      supportsTools: m.supportsTools,
      supportsVision: m.supportsVision,
    },
    status: !m.enabled ? "disabled" : m.requiresKey && !keyAvailable(env) ? "needs-key" : "available",
    default: !!m.default && isModelUsable(m, env),
  }));
}
