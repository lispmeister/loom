# Contributing to Loom

## Prerequisites

- macOS 26+ on Apple Silicon
- Node.js 25+
- Clojure CLI (`clj`)
- shadow-cljs (`npm install -g shadow-cljs`)
- Apple Containerization (`container` CLI)

## Setup

```bash
npm install
```

## Running Tests

```bash
npm test && node out/test.js
```

`npm test` compiles via shadow-cljs; `node out/test.js` runs the compiled tests. Both steps are required.

For continuous recompilation during development:

```bash
npm run watch:test
# In another terminal:
node out/test.js   # re-run after each recompile
```

## Building Components

```bash
# Supervisor (runs on host)
npm run supervisor

# Prime agent (runs in container or locally)
npm run agent

# Lab worker (compiled for container use)
npx shadow-cljs compile lab-worker
```

## Environment Variables

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `ANTHROPIC_API_KEY` | Yes | — | Claude API key |
| `LOOM_SUPERVISOR_URL` | No | `http://localhost:8400` | Supervisor endpoint for Prime |
| `LOOM_MODEL` | No | `claude-sonnet-4-20250514` (Prime), `claude-haiku-4-5-20251001` (Lab) | Model override |
| `LOOM_REPO_PATH` | No | `.` | Repo path for Supervisor |
| `LOOM_NETWORK` | No | `loom-net` | Container network name |
| `LOOM_LOOP_DELAY_MS` | No | `2000` | Delay between agent loop API calls (ms) |
| `LOOM_MAX_CONTEXT` | No | `20` | Max messages kept in conversation history |
| `PORT` | No | `8400`/`8401`/`8402` | Listen port (per component) |

## Running a Self-Modification Generation

### Quick version

```bash
./scripts/run-gen.sh path/to/program.md
```

### Manual steps

1. **Disconnect Tailscale** (if running) — it breaks container vmnet routing:
   ```bash
   # After disconnecting:
   container system stop && container system start
   ```

2. **Build everything:**
   ```bash
   npx shadow-cljs compile supervisor agent lab-worker
   ```

3. **Start Supervisor** (terminal 1):
   ```bash
   node out/supervisor.js
   ```

4. **Start Prime** (terminal 2):
   ```bash
   node out/agent.js
   ```

5. **Send a task** via Prime's chat endpoint:
   ```bash
   curl -X POST http://localhost:8401/chat \
     -H 'Content-Type: application/json' \
     -d '{"message": "Read the program.md I have prepared and execute the self-modification workflow."}'
   ```

6. **Monitor** (terminal 3):
   ```bash
   ./scripts/watch-logs.sh
   # Or manually:
   curl -N http://localhost:8400/logs   # Supervisor SSE
   curl -N http://localhost:8401/logs   # Prime SSE
   ```

7. **Check result:**
   - Supervisor dashboard: http://localhost:8400
   - Generation history: `curl http://localhost:8400/versions`

## Writing a program.md

See [`templates/program.md.example`](templates/program.md.example) for the template.

Key guidelines:
- **One focused task per generation** — smaller is better
- **Explicit file paths** — tell the Lab exactly which files to modify
- **Concrete acceptance criteria** — "tests pass" is not enough; specify what behavior to verify
- **Include the constraint block** — Labs cannot run `npm test` or `npx shadow-cljs` (no build toolchain in the container VM)

## Generation Lifecycle

```
User writes program.md
  → Prime calls spawn_lab(program_md)
    → Supervisor creates branch lab/gen-N
    → Supervisor clones repo, writes program.md, starts container
    → Lab reads program.md, runs autonomous agent loop
    → Lab commits changes, reports done via GET /status
  → Prime calls verify_generation(N)
    → Checks out lab/gen-N, shows diff, runs tests, returns to master
  → If PASS: Prime calls promote_generation(N)
    → Supervisor merges to main, tags gen-N, deletes branch, cleans up
  → If FAIL: Prime calls rollback_generation(N)
    → Supervisor deletes branch, marks failed, cleans up
```

## Task Tracking

All tasks are tracked in [beads](https://github.com/lispmeister/beads):

```bash
beads list              # open issues
beads list --all        # include closed
beads show <id>         # details + dependencies
```

Do not add task lists to markdown files. Create a bead instead.

## Architecture Reviews

Periodic reviews are stored in [`architecture-reviews/`](architecture-reviews/). These assess the state of the codebase, identify risks, and generate actionable beads.

## Code Style

- Pure ClojureScript — no Java interop, no JVM dependencies
- Prefer `node:` built-in modules over npm packages
- Keep functions small; prefer data transformation over side effects
- Errors resolve as `{:error true :message "..."}` — never thrown
- Format with cljfmt (auto-applied by Claude Code hook)
