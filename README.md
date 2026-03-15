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
│   │         check_lab_status, verify_generation, promote_generation
│   └── HTTP dashboard + chat endpoint (:8401)
└── Lab Container (ephemeral)
    ├── Autonomous agent (reads program.md, implements task)
    ├── GET /status endpoint
    └── Commits to lab/gen-N branch
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

These are the fixed points of the system. The agent cannot modify these schemas.

```clojure
;; Modification proposal: Prime → Supervisor
(def Proposal
  [:map
   [:id :string]
   [:description :string]
   [:files [:vector [:map [:path :string] [:content :string]]]]
   [:rationale :string]
   [:parent-version :int]])

;; Eval protocol: Prime ↔ Lab
(def EvalRequest
  [:map
   [:form :string]
   [:timeout {:optional true} :int]])

(def EvalResponse
  [:map
   [:status [:enum :ok :error]]
   [:value {:optional true} :any]
   [:message {:optional true} :string]])

;; Probe result (aggregated)
(def ProbeResult
  [:map
   [:status [:enum :ok :error :timeout]]
   [:value {:optional true} :any]
   [:elapsed-ms :int]])

;; Verdict: Prime → Supervisor
(def Verdict
  [:map
   [:proposal-id :string]
   [:decision [:enum :promote :revert]]
   [:evidence [:vector ProbeResult]]
   [:reasoning :string]])
```

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
