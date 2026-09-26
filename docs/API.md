# API / Protocol Reference

Base URL: `https://droidpilot-ai.<your-subdomain>.workers.dev`

All request/response bodies are JSON (`Content-Type: application/json`) unless noted.
All endpoints except `/`, `/api/health`, `/api/device/register`, `/api/device/pair`, and `/api/models` require an `Authorization: Bearer <jwt>` header obtained from `/api/device/pair`.

---

## Health & metadata

### `GET /`
Returns service identity.

```json
{
  "service": "droidpilot-ai",
  "version": "1.0.0",
  "ok": true
}
```

### `GET /api/health`
Same as `/` but with `Cache-Control: no-store`.

### `GET /api/models`
List enabled AI models from the Model Registry.

**Auth:** none (public — used by Web UI before pairing).

**Response 200:**
```json
[
  {
    "id": "@cf/openai/gpt-oss-20b",
    "displayName": "GPT-OSS 20B",
    "provider": "cloudflare",
    "supportsTools": true,
    "supportsVision": false,
    "enabled": true,
    "contextWindow": 8192
  }
]
```

### `GET /api/models/:id`
Single model by ID. Returns `404` if not found or `enabled: false`.

---

## Device pairing

### `POST /api/device/register`
Called by the Android app on first launch (or after "Reset pairing").

**Auth:** none.

**Request:**
```json
{
  "device_name": "OPPO Reno5",
  "model": "CPH2159",
  "android_version": "13",
  "sdk_int": 33
}
```

**Response 200:**
```json
{
  "device_id": "DROID-AB91",
  "pairing_code": "482913",
  "device_secret": "<32-byte hex string>",
  "expires_in": 600
}
```

The Android app stores `device_id` and `device_secret` locally (SharedPreferences — TODO: migrate to EncryptedSharedPreferences). The user enters `device_id` + `pairing_code` in the Web UI within 10 minutes.

**Rate limit:** 30/min/IP.

### `POST /api/device/pair`
Called by the Web UI to redeem a pairing code.

**Auth:** none.

**Request:**
```json
{
  "device_id": "DROID-AB91",
  "pairing_code": "482913"
}
```

**Response 200:**
```json
{
  "jwt": "<HMAC-signed session token, 24h expiry>",
  "device": {
    "device_id": "DROID-AB91",
    "device_name": "OPPO Reno5",
    "model": "CPH2159",
    "android_version": "13",
    "connected": true
  }
}
```

**Response 401:** `pairing_code` invalid, expired, or already used.

**Rate limit:** 10/min/IP. Lockout: 5 failed attempts on the same `device_id` → 15-min lockout.

### `GET /api/device/status`
**Auth:** Bearer JWT.

Returns the current state of the paired device.

**Response 200:**
```json
{
  "device_id": "DROID-AB91",
  "device_name": "OPPO Reno5",
  "connected": true,
  "last_seen": "2026-09-26T10:30:00Z",
  "model": "CPH2159",
  "android_version": "13"
}
```

---

## Chat

### `POST /api/chat`
Streams an AI-generated response via Server-Sent Events (SSE).

**Auth:** Bearer JWT.

**Request:**
```json
{
  "message": "افتح TikTok وانتقل للفيديو التالي.",
  "model": "@cf/openai/gpt-oss-20b"
}
```
`model` is optional; defaults to `DEFAULT_MODEL_ID` from `wrangler.jsonc`. Must be one of the enabled models from `/api/models`.

**Response 200:** `Content-Type: text/event-stream`

Each event is `data: <json>\n\n`. Event types:

#### `tool_call` — AI is about to call a tool on the device
```json
{
  "type": "tool_call",
  "data": {
    "id": "call_001",
    "tool": "open_app",
    "arguments": { "package_name": "com.zhiliaoapp.musically" }
  }
}
```

#### `tool_result` — Device returned a result
```json
{
  "type": "tool_result",
  "data": {
    "id": "call_001",
    "success": true,
    "data": { "package": "com.zhiliaoapp.musically" }
  }
}
```

On failure:
```json
{
  "type": "tool_result",
  "data": {
    "id": "call_001",
    "success": false,
    "error": {
      "code": "ELEMENT_NOT_FOUND",
      "message": "No element matches id=node_001"
    }
  }
}
```

#### `assistant` — AI emitted text for the user
```json
{
  "type": "assistant",
  "data": { "content": "تم الانتقال للفيديو التالي." }
}
```

Multiple `assistant` events may be emitted during a single turn (the AI sometimes splits its response across the loop).

