/* ============================================================
   AIMINOS — Web UI v2 (vanilla JS, zero deps)
   ============================================================ */
"use strict";
const $ = (s) => document.querySelector(s);
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

const S = {
  device: localStorage.getItem("aim_device") || "",
  model: localStorage.getItem("aim_model") || "",
  agent: "",
  models: [], agents: [], devices: [],
  lastTurn: null,       // {id, state}
  turnSeq: 0,           // guards stale pollers
  busy: false,
};

/* ---------------- tabs ---------------- */
$("#tabs").addEventListener("click", (e) => {
  const b = e.target.closest("button[data-tab]"); if (!b) return;
  document.querySelectorAll(".tabs button").forEach((x) => x.classList.toggle("on", x === b));
  document.querySelectorAll(".tab").forEach((x) => x.classList.toggle("on", x.id === "tab-" + b.dataset.tab));
  if (b.dataset.tab === "memory") loadMemory();
  if (b.dataset.tab === "devices") renderDevices();
  if (b.dataset.tab === "logs") refreshLogs();
});

/* ---------------- boot ---------------- */
async function jget(u) { try { const r = await fetch(u); return await r.json(); } catch { return null; } }
async function jpost(u, body) {
  try { const r = await fetch(u, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(body) }); return await r.json(); }
  catch { return null; }
}

async function boot() {
  const [m, a, d] = await Promise.all([jget("/api/models"), jget("/api/agents"), jget("/api/devices")]);
  S.models = m?.models?.filter((x) => x.status === "available") ?? [];
  S.agents = a?.agents ?? [];
  S.devices = d?.devices ?? [];
  fillSelectors(); renderTeam(); renderModelCards(); renderDevices(); syncDeviceChip();
  if (!S.model && S.models.length) { S.model = (S.models.find((x) => x.default) ?? S.models[0]).id; }
  $("#modelSel").value = S.model;
  setInterval(pollDevices, 5000);
}
function fillSelectors() {
  $("#modelSel").innerHTML = S.models.map((m) => `<option value="${esc(m.id)}">${esc(m.displayName)}</option>`).join("");
  $("#agentSel").innerHTML = `<option value="">🤖 تلقائي (الموجّه يختار)</option>` +
    S.agents.map((a) => `<option value="${esc(a.id)}">${a.emoji} ${esc(a.name)}</option>`).join("");
  if (S.model) $("#modelSel").value = S.model;
}
$("#modelSel").addEventListener("change", () => { S.model = $("#modelSel").value; localStorage.setItem("aim_model", S.model); });
$("#agentSel").addEventListener("change", () => { S.agent = $("#agentSel").value; });

