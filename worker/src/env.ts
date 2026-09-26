export interface Env {
  /** Workers AI binding */
  AI: any;
  /** Static assets (Web UI) */
  ASSETS: Fetcher;

  KV_DEVICES: KVNamespace;
  KV_TURNS: KVNamespace;
  KV_RATE: KVNamespace;
  KV_LOGS: KVNamespace;

  MAX_TOOL_CALLS_PER_TURN?: string;
  /** "workers-ai" (default) | "openai-compat" — usually routed per-model via the registry instead */
  AI_PROVIDER?: string;
  /** Public endpoint of the OpenAI-compatible API (e.g. https://openrouter.ai/api/v1). Set as a var. */
  OPENAI_BASE_URL?: string;
  /** Provider API key (OpenRouter: sk-or-v1-…). Set via secret — NEVER in files. */
  OPENAI_API_KEY?: string;
  OPENAI_MODEL_FALLBACK?: string;
  /** Model used when the primary is region-blocked (HTTP 403 "not available in your region"). */
  OPENAI_REGION_FALLBACK?: string;
  /** Optional OpenRouter app-attribution overrides */
  OPENROUTER_SITE_URL?: string;
  OPENROUTER_SITE_NAME?: string;
  /** HMAC pepper for device secret hashes. Set via secret. */
  DEVICE_AUTH_SECRET?: string;
  /** "true" enables /api/debug/ai. Keep "false" in production. */
  DEBUG?: string;
  /** "off" disables audit KV writes (free-tier KV write budget). Default: on. */
  AUDIT_MODE?: string;
}
