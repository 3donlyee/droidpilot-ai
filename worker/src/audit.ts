import type { Env } from "./env";

/**
 * Minimal audit/live log (KV ring buffer, last 300 entries).
 * NEVER log API keys, tokens, device secrets, or raw message content here —
 * only event types, ids, tool names and success flags.
 *
 * Set AUDIT_MODE="off" (var) to disable audit writes entirely (KV write budget).
 */
export async function audit(env: Env, event: string, info: Record<string, any> = {}): Promise<void> {
  if (env.AUDIT_MODE === "off") return;
  try {
    const raw = await env.KV_LOGS.get("audit");
    const arr: any[] = raw ? JSON.parse(raw) : [];
    arr.push({ ts: Date.now(), event, ...sanitize(info) });
    while (arr.length > 300) arr.shift();
    await env.KV_LOGS.put("audit", JSON.stringify(arr));
  } catch {
    // audit must never break the request path
  }
}

function sanitize(info: Record<string, any>): Record<string, any> {
  const out: Record<string, any> = {};
  const allowed = ["device", "turn", "tool", "ok", "error", "model", "state"];
  for (const k of allowed) if (k in info) out[k] = info[k];
  return out;
}

export async function getLogs(env: Env, limit = 100): Promise<Response> {
  const raw = await env.KV_LOGS.get("audit");
  const arr: any[] = raw ? JSON.parse(raw) : [];
  return new Response(
    JSON.stringify({ ok: true, logs: arr.slice(-limit) }),
    { headers: { "content-type": "application/json; charset=utf-8" } }
  );
}
