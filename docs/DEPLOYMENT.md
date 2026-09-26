# Deployment

## A. Cloudflare Worker

### Option 1 — helper script (recommended)

```bash
export CLOUDFLARE_ACCOUNT_ID=…          # your account id
export CLOUDFLARE_API_TOKEN=…           # token with Workers + KV permissions
bash scripts/deploy-cloudflare.sh
```

The script: installs deps → creates the 4 KV namespaces (if missing) → sets
`DEVICE_AUTH_SECRET` (random) → `wrangler deploy`.

### Option 2 — manual

```bash
cd worker
npm install
npx wrangler kv namespace create KV_DEVICES
npx wrangler kv namespace create KV_TURNS
npx wrangler kv namespace create KV_RATE
npx wrangler kv namespace create KV_LOGS
# put the printed ids into wrangler.jsonc (or leave the ids already committed)
npx wrangler secret put DEVICE_AUTH_SECRET     # openssl rand -hex 32
npx wrangler deploy
```

Output gives the URL: `https://droidpilot-ai.<your-subdomain>.workers.dev`.

### Secrets (never in code, never in git)

| Secret | Required | Purpose |
|---|---|---|
| `DEVICE_AUTH_SECRET` | yes | pepper for device-secret hashes |
| `OPENAI_BASE_URL` + `OPENAI_API_KEY` | only for `openai-compat` models | Groq / Ollama / OpenAI / CF REST |

Set via `npx wrangler secret put NAME` (or the dashboard).

### Production checklist

- `wrangler.jsonc` → `"DEBUG": "false"` (default).
- Optional: front the Web UI with **Cloudflare Access** (the MVP Web UI has no user accounts — anyone with the URL can pair devices and chat).
- `npx wrangler tail` for live logs.

## B. Android APK

### Fastest — GitHub Actions (no local tooling)

1. Push the repo (see section C).
2. GitHub → **Actions** → `android-debug-build` → wait for green.
3. Download artifact **DroidPilot-debug-apk** → sideload on the phone
   (Settings → allow install unknown apps for your browser/files app).
4. ColorOS (OPPO Reno5): Settings → Battery → App management → DroidPilot AI →
   *Allow background activity* + *Allow auto-start*, then lock the app in
   Recents. Without this, ColorOS kills the foreground service.

### Android Studio

Open `android/` as a Gradle project → let Studio sync → Run ▶.

### CLI

```bash
gradle -p android :app:assembleDebug --no-daemon
# APK: android/app/build/outputs/apk/debug/app-debug.apk
```

## C. GitHub

```bash
git init -b main && git add -A && git commit -m "DroidPilot AI — initial release"
# create the repo in the web UI or with gh; then:
git remote add origin https://github.com/<user>/droidpilot-ai.git
git push -u origin main
```

## D. Post-deploy verification

```bash
curl https://<worker>/api/health
curl https://<worker>/api/models
python3 scripts/fake_device.py https://<worker> "افتح TikTok وانتقل للفيديو التالي"
```

The fake-device script registers, pairs, chats, and answers tool calls —
it must end with `TURN RESULT == done` and a final answer.

## E. Credential rotation (IMPORTANT)

The deployment credentials used for the initial setup were **temporary and
were shared in a chat session — treat them as compromised**:

1. GitHub: Settings → Developer settings → Personal access tokens → delete.
2. Cloudflare: Dashboard → My Profile → API Tokens → delete
   `droidpilot-deploy-temp`; **roll the Global API Key** (My Profile → API
   Tokens → Global API Key → Roll).
3. Rotate the worker secret: `openssl rand -hex 32 | npx wrangler secret put DEVICE_AUTH_SECRET`
   (devices must re-pair afterwards).
4. Generate fresh, scoped tokens for future deploys (Workers Scripts Edit +
   Workers KV Storage Edit only, short expiry).

Deployment is fully reproducible with the new credentials using section A.
