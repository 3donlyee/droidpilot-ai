# Android build & setup

## Build options

### 1. GitHub Actions (recommended — zero local setup)

Push to GitHub → **Actions** tab → workflow `android-debug-build` builds
`assembleDebug` with JDK 17 + Gradle 8.9 → download artifact
`DroidPilot-debug-apk`.

### 2. Android Studio

`File → Open` → select the `android/` folder → sync → `Run ▶` on a device or
emulator.

### 3. CLI

```bash
gradle -p android :app:assembleDebug --no-daemon
# → android/app/build/outputs/apk/debug/app-debug.apk
```

Requirements: JDK 17, Android SDK 34 (`local.properties` with
`sdk.dir` if not using Android Studio).

## First-run setup (OPPO Reno5 / ColorOS)

1. **Install** the APK (allow unknown sources for the installer you use).
2. **Worker URL**: open DroidPilot AI → paste
   `https://droidpilot-ai.<subdomain>.workers.dev` → **Save URL**.
   AI keys are never entered here — they live in Cloudflare Secrets only.
3. **Connect** → the app registers and shows `DROID-XXXX` + a 6-digit PIN
   (valid 15 minutes).
4. **Accessibility**: tap *Accessibility* → find *DroidPilot AI* → enable.
   The service reads the screen and performs gestures — required for tools.
5. **Start Connection** → a persistent notification appears:
   *"DroidPilot AI is connected"*.
6. **Web pairing**: open the Worker URL → Device Pairing → select the device
   → enter the PIN → Pair.
7. **ColorOS battery exemptions** (critical — otherwise the service dies):
   - Settings → Battery → More settings → *Optimize battery use* → DroidPilot AI → *Don't optimize*
   - Settings → Battery → App management → DroidPilot AI → allow *Background activity* + *Auto-launch*
   - Recents → lock the app (pull down on its card → lock)

## Permissions used

| Permission | Why |
|---|---|
| INTERNET | worker communication |
| POST_NOTIFICATIONS | the visible "connected" notification (Android 13+) |
| FOREGROUND_SERVICE(_DATA_SYNC) | keep the poll loop alive |
| Accessibility Service | screen tree, gestures, taps, back |
| `<queries>` launcher intent | `open_app` label resolution |

Nothing else — no storage scanning, no contacts, no location.

## Troubleshooting

| Symptom | Fix |
|---|---|
| `INSECURE_URL_REJECTED` | you entered http:// — production URLs must be https:// (http is allowed only for 10.0.2.2/localhost emulator dev) |
| commands stop mid-flow | ColorOS killed the service → battery exemptions above |
| `NO_WINDOW` | screen is off/locked — unlock and keep TikTok in the foreground |
| pairing expired | press Connect again for a fresh PIN |
