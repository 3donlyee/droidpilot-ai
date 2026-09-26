/* DroidPilot AI — Web UI. Vanilla JS, no build. Namespaces: ui, config,
 * debug, api, pairing, models, chat. JWT in localStorage; no tokens logged. */
'use strict';
(function DroidPilotApp() {
const ui = {
  $(s, r = document) { return r.querySelector(s); },
  el(tag, attrs = {}, kids = []) {
    const n = document.createElement(tag);
    for (const [k, v] of Object.entries(attrs)) {
      if (v == null || v === false) continue;
      if (k === 'class') n.className = v;
      else if (k === 'dataset') Object.assign(n.dataset, v);
      else if (k === 'style' && typeof v === 'object') Object.assign(n.style, v);
      else if (k.startsWith('on') && typeof v === 'function') n.addEventListener(k.slice(2).toLowerCase(), v);
      else if (k === 'html') n.innerHTML = v; // trusted static SVG only
      else n.setAttribute(k, v === true ? '' : String(v));
    }
    (Array.isArray(kids) ? kids : [kids]).forEach(c => {
      if (c == null || c === false) return;
      n.append(c.nodeType ? c : document.createTextNode(String(c)));
    });
    return n;
  },
  clear(n) { while (n && n.firstChild) n.removeChild(n.firstChild); },
  icon(paths, vb = '0 0 24 24') {
    const s = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    s.setAttribute('viewBox', vb); s.setAttribute('aria-hidden', 'true');
    s.innerHTML = paths; return s;
  },
  TOOL_ICON: '<path fill="currentColor" d="M14.7 6.3a4 4 0 0 0-5.4 5.4l-6 6 2.7 2.7 6-6a4 4 0 0 0 5.4-5.4l-2.1 2.1-2.1-.6-.6-2.1 2.1-2.1Z"/>',
  TOOL_CHECK: '<path fill="currentColor" d="M9 16.2 4.8 12l-1.4 1.4L9 19 21 7l-1.4-1.4L9 16.2Z"/>',
  TOOL_X: '<path fill="currentColor" d="M6.4 5 5 6.4 10.6 12 5 17.6 6.4 19 12 13.4 17.6 19 19 17.6 13.4 12 19 6.4 17.6 5 12 10.6 6.4 5Z"/>',
  detectDir(t) {
    if (!t) return 'ltr';
    return /[\u0600-\u06FF\u0750-\u077F\u08A0-\u08FF\uFB50-\uFDFF\uFE70-\uFEFF]/.test(t) ? 'rtl' : 'ltr';
  },
  summarize(v, max = 200) {
    if (v == null) return '(null)';
    let s; try { s = typeof v === 'string' ? v : JSON.stringify(v); } catch { s = String(v); }
    return s.length > max ? s.slice(0, max) + '…' : s;
  },
  hhmmss(d = new Date()) {
    const p = n => String(n).padStart(2, '0');
    return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
  },
};
const KEY = 'droidpilot.config.v1';
const DEFAULT_WORKER_URL = 'https://droidpilot-ai.YOUR-SUBDOMAIN.workers.dev';
const config = {
  _read()  { try { return JSON.parse(localStorage.getItem(KEY) || '{}'); } catch { return {}; } },
  _write(o){ try { localStorage.setItem(KEY, JSON.stringify(o)); } catch {} },
  _patch(p){ const n = { ...config._read(), ...p }; config._write(n); return n; },
  get workerUrl()  { return config._read().workerUrl  || DEFAULT_WORKER_URL; },
  set workerUrl(v)  { config._patch({ workerUrl: v }); },
  get token()       { return config._read().token      || null; },
  set token(v)      { config._patch({ token: v }); },
  get deviceId()    { return config._read().deviceId  || null; },
  set deviceId(v)   { config._patch({ deviceId: v }); },
  get deviceName()  { return config._read().deviceName || null; },
  set deviceName(v)  { config._patch({ deviceName: v }); },
  get modelId()     { return config._read().modelId   || null; },
  set modelId(v)    { config._patch({ modelId: v }); },
  get debugOpen()   { return config._read().debugOpen === true; },
  set debugOpen(v)  { config._patch({ debugOpen: !!v }); },
  clearSession() {
    const c = config._read();
    delete c.token; delete c.deviceId; delete c.deviceName;
    config._write(c);
  },
};
const debug = {
  TYPES: { USER: 'cyan', AI: 'teal', ANDROID: 'green', TOOL: 'yellow', ERROR: 'red' },
  els: {},
  mount() { debug.els.log = ui.$('#debug-log'); },
  log(type, msg) {
    if (!debug.els.log) return;
    const t = debug.TYPES[type] ? type : 'AI';
    const line = ui.el('div', { class: 'log-line', 'data-type': t }, [
      ui.el('span', { class: 'log-time mono' }, `[${ui.hhmmss()}]`),
      ui.el('span', { class: 'log-type' }, t + ':'),
      ui.el('span', { class: 'log-msg' }, String(msg)),
    ]);
    debug.els.log.appendChild(line);
    while (debug.els.log.children.length > 500) debug.els.log.removeChild(debug.els.log.firstChild);
    debug.els.log.scrollTop = debug.els.log.scrollHeight;
  },
  clear() { if (debug.els.log) ui.clear(debug.els.log); },
  toggle(open) {
    if (open === undefined) open = document.body.dataset.debug !== 'open';
    document.body.dataset.debug = open ? 'open' : 'closed';
    config.debugOpen = open;
    const btn = ui.$('#debug-toggle');
    if (btn) btn.setAttribute('aria-expanded', open ? 'true' : 'false');
    const scrim = ui.$('#debug-scrim');
    if (scrim) scrim.hidden = !open;
    const panel = ui.$('#debug-console');
    if (panel) panel.hidden = false;
  },
};
const api = {
  url(p) { return `${(config.workerUrl || '').replace(/\/+$/, '')}${p}`; },
  async request(path, { method = 'GET', body, token = config.token } = {}) {
    const headers = { Accept: 'application/json' };
    if (token) headers.Authorization = `Bearer ${token}`;
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    const res = await fetch(api.url(path), {
      method, headers,
      body: body !== undefined ? JSON.stringify(body) : undefined,
      mode: 'cors', credentials: 'omit',
    });
    if (!res.ok) {
      let d = '';
      try { const b = await res.json(); d = b?.error || b?.message || JSON.stringify(b); } catch {}
      const e = new Error(`HTTP ${res.status}: ${d || res.statusText}`);
      e.status = res.status; e.detail = d; throw e;
    }
    return res.json();
  },
  async health(workerUrl) {
    const base = (workerUrl || config.workerUrl || '').replace(/\/+$/, '');
    const res = await fetch(`${base}/`, { method: 'GET', mode: 'cors', credentials: 'omit' });
    if (!res.ok) throw new Error(`Worker responded ${res.status}`);
    return res.json();
  },
  listModels() { return api.request('/api/models').then(d => Array.isArray(d?.models) ? d.models : []); },
  deviceStatus() { return api.request('/api/device/status'); },
  pair(deviceId, code) {
    return api.request('/api/device/pair', {
      method: 'POST', body: { device_id: deviceId, pairing_code: code }, token: null,
    });
  },
  async chatStream(message, model, onEvent) {
    const headers = { 'Content-Type': 'application/json', Accept: 'text/event-stream' };
    if (config.token) headers.Authorization = `Bearer ${config.token}`;
    const res = await fetch(api.url('/api/chat'), {
      method: 'POST', headers, body: JSON.stringify({ message, model }),
      mode: 'cors', credentials: 'omit',
    });
    if (!res.ok || !res.body) {
      let d = '';
      try { const e = await res.json(); d = e?.error || e?.message || ''; } catch {}
      onEvent({ type: 'error', data: { message: `HTTP ${res.status}${d ? ': ' + d : ''}` } });
      return;
    }
    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let buf = '';
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      buf += decoder.decode(value, { stream: true });
      let sep;
      while ((sep = buf.indexOf('\n\n')) !== -1) {
        api._parse(buf.slice(0, sep), onEvent);
        buf = buf.slice(sep + 2);
      }
    }
    if (buf.trim()) api._parse(buf, onEvent);
  },
  _parse(chunk, onEvent) {
    for (const line of chunk.split('\n')) {
      const t = line.trim();
      if (!t || t.startsWith(':') || !t.startsWith('data:')) continue;
      const p = t.slice(5).trim();
      if (p === '[DONE]') { onEvent({ type: 'done', data: { reason: 'stream_end' } }); continue; }
      try { onEvent(JSON.parse(p)); }
      catch { debug.log('ERROR', `Bad SSE: ${p.slice(0, 80)}`); }
    }
  },
};
const pairing = {
  els: {},
  mount() {
    pairing.els = {
      workerUrl: ui.$('#worker-url-input'), connectBtn: ui.$('#connect-btn'),
      workerStatus: ui.$('#worker-status'),
      deviceId: ui.$('#device-id-input'), pairCode: ui.$('#pairing-code-input'),
      pairBtn: ui.$('#pair-btn'), pairStatus: ui.$('#pair-status'),
      disconnectBtn: ui.$('#disconnect-btn'),
    };
    pairing.els.workerUrl.value = config.workerUrl;
    pairing.els.connectBtn.addEventListener('click', pairing.connect);
    pairing.els.pairBtn.addEventListener('click', pairing.pair);
    pairing.els.disconnectBtn.addEventListener('click', pairing.disconnect);
    pairing.els.pairCode.addEventListener('input', e => {
      e.target.value = e.target.value.replace(/\D/g, '').slice(0, 6);
    });
    pairing.els.workerUrl.addEventListener('keydown', e => { if (e.key === 'Enter') pairing.connect(); });
    pairing.els.pairCode.addEventListener('keydown', e => {
      if (e.key === 'Enter' && !pairing.els.pairBtn.disabled) pairing.pair();
    });
  },
  setStatus(t, k) { pairing.els.pairStatus.textContent = t; pairing.els.pairStatus.dataset.kind = k || ''; },
  setWorkerStatus(t, k) { pairing.els.workerStatus.textContent = t; pairing.els.workerStatus.dataset.kind = k || ''; },
  setStatusBadge(state, label) {
    const b = ui.$('#status-badge');
    b.dataset.state = state;
    ui.$('.status-label', b).textContent = label;
    document.body.dataset.state = state;
  },
  async connect() {
    const url = (pairing.els.workerUrl.value || '').trim();
    if (!url) { pairing.setWorkerStatus('Enter a Worker URL.', 'error'); return; }
    // Security: HTTPS only — http://localhost/127.0.0.1/[::1] permitted for dev.
    let p; try { p = new URL(url); } catch { pairing.setWorkerStatus('Invalid URL.', 'error'); return; }
    const local = /^(localhost|127\.0\.0\.1|\[::1\])$/.test(p.hostname);
    if (p.protocol !== 'https:' && !(p.protocol === 'http:' && local)) {
      pairing.setWorkerStatus('Worker URL must be https:// (or http://localhost for dev).', 'error');
      debug.log('ERROR', 'Blocked non-https worker URL');
      return;
    }
    config.workerUrl = url;
    pairing.setWorkerStatus('Verifying worker…');
    pairing.setStatusBadge('connecting', 'Connecting');
    debug.log('ANDROID', 'Worker connect');
    pairing.els.connectBtn.disabled = true;
    try {
      const data = await api.health(url);
      if (!data?.ok || data?.service !== 'droidpilot-ai')
        throw new Error('Worker responded but is not a DroidPilot endpoint');
      pairing.setWorkerStatus(`Worker online (v${data.version || '?'}). Enter device credentials.`, 'success');
      pairing.setStatusBadge('paired', 'Worker OK');
      debug.log('ANDROID', `Worker v${data.version}`);
      pairing.els.deviceId.disabled = false;
      pairing.els.pairCode.disabled = false;
      pairing.els.pairBtn.disabled = false;
      if (config.token) await pairing.verifyExistingSession();
      else pairing.els.deviceId.focus();
      models.fetch();
    } catch (e) {
      pairing.setWorkerStatus(`Connection failed: ${e.message}`, 'error');
      pairing.setStatusBadge('disconnected', 'Disconnected');
      debug.log('ERROR', `Connect failed: ${e.message}`);
      pairing.els.connectBtn.disabled = false;
    }
  },
  async verifyExistingSession() {
    try {
      const s = await api.deviceStatus();
      if (s?.device_id) {
        config.deviceId = s.device_id;
        config.deviceName = s.device_name || s.device_id;
        pairing.setStatusBadge('connected', 'Connected');
        pairing.setWorkerStatus(`Session restored: ${s.device_name || s.device_id}`, 'success');
        pairing.afterPaired();
        debug.log('ANDROID', 'Session restored');
      }
    } catch (e) {
      debug.log('ERROR', `Session invalid: ${e.message}`);
      config.clearSession();
      pairing.setStatusBadge('paired', 'Worker OK');
    }
  },
  async pair() {
    const deviceId = (pairing.els.deviceId.value || '').trim();
    const code = (pairing.els.pairCode.value || '').trim();
    if (deviceId.length < 3) { pairing.setStatus('Invalid Device ID.', 'error'); return; }
    if (!/^\d{6}$/.test(code)) { pairing.setStatus('Pairing code must be 6 digits.', 'error'); return; }
    pairing.els.pairBtn.disabled = true;
    pairing.setStatus('Pairing…');
    pairing.setStatusBadge('connecting', 'Pairing');
    debug.log('USER', 'Pairing…');
    try {
      const r = await api.pair(deviceId, code);
      if (!r?.token) throw new Error('Server did not return a session token.');
      config.token = r.token;
      config.deviceId = r.device_id || deviceId;
      config.deviceName = r.device_name || r.device_id;
      pairing.setStatus('Paired successfully.', 'success');
      pairing.setStatusBadge('connected', 'Connected');
      debug.log('ANDROID', 'Paired');
      pairing.afterPaired();
      pairing.els.pairCode.value = '';
    } catch (e) {
      pairing.setStatus(`Pairing failed: ${e.message}`, 'error');
      pairing.setStatusBadge('paired', 'Worker OK');
      debug.log('ERROR', `Pair fail: ${e.message}`);
      pairing.els.pairBtn.disabled = false;
    }
  },
  afterPaired() {
    pairing.els.pairBtn.disabled = true;
    pairing.els.deviceId.disabled = true;
    pairing.els.pairCode.disabled = true;
    pairing.els.disconnectBtn.hidden = false;
    pairing.els.pairCode.value = '';
    const name = config.deviceName || config.deviceId || 'Device';
    const chatName = ui.$('#chat-device-name');
    chatName.textContent = name; chatName.classList.remove('muted');
    const input = ui.$('#message-input');
    input.disabled = false;
    ui.$('#send-btn').disabled = false;
    input.focus();
  },
  disconnect() {
    config.clearSession();
    pairing.els.pairBtn.disabled = false;
    pairing.els.deviceId.disabled = false;
    pairing.els.pairCode.disabled = false;
    pairing.els.disconnectBtn.hidden = true;
    pairing.els.deviceId.value = '';
    pairing.els.pairCode.value = '';
    const chatName = ui.$('#chat-device-name');
    chatName.textContent = 'No device connected';
    chatName.classList.add('muted');
    const input = ui.$('#message-input');
    input.disabled = true; input.value = '';
    ui.$('#send-btn').disabled = true;
    pairing.setStatusBadge('paired', 'Worker OK');
    pairing.setStatus('Disconnected. You can pair again.', '');
    debug.log('USER', 'Session disconnected');
  },
};
const models = {
  list: [], els: {},
  mount() {
    models.els = {
      select: ui.$('#model-select'), info: ui.$('#model-info'),
      provider: ui.$('#model-provider'), status: ui.$('#model-status'),
      context: ui.$('#model-context'),
      capTools: ui.$('#cap-tools'), capVision: ui.$('#cap-vision'),
      desc: ui.$('#model-desc'),
    };
    models.els.select.addEventListener('change', e => {
      const id = e.target.value;
      config.modelId = id;
      models.renderInfo(models.find(id));
      debug.log('AI', `Model selected: ${id}`);
    });
  },
  find(id) { return models.list.find(m => m.id === id) || null; },
  async fetch() {
    try {
      models.list = await api.listModels();
      models.renderSelect();
      debug.log('AI', `${models.list.length} models`);
    } catch (e) {
      debug.log('ERROR', `Models fail: ${e.message}`);
      ui.clear(models.els.select);
      models.els.select.appendChild(ui.el('option', { value: '' }, `Failed: ${e.message}`));
    }
  },
  renderSelect() {
    ui.clear(models.els.select);
    if (!models.list.length) {
      models.els.select.appendChild(ui.el('option', { value: '' }, 'No models available'));
      models.els.select.disabled = true;
      models.els.info.hidden = true;
      return;
    }
    models.els.select.disabled = false;
    for (const m of models.list) {
      models.els.select.appendChild(ui.el('option', { value: m.id }, `${m.displayName} (${m.provider})`));
    }
    const sel = models.find(config.modelId) || models.list[0];
    models.els.select.value = sel.id;
    models.renderInfo(sel);
  },
  renderInfo(m) {
    if (!m) { models.els.info.hidden = true; return; }
    models.els.info.hidden = false;
    models.els.provider.textContent = m.provider;
    models.els.context.textContent = m.contextWindow ? `${Math.round(m.contextWindow / 1000)}k tokens` : '—';
    models.els.status.dataset.on = m.enabled ? 'true' : 'false';
    models.els.status.textContent = m.enabled ? 'Available' : 'Unavailable';
    models.els.capTools.dataset.on = m.supportsTools ? 'true' : 'false';
    models.els.capTools.textContent = m.supportsTools ? '✓' : '✗';
    models.els.capVision.dataset.on = m.supportsVision ? 'true' : 'false';
    models.els.capVision.textContent = m.supportsVision ? '✓' : '✗';
    models.els.desc.textContent = m.description || '';
    ui.$('#chat-device-model').textContent = m.displayName;
  },
};
const chat = {
  els: {}, busy: false,
  pendingTools: new Map(), // callId → card element
  mount() {
    chat.els = {
      messages: ui.$('#messages'), input: ui.$('#message-input'),
      sendBtn: ui.$('#send-btn'), indicator: ui.$('#chat-indicator'),
      empty: ui.$('#empty-state'),
    };
    chat.els.sendBtn.addEventListener('click', chat.send);
    chat.els.input.addEventListener('keydown', e => {
      if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); chat.send(); }
    });
    chat.els.input.addEventListener('input', () => {
      const ta = chat.els.input;
      ta.style.height = 'auto';
      ta.style.height = `${Math.min(ta.scrollHeight, 160)}px`;
    });
  },
  setBusy(b) {
    chat.busy = b;
    chat.els.input.disabled = b || !config.token;
    chat.els.sendBtn.disabled = b || !config.token;
    chat.els.indicator.hidden = !b;
  },
  _scroll() { chat.els.messages.scrollTop = chat.els.messages.scrollHeight; },
  _hideEmpty() {
    if (chat.els.empty && chat.els.empty.parentNode) { chat.els.empty.remove(); chat.els.empty = null; }
  },
  _appendMessage(text, kind, meta) {
    chat._hideEmpty();
    const kids = meta ? [ui.el('div', { class: 'msg-body' }, text), ui.el('div', { class: 'msg-meta' }, meta)] : null;
    const b = ui.el('div', { class: `msg msg-${kind}`, 'data-dir': ui.detectDir(text) }, kids);
    if (!meta) b.textContent = text;
    chat.els.messages.appendChild(b);
    chat._scroll();
    return b;
  },
  renderUser(t) { chat._appendMessage(t, 'user'); debug.log('USER', t); },
  renderAssistant(t) { chat._appendMessage(t, 'ai'); debug.log('AI', t); },
  renderError(msg) {
    const c = ui.el('div', { class: 'msg msg-error' }, [
      ui.el('strong', {}, 'Error: '),
      document.createTextNode(msg),
    ]);
    chat.els.messages.appendChild(c);
    chat._scroll();
    debug.log('ERROR', msg);
  },
  renderToolCall(call) {
    chat._hideEmpty();
    const id = call?.id || `tool_${Date.now()}`;
    const name = call?.function?.name || 'unknown_tool';
    const argsRaw = call?.function?.arguments || '{}';
    let argsPretty;
    try { argsPretty = JSON.stringify(JSON.parse(argsRaw), null, 2); } catch { argsPretty = argsRaw; }
    const argsPre = ui.el('pre', { class: 'tool-args' });
    argsPre.textContent = argsPretty;
    const badge = ui.el('span', { class: 'tool-badge', 'data-state': 'pending' }, [
      ui.el('span', { class: 'spinner', 'aria-hidden': 'true' }), document.createTextNode('Pending'),
    ]);
    const card = ui.el('div', { class: 'tool-card', 'data-state': 'pending', 'data-call-id': id }, [
      ui.el('span', { class: 'tool-icon' }, [ui.icon(ui.TOOL_ICON)]),
      ui.el('span', { class: 'tool-name' }, name),
      badge,
      ui.el('div', { class: 'tool-args-label' }, 'Arguments'),
      argsPre,
    ]);
    chat.els.messages.appendChild(card);
    chat.pendingTools.set(id, card);
    chat._scroll();
    debug.log('TOOL', `→ ${name}(${argsRaw.length > 60 ? argsRaw.slice(0, 60) + '…' : argsRaw})`);
    return card;
  },
  updateToolResult(data) {
    const id = data?.callId;
    const card = chat.pendingTools.get(id);
    if (!card) { debug.log('TOOL', 'Unknown call'); return; }
    chat.pendingTools.delete(id);
    const success = !!data?.success;
    const icn = success ? ui.TOOL_CHECK : ui.TOOL_X;
    card.dataset.state = success ? 'success' : 'failure';
    const badge = card.querySelector('.tool-badge');
    if (badge) {
      badge.dataset.state = success ? 'success' : 'failure';
      ui.clear(badge);
      badge.appendChild(ui.icon(icn));
      badge.appendChild(document.createTextNode(success ? 'Success' : 'Failed'));
    }
    const iconWrap = card.querySelector('.tool-icon');
    if (iconWrap) { ui.clear(iconWrap); iconWrap.appendChild(ui.icon(icn)); }
    const pre = ui.el('pre', { class: 'tool-result' });
    pre.textContent = ui.summarize(success ? data?.data : (data?.error || '(unknown error)'), 600);
    card.appendChild(ui.el('div', { class: 'tool-result-label' }, success ? 'Result' : 'Error'));
    card.appendChild(pre);
    chat._scroll();
    debug.log('TOOL', `← ${data?.toolName || id} ${success ? '✓' : '✗'} ${ui.summarize(success ? data?.data : data?.error, 60)}`);
  },
  async send() {
    if (chat.busy) return;
    const text = chat.els.input.value.trim();
    if (!text) return;
    if (!config.token) { chat.renderError('Not paired — connect to a device first.'); return; }
    chat.renderUser(text);
    chat.els.input.value = '';
    chat.els.input.style.height = 'auto';
    chat.setBusy(true);
    const model = models.els.select?.value || config.modelId || undefined;
    debug.log('USER', `${model || 'default'}: ${text.slice(0, 40)}`);
    try { await api.chatStream(text, model, chat.handleSSE); }
    catch (e) { chat.renderError(`Stream failed: ${e.message}`); }
    finally { chat.setBusy(false); }
  },
  handleSSE(ev) {
    if (!ev || typeof ev !== 'object') return;
    const { type, data = {} } = ev;
    switch (type) {
      case 'tool_call':   if (data?.call) chat.renderToolCall(data.call); break;
      case 'tool_result': chat.updateToolResult(data); break;
      case 'assistant':   if (typeof data?.text === 'string') chat.renderAssistant(data.text); break;
      case 'done':
        if (data?.finalText && data?.reason === 'max_calls') chat.renderAssistant(data.finalText);
        debug.log('AI', `Done: ${data?.reason}, ${data?.totalToolCalls ?? 0} calls`);
        break;
      case 'error': chat.renderError(data?.message || 'Unknown error from server.'); break;
      default: debug.log('TOOL', `event: ${type}`);
    }
  },
};
function wireDebug() {
  ui.$('#debug-toggle').addEventListener('click', () => debug.toggle());
  ui.$('#debug-close').addEventListener('click', () => debug.toggle(false));
  ui.$('#debug-clear').addEventListener('click', () => debug.clear());
  ui.$('#debug-scrim').addEventListener('click', () => debug.toggle(false));
  document.addEventListener('keydown', e => {
    if (e.key === 'Escape' && document.body.dataset.debug === 'open') debug.toggle(false);
  });
  if (config.debugOpen && window.matchMedia('(min-width: 761px)').matches) debug.toggle(true);
}
function init() {
  debug.mount(); pairing.mount(); models.mount(); chat.mount(); wireDebug();
  ui.$('#worker-url-input').value = config.workerUrl;
  pairing.setStatusBadge('disconnected', 'Disconnected');
  debug.log('AI', 'DroidPilot AI Web UI ready.');
}
if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
else init();
window.DroidPilot = { ui, config, debug, api, pairing, models, chat };
})();
