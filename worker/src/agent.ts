import type { Env } from "./env";
import type { Turn, Command, DeviceResult } from "./types";
import { json, randomId, now, safeJsonParse } from "./util";
import { TOOL_SCHEMAS } from "./tools";
import { getProvider } from "./ai/provider";
import { getModel, defaultModel } from "./models";
import { audit } from "./audit";

const DEVICE_TIMEOUT_MS = 45_000;   // device must return a result within 45s of a tool call
const AI_TIMEOUT_MS = 90_000;       // safety net for stuck "thinking" turns

function maxCalls(env: Env): number {
  const v = parseInt(env.MAX_TOOL_CALLS_PER_TURN ?? "20", 10);
  return Number.isFinite(v) && v > 0 ? Math.min(v, 100) : 20;
}

// ------------------------------------------------------------- KV helpers

export async function saveTurn(env: Env, turn: Turn): Promise<void> {
  turn.updated_at = now();
  await env.KV_TURNS.put(`turn:${turn.id}`, JSON.stringify(turn), { expirationTtl: 86400 });
}

async function loadTurn(env: Env, turnId: string): Promise<Turn | null> {
  const raw = await env.KV_TURNS.get(`turn:${turnId}`);
  return raw ? (JSON.parse(raw) as Turn) : null;
}

async function clearDeviceTurn(env: Env, deviceId: string): Promise<void> {
  await env.KV_DEVICES.delete(`turn:${deviceId}`);
  await env.KV_DEVICES.delete(`pendingcmd:${deviceId}`);
}

function systemPrompt(deviceInfo?: { model?: string; android_version?: string }): string {
  const dev = deviceInfo?.model ? ` Target device: ${deviceInfo.model} (Android ${deviceInfo.android_version ?? "?"}).` : "";
  return [
    "You are DroidPilot AI, an agent that controls the user's Android phone through tools executed on the device.",
    dev,
    "",
    "Rules:",
    "1. Issue ONE tool call per step, then wait for its result before deciding the next action.",
    "2. Priority: accessibility tools first. Use take_screenshot ONLY as a last resort.",
    "3. After open_app, verify with get_current_package.",
    "4. Before tapping, call get_screen_nodes and pick the correct element_id.",
    "5. If a tool fails (e.g. ELEMENT_NOT_FOUND), re-observe with get_screen_nodes and try a different strategy. Never repeat the exact same failing call more than twice.",
    "6. Never attempt destructive or unsafe actions. run_shell is policy-restricted; blocked commands are rejected by the device.",
    "7. The user may write in Arabic or English — always reply in the user's language, briefly and clearly.",
    "8. When the goal is achieved, reply with a short confirmation and stop.",
  ].join("\n");
}

// -------------------------------------------------------------- start turn

export async function startTurn(
  env: Env,
  ctx: ExecutionContext,
  deviceId: string,
  message: string,
  modelId?: string | null
): Promise<Response> {
  const model = (modelId && getModel(modelId)) || defaultModel();
  if (!model.supportsTools) {
    return json({ ok: false, error: "MODEL_NO_TOOLS", hint: "Pick a model with Tool Calling enabled" }, 400);
  }

  // Expire a stale active turn before deciding the device is busy.
  const activeId = await env.KV_DEVICES.get(`turn:${deviceId}`);
  if (activeId) {
    const active = await loadTurn(env, activeId);
    if (active && (active.state === "thinking" || active.state === "awaiting_device")) {
      if (await expireIfNeeded(env, active)) {
        /* expired & cleaned */
      } else {
        return json({ ok: false, error: "DEVICE_BUSY", turn_id: active.id }, 409);
      }
    }
  }

  // Fetch device info for the system prompt.
  let deviceInfo: any;
  try {
    const raw = await env.KV_DEVICES.get(`device:${deviceId}`);
    deviceInfo = raw ? JSON.parse(raw) : undefined;
  } catch {}

  const turnId = `turn_${randomId(8).toLowerCase()}`;
  const turn: Turn = {
    id: turnId,
    device_id: deviceId,
    model_id: model.id,
    state: "thinking",
    messages: [
      { role: "system", content: systemPrompt(deviceInfo) },
      { role: "user", content: message.slice(0, 4000) },
    ],
    steps: [{ ts: now(), type: "user", text: message.slice(0, 4000) }],
    tool_calls_used: 0,
    created_at: now(),
    updated_at: now(),
  };
  await saveTurn(env, turn);
  await env.KV_DEVICES.put(`turn:${deviceId}`, turnId, { expirationTtl: 3600 });
  await audit(env, "chat_started", { turn: turnId, device: deviceId, model: model.id });

  ctx.waitUntil(agentStep(env, turnId));
  return json({ ok: true, turn_id: turnId, device_id: deviceId, model_id: model.id });
}

