import type { Env } from "../env";
import { AuthManager, type SessionPayload } from "./AuthManager";
import { logger } from "../utils/logger";

/**
 * PairingManager — issues device ids, pairing codes, and device secrets.
 *
 * Flow:
 *   1. Android calls POST /api/device/register on first launch.
 *      → createPairing() returns { device_id, device_secret, pairing_code }
 *   2. The pairing_code (6-digit PIN, 10-min TTL) is shown to the user.
 *   3. The user enters device_id + pairing_code in the Web UI.
 *      → Web UI calls POST /api/device/pair
 *      → redeemPairing() verifies the code, stores a long-lived device record
 *        in KV, and returns a 24h session JWT (signed via AuthManager).
 *   4. All subsequent requests carry the JWT. The Worker resolves it back to
 *      a deviceId via AuthManager.verify().
 *   5. The Android device authenticates its WebSocket via device_secret
 *      (validateDeviceSecret) — either as a query string param (legacy)
 *      or as an in-band `{type:"auth", deviceId, secret}` message.
 */

const PAIRING_TTL_SECONDS = 10 * 60; // 10 minutes

export interface RegisterRequest {
  device_name: string;
  model: string;
  manufacturer?: string;
  android_version: string;
  sdk_int?: number;
  screen_width?: number;
  screen_height?: number;
}

export interface RegisterResponse {
  device_id: string;
  device_secret: string;
  pairing_code: string;
  expires_in_seconds: number;
}

export interface PairingRecord {
  device_id: string;
  device_secret: string;
  pairing_code: string;
  device_info: RegisterRequest;
  created_at: number;
}

export interface RedeemResult {
  token: string;
  device: PairingRecord;
}

export class PairingManager {
  constructor(private env: Env, private auth: AuthManager) {}

  /** Android first launch: create a new pairing. Stores the record in KV with 10-min TTL. */
  async createPairing(deviceInfo: RegisterRequest): Promise<RegisterResponse> {
    const deviceId = `DROID-${randomHex(2).toUpperCase()}`;
    const deviceSecret = randomHex(32);
    const pairingCode = randomPin(6);

    const record: PairingRecord = {
      device_id: deviceId,
      device_secret: deviceSecret,
      pairing_code: pairingCode,
      device_info: deviceInfo,
      created_at: Date.now(),
    };

    await this.env.DEVICE_SESSIONS.put(
      kvPairingKey(deviceId),
      JSON.stringify(record),
      { expirationTtl: PAIRING_TTL_SECONDS },
    );

    logger.info("pairing created", { deviceId });
    return {
      device_id: deviceId,
      device_secret: deviceSecret,
      pairing_code: pairingCode,
      expires_in_seconds: PAIRING_TTL_SECONDS,
    };
  }

  /** Verify a pairing code without redeeming it (used for status checks). */
  async verifyPairing(deviceId: string, code: string): Promise<boolean> {
    const record = await this.getPairingRecord(deviceId);
    if (!record) return false;
    return record.pairing_code === code;
  }

  /** Redeem a pairing: verifies the code, stores a long-lived device record, issues a 24h JWT. */
  async redeemPairing(deviceId: string, code: string): Promise<RedeemResult | null> {
    const record = await this.getPairingRecord(deviceId);
    if (!record) return null;
    if (record.pairing_code !== code || record.pairing_code === "[redeemed]") return null;

    // Persist a long-lived device record (without the active pairing code)
    // so the WSS handshake can validate the device_secret afterward.
    const stored: PairingRecord = { ...record, pairing_code: "[redeemed]" };
    await this.env.DEVICE_SESSIONS.put(kvDeviceKey(deviceId), JSON.stringify(stored));

    const token = await this.auth.sign({ deviceId });
    logger.info("pairing redeemed", { deviceId });
    return { token, device: stored };
  }

  /** Read the pairing record (from short-lived pairing key, falling back to long-lived device key). */
  async getPairingRecord(deviceId: string): Promise<PairingRecord | null> {
    const pairingRaw = await this.env.DEVICE_SESSIONS.get(kvPairingKey(deviceId));
    if (pairingRaw) {
      try {
        return JSON.parse(pairingRaw) as PairingRecord;
      } catch {
        // fall through
      }
    }
    const deviceRaw = await this.env.DEVICE_SESSIONS.get(kvDeviceKey(deviceId));
    if (deviceRaw) {
      try {
        return JSON.parse(deviceRaw) as PairingRecord;
      } catch {
        // fall through
      }
    }
    return null;
  }

  /** Validate the device_secret for WSS auth. Returns the PairingRecord on success. */
  async validateDeviceSecret(
    deviceId: string,
    deviceSecret: string,
  ): Promise<PairingRecord | null> {
    const record = await this.getPairingRecord(deviceId);
    if (!record) return null;
    if (!deviceSecret || record.device_secret !== deviceSecret) return null;
    return record;
  }

  /** Issue a fresh session token for an existing paired device (e.g. token refresh). */
  async reissueToken(deviceId: string): Promise<{ token: string; payload: SessionPayload } | null> {
    const record = await this.getPairingRecord(deviceId);
    if (!record) return null;
    const token = await this.auth.sign({ deviceId });
    const payload = (await this.auth.verify(token)) as SessionPayload | null;
    if (!payload) return null;
    return { token, payload };
  }
}

// ---------------------------------------------------------------------------
// KV key helpers
// ---------------------------------------------------------------------------

export function kvPairingKey(deviceId: string): string {
  return `pairing:${deviceId}`;
}

export function kvDeviceKey(deviceId: string): string {
  return `device:${deviceId}`;
}

// ---------------------------------------------------------------------------
// Random generators (Web Crypto)
// ---------------------------------------------------------------------------

function randomHex(byteLength: number): string {
  const bytes = new Uint8Array(byteLength);
  crypto.getRandomValues(bytes);
  return Array.from(bytes)
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

function randomPin(digits: number): string {
  const bytes = new Uint8Array(digits);
  crypto.getRandomValues(bytes);
  return Array.from(bytes)
    .map((b) => (b % 10).toString())
    .join("");
}
