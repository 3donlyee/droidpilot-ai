# Deployment Guide

This guide covers the three deployable components of DroidPilot AI:

1. **Cloudflare Worker** — backend orchestration + Workers AI
2. **Android APK** — on-device agent (built locally on your dev machine)
3. **Web UI** — static front-end (Cloudflare Pages / GitHub Pages / any static host)

---

## Prerequisites

| Tool | Min version | Install |
|------|-------------|---------|
| Node.js | 20+ | https://nodejs.org |
| npm | 10+ | comes with Node |
| wrangler | 4.x | `npm install -g wrangler` or `npx wrangler` |
| Android Studio | Hedgehog 2023.1+ | https://developer.android.com/studio |
| JDK | 17 | bundled with Android Studio |
| Git | 2.40+ | https://git-scm.com |

A Cloudflare account with Workers AI enabled (free tier works for testing).
A GitHub account (or any git remote).

---

## 1. Cloudflare Worker

### 1.1 Clone + install

```bash
git clone https://github.com/<your-user>/droidpilot-ai.git
cd droidpilot-ai/worker
npm install
```

### 1.2 Authenticate wrangler

```bash
npx wrangler login
# Opens browser → grant access → returns to terminal
```

Or use an API token (CI / headless):

```bash
export CLOUDFLARE_API_TOKEN=<your-token>      # via Cloudflare dashboard → My Profile → API Tokens
# Account ID is auto-resolved by wrangler from the token's permissions.
```

> **Never commit** the API token. Use environment variables or a secrets manager.

### 1.3 Create KV namespace

The Worker uses a KV namespace called `DEVICE_SESSIONS` to store pairing codes and rate-limit counters:

```bash
npx wrangler kv namespace create DEVICE_SESSIONS
# Output:
# { "id": "abc123..." }
```

Paste the returned `id` into `wrangler.jsonc`, replacing `REPLACE_WITH_KV_ID`:

```jsonc
"kv_namespaces": [
  { "binding": "DEVICE_SESSIONS", "id": "abc123..." }
]
```

### 1.4 Set secrets

```bash
# Required: HMAC key for signing session JWTs (use a strong random value)
openssl rand -hex 32 | npx wrangler secret put DEVICE_AUTH_SECRET

# Optional: only if you enable the OpenAI provider in the Model Registry
npx wrangler secret put OPENAI_API_KEY
```

Secrets are stored encrypted in Cloudflare and never appear in source code, `wrangler.jsonc`, or `git`.

### 1.5 Local development

```bash
cp .dev.vars.example .dev.vars
# Edit .dev.vars with local secret values (NEVER commit .dev.vars — it's gitignored)
npm run dev
# → http://localhost:8787
```

### 1.6 Deploy

```bash
npm run deploy
# Output:
# Published droidpilot-ai (x.xx sec)
#   https://droidpilot-ai.<your-subdomain>.workers.dev
```

Update `web/index.html` default Worker URL and `android/app/src/main/res/values/strings.xml` hint to point to your deployed URL (or leave the placeholder — users can override at runtime via the UI fields).

### 1.7 Verify

```bash
curl https://droidpilot-ai.<your-subdomain>.workers.dev/api/health
# → { "ok": true, "service": "droidpilot-ai", "version": "1.0.0" }

curl https://droidpilot-ai.<your-subdomain>.workers.dev/api/models
# → [ { "id": "@cf/openai/gpt-oss-20b", "displayName": "GPT-OSS 20B", ... } ]
```

---

## 2. Android APK

### 2.1 Open in Android Studio

```bash
cd droidpilot-ai/android
# Open this folder in Android Studio
```

If `gradlew` is missing (no JAR shipped in repo), regenerate the wrapper once:

```bash
# Either via Android Studio: "File → Sync Project with Gradle Files"
# Or via system gradle (if installed):
gradle wrapper --gradle-version 8.9
```

The repo includes `bootstrap.sh` / `bootstrap.bat` helpers that do this automatically when gradle is on PATH.

### 2.2 Build

```bash
# Debug APK
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk

# Release APK (requires signing config — see below)
./gradlew assembleRelease
```

### 2.3 Sign release APK (one-time setup)

```bash
keytool -genkey -v -keystore droidpilot-release.keystore \
  -alias droidpilot -keyalg RSA -keysize 2048 -validity 10000
# Fill in the prompts. DO NOT commit the .keystore file (already gitignored).
```

Add to `~/.gradle/gradle.properties` (NOT in the repo):

```
DROIDPILOT_STORE_FILE=/absolute/path/to/droidpilot-release.keystore
DROIDPILOT_STORE_PASSWORD=<your-password>
DROIDPILOT_KEY_ALIAS=droidpilot
DROIDPILOT_KEY_PASSWORD=<your-password>
```

