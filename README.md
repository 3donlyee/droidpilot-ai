# DroidPilot AI ⚡

**DroidPilot AI** is an autonomous Android AI Agent that bridges Cloudflare Workers AI with an Android device to control apps, parse UI accessibility hierarchies, and execute multi-step tools autonomously.

Targeted and tested for **OPPO Reno5 (CPH2159, Android 13, ColorOS, No Root)** with zero reliance on root or Shizuku for the core MVP.

---

## 🌟 Key Features

- **Autonomous Tool Calling Loop**: User prompt → Cloudflare Worker AI → Function Call → Android Agent → Accessibility API / Gestures → TikTok / Target App → Result → AI Model (up to 20 turns with infinite-loop prevention).
- **Accessibility Engine**:
  - `get_screen_nodes()` with compact JSON, deduplication, hash caching, and diffing.
  - Smooth gestures: `swipe_up()`, `swipe_down()`, `tap_element()`, `press_back()`.
  - Input management: `type_text()`.
- **Strict Screenshot Policy**: Zero-screenshot reliance for normal workflows. High-speed Accessibility node tree evaluation is primary; screenshots are used strictly as a secondary fallback on demand.
- **Model Registry (`models.ts`)**: Decoupled AIProvider supporting:
  - `@cf/openai/gpt-oss-20b` (Reasoning + Tools)
  - `@cf/meta/llama-3.3-70b-instruct`
  - `@cf/meta/llama-3.1-8b-instruct`
  - Extensible to Groq, Local AI, and Ollama.
- **Device Pairing**: Secure 6-digit PIN pairing (`DROID-XXXX` + PIN) between Android app and Web Dashboard.
- **Optional ADB Bridge & Shell Policy**:
  - `SAFE`: `getprop`, `pm list packages`, `dumpsys`, `input swipe`, `input tap`, `date`, `uptime`
  - `REQUIRES_CONFIRMATION`: custom execution
  - `BLOCKED`: `rm`, `factory reset`, destructive commands
- **Live Debug Console**: Real-time event log stream with source filters (`ALL`, `SYSTEM`, `ACCESSIBILITY`, `AI`, `TOOL`, `WORKER`).

---

## 📁 Repository Structure

```
droidpilot-ai/
├── app/                  # Android APK Source (Kotlin, Jetpack Compose, Room, Accessibility)
│   ├── src/main/java/com/example/
│   │   ├── data/         # Models, Room DAOs, Entities, Repository
│   │   ├── engine/       # DeviceManager, ToolExecutor, CommandValidator, AdbBridge
│   │   ├── service/      # DroidPilotAccessibilityService, DroidPilotAgentService
│   │   ├── network/      # CloudflareClient (REST & Polling)
│   │   └── ui/           # Jetpack Compose Cybernetic M3 UI & ViewModels
├── worker/               # Cloudflare Worker (TypeScript, Workers AI, Tool Orchestrator)
│   ├── src/
│   │   ├── index.ts      # Main Router, Tool Loop, Web UI server
│   │   ├── models.ts     # Model Registry
│   │   ├── tools.ts      # Tool Definitions & JSON Schemas
│   │   ├── ai.ts         # AIProvider abstraction
│   │   └── pairing.ts    # Device Session & Command Queue
│   ├── wrangler.jsonc    # Cloudflare Worker Configuration (AI binding)
│   └── package.json
├── web/                  # Web Dashboard (HTML5, Modern Cybernetic CSS, JS)
│   ├── index.html
│   ├── style.css
│   └── app.js
├── docs/                 # Documentation
│   ├── ARCHITECTURE.md   # Architectural design & communication protocol
│   ├── TIKTOK_FLOW.md    # TikTok autonomous CUJ walkthrough
│   └── DEPLOYMENT.md     # Deployment commands and configuration
├── metadata.json
└── README.md
```

---

## 🚀 Quick Start & Installation

### 1. Build Android APK
```bash
gradle :app:assembleDebug
```
The APK is generated at `app/build/outputs/apk/debug/app-debug.apk`. Install it on your OPPO Reno5 (or any Android 8.0+ device).

### 2. Enable Accessibility Service
1. On phone, go to **Settings > Accessibility**.
2. Turn on **DroidPilot AI Agent Automation**.
3. Open the app to view your **Pairing PIN** (e.g. `482913`).

### 3. Deploy Cloudflare Worker
```bash
cd worker
npm install
npm run deploy
```

---

## 🎬 TikTok Autonomous Test Flow

1. In the Web UI or Android Chat, enter:
   ```
   "افتح TikTok وانتقل للفيديو التالي"
   ```
2. DroidPilot executes:
   1. `open_app("TikTok")` → launches `com.zhiliaoapp.musically`
   2. `get_current_package()` → confirms TikTok is foregrounded
   3. `get_screen_nodes()` → parses screen UI hierarchy
   4. `swipe_up()` → dispatches smooth upward gesture
   5. `get_screen_nodes()` → confirms next video is loaded
3. AI reports success to the user — completely without screenshots!

---

## 🔒 Security & Credential Notice

- **No Hardcoded Secrets**: Secrets and tokens are never committed to git or hardcoded into the APK.
- **Dynamic Configuration**: The Android app includes a Settings modal to update the Cloudflare Worker URL and rotate API keys anytime at runtime.
- **Temporary Deployment Keys**: Any disposable temporary tokens provided for initial deployment should be deleted/revoked immediately after verification.
