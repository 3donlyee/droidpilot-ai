# Architecture

## High-level flow

```
USER
  │  (chat message in Web UI)
  ▼
Cloudflare Worker  ────────────────  Cloudflare D1 (sessions, audit logs)
  │  HTTPS  ↑↓
  │  WSS    ↑↓  (persistent command channel)
  ▼
DroidPilot Android App
  │  Foreground Service
  │  ├── WorkerClient (HTTPS + WebSocket)
  │  ├── Tool Executor
  │  │     ├── AccessibilityTools   (read tree, tap element)
  │  │     ├── GestureTools         (swipe, tap coords)
  │  │     ├── AppTools             (open app, current package)
  │  │     ├── ShellTools           (run_shell w/ policy)
  │  │     └── ScreenshotTools      (on-demand capture)
  │  ├── CommandValidator           (allowlist + safety check)
  │  ├── AdbBridge (OPTIONAL)      (no root, local ADB over Wi-Fi)
  │  └── DroidPilotAccessibilityService (Android Accessibility)
  ▼
TikTok / any target app
```

## Why this design

### 1. Accessibility-first (not vision-first)
Reading the Accessibility tree is **O(1)** in cost and **O(n)** in tokens (n = nodes),
whereas screenshots require base64 encoding + multimodal model tokens. For routine
operations (tap a Like button, scroll feed, switch tab), the tree is enough.

**Hierarchy of evidence** used by the agent:
1. Accessibility tree (preferred)
2. Android system APIs (package manager, activity manager)
3. Gesture APIs (programmable taps/swipes via AccessibilityService.dispatchGesture)
4. Screenshot + OCR (only when the tree is empty or AI explicitly requests it)

### 2. Cloudflare Worker as the only public endpoint
- The Android device **never** exposes a public port. It only makes outbound HTTPS/WSS
  connections to the Worker.
- The Worker is the AI orchestrator + auth gateway + rate limiter + audit logger.
- Workers AI binding (`env.AI`) provides inference without per-request API keys.

### 3. Tool calling (not free-text commands)
The AI model emits structured tool calls (`{tool, arguments}`) which the Worker routes
to the device. This is verifiable, sandboxable, and prevents prompt-injection-driven
arbitrary shell execution.

### 4. AIProvider abstraction
```
AIProvider (interface)
  ├── CloudflareAIProvider   (uses env.AI — Workers AI binding)
  ├── OpenAIProvider         (uses OPENAI_API_KEY secret — any OpenAI-compatible endpoint)
  └── (future) GroqProvider, OllamaProvider, LocalProvider
```
Switching models = switching one entry in the Model Registry. No code changes elsewhere.

### 5. Model Registry
A single TypeScript module (`worker/src/models/registry.ts`) lists every model with
its provider, capabilities (tools/vision), and enabled flag. Adding a model = adding
one entry. The Web UI's Model Selector reads this registry dynamically.

## Tool calling loop

```
User message
  │
  ▼
[Worker] build prompt + tool schemas → call AIProvider
  │
  ▼
[AI] returns:  text response  OR  N tool_calls
  │
  ├── if text:  stream back to user. DONE.
  │
  └── if tool_calls:  for each (up to MAX_TOOL_CALLS_PER_TURN=20)
        │
        ▼
      [Worker] route command → Device via WSS
        │
        ▼
      [Device] ToolExecutor runs tool → returns result
        │
        ▼
      [Worker] append tool_result to conversation
        │
        ▼
      [Worker] call AIProvider again with updated context
        │
        ▼
      ...loop until AI emits final text or limit reached
```

### Loop safety
- `MAX_TOOL_CALLS_PER_TURN = 20` (configurable via env).
- Hash-dedup of `get_screen_nodes` responses — if the tree hasn't changed, return cached.
- No-op detection: if AI calls the same tool with the same args 3× in a row with the
  same failing result, the Worker injects a system message asking AI to try a different
  strategy or request user confirmation.

## Device pairing

```
[Android app on first launch]
  │
  ├── POST /api/device/register
  │     { device_name, model, android_version }
  │
  ▼
[Worker]
  - generates device_id     (e.g. DROID-AB91)
  - generates pairing_code  (6-digit PIN, valid 10 min)
  - generates device_secret (32-byte random, stored hashed in D1)
  - returns all three to the device (the device stores device_secret locally)

[User]
  - opens Web UI
  - enters device_id + pairing_code
  - Web UI POST /api/device/pair → Worker verifies PIN, issues session JWT

[All subsequent requests]
  - Authorization: Bearer <session JWT>
  - Worker resolves to device_id, routes commands via WSS
```

## Data optimization

| Strategy | Where | What |
|----------|-------|------|
| Compact JSON | `get_screen_nodes` | Strip empty fields, omit nodes with no text/desc/clickable |
| Dedup hash | Worker | SHA-256 of canonical tree JSON; if unchanged, return `{unchanged:true}` |
| Batching | Worker → Device | Multiple tool calls in one WSS frame when independent |
| Diff mode (planned) | Worker → Device | Send only changed nodes when caller supplies `since=<hash>` |
| Screenshot lifecycle | Device | Capture → base64 → send → delete from device memory |

## Error recovery

When a tool returns `success: false`:
1. Worker records the error code (`ELEMENT_NOT_FOUND`, `TIMEOUT`, `PERMISSION_DENIED`, ...).
2. Worker injects the error into the conversation as a `tool_result`.
3. AI decides next step — typically: `get_screen_nodes()` → re-evaluate.
4. If `get_screen_nodes` returns an unchanged tree (same hash) and AI retries the same
   failing call → Worker forces `take_screenshot` to give AI new evidence.
5. If 3 retries fail → Worker sends a `needs_confirmation` event to the user.

No infinite retries. No silent failures.

## Background execution

The Android app runs as a **Foreground Service** with a persistent notification
("DroidPilot AI is connected"). This is required by Android 8+ for long-running network
connections and is the only way to keep the WSS channel alive when the app is backgrounded.

The service is **explicitly visible** to the user (notification cannot be dismissed
while service is running). This is intentional — hidden services violate Play Store
policy and user trust.

## Optional ADB

`AdbBridge` is a **completely optional** layer. If Wireless Debugging is enabled on the
device AND the user explicitly opts in via in-app toggle, the bridge connects to
`localhost:5555` and exposes a small set of ADB-backed tools (mostly for diagnostics).
If ADB is not available, `AdbBridge.isAvailable()` returns `false` and the system falls
back to Accessibility-only mode. **The MVP works fully without ADB.**

## Security boundaries

```
Internet
   │
   ▼
[Cloudflare Worker]  ← rate limit, JWT auth, tool allowlist, command validation
   │
   ▼ (WSS over TLS)
[DroidPilot Android]
   │
   ├── AccessibilityService  ← Android permission (user-granted, revocable)
   ├── ForegroundService     ← Android notification required
   └── AdbBridge (optional)  ← local only, never exposed to network
```

Every command flows through `CommandValidator` on the device before execution. Shell
commands are classified `SAFE / REQUIRES_CONFIRMATION / BLOCKED`. `BLOCKED` commands
(`rm -rf`, `factory reset`, `reboot`, etc.) are rejected before any side effect.
