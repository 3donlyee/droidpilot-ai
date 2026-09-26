# DroidPilot AI — Cloudflare Worker

The backend orchestrator between the Web UI and the Android device.

```
Web UI  ──HTTP/SSE──▶  Worker  ──WSS──▶  Android (DroidPilot)
   ▲                     │
   │                     ▼
   └──SSE stream──── Workers AI / OpenAI-compatible provider
```

The Worker:

1. Receives chat messages from the Web UI.
2. Calls Workers AI (default `@cf/openai/gpt-oss-20b`) or any OpenAI-compatible provider.
3. When the AI emits tool calls, routes commands to the paired Android device over WebSocket.
4. Feeds device results back to the AI in a loop (max `MAX_TOOL_CALLS_PER_TURN` calls/turn, default 20).
5. Streams the AI's final text back to the Web UI via Server-Sent Events.

---

## Project layout

```
worker/
├── package.json
├── tsconfig.json
├── wrangler.jsonc
├── .dev.vars.example
├── .gitignore
├── README.md
└── src/
    ├── index.ts               # Worker entry — fetch (HTTP + WS upgrade)
    ├── env.ts                 # Env interface (bindings + secrets + vars)
    ├── systemPrompt.ts        # The system prompt sent to the AI
    ├── ai/
    │   ├── AIProvider.ts      # Provider-agnostic interface
    │   ├── CloudflareAIProvider.ts  # env.AI binding
    │   ├── OpenAIProvider.ts        # OpenAI/Groq/Ollama compatible
    │   └── AgentEngine.ts           # The orchestrator loop
    ├── auth/
    │   ├── AuthManager.ts     # HMAC-signed session JWTs
    │   └── PairingManager.ts  # Device pairing (PIN + secret)
    ├── device/
    │   └── DeviceRegistry.ts  # In-memory WS map + sendCommand with timeout
    ├── models/
    │   ├── types.ts           # ModelDefinition interface
    │   └── registry.ts        # MODEL_REGISTRY + helpers
    ├── routes/
    │   ├── pair.ts            # /api/device/{register,pair,status}
    │   ├── chat.ts            # /api/chat (SSE)
    │   ├── device.ts          # /api/device/ws (WebSocket upgrade)
    │   └── models.ts          # /api/models[/:id]
    ├── tools/
    │   ├── schemas.ts         # TOOL_SCHEMAS (12 tools)
    │   └── registry.ts        # TOOL_REGISTRY map + helpers
    └── utils/
        ├── logger.ts          # Structured logger (redacts secrets)
        ├── cors.ts            # CORS helpers
        ├── response.ts        # JSON / error response helpers
        ├── rateLimit.ts       # Token-bucket rate limiter (KV-backed)
        └── request.ts         # Bearer / IP / JSON body helpers
```

---

## Local development

### 1) Install dependencies

```bash
cd worker
npm install
```

### 2) Create the KV namespace (required for pairing + rate limiting)

```bash
npx wrangler kv namespace create DEVICE_SESSIONS
```

This prints something like:

```
{ "binding": "DEVICE_SESSIONS", "id": "abcd1234..." }
```

Paste that `id` into `wrangler.jsonc` in place of `REPLACE_WITH_KV_ID`.

> For local dev only, you can also run `npx wrangler kv namespace create DEVICE_SESSIONS --preview`
> and add a `preview_id` field. Wrangler will create a local KV shim during `wrangler dev`.

### 3) Create local dev secrets

```bash
cp .dev.vars.example .dev.vars
# Edit .dev.vars and set DEVICE_AUTH_SECRET to a 32-byte hex string:
#   openssl rand -hex 32
# Leave OPENAI_API_KEY blank unless you're using an external provider.
```

`.dev.vars` is gitignored — never commit it.

### 4) Run the dev server

```bash
npm run dev
# → http://localhost:8787
```

### 5) Type-check

```bash
npm run typecheck
```

---

## Deploying to production

### 1) Set production secrets (do NOT put them in `wrangler.jsonc`)

```bash
npx wrangler secret put DEVICE_AUTH_SECRET
# Paste a 32-byte random hex string. Generate one with:
#   openssl rand -hex 32

npx wrangler secret put OPENAI_API_KEY
# Only needed if you intend to use the OpenAI or Groq provider.
# Skip if you only use Workers AI.
```

### 2) Configure production CORS

In `wrangler.jsonc`, set `ALLOWED_ORIGIN` to your Web UI origin:

```jsonc
"vars": {
  "MAX_TOOL_CALLS_PER_TURN": "20",
  "DEFAULT_MODEL_ID": "@cf/openai/gpt-oss-20b",
  "ALLOWED_ORIGIN": "https://app.droidpilot.ai"
}
```

For multi-origin, use a comma-separated list:

```jsonc
"ALLOWED_ORIGIN": "https://app.droidpilot.ai,https://staging.droidpilot.ai"
```

Leave `"*"` for dev only.

### 3) Deploy

```bash
npm run deploy
```

The Worker will be live at `https://droidpilot-ai.<your-subdomain>.workers.dev`.

### 4) Watch logs

```bash
npm run tail
```

---

## API reference

### Public routes

| Method | Path | Description |
|--------|------|-------------|
| `GET`  | `/` | Health check (`{ ok: true, service: "droidpilot-ai", version: "0.1.0" }`). |
| `GET`  | `/api/models` | List enabled models. |
| `GET`  | `/api/models/:id` | Get one model by id. |
| `POST` | `/api/device/register` | Android first-launch registration. Body: `{ device_name, model, manufacturer?, android_version, sdk_int?, screen_width?, screen_height? }`. Returns `{ device_id, device_secret, pairing_code, expires_in_seconds }`. **Rate-limited: 30/min per IP.** |
| `POST` | `/api/device/pair` | Web UI redeems a pairing code for a JWT. Body: `{ device_id, pairing_code }`. Returns `{ token, device_id, device_name }`. **Rate-limited: 10/min per IP.** |

