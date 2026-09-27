#!/usr/bin/env bash
# aMiNo — IN-PLACE upgrade deploy.
#
# Continuity contract:
#   - deploys to the SAME worker name (droidpilot-ai) → same URL the user knows
#   - uses the LIVE KV namespace ids already pinned in wrangler.jsonc
#   - does NOT create KV, does NOT touch DEVICE_AUTH_SECRET → existing
#     registrations (his real phone) stay paired
#
# Credentials (env only, never stored): CF_E (email) + CF_K (global key)
#   or CF_T (already-scoped API token — skips minting)
set -euo pipefail
cd "$(dirname "$0")/../worker"

BASE="https://droidpilot-ai.droidpilot.workers.dev"

curlj() {
  local out="" i
  for i in 1 2 3; do
    out=$(curl -s -m 30 "$@" 2>/dev/null || true)
    if [ -n "$out" ]; then printf '%s' "$out"; return 0; fi
    sleep 2
  done
  echo "CURL_EMPTY_AFTER_RETRIES ($*)" >&2; return 1
}

TOK="${CF_T:-}"
if [ -z "$TOK" ]; then
  : "${CF_E:?CF_E required}"; : "${CF_K:?CF_K required}"

  echo "==> 1. verify credential"
  OK=$(curlj -H "X-Auth-Email: $CF_E" -H "X-Auth-Key: $CF_K" \
    "https://api.cloudflare.com/client/v4/user" \
    | python3 -c "import json,sys; print(json.load(sys.stdin).get('success', False))")
  [ "$OK" = "True" ] || { echo "credential check FAILED"; exit 1; }

  echo "==> 2. discover account"
  ACC=$(curlj -H "X-Auth-Email: $CF_E" -H "X-Auth-Key: $CF_K" \
    "https://api.cloudflare.com/client/v4/accounts" \
    | python3 -c "import json,sys; print(json.load(sys.stdin)['result'][0]['id'])")
  echo "    account ${ACC:0:8}..."

  echo "==> 3. find 'Workers Scripts Write' permission group"
  PG=$(curlj -H "X-Auth-Email: $CF_E" -H "X-Auth-Key: $CF_K" \
    "https://api.cloudflare.com/client/v4/accounts/$ACC/tokens/permission_groups" \
    | python3 -c "
import json,sys
for g in json.load(sys.stdin)['result']:
    if g['name'] == 'Workers Scripts Write':
        print(g['id']); break")
  [ -n "$PG" ] || { echo "permission group not found"; exit 1; }

  echo "==> 4. mint 1-day scoped deploy token (memory only)"
  TOK=$(curlj -X POST \
    -H "X-Auth-Email: $CF_E" -H "X-Auth-Key: $CF_K" -H "content-type: application/json" \
    "https://api.cloudflare.com/client/v4/user/tokens" \
    --data "{\"name\":\"amino-deploy-$RANDOM\",\"expires_in\":86400,\"policies\":[{\"effect\":\"allow\",\"resources\":{\"com.cloudflare.api.account.$ACC\":\"*\"},\"permission_groups\":[{\"id\":\"$PG\"}]}]}" \
    | python3 -c "import json,sys; d=json.load(sys.stdin); print(d['result']['value'] if d.get('success') else '')")
  [ -n "$TOK" ] || { echo "token mint FAILED"; exit 1; }
fi

echo "==> 5. wrangler deploy (in-place upgrade)"
CLOUDFLARE_API_TOKEN="$TOK" npx wrangler deploy 2>&1 | grep -E "Uploaded|Deployed|Version|Current Version|error|Error" || true

# Optional: activate OpenRouter free models by piping the key into the secret
# (OR_KEY env only — never echoed, never stored). When set, aMiNo's default
# model becomes an OpenRouter :free model and Cloudflare Neurons are preserved.
if [ -n "${OR_KEY:-}" ]; then
  echo "==> 5b. set OPENAI_API_KEY secret (OpenRouter key from env, value hidden)"
  printf '%s' "$OR_KEY" | CLOUDFLARE_API_TOKEN="$TOK" npx wrangler secret put OPENAI_API_KEY 2>&1 | grep -vE "telemetry" | tail -2
fi

echo "==> 6. wait for version propagation (15s)"; sleep 15

echo "==> 7. verify live endpoints"
curlj "$BASE/api/health" | python3 -c "import json,sys; d=json.load(sys.stdin); print('    health:', d.get('service'), d.get('ok'))"
curlj "$BASE/api/models" | python3 -c "
import json,sys
d=json.load(sys.stdin)
for m in d.get('models',[]):
    if m.get('status')=='available':
        print('    model:', m.get('displayName'), '(default)' if m.get('default') else '')"
curlj "$BASE/api/devices" | python3 -c "
import json,sys
d=json.load(sys.stdin)
devs=d.get('devices',[])
print('    devices:', len(devs))
for x in devs[:6]:
    print('      -', x.get('device_id'), x.get('status'), x.get('model',''))"
curlj "$BASE/api/agents" | python3 -c "
import json,sys
d=json.load(sys.stdin)
print('    agents:', ', '.join(a.get('emoji','')+a.get('id','') for a in d.get('agents',[])))"

echo "==> 8. E2E: fake device full loop"
if [ "${SKIP_E2E:-0}" = "1" ]; then echo "    (skipped — SKIP_E2E=1)"; else
cd ..
python3 scripts/fake_device.py "$BASE" "افتح TikTok وانتقل للفيديو التالي" 2>&1 | tail -18

echo "==> 9. E2E: full aMiNo suite (13 checks)"
python3 scripts/test-aiminos.py "$BASE" 2>&1 | tail -22
fi
