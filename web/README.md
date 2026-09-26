# DroidPilot AI — Web UI
Static, dependency-free front-end for the DroidPilot AI agent. Connects to a
Cloudflare Worker, pairs with an Android device, and streams the AI
tool-calling loop over Server-Sent Events. No build step, no framework, no
CDN. Four files.
## Files
`index.html` — UI shell. `styles.css` — dark
theme, CSS variables, CSS Grid, RTL aware. `app.js` — vanilla-JS app
(modular IIFE namespaces: `ui`, `config`, `debug`, `api`, `pairing`,
`models`, `chat`, exposed on `window.DroidPilot`).
## Quick start
Open `index.html` directly (`file:///…/web/index.html`), or serve:
```bash
python3 -m http.server 8080 --directory web
npx --yes serve web
```
ES `import`/`export` is intentionally not used so `file://` works; `app.js`
loads as a deferred regular script. Deploy: drop `web/` at the root of
Cloudflare Pages, Netlify, Vercel, or GitHub Pages. No build command.
## First-run flow
1. **Worker URL** — paste your Worker URL (default
   `https://droidpilot-ai.YOUR-SUBDOMAIN.workers.dev`). Click **Connect**.
   Calls `GET /` to verify it's a DroidPilot Worker.
2. **Pair device** — start the Android foreground service. Enter
   `Device ID` (e.g. `DROID-AB12`) + 6-digit `Pairing Code`. Click **Pair**.
   Calls `POST /api/device/pair`; stores JWT in `localStorage`.
3. **Model selector** — populates from `GET /api/models`. Info card shows
   provider, capabilities, status, context window.
4. **Chat** — type a message, press **Enter** (or click Send). Calls
   `POST /api/chat` with `Authorization: Bearer <JWT>` and reads the SSE
   stream: `tool_call` → inline card; `tool_result` → ✓/✗ badge;
   `assistant` → AI message (RTL aware); `done` → stream ends;
   `error` → red banner.
## Debug console
Eye icon in header toggles it. Color-coded: `USER` cyan, `AI` teal,
`ANDROID` green, `TOOL` yellow, `ERROR` red. Format: `[HH:MM:SS] TYPE: …`.
Auto-scrolls; capped at 500 lines. **Esc** or scrim click closes. Trash
icon clears. Desktop: bottom drawer ~220 px. Mobile: 75vh.
## Customization
All colors are CSS custom properties on `:root` in `styles.css`. Common
tweaks: `--bg`, `--panel`, `--accent` (teal `#00d4aa`), `--text`,
`--muted`, `--sidebar-w` (320 px), `--debug-h` (220 px). Swap the favicon
by replacing the `<link rel="icon">` data URI in `index.html`.
## Security
- **HTTPS only** — Worker URL must be `https://`; `http://localhost`,
  `127.0.0.1`, `[::1]` are permitted for local dev (`wrangler dev`).
- **JWT** in `localStorage` under `droidpilot.config.v1`. MVP only — for
  production prefer a short-lived JWT in an **HttpOnly + Secure +
  SameSite=Strict cookie** issued by the Worker. To migrate: in
  `api.request`, switch `credentials: 'omit'` to `'include'` and drop the
  `Authorization` header.
- **Pairing code** cleared from the input after a successful pair. Never
  logged or persisted.
- **XSS**: dynamic text via `textContent` /
  `appendChild(document.createTextNode(...))`. The only `innerHTML`
  uses are on `<svg>` elements built from trusted static path strings
  (`ui.icon()` + brand logo). Tool args/results are `JSON.stringify`'d
  into `<pre>` via `textContent`.
- **CORS**: all `fetch` calls use `mode: 'cors'`, `credentials: 'omit'`.
- **No secrets in source** — no API keys, no real Worker URLs, no tokens.
## API contract
| Method | Path | Auth | Body / Shape |
|--------|------|------|--------------|
| GET | `/` | none | `{ ok, service, version }` |
| GET | `/api/models` | none | `{ models: ModelDefinition[] }` |
| POST | `/api/device/pair` | none | `{ device_id, pairing_code }` → `{ token, device_id, device_name }` |
| GET | `/api/device/status` | Bearer | `{ device_id, device_name, model, paired_at, session_expires_at }` |
| POST | `/api/chat` | Bearer | `{ message, model? }` → SSE stream |
SSE event shapes (`data:` lines from `POST /api/chat`):
```jsonc
data: {"type":"tool_call","data":{"call":{"id":"…","function":{"name":"tap_element","arguments":"{\"element_id\":\"node_abc\"}"}}}}
data: {"type":"tool_result","data":{"callId":"…","success":true,"data":{…}}}
data: {"type":"assistant","data":{"text":"I tapped the search button."}}
data: {"type":"done","data":{"reason":"stop","totalToolCalls":3}}
data: {"type":"error","data":{"message":"device_offline"}}
```
## Troubleshooting
- `Worker responded but is not a DroidPilot endpoint` — Worker returned JSON but `service !== 'droidpilot-ai'`.
- `HTTP 0` / `Failed to fetch` — CORS pre-flight failed. Set `ALLOWED_ORIGIN=*` (dev) or your origin.
- Models dropdown shows "Failed: …" — `GET /api/models` error. Check Worker logs.
- Pairing stuck — Android foreground service not started, or pairing code expired (10 min).
- Chat input disabled after pair — JWT expired (24h). Disconnect and pair again.
- `localStorage` not persisting on `file://` — use a static server.
Latest Chrome / Edge / Firefox / Safari. Total uncompressed under 50 KB.