### Authenticated routes (require `Authorization: Bearer <session JWT>`)

| Method | Path | Description |
|--------|------|-------------|
| `GET`  | `/api/device/status` | Returns paired-device info + session expiry. |
| `POST` | `/api/chat` | Body `{ message, model? }`. Returns an SSE stream of agent events. **Rate-limited: 60/min per IP.** |
| `GET`  | `/api/device/ws` (or `/api/device/connect`) | WebSocket upgrade for Android. Auth via query string `?device_id=...&device_secret=...` OR first in-band message `{type:"auth", deviceId, secret}`. |

### SSE event shape (`/api/chat`)

```
data: {"type":"tool_call","data":{"call":{...}}}
data: {"type":"tool_result","data":{"callId":"...","success":true,"data":{...},"error":null}}
data: {"type":"assistant","data":{"text":"I opened TikTok and tapped the Like button."}}
data: {"type":"done","data":{"reason":"stop","totalToolCalls":3,"finalText":"..."}}
```

Possible event `type` values: `tool_call`, `tool_result`, `assistant`, `done`, `error`.

---

## Tools

The Worker exposes 12 tools to the AI (defined in `src/tools/schemas.ts`). They MUST match the names implemented on the Android side (`android/.../tools/`):

| Tool | Args |
|------|------|
| `get_device_info` | — |
| `get_current_package` | — |
| `get_screen_nodes` | — |
| `open_app` | `package_name` |
| `swipe_up` | — |
| `swipe_down` | — |
| `tap_element` | `element_id` |
| `press_back` | — |
| `type_text` | `text` |
| `take_screenshot` | — |
| `run_shell` | `command` |
| `get_logs` | `limit?` |

---

## Model registry

Defined in `src/models/registry.ts`. Adding a model = adding one entry.

| Model id | Provider | Tools | Vision | Enabled |
|----------|----------|-------|--------|---------|
| `@cf/openai/gpt-oss-20b` | cloudflare | ✓ | ✗ | ✓ (default) |
| `@cf/meta/llama-3.1-8b-instruct` | cloudflare | ✓ | ✗ | ✓ |
| `@cf/meta/llama-3.2-11b-vision-instruct` | cloudflare | ✓ | ✓ | ✓ |
| `@cf/qwen/qwen2.5-coder-32b-instruct` | cloudflare | ✓ | ✗ | ✓ |
| `gpt-4o-mini` | openai | ✓ | ✓ | ✗ (example) |
| `llama-3.3-70b-versatile` | groq | ✓ | ✗ | ✗ (example) |

To enable an external provider:

1. Flip `enabled: true` in the registry entry.
2. Set `OPENAI_API_KEY` via `wrangler secret put OPENAI_API_KEY`.
3. (For Groq, set the same key — it's reused.)

---

## Security notes

- **No secrets in source.** All secrets are managed via `wrangler secret put` and stored in Cloudflare's encrypted secret store. The `.dev.vars` file is gitignored.
- **`wrangler.jsonc` contains NO secrets.** The KV namespace id is `REPLACE_WITH_KV_ID` — you must replace it after creating the namespace.
- **Logger redacts** known secret field names (`secret`, `token`, `apiKey`, `device_secret`, `authorization`, `pairing_code`, etc.) before serialization.
- **CORS** defaults to `*` for dev; in production set `ALLOWED_ORIGIN` to your Web UI origin(s).
- **Rate limits**: 60/min on `/api/chat`, 10/min on `/api/device/pair`, 30/min on `/api/device/register` (per-IP, KV-backed token bucket).
- **All endpoints except `/`, `/api/device/register`, `/api/device/pair`, `/api/models` require a valid session JWT.**
- **JWT** is HMAC-signed with `DEVICE_AUTH_SECRET` (24h expiry). Tampering invalidates the signature.
- **Pairing code** is a 6-digit PIN with a 10-minute KV TTL.
- **`device_secret`** is a 32-byte random hex string returned ONCE during registration. The Android app stores it locally and uses it to authenticate the WebSocket.
- **No root / no ADB required** on the Android side. Shell commands are subject to a SAFE/CONFIRM/BLOCKED policy on the device.

---

## Troubleshooting

### `Missing KV namespace id`
You haven't replaced `REPLACE_WITH_KV_ID` in `wrangler.jsonc`. Run `npx wrangler kv namespace create DEVICE_SESSIONS` and paste the id.

### `DEVICE_AUTH_SECRET is not defined`
You haven't set the HMAC secret. Either:
- For dev: copy `.dev.vars.example` to `.dev.vars` and fill in the value.
- For prod: `npx wrangler secret put DEVICE_AUTH_SECRET`.

### WebSocket closes immediately with code 4001
Auth failed — either the `device_secret` is wrong, or the device wasn't registered/paired yet. Re-register the device via POST `/api/device/register`.

### `device_offline` on chat
The Android app isn't connected to the WebSocket. Make sure the foreground service is running and the worker URL is correct.

### AI doesn't call tools
Make sure the model in use has `supportsTools: true` in the registry. Some CF models may not support function calling — fall back to `@cf/openai/gpt-oss-20b` or `@cf/meta/llama-3.1-8b-instruct`.

### Agent hits max_tool_calls without finishing
Either the goal is genuinely complex, or the AI is stuck in a retry loop. The loop-break detector will inject a system message after 3 identical failures — check `wrangler tail` for `loop-break triggered` warnings.
