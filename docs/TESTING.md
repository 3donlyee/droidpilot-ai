# Testing

## Phase checklist

### Phase 1 — Android agent (on-device)

- [ ] App installs and opens; Worker URL saved.
- [ ] `Connect` → `DROID-XXXX` + 6-digit PIN shown.
- [ ] Accessibility service enabled (button → system settings).
- [ ] `Start Connection` → notification "DroidPilot AI is connected".
- [ ] `get_screen_nodes` returns elements with id/text/clickable/bounds.
- [ ] `swipe_up` / `swipe_down` perform gestures; `press_back` works.
- [ ] `tap_element` clicks the right node (click action, ancestor, or gesture).
- [ ] `run_shell getprop ro.build.version.release` runs; `rm` is BLOCKED;
      unknown commands ask for confirmation.
- [ ] Screenshot only when explicitly requested; discarded after analysis.

### Phase 2/3/4 — Worker + integration (server-side, no phone needed)

```bash
curl https://<worker>/api/health
curl https://<worker>/api/models
python3 scripts/fake_device.py https://<worker> "افتح TikTok وانتقل للفيديو التالي"
```

Expected: `TURN RESULT == done`, steps show
`open_app → get_current_package → swipe_up → final answer`.

Additional scenarios:

```bash
# shell policy (device answers BLOCKED — check the error_code in the step)
python3 scripts/fake_device.py https://<worker> "Run: rm -rf /sdcard/DCIM"

# device offline → expect DEVICE_TIMEOUT after ~45 s
# (start a chat, never answer the poll)
```

### Phase 5/6 — Web UI

- [ ] Model selector lists registry models with capability ticks; new registry
      entries appear without code changes.
- [ ] Pairing: pending device appears → wrong PIN rejected → correct PIN pairs
      → header chip turns `● <device> — connected`.
- [ ] Chat: user bubble → tool chips update ⏳ → ✓/✗ with summaries → final AI
      bubble; LIVE LOG mirrors every step with timestamps.
- [ ] Arabic input renders RTL (`dir="auto"`).

### Phase 7 — TikTok end-to-end (real device)

1. Say: **افتح TikTok وانتقل للفيديو التالي**
2. Watch the log: `open_app → success`, `get_current_package → com.zhiliaoapp.musically`,
   `get_screen_nodes → nodes=N`, `swipe_up → success`, `get_screen_nodes → nodes=N`.
3. No screenshot should be requested anywhere in the flow.

## Rate limits (defaults)

| Scope | Limit |
|---|---|
| `POST /api/chat` | 10 / min / IP |
| `POST /api/device/register` | 10 / hour / IP |
| `POST /api/pair/confirm` | 6 / 15 min / IP |
| device endpoints | authenticated device only |

## Troubleshooting

| Symptom | Likely cause / fix |
|---|---|
| `AI_PROVIDER_FAILED` in the turn | model temporarily unavailable; retry, or pick another model in the selector |
| `DEVICE_TIMEOUT` | foreground service killed by ColorOS (see docs/ANDROID.md battery steps) or wrong Worker URL |
| `SERVICE_NOT_CONNECTED` | accessibility service not enabled |
| 403 with `error code: 1010` from curl/python | banned User-Agent at the edge — send a custom `User-Agent` (the app already does) |
| device shows offline | long-poll is running? check notification presence |
