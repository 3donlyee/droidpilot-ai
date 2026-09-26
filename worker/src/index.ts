import { Router } from "itty-router";
import type { Env } from "./env";
import { AuthManager } from "./auth/AuthManager";
import { PairingManager } from "./auth/PairingManager";
import { DeviceRegistry } from "./device/DeviceRegistry";
import { logger } from "./utils/logger";
import { corsHeaders, preflightResponse, applyCors } from "./utils/cors";
import { jsonResponse, errorResponse } from "./utils/response";
import { handleRegister, handlePair, handleStatus } from "./routes/pair";
import { handleListModels, handleGetModel } from "./routes/models";
import { handleChat } from "./routes/chat";
import { handleDeviceWS } from "./routes/device";

/**
 * DroidPilot AI — Worker entry point.
 *
 * Exposes:
 *   - fetch:      All HTTP routes + WebSocket upgrades.
 *
 * NOTE: Cloudflare Workers do NOT support a separate `websockets` export on
 * the default Worker object. WebSocket connections are handled WITHIN `fetch`
 * by detecting the `Upgrade: websocket` header and returning a `Response`
 * with `webSocket: <client>` (see routes/device.ts). The Durable Objects API
 * does have a `webSocket` handler, but plain Workers do not — so we route
 * everything through `fetch`.
 *
 * Routes:
 *   GET  /                       — Health check.
 *   GET  /api/models             — List enabled models. (public)
 *   GET  /api/models/:id         — Get one model.      (public)
 *   POST /api/device/register    — Android first-launch. (public, rate-limited)
 *   POST /api/device/pair        — Web UI pairing.       (public, rate-limited)
 *   GET  /api/device/status      — Session status.       (auth)
 *   POST /api/chat               — SSE chat stream.      (auth, rate-limited)
 *   GET  /api/device/ws          — Device WebSocket.     (auth via query or in-band)
 *   GET  /api/device/connect     — Alias of /api/device/ws (backward compat with Android v1)
 */

// In-memory device registry — one per Worker isolate. Devices reconnect on
// isolate eviction; this is acceptable for the MVP.
const devices = new DeviceRegistry();

const router = Router();

router
  .get("/", () => jsonResponse({ ok: true, service: "droidpilot-ai", version: "0.1.0" }))
  .get("/api/models", handleListModels)
  .get("/api/models/:id", handleGetModel)
  .post("/api/device/register", handleRegister)
  .post("/api/device/pair", handlePair)
  .get("/api/device/status", handleStatus)
  .post("/api/chat", handleChat)
  .all("*", () => errorResponse("not_found", 404));

export interface WorkerCtx {
  devices: DeviceRegistry;
}

export default {
  async fetch(req: Request, env: Env, _executionCtx: ExecutionContext): Promise<Response> {
    // CORS preflight (short-circuit before anything else).
    if (req.method === "OPTIONS") {
      return preflightResponse(env, req);
    }

    const url = new URL(req.url);

    // WebSocket upgrade — handle separately (not via itty-router).
    const upgrade = req.headers.get("Upgrade");
    if (
      upgrade &&
      upgrade.toLowerCase() === "websocket" &&
      (url.pathname === "/api/device/ws" || url.pathname === "/api/device/connect")
    ) {
      try {
        const auth = new AuthManager(env);
        const pairing = new PairingManager(env, auth);
        return await handleDeviceWS(req, { env, devices, pairing });
      } catch (e) {
        logger.error("ws upgrade error", {
          error: e instanceof Error ? e.message : String(e),
        });
        return errorResponse("internal_error", 500, env, req);
      }
    }

    try {
      // Pass `(env, ctx)` as extra args to every handler. Each handler picks
      // what it needs from the front of the args list.
      const ctx: WorkerCtx = { devices };
      const response = await router
        .handle(req, env, ctx)
        .catch((e: unknown) => {
          logger.error("router error", {
            error: e instanceof Error ? e.message : String(e),
          });
          return errorResponse("internal_error", 500, env, req);
        });

      if (!response) {
        return applyCors(env, errorResponse("not_found", 404, env, req), req);
      }

      // Apply CORS to every response (including errors).
      return applyCors(env, response, req);
    } catch (e) {
      logger.error("unhandled error", {
        error: e instanceof Error ? e.message : String(e),
      });
      return applyCors(env, errorResponse("internal_error", 500, env, req), req);
    }
  },
} satisfies ExportedHandler<Env>;

// Re-export the CORS helper for ad-hoc usage (e.g. in tests).
export { corsHeaders };
