# Loom — Implementation Plan

Each phase is a falsifiable experiment. If a phase fails, we reassess before continuing.
Tasks marked `(parallel)` within a phase can be worked on simultaneously.

---

## Refined Architecture (2026-03-13)

Design decisions made during spec refinement session. These supersede conflicting details in earlier phase descriptions.

### Roles

- **Prime Container (Gen 0)** — The orchestrator. Reads `program.md`, spawns Labs via the Supervisor, observes results, decides promote or rollback. The user interacts only with Prime.
- **Supervisor** — Host-side infrastructure. Manages container lifecycle (create, start, stop, destroy) and hard timeouts. Does NOT decide on pruning — that's Prime's job. Handles git operations (branching, tagging) as part of the lifecycle.
- **Lab Container** — A full clone of the agent that executes tasks from `program.md` autonomously. Runs the complete agent loop with LLM access. Has full network access. Commits work to its generation branch before executing new code.

### Execution Model

1. User refines `program.md` (task spec + acceptance criteria) in conversation with Prime. Prime generates it, user approves.
2. Prime constructs the complete container environment: full repo clone on a `lab/gen-N` branch with `program.md` included.
3. Supervisor creates the Lab container on the `loom-net` network. Boot auto-starts the research task — no explicit "go" signal.
4. Prime polls `http://lab-gen-N:PORT/status` (with connect-retry for readiness) to monitor progress.
5. Supervisor enforces a hard timeout of 5 minutes per Lab run.
6. When Lab reports done (or times out), Prime independently verifies: pulls the Lab's branch, runs tests, sends eval probes, checks benchmarks — per `program.md` criteria. (Trust but verify: Lab runs its own tests AND Prime checks independently.)
7. **Promote:** Merge `lab/gen-N` into `main`, tag `gen-N`, serialize Prime state, restart Prime with new code.
8. **Rollback:** Discard `lab/gen-N`. On failure, Prime retries with same `program.md` (v0). Auto-refine (Prime analyzes failure and improves `program.md` before retry) planned for post-v0.

### Acceptance Criteria (heterogeneous)

The `program.md` declares which criteria apply for each task:

- **Tests** — for hard requirements (e.g., "these LLM providers must be accessible")
- **Eval probes** — Prime independently verifies Lab behavior (e.g., "this API returns valid JSON")
- **Benchmarks** — for performance/optimization tasks (e.g., "latency must be < 200ms")

### `program.md` Contract

- The sole directive for a Lab run — analogous to Karpathy's autoresearch
- User and Prime collaborate to write and refine it before each Lab spawn
- Contains: task spec, acceptance criteria, success conditions
- `program.md` is the prompt: Lab reads it at boot and works autonomously from there

### Networking

- Supervisor creates a `loom-net` custom network via `container network create loom-net`
- All containers (Prime + Labs) attach to `loom-net` via `--network loom-net`
- DNS resolution built-in: Prime reaches Lab by container name (e.g., `lab-gen-1`)
- No IP discovery or port mapping needed
- Lab readiness: Prime polls `/status` with connect-retry until Lab responds

### Default Ports

| Component | Port | Purpose |
|-----------|------|---------|
| Supervisor | 8400 | HTTP dashboard, `/spawn`, `/promote`, `/rollback` |
| Prime | 8401 | HTTP dashboard, `/chat`, `/logs`, `/stats` |
| Lab | 8402 | `/status` endpoint, eval server (TCP) |

### Git Strategy

- `main` — always tracks current Prime code (known-good)
- `lab/gen-N` — branch created when Lab N is spawned, Lab commits here
- On promote: merge `lab/gen-N` into `main`, tag `gen-N`, delete lab branch
- On rollback: discard `lab/gen-N`, Prime stays on `main`
- Tags provide unlimited rollback depth

### Lineage Tracking

Supervisor maintains `generations.edn` in its working directory, versioned in git. Per generation:

- Generation number
- Parent generation
- Branch name
- `program.md` hash
- Outcome (promoted / failed / timed out)
- Timestamps (created, completed)
- Container ID

### State Continuity Across Generations

Three problems to solve:

1. **Serialize state** — snapshot the outgoing Prime's state before promotion
2. **Load state** — hydrate the incoming Prime from the snapshot
3. **Schema evolution** — handle format differences between generations

