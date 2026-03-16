# Loom — Recursive Self-Improving Agent

Loom is a self-modifying coding agent written in pure ClojureScript. It runs in Apple containers (VM-level isolation), can rewrite its own code, test modifications in an isolated Lab container, and promote successful changes.

The architecture is described in detail in [The Prime and the Lab](https://lispmeister.github.io/deeprecursion/posts/2026-03-12-recursive-self-improvement.html).

## Architecture

Three components, all ClojureScript on Node.js:

- **Prime Container** — The live, known-good agent. Runs the agentic loop (Claude API → tool dispatch → repeat). Spawns Labs, verifies results, promotes or rolls back.
- **Supervisor** — Runs on the host (macOS). Manages container lifecycle: create, start, stop, destroy. Maintains generation history. Exposes HTTP dashboard.
- **Lab Container** — Ephemeral. Reads `program.md`, runs an autonomous agent loop, commits results. Prime verifies independently before promoting.

```
Host (macOS 26, Apple Silicon)
├── Supervisor (ClojureScript/Node)
│   ├── HTTP dashboard (:8400)
│   ├── Container lifecycle (shells out to `container` CLI)
│   └── generations.edn (lineage tracking)
├── Prime Container
│   ├── Agent loop + Claude API client
│   ├── Tools: read-file, write-file, edit-file, bash, spawn_lab,
│   │         verify_generation, promote_generation, rollback_generation,
│   │         reflect_and_propose
│   └── HTTP dashboard + chat endpoint (:8401)
└── Lab Container (ephemeral)
    ├── Autonomous agent (reads program.md, implements task)
    ├── GET /status endpoint
    └── Commits to lab/gen-N branch
```

## LLM Usage

Both Prime and Lab are autonomous agents that call the Claude API (Anthropic). They share the same API client code (`agent/claude.cljs`) but run with different configurations:

| | Prime | Lab |
|---|---|---|
| **Default model** | `claude-sonnet-4-20250514` | `claude-haiku-4-5-20251001` |
| **Tools** | Full set + self-modification (spawn, verify, promote, rollback) | Base tools only (read, write, edit, bash) |
| **Interaction** | Multi-turn conversation, up to 20 messages in context | Single turn — program.md in, committed code out |
| **Loop** | Tool-use loop, up to 25 iterations per turn | Same tool-use loop, same iteration cap |

Labs cannot spawn other Labs or promote themselves — `agent/self_modify.cljs` is excluded from the `lab-worker` build target.

### Environment Variables

All configuration lives in `.env` (gitignored). Copy `env-template` to get started:

```bash
cp env-template .env
# Edit .env with your API keys
```

| Variable | Required | Default | Purpose |
|---|---|---|---|
| `ANTHROPIC_API_KEY` | Yes | — | API key for Prime (and Lab fallback) |
| `LOOM_MODEL` | No | `claude-sonnet-4-20250514` | Model for Prime agent |
| `LOOM_LAB_API_KEY` | No | Falls back to `ANTHROPIC_API_KEY` | Separate API key for Lab containers |
| `LOOM_LAB_API_BASE` | No | `https://api.anthropic.com` | Base URL for Lab API (Anthropic-compatible) |
| `LOOM_LAB_MODEL` | No | Falls back to `LOOM_MODEL` | Model override for Lab containers |
| `LOOM_LAB_TIMEOUT_MS` | No | `300000` (5 min) | Max Lab generation runtime (Supervisor hard-kill + Prime polling) |
| `LOOM_MAX_ITERATIONS` | No | `40` | Max tool-use loop iterations per turn (forwarded to Labs) |
| `LOOM_REPO_PATH` | No | `.` | Repo path managed by the Supervisor |
| `LOOM_NETWORK` | No | `loom-net` | Container network name |

**Model precedence:**

- **Prime** reads `LOOM_MODEL` → falls back to `claude-sonnet-4-20250514`
- **Lab** receives `LOOM_LAB_MODEL` if set, otherwise `LOOM_MODEL`, otherwise uses its built-in default (`claude-haiku-4-5-20251001`)

```bash
# Default behavior: Sonnet for Prime, Haiku for Lab
set -a && source .env && set +a
npm run supervisor

# Use Sonnet for both Prime and Lab
LOOM_MODEL=claude-sonnet-4-20250514 npm run supervisor

# Sonnet for Prime, specific model for Lab
LOOM_LAB_MODEL=claude-haiku-4-5-20251001 npm run supervisor
```

## Tech Stack

| Component | Technology |
|---|---|
| Language | ClojureScript (self-hosted via `cljs.js`) |
| Runtime | Node.js (no JVM) |
| Schemas | [Malli](https://github.com/metosin/malli) — data-driven, schemas as EDN |
| Containers | [Apple Containerization](https://github.com/apple/container) — VM-per-container |
| LLM | Claude API (Anthropic) — single provider for v0 |
| HTTP | Node.js `http` module — no frameworks |
| Task tracking | [beads](https://github.com/lispmeister/beads) |

## Project Structure

```
src/
  loom/
    shared/        — Malli schemas, eval protocol, HTTP helpers
    agent/         — Agentic loop, Claude API client, tools, self-modify
    supervisor/    — Container lifecycle, version management, dashboard
    lab/           — Eval server + autonomous worker
test/
  loom/            — Tests
```

## Key Design Decisions

1. **Self-hosted ClojureScript.** Components are AOT-compiled by shadow-cljs for startup performance, but the Lab uses `cljs.js/eval-str` at runtime to evaluate modified code without a build step. This is what enables the agent to test its own modifications.

2. **Homoiconic code.** ClojureScript is a Lisp — code is data. The agent constructs and manipulates syntax trees directly, not text.

3. **Malli contracts as fixed points.** Communication schemas (Proposal, ProbeResult, Verdict) are the one thing the agent cannot modify. Everything else — loop, tools, prompts, probe strategy — is mutable.

4. **Source files as serialization.** Modified code travels as `.cljs` files, not serialized ASTs or binary formats. The Supervisor copies files between version directories.

5. **Container = keep/revert boundary.** Instead of building safety into the language, we put experiments in disposable VMs. Promote = merge to main + tag. Revert = destroy container + discard branch.

6. **No MCP, no sub-agents, no streaming for v0.** Direct Claude API calls, radical minimalism. Following Pi coding agent's approach.

## HTTP Endpoints

**Supervisor (:8400)**
- `GET /` — Dashboard (generation history, status)
- `GET /logs` — SSE event stream
- `GET /stats` — JSON stats
- `POST /spawn` — Create Lab container with program.md
- `POST /promote` — Merge lab branch, tag, cleanup
- `POST /rollback` — Discard lab branch, cleanup

**Prime (:8401)**
- `GET /` — Dashboard
- `GET /logs` — SSE event stream
- `GET /stats` — JSON stats
- `POST /chat` — User message input, SSE response

**Lab (:8402)**
- `GET /status` — `{status, progress, error}`

## Malli Schemas (Contracts)

The eval protocol schemas are the fixed points. The agent cannot modify these.

```clojure
;; Eval protocol: Prime ↔ Lab eval server
(def EvalRequest
  [:map
   [:form :string]
   [:timeout {:optional true} :int]])

(def EvalResponse
  [:map
   [:status [:enum :ok :error]]
   [:value {:optional true} :any]
   [:message {:optional true} :string]])
```

The self-modification cycle uses direct HTTP JSON payloads (`POST /spawn`, `/promote`, `/rollback`) — see PLAN.md for details.

## Prerequisites

- macOS 26+ on Apple Silicon
- Node.js 25+
- Clojure CLI (`clj`)
- shadow-cljs (`npm install -g shadow-cljs`)
- Apple Containerization (`container` CLI — install from https://github.com/apple/container/releases)

## Getting Started

```bash
# Install dependencies
npm install

# Run tests
npm test && node out/test.js

# Start Apple container system
container system start

# Start the supervisor (host-side)
npm run supervisor

# The supervisor creates and manages Prime/Lab containers automatically
```

## Development

- VS Code + [Calva](https://calva.io/) for REPL-connected editing
- Watch mode: `npm run watch:test` for continuous test compilation
- Task tracking: `beads list` to see open work

## References

- [The Prime and the Lab](https://lispmeister.github.io/deeprecursion/posts/2026-03-12-recursive-self-improvement.html) — Architecture spec (blog post)
- [Pi Coding Agent](https://mariozechner.at/posts/2025-11-30-pi-coding-agent/) — Minimalist agent design
- [Build Your First 24/7 Agentic Loop](https://wezzard.com/post/2025/09/build-your-first-agentic-loop-9d22) — Contract-driven loops
- [Malli](https://github.com/metosin/malli) — Schema library
- [Apple Containerization](https://github.com/apple/container) — Container runtime
- [ClojureScript Self-Hosting](https://clojurescript.org/guides/self-hosting) — `cljs.js` docs

## License

All rights reserved.
