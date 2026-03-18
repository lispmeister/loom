# CAMBRIAN-SPEC-001

**Version:** 001 | **Ancestor:** nil | **Lineage:** genesis | **Organism:** Loom

> This document is the genome. An LLM reading this specification produces a working
> self-modifying coding agent. The spec evolves; the code is regenerated from scratch.

---

## 1. What It Is

A recursive self-improving coding agent. It modifies its own source code through isolated
experimentation, automated verification, and selective promotion. Three processes cooperate:
one orchestrates (Prime), one manages isolation (Supervisor), one does the work (Lab).

## 2. Stack

- **Language:** ClojureScript on Node.js. No JVM, no Java interop.
- **Build:** shadow-cljs (AOT compilation to JS). Single dependency: `metosin/malli 0.16.4`.
- **Runtime:** `node:` built-in modules only. Zero npm runtime dependencies.
- **Isolation:** macOS containers via Apple Containerization CLI (`container` binary).
- **LLM:** Claude API (non-streaming, tool_use mode). Any OpenAI-compatible API via base URL override.

## 3. Architecture

```
┌─────────────────────────────────────────────────────┐
│  Host Machine                                       │
│  ┌───────────────────────┐  ┌────────────────────┐  │
│  │ Supervisor :8400      │  │ Prime :8401        │  │
│  │ - container lifecycle │  │ - agentic loop     │  │
│  │ - generation history  │  │ - self-modify tools│  │
│  │ - fitness tracking    │  │ - reflect engine   │  │
│  │ - spawn/promote/roll  │  │ - autonomous cycle │  │
│  │ - dashboard           │  │ - dashboard + chat │  │
│  └───────────┬───────────┘  └─────────┬──────────┘  │
│              │                        │              │
│         HTTP │  ┌─────────────────────┘              │
│              │  │ HTTP (spawn/verify/promote)         │
│  ┌───────────▼──▼────────┐                           │
│  │ Lab Container :8402   │ ← ephemeral, disposable   │
│  │ - reads program.md    │                           │
│  │ - base tools only     │                           │
│  │ - commits to branch   │                           │
│  │ - reports status      │                           │
│  └───────────────────────┘                           │
└─────────────────────────────────────────────────────┘
```

**Prime** (port 8401): The live, known-good agent. Runs the LLM conversation loop with
full tool access including self-modification. Orchestrates the improvement cycle.

**Supervisor** (port 8400): Host-only. Manages container lifecycle (create/start/stop/destroy).
Maintains generation history in `generations.edn`. Tracks fitness. Serves HTML dashboard.
Never runs inside a container.

**Lab** (port 8402): Ephemeral worker spawned per generation. Reads a `program.md` task
contract, runs an agentic loop with base tools only (read, write, edit, bash). Commits
results to a `lab/gen-N` branch. Cannot spawn Labs, cannot promote itself. Destroyed after
verification regardless of outcome.

## 4. Fixed-Point Contracts

These Malli schemas are **immutable**. The agent must never modify them. They are the
protocol boundary that allows components to communicate across versions.