/* ---------------- devices / pairing ---------------- */
function connectedDevice() {
  return S.devices.find((x) => x.device_id === S.device && x.status === "active");
}
function pollDevices() {
  jget("/api/devices").then((d) => {
    if (!d) return;
    S.devices = d.devices ?? [];
    renderDevices(); syncDeviceChip();
  });
}
function syncDeviceChip() {
  const dev = connectedDevice();
  const dot = $("#connDot"), txt = $("#connText");
  if (dev) {
    dot.className = "dot on"; txt.textContent = dev.model || dev.device_id;
    $("#chatDevice").innerHTML = `📱 ${esc(dev.device_id)} <small>${esc(dev.model ?? "")}</small>`;
    $("#btnSend").disabled = false; $("#btnMic").disabled = false;
  } else {
    dot.className = "dot off"; txt.textContent = S.device ? "الجهاز غير متصل" : "لا جهاز مقترن";
    $("#chatDevice").innerHTML = `📱 <small>اقترن جهازك من تبويب الأجهزة</small>`;
    $("#btnSend").disabled = true; $("#btnMic").disabled = true;
  }
}
function renderDevices() {
  const box = $("#deviceCards"); if (!box) return;
  box.innerHTML = S.devices.map((d) => {
    const live = d.connected && (Date.now() - (d.last_seen ?? 0) < 120000);
    const st = d.status !== "active" ? `<span class="st mid">⏳ ينتظر الاقتران</span>`
      : live ? `<span class="st on">● متصل</span>` : `<span class="st off">○ غير متصل</span>`;
    const mine = d.device_id === S.device ? `<span class="badge">جهازي</span>` : "";
    return `<div class="card dev"><span class="face">📱</span>
      <div class="who"><b>${esc(d.device_id)} ${mine}</b><small>${esc(d.model ?? "")} · Android ${esc(d.android_version ?? "?")}</small></div>${st}</div>`;
  }).join("") || `<p class="hint">لا أجهزة بعد — افتح التطبيق واضغط Connect.</p>`;
}
async function tryPair() {
  const pin = $("#pin").value.replace(/\D/g, "");
  const hint = $("#pairHint");
  if (pin.length !== 6) { hint.textContent = "أدخل الرمز المكوَّن من 6 أرقام."; return; }
  const pending = S.devices.filter((d) => d.status === "pending");
  if (!pending.length) { hint.textContent = "لا يوجد جهاز ينتظر — اضغط Connect في التطبيق أولاً."; return; }
  const dev = pending[pending.length - 1];
  const r = await jpost("/api/pair/confirm", { device_id: dev.device_id, pairing_code: pin });
  if (r?.ok) {
    S.device = dev.device_id; localStorage.setItem("aim_device", S.device);
    $("#pin").value = ""; hint.textContent = `✅ تم الاقتران مع ${dev.device_id}`;
    pollDevices(); syncDeviceChip();
    document.querySelector('#tabs button[data-tab="chat"]').click();
  } else {
    hint.textContent = r?.error === "WRONG_PIN" ? "الرمز غير صحيح." :
      r?.error === "PAIRING_EXPIRED" ? "انتهت صلاحية الرمز — اضغط Connect من جديد." : "فشل الاقتران، حاول مجددًا.";
  }
}
$("#btnPair").addEventListener("click", tryPair);
$("#pin").addEventListener("keydown", (e) => { if (e.key === "Enter") tryPair(); });
$("#pin").addEventListener("input", (e) => { if (e.target.value.replace(/\D/g, "").length === 6) tryPair(); });

/* ---------------- chat ---------------- */
const log = $("#chatLog");
function bubble(cls, html) {
  const el = document.createElement("div");
  el.className = "msg " + cls; el.innerHTML = html;
  log.appendChild(el); log.scrollTop = log.scrollHeight;
  return el;
}
const hello = () => log.querySelector(".hello")?.remove();

