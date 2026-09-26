# Architecture

## Components & flow

```
┌──────────┐   HTTPS    ┌─────────────────────┐    env.AI    ┌────────────────┐
│  Web UI  │──────────▶│  Cloudflare Worker   │────────────▶│ Workers AI      │
│ (static) │◀──────────│  (this repo/worker)  │◀────────────│ gpt-oss-20b     │
└──────────┘           │  · pairing/auth      │             └────────────────┘
                       │  · chat API          │
┌──────────┐   HTTPS   │  · agent loop        │
│ Android  │──────────▶│  · command routing   │
│ DroidPilot│◀──────────│  · rate limit/audit  │
└──────────┘           └─────────────────────┘
   │ Accessibility tree · gestures · intents · (optional) ADB
   ▼
 TikTok / any app → Result → back to AI
```

## Turn state machine

```
        POST /api/chat
              │
              ▼
         ┌─────────┐   AI returns tool_call    ┌──────────────────┐
         │thinking │ ────────────────────────▶ │ awaiting_device  │
         └─────────┘                           └──────────────────┘
              ▲  ▲        device POSTs result        │  (45 s timeout)
              │  └───────────────────────────────────┘
              │ AI returns text / max calls / error
              ▼
        done | error
```

- `POST /api/chat` creates the turn and runs the first AI step in `waitUntil`.
- Each AI step: provider.chat() → if `tool_calls` → queue ONE command
  (`pendingcmd:<device>` key) → device long-polls → executes → POSTs result →
  the result handler appends the tool message and re-triggers the loop.
- `MAX_TOOL_CALLS_PER_TURN` (default 20) hard-stops the loop.
- Lazy expiry: `awaiting_device` > 45 s → `DEVICE_TIMEOUT`; stalled
  `thinking` > 90 s → `AI_TIMEOUT` (checked on turn reads).

## Protocol

Command (Worker → device, via long-poll GET /api/device/poll):

```json
{ "type": "command", "id": "cmd_ab12cd34", "tool": "swipe_up", "arguments": {}, "approved": false }
```

Result (device → Worker, POST /api/device/result):

```json
{ "type": "result", "id": "cmd_ab12cd34", "success": true, "data": { "direction": "up" } }
```

Error envelope: `{ "id", "success": false, "error_code": "ELEMENT_NOT_FOUND", "error": "…" }`

Error codes: `ELEMENT_NOT_FOUND`, `PACKAGE_NOT_FOUND`, `NO_WINDOW`,
`SERVICE_NOT_CONNECTED`, `BLOCKED_COMMAND`, `CONFIRMATION_REQUIRED`,
`SHELL_UNAVAILABLE`, `UNSUPPORTED_API`, `SCREENSHOT_TIMEOUT`,
`EXECUTION_FAILED`, `UNKNOWN_TOOL`.

Device auth: `x-device-id` + `x-device-secret` headers. The secret is shown
once at registration and stored encrypted (EncryptedSharedPreferences) on the
phone; the Worker stores only SHA-256(pepper + secret).

## API surface

| Endpoint | Auth | Purpose |
|---|---|---|
| `GET  /api/health` | — | liveness |
| `GET  /api/models` | — | model registry (public projection) |
| `GET  /api/devices` | — | device list for the Web UI |
| `POST /api/device/register` | rate-limited | bootstrap pairing (returns secret once) |
| `POST /api/pair/confirm` | rate-limited | confirm PIN from Web UI |
| `GET  /api/device/poll?wait=20` | device | long-poll for commands (max 25 s hold) |
| `POST /api/device/result` | device | report result, continues agent loop |
| `POST /api/device/heartbeat` | device | update info/last_seen |
| `POST /api/chat` | rate-limited | start a turn |
| `GET  /api/turn/:id` | — | turn steps/state for the UI |
| `GET  /api/logs` | — | audit ring buffer (LIVE LOG) |
| `POST /api/debug/ai` | DEBUG=true only | raw AI response inspection |

## KV schema (4 namespaces)

| Namespace | Keys |
|---|---|
| `KV_DEVICES` | `device:<id>` record · `pair:<pin>` → id (15 min TTL) · `turn:<device>` → turnId · `pendingcmd:<device>` command (5 min TTL) · `seen:<device>` presence (2 min TTL) |
| `KV_TURNS` | `turn:<id>` full turn (24 h TTL) |
| `KV_RATE` | `rate:<scope>:<window>` counters |
| `KV_LOGS` | `audit` ring buffer (last 300 events, sanitized) |

## Data optimization

- Compact JSON everywhere (short field names, capped text, node cap 250/400).
- Accessibility tree hashing (MD5 over package+elements): if unchanged, the
  device answers `{unchanged: true, hash}` instead of the full tree.
- Screenshots only on explicit tool call, downscaled to ≤720 px, JPEG q60,
  discarded after analysis — never stored.
- Device presence writes are throttled to 1 write / 2 min (KV write limits).

## Communication choice: HTTPS long-poll (not WSS)

WSS on Workers requires a Durable Object per connection. For the MVP we use
long-poll (`wait` up to 25 s, 2 s internal interval) which gives sub-3 s
command latency with zero stateful infra and survives ColorOS background
limits through the foreground service. The upgrade path (Durable Object +
WSS for push + presence) is described below.

## ADB bridge (optional)

`AdbBridge` is an isolated capability layer. The MVP reports
`isAvailable() == false`; `run_shell` then executes only app-permitted
commands and fails honestly otherwise (`SHELL_UNAVAILABLE`). A full
implementation would add:

1. Wireless-debugging pairing (Android 11+): PSAKE2+ over mDNS-discovered
   `adb-tls-pairing` socket (`adb_wifi_enabled` detection already wired).
2. ADB/TLS client to execute shell as `shell` uid.
3. Confirmation gating stays in `CommandValidator` either way.

The project never assumes ADB exists.

## Known MVP limits (and upgrades)

| Limit | Why | Upgrade |
|---|---|---|
| KV eventual consistency, ~1 write/s/key | free-tier KV | Durable Objects for turns/devices |
| One command at a time per device | simplicity | command queue depth N |
| AI step chained via `waitUntil` (≤30 s/step) | serverless | DO + alarms, or streaming |
| Web UI has no user accounts | single-admin MVP | Cloudflare Access in front |