#### `done` — Turn complete, stream will close
```json
{
  "type": "done",
  "data": {
    "tool_calls_made": 4,
    "tokens_used": { "prompt": 1234, "completion": 87 }
  }
}
```

#### `error` — Unrecoverable error
```json
{
  "type": "error",
  "data": {
    "code": "DEVICE_OFFLINE",
    "message": "Device is not connected to the command channel"
  }
}
```
Stream closes after this event.

**Loop safety:** The Worker caps the number of tool calls per turn at `MAX_TOOL_CALLS_PER_TURN` (default 20). If the AI hits the cap, the Worker sends a `done` event with `tool_calls_made: 20` and a system-generated assistant message explaining the cap.

**Rate limit:** 60/min/IP.

---

## Device command channel (WebSocket)

### `GET /api/device/ws` (or `GET /api/device/connect`)
WebSocket upgrade. Used by the Android app to receive commands and send results.

**Auth (two supported modes):**

1. **Query string:** `?device_id=DROID-AB91&device_secret=<32-byte hex>` (used by Android app on WS open).
2. **In-band message:** Send `{ "type": "auth", "deviceId": "DROID-AB91", "secret": "<32-byte hex>" }` as the first message after open.

The Worker verifies the secret against the hashed value in KV. If invalid → close with code `4401` and reason `Unauthorized`.

### Messages: Worker → Device

#### `command`
```json
{
  "type": "command",
  "id": "cmd_001",
  "tool": "swipe_up",
  "arguments": {}
}
```

### Messages: Device → Worker

#### `result`
```json
{
  "type": "result",
  "id": "cmd_001",
  "success": true,
  "data": {}
}
```
On failure:
```json
{
  "type": "result",
  "id": "cmd_001",
  "success": false,
  "error": {
    "code": "ELEMENT_NOT_FOUND",
    "message": "..."
  }
}
```

#### `heartbeat`
Device sends every 30s to keep the WSS alive through proxies / NATs:
```json
{ "type": "heartbeat", "ts": 1727300000 }
```

#### `log` (optional)
Device can send log lines for the Worker to forward to the debug console:
```json
{ "type": "log", "level": "INFO", "message": "..." }
```

### Tool execution timeout
Each command has a 30-second timeout on the Worker side. If no `result` arrives within 30s, the Worker returns a `tool_result` with `error.code = "TIMEOUT"` to the AI.

---

## Tool catalog

### `get_device_info`
Returns hardware/software info.

**Arguments:** none.

**Result data:**
```json
{
  "deviceId": "DROID-AB91",
  "name": "OPPO Reno5",
  "model": "CPH2159",
  "manufacturer": "OPPO",
  "androidVersion": "13",
  "sdkInt": 33,
  "screenDensity": 480,
  "screenWidth": 1080,
  "screenHeight": 2400,
  "batteryLevel": 87,
  "isCharging": false
}
```

### `get_current_package`
Returns the foreground app's package name.

**Arguments:** none.

**Result data:**
```json
{ "package": "com.zhiliaoapp.musically" }
```

### `get_screen_nodes`
Returns the Accessibility tree of the active window as a compact JSON.

**Arguments:** none.

**Result data:**
```json
{
  "package": "com.zhiliaoapp.musically",
  "windowId": 12,
  "elements": [
    {
      "id": "node_001",
      "text": "Like",
      "description": "Like",
      "className": "android.widget.ImageView",
      "clickable": true,
      "enabled": true,
      "bounds": { "left": 400, "top": 1000, "right": 600, "bottom": 1100 }
    }
  ]
}
```

Empty fields are omitted (compact mode). The `id` is a stable hash of `bounds+text+desc+class`, so the same logical element has the same id across snapshots, enabling deduplication.

The Worker computes a SHA-256 of the canonical JSON. If unchanged from the previous call, it returns `{ "unchanged": true }` to the AI, saving tokens.

### `open_app`
**Arguments:** `{ "package_name": "com.zhiliaoapp.musically" }`

**Result data:** `{ "package": "<launched package>", "started": true }`

### `swipe_up` / `swipe_down`
**Arguments:** none. Default duration 300ms, default distance 75% of screen height.

**Result data:** `{ "performed": true }`

### `tap_element`
**Arguments:** `{ "element_id": "node_001" }`

Looks up the element by `id` in the current Accessibility tree. If found, performs `ACTION_CLICK` on the AccessibilityNodeInfo. If the node is not clickable, walks up the parent chain to find the nearest clickable ancestor.

**Result data:** `{ "clicked": true, "element_id": "node_001" }`

