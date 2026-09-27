import type { Env } from "./env";
import type { DeviceRecord } from "./types";
import { json, randomId, randomDigits, randomHex, sha256Hex, now, ipOf } from "./util";
import { rateLimit } from "./ratelimit";

const PAIRING_TTL_MS = 15 * 60 * 1000; // PIN valid for 15 minutes

function pepper(env: Env): string {
  return env.DEVICE_AUTH_SECRET ?? "insecure-dev-pepper-change-me";
}

async function hashSecret(env: Env, secret: string): Promise<string> {
  return sha256Hex(`${pepper(env)}:${secret}`);
}

// ------------------------------------------------------------ registration

export async function registerDevice(env: Env, req: Request): Promise<Response> {
  if (!(await rateLimit(env, `register:${ipOf(req)}`, 10, 3600))) {
    return json({ ok: false, error: "RATE_LIMITED" }, 429);
  }
  const body: any = await req.json().catch(() => ({}));
  if (!env.DEVICE_AUTH_SECRET) {
    return json({ ok: false, error: "SERVER_MISCONFIGURED", hint: "DEVICE_AUTH_SECRET secret is not set" }, 500);
  }

  let deviceId = `DROID-${randomId(4)}`;
  // Ensure uniqueness (retry is practically never needed).
  if (await env.KV_DEVICES.get(`device:${deviceId}`)) {
    deviceId = `DROID-${randomId(4)}`;
  }

  const pairingCode = randomDigits(6);
  const deviceSecret = randomHex(24);

  const record: DeviceRecord = {
    device_id: deviceId,
    device_name: String(body?.device_name ?? "").slice(0, 60),
    model: String(body?.model ?? "").slice(0, 80),
    android_version: String(body?.android_version ?? "").slice(0, 20),
    app_version: String(body?.app_version ?? "").slice(0, 20),
    secret_hash: await hashSecret(env, deviceSecret),
    pairing_code: pairingCode,
    pairing_expires: now() + PAIRING_TTL_MS,
    status: "pending",
    created_at: now(),
  };

  await env.KV_DEVICES.put(`device:${deviceId}`, JSON.stringify(record), { expirationTtl: 86400 });
  await env.KV_DEVICES.put(`pair:${pairingCode}`, deviceId, { expirationTtl: 900 });

  // The plaintext device_secret is returned EXACTLY ONCE and stored encrypted on the phone.
  return json({
    ok: true,
    device_id: deviceId,
    pairing_code: pairingCode,
    device_secret: deviceSecret,
    poll_wait_seconds: 20,
    message: "أدخل الرمز في موقع aMiNo لإكمال الاقتران",
  });
}

// ---------------------------------------------------------------- pairing

export async function confirmPairing(env: Env, req: Request): Promise<Response> {
  if (!(await rateLimit(env, `pair:${ipOf(req)}`, 6, 900))) {
    return json({ ok: false, error: "RATE_LIMITED", hint: "Too many pairing attempts" }, 429);
  }
  const body: any = await req.json().catch(() => ({}));
  const deviceId = String(body?.device_id ?? "");
  const code = String(body?.pairing_code ?? "");
  if (!deviceId || !/^\d{6}$/.test(code)) {
    return json({ ok: false, error: "INVALID_ARGUMENT" }, 400);
  }

  const raw = await env.KV_DEVICES.get(`device:${deviceId}`);
  if (!raw) return json({ ok: false, error: "DEVICE_NOT_FOUND" }, 404);
  const rec: DeviceRecord = JSON.parse(raw);

  if (rec.status !== "pending") return json({ ok: false, error: "ALREADY_PAIRED" }, 409);
  if (!rec.pairing_code || rec.pairing_code !== code) return json({ ok: false, error: "WRONG_PIN" }, 403);
  if ((rec.pairing_expires ?? 0) < now()) return json({ ok: false, error: "PAIRING_EXPIRED", hint: "Press Connect again in the app" }, 410);

  rec.status = "active";
  rec.last_seen = now();
  rec.pairing_code = undefined; // one-time secret: gone after pairing
  rec.pairing_expires = undefined;
  await env.KV_DEVICES.put(`device:${deviceId}`, JSON.stringify(recordTtl(rec)));
  await env.KV_DEVICES.delete(`pair:${code}`);

  return json({
    ok: true,
    device: {
      device_id: rec.device_id,
      device_name: rec.device_name,
      model: rec.model,
      android_version: rec.android_version,
      status: rec.status,
    },
  });
}

