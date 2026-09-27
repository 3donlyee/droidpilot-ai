import type { Env } from "./env";
import type { Turn, Command, DeviceResult } from "./types";
import { json, randomId, now, safeJsonParse } from "./util";
import { getProvider } from "./ai/provider";
import { getModel, defaultModel } from "./models";
import { audit } from "./audit";
import { getAgent, pickAgent, toolsForAgent, systemPrompt as agentSystemPrompt, AgentEntry } from "./agents";
import * as memory from "./memory";

const DEVICE_TIMEOUT_MS = 45_000;   // device must return a result within 45s of a tool call
const AI_TIMEOUT_MS = 90_000;       // safety net for stuck "thinking" turns
const STOP_MSG = "⏹ أوقفتُ المهمة بناءً على طلبك. اضغط «متابعة» أو أرسل رسالة جديدة وقتما تشاء.";

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
  return agentSystemPrompt(getAgent("general"), "", deviceInfo);
}

// -------------------------------------------------------------- start turn

export async function startTurn(
  env: Env,
  ctx: ExecutionContext,
  deviceId: string,
  message: string,
  modelId?: string | null,
  agentId?: string | null,
  source?: string | null
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

  // === aMiNo mind: pick the specialist + load the three-tier memory ===
  const agent = pickAgent(message, agentId ?? null);
  const [memBlock, hist] = await Promise.all([
    memory.memoryBlock(env, deviceId),
    memory.loadHistory(env, deviceId),
  ]);

  const turnId = `turn_${randomId(8).toLowerCase()}`;
  const turn: Turn = {
    id: turnId,
    device_id: deviceId,
    model_id: model.id,
    agent_id: agent.id,
    source: source ?? "web",
    state: "thinking",
    messages: [
      { role: "system", content: agentSystemPrompt(agent, memBlock, deviceInfo) },
      ...hist.slice(-6).map((h) => ({ role: h.role, content: h.text }) as any),
      { role: "user", content: message.slice(0, 4000) },
    ],
    steps: [
      { ts: now(), type: "user", text: message.slice(0, 4000) },
      { ts: now(), type: "info", text: `${agent.emoji} الوكيل: ${agent.name}` },
    ],
    tool_calls_used: 0,
    created_at: now(),
    updated_at: now(),
  };
  await saveTurn(env, turn);
  await env.KV_DEVICES.put(`turn:${deviceId}`, turnId, { expirationTtl: 3600 });
  await audit(env, "chat_started", { turn: turnId, device: deviceId, model: model.id, agent: agent.id, source: turn.source });

  ctx.waitUntil(agentStep(env, ctx, turnId));
  return json({ ok: true, turn_id: turnId, device_id: deviceId, model_id: model.id, agent_id: agent.id });
}

// ------------------------------------------------------------ agent engine

/**
 * One step of the tool loop:
 *   AI → tool_call? → queue for device → (device result re-triggers us)
 *      → final text? → turn done.
 */