```clojure
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

Schema version: 1. All components validate against these at startup.

## 5. The Self-Modification Cycle

The core algorithm. This is what makes the agent recursive.

```
reflect → spawn → work → verify → promote|rollback → repeat
```

### 5.1 Reflect

Gather context: generation history, fitness log, lessons log, codebase file list.
Prompt the LLM to produce a `program.md` — a structured task contract containing:
- Task description (what to change)
- Context (why this change matters)
- Acceptance criteria (how to verify success)
- Constraints (what not to touch)

### 5.2 Spawn

Prime POSTs `{program-md, generation, api-key, model}` to Supervisor `/spawn`.

Supervisor:
1. Clones the repo
2. Creates branch `lab/gen-N`
3. Writes `program.md` to workspace root
4. Copies the pre-built `lab-worker.js` into the clone
5. Launches a container with: dual-network, port publish (18400 + N), env injection
6. Returns `{ok: true, container-id, generation}`

Prime polls Lab `/status` every 5 seconds until `done`, `failed`, or timeout (default 300s).

### 5.3 Work (inside Lab)

1. Read `/workspace/program.md`
2. Run agentic loop (LLM + base tools) to complete the task
3. On completion: `git add -A && git commit -m "gen-N: <summary>"`
4. Set status to `done` (or `failed` on error)

Lab model default: `claude-haiku-4-5-20251001` (cheapest viable).

### 5.4 Verify (two-stage)

**Stage 1 — Tests:**
- Fetch branch, compute diff: `git diff master...lab/gen-N`
- Checkout lab branch
- Run `npm test && node out/test.js` (3 retries, 2s delay between)
- Tests must pass. Test count must not decrease vs. parent.

**Stage 2 — LLM Review:**
- Submit the diff to LLM for code review
- LLM returns: `{approved: boolean, summary: string, concerns: [string]}`
- Must be approved

Both stages must pass. Either failing triggers rollback.

### 5.5 Promote

Supervisor `/promote`:
1. Merge `lab/gen-N` into master (fast-forward or merge commit)
2. Create annotated git tag `gen-N`
3. Delete the lab branch
4. Update generation record: outcome → `:promoted`
5. Compute and record fitness score

### 5.6 Rollback

Supervisor `/rollback`:
1. Delete branch `lab/gen-N`
2. Destroy container
3. Update generation record: outcome → `:failed`

## 6. HTTP APIs

### Supervisor (port 8400)

| Method | Path       | Body                                          | Response                     |
|--------|------------|-----------------------------------------------|------------------------------|
| GET    | /          | —                                             | HTML dashboard               |
| GET    | /stats     | —                                             | `{generation, status, uptime}` |
| GET    | /versions  | —                                             | `[Generation...]`            |
| GET    | /logs      | —                                             | SSE stream                   |
| POST   | /spawn     | `{program-md, generation, api-key, model?, api-base?, max-iterations?}` | `{ok, container-id, generation}` |
| POST   | /promote   | `{generation}`                                | `{ok, generation}`           |
| POST   | /rollback  | `{generation}`                                | `{ok, generation}`           |

### Prime (port 8401)

| Method | Path   | Response                       |
|--------|--------|--------------------------------|
| GET    | /      | HTML dashboard with chat       |
| GET    | /stats | `{generation, tokens, status}` |
| GET    | /logs  | SSE stream                     |
| POST   | /chat  | SSE stream (user interaction)  |

### Lab (port 8402)

| Method | Path    | Response                                     |
|--------|---------|----------------------------------------------|
| GET    | /status | `{status: ready|working|done|failed, progress?, error?}` |

## 7. Data Schemas

### Generation Record (persisted in `generations.edn`)

```clojure
[:map
 [:generation :int]
 [:parent :int]
 [:branch :string]              ; "lab/gen-N"
 [:program-md-hash :string]
 [:outcome [:enum :promoted :failed :timeout :in-progress :done]]
 [:created :string]             ; ISO-8601
 [:completed {:optional true} :string]
 [:container-id :string]
 [:source {:optional true} [:enum :user :reflect :cli]]]
```

### Fitness Record (append-only JSONL in `fitness-log.jsonl`)

```json
{"generation": 1, "score": 42.5, "tests-run": 10, "assertions": 25,
 "failures": 0, "total-tokens": 7500, "task-type": "test", "program-md-hash": "abc123"}
