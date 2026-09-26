import type { Env } from "../env";
import type { DeviceRegistry } from "../device/DeviceRegistry";
import { AuthManager } from "../auth/AuthManager";
import { PairingManager } from "../auth/PairingManager";
import { logger } from "../utils/logger";

/**
 * Device WebSocket upgrade handler.
 *
 * Routes (both supported, `/api/device/ws` is the canonical spec path,
 * `/api/device/connect` is the alias used by Android app v1 for backward
 * compatibility):
 *   GET /api/device/ws        — WebSocket upgrade
 *   GET /api/device/connect    — alias
 *
 * Auth: EITHER
 *   - Query string: `?device_id=...&device_secret=...` (Android v1 style), OR
 *   - First in-band message: `{type:"auth", deviceId, secret}` (spec style).
 *
 * After successful auth, the WS is registered with the DeviceRegistry and
 * becomes the command channel for that device.
 */
export interface DeviceWSContext {
  env: Env;
  devices: DeviceRegistry;
  pairing: PairingManager;
}

const AUTH_TIMEOUT_MS = 10_000;

export async function handleDeviceWS(req: Request, ctx: DeviceWSContext): Promise<Response> {
  const upgrade = req.headers.get("Upgrade");
  if (!upgrade || upgrade.toLowerCase() !== "websocket") {
    return new Response("Expected Upgrade: websocket", {
      status: 426,
      headers: { "Allow": "GET" },
    });
  }

  const pair = new WebSocketPair();
  const client = pair[0];
  const server = pair[1];

  server.accept();

  const url = new URL(req.url);

  // 1) Try query-string auth (backward compat with Android v1).
  const qsDeviceId = url.searchParams.get("device_id");
  const qsSecret = url.searchParams.get("device_secret");

  let authenticated = false;
  let deviceId: string | null = null;

  if (qsDeviceId && qsSecret) {
    const record = await ctx.pairing.validateDeviceSecret(qsDeviceId, qsSecret);
    if (record) {
      authenticated = true;
      deviceId = qsDeviceId;
      finalizeAuth(server, deviceId, ctx.devices);
      try {
        server.send(JSON.stringify({ type: "ready" }));
      } catch {
        /* noop */
      }
    } else {
      logger.warn("ws auth: invalid query-string credentials", { deviceId: qsDeviceId });
      try {
        server.send(
          JSON.stringify({
            type: "error",
            code: "AUTH_FAILED",
            message: "Invalid device_id or device_secret",
          }),
        );
        server.close(4001, "auth_failed");
      } catch {
        /* noop */
      }
      return new Response(null, { status: 101, webSocket: client });
    }
  }

  // 2) Otherwise, wait for an in-band auth message.
  if (!authenticated) {
    const authTimeout = setTimeout(() => {
      try {
        server.send(
          JSON.stringify({
            type: "error",
            code: "AUTH_TIMEOUT",
            message: "No auth received within 10s",
          }),
        );
        server.close(4001, "auth_timeout");
      } catch {
        /* noop */
      }
    }, AUTH_TIMEOUT_MS);

    const onAuthMessage = async (event: MessageEvent) => {
      try {
        const data =
          typeof event.data === "string" ? JSON.parse(event.data) : null;
        if (!data || typeof data !== "object") return;
        const d = data as { type?: string; deviceId?: string; secret?: string };
        if (d.type !== "auth") return;

        const dId = String(d.deviceId ?? "");
        const secret = String(d.secret ?? "");
        if (!dId || !secret) {
          server.send(
            JSON.stringify({
              type: "error",
              code: "INVALID_AUTH",
              message: "Missing deviceId or secret",
            }),
          );
          server.close(4001, "invalid_auth");
          server.removeEventListener("message", onAuthMessage);
          return;
        }

        const record = await ctx.pairing.validateDeviceSecret(dId, secret);
        if (!record) {
          server.send(
            JSON.stringify({
              type: "error",
              code: "AUTH_FAILED",
              message: "Invalid deviceId or secret",
            }),
          );
          server.close(4001, "auth_failed");
          server.removeEventListener("message", onAuthMessage);
          return;
        }

        authenticated = true;
        deviceId = dId;
        clearTimeout(authTimeout);
        server.removeEventListener("message", onAuthMessage);
        finalizeAuth(server, deviceId, ctx.devices);
        try {
          server.send(JSON.stringify({ type: "auth_ok" }));
          server.send(JSON.stringify({ type: "ready" }));
        } catch {
          /* noop */
        }
      } catch (e) {
        logger.warn("ws auth parse error", {
          error: e instanceof Error ? e.message : String(e),
        });
      }
    };

    server.addEventListener("message", onAuthMessage);
  }

  return new Response(null, { status: 101, webSocket: client });
}

function finalizeAuth(server: WebSocket, deviceId: string, registry: DeviceRegistry): void {
  registry.register(deviceId, server);
  logger.info("device ws authenticated", { deviceId });
}
