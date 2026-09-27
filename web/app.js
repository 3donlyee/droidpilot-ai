/* aMiNo — Web UI logic (vanilla JS, no build step)
   Same skeleton as the original working UI + team/memory/stop/mic additions. */

const $ = (id) => document.getElementById(id);

const state = {
  models: [],
  modelId: null,
  devices: [],
  deviceId: null,
  turnId: null,
  rendered: 0,
  agents: [],
  agentId: "auto",
  recog: null,
  recogOn: false,
};

/* ---------------------------------------------------------------- helpers */

function esc(s) {
  return String(s ?? "").replace(/[&<>"']/g, (c) =>
    ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c])
  );
}

function fmtTs(ts) {
  return new Date(ts).toTimeString().slice(0, 8);
}

async function api(path, opts = {}) {
  const res = await fetch(path, {
    headers: { "content-type": "application/json" },
    ...opts,
  });
  let data = {};
  try { data = await res.json(); } catch {}
  if (!res.ok) throw new Error(data.error || `HTTP_${res.status}`);
  return data;
}

function addMsg(kind, text) {
  const box = $("messages");
  box.querySelector(".empty")?.remove();
  const el = document.createElement("div");
  el.className = `bubble ${kind}`;
  el.dir = "auto"; // Arabic/English text direction
  el.textContent = text;
  box.appendChild(el);
  box.scrollTop = box.scrollHeight;
}

function addToolChip(step) {
  const box = $("messages");
  const el = document.createElement("div");
  el.className = "chip-tool";
  el.id = `chip-${step.cmd_id}`;
  el.innerHTML = `⚙ <span class="t">${esc(step.tool)}</span> … ⏳`;
  box.appendChild(el);
  box.scrollTop = box.scrollHeight;
}

function updateChip(step) {
  const el = $(`chip-${step.cmd_id}`);
  if (!el) return;
  el.classList.add(step.ok ? "done" : "failed");
  el.innerHTML = `⚙ <span class="t">${esc(step.tool)}</span> ${step.ok ? "✓" : "✗"} ${esc(step.summary ?? "")}`;
}

function addAgentLine(text) {
  const box = $("messages");
  box.querySelector(".empty")?.remove();
  const el = document.createElement("div");
  el.className = "chip-agent";
  el.dir = "auto";
  el.textContent = text;
  box.appendChild(el);
  box.scrollTop = box.scrollHeight;
}

function addLog(who, text) {
  const log = $("liveLog");
  const line = document.createElement("div");
  line.dir = "auto";
  const cls = { USER: "user", AI: "ai", ANDROID: "android", ERROR: "error", INFO: "info" }[who] || "info";
  line.innerHTML = `<span class="ts">[${fmtTs(Date.now())}]</span> <span class="who ${cls}">${esc(who)}:</span> ${esc(text)}`;
  log.appendChild(line);
  log.scrollTop = log.scrollHeight;
}

/* ----------------------------------------------------------------- models */

async function refreshModels() {
  try {
    const { models } = await api("/api/models");
    state.models = models;
    const sel = $("modelSelect");
    sel.innerHTML = "";
    for (const m of models) {
      const o = document.createElement("option");
      o.value = m.id;
      o.textContent = m.displayName + (m.default ? "  (افتراضي)" : "");
      if (m.status !== "available") o.disabled = true;
      sel.appendChild(o);
    }
    const def = models.find((m) => m.default && m.status === "available") || models.find((m) => m.status === "available");
    if (def) { sel.value = def.id; state.modelId = def.id; renderCapabilities(def); }
    sel.onchange = () => {
      state.modelId = sel.value;
      renderCapabilities(models.find((m) => m.id === sel.value));
    };
  } catch (e) {
    addLog("ERROR", `models: ${e.message}`);
  }
}

function renderCapabilities(m) {
  if (!m) return;
  $("modelMeta").innerHTML = `
    <div class="caps">
      <span class="${m.capabilities.reasoning ? "ok" : "no"}">${m.capabilities.reasoning ? "✓" : "✗"} تفكير</span>
      <span class="${m.capabilities.supportsTools ? "ok" : "no"}">${m.capabilities.supportsTools ? "✓" : "✗"} أدوات</span>
      <span class="${m.capabilities.supportsVision ? "ok" : "no"}">${m.capabilities.supportsVision ? "✓" : "✗"} رؤية</span>
    </div>
    <div>الحالة: <span class="dot ${m.status === "available" ? "green" : "red"}"></span> ${esc(m.status)}</div>`;
}

/* ------------------------------------------------------------------ team */

async function refreshAgents() {
  try {
    const { agents } = await api("/api/agents");
    state.agents = agents;
    renderTeam();
  } catch (e) {
    addLog("ERROR", `agents: ${e.message}`);
  }
}