**v0 approach:** State as simple EDN with a version tag. If schema doesn't match, start fresh (git tag preserves the old state for inspection). Proper schema migration logic needed as soon as state format stabilizes.

### Lab HTTP API

- `GET /status` — structured JSON: running/done/failed, test results, progress (v0)
- REPL (eval server from Phase 2) — for deep inspection and debugging (post-v0 priority)

---

## Parallel Tracks

Tasks from different phases that share no dependencies can be developed in parallel across five tracks. They converge at Phase 5.

```
Track A: Container infra     1b → 3a.1 + 3a.2 → 3a.3 → 3e
Track B: Git + lineage        3b.1 + 3b.2
Track C: Eval protocol        2a + 2b → 2c
Track D: Agent core           4a.1 → 4a.2 + 4b.1–4b.4 → 4c
Track E: HTTP layer            3c.1 → 3c.2 + 3d + 4d
                                         ↘ all converge → Phase 5 → Phase 6
```

---

## Phase 1: Foundation

**Goal:** Self-hosted ClojureScript running in an Apple container.

- [x] 1.0 Create `shadow-cljs.edn` and `deps.edn` for the project
- [x] 1a. Verify `cljs.js/eval-str` works — write a test that compiles and evaluates a form at runtime using self-hosted ClojureScript on Node.js (9 tests, 17 assertions passing)
- [x] 1b. Container image — `loom-lab:latest` OCI image (node:22-slim + release lab.js + cljs.core analysis cache). Boot time: ~730ms to TCP ready, ~1.8s to first eval. Verified eval-str works inside container. **(Track A)**

**Dependencies:** 1b blocked on 1a passing.

**Falsifiable:** If self-hosted ClojureScript boot time exceeds 10 seconds in a container, switch to pre-compiled with shadow-cljs.

## Phase 2: Form Evaluation Server

**Goal:** A Lab container that accepts and evaluates ClojureScript forms over TCP.

- [x] 2a. Eval server — implemented in `loom.lab.eval-server`: TCP socket, EDN in/out, `cljs.js/eval-str` (~50 lines) `(parallel)` **(Track C)**
- [x] 2b. Eval client — implemented in `loom.shared.eval-client`: connect to server, send form, receive result `(parallel)` **(Track C)**
- [x] 2c. Eval round-trip test — `eval_server_test.cljs`: arithmetic, invalid form, malformed EDN, data structures **(Track C)**

**Dependencies:** 2a and 2b are parallel. 2c depends on both. Schemas (`EvalRequest`, `EvalResponse`) already defined in `loom.shared.schemas`.

**Falsifiable:** If TCP+EDN round-trip latency exceeds 500ms per form, simplify the protocol.

**Note:** The eval server remains relevant even with full-agent Labs. Prime uses it to independently probe Lab state (acceptance criteria via eval probes).

## Phase 3: Supervisor

**Goal:** A host process that creates, starts, stops, and destroys containers on `loom-net`.

### 3a. Container lifecycle

- [x] 3a.1 — `container` CLI wrapper: pure functions that shell out to `create`, `start`, `stop`, `destroy`, parse output, return structured results `(parallel)` **(Track A)**
- [x] 3a.2 — Network setup: `network-ensure("loom-net")` called on Supervisor startup in `supervisor/core.cljs`, containers attached via `--network loom-net` in `lab/spawn-lab` `(parallel)` **(Track A)**
- [x] 3a.3 — Repo cloning + branch setup: `git/clone-repo` + `git/commit` in `supervisor/git.cljs`, `lab/setup-lab-repo` (clone → config → branch → program.md → commit), `lab/spawn-lab` (full lifecycle with container + volume mount). 4 tests in `lab_setup_test.cljs`. **(Track A)**

### 3b. Version management

- [x] 3b.1 — Git operations: create branch, merge, tag, delete branch, clone-repo, commit — pure functions wrapping `git` CLI in `supervisor/git.cljs` `(parallel)` **(Track B)**
- [x] 3b.2 — `generations.edn`: read/write/append generation records in `supervisor/generations.cljs` `(parallel)` **(Track B)**

### 3c. HTTP server + dashboard

