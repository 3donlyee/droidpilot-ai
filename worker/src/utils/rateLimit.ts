import type { Env } from "../env";

/**
 * Token-bucket rate limiter backed by Workers KV.
 *
 * KV is eventually consistent, so this is a SOFT rate limit — under burst it
 * may briefly allow a few extra requests. That's acceptable for our use case
 * (preventing abuse, not enforcing a tight SLA). For tighter limits, migrate
 * to a Durable Object per-key counter (Phase 7+).
 *
 * Bucket state in KV under key `rl:<rateLimitKey>`:
 *   { tokens: number, lastRefill: epochMs }
 * TTL: 120s after last update (auto-cleanup of stale buckets).
 *
 * Default: capacity 60, refill 60/min (1 token/sec).
 */
export interface RateLimitConfig {
  capacity: number;
  refillPerMinute: number;
}

export const DEFAULT_LIMITS = {
  chat: { capacity: 60, refillPerMinute: 60 }, // 60 req/min per IP
  pair: { capacity: 10, refillPerMinute: 10 }, // 10 req/min per IP
  register: { capacity: 30, refillPerMinute: 30 }, // 30 req/min per IP
};

interface BucketState {
  tokens: number;
  lastRefill: number; // epoch ms
}

/**
 * Attempt to consume 1 token from the bucket. Returns true if allowed,
 * false if rate-limited.
 */
export async function check(
  env: Env,
  rateLimitKey: string,
  config: RateLimitConfig = DEFAULT_LIMITS.chat,
): Promise<boolean> {
  const kvKey = `rl:${rateLimitKey}`;
  const now = Date.now();
  const refillPerMs = config.refillPerMinute / 60_000;

  let state: BucketState;
  const raw = await env.DEVICE_SESSIONS.get(kvKey);
  if (raw) {
    try {
      state = JSON.parse(raw) as BucketState;
      if (typeof state.tokens !== "number" || typeof state.lastRefill !== "number") {
        state = { tokens: config.capacity, lastRefill: now };
      }
    } catch {
      state = { tokens: config.capacity, lastRefill: now };
    }
  } else {
    state = { tokens: config.capacity, lastRefill: now };
  }

  // Refill
  const elapsed = Math.max(0, now - state.lastRefill);
  state.tokens = Math.min(config.capacity, state.tokens + elapsed * refillPerMs);
  state.lastRefill = now;

  if (state.tokens < 1) {
    // Save updated state so refill time advances (prevents burst after expiry).
    await env.DEVICE_SESSIONS.put(kvKey, JSON.stringify(state), {
      expirationTtl: 120,
    });
    return false;
  }

  state.tokens -= 1;
  await env.DEVICE_SESSIONS.put(kvKey, JSON.stringify(state), {
    expirationTtl: 120,
  });
  return true;
}

/** Convenience wrapper for the chat endpoint limit. */
export function checkChat(env: Env, ip: string): Promise<boolean> {
  return check(env, `chat:${ip}`, DEFAULT_LIMITS.chat);
}

/** Convenience wrapper for the pair endpoint limit. */
export function checkPair(env: Env, ip: string): Promise<boolean> {
  return check(env, `pair:${ip}`, DEFAULT_LIMITS.pair);
}

/** Convenience wrapper for the register endpoint limit. */
export function checkRegister(env: Env, ip: string): Promise<boolean> {
  return check(env, `register:${ip}`, DEFAULT_LIMITS.register);
}
