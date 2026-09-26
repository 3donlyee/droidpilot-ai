import { corsHeaders } from "./cors";
import type { Env } from "../env";

/** JSON response helper. */
export function jsonResponse(
  data: unknown,
  status: number = 200,
  env?: Pick<Env, "ALLOWED_ORIGIN">,
  request?: Request,
): Response {
  const headers: Record<string, string> = {
    "Content-Type": "application/json; charset=utf-8",
  };
  if (env) {
    Object.assign(headers, corsHeaders(env, request));
  }
  return new Response(JSON.stringify(data), { status, headers });
}

/** Error response helper. */
export function errorResponse(
  message: string,
  status: number = 400,
  env?: Pick<Env, "ALLOWED_ORIGIN">,
  request?: Request,
): Response {
  return jsonResponse({ error: message }, status, env, request);
}