```

**Scoring:** `(tests-run × 10) + (assertions × 1) − (total-tokens / 1000)`

**Safety invariant:** tests must pass AND test count must not decrease from parent.

### Lessons Record (append-only JSONL in `lessons-log.jsonl`)

```json
{"generation": 1, "outcome": "promoted", "lesson": "Small focused changes succeed more often"}
```

## 8. Tool System

### Base Tools (Prime + Lab)

| Tool        | Input                     | Behavior                              |
|-------------|---------------------------|---------------------------------------|
| `read_file` | `{path}`                  | Returns content with 1-based line numbers |
| `write_file`| `{path, content}`         | Creates parent dirs as needed         |
| `edit_file` | `{path, old_string, new_string}` | Replaces first occurrence      |
| `bash`      | `{command, timeout?}`     | Shell exec, 10MB max output buffer    |

### Self-Modification Tools (Prime only, excluded from Lab build)

| Tool                   | Input            | Behavior                                 |
|------------------------|------------------|------------------------------------------|
| `spawn_lab`            | `{program_md}`   | POST to Supervisor /spawn, poll status   |
| `verify_generation`    | `{generation}`   | Two-stage verify (tests + LLM review)    |
| `promote_generation`   | `{generation}`   | POST to Supervisor /promote              |
| `rollback_generation`  | `{generation}`   | POST to Supervisor /rollback             |

### Reflect Tools (Prime only)

| Tool                   | Behavior                                             |
|------------------------|------------------------------------------------------|
| `reflect_and_propose`  | Gather history + fitness → LLM produces program.md   |
| `build_task`           | Format task/context/criteria into program.md          |

Tool dispatch: sequential via promise chain. Each result returns with the `tool_use_id`.

## 9. Agentic Loop

```
create-agent(api-key, model, system-prompt, tools, tool-registry)
  → agent state

