# ▲ DroidPilot AI

**Android AI Agent** — an AI model (via a Cloudflare Worker) that sees and controls an Android phone through the Accessibility API. No root, no Shizuku required for the MVP.

```
USER → DroidPilot Chat → Cloudflare Worker → AI Model → Tool Call
     → Android DroidPilot → Accessibility / Android APIs / optional ADB
     → TikTok (or any app) → Result → AI → USER
```

**Live deployment**: `https://droidpilot-ai.turkjgastroenterol-org.workers.dev`

---

## Components

| Path | What it is |
|---|---|
| `android/` | Kotlin Android app (min SDK 26, target 34) — AccessibilityService, ForegroundService, ToolExecutor, CommandValidator, AdbBridge (optional) |
| `worker/` | Cloudflare Worker (TypeScript) — pairing, auth, chat API, AI orchestration, tool-calling loop, rate limiting, audit log |
| `web/` | Single-page Web UI — chat, model selector, device pairing, LIVE LOG console |
| `docs/` | Architecture, deployment, testing, security, Android build guide (+ Arabic quick start) |
| `scripts/` | `deploy-cloudflare.sh`, `fake_device.py` (E2E test without a phone) |

## Tool registry (12 tools)

`get_device_info` · `get_current_package` · `get_screen_nodes` · `open_app` ·
`swipe_up` · `swipe_down` · `tap_element` · `press_back` · `type_text` ·
`take_screenshot` (last resort) · `get_logs` · `run_shell` (policy-gated)

### Shell command policy (`run_shell`)

| Command | Policy |
|---|---|
| `getprop`, `pm list packages`, `dumpsys`, `input swipe/tap`, `logcat -d`, … | SAFE |
| anything unknown (`am start`, `settings put`, …) | REQUIRES_CONFIRMATION (user approves in Web UI) |
| `rm`, `mkfs`, `dd`, `reboot`, `factory reset`, `su`, redirects/pipes | BLOCKED |

## Model registry

Models are declared **only** in `worker/src/models.ts` — nothing is hard-coded:

| Model | Provider | Reasoning | Tools | Vision |
|---|---|---|---|---|
| `@cf/openai/gpt-oss-20b` (default) | Cloudflare (env.AI) | ✓ | ✓ | ✗ |
| `@cf/meta/llama-3.3-70b-instruct-fp8-fast` | Cloudflare | ✗ | ✓ | ✗ |
| `@cf/meta/llama-4-scout-17b-16e-instruct` | Cloudflare | ✗ | ✓ | ✓ (disabled) |

Add an entry → it appears automatically in the Web UI selector. External providers (Groq, Ollama, OpenAI…) plug in through the `AIProvider` abstraction (`worker/src/ai/`).

## Quick start

### 1. Deploy the Worker

```bash
cd worker && npm install
npx wrangler login                     # or set CLOUDFLARE_API_TOKEN + CLOUDFLARE_ACCOUNT_ID
bash ../scripts/deploy-cloudflare.sh   # creates KV namespaces, sets DEVICE_AUTH_SECRET, deploys
```

### 2. Build the Android app

Fastest: push to GitHub → the included Action builds the debug APK automatically (Artifacts tab).
Local: see `docs/ANDROID.md` (Android Studio or `gradle -p android :app:assembleDebug`).

### 3. Pair and drive

1. Open the app → set the **Worker URL** → **Connect** → note the `DROID-XXXX` + PIN
2. Enable the DroidPilot **Accessibility service** → **Start Connection**
3. Open the Web URL → enter the PIN under *Device Pairing* → chat:
   > افتح TikTok وانتقل للفيديو التالي

## Verified end-to-end (Workers AI, gpt-oss-20b)

```
USER : افتح TikTok وانتقل للفيديو التالي
AI   → open_app({"package_name":"com.zhiliaoapp.musically"})   ✓
AI   → get_current_package()                                   ✓ TikTok
AI   → swipe_up({"duration_ms":500})                           ✓
AI   : تمّ.
```

Reproduce it any time without a phone:

```bash
python3 scripts/fake_device.py https://<your-worker>.workers.dev "افتح TikTok"
```

## Design highlights

- **Tool calling, not text parsing** — the model returns native function calls; the worker executes them through a bounded loop (`MAX_TOOL_CALLS_PER_TURN = 20`, no infinite loops).
- **Screenshot policy** — Accessibility → Android APIs → Gestures → Screenshot last. Screenshots are capture → analyze → discard.
- **Data optimization** — compact JSON, node caps, and tree hashing: unchanged accessibility trees are reported as `{unchanged: true}` instead of being resent.
- **Error recovery** — `ELEMENT_NOT_FOUND` → re-observe → alternate strategy → user confirmation; the device never gets stuck retrying.
- **Security** — HTTPS only, device pairing (6-digit PIN, 15-min TTL), hashed device secrets (HMAC-peppered SHA-256), per-IP rate limits, audit log with no secrets. Secrets live in Cloudflare Secrets, never in the repo. See `docs/SECURITY.md`.

## Docs

- `docs/ARCHITECTURE.md` — protocol, KV schema, sequence diagrams, limits
- `docs/DEPLOYMENT.md` — step-by-step Cloudflare + GitHub deployment & rotation
- `docs/TESTING.md` — phase checklists, curl recipes, TikTok test script
- `docs/SECURITY.md` — threat model, secret handling, rotation guide
- `docs/ANDROID.md` — APK build (Studio/CLI/CI) + OPPO ColorOS battery setup
- `docs/QUICKSTART-AR.md` — دليل سريع بالعربية
