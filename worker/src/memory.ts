/**
 * AIMINOS MEMORY PROTOCOLS
 * ========================
 * Three-tier memory (the "aMiNo Mind"):
 *   T1 context    → system prompt + agent persona (per turn, ephemeral)
 *   T2 session    → rolling verbatim history (last 8 exchanges) injected each turn
 *   T3 durable    → named facts the user/agents explicitly saved (memory_save)
 *   + rolling summary → regenerated every N turns so old context is never fully lost
 *
 * Storage keys live in KV_DEVICES (free tier friendly: ~2 writes per turn).
 * Memory_* tools execute SERVER-SIDE (no device round-trip needed).
 */
import type { Env } from "./env";
import { now } from "./util";
import { getProvider } from "./ai/provider";
import { defaultModel } from "./models";

const FACTS_CAP = 50;
const HIST_ITEMS_CAP = 16; // 8 user/assistant exchanges
const HIST_INJECT = 6;     // items injected into a turn's context
const SUMMARY_EVERY = 8;   // refresh rolling summary every N remembered exchanges
const SUMMARY_MAX = 900;   // chars

export interface MemoryFact { k: string; v: string; ts: number; }
export interface HistItem { role: "user" | "assistant"; text: string; ts: number; }

const factKey = (d: string) => `aimem:${d}`;
const histKey = (d: string) => `aimhist:${d}`;
const sumKey = (d: string) => `aimsum:${d}`;

// ------------------------------------------------------------------ T3 facts

export async function listFacts(env: Env, deviceId: string): Promise<MemoryFact[]> {
  const raw = await env.KV_DEVICES.get(factKey(deviceId));
  try { return raw ? (JSON.parse(raw) as MemoryFact[]) : []; } catch { return []; }
}

export async function saveFact(env: Env, deviceId: string, k: string, v: string): Promise<MemoryFact> {
  const facts = await listFacts(env, deviceId);
  const item: MemoryFact = { k: k.slice(0, 60).trim(), v: v.slice(0, 400).trim(), ts: now() };
  const i = facts.findIndex((x) => x.k === item.k);
  if (i >= 0) facts[i] = item; else facts.push(item);
  while (facts.length > FACTS_CAP) facts.shift();
  await env.KV_DEVICES.put(factKey(deviceId), JSON.stringify(facts));
  return item;
}

export async function forgetFact(env: Env, deviceId: string, k: string): Promise<boolean> {
  const facts = await listFacts(env, deviceId);
  const next = facts.filter((x) => x.k !== k.slice(0, 60).trim());
  if (next.length === facts.length) return false;
  await env.KV_DEVICES.put(factKey(deviceId), JSON.stringify(next));
  return true;
}

// ------------------------------------------------------------------ T2 history

export async function loadHistory(env: Env, deviceId: string): Promise<HistItem[]> {
  const raw = await env.KV_DEVICES.get(histKey(deviceId));
  try { return raw ? (JSON.parse(raw) as HistItem[]) : []; } catch { return []; }
}

async function pushHistory(env: Env, deviceId: string, role: "user" | "assistant", text: string): Promise<void> {
  const hist = await loadHistory(env, deviceId);
  hist.push({ role, text: text.slice(0, 600), ts: now() });
  while (hist.length > HIST_ITEMS_CAP) hist.shift();
  await env.KV_DEVICES.put(histKey(deviceId), JSON.stringify(hist));
}

// ----------------------------------------------------------------- summary

export async function loadSummary(env: Env, deviceId: string): Promise<string> {
  return (await env.KV_DEVICES.get(sumKey(deviceId))) ?? "";
}

async function refreshSummary(env: Env, deviceId: string, hist: HistItem[]): Promise<void> {
  try {
    const convo = hist.map((h) => `${h.role === "user" ? "المستخدم" : "aMiNo"}: ${h.text}`).join("\n");
    const model = defaultModel();
    const provider = getProvider(env, model.id);
    const r = await provider.chat(model.id, [
      { role: "system", content: "لخص المحادثة في ما لا يزيد عن 5 جمل عربية، ركّز على حقائق دائمة عن المستخدم وتفضيلاته ومهامه المتكررة. أعد النص فقط." },
      { role: "user", content: convo.slice(0, 4000) },
    ], []);
    const text = (r.text ?? "").trim();
    if (text) await env.KV_DEVICES.put(sumKey(deviceId), text.slice(0, SUMMARY_MAX));
  } catch { /* summary is best-effort — never break the turn path */ }
}

// ----------------------------------------------------------- context builder

export async function memoryBlock(env: Env, deviceId: string): Promise<string> {
  const [facts, summary] = await Promise.all([listFacts(env, deviceId), loadSummary(env, deviceId)]);
  const parts: string[] = [];
  if (summary) parts.push(`ملخص الجلسات السابقة:\n${summary}`);
  if (facts.length) {
    parts.push(`حقائق محفوظة (T3 durable):\n${facts.map((f) => `- ${f.k}: ${f.v}`).join("\n")}`);
  }
  return parts.length ? parts.join("\n\n") : "";
}

/** Called when a turn finishes — updates T2 and occasionally the summary. */
export async function rememberTurn(
  env: Env,
  ctx: { waitUntil(p: Promise<any>): void },
  deviceId: string,
  userText: string,
  assistantText: string
): Promise<void> {
  try {
    await pushHistory(env, deviceId, "user", userText);
    await pushHistory(env, deviceId, "assistant", assistantText);
    const hist = await loadHistory(env, deviceId);
    if (hist.length >= HIST_ITEMS_CAP) {
      const marker = await env.KV_DEVICES.get(`aimsum_ts:${deviceId}`);
      const due = !marker || now() - Number(marker) > SUMMARY_EVERY * 60_000;
      if (due) {
        await env.KV_DEVICES.put(`aimsum_ts:${deviceId}`, String(now()), { expirationTtl: 86400 });
        ctx.waitUntil(refreshSummary(env, deviceId, hist));
      }
    }
  } catch { /* memory is best-effort */ }
}

// ------------------------------------------------- server-side memory tools

export async function handleMemoryTool(
  env: Env,
  deviceId: string,
  name: string,
  args: any
): Promise<{ ok: boolean; result: any }> {
  try {
    if (name === "memory_save") {
      const k = String(args?.key ?? "").trim();
      const v = String(args?.value ?? "").trim();
      if (!k || !v) return { ok: false, result: { error: "key and value are required" } };
      await saveFact(env, deviceId, k, v);
      return { ok: true, result: { saved: true, key: k } };
    }
    if (name === "memory_list") {
      const facts = await listFacts(env, deviceId);
      return { ok: true, result: { count: facts.length, facts: facts.map((f) => ({ key: f.k, value: f.v })) } };
    }
    if (name === "memory_forget") {
      const k = String(args?.key ?? "").trim();
      const gone = await forgetFact(env, deviceId, k);
      return { ok: gone, result: gone ? { forgotten: k } : { error: `no fact named '${k}'` } };
    }
    return { ok: false, result: { error: `unknown memory tool '${name}'` } };
  } catch (e: any) {
    return { ok: false, result: { error: String(e?.message ?? e).slice(0, 200) } };
  }
}

/** Snapshot for the Web UI "الذاكرة" tab. */
export async function memorySnapshot(env: Env, deviceId: string): Promise<any> {
  const [facts, summary, hist] = await Promise.all([
    listFacts(env, deviceId), loadSummary(env, deviceId), loadHistory(env, deviceId),
  ]);
  return { ok: true, facts, summary, history: hist.slice(-8) };
}