- [x] 3c.1 — HTTP server skeleton: routing, JSON responses, SSE in `shared/http.cljs` `(parallel)` **(Track E)**
- [x] 3c.2 — Dashboard HTML: single page in `supervisor/http.cljs` showing status, generation history, links `(depends on 3c.1)` **(Track E)**

### 3d. Lab orchestration endpoints

- [x] 3d — `POST /spawn` (calls `lab/spawn-lab`), `POST /promote` (merge + tag + delete branch), `POST /rollback` (discard Lab branch). All wired in `supervisor/http.cljs` with SSE logging. 8 tests in `supervisor_http_test.cljs`. **(Track E)**

### 3e. Timeout enforcement

- [x] 3e — 5-min timeout via `js/setTimeout` in `lab/spawn-lab`, callback updates `generations.edn` to `:timeout` and calls `cleanup-lab`. Supervisor tracks timeout-ids in `lab-timeouts` atom, cancels on promote/rollback. **(Track A)**

**Falsifiable:** If Apple Containerization is too unstable (>30% failure rate on create/start), fall back to UTM via `utmctl`.

## Phase 4: Agent Loop

**Goal:** A working coding agent in the Prime container that talks to Claude.

### 4a. Claude API client

- [x] 4a.1 — HTTP client in `loom.agent.claude`: POST to Messages API, parse JSON, handle errors. Tested in `claude_test.cljs`. `(parallel)` **(Track D)**
- [x] 4a.2 — Tool-use parsing in `loom.agent.dispatch`: extract tool calls, dispatch to registry. Tested in `tools_test.cljs`. `(depends on 4a.1)` **(Track D)**

### 4b. Tool implementations

- [x] 4b.1 — `read-file` in `loom.agent.tools`, tested `(parallel)` **(Track D)**
- [x] 4b.2 — `write-file` in `loom.agent.tools`, tested `(parallel)` **(Track D)**
- [x] 4b.3 — `edit-file` in `loom.agent.tools`, tested `(parallel)` **(Track D)**
- [x] 4b.4 — `bash` in `loom.agent.tools`, 30s timeout, 10MB maxBuffer, tested `(parallel)` **(Track D)**

### 4c. Agentic loop

- [x] 4c — Agentic loop in `loom.agent.loop`: user message → Claude → tool dispatch → feed results back → repeat. Max 25 iterations, on-event callbacks for SSE streaming, API error handling. Wired to Prime HTTP `/chat` endpoint. **(Track D)**

### 4d. Prime HTTP server

- [x] 4d — `GET /`, `/logs` (SSE), `/stats`, `POST /chat` in `loom.agent.http`. Tested in `agent_http_test.cljs`. `(parallel with 4a/4b)` **(Track E)**

**Falsifiable:** If Claude can't reliably generate valid ClojureScript tool calls with a <1000 token prompt, expand the prompt incrementally.

## Phase 5: Self-Modify Tool + Lab Orchestration

**Goal:** Prime spawns a Lab with `program.md`, observes results, promotes or reverts.

**Converges all tracks.** Depends on Phase 3 (all) + Phase 4 (all).

### 5a. Self-modify tool

- [x] 5a.1 — `program.md` generation: handled by Claude in conversation, Prime's system prompt includes self-modification workflow guidance `(parallel)`
- [x] 5a.2 — Spawn request: `spawn_lab` tool in `loom.agent.self-modify`, POSTs to Supervisor `/spawn` via `shared/http-client` `(parallel with 5a.1)`
- [x] 5a.3 — Status polling: `check_lab_status` tool with connect-retry (5 attempts, 2s apart) against Lab `/status` `(parallel with 5a.1)`

### 5b–5e. Evaluation and promotion

- [x] 5b. Acceptance evaluation — `verify_generation` tool in `self-modify.cljs`: checks out Lab branch, compiles and runs tests, reports pass/fail, returns to master. Prime calls this after Lab reports done, before promoting.
- [x] 5c. Version promotion — `promote_generation` tool POSTs to Supervisor `/promote` (merge, tag, delete branch). State serialization deferred.
- [x] 5d. Retry on failure — system prompt guides Prime to retry `spawn_lab` with same `program.md` on failure/timeout (up to 2 retries). Auto-refine deferred to post-v0.
- [x] 5e. Logging — spawn/timeout/promote/rollback events emitted via SSE. Agent on-event callbacks stream to `/logs`.

### 5f. Lab worker (autonomous agent in container)

