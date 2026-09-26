/**
 * DroidPilot AI — Worker environment bindings.
 *
 * These fields are populated by Cloudflare from:
 *  - bindings declared in `wrangler.jsonc` (AI, DEVICE_SESSIONS KV)
 *  - non-secret vars in `wrangler.jsonc` (MAX_TOOL_CALLS_PER_TURN, ...)
 *  - secrets set via `wrangler secret put` (DEVICE_AUTH_SECRET, OPENAI_API_KEY)
 *
 * No secrets are written to source files. They are only ever read here as
 * runtime fields.
 */
export interface Env {
  /** Workers AI binding — declared via `ai.binding = "AI"` in wrangler.jsonc. */
  AI: Ai;

  /** KV namespace for pairing records + rate-limit buckets. */
  DEVICE_SESSIONS: KVNamespace;

  /** Max number of tool calls per chat turn before the agent loop terminates. */
  MAX_TOOL_CALLS_PER_TURN: string;

  /** Default model id (must exist in MODEL_REGISTRY). */
  DEFAULT_MODEL_ID: string;

  /**
   * Comma-separated allowed origins for CORS, or `*` for dev.
   * In production, set this to your Web UI origin (e.g. `https://app.droidpilot.ai`).
   */
  ALLOWED_ORIGIN: string;

  /** HMAC secret for signing session JWTs. Set via `wrangler secret put`. */
  DEVICE_AUTH_SECRET: string;

  /**
   * Optional. OpenAI-compatible API key. Used only when the selected model's
   * provider is `openai` or `groq`. Set via `wrangler secret put` if needed.
   */
  OPENAI_API_KEY?: string;
}
