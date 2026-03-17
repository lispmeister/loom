#!/usr/bin/env bash
# run-gen.sh — Run a single Loom generation end-to-end.
# Usage: ./scripts/run-gen.sh <path-to-program.md>
#
# Starts the supervisor, spawns a Lab via POST /spawn with the given program.md,
# polls until the generation completes, reports the result, then shuts down.
#
# Requires: .env with ANTHROPIC_API_KEY, out/supervisor.js built.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

SUPERVISOR_URL="http://localhost:8400"
SUPERVISOR_PID=""
POLL_INTERVAL=5
TIMEOUT_SECS=600   # 10-minute wall-clock cap for the whole script

# ---- Cleanup trap ----
cleanup() {
  if [[ -n "$SUPERVISOR_PID" ]] && kill -0 "$SUPERVISOR_PID" 2>/dev/null; then
    echo ""
    echo "==> Shutting down supervisor (pid $SUPERVISOR_PID)..."
    kill "$SUPERVISOR_PID" 2>/dev/null || true
    wait "$SUPERVISOR_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT INT TERM

# ---- Validate args ----
PROGRAM_MD="${1:?Usage: $0 <path-to-program.md>}"

# Resolve to absolute path before cd-ing to PROJECT_DIR
if [[ "$PROGRAM_MD" != /* ]]; then
  PROGRAM_MD="$(pwd)/$PROGRAM_MD"
fi

if [[ ! -f "$PROGRAM_MD" ]]; then
  echo "Error: program.md not found: $PROGRAM_MD" >&2
  exit 1
fi

cd "$PROJECT_DIR"

# ---- Validate .env ----
if [[ ! -f .env ]]; then
  echo "Error: .env not found in $PROJECT_DIR" >&2
  exit 1
fi

set -a; source .env; set +a

if [[ -z "${ANTHROPIC_API_KEY:-}" ]]; then
  echo "Error: ANTHROPIC_API_KEY is not set in .env" >&2
  exit 1
fi

# ---- Validate build artifacts ----
if [[ ! -f out/supervisor.js ]]; then
  echo "Error: out/supervisor.js not found — run 'npx shadow-cljs compile supervisor' first" >&2
  exit 1
fi

# ---- Start supervisor ----
echo "==> Starting supervisor on :8400..."
LOOM_REPO_PATH="$PROJECT_DIR" node out/supervisor.js &
SUPERVISOR_PID=$!

# Poll until supervisor is ready (up to 30s)
echo -n "==> Waiting for supervisor..."
READY=0
for i in $(seq 1 30); do
  if curl -sf "$SUPERVISOR_URL/stats" >/dev/null 2>&1; then
    READY=1
    break
  fi
  if ! kill -0 "$SUPERVISOR_PID" 2>/dev/null; then
    echo ""
    echo "Error: supervisor process died before becoming ready" >&2
    exit 1
  fi
  sleep 1
  echo -n "."
done
echo ""

if [[ $READY -eq 0 ]]; then
  echo "Error: supervisor did not become ready within 30s" >&2
  exit 1
fi
echo "==> Supervisor ready."

# ---- POST /spawn ----
PROGRAM_CONTENT="$(cat "$PROGRAM_MD")"
echo "==> Sending POST /spawn with $(wc -c < "$PROGRAM_MD" | tr -d ' ') bytes from $PROGRAM_MD..."

SPAWN_RESPONSE=$(curl -sf -X POST "$SUPERVISOR_URL/spawn" \
  -H "Content-Type: application/json" \
  -d "$(printf '{"program_md":%s}' "$(echo "$PROGRAM_CONTENT" | python3 -c 'import json,sys; print(json.dumps(sys.stdin.read()))')" )" \
  2>&1) || {
  echo "Error: POST /spawn failed:" >&2
  echo "$SPAWN_RESPONSE" >&2
  exit 1
}

GEN_NUM=$(echo "$SPAWN_RESPONSE" | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d["generation"])' 2>/dev/null || echo "")
BRANCH=$(echo "$SPAWN_RESPONSE" | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d.get("branch",""))' 2>/dev/null || echo "")
CONTAINER=$(echo "$SPAWN_RESPONSE" | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d.get("container_name",""))' 2>/dev/null || echo "")

if [[ -z "$GEN_NUM" ]]; then
  echo "Error: /spawn did not return a generation number" >&2
  echo "Response: $SPAWN_RESPONSE" >&2
  exit 1
fi

echo "==> Spawned generation $GEN_NUM (branch: $BRANCH, container: $CONTAINER)"

# ---- Poll until done ----
echo "==> Polling status (every ${POLL_INTERVAL}s, wall-clock cap ${TIMEOUT_SECS}s)..."
DEADLINE=$(( $(date +%s) + TIMEOUT_SECS ))
OUTCOME=""

while true; do
  NOW=$(date +%s)
  if [[ $NOW -ge $DEADLINE ]]; then
    echo ""
    echo "==> Script timeout (${TIMEOUT_SECS}s) reached — generation may still be running in the supervisor." >&2
    exit 1
  fi

  VERSIONS=$(curl -sf "$SUPERVISOR_URL/versions" 2>/dev/null || echo "[]")
  OUTCOME=$(echo "$VERSIONS" | python3 -c "
import json, sys
gens = json.load(sys.stdin)
target = [g for g in gens if g.get('generation') == $GEN_NUM]
if target:
    print(target[0].get('outcome', ''))
" 2>/dev/null || echo "")

  TS=$(date +"%H:%M:%S")
  echo -ne "\r[$TS] generation=$GEN_NUM outcome=${OUTCOME:-in-progress}    "

  case "$OUTCOME" in
    promoted|done|failed|timeout|rolled-back)
      echo ""
      break
      ;;
  esac

  sleep "$POLL_INTERVAL"
done

# ---- Final report ----
echo ""
echo "==> Generation $GEN_NUM finished with outcome: $OUTCOME"

VERSIONS=$(curl -sf "$SUPERVISOR_URL/versions" 2>/dev/null || echo "[]")
GEN_RECORD=$(echo "$VERSIONS" | python3 -c "
import json, sys
gens = json.load(sys.stdin)
target = [g for g in gens if g.get('generation') == $GEN_NUM]
if target:
    print(json.dumps(target[0], indent=2))
" 2>/dev/null || echo "{}")
echo "$GEN_RECORD"

case "$OUTCOME" in
  promoted)
    echo "==> SUCCESS: generation $GEN_NUM promoted."
    exit 0
    ;;
  done)
    # done means Lab finished but Prime hasn't promoted yet — treat as success for script purposes
    echo "==> Lab completed (not yet promoted). Inspect with: POST $SUPERVISOR_URL/promote {\"generation\": $GEN_NUM}"
    exit 0
    ;;
  *)
    echo "==> FAILED: outcome=$OUTCOME" >&2
    exit 1
    ;;
esac
