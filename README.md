# DroidPilot AI

> **Android AI Agent** powered by Cloudflare Workers AI — controlled via natural language, executes on the device through Accessibility / Gesture / Android APIs.

```
USER  →  Web UI  →  Cloudflare Worker  →  Workers AI
                                         ↓ (function/tool call)
                                    Android DroidPilot
                                         ↓
                          Accessibility / Android APIs / optional ADB
                                         ↓
                                TikTok / any app
                                         ↓
                                      Result
                                         ↓
                                        AI
                                         ↓
                                       USER
```

---

## ✨ Features

| Capability | Status |
|------------|--------|
| Accessibility tree reading (text, bounds, clickable, actions) | ✅ Phase 1 |
| Tools: open_app, swipe, tap, type, back, screenshot, shell | ✅ Phase 1 |
| Foreground service with notification | ✅ Phase 1 |
| Cloudflare Worker + Workers AI binding | ✅ Phase 2 |
| Device pairing (PIN-based) | ✅ Phase 3 |
| AI Tool Calling loop (max 20 calls/turn) | ✅ Phase 4 |
| Model Registry + Selector | ✅ Phase 5 |
| Web UI with chat + live debug console | ✅ Phase 6 |
| Optional ADB bridge (no root, no Shizuku) | ✅ Phase 7 |
| No root required | ✅ |
| No Shizuku required (MVP) | ✅ |

---

## 📱 Target Device (tested)

- **OPPO Reno5 (CPH2159)**
- Android 13, ARM64, ColorOS
- No Root

Should work on most Android 10+ devices with Accessibility permission.

---

## 🗂 Repository structure

```
droidpilot-ai/
├── android/        # Kotlin Android app (Accessibility Agent + Tool Executor)
├── worker/         # Cloudflare Worker (TypeScript) — AI orchestration + device routing
├── web/            # Static Web UI (chat + debug console)
├── docs/           # Architecture, deployment, security, API docs
└── README.md
```

---

## 🚀 Quick start

### 1) Cloudflare Worker (backend)

```bash
cd worker
npm install
cp .dev.vars.example .dev.vars      # then edit local secrets (NEVER commit)
npm run dev                         # local dev on http://localhost:8787
npm run deploy                      # deploy to Cloudflare
```

Set production secrets via wrangler (NOT in source):

```bash
npx wrangler secret put DEVICE_AUTH_SECRET
npx wrangler secret put OPENAI_API_KEY        # optional, only if using OpenAI provider
```

### 2) Android app (APK)

```bash
cd android
# Open in Android Studio, or build via Gradle wrapper:
./gradlew assembleDebug
# APK: android/app/build/outputs/apk/debug/app-debug.apk
```

See **[docs/DEPLOYMENT.md](docs/DEPLOYMENT.md)** for full instructions including
Accessibility permission setup, foreground service, and pairing with the Worker.

### 3) Web UI

Open `web/index.html` in a browser, or serve statically:

```bash
cd web && npx serve .
```

Enter your Worker URL + pairing PIN → start chatting with your device.

---

## 🔐 Security model

- **No secrets in APK / GitHub / source / wrangler.jsonc / README.**
- All secrets live in Cloudflare Secrets or environment variables.
- Device pairing uses short-lived PIN + per-device secret.
- HTTPS/WSS only. Rate limiting + command allowlist + audit logs.
- Shell commands are classified `SAFE / REQUIRES_CONFIRMATION / BLOCKED`.
- Screenshots are **not** sent continuously — captured on demand only.

See **[docs/SECURITY.md](docs/SECURITY.md)** for full policy.

---

## 🤖 AI Models

The Model Registry (`worker/src/models/registry.ts`) defines all available models.
Adding a model = adding one entry — no code changes elsewhere.

Default: `@cf/openai/gpt-oss-20b` (Workers AI, free tier).

Provider abstraction (`AIProvider`) lets you add Groq / OpenAI / Ollama / Local LLM
without rewriting the agent engine.

---

## 🛠 Tools

| Tool | Description |
|------|-------------|
| `get_device_info` | Model, OS, screen size, battery |
| `get_current_package` | Foreground app package name |
| `get_screen_nodes` | Accessibility tree (compact JSON) |
| `open_app(package_name)` | Launch an app |
| `swipe_up` / `swipe_down` | Vertical gestures |
| `tap_element(element_id)` | Tap an accessibility node |
| `press_back` | System back |
| `type_text(text)` | Input text into focused field |
| `take_screenshot` | On-demand screenshot (not continuous) |
| `run_shell(command)` | Shell with SAFE/CONFIRM/BLOCKED policy |
| `get_logs` | Recent device logs |

See **[docs/API.md](docs/API.md)** for full protocol.

---

## 📚 Documentation

- [Architecture](docs/ARCHITECTURE.md)
- [Deployment guide](docs/DEPLOYMENT.md)
- [Security policy](docs/SECURITY.md)
- [API / Protocol reference](docs/API.md)

---

## ⚠️ Disclaimer

This project controls a real Android device via Accessibility. Use only on devices
you own. The authors are not responsible for misuse.

---

## 📄 License

MIT — see [LICENSE](LICENSE).
