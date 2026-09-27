import type { Env } from "./env";
import { json, readJson, ipOf } from "./util";
import { publicModels } from "./models";
import { registerDevice, confirmPairing, verifyDevice, touchDevice, listDevices, heartbeat, publicDevice } from "./devices";
import { startTurn, pollCommand, applyDeviceResult, getTurnResponse, stopDeviceTurn } from "./agent";
import { publicAgents } from "./agents";
import { memorySnapshot, saveFact, forgetFact, listFacts } from "./memory";
import { rateLimit } from "./ratelimit";
import { audit, getLogs } from "./audit";

export default {
  async fetch(req: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    const url = new URL(req.url);
    const path = url.pathname;

    try {
      if (path.startsWith("/api/")) {
        return await handleApi(req, env, ctx, path);
      }
      // Static Web UI (same origin → no CORS needed)
      if (req.method === "GET") {
        return env.ASSETS.fetch(req);
      }
      return json({ ok: false, error: "NOT_FOUND" }, 404);
    } catch (e: any) {
      return json({ ok: false, error: "INTERNAL", message: String(e?.message ?? e).slice(0, 300) }, 500);
    }
  },
};

async function handleApi(req: Request, env: Env, ctx: ExecutionContext, path: string): Promise<Response> {
  const method = req.method;

  // ---------------------------------------------------------- public reads
  if (method === "GET" && path === "/api/health") {
    return json({ ok: true, service: "amino", time: Date.now() });
  }
  if (method === "GET" && path === "/api/models") {
    return json({ ok: true, models: publicModels() });
  }
  if (method === "GET" && path === "/api/agents") {
    return json({ ok: true, agents: publicAgents() });
  }
  if (method === "GET" && path === "/api/devices") {
    return listDevices(env);
  }
  if (method === "GET" && path === "/api/logs") {
    return getLogs(env, 100);
  }

  // ------------------------------------------------------------- pairing
  if (method === "POST" && path === "/api/device/register") {
    return registerDevice(env, req);
  }
  if (method === "POST" && path === "/api/pair/confirm") {
    return confirmPairing(env, req);
  }

  // ------------------------------------------------- device-authenticated
  if (path === "/api/device/poll" || path === "/api/device/result" || path === "/api/device/heartbeat") {
    const rec = await verifyDevice(env, req);
    if (!rec) {
      await audit(env, "auth_failed", { device: req.headers.get("x-device-id") ?? "?" });
      return json({ ok: false, error: "UNAUTHORIZED" }, 401);
    }
    await touchDevice(env, rec);

    if (method === "GET" && path === "/api/device/poll") {
      const wait = parseInt(new URL(req.url).searchParams.get("wait") ?? "20", 10);
      return pollCommand(env, rec, Number.isFinite(wait) ? wait : 20);
    }
    if (method === "POST" && path === "/api/device/result") {
      const body = await readJson(req);
      if (!body?.id) return json({ ok: false, error: "INVALID_ARGUMENT" }, 400);
      return applyDeviceResult(env, ctx, rec, {
        id: String(body.id),
        success: !!body.success,
        data: body.data ?? null,
        error: body.error ?? null,
        error_code: body.error_code ?? null,
      });
    }
    if (method === "POST" && path === "/api/device/heartbeat") {
      return heartbeat(env, req, rec);
    }
  }

  // -------------------------------------------------------------- chat
  if (method === "POST" && path === "/api/chat") {
    if (!(await rateLimit(env, `chat:${ipOf(req)}`, 10, 60))) {
      return json({ ok: false, error: "RATE_LIMITED", hint: "Max 10 chats per minute" }, 429);
    }
    const body = await readJson(req);
    const message = String(body?.message ?? "").trim();
    const deviceId = String(body?.device_id ?? "");
    if (!message) return json({ ok: false, error: "EMPTY_MESSAGE" }, 400);
    if (!deviceId) return json({ ok: false, error: "NO_DEVICE", hint: "Pair a device first" }, 400);

    const raw = await env.KV_DEVICES.get(`device:${deviceId}`);
    if (!raw) return json({ ok: false, error: "DEVICE_NOT_FOUND" }, 404);
    const rec = JSON.parse(raw);
    if (rec.status !== "active") return json({ ok: false, error: "DEVICE_NOT_PAIRED" }, 409);

    return startTurn(env, ctx, deviceId, message, body?.model_id ?? null, body?.agent_id ?? null, body?.source ?? null);
  }

  // ------------------------------------------------- aMiNo: stop / memory
  if (method === "POST" && path === "/api/turn/stop") {
    const body = await readJson(req);
    const deviceId = String(body?.device_id ?? "");
    if (!deviceId) return json({ ok: false, error: "INVALID_ARGUMENT" }, 400);
    await audit(env, "stop_requested", { device: deviceId, source: String(body?.source ?? "web") });
    return stopDeviceTurn(env, deviceId);
  }

  if (method === "GET" && path.startsWith("/api/memory/")) {
    const deviceId = decodeURIComponent(path.slice("/api/memory/".length));
    if (!deviceId) return json({ ok: false, error: "INVALID_ARGUMENT" }, 400);
    const snap = await memorySnapshot(env, deviceId);
    return json(snap);
  }

  if (method === "POST" && path === "/api/memory/add") {
    const body = await readJson(req);
    const deviceId = String(body?.device_id ?? "");
    const k = String(body?.key ?? "").trim();
    const v = String(body?.value ?? "").trim();
    if (!deviceId || !k || !v) return json({ ok: false, error: "INVALID_ARGUMENT" }, 400);
    await saveFact(env, deviceId, k, v);
    return json({ ok: true, facts: await listFacts(env, deviceId) });
  }

  if (method === "POST" && path === "/api/memory/delete") {
    const body = await readJson(req);
    const deviceId = String(body?.device_id ?? "");
    const k = String(body?.key ?? "").trim();
    if (!deviceId || !k) return json({ ok: false, error: "INVALID_ARGUMENT" }, 400);
    const gone = await forgetFact(env, deviceId, k);
    return json({ ok: true, forgotten: gone, facts: await listFacts(env, deviceId) });
  }

  // ------------------------------------------------------------- turns
  if (method === "GET" && path.startsWith("/api/turn/")) {
    const turnId = path.slice("/api/turn/".length);
    return getTurnResponse(env, turnId);
  }

  // ------------------------------------------------------------- debug
  if (method === "POST" && path === "/api/debug/ai" && env.DEBUG === "true") {
    const body = await readJson(req);
    const { getProvider } = await import("./ai/provider");
    const { TOOL_SCHEMAS } = await import("./tools");
    const provider = getProvider(env, body?.model_id);
    const messages = Array.isArray(body?.messages)
      ? body.messages
      : [
          { role: "system", content: "You are a diagnostic agent. Use the tools available to test tool-calling." },
          { role: "user", content: String(body?.prompt ?? "Call get_device_info now.") },
        ];
    const result = await provider.chat(body?.model_id ?? "@cf/openai/gpt-oss-20b", messages, TOOL_SCHEMAS);
    return json({
      ok: true,
      provider: provider.name,
      style: result.style ?? null,
      text: result.text,
      toolCalls: result.toolCalls,
      raw_type: typeof result.raw,
      raw_preview: JSON.stringify(result.raw)?.slice(0, 1200) ?? null,
    });
  }

  return json({ ok: false, error: "NOT_FOUND" }, 404);
}
