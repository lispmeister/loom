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

## Phase 1: Foundation

**Goal:** Self-hosted ClojureScript running in an Apple container.

- [x] 1.0 Create `shadow-cljs.edn` and `deps.edn` for the project
- [x] 1a. Verify `cljs.js/eval-str` works — write a test that compiles and evaluates a form at runtime using self-hosted ClojureScript on Node.js (9 tests, 17 assertions passing)
- [ ] 1b. Container image — build a minimal OCI image (Node + ClojureScript source), boot with `container run`, verify eval-str works inside, measure boot time (target: <5s)

**Dependencies:** 1b blocked on 1a passing.

**Falsifiable:** If self-hosted ClojureScript boot time exceeds 10 seconds in a container, switch to pre-compiled with shadow-cljs.

## Phase 2: Form Evaluation Server

**Goal:** A Lab container that accepts and evaluates ClojureScript forms over TCP.

- [ ] 2a. Eval server — implement `loom.lab.eval-server`: TCP socket, EDN in/out, `cljs.js/eval-str` (~50 lines) `(parallel)`
- [ ] 2b. Eval client — implement `loom.shared.eval-client`: connect to server, send form, receive result `(parallel)`
- [ ] 2c. Eval round-trip test — integration test: start server, connect client, eval `(+ 1 2)` → `{:status :ok :value 3}`, test error/timeout paths

**Dependencies:** 2a and 2b are parallel. 2c depends on both. Schemas (`EvalRequest`, `EvalResponse`) already defined in `loom.shared.schemas`.

**Falsifiable:** If TCP+EDN round-trip latency exceeds 500ms per form, simplify the protocol.

**Note:** The eval server remains relevant even with full-agent Labs. Prime uses it to independently probe Lab state (acceptance criteria via eval probes).

## Phase 3: Supervisor

**Goal:** A host process that creates, starts, stops, and destroys containers on `loom-net`.

- [ ] 3a. Container lifecycle — shell out to `container` CLI (create, start, stop, destroy), attach to `loom-net`, inject env vars, clone full repo into container `(parallel)`
- [ ] 3b. Version management — git branching (`lab/gen-N`), tagging (`gen-N`), merge on promote, maintain `generations.edn` `(parallel)`
- [ ] 3c. HTTP server + dashboard — `GET /`, `/logs` (SSE), `/stats`, `/versions`, single HTML page `(parallel)`
- [ ] 3d. Lab orchestration endpoints — `POST /spawn` (create Lab from `program.md`), `POST /promote` (merge + tag + restart Prime), `POST /rollback` (discard Lab branch)
- [ ] 3e. Hard timeout enforcement — kill Lab container after 5 minutes

**Dependencies:** 3a, 3b, 3c are parallel. 3d depends on 3a + 3b. 3e depends on 3a.

**Falsifiable:** If Apple Containerization is too unstable (>30% failure rate on create/start), fall back to UTM via `utmctl`.

## Phase 4: Agent Loop

**Goal:** A working coding agent in the Prime container that talks to Claude.

- [ ] 4a. Claude API client — direct HTTP to `api.anthropic.com/v1/messages`, non-streaming, tool_use parsing `(parallel)`
- [ ] 4b. Tool implementations — `read-file`, `write-file`, `edit-file`, `bash` (4 of 5; `self-modify` is Phase 5) `(parallel)`
- [ ] 4c. Agentic loop — user message → Claude → tool dispatch → feed back → repeat (context management, conversation history)
- [ ] 4d. Prime HTTP server — `GET /`, `/logs`, `/stats`, `POST /chat` `(parallel with 4a/4b)`

**Dependencies:** 4a, 4b, 4d are parallel. 4c depends on 4a + 4b.

**Falsifiable:** If Claude can't reliably generate valid ClojureScript tool calls with a <1000 token prompt, expand the prompt incrementally.

## Phase 5: Self-Modify Tool + Lab Orchestration

**Goal:** Prime spawns a Lab with `program.md`, observes results, promotes or reverts.

- [ ] 5a. `self-modify` tool — collaborate with user to produce `program.md`, POST to Supervisor `/spawn`, poll Lab `/status`
- [ ] 5b. Acceptance evaluation — pull Lab branch, run tests independently, send eval probes, compare benchmarks (trust but verify)
- [ ] 5c. Version promotion — POST `/promote` to Supervisor (merge, tag, serialize state, restart Prime)
- [ ] 5d. Retry on failure — on Lab failure/timeout, retry with same `program.md` (v0); auto-refine `program.md` based on failure analysis (post-v0)
- [ ] 5e. Logging — every spawn, status poll, evaluation, and verdict logged and visible in dashboard

**Dependencies:** Depends on all of Phase 3 + Phase 4. 5b/5c/5d/5e can be developed alongside 5a.

**Falsifiable:** If the agent can't complete one successful self-modification cycle end-to-end, debug each stage independently.

## Phase 6: First Self-Modification

**Goal:** The agent rewrites one of its own functions, tests it, and promotes it.

- [ ] User and Prime collaborate on a `program.md` requesting an improvement (e.g., "add line numbers to read-file tool")
- [ ] Prime spawns a Lab with the task via Supervisor
- [ ] Lab implements the change, commits to `lab/gen-1`
- [ ] Lab reports done via `/status`
- [ ] Prime independently verifies acceptance criteria
- [ ] Prime promotes: merge, tag `gen-1`, restart
- [ ] Prime restarts with improved code
- [ ] The improvement is visible and functional

**This is the proof of concept.** Everything before this is scaffolding. Everything after is iteration.

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

### Deferred (non-blocking for v0)

1. **EvalResponse size** — `:value :any` has no size/depth guard. Add limits when eval server is in use. (Revisit Phase 2)
2. **Proposal schema versioning** — no version marker on fixed-point schemas. (Revisit after Phase 6)
3. **Lab concurrency cost** — how expensive is an Apple Containerization VM? Determines feasibility of parallel Labs. (Explore after v0)
4. **State schema migration** — v0 uses EDN + version tag with fresh-start fallback. Proper migration needed once state format stabilizes. (Revisit after Phase 6)

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