run-turn(agent, user-message)
  → {agent', response, token-usage}

tool-use-loop:
  1. Send messages to LLM
  2. If response has tool_use blocks → dispatch each → append results → goto 1
  3. If end_turn or iteration limit (default 40) → return
```

**Context management:** Keep first message + last 20 messages. Truncate tool results >2000 chars.

## 10. Autonomous Loop

Drives the full cycle without human input. Configurable stopping conditions:

| Condition       | Env Variable           | Default    |
|-----------------|------------------------|------------|
| Generation cap  | `LOOM_MAX_GENERATIONS` | 5          |
| Token budget    | `LOOM_TOKEN_BUDGET`    | 0 (none)   |
| Plateau         | `LOOM_PLATEAU_WINDOW`  | 3          |
| Signal          | SIGINT / SIGTERM       | graceful   |

Plateau = N consecutive promoted generations with no fitness improvement → stop.

## 11. Container Lifecycle

Wraps the `container` CLI (Apple Containerization framework). Every function returns
a promise resolving to `{:ok true ...}` or `{:error true :message ...}`. Never throws.

| Function         | Purpose                                      |
|------------------|----------------------------------------------|
| `cli-available?` | Check if `container` binary exists           |
| `create`         | Create container from config                 |
| `start`          | Start created container                      |
| `stop`           | Stop running container                       |
| `destroy`        | Remove container                             |
| `run`            | Create + start in one call                   |
| `inspect`        | Get container state                          |
| `published-port` | Get host-mapped port                         |
| `network-ensure` | Create network if it doesn't exist           |

Lab containers receive: dual-network, env vars (API key, model, iteration limit),
port mapping (18400 + gen), volume mount (cloned repo at `/workspace`).

## 12. Claude API Client

Non-streaming HTTP client for the Claude Messages API.

- Model default: `claude-sonnet-4-20250514` (Prime), `claude-haiku-4-5-20251001` (Lab)
- Max tokens: 4096
- Rate limiting: retry up to 3× on HTTP 429, respect `retry-after` header
- Custom endpoint: `ANTHROPIC_API_BASE` env var (auto-selects http/https)
- Response includes `:token-usage {input-tokens, output-tokens}`

## 13. Shared Infrastructure

**HTTP Server** (`shared/http.cljs`): Route-based, built on `node:http`. No frameworks.
Routes are `[method path] → handler-fn`. Supports JSON responses, body parsing, SSE, CORS.

**HTTP Client** (`shared/http_client.cljs`): Built on `node:http`. Functions: `request`,
`get-json`, `post-json`. Auto-parses JSON responses.

**State** (`agent/state.cljs`): Serializes Prime state to `tmp/prime-state.edn`.
Version-tagged. Returns nil on mismatch/corrupt. Allows restart without losing context.

**Budget** (`agent/budget.cljs`): Rolling 5-hour window rate limiter. Tracks prompt count.
Functions: `record-call!`, `budget-remaining`, `budget-exhausted?`.

## 14. Configuration

| Variable               | Default                          | Purpose              |
|------------------------|----------------------------------|----------------------|
| `ANTHROPIC_API_KEY`    | *required*                       | LLM access           |
| `LOOM_MODEL`           | `claude-sonnet-4-20250514`       | Prime model          |
| `LOOM_LAB_MODEL`       | `claude-haiku-4-5-20251001`      | Lab model            |
| `LOOM_LAB_API_KEY`     | falls back to ANTHROPIC_API_KEY  | Multi-provider       |
| `LOOM_LAB_API_BASE`    | —                                | Custom API endpoint  |
| `LOOM_LAB_TIMEOUT_MS`  | 300000                           | Lab timeout (ms)     |
| `LOOM_MAX_ITERATIONS`  | 40                               | Max tool loops       |
| `LOOM_MAX_GENERATIONS` | 5                                | Autonomous gen cap   |
| `LOOM_TOKEN_BUDGET`    | 0                                | Token cap (0=none)   |
| `LOOM_PLATEAU_WINDOW`  | 3                                | Plateau detection    |

## 15. Project Layout

```
src/loom/
  shared/        schemas.cljs http.cljs http_client.cljs eval_client.cljs inventory.cljs
  agent/         core.cljs claude.cljs loop.cljs tools.cljs dispatch.cljs
                 self_modify.cljs reflect.cljs autonomous.cljs state.cljs budget.cljs
                 cli.cljs http.cljs
  supervisor/    core.cljs http.cljs container.cljs lab.cljs
                 fitness.cljs generations.cljs git.cljs
  lab/           worker.cljs eval_server.cljs
test/loom/       (mirrors src — one test file per source file)
templates/       reflect-system.md lab-system.md program.md.example
config/          fitness.edn
```

Build targets: `:supervisor`, `:agent`, `:lab-worker`, `:test` (all output to `out/`).
Lab-worker uses `:simple` optimizations for a self-contained JS bundle.

## 16. Design Laws

1. **Promise-based, never-throws.** All async returns `{:ok true ...}` or `{:error true :message ...}`.
2. **Containers are the keep/revert boundary.** Promote = merge + tag. Revert = destroy + discard.
3. **Fixed-point contracts are sacred.** Schemas don't change. Everything else is mutable.
4. **Two-stage verification.** Tests AND LLM review. Both must pass.
5. **Append-only audit trail.** Fitness log, lessons log, generation history — never overwritten.
6. **Radical minimalism.** No frameworks, no streaming, no sub-agents. Direct API calls over HTTP.
7. **Data over effects.** Prefer pure transformations. Isolate side effects at boundaries.

## 17. Acceptance Criteria

A valid organism produced from this spec must:

1. ✅ `npm test && node out/test.js` — all tests pass
2. ✅ Supervisor starts on :8400, serves dashboard at GET /
3. ✅ Prime starts on :8401, accepts chat at POST /chat
4. ✅ POST /spawn creates a Lab container that executes program.md
5. ✅ Lab reports status at GET /status, transitions ready → working → done
6. ✅ Verify runs tests on lab branch + LLM review, returns pass/fail
7. ✅ Promote merges lab/gen-N to master, creates tag, updates history
8. ✅ Rollback discards branch, marks generation failed
9. ✅ Autonomous loop runs N generations, stops on cap/budget/plateau/signal
10. ✅ Fitness scores track across generations, detect improvement and plateau

## 18. Spec Metadata

```yaml
spec-version: "001"
organism: "loom"
lineage: "genesis"
parent-spec: null
language: "ClojureScript"
loc-estimate: 2600
test-count: 236
assertion-count: 607
birth-cost-estimate: "$1-5 (Opus), $0.10-0.50 (Haiku)"
```

---

*This spec is the genotype. The code an LLM generates from it is the phenotype.
Different LLMs will produce different organisms. Natural selection begins at birth:
if it doesn't pass its own acceptance criteria, it was never alive.*