function renderTeam() {
  const box = $("teamChips");
  box.innerHTML = "";
  const mk = (id, label, title) => {
    const b = document.createElement("button");
    b.type = "button";
    b.className = "agent-chip" + (state.agentId === id ? " active" : "") + (id === "auto" ? " auto" : "");
    b.textContent = label;
    if (title) b.title = title;
    b.onclick = () => { state.agentId = id; renderTeam(); };
    box.appendChild(b);
  };
  mk("auto", "🧠 تلقائي", "aMiNo يختار الوكيل المناسب تلقائياً");
  for (const a of state.agents) mk(a.id, `${a.emoji} ${a.name}`, a.tagline);
}

function agentLabel(id) {
  const a = state.agents.find((x) => x.id === id);
  return a ? `${a.emoji} ${a.name}` : "🧠 aMiNo";
}

/* ---------------------------------------------------------------- devices */

async function refreshDevices() {
  try {
    const { devices } = await api("/api/devices");
    state.devices = devices;

    // Header chip: first active device
    const active = devices.find((d) => d.status === "active");
    if (active) {
      const changed = state.deviceId !== active.device_id;
      state.deviceId = active.device_id;
      const chip = $("deviceChip");
      chip.textContent = `${active.connected ? "●" : "○"} ${active.device_id} — ${active.model || "جهاز"}`;
      chip.classList.toggle("online", !!active.connected);
      if (changed) refreshMemory();
    } else {
      state.deviceId = null;
      $("deviceChip").textContent = "○ لا يوجد جهاز مقترن";
      $("deviceChip").classList.remove("online");
      $("memSummary").textContent = "اختر جهازاً لعرض ذاكرته…";
      $("memFacts").innerHTML = "";
    }

    // Pairing dropdown: pending devices only
    const pending = devices.filter((d) => d.status === "pending");
    const sel = $("deviceSelect");
    const current = sel.value;
    sel.innerHTML = "";
    if (!pending.length) {
      const o = document.createElement("option");
      o.value = "";
      o.textContent = "لا توجد أجهزة قيد الاقتران…";
      sel.appendChild(o);
    } else {
      for (const d of pending) {
        const o = document.createElement("option");
        o.value = d.device_id;
        o.textContent = `${d.device_id} — ${d.model || d.device_name || "غير معروف"}`;
        sel.appendChild(o);
      }
      if (pending.some((d) => d.device_id === current)) sel.value = current;
    }
  } catch (e) {
    addLog("ERROR", `devices: ${e.message}`);
  }
}

async function pair() {
  const deviceId = $("deviceSelect").value;
  const pin = $("pinInput").value.trim();
  const msg = $("pairMsg");
  if (!deviceId || pin.length !== 6) {
    msg.textContent = "اختر جهازاً وأدخل رمز الـ 6 أرقام.";
    return;
  }
  try {
    const r = await api("/api/pair/confirm", {
      method: "POST",
      body: JSON.stringify({ device_id: deviceId, pairing_code: pin }),
    });
    msg.textContent = `✓ تم الاقتران: ${r.device.device_id}`;
    $("pinInput").value = "";
    addLog("INFO", `device paired: ${r.device.device_id}`);
    refreshDevices();
  } catch (e) {
    msg.textContent = `✗ ${e.message}`;
  }
}

/* ----------------------------------------------------------------- memory */

async function refreshMemory() {
  if (!state.deviceId) return;
  try {
    const snap = await api(`/api/memory/${encodeURIComponent(state.deviceId)}`);
    const sum = $("memSummary");
    if (snap.summary) {
      sum.textContent = `📌 ${snap.summary}`;
      sum.style.display = "";
    } else {
      sum.textContent = "لا يوجد ملخص بعد — سيتكوّن تلقائياً مع المحادثات.";
    }
    const box = $("memFacts");
    box.innerHTML = "";
    if (!snap.facts.length) {
      box.innerHTML = `<div class="hint">لا حقائق محفوظة. اطلب من aMiNo «تذكر أن…» أو أضف يدوياً.</div>`;
      return;
    }
    for (const f of snap.facts) {
      const el = document.createElement("div");
      el.className = "fact";
      el.innerHTML = `<b>${esc(f.k)}</b><span dir="auto">${esc(f.v)}</span><button title="نسيان">✕</button>`;
      el.querySelector("button").onclick = () => forgetFact(f.k);
      box.appendChild(el);
    }
  } catch (e) {
    $("memSummary").textContent = `الذاكرة: ${e.message}`;
  }
}

async function saveFact() {
  const k = $("memKey").value.trim();
  const v = $("memValue").value.trim();
  if (!state.deviceId) { addLog("ERROR", "لا يوجد جهاز مقترن"); return; }
  if (!k || !v) { addLog("ERROR", "أدخل الاسم والقيمة"); return; }
  try {
    await api("/api/memory/add", {
      method: "POST",
      body: JSON.stringify({ device_id: state.deviceId, key: k, value: v }),
    });
    $("memKey").value = ""; $("memValue").value = "";
    addLog("INFO", `memory saved: ${k}`);
    refreshMemory();
  } catch (e) { addLog("ERROR", e.message); }
}

