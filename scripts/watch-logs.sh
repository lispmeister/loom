#!/usr/bin/env bash
# watch-logs.sh — Tail SSE log streams from Supervisor and Prime.
# Usage: ./scripts/watch-logs.sh [supervisor-port] [prime-port]
set -euo pipefail

SUP_PORT="${1:-8400}"
PRIME_PORT="${2:-8401}"

SUP_URL="http://localhost:${SUP_PORT}/logs"
PRIME_URL="http://localhost:${PRIME_PORT}/logs"

format_sse() {
  local source="$1"
  local color="$2"
  while IFS= read -r line; do
    # SSE lines: "event: <name>" or "data: <json>"
    if [[ "$line" == data:* ]]; then
      local data="${line#data: }"
      local ts
      ts=$(date +"%H:%M:%S")
      printf "\033[${color}m[%s %s]\033[0m %s\n" "$ts" "$source" "$data"
    fi
  done
}

echo "Tailing Supervisor ($SUP_URL) and Prime ($PRIME_URL)..."
echo "Press Ctrl-C to stop."
echo ""

# Start both SSE streams in background
curl -sN "$SUP_URL" 2>/dev/null | format_sse "supervisor" "36" &
SUP_PID=$!

curl -sN "$PRIME_URL" 2>/dev/null | format_sse "prime" "35" &
PRIME_PID=$!

trap 'kill $SUP_PID $PRIME_PID 2>/dev/null; exit 0' INT TERM

wait