**Error codes:** `ELEMENT_NOT_FOUND`, `ELEMENT_NOT_CLICKABLE`.

### `press_back`
**Arguments:** none. Performs `GLOBAL_ACTION_BACK`.

**Result data:** `{ "performed": true }`

### `type_text`
**Arguments:** `{ "text": "hello world" }`

Finds the currently focused editable node and calls `ACTION_SET_TEXT`. If no editable node is focused, returns `NO_FOCUSED_INPUT`.

**Result data:** `{ "typed": "hello world" }`

### `take_screenshot`
**Arguments:** none.

Captures via MediaProjection (requires one-time user consent on Android). Returns base64-encoded PNG.

**Result data:**
```json
{
  "image": "iVBORw0KG...",
  "width": 1080,
  "height": 2400,
  "format": "png",
  "mime": "image/png"
}
```

After being sent to the AI, the screenshot is removed from device memory (not written to disk).

**Note (MVP limitation):** If MediaProjection consent has not been granted, returns `error.code = "SCREENSHOT_REQUIRES_PERMISSION"`. The Android app's MainActivity shows a consent dialog on first screenshot request.

### `run_shell`
**Arguments:** `{ "command": "getprop ro.product.model" }`

The command is first classified by `CommandValidator`:
- `SAFE` → executed via `Runtime.getRuntime().exec()`, output captured.
- `REQUIRES_CONFIRMATION` → returns `error.code = "REQUIRES_CONFIRMATION"` (Phase 2 will add an Android dialog).
- `BLOCKED` → returns `error.code = "BLOCKED"` without execution.

**Result data (SAFE):**
```json
{
  "exitCode": 0,
  "stdout": "CPH2159\n",
  "stderr": ""
}
```

**Note:** Shell commands require the `android.permission.INSTALL_PACKAGES` or similar elevated permission which is normally NOT available to a user app without root. In the MVP, `run_shell` only works for commands that the app can already execute (which is very limited). The full `run_shell` functionality requires the **optional ADB bridge** (Phase 7), which works if Wireless Debugging is enabled on the device.

### `get_logs`
Returns the last N lines from the device's in-memory Logger (see `util/Logger.kt`).

**Arguments:** `{ "lines": 100 }` (optional, default 100, max 500).

**Result data:**
```json
{
  "lines": [
    "[12:30:01] INFO  MainActivity: Service started",
    "[12:30:02] DEBUG WorkerClient: WSS connected"
  ],
  "count": 2
}
```

---

## Error codes

| Code | Meaning |
|------|---------|
| `ELEMENT_NOT_FOUND` | The `element_id` no longer exists in the tree (UI changed). |
| `ELEMENT_NOT_CLICKABLE` | The element exists but neither it nor any ancestor is clickable. |
| `NO_FOCUSED_INPUT` | `type_text` was called but no editable node has focus. |
| `PERMISSION_DENIED` | Accessibility service is not enabled, or a required Android permission is missing. |
| `TIMEOUT` | Tool execution exceeded 30s on the device. |
| `SCREENSHOT_REQUIRES_PERMISSION` | MediaProjection consent has not been granted. |
| `REQUIRES_CONFIRMATION` | Shell command classified as `REQUIRES_CONFIRMATION` (Phase 2 will add dialog). |
| `BLOCKED` | Shell command classified as `BLOCKED` — rejected before execution. |
| `ADB_NOT_AVAILABLE` | `run_shell` requires ADB bridge but it's not running. |
| `DEVICE_OFFLINE` | The paired device is not connected to the WSS channel. |
| `RATE_LIMITED` | Rate limit exceeded. Retry after `Retry-After` header value. |
| `MODEL_UNAVAILABLE` | Selected AI model is disabled or doesn't exist. |
| `MAX_TOOL_CALLS_EXCEEDED` | Turn exceeded `MAX_TOOL_CALLS_PER_TURN`. |
| `INTERNAL_ERROR` | Unhandled exception. Details in Worker logs. |

---

## Rate limits

| Endpoint | Limit |
|----------|-------|
| `POST /api/device/register` | 30 / min / IP |
| `POST /api/device/pair` | 10 / min / IP |
| `POST /api/chat` | 60 / min / IP (per JWT) |
| `GET /api/device/ws` (WS) | 5 concurrent connections / device |
| All other GET | 120 / min / IP |

Rate limits are enforced via a KV-backed token bucket per IP + per `device_id`.

---

## Versioning

The API is versioned via the URL path: `/api/v1/...`. The unversioned `/api/...` aliases the latest version. Breaking changes will increment the version.

Current version: **v1**.
