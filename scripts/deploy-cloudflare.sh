#!/usr/bin/env bash
#
# DroidPilot AI — Cloudflare deployment helper.
#
# REQUIRED environment variables (never hardcode, never pass as args):
#   CLOUDFLARE_ACCOUNT_ID  – your Cloudflare account id
#   CLOUDFLARE_API_TOKEN   – API token with Workers + KV permissions
#
# Optional:
#   SKIP_SECRET=1   – do not prompt to set DEVICE_AUTH_SECRET
#
set -euo pipefail

: "${CLOUDFLARE_ACCOUNT_ID:?set CLOUDFLARE_ACCOUNT_ID in your environment}"
: "${CLOUDFLARE_API_TOKEN:?set CLOUDFLARE_API_TOKEN in your environment}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/worker"

echo "==> Installing dependencies"
npm install

API="https://api.cloudflare.com/client/v4/accounts/$CLOUDFLARE_ACCOUNT_ID"

create_ns() {
  curl -s -H "Authorization: Bearer $CLOUDFLARE_API_TOKEN" \
       -H "content-type: application/json" \
       -X POST "$API/storage/kv/namespaces" \
       -d "{\"title\":\"droidpilot-$1\"}" |
    node -e "let d='';process.stdin.on('data',c=>d+=c).on('end',()=>{const j=JSON.parse(d);if(!j.success){console.error('CREATE_NS_FAILED',JSON.stringify(j.errors));process.exit(1)}console.log(j.result.id)})"
}

echo "==> Creating KV namespaces (if placeholders remain)"
for B in KV_DEVICES KV_TURNS KV_RATE KV_LOGS; do
  if grep -q "PLACEHOLDER_$B" wrangler.jsonc; then
    ID=$(create_ns "$B")
    sed -i.bak "s/PLACEHOLDER_$B/$ID/" wrangler.jsonc && rm -f wrangler.jsonc.bak
    echo "    $B -> $ID"
  fi
done

if [ "${SKIP_SECRET:-0}" != "1" ]; then
  if ! npx wrangler secret list 2>/dev/null | grep -q DEVICE_AUTH_SECRET; then
    echo "==> Setting DEVICE_AUTH_SECRET (random, generated locally, never printed)"
    openssl rand -hex 32 | npx wrangler secret put DEVICE_AUTH_SECRET
  else
    echo "==> DEVICE_AUTH_SECRET already set"
  fi
fi

echo "==> Deploying Worker"
npx wrangler deploy

echo
echo "Done. Set DEBUG=false in wrangler.jsonc for production (it already defaults to false)."
