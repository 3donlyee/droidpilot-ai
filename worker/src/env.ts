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
  /** "workers-ai" (default) | "openai-compat" */
  AI_PROVIDER?: string;
  /** Only needed for the openai-compat provider (Groq, Ollama, OpenAI, CF REST…). Set via secret. */
  OPENAI_BASE_URL?: string;
  OPENAI_API_KEY?: string;
  OPENAI_MODEL_FALLBACK?: string;
  /** HMAC pepper for device secret hashes. Set via secret. */
  DEVICE_AUTH_SECRET?: string;
  /** "true" enables /api/debug/ai. Keep "false" in production. */
  DEBUG?: string;
}