async function forgetFact(k) {
  if (!state.deviceId) return;
  try {
    await api("/api/memory/delete", {
      method: "POST",
      body: JSON.stringify({ device_id: state.deviceId, key: k }),
    });
    addLog("INFO", `memory forgotten: ${k}`);
    refreshMemory();
  } catch (e) { addLog("ERROR", e.message); }
}

/* ------------------------------------------------------------------- chat */

function setRunning(on) {
  $("btnSend").disabled = on;
  $("chatInput").disabled = on;
  $("btnStop").hidden = !on;
}

async function send(e) {
  e.preventDefault();
  const text = $("chatInput").value.trim();
  $("chatInput").value = "";
  await sendText(text);
}

async function sendText(text) {
  if (!text) return;
  if (!state.deviceId) { addLog("ERROR", "اقترن جهازاً أولاً"); return; }
  if (!state.modelId) { addLog("ERROR", "لا يوجد نموذج متاح"); return; }

  try {
    setRunning(true);
    $("messages").innerHTML = "";
    $("liveLog").innerHTML = "";
    state.rendered = 0;
    const body = { message: text, device_id: state.deviceId, model_id: state.modelId, source: "web" };
    if (state.agentId !== "auto") body.agent_id = state.agentId;
    const r = await api("/api/chat", { method: "POST", body: JSON.stringify(body) });
    state.turnId = r.turn_id;
    addAgentLine(`${agentLabel(r.agent_id)} يتولى المهمة`);
    addLog("INFO", `turn started: ${r.turn_id} · agent: ${r.agent_id ?? "auto"}`);
  } catch (err) {
    addLog("ERROR", err.message);
    setRunning(false);
  }
}

async function stopTask() {
  if (!state.deviceId) return;
  try {
    await api("/api/turn/stop", {
      method: "POST",
      body: JSON.stringify({ device_id: state.deviceId, source: "web" }),
    });
    addLog("INFO", "⏹ طلب إيقاف المهمة");
  } catch (e) { addLog("ERROR", e.message); }
}

async function pollTurn() {
  if (!state.turnId) return;
  try {
    const t = await api(`/api/turn/${state.turnId}`);
    for (; state.rendered < t.steps.length; state.rendered++) {
      const s = t.steps[state.rendered];
      switch (s.type) {
        case "user":        addMsg("user", s.text); addLog("USER", s.text); break;
        case "ai":          if (s.text) { addMsg("ai", s.text); addLog("AI", s.text); } break;
        case "info":        addAgentLine(s.text); addLog("INFO", s.text); break;
        case "tool_call":   addToolChip(s); addLog("AI", s.tool); break;
        case "tool_result": updateChip(s); addLog("ANDROID", s.summary); break;
        case "final":       addMsg("ai", s.text); addLog("AI", s.text); break;
        case "error":       addMsg("error", s.text); addLog("ERROR", s.text); break;
      }
    }
    // Terminal states: done / error / stopped (v2 adds "stopped")
    if (["done", "error", "stopped"].includes(t.state)) {
      state.turnId = null;
      setRunning(false);
    }
  } catch (e) {
    addLog("ERROR", `turn poll: ${e.message}`);
  }
}

/* -------------------------------------------------------------------- mic */

function initMic() {
  const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
  if (!SR) { $("btnMic").style.display = "none"; return; }
  const r = new SR();
  r.lang = "ar-DZ";
  r.interimResults = false;
  r.maxAlternatives = 1;
  r.onstart = () => { state.recogOn = true; $("btnMic").classList.add("rec"); };
  r.onend = () => { state.recogOn = false; $("btnMic").classList.remove("rec"); };
  r.onerror = () => { state.recogOn = false; $("btnMic").classList.remove("rec"); };
  r.onresult = (ev) => {
    const text = ev.results?.[0]?.[0]?.transcript?.trim();
    if (text) { addLog("USER", `🎤 ${text}`); sendText(text); }
  };
  state.recog = r;
  $("btnMic").onclick = () => {
    if (!state.recog) return;
    if (state.recogOn) { state.recog.stop(); return; }
    try { state.recog.start(); } catch {}
  };
}

/* ------------------------------------------------------------------- init */

(async function init() {
  $("chatForm").addEventListener("submit", send);
  $("btnPair").addEventListener("click", pair);
  $("btnStop").addEventListener("click", stopTask);
  $("btnSaveFact").addEventListener("click", saveFact);
  $("pinInput").addEventListener("keydown", (e) => { if (e.key === "Enter") { e.preventDefault(); pair(); } });

  await refreshModels();
  await refreshAgents();
  await refreshDevices();
  initMic();
  setInterval(refreshDevices, 5000);
  setInterval(() => pollTurn().catch(() => {}), 1200);

  try {
    const h = await fetch("/api/health").then((r) => r.json());
    $("health").textContent = `· API ${h.ok ? "online" : "degraded"}`;
  } catch {}
})();