// ------------------------------------------------------------ agent engine

/**
 * One step of the tool loop:
 *   AI → tool_call? → queue for device → (device result re-triggers us)
 *      → final text? → turn done.
 */
export async function agentStep(env: Env, turnId: string): Promise<void> {
  const turn = await loadTurn(env, turnId);
  if (!turn || turn.state !== "thinking") return;

  if (turn.tool_calls_used >= maxCalls(env)) {
    await finishTurn(env, turn, "Stopped: reached MAX_TOOL_CALLS_PER_TURN.");
    return;
  }

  try {
    const provider = getProvider(env, turn.model_id);
    const result = await provider.chat(turn.model_id, turn.messages, TOOL_SCHEMAS);

    if (result.toolCalls.length > 0) {
      const tc = result.toolCalls[0]; // one command at a time
      const cmdId = `cmd_${randomId(8).toLowerCase()}`;
      let args = safeJsonParse(tc.args) ?? {};
      if (typeof args !== "object") args = { value: args };

      if (result.text) turn.steps.push({ ts: now(), type: "ai", text: result.text });

      const cmd: Command = {
        type: "command",
        id: cmdId,
        tool: tc.name,
        arguments: args,
        issued_at: now(),
      };

      turn.messages.push({
        role: "assistant",
        content: result.text ?? null,
        tool_calls: [{ id: cmdId, name: tc.name, args }],
      });
      turn.steps.push({ ts: now(), type: "tool_call", tool: tc.name, cmd_id: cmdId, args });
      turn.pending_command = cmd;
      turn.pending_since = now();
      turn.state = "awaiting_device";
      turn.tool_calls_used += 1;
      await saveTurn(env, turn);
      // Command queue: the device long-polls this key.
      await env.KV_DEVICES.put(`pendingcmd:${turn.device_id}`, JSON.stringify(cmd), { expirationTtl: 300 });
      await audit(env, "tool_call", { turn: turnId, device: turn.device_id, tool: tc.name });
    } else {
      await finishTurn(env, turn, result.text ?? "(empty response)");
    }
  } catch (e: any) {
    turn.steps.push({ ts: now(), type: "error", text: `AI_ERROR: ${String(e?.message ?? e).slice(0, 800)}` });
    turn.state = "error";
    turn.error = String(e?.message ?? e).slice(0, 800);
    await saveTurn(env, turn);
    await clearDeviceTurn(env, turn.device_id);
    await audit(env, "turn_error", { turn: turnId, device: turn.device_id, error: turn.error });
  }
}

async function finishTurn(env: Env, turn: Turn, text: string): Promise<void> {
  turn.steps.push({ ts: now(), type: "final", text });
  turn.messages.push({ role: "assistant", content: text });
  turn.state = "done";
  turn.final_response = text;
  turn.pending_command = null;
  await saveTurn(env, turn);
  await clearDeviceTurn(env, turn.device_id);
  await audit(env, "turn_done", { turn: turn.id, device: turn.device_id });
}

// ------------------------------------------------------------ device result

