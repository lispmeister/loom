#!/usr/bin/env bash
# watch-logs.sh — Tail SSE log streams from Supervisor and Prime simultaneously.
# Usage: ./scripts/watch-logs.sh [supervisor-port] [prime-port]
#
# Formats each line as: [HH:MM:SS] [SOURCE] event-type: data-summary
# Reconnects automatically on connection drop (2-second backoff).
# Pure bash — requires only curl and standard Unix tools.

SUP_PORT="${1:-8400}"
PRIME_PORT="${2:-8401}"

SUP_URL="http://localhost:${SUP_PORT}/logs"
PRIME_URL="http://localhost:${PRIME_PORT}/logs"

# ANSI colours: 36=cyan (supervisor), 35=magenta (prime)
COLOR_SUP="36"
COLOR_PRIME="35"
COLOR_RESET="\033[0m"

# stream_sse SOURCE COLOR URL
# Runs in a loop: connects to the SSE endpoint, formats lines, reconnects on drop.
stream_sse() {
  local source="$1"
  local color="$2"
  local url="$3"
  local event_type="message"

  while true; do
    # Read the raw SSE stream line by line.
    # curl -N disables output buffering; --no-progress-meter suppresses stats.
    while IFS= read -r line; do
      if [[ "$line" == event:* ]]; then
        # Capture the event type for the next data line.
        event_type="${line#event:}"
        event_type="${event_type# }"   # trim leading space
      elif [[ "$line" == data:* ]]; then
        local data="${line#data:}"
        data="${data# }"               # trim leading space
        local ts
        ts=$(date +"%H:%M:%S")
        # Truncate data to 120 chars so wide JSON doesn't wrap.
        local summary="${data:0:120}"
        [[ ${#data} -gt 120 ]] && summary="${summary}…"
        printf "\033[${color}m[%s] [%s]\033[0m %s: %s\n" \
          "$ts" "$source" "$event_type" "$summary"
        # Reset event_type after consuming the data line.
        event_type="message"
      fi
    done < <(curl -sN --no-progress-meter "$url" 2>/dev/null)

    # curl exited (connection dropped or server unavailable).
    local ts
    ts=$(date +"%H:%M:%S")
    printf "\033[${color}m[%s] [%s]\033[0m disconnected — retrying in 2s...\n" \
      "$ts" "$source"
    sleep 2
  done
}

# Kill background jobs cleanly on Ctrl-C or SIGTERM.
cleanup() {
  echo ""
  echo "Shutting down log watchers..."
  # Kill entire process group of each background job.
  kill -- -"$SUP_BGPID" 2>/dev/null || kill "$SUP_BGPID" 2>/dev/null || true
  kill -- -"$PRIME_BGPID" 2>/dev/null || kill "$PRIME_BGPID" 2>/dev/null || true
  exit 0
}

echo "Tailing Supervisor (${SUP_URL}) and Prime (${PRIME_URL})..."
echo "Press Ctrl-C to stop."
echo ""

# Run both streams in background subshells.
( stream_sse "SUPERVISOR" "$COLOR_SUP"   "$SUP_URL"   ) &
SUP_BGPID=$!

( stream_sse "PRIME"      "$COLOR_PRIME" "$PRIME_URL" ) &
PRIME_BGPID=$!

trap cleanup INT TERM

# Wait for both background jobs; if either dies, the trap fires on next signal.
wait
