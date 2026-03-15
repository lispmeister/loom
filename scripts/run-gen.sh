#!/usr/bin/env bash
# run-gen.sh — Run a single Loom self-modification generation end-to-end.
# Usage: ./scripts/run-gen.sh <path-to-program.md>
#
# Requires: .env with ANTHROPIC_API_KEY, shadow-cljs builds up to date.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_DIR"

# -- Args --
PROGRAM_MD="${1:?Usage: $0 <path-to-program.md>}"
if [[ ! -f "$PROGRAM_MD" ]]; then
  echo "Error: $PROGRAM_MD not found" >&2
  exit 1
fi

# -- Load env --
if [[ -f .env ]]; then
  set -a; source .env; set +a
fi
if [[ -z "${ANTHROPIC_API_KEY:-}" ]]; then
  echo "Error: ANTHROPIC_API_KEY not set (check .env)" >&2
  exit 1
fi

# -- Cleanup from previous runs --
echo "==> Cleaning up old processes and containers..."
pkill -f "node out/supervisor.js" 2>/dev/null || true
pkill -f "node out/agent.js" 2>/dev/null || true
# Stop any lingering lab containers
for c in $(container list 2>/dev/null | grep "lab-gen-" | awk '{print $1}'); do
  container stop "$c" 2>/dev/null || true
  container rm "$c" 2>/dev/null || true
done
sleep 1

# -- Build --
echo "==> Building..."
npx shadow-cljs compile supervisor agent lab-worker 2>&1 | tail -5
echo "Build complete."

# Validate build artifacts
for artifact in out/supervisor.js out/agent.js out/lab-worker.js; do
  if [[ ! -f "$artifact" ]]; then
    echo "Error: $artifact not found after build" >&2
    exit 1
  fi
done

# -- Start Supervisor --
echo "==> Starting Supervisor on :8400..."
LOOM_REPO_PATH="$PROJECT_DIR" node out/supervisor.js &
SUPERVISOR_PID=$!
sleep 2

if ! kill -0 "$SUPERVISOR_PID" 2>/dev/null; then
  echo "Error: Supervisor failed to start" >&2
  exit 1
fi

# -- Start Prime agent --
echo "==> Starting Prime agent on :8401..."
LOOM_SUPERVISOR_URL="http://localhost:8400" node out/agent.js &
AGENT_PID=$!
sleep 2

if ! kill -0 "$AGENT_PID" 2>/dev/null; then
  echo "Error: Agent failed to start" >&2
  kill "$SUPERVISOR_PID" 2>/dev/null || true
  exit 1
fi

# -- Send task --
PROGRAM_CONTENT=$(cat "$PROGRAM_MD")
echo "==> Sending program.md to Prime..."
echo "--- program.md ---"
echo "$PROGRAM_CONTENT"
echo "--- end ---"
echo ""

RESPONSE=$(curl -s -X POST http://localhost:8401/chat \
  -H "Content-Type: application/json" \
  -d "$(jq -n --arg msg "Please execute this self-modification task. Here is the program.md:\n\n$PROGRAM_CONTENT" '{message: $msg}')")

echo "==> Prime response:"
echo "$RESPONSE" | jq -r '.response // .error // "No response"' 2>/dev/null || echo "$RESPONSE"

# -- Wait for completion --
echo ""
echo "==> Monitoring (Ctrl-C to stop, processes will be cleaned up)..."
trap 'echo "==> Shutting down..."; kill $SUPERVISOR_PID $AGENT_PID 2>/dev/null; exit 0' INT TERM

# Poll supervisor stats until generation completes
while true; do
  STATS=$(curl -s http://localhost:8400/stats 2>/dev/null || echo '{}')
  GENS=$(curl -s http://localhost:8400/versions 2>/dev/null || echo '[]')
  LATEST_OUTCOME=$(echo "$GENS" | jq -r '.[-1].outcome // "none"' 2>/dev/null || echo "unknown")

  if [[ "$LATEST_OUTCOME" == "promoted" ]]; then
    echo "==> Generation promoted successfully!"
    break
  elif [[ "$LATEST_OUTCOME" == "failed" || "$LATEST_OUTCOME" == "timeout" ]]; then
    echo "==> Generation ended with outcome: $LATEST_OUTCOME"
    break
  fi
  sleep 10
done

# -- Final status --
echo ""
echo "==> Final generations:"
curl -s http://localhost:8400/versions | jq '.' 2>/dev/null || true

# -- Cleanup --
echo "==> Shutting down processes..."
kill "$SUPERVISOR_PID" "$AGENT_PID" 2>/dev/null || true
wait "$SUPERVISOR_PID" "$AGENT_PID" 2>/dev/null || true
echo "==> Done."
