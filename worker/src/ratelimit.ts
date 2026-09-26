import type { Env } from "./env";

/**
 * Fixed-window rate limiter on top of KV.
 *
 * Note: KV allows ~1 write/sec/key, so bursts may be under-counted (fail-open).
 * That is the intended trade-off for the MVP — see docs/ARCHITECTURE.md for the
 * Durable Object upgrade path.
 */
export async function rateLimit(
  env: Env,
  scope: string,
  limit: number,
  windowSeconds: number
): Promise<boolean> {
  const bucket = Math.floor(Date.now() / (windowSeconds * 1000));
  const key = `rate:${scope}:${bucket}`;
  let current = 0;
  try {
    current = parseInt((await env.KV_RATE.get(key)) ?? "0", 10);
  } catch {
    return true; // fail-open on KV errors
  }
  if (current >= limit) return false;
  try {
    await env.KV_RATE.put(key, String(current + 1), { expirationTtl: windowSeconds + 60 });
  } catch {}
  return true;
}