function stepLine(s) {
  if (s.type === "tool_call") return `<div class="step"><span class="t">${esc(s.tool)}</span><span>${esc(JSON.stringify(s.args ?? {}).slice(0, 70))}</span></div>`;
  if (s.type === "tool_result") return `<div class="step ${s.ok ? "ok" : "no"}"><span class="t">${esc(s.tool)}</span><span>${esc(s.summary ?? "")}</span></div>`;
  if (s.type === "info" && /الوكيل/.test(s.text ?? "")) return `<div class="step"><span>${esc(s.text)}</span></div>`;
  return "";
}
async function send(text, source = "web") {
  const msg = (text ?? "").trim();
  if (!msg || S.busy || !connectedDevice()) return;
  hello();
  bubble("user", esc(msg));
  $("#msg").value = ""; $("#msg").style.height = "auto";
  S.busy = true; $("#btnSend").disabled = true; $("#btnStop").hidden = false;

  const r = await jpost("/api/chat", { device_id: S.device, message: msg, model_id: S.model || null, agent_id: S.agent || null, source });
  if (!r?.ok) {
    bubble("ai", `⚠️ ${esc(r?.error ?? "خطأ غير معروف")}`);
    S.busy = false; $("#btnSend").disabled = !connectedDevice(); $("#btnStop").hidden = true;
    return;
  }
  followTurn(r.turn_id, r.agent_id);
}
async function followTurn(turnId, agentId) {
  const seq = ++S.turnSeq;
  const ag = S.agents.find((a) => a.id === agentId);
  const el = bubble("ai", `<span class="agent-tag">${ag ? ag.emoji + " " + esc(ag.name) : "🤖 Aiminos"}</span><div class="typing"><i></i><i></i><i></i></div>`);
  S.lastTurn = { id: turnId, agentId, el };

  const t0 = Date.now();
  while (Date.now() - t0 < 240000) {
    if (seq !== S.turnSeq) return;
    await new Promise((r) => setTimeout(r, 2500));
    const t = await jget("/api/turn/" + turnId);
    if (!t || seq !== S.turnSeq) return;
    const visible = (t.steps ?? []).filter((s) => ["tool_call", "tool_result", "info"].includes(s.type));
    const agentTag = `<span class="agent-tag">${ag ? ag.emoji + " " + esc(ag.name) : "🤖 Aiminos"}</span>`;
    if (t.state === "thinking" || t.state === "awaiting_device") {
      el.innerHTML = agentTag + (visible.map(stepLine).join("") || "") + `<div class="typing"><i></i><i></i><i></i></div>`;
      log.scrollTop = log.scrollHeight;
      continue;
    }
    el.innerHTML = agentTag + esc(t.final_response ?? t.error ?? "—") +
      (visible.length ? `<div class="steps">${visible.map(stepLine).join("")}</div>` : "");
    log.scrollTop = log.scrollHeight;
    S.busy = false; $("#btnSend").disabled = !connectedDevice(); $("#btnStop").hidden = true;
    $("#btnResume").hidden = t.state !== "stopped";
    renderTeam(t.agent_id);
    return;
  }
  el.innerHTML = "⏱️ انتهت مهلة الانتظار.";
  S.busy = false; $("#btnSend").disabled = !connectedDevice(); $("#btnStop").hidden = true;
}
$("#btnSend").addEventListener("click", () => send($("#msg").value));
$("#msg").addEventListener("keydown", (e) => {
  if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); send($("#msg").value); }
});
$("#msg").addEventListener("input", (e) => { e.target.style.height = "auto"; e.target.style.height = Math.min(e.target.scrollHeight, 110) + "px"; });
$("#btnStop").addEventListener("click", async () => {
  if (!connectedDevice()) return;
  await jpost("/api/turn/stop", { device_id: S.device, source: "web" });
  $("#btnStop").hidden = true;
});
$("#btnResume").addEventListener("click", () => {
  $("#btnResume").hidden = true;
  send("أكمل المهمة السابقة من حيث توقفت.", "web");
});

/* ---------------- voice (Web) ---------------- */
const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
let rec = null, recOn = false;
$("#btnMic").addEventListener("click", () => {
  if (!SR) { $("#micState").hidden = false; $("#micState").textContent = "⚠️ المتصفح لا يدعم الصوت — استعمل التطبيق 🎤"; setTimeout(() => $("#micState").hidden = true, 3000); return; }
  if (recOn) { rec.stop(); return; }
  rec = new SR(); rec.lang = "ar-DZ"; rec.interimResults = false; rec.maxAlternatives = 1;
  rec.onstart = () => { recOn = true; $("#btnMic").classList.add("rec"); $("#micState").hidden = false; };
  rec.onend = () => { recOn = false; $("#btnMic").classList.remove("rec"); $("#micState").hidden = true; };
  rec.onerror = () => { recOn = false; $("#btnMic").classList.remove("rec"); $("#micState").hidden = true; };
  rec.onresult = (e) => { const t = e.results[0][0].transcript; if (t) send(t, "voice"); };
  rec.start();
});