The `app/build.gradle.kts` already references these properties when present.

### 2.4 Install on OPPO Reno5

1. Connect phone via USB, enable Developer Options → USB Debugging.
2. `adb install -r app/build/outputs/apk/debug/app-debug.apk`
   Or copy the APK to the phone and tap it in Files.
3. Open "DroidPilot AI" from app drawer.
4. **Grant Accessibility permission**:
   - Tap "Enable Accessibility" in the app, OR
   - Settings → Accessibility → Installed Apps → DroidPilot AI → toggle On.
   - ColorOS may show a warning — accept it (the app needs Accessibility to read screen state).
5. Tap "Start Service" — the foreground service starts, notification appears.
6. The app displays a `device_id` (e.g. `DROID-AB91`) and `pairing_code` (e.g. `482913`).
7. Enter these in the Web UI to pair.

### 2.5 Battery optimization (important for ColorOS)

ColorOS aggressively kills background services. To keep DroidPilot alive:

- Settings → Battery → DroidPilot AI → "Don't optimize"
- Settings → App management → DroidPilot AI → Battery → "Allow background activity"
- Lock the app in recent apps (lock icon / swipe down on the card → lock)

Without these, the WSS connection drops when the screen turns off.

---

## 3. Web UI

The Web UI is static. Host it anywhere:

### Option A: Cloudflare Pages (recommended)

```bash
cd droidpilot-ai/web
npx wrangler pages deploy . --project-name droidpilot-web
# → https://droidpilot-web.<your-subdomain>.pages.dev
```

Or connect the GitHub repo in the Cloudflare dashboard → Pages → Create project → Build command: (none), Output dir: `web`.

### Option B: GitHub Pages

```bash
# In the repo:
git checkout -b gh-pages
git rm -rf android worker docs
git add web/*
git commit -m "Web UI"
git push origin gh-pages
# → https://<your-user>.github.io/droidpilot-ai/
```

### Option C: Any static host

Upload the `web/` folder to Netlify, Vercel, Surge, S3+CloudFront, etc.

### Update Worker CORS

If you host the Web UI on a custom origin, set `ALLOWED_ORIGIN` in `wrangler.jsonc` `vars` to that origin (NOT `*` in production):

```jsonc
"vars": {
  "MAX_TOOL_CALLS_PER_TURN": "20",
  "DEFAULT_MODEL_ID": "@cf/openai/gpt-oss-20b",
  "ALLOWED_ORIGIN": "https://droidpilot-web.<your-subdomain>.pages.dev"
}
```

Then redeploy the Worker.

---

## 4. End-to-end test (TikTok)

Once all three components are deployed and paired:

1. Open TikTok on the phone (install if needed).
2. In the Web UI, send: **"افتح TikTok وانتقل للفيديو التالي."**
3. Expected agent loop:
   - AI calls `open_app("com.zhiliaoapp.musically")` (TikTok's package)
   - AI calls `get_current_package()` → confirms TikTok is foreground
   - AI calls `get_screen_nodes()` → reads the tree
   - AI calls `swipe_up()` → next video
   - AI calls `get_screen_nodes()` → confirms change
   - AI responds: "تم الانتقال للفيديو التالي."
4. Watch the Debug Console — each step should appear with timestamps.

If a step fails, check:
- Device still paired? (status badge)
- Accessibility still enabled? (Android Settings)
- Foreground service running? (notification present)
- Worker logs: `npx wrangler tail`

---

## 5. CI/CD (optional)

A minimal GitHub Actions workflow for the Worker:

```yaml
# .github/workflows/deploy-worker.yml
name: Deploy Worker
on:
  push:
    branches: [main]
    paths: ['worker/**']
jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with: { node-version: '20' }
      - run: cd worker && npm ci
      - run: cd worker && npx wrangler deploy
        env:
          CLOUDFLARE_API_TOKEN: ${{ secrets.CLOUDFLARE_API_TOKEN }}
```

Store `CLOUDFLARE_API_TOKEN` in GitHub Actions secrets (repo Settings → Secrets and variables → Actions).

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---------|--------------|-----|
| Web UI shows "Disconnected" | Worker URL wrong / Worker down | `curl <worker-url>/api/health` |
| Pairing fails (401) | Wrong pairing_code or expired (10-min TTL) | Re-pair from Android app |
| Tool call hangs > 30s | Device offline / WSS dropped | Check foreground service notification |
| `ELEMENT_NOT_FOUND` | Tree stale (UI changed between read and tap) | AI auto-recovers via `get_screen_nodes` |
| `EACCES` on `npm run deploy` | Wrangler not authenticated | `npx wrangler login` |
| ColorOS kills service in background | Battery optimization | See §2.5 |
| `tsc --noEmit` errors in worker | Node modules not installed | `cd worker && npm install` |
