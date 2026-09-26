# DroidPilot AI — Deployment & Secrets Guide

## 1. Cloudflare Worker Deployment

### Prerequisites
- Node.js 18+ and npm
- Wrangler CLI (`npm install -g wrangler`)
- Cloudflare Account with Workers AI enabled

### Step-by-Step Commands
```bash
# Navigate to worker directory
cd worker

# Install dependencies
npm install

# Authenticate or set credentials
export CLOUDFLARE_ACCOUNT_ID="YOUR_ACCOUNT_ID"
export CLOUDFLARE_API_TOKEN="YOUR_API_TOKEN"

# Set secret for device communication
npx wrangler secret put DEVICE_AUTH_SECRET

# Run local development server
npm run dev

# Deploy to Cloudflare edge network
npm run deploy
```

---

## 2. Android APK Build & Installation

### Building Debug APK
```bash
# Assemble debug APK
gradle :app:assembleDebug

# Output APK path:
# app/build/outputs/apk/debug/app-debug.apk
```

### Installation on OPPO Reno5 (Android 13 / No Root)
1. Transfer `app-debug.apk` to phone.
2. Install APK.
3. Open **Settings > Additional Settings > Accessibility > Downloaded Apps**.
4. Enable **DroidPilot AI Agent Automation**.
5. Launch DroidPilot AI app, view your 6-digit **Pairing PIN** (e.g. `482913`).
6. Enter PIN into the Web Dashboard to establish connection.

---

## 3. Temporary Credentials & Security Notice

> [!CAUTION]
> **IMPORTANT SECURITY DIRECTIVE**:
> Any temporary deployment tokens provided during setup (such as GitHub PAT or Cloudflare tokens) MUST be revoked and deleted after testing and deployment.
> Never commit credentials to source code. In DroidPilot AI, all server URLs, keys, and tokens are configurable dynamically via the Settings dialog in the app and via Cloudflare Secrets on the Worker.