/* ---------------- team ---------------- */
function renderTeam(activeId) {
  const box = $("#teamCards"); if (!box) return;
  box.innerHTML = S.agents.map((a) => `
    <div class="card agent ${a.id === activeId ? "active-work" : ""}">
      <div class="face">${a.emoji}</div>
      <h4>${esc(a.name)}</h4>
      <div class="tag">${esc(a.tagline)}</div>
      <div class="meta">
        <span class="pill acc">${a.max_steps} خطوة</span>
        <span class="pill">${a.tools === "all" ? "كل الأدوات" : a.tools + " أدوات"}</span>
        <span class="pill">${esc(a.id)}</span>
      </div>
    </div>`).join("");
}

/* ---------------- memory ---------------- */
async function loadMemory() {
  if (!S.device) { $("#factList").innerHTML = `<li><span>اقترن جهازًا أولاً</span></li>`; return; }
  const m = await jget("/api/memory/" + encodeURIComponent(S.device));
  if (!m?.ok) return;
  const fl = $("#factList");
  fl.innerHTML = (m.facts ?? []).map((f) =>
    `<li><b>${esc(f.k)}</b><span>${esc(f.v)}</span><button data-k="${esc(f.k)}" title="حذف">🗑</button></li>`
  ).join("") || `<li><span>الذاكرة فارغة — قل لـ Aiminos «تذكر …»</span></li>`;
  fl.querySelectorAll("button").forEach((b) => b.addEventListener("click", async () => {
    await jpost("/api/memory/delete", { device_id: S.device, key: b.dataset.k }); loadMemory();
  }));
  $("#memSummary").textContent = m.summary || "لا ملخص بعد — يُولَّد تلقائيًا مع الحوارات.";
  $("#memHist").innerHTML = (m.history ?? []).slice().reverse().map((h) =>
    `<li><b>${h.role === "user" ? "أنت" : "Aiminos"}:</b> ${esc(h.text.slice(0, 120))}</li>`
  ).join("") || `<li>لا حوارات بعد.</li>`;
}
$("#memAdd").addEventListener("submit", async (e) => {
  e.preventDefault();
  const k = $("#memKey").value.trim(), v = $("#memVal").value.trim();
  if (!k || !v || !S.device) return;
  await jpost("/api/memory/add", { device_id: S.device, key: k, value: v });
  $("#memKey").value = ""; $("#memVal").value = ""; loadMemory();
});

/* ---------------- settings: models ---------------- */
function renderModelCards() {
  const box = $("#modelCards"); if (!box) return;
  box.innerHTML = S.models.map((m) => `
    <div class="card agent" data-id="${esc(m.id)}" style="cursor:pointer;${m.id === S.model ? "border-color:var(--acc);box-shadow:0 0 0 1px var(--acc)" : ""}">
      <div class="face">${m.supportsVision ? "👁️" : m.id.includes("Fast") ? "⚡" : "🧠"}</div>
      <h4>${esc(m.displayName)} ${m.default ? '<span class="pill acc">افتراضي</span>' : ""}</h4>
      <div class="tag">${m.supportsVision ? "يرى الصور" : "نصوص وأدوات"} · مجاني</div>
    </div>`).join("");
  box.querySelectorAll(".agent").forEach((c) => c.addEventListener("click", () => {
    S.model = c.dataset.id; localStorage.setItem("aim_model", S.model);
    $("#modelSel").value = S.model; renderModelCards();
  }));
}

/* ---------------- logs ---------------- */
let logsOn = false;
async function refreshLogs() {
  if (!$("#tab-logs").classList.contains("on")) { logsOn = false; return; }
  const l = await jget("/api/logs");
  if (l?.ok) {
    const lines = (l.logs ?? l.events ?? []).map((e) =>
      `${new Date(e.ts).toLocaleTimeString("ar-DZ")}  ${e.event}  ${JSON.stringify(e).slice(0, 140)}`).join("\n");
    $("#logView").textContent = lines || "—";
  }
  if (logsOn) setTimeout(refreshLogs, 3000);
}
new MutationObserver(() => { logsOn = $("#tab-logs").classList.contains("on"); if (logsOn) refreshLogs(); })
  .observe($("#tabs"), { attributes: true, subtree: true, attributeFilter: ["class"] });

boot();
