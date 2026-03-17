#!/usr/bin/env bash
# Check Anthropic API credit balance before an expensive run.
# Usage: ./scripts/check-credits.sh [model]
#
# Sends a minimal API call (1 token max) and checks the response.
# If credits are insufficient, exits with code 1.
# Shows cost estimates for autonomous loop runs.

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
GENS="${2:-3}"

# Pricing per million tokens (as of 2025-05)
# Format: input/output per 1M tokens
case "$MODEL" in
  *opus-4-6*|*opus-4*)
    INPUT_PRICE=15.00
    OUTPUT_PRICE=75.00
    MODEL_SHORT="Opus"
    ;;
  *sonnet-4*|*sonnet*)
    INPUT_PRICE=3.00
    OUTPUT_PRICE=15.00
    MODEL_SHORT="Sonnet"
    ;;
  *haiku*)
    INPUT_PRICE=0.80
    OUTPUT_PRICE=4.00
    MODEL_SHORT="Haiku"
    ;;
  *)
    INPUT_PRICE=3.00
    OUTPUT_PRICE=15.00
    MODEL_SHORT="Unknown"
    ;;
esac

echo "Checking API credits (model: $MODEL)..."
echo ""

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

# Cost estimation based on observed autonomous loop data:
# - Reflect (Sonnet): ~10k tokens per cycle
# - Lab run: ~150-200k tokens per cycle (mostly input)
# - LLM review (Sonnet): ~5k tokens per cycle
# Assume 70% input, 30% output for Lab; reflect+review always Sonnet
TOKENS_PER_GEN=200000
LAB_INPUT=$(echo "$TOKENS_PER_GEN * 0.70" | bc)
LAB_OUTPUT=$(echo "$TOKENS_PER_GEN * 0.30" | bc)
cost_estimate() {
  local lab_cost=$(echo "scale=2; ($LAB_INPUT * $INPUT_PRICE + $LAB_OUTPUT * $OUTPUT_PRICE) / 1000000" | bc)
  local reflect_review_cost="0.10"  # ~15k tokens Sonnet per cycle (reflect + review)
  local per_gen=$(echo "scale=2; $lab_cost + $reflect_review_cost" | bc)
  local total=$(echo "scale=2; $per_gen * $GENS" | bc)
  echo "--- Cost Estimate ($MODEL_SHORT Lab, $GENS generations) ---"
  echo "  Lab tokens/gen:    ~${TOKENS_PER_GEN} (70% input, 30% output)"
  echo "  Lab cost/gen:      ~\$${lab_cost}"
  echo "  Reflect+review:    ~\$${reflect_review_cost}/gen (Sonnet)"
  echo "  Per generation:    ~\$${per_gen}"
  echo "  Total ($GENS gens):     ~\$${total}"
  echo ""
  echo "  Pricing: \$${INPUT_PRICE}/M input, \$${OUTPUT_PRICE}/M output"
}

if [ "$HTTP_CODE" = "200" ]; then
  INPUT=$(echo "$BODY" | grep -o '"input_tokens":[0-9]*' | cut -d: -f2)
  OUTPUT=$(echo "$BODY" | grep -o '"output_tokens":[0-9]*' | cut -d: -f2)
  echo "OK — API responding (${INPUT:-?} in + ${OUTPUT:-?} out tokens for probe)"
  echo "Credits sufficient for model: $MODEL"
  echo ""
  cost_estimate
  exit 0
elif echo "$BODY" | grep -q "credit balance is too low"; then
  echo "FAIL — Credit balance too low for $MODEL_SHORT."
  # Try to extract the error message for more detail
  MSG=$(echo "$BODY" | grep -o '"message":"[^"]*"' | cut -d'"' -f4)
  [ -n "$MSG" ] && echo "  API says: $MSG"
  echo ""
  echo "Top up at: https://console.anthropic.com/settings/billing"
  echo ""
  cost_estimate
  exit 1
elif echo "$BODY" | grep -q "Could not resolve model"; then
  echo "FAIL — Model not found: $MODEL"
  echo "  Check model ID at: https://docs.anthropic.com/en/docs/about-claude/models"
  exit 1
elif echo "$BODY" | grep -q "invalid_api_key\|authentication_error"; then
  echo "FAIL — Invalid API key."
  exit 1
elif echo "$BODY" | grep -q "rate_limit\|overloaded"; then
  echo "WARN — Rate limited or overloaded, but credits likely OK."
  echo ""
  cost_estimate
  exit 0
else
  echo "UNEXPECTED — HTTP $HTTP_CODE"
  MSG=$(echo "$BODY" | grep -o '"message":"[^"]*"' | cut -d'"' -f4)
  [ -n "$MSG" ] && echo "  $MSG" || echo "$BODY" | head -3
  exit 1
fi
