# Loom — Recursive Self-Improving Agent

## What This Is

Loom is a self-modifying coding agent written in pure ClojureScript. It runs in Apple containers (VM-level isolation), can rewrite its own code, test modifications in an isolated Lab container, and promote successful changes. The architecture is described in detail in [The Prime and the Lab](https://lispmeister.github.io/deeprecursion/posts/2026-03-12-recursive-self-improvement.html).

## Architecture

Three components, all ClojureScript on Node.js:

- **Prime Container** — The live, known-good agent. Runs the agentic loop (Claude API → tool dispatch → repeat). Connects to Lab containers to probe modified code.
- **Supervisor** — Runs on the host (macOS). Manages container lifecycle: create, start, stop, destroy. Maintains version history. Exposes HTTP dashboard.
- **Lab Container** — Ephemeral. Boots modified agent code with a form evaluation server. The Prime probes it to judge whether a modification works.

```
Host (macOS 26, Apple Silicon)
├── Supervisor (ClojureScript/Node)
│   ├── HTTP dashboard (:8400)
│   ├── Container lifecycle (shells out to `container` CLI)
│   └── versions/ directory (immutable version history)
├── Prime Container
│   ├── Agent loop + Claude API client
│   ├── 5 tools: read-file, write-file, edit-file, bash, self-modify
│   ├── Eval client (connects to Lab's eval server)
│   └── HTTP dashboard + chat endpoint (:8401)
└── Lab Container (ephemeral)
    ├── Modified agent source
    └── Form eval server (TCP, accepts EDN forms, returns EDN results)
```

## Tech Stack

| Component | Technology |
|---|---|
| Language | ClojureScript (self-hosted via `cljs.js`) |
| Runtime | Node.js (no JVM, no build step) |
| Schemas | [Malli](https://github.com/metosin/malli) — data-driven, schemas as EDN |
| Containers | [Apple Containerization](https://github.com/apple/container) — VM-per-container |
| LLM | Claude API (Anthropic) — single provider for v0 |
| HTTP | Node.js `http` module — no frameworks |
| Fallback containers | [UTM](https://docs.getutm.app/) via `utmctl` CLI |

## Project Structure

```
src/
  loom/
    shared/        — Malli schemas, eval protocol, HTTP helpers
    agent/         — Agentic loop, Claude API client, tools
    supervisor/    — Container lifecycle, version management, dashboard
    lab/           — Form evaluation server (~50 lines)
test/
  loom/            — Tests
```

## Key Design Decisions

1. **Self-hosted ClojureScript.** No build step. `cljs.js/eval-str` compiles at runtime. Source files are the program. This enables the agent to eval modified code without toolchain.

2. **Homoiconic code.** ClojureScript is a Lisp — code is data. The agent constructs and manipulates syntax trees directly, not text.

3. **Malli contracts as fixed points.** Communication schemas (Proposal, ProbeResult, Verdict) are the one thing the agent cannot modify. Everything else — loop, tools, prompts, probe strategy — is mutable.

4. **Source files as serialization.** Modified code travels as `.cljs` files, not serialized ASTs or binary formats. The Supervisor copies files between version directories.

5. **Container = keep/revert boundary.** Instead of building safety into the language, we put experiments in disposable VMs. Promote = copy source to new version. Revert = destroy container.

6. **No MCP, no sub-agents, no streaming for v0.** Five tools, direct Claude API calls, stdin/stdout simplicity. Following Pi coding agent's principle of radical minimalism.

## Malli Schemas (Contracts)

These are the fixed points. DO NOT MODIFY these schemas without explicit user approval.

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

## HTTP Endpoints

**Supervisor (:8400)**
- `GET /` — Dashboard
- `GET /logs` — SSE event stream
- `GET /stats` — JSON stats
- `GET /lab/repl` — Proxied Lab eval session
- `GET /versions` — Version history with diffs
- `POST /proposal` — Receive modification proposal from Prime
- `POST /verdict` — Receive promote/revert verdict from Prime

**Prime (:8401)**
- `GET /` — Dashboard
- `GET /logs` — SSE event stream
- `GET /stats` — JSON stats
- `POST /chat` — User message input, SSE response

## Development Setup

### Prerequisites
- macOS 26+ on Apple Silicon
- Node.js 25+
- Clojure CLI (`clj`)
- shadow-cljs (`npm install -g shadow-cljs`)
- Apple Containerization (`container` CLI — install from https://github.com/apple/container/releases)
- Babashka + bbin (for clojure-mcp-light tools)

### Editor
- VS Code + [Calva](https://calva.io/) for REPL-connected editing
- [clojure-mcp-light](https://github.com/bhauman/clojure-mcp-light) hooks installed for Claude Code paren repair

### Running
```bash
# Start Apple container system
container system start

# Start the supervisor (host-side)
node supervisor.js

# The supervisor creates and manages Prime/Lab containers automatically
```

## Code Style

- Pure ClojureScript. No Java interop, no JVM dependencies.
- Prefer `node:` built-in modules over npm packages.
- Keep functions small. Prefer data transformation over side effects.
- Use Malli for all external-facing data validation.
- No macros unless absolutely necessary — LLMs handle functions better.
- Format with cljfmt (auto-applied by Claude Code hook).

## References

- [The Prime and the Lab](https://lispmeister.github.io/deeprecursion/posts/2026-03-12-recursive-self-improvement.html) — Architecture spec (blog post)
- [Pi Coding Agent](https://mariozechner.at/posts/2025-11-30-pi-coding-agent/) — Minimalist agent design
- [Build Your First 24/7 Agentic Loop](https://wezzard.com/post/2025/09/build-your-first-agentic-loop-9d22) — Contract-driven loops
- [Malli](https://github.com/metosin/malli) — Schema library
- [Apple Containerization](https://github.com/apple/container) — Container runtime
- [ClojureScript Self-Hosting](https://clojurescript.org/guides/self-hosting) — `cljs.js` docs
- [ByteRover](https://clawhub.ai/byteroverinc/byterover) — Memory system (future integration)