export async function applyDeviceResult(
  env: Env,
  ctx: ExecutionContext,
  rec: { device_id: string },
  result: DeviceResult
): Promise<Response> {
  const turnId = await env.KV_DEVICES.get(`turn:${rec.device_id}`);
  if (!turnId) return json({ ok: true, ignored: true, reason: "NO_ACTIVE_TURN" });

  const turn = await loadTurn(env, turnId);
  if (!turn || turn.state !== "awaiting_device") return json({ ok: true, ignored: true, reason: "TURN_NOT_AWAITING" });
  const pending = turn.pending_command;
  if (!pending || pending.id !== result.id) return json({ ok: true, ignored: true, reason: "COMMAND_ID_MISMATCH" });

  const tool = pending.tool;
  turn.pending_command = null;
  turn.pending_since = null;

  const summary = summarizeResult(tool, result);
  turn.steps.push({
    ts: now(),
    type: "tool_result",
    tool,
    cmd_id: result.id,
    ok: !!result.success,
    summary,
  });
  turn.messages.push({
    role: "tool",
    tool_call_id: result.id,
    content: JSON.stringify({ tool, success: !!result.success, ...(result.data ?? {}), ...(result.error ? { error: result.error, error_code: result.error_code } : {}) }),
  });
  turn.state = "thinking";
  await saveTurn(env, turn);
  await env.KV_DEVICES.delete(`pendingcmd:${rec.device_id}`);
  await audit(env, "tool_result", { turn: turnId, device: rec.device_id, tool, ok: !!result.success });

  // Continue the loop: AI sees the result and either calls the next tool or answers.
  ctx.waitUntil(agentStep(env, turnId));
  return json({ ok: true, next: "agent_step" });
}

function summarizeResult(tool: string, r: DeviceResult): string {
  if (!r.success) return r.error_code ?? r.error ?? "failed";
  if (tool === "get_screen_nodes") {
    const d = r.data ?? {};
    return d.unchanged ? `unchanged (hash ${String(d.hash).slice(0, 8)})` : `nodes=${d.node_count ?? "?"}`;
  }
  if (tool === "get_current_package" && r.data?.package) return r.data.package;
  if (tool === "open_app" && r.data?.package) return `open: ${r.data.package}`;
  if (tool === "run_shell" && r.data?.stdout) return `exit=${r.data.exit_code ?? 0}`;
  return "success";
}

// ---------------------------------------------------------------- polling

export async function pollCommand(
  env: Env,
  rec: { device_id: string },
  waitSeconds: number
): Promise<Response> {
  const wait = Math.min(Math.max(waitSeconds, 0), 25);
  const deadline = now() + wait * 1000;

  for (;;) {
    const raw = await env.KV_DEVICES.get(`pendingcmd:${rec.device_id}`);
    if (raw) {
      const cmd = JSON.parse(raw) as Command;
      // strip anything the device doesn't need
      return json({ type: "command", id: cmd.id, tool: cmd.tool, arguments: cmd.arguments, approved: !!cmd.approved });
    }
    if (now() >= deadline) return new Response(null, { status: 204 });
    await new Promise((r) => setTimeout(r, 2000));
  }
}

// ----------------------------------------------------------------- sweep

/**
 * Lazy expiry: checked on turn reads and before starting new turns.
 * (No cron/alarms needed for the MVP.)
 */
export async function expireIfNeeded(env: Env, turn: Turn): Promise<boolean> {
  let expired: string | null = null;

  if (turn.state === "awaiting_device" && turn.pending_since && now() - turn.pending_since > DEVICE_TIMEOUT_MS) {
    expired = "DEVICE_TIMEOUT: the device did not return a result in time. Is it connected and the service running?";
  } else if (turn.state === "thinking" && now() - turn.updated_at > AI_TIMEOUT_MS) {
    expired = "AI_TIMEOUT: the turn stalled.";
  }

  if (!expired) return false;

  turn.steps.push({ ts: now(), type: "error", text: expired });
  turn.state = "error";
  turn.error = expired;
  await saveTurn(env, turn);
  await clearDeviceTurn(env, turn.device_id);
  await audit(env, "turn_error", { turn: turn.id, device: turn.device_id, error: expired });
  return true;
}

export async function getTurnResponse(env: Env, turnId: string): Promise<Response> {
  const turn = await loadTurn(env, turnId);
  if (!turn) return json({ ok: false, error: "TURN_NOT_FOUND" }, 404);
  await expireIfNeeded(env, turn);
  return json({
    ok: true,
    id: turn.id,
    state: turn.state,
    model_id: turn.model_id,
    steps: turn.steps,
    tool_calls_used: turn.tool_calls_used,
    final_response: turn.final_response ?? null,
    error: turn.error ?? null,
  });
}
