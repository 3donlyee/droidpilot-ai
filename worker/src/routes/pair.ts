import type { Env } from "../env";
import { AuthManager } from "../auth/AuthManager";
import { PairingManager } from "../auth/PairingManager";
import { logger } from "../utils/logger";
import { jsonResponse, errorResponse } from "../utils/response";
import { checkRegister, checkPair } from "../utils/rateLimit";
import { extractBearer, clientIp, parseJsonBody } from "../utils/request";
import { z } from "zod";

/**
 * Pairing routes.
 *
 *   POST /api/device/register   — Android first-launch registration.
 *   POST /api/device/pair       — Web UI redeems a pairing code for a JWT.
 *   GET  /api/device/status     — Check current session (requires JWT).
 *
 * The register + pair endpoints are PUBLIC (no auth), but rate-limited
 * heavily because they accept unauthenticated input.
 */

const registerSchema = z.object({
  device_name: z.string().min(1).max(120),
  model: z.string().min(1).max(120),
  manufacturer: z.string().max(120).optional(),
  android_version: z.string().min(1).max(30),
  sdk_int: z.number().int().min(1).max(99).optional(),
  screen_width: z.number().int().min(1).max(10_000).optional(),
  screen_height: z.number().int().min(1).max(10_000).optional(),
});

const pairSchema = z.object({
  device_id: z.string().min(3).max(40),
  pairing_code: z.string().regex(/^\d{6}$/),
});

/** POST /api/device/register — Android calls this on first launch. */
export async function handleRegister(req: Request, env: Env): Promise<Response> {
  const ip = clientIp(req);
  const allowed = await checkRegister(env, ip);
  if (!allowed) {
    return errorResponse("rate_limited", 429, env, req);
  }

  const body = await parseJsonBody<unknown>(req);
  if (body === null) return errorResponse("invalid_json", 400, env, req);

  const parsed = registerSchema.safeParse(body);
  if (!parsed.success) {
    return errorResponse(
      `invalid_request: ${parsed.error.issues.map((i) => i.path.join(".") + ":" + i.message).join("; ")}`,
      400,
      env,
      req,
    );
  }

  const auth = new AuthManager(env);
  const pairing = new PairingManager(env, auth);

  try {
    const resp = await pairing.createPairing(parsed.data);
    // NOTE: device_secret is returned ONCE here. The Android app stores it
    // locally (encrypted) and uses it for WSS auth. It is never sent again.
    return jsonResponse(resp, 200, env, req);
  } catch (e) {
    logger.error("register error", { error: e instanceof Error ? e.message : String(e) });
    return errorResponse("internal_error", 500, env, req);
  }
}

/** POST /api/device/pair — Web UI redeems a pairing code for a JWT. */
export async function handlePair(req: Request, env: Env): Promise<Response> {
  const ip = clientIp(req);
  const allowed = await checkPair(env, ip);
  if (!allowed) {
    return errorResponse("rate_limited", 429, env, req);
  }

  const body = await parseJsonBody<unknown>(req);
  if (body === null) return errorResponse("invalid_json", 400, env, req);

  const parsed = pairSchema.safeParse(body);
  if (!parsed.success) {
    return errorResponse(
      `invalid_request: ${parsed.error.issues.map((i) => i.path.join(".") + ":" + i.message).join("; ")}`,
      400,
      env,
      req,
    );
  }

  const auth = new AuthManager(env);
  const pairing = new PairingManager(env, auth);

  try {
    const result = await pairing.redeemPairing(parsed.data.device_id, parsed.data.pairing_code);
    if (!result) {
      // Slight delay to make brute-force less attractive.
      await sleep(500);
      return errorResponse("invalid_pairing", 401, env, req);
    }
    return jsonResponse(
      {
        token: result.token,
        device_id: result.device.device_id,
        device_name: result.device.device_info.device_name,
      },
      200,
      env,
      req,
    );
  } catch (e) {
    logger.error("pair error", { error: e instanceof Error ? e.message : String(e) });
    return errorResponse("internal_error", 500, env, req);
  }
}

/** GET /api/device/status — check current session. */
export async function handleStatus(req: Request, env: Env): Promise<Response> {
  const token = extractBearer(req);
  if (!token) return errorResponse("unauthorized", 401, env, req);

  const auth = new AuthManager(env);
  const payload = await auth.verify(token);
  if (!payload) return errorResponse("invalid_or_expired_token", 401, env, req);

  const pairing = new PairingManager(env, auth);
  const record = await pairing.getPairingRecord(payload.deviceId);
  if (!record) return errorResponse("device_not_found", 404, env, req);

  return jsonResponse(
    {
      device_id: record.device_id,
      device_name: record.device_info.device_name,
      model: record.device_info.model,
      android_version: record.device_info.android_version,
      paired_at: record.created_at,
      session_expires_at: payload.exp,
    },
    200,
    env,
    req,
  );
}

function sleep(ms: number): Promise<void> {
  return new Promise((r) => setTimeout(r, ms));
}
