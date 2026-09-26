#!/usr/bin/env bash
# ============================================================
# DroidPilot AI — Cloudflare Worker deployment helper.
#
# This script does NOT contain any credentials. It expects the
# caller to provide:
#   CLOUDFLARE_API_TOKEN  — a valid Cloudflare API token
#                           (create at https://dash.cloudflare.com/profile/api-tokens
#                            → "Edit Cloudflare Workers" template)
#
# Usage:
#   export CLOUDFLARE_API_TOKEN="<your-token>"
#   ./deploy-worker.sh
# ============================================================
set -euo pipefail

cd "$(dirname "$0")/.."

WORKER_DIR="./worker"
if [ ! -d "$WORKER_DIR" ]; then
  echo "ERROR: $WORKER_DIR not found. Run from repo root."
  exit 1
fi

cd "$WORKER_DIR"

# 1) Check token presence
if [ -z "${CLOUDFLARE_API_TOKEN:-}" ]; then
  cat <<'EOF'
ERROR: CLOUDFLARE_API_TOKEN is not set.

Create one at:  https://dash.cloudflare.com/profile/api-tokens
Use the template "Edit Cloudflare Workers" (includes Workers AI + KV permissions).

Then:
  export CLOUDFLARE_API_TOKEN="<paste-token-here>"
  ./deploy-worker.sh
EOF
  exit 1
fi

# Never print the token
echo "✓ CLOUDFLARE_API_TOKEN is set (length: ${#CLOUDFLARE_API_TOKEN})."

# 2) Verify token
echo ""
echo "=== Verifying token ==="
if ! npx wrangler whoami 2>&1 | tail -5; then
  echo "ERROR: Token verification failed. Aborting."
  exit 1
fi

# 3) Install deps
echo ""
echo "=== Installing dependencies ==="
npm install --silent

# 4) TypeScript check
echo ""
echo "=== TypeScript check ==="
npx tsc --noEmit && echo "✓ TypeScript clean"

# 5) Create KV namespace if it doesn't exist
echo ""
echo "=== Ensuring KV namespace DEVICE_SESSIONS exists ==="
KV_OUTPUT=$(npx wrangler kv namespace list 2>&1)
if echo "$KV_OUTPUT" | grep -q '"title": "DEVICE_SESSIONS"'; then
  KV_ID=$(echo "$KV_OUTPUT" | python3 -c "
import json, sys
data = json.load(sys.stdin)
for ns in data:
    if ns.get('title') == 'DEVICE_SESSIONS':
        print(ns['id'])
        break
")
  echo "✓ KV namespace exists (id: ${KV_ID:0:8}...)"
else
  echo "Creating KV namespace..."
  KV_CREATE_OUT=$(npx wrangler kv namespace create DEVICE_SESSIONS 2>&1)
  KV_ID=$(echo "$KV_CREATE_OUT" | python3 -c "
import json, sys, re
m = re.search(r'\\{\\s*\"id\":\\s*\"([^\"]+)\"', sys.stdin.read())
print(m.group(1) if m else '')
")
  if [ -z "$KV_ID" ]; then
    echo "ERROR: Could not create KV namespace. Output was:"
    echo "$KV_CREATE_OUT"
    exit 1
  fi
  echo "✓ KV namespace created (id: ${KV_ID:0:8}...)"
fi

# 6) Patch wrangler.jsonc with the real KV id
echo ""
echo "=== Updating wrangler.jsonc with KV id ==="
python3 -c "
with open('wrangler.jsonc') as f:
    original = f.read()
patched = original.replace('REPLACE_WITH_KV_ID', '$KV_ID')
with open('wrangler.jsonc', 'w') as f:
    f.write(patched)
print('✓ wrangler.jsonc updated')
"

# 7) Set DEVICE_AUTH_SECRET (required)
echo ""
echo "=== Setting DEVICE_AUTH_SECRET (required) ==="
if npx wrangler secret list 2>&1 | grep -q DEVICE_AUTH_SECRET; then
  echo "✓ DEVICE_AUTH_SECRET already set (skipping). To rotate: npx wrangler secret put DEVICE_AUTH_SECRET"
else
  SECRET_VALUE=$(openssl rand -hex 32)
  echo "$SECRET_VALUE" | npx wrangler secret put DEVICE_AUTH_SECRET
  echo "✓ DEVICE_AUTH_SECRET set (32-byte random hex)."
  echo "  (The value itself is stored in Cloudflare Secrets — not echo'd back for security.)"
fi

# 8) Deploy
echo ""
echo "=== Deploying Worker ==="
npx wrangler deploy 2>&1 | tail -20

echo ""
echo "✅ Done. Verify:"
echo "   curl https://droidpilot-ai.<your-subdomain>.workers.dev/api/health"
echo ""
echo "Next: update web/index.html default URL + android strings.xml hint"
echo "      to your deployed worker URL."
