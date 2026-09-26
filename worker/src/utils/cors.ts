import type { Env } from "../env";

/**
 * CORS headers helper.
 *
 * In dev: ALLOWED_ORIGIN defaults to `*` (any origin).
 * In production: set ALLOWED_ORIGIN to your Web UI origin via
 *   - the `vars` block in wrangler.jsonc (non-secret)
 *   - the Cloudflare dashboard
 *   - `wrangler deploy --env production` with a `[env.production]` override
 *
 * For multi-origin production, set ALLOWED_ORIGIN to a comma-separated list
 * (e.g. `https://app.droidpilot.ai,https://staging.droidpilot.ai`). The
 * request's Origin header is matched against this list and reflected back,
 * or `null` is returned if not allowed.
 */
export function corsHeaders(env: Pick<Env, "ALLOWED_ORIGIN">, request?: Request): Record<string, string> {
  const configured = env.ALLOWED_ORIGIN ?? "*";
  const origin = request?.headers.get("Origin") ?? "";

  let allowedOrigin: string;
  if (configured === "*") {
    allowedOrigin = "*";
  } else {
    const list = configured
      .split(",")
      .map((s) => s.trim())
      .filter((s) => s.length > 0);
    allowedOrigin = list.includes(origin) ? origin : "null";
  }

  return {
    "Access-Control-Allow-Origin": allowedOrigin,
    "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type, Authorization",
    "Access-Control-Max-Age": "86400",
    "Access-Control-Expose-Headers": "Content-Type",
    "Vary": "Origin",
  };
}

/** Build a 204 No Content response for CORS preflight (OPTIONS) requests. */
export function preflightResponse(env: Pick<Env, "ALLOWED_ORIGIN">, request?: Request): Response {
  return new Response(null, {
    status: 204,
    headers: corsHeaders(env, request),
  });
}

/** Apply CORS headers to an existing Response (returns a new Response). */
export function applyCors(
  env: Pick<Env, "ALLOWED_ORIGIN">,
  response: Response,
  request?: Request,
): Response {
  const headers = corsHeaders(env, request);
  const newResp = new Response(response.body, response);
  for (const [k, v] of Object.entries(headers)) {
    newResp.headers.set(k, v);
  }
  return newResp;
}