function recordTtl(rec: DeviceRecord): DeviceRecord {
  return rec;
}

// ------------------------------------------------------------------- auth

export function deviceCredentials(req: Request): { id: string; secret: string } | null {
  const id = req.headers.get("x-device-id");
  const secret = req.headers.get("x-device-secret");
  if (id && secret) return { id, secret };
  const auth = req.headers.get("authorization");
  if (auth?.startsWith("Bearer ")) {
    const [id2, secret2] = auth.slice(7).split(":", 2);
    if (id2 && secret2) return { id: id2, secret: secret2 };
  }
  return null;
}

export async function verifyDevice(env: Env, req: Request): Promise<DeviceRecord | null> {
  const creds = deviceCredentials(req);
  if (!creds) return null;
  const raw = await env.KV_DEVICES.get(`device:${creds.id}`);
  if (!raw) return null;
  const rec: DeviceRecord = JSON.parse(raw);
  if (rec.status !== "active") return null;
  const hash = await hashSecret(env, creds.secret);
  if (hash !== rec.secret_hash) return null;
  return rec;
}

/** Throttled presence update — max 1 KV write / 30 min / device.
 * Free-tier KV allows only 1,000 writes/day: a 24/7 polling device must NOT
 * write per poll (the old 2-min throttle burned 1,440 writes/day alone and
 * exhausted the daily quota → HTTP 500 on every write path). */
export async function touchDevice(env: Env, rec: DeviceRecord): Promise<void> {
  try {
    const seenKey = `seen:${rec.device_id}`;
    if (await env.KV_DEVICES.get(seenKey)) return;
    rec.last_seen = now();
    await env.KV_DEVICES.put(`device:${rec.device_id}`, JSON.stringify({ ...rec, last_seen: rec.last_seen }));
    await env.KV_DEVICES.put(seenKey, "1", { expirationTtl: 1800 });
  } catch {
    // never break the poll path on KV write limits — presence is best-effort
  }
}

// ------------------------------------------------------------------- list

export function publicDevice(rec: DeviceRecord) {
  return {
    device_id: rec.device_id,
    device_name: rec.device_name,
    model: rec.model,
    android_version: rec.android_version,
    app_version: rec.app_version,
    status: rec.status,
    last_seen: rec.last_seen ?? null,
    created_at: rec.created_at,
    connected: rec.status === "active" && (rec.last_seen ?? 0) > now() - 35 * 60_000,
    // presence is throttled to 1 write / 30 min → the online window is coarse by design
  };
}

export async function listDevices(env: Env): Promise<Response> {
  const out: any[] = [];
  // KV list is prefix-based; device ids are few in this deployment.
  let cursor: string | undefined;
  do {
    const page = await env.KV_DEVICES.list({ prefix: "device:", cursor });
    for (const key of page.keys) {
      const raw = await env.KV_DEVICES.get(key.name);
      if (raw) {
        try {
          out.push(publicDevice(JSON.parse(raw) as DeviceRecord));
        } catch {}
      }
    }
    cursor = page.list_complete ? undefined : page.cursor;
  } while (cursor);
  out.sort((a, b) => (b.created_at ?? 0) - (a.created_at ?? 0));
  return json({ ok: true, devices: out });
}

/** Heartbeat is write-free — presence is maintained by the throttled touchDevice.
 * (Writing the device record per heartbeat would burn the free-tier KV daily quota.) */
export async function heartbeat(env: Env, req: Request, rec: DeviceRecord): Promise<Response> {
  void req;
  void rec;
  return json({ ok: true, server_time: now() });
}