The Lab container currently only runs the eval server. For self-modification, Labs need to be autonomous agents that read `program.md` and execute the task end-to-end.

- [x] 5f.1 — Lab worker entry point: `src/loom/lab/worker.cljs`. On boot: read `/workspace/program.md`, create agent, run the agentic loop, expose `GET /status`, commit results on completion.
- [x] 5f.2 — Shadow-cljs build target: `:lab-worker` in `shadow-cljs.edn`, compiles to `out/lab-worker.js` (default optimizations — no self-hosted eval needed).
- [x] 5f.3 — Spawn integration: `lab/spawn-lab` copies `out/lab-worker.js` into workspace, passes `["node" "/workspace/lab-worker.js"]` as container command, injects `ANTHROPIC_API_KEY` + `PORT` from Supervisor env.
- [x] 5f.4 — Status endpoint: `GET /status` returns `{:status "starting"|"running"|"done"|"failed", :progress <string>, :error <string>}`.

**Dependencies:** 5f depends on Phase 4 (agent loop, tools, Claude client). 5f.1 and 5f.2 are parallel. 5f.3 depends on 5f.1 + 5f.2.

**Falsifiable:** If the Lab worker can't complete a simple file edit task inside a container within 5 minutes, debug boot time and API connectivity independently.

**Dependencies:** 5b/5c/5d/5e can be developed alongside 5a.

**Falsifiable:** If the agent can't complete one successful self-modification cycle end-to-end, debug each stage independently.

## Phase 6: First Self-Modification

**Goal:** The agent rewrites one of its own functions, tests it, and promotes it.

- [ ] User and Prime collaborate on a `program.md` requesting an improvement (e.g., "add line numbers to read-file tool")
- [ ] Prime spawns a Lab with the task via Supervisor
- [ ] Lab boots, reads `program.md`, starts autonomous agent loop
- [ ] Lab implements the change, commits to `lab/gen-1`
- [ ] Lab reports done via `/status`
- [ ] Prime independently verifies acceptance criteria
- [ ] Prime promotes: merge, tag `gen-1`, restart
- [ ] Prime restarts with improved code
- [ ] The improvement is visible and functional

**This is the proof of concept.** Everything before this is scaffolding. Everything after is iteration.

---

## Immediate Backlog

Items to address before or shortly after a clean Phase 6 run.

- [x] **Rate-limit-aware retry** — In `claude.cljs`, on 429 response parse `retry-after` header, wait that duration, then retry (max 3 retries). Committed.
- [ ] **Keep program.md focused** — Document guidelines for writing effective program.md: single focused task, explicit file paths, concrete acceptance criteria, avoid open-ended exploration.
- [ ] **Request API rate limit increase** — Once usage patterns stabilize, request higher limits from Anthropic for the project API key.
- [ ] **Batch API for non-interactive work** — Evaluate Anthropic Batch API (50% cost reduction, higher limits, 24h turnaround) for Lab tasks that don't need real-time interaction.
- [ ] **GPG-sign commits from Lab containers** — Figure out how to forward or provision GPG signing inside Lab containers so that Lab commits are signed. Currently disabled via `commit.gpgsign false` in lab repo config.

---

## Milestone: Install ByteRover (after Phase 4)

**When:** After the agent loop is running and generating real session history across multiple development sessions.

**Why not now:** The `.claude/memory/` files and `CLAUDE.md` carry everything needed for early phases. ByteRover's value is long-term knowledge retention across dozens of sessions — premature before the project generates that volume of decisions and patterns.

**What to do:**
- [ ] Install: `npm install -g byterover-cli` or `curl -fsSL https://byterover.dev/install.sh | sh`
- [ ] Connect: `brv connectors install "Claude Code"`
- [ ] Verify `.brv/context-tree/` is created and queryable
- [ ] Evaluate whether to add automatic memory flush (context window threshold trigger)
- [ ] Evaluate daily knowledge mining cron job

**Docs:** https://docs.byterover.dev/

---

## Open Questions

### Resolved (2026-03-13)

