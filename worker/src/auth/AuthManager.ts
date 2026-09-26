import type { Env } from "../env";

/**
 * AuthManager — HMAC-signed session tokens (JWT-like, header.payload.signature).
 *
 * Uses the Web Crypto API (no external deps). The HMAC key is derived from
 * `env.DEVICE_AUTH_SECRET` (a 32-byte hex string set via `wrangler secret put`).
 *
 * Token payload: `{ deviceId, exp }` — 24h expiry by default.
 *
 * Tokens are NOT encrypted (only integrity-protected). They MUST be sent over
 * HTTPS to keep the payload confidential. The deviceId inside is what authorizes
 * the bearer to control that specific device.
 */
export interface SessionPayload {
  deviceId: string;
  /** Expiration epoch seconds. */
  exp: number;
}

const TOKEN_TTL_SECONDS = 24 * 60 * 60; // 24h
const ALG = "HS256";

export class AuthManager {
  constructor(private env: Env) {}

  async sign(
    payload: Omit<SessionPayload, "exp">,
    ttlSeconds: number = TOKEN_TTL_SECONDS,
  ): Promise<string> {
    const now = Math.floor(Date.now() / 1000);
    const full: SessionPayload = { deviceId: payload.deviceId, exp: now + ttlSeconds };
    const headerB64 = base64UrlJson({ alg: ALG, typ: "JWT" });
    const payloadB64 = base64UrlJson(full);
    const signingInput = `${headerB64}.${payloadB64}`;
    const key = await this.getKey();
    const sigBuf = await crypto.subtle.sign(
      "HMAC",
      key,
      new TextEncoder().encode(signingInput),
    );
    const sigB64 = base64UrlBytes(new Uint8Array(sigBuf));
    return `${signingInput}.${sigB64}`;
  }

  async verify(token: string): Promise<SessionPayload | null> {
    const parts = token.split(".");
    if (parts.length !== 3) return null;
    const [headerB64, payloadB64, sigB64] = parts;
    const signingInput = `${headerB64}.${payloadB64}`;

    let sigBuf: ArrayBuffer;
    try {
      sigBuf = base64UrlDecodeToBuffer(sigB64);
    } catch {
      return null;
    }

    const key = await this.getKey();
    let valid = false;
    try {
      valid = await crypto.subtle.verify(
        "HMAC",
        key,
        sigBuf,
        new TextEncoder().encode(signingInput),
      );
    } catch {
      return null;
    }
    if (!valid) return null;

    try {
      const json = new TextDecoder().decode(base64UrlDecodeToBuffer(payloadB64));
      const payload = JSON.parse(json) as SessionPayload;
      const now = Math.floor(Date.now() / 1000);
      if (typeof payload.exp !== "number" || payload.exp < now) return null;
      if (typeof payload.deviceId !== "string" || payload.deviceId.length === 0) return null;
      return payload;
    } catch {
      return null;
    }
  }

  private async getKey(): Promise<CryptoKey> {
    const enc = new TextEncoder();
    return crypto.subtle.importKey(
      "raw",
      enc.encode(this.env.DEVICE_AUTH_SECRET),
      { name: "HMAC", hash: "SHA-256" },
      false,
      ["sign", "verify"],
    );
  }
}

/** Encode a JSON-serializable value as base64url. */
function base64UrlJson(value: unknown): string {
  const json = JSON.stringify(value);
  const bytes = new TextEncoder().encode(json);
  return base64UrlBytes(bytes);
}

/** Encode bytes as base64url (no padding). */
function base64UrlBytes(bytes: Uint8Array): string {
  let bin = "";
  for (let i = 0; i < bytes.length; i++) {
    bin += String.fromCharCode(bytes[i]);
  }
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/** Decode a base64url string to an ArrayBuffer (throws on malformed input). */
function base64UrlDecodeToBuffer(input: string): ArrayBuffer {
  let s = input.replace(/-/g, "+").replace(/_/g, "/");
  while (s.length % 4 !== 0) s += "=";
  const bin = atob(s);
  const bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  return bytes.buffer;
}
