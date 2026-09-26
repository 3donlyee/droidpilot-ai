# Security

## Secret placement (hard rules)

| Secret | Where it lives | Where it must NEVER appear |
|---|---|---|
| `CLOUDFLARE_API_TOKEN` / Global key | deploy-time env only | source, wrangler.jsonc, README, logs, git |
| `CLOUDFLARE_ACCOUNT_ID` | env / dashboard | (not secret, but keep out of client code) |
| `DEVICE_AUTH_SECRET` | Cloudflare Secrets | code, git, logs |
| `OPENAI_API_KEY` (optional) | Cloudflare Secrets | code, git, frontend |
| `device_secret` | generated once, encrypted on device (EncryptedSharedPreferences) | repo, logs, screenshots |
| GitHub PAT | git credential helper / `GH_TOKEN` env | remote URLs, commits |

The audit log (`KV_LOGS`) stores only event names, ids, tool names and success
flags — never tokens, secrets, or message content.

## Device trust chain

1. **Registration**: device POSTs /api/device/register → receives
   `device_id`, one-time `pairing_code` (6 digits, 15 min TTL), and
   `device_secret` (shown once).
2. **Pairing**: a human enters the PIN in the Web UI; rate-limited to
   6 attempts / 15 min / IP.
3. **Authentication**: every device call carries `x-device-id` +
   `x-device-secret`; the Worker compares `SHA-256(pepper || secret)`.
4. **Revocation**: device records can be flipped to `status: "revoked"` in KV.

## Command safety

- Every `run_shell` command passes `CommandValidator`:
  BLOCKED (destructive) / REQUIRES_CONFIRMATION (unknown, needs explicit user
  approval passed as `approved=true`) / SAFE (allowlist).
- Redirects, pipes and command chaining (`>`, `|`, `;`, `&&`, backticks) are
  blocked outright.
- Without ADB/root, app-level shell is inherently restricted; the bridge
  reports `SHELL_UNAVAILABLE` honestly instead of pretending.

## Web UI exposure (MVP)

The Web UI has **no user authentication** by design (single-admin MVP).
Anyone with the URL can: list devices, attempt pairing (PIN-gated + rate
limited), and chat (rate limited). Recommended hardening, in order:

1. Put Cloudflare Access (Zero Trust) in front of the worker — zero code.
2. Or add a `WEB_PASSWORD` secret + cookie check on Web endpoints.

## Rate limiting & abuse

- Fixed-window KV counters; per-IP for web endpoints, per-device for device
  endpoints. Bursts may under-count (KV write limits) — acceptable for MVP,
  Durable Objects for strictness.

## Rotation guide

1. Cloudflare dashboard → API Tokens → delete temporary tokens; roll the
   Global API Key if it was ever shared.
2. `wrangler secret put DEVICE_AUTH_SECRET` with a fresh random value →
   devices re-pair.
3. GitHub → delete/rotate PATs; prefer fine-grained tokens with short expiry.
4. Anything that touched a chat session in plaintext must be considered
   compromised — rotate first, redeploy second.
