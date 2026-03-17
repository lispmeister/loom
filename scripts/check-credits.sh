#!/usr/bin/env bash
# Check Anthropic API credit balance before an expensive run.
# Usage: ./scripts/check-credits.sh
#
# Sends a minimal API call (1 token max) and checks the response.
# If credits are insufficient, exits with code 1.

set -euo pipefail

# Load .env if not already set
if [ -z "${ANTHROPIC_API_KEY:-}" ]; then
  if [ -f .env ]; then
    set -a && source .env && set +a
  else
    echo "ERROR: No ANTHROPIC_API_KEY set and no .env file found"
    exit 1
  fi
fi

MODEL="${1:-claude-haiku-4-5-20251001}"

echo "Checking API credits (model: $MODEL)..."

RESPONSE=$(curl -s -w "\n%{http_code}" \
  https://api.anthropic.com/v1/messages \
  -H "content-type: application/json" \
  -H "x-api-key: $ANTHROPIC_API_KEY" \
  -H "anthropic-version: 2023-06-01" \
  -d "{
    \"model\": \"$MODEL\",
    \"max_tokens\": 1,
    \"messages\": [{\"role\": \"user\", \"content\": \"hi\"}]
  }")

HTTP_CODE=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | sed '$d')

if [ "$HTTP_CODE" = "200" ]; then
  # Extract token usage
  INPUT=$(echo "$BODY" | grep -o '"input_tokens":[0-9]*' | cut -d: -f2)
  OUTPUT=$(echo "$BODY" | grep -o '"output_tokens":[0-9]*' | cut -d: -f2)
  echo "OK — API responding. Used ${INPUT:-?} input + ${OUTPUT:-?} output tokens for check."
  echo "Credits are sufficient for model: $MODEL"
  exit 0
elif echo "$BODY" | grep -q "credit balance is too low"; then
  echo "FAIL — Credit balance too low."
  echo "Top up at: https://console.anthropic.com/settings/billing"
  exit 1
elif echo "$BODY" | grep -q "invalid_api_key\|authentication_error"; then
  echo "FAIL — Invalid API key."
  exit 1
elif echo "$BODY" | grep -q "rate_limit"; then
  echo "WARN — Rate limited, but credits likely OK."
  exit 0
else
  echo "UNKNOWN — HTTP $HTTP_CODE"
  echo "$BODY" | head -5
  exit 1
fi