export async function agentStep(env: Env, ctx: ExecutionContext, turnId: string): Promise<void> {
  const turn = await loadTurn(env, turnId);
  if (!turn || turn.state !== "thinking") return;

  const agent: AgentEntry = getAgent(turn.agent_id);

  // Stop/Resume control channel: check before every AI step.
  if (await env.KV_DEVICES.get(`abort:${turn.id}`)) {
    await finishTurn(env, ctx, turn, STOP_MSG, "stopped");
    return;
  }

  const budget = Math.min(maxCalls(env), agent.max_steps);
  if (turn.tool_calls_used >= budget) {
    await finishTurn(env, ctx, turn, `توقفت عند حد الخطوات لهذا الوكيل (${budget}). اطلب مني المتابعة لأكمل.`, "stopped");
    return;
  }

  try {
    const provider = getProvider(env, turn.model_id);
    const result = await provider.chat(turn.model_id, turn.messages, toolsForAgent(agent));

    if (result.toolCalls.length > 0) {
      const tc = result.toolCalls[0]; // one command at a time
      // Sanitize model artifacts: some models emit "tool<|channel|>…" fragments.
      tc.name = String(tc.name ?? "").split("<|")[0].trim();
      const cmdId = `cmd_${randomId(8).toLowerCase()}`;
      let args = safeJsonParse(tc.args) ?? {};
      if (typeof args !== "object") args = { value: args };

      if (result.text) turn.steps.push({ ts: now(), type: "ai", text: result.text });

      // --- Server-side memory tools: never dispatched to the device ---
      if (tc.name === "memory_save" || tc.name === "memory_list" || tc.name === "memory_forget") {
        const out = await memory.handleMemoryTool(env, turn.device_id, tc.name, args);
        turn.messages.push({
          role: "assistant",
          content: result.text ?? null,
          tool_calls: [{ id: cmdId, name: tc.name, args }],
        });
        turn.messages.push({ role: "tool", tool_call_id: cmdId, content: JSON.stringify({ tool: tc.name, success: out.ok, ...out.result }) });
        turn.steps.push({ ts: now(), type: "tool_call", tool: tc.name, cmd_id: cmdId, args });
        turn.steps.push({ ts: now(), type: "tool_result", tool: tc.name, cmd_id: cmdId, ok: out.ok, summary: "memory" });
        turn.tool_calls_used += 1;
        await saveTurn(env, turn);
        ctx.waitUntil(agentStep(env, ctx, turnId));
        return;
      }

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
      // (audit for tool_call removed — tool_result below logs the same info + outcome;
      //  halves audit writes to respect the free-tier KV daily quota)
    } else {
      await finishTurn(env, ctx, turn, result.text ?? "(empty response)");
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

async function finishTurn(
  env: Env,
  ctx: { waitUntil(p: Promise<any>): void },
  turn: Turn,
  text: string,
  state: "done" | "stopped" = "done"
): Promise<void> {
  turn.steps.push({ ts: now(), type: "final", text });
  turn.messages.push({ role: "assistant", content: text });
  turn.state = state;
  turn.final_response = text;
  turn.pending_command = null;
  await saveTurn(env, turn);
  await clearDeviceTurn(env, turn.device_id);
  await audit(env, state === "stopped" ? "turn_stopped" : "turn_done", { turn: turn.id, device: turn.device_id });
  // Memory protocol T2: remember the exchange (and occasionally refresh the summary).
  const userText = (turn.steps.find((s) => s.type === "user")?.text ?? "").slice(0, 600);
  await memory.rememberTurn(env, ctx, turn.device_id, userText, text.slice(0, 600));
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
  ctx.waitUntil(agentStep(env, ctx, turnId));
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

// ------------------------------------------------------------ stop / resume

/**
 * Stop the device's active turn (Stop button in Web UI / voice overlay).
 * Sets an abort flag the agent loop checks before every AI step, and
 * immediately cancels any pending device command.
 */
export async function stopDeviceTurn(env: Env, deviceId: string): Promise<Response> {
  const turnId = await env.KV_DEVICES.get(`turn:${deviceId}`);
  if (!turnId) return json({ ok: false, error: "NO_ACTIVE_TURN" }, 404);
  await env.KV_DEVICES.put(`abort:${turnId}`, "1", { expirationTtl: 600 });

  const turn = await loadTurn(env, turnId);
  if (turn && (turn.state === "thinking" || turn.state === "awaiting_device")) {
    await env.KV_DEVICES.delete(`pendingcmd:${deviceId}`);
    await finishTurn(env, { waitUntil: () => {} } as any, turn, STOP_MSG, "stopped");
  }
  return json({ ok: true, stopped: turnId });
}

// ----------------------------------------------------------------- sweep
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
    agent_id: turn.agent_id ?? "general",
    steps: turn.steps,
    tool_calls_used: turn.tool_calls_used,
    final_response: turn.final_response ?? null,
    error: turn.error ?? null,
  });
}
