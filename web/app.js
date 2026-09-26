/* DroidPilot AI — Web UI logic (vanilla JS, no build step) */

const $ = (id) => document.getElementById(id);

const state = {
  models: [],
  modelId: null,
  devices: [],
  deviceId: null,
  turnId: null,
  rendered: 0,
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
  el.innerHTML = `Tool: <span class="t">${esc(step.tool)}</span> … ⏳`;
  box.appendChild(el);
  box.scrollTop = box.scrollHeight;
}

function updateChip(step) {
  const el = $(`chip-${step.cmd_id}`);
  if (!el) return;
  el.classList.add(step.ok ? "done" : "failed");
  el.innerHTML = `Tool: <span class="t">${esc(step.tool)}</span> ${step.ok ? "✓" : "✗"} ${esc(step.summary ?? "")}`;
}

function addLog(who, text) {
  const log = $("liveLog");
  const line = document.createElement("div");
  line.dir = "auto";
  const cls = { USER: "user", AI: "ai", ANDROID: "android", ERROR: "error", INFO: "ai" }[who] || "ai";
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
      o.textContent = m.displayName + (m.default ? "  (default)" : "");
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
    <div>Provider: <b>${esc(m.provider)}</b></div>
    <div class="caps">
      <span class="${m.capabilities.reasoning ? "ok" : "no"}">${m.capabilities.reasoning ? "✓" : "✗"} Reasoning</span>
      <span class="${m.capabilities.supportsTools ? "ok" : "no"}">${m.capabilities.supportsTools ? "✓" : "✗"} Tool Calling</span>
      <span class="${m.capabilities.supportsVision ? "ok" : "no"}">${m.capabilities.supportsVision ? "✓" : "✗"} Vision</span>
    </div>
    <div>Status: <span class="dot ${m.status === "available" ? "green" : "red"}"></span> ${esc(m.status)}</div>`;
}

/* ---------------------------------------------------------------- devices */

async function refreshDevices() {
  try {
    const { devices } = await api("/api/devices");
    state.devices = devices;

    // Header chip: first active device
    const active = devices.find((d) => d.status === "active");
    if (active) {
      state.deviceId = active.device_id;
      const chip = $("deviceChip");
      chip.textContent = `${active.connected ? "●" : "○"} ${active.device_id} — ${active.model || "device"}`;
      chip.classList.toggle("online", !!active.connected);
    } else {
      state.deviceId = null;
      $("deviceChip").textContent = "○ No paired device";
      $("deviceChip").classList.remove("online");
    }

    // Pairing dropdown: pending devices only
    const pending = devices.filter((d) => d.status === "pending");
    const sel = $("deviceSelect");
    const current = sel.value;
    sel.innerHTML = "";
    if (!pending.length) {
      const o = document.createElement("option");
      o.value = "";
      o.textContent = "No pending devices…";
      sel.appendChild(o);
    } else {
      for (const d of pending) {
        const o = document.createElement("option");
        o.value = d.device_id;
        o.textContent = `${d.device_id} — ${d.model || d.device_name || "unknown"}`;
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
    msg.textContent = "Select a device and enter the 6-digit PIN.";
    return;
  }
  try {
    const r = await api("/api/pair/confirm", {
      method: "POST",
      body: JSON.stringify({ device_id: deviceId, pairing_code: pin }),
    });
    msg.textContent = `✓ Paired: ${r.device.device_id}`;
    $("pinInput").value = "";
    addLog("INFO", `device paired: ${r.device.device_id}`);
    refreshDevices();
  } catch (e) {
    msg.textContent = `✗ ${e.message}`;
  }
}

/* ------------------------------------------------------------------- chat */

function setSendEnabled(on) {
  $("btnSend").disabled = !on;
  $("chatInput").disabled = !on;
}

async function send(e) {
  e.preventDefault();
  const text = $("chatInput").value.trim();
  if (!text) return;
  if (!state.deviceId) { addLog("ERROR", "Pair a device first"); return; }
  if (!state.modelId) { addLog("ERROR", "No model available"); return; }

  try {
    setSendEnabled(false);
    $("messages").innerHTML = "";
    $("liveLog").innerHTML = "";
    state.rendered = 0;
    const r = await api("/api/chat", {
      method: "POST",
      body: JSON.stringify({ message: text, device_id: state.deviceId, model_id: state.modelId }),
    });
    state.turnId = r.turn_id;
    $("chatInput").value = "";
    addLog("INFO", `turn started: ${r.turn_id}`);
  } catch (err) {
    addLog("ERROR", err.message);
    setSendEnabled(true);
  }
}

async function pollTurn() {
  if (!state.turnId) return;
  try {
    const t = await api(`/api/turn/${state.turnId}`);
    for (; state.rendered < t.steps.length; state.rendered++) {
      const s = t.steps[state.rendered];
      switch (s.type) {
        case "user":       addMsg("user", s.text); addLog("USER", s.text); break;
        case "ai":         if (s.text) { addMsg("ai", s.text); addLog("AI", s.text); } break;
        case "tool_call":  addToolChip(s); addLog("AI", s.tool); break;
        case "tool_result": updateChip(s); addLog("ANDROID", s.summary); break;
        case "final":      addMsg("ai", s.text); addLog("AI", s.text); break;
        case "error":      addMsg("error", s.text); addLog("ERROR", s.text); break;
      }
    }
    if (t.state === "done" || t.state === "error") {
      state.turnId = null;
      setSendEnabled(true);
    }
  } catch (e) {
    addLog("ERROR", `turn poll: ${e.message}`);
  }
}

/* ------------------------------------------------------------------- init */

(async function init() {
  $("chatForm").addEventListener("submit", send);
  $("btnPair").addEventListener("click", pair);
  $("pinInput").addEventListener("keydown", (e) => { if (e.key === "Enter") { e.preventDefault(); pair(); } });

  await refreshModels();
  await refreshDevices();
  setInterval(refreshDevices, 5000);
  setInterval(() => pollTurn().catch(() => {}), 1200);

  try {
    const h = await fetch("/api/health").then((r) => r.json());
    $("health").textContent = `· API ${h.ok ? "online" : "degraded"}`;
  } catch {}
})();