- **Rollback depth** → git tags on every generation, unlimited rollback
- **Prime restart semantics** → Supervisor merges, serializes state, kills and relaunches Prime
- **Container networking** → `loom-net` custom network, DNS resolution by container name
- **Lab readiness** → Prime polls `/status` with connect-retry
- **`program.md` contract** → collaboratively refined by user+Prime, sole Lab directive, boot = start
- **Lab timeout** → 5 minute hard timeout enforced by Supervisor
- **Lab observability** → `/status` endpoint for v0, REPL (eval server) post-v0
- **Lab failure modes** → retry for v0, auto-refine post-v0; Lab commits before executing for audit trail
- **Lineage metadata** → `generations.edn` in Supervisor dir, versioned in git
- **Container filesystem** → full repo clone, full network access
- **Prime↔Lab protocol** → boot = start, `/status` for monitoring, Prime verifies independently (option C)
- **Acceptance evaluation** → trust but verify: Lab self-tests AND Prime checks independently

### Resolved (2026-03-14)

- **Container outbound networking** → Apple Containerization provides outbound internet via vmnet by default. Tailscale VPN breaks vmnet routing — must be disconnected before running Labs. After disconnecting, restart with `container system stop && container system start`.
- **Lab worker** → Labs run `out/lab-worker.js` (release build, self-contained). Worker reads `program.md`, runs agent loop, exposes `GET /status`, commits results. Git user set to `lab@loom.local` with GPG signing disabled.
- **API key for Labs** → Supervisor injects `ANTHROPIC_API_KEY` from its own env into Lab containers.

### Resolved (2026-03-15)

- **Labs cannot self-test** → Shadow-cljs compilation takes ~25s inside the container VM, which combined with the 5-min timeout leaves insufficient time for actual work. Labs must NOT run `npm test` or any compilation commands. Prime's `verify_generation` tool runs tests host-side after the Lab reports done. The Lab worker system prompt and `templates/program.md.example` enforce this constraint.
- **Lab artifact leak** → `git add -A` in Lab commits `lab-worker.js` and `program.md` to the lab branch, which merge into main. Fixed by writing a `.gitignore` (excluding both files) into the workspace during `setup-lab-repo`, before the Lab starts.
- **Port collision on spawn** → Fixed host port `"8402:8402"` causes failures on sequential spawns. Changed to `"0:8402"` for dynamic host port assignment; `published-port` reads the actual assigned port.
- **Lab workspace cleanup** → Promote and rollback handlers now auto-clean old Lab workspace directories, keeping the last 3 for debugging.

### Deferred (non-blocking for v0)

1. **EvalResponse size** — `:value :any` has no size/depth guard. Add limits when eval server is in use. (Revisit Phase 2)
2. **Proposal schema versioning** — no version marker on fixed-point schemas. (Revisit after Phase 6)
3. **Lab concurrency cost** — how expensive is an Apple Containerization VM? Determines feasibility of parallel Labs. (Explore after v0)
4. **State schema migration** — v0 uses EDN + version tag with fresh-start fallback. Proper migration needed once state format stabilizes. (Revisit after Phase 6)
5. **API rate limits** — New Anthropic API accounts have low rate limits (30k input tokens/min for Sonnet). The Lab agent hit this mid-task during Phase 6 testing. Strategy to mitigate:
   - **Use Haiku for Lab tasks** — much cheaper, higher rate limits, sufficient for straightforward code edits. Set `LOOM_MODEL=claude-haiku-4-5-20251001` for Labs.
   - **Keep program.md focused** — smaller tasks = fewer loop iterations = fewer API calls. Avoid open-ended exploration in v0.
   - **Rate-limit-aware retry** — on 429, parse `retry-after` header, wait, then continue the loop instead of aborting. (Implement in `claude.cljs`.)
   - **Request rate limit increase** — once usage patterns stabilize, request higher limits from Anthropic.
   - **Batch tier** — for non-interactive Lab work, consider Anthropic's Batch API (50% cost reduction, higher limits, 24h turnaround). Not suitable for interactive loops but good for bulk tasks.

## Not in v0

- Streaming from Claude API
- Multi-provider support
- MCP
- TUI
- Sub-agents
- Parallel Lab exploration (tree search)
- Generational chaining (Labs spawning children)
- Autonomous self-modification triggers (user-prompted only)
- Formal verification
- Production deployment
- Live user↔Lab interaction (Labs are autonomous for now)
- Auto-refine `program.md` on failure (retry only for v0)
- Lab REPL for deep inspection (eval server available but not wired to Prime)
