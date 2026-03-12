# Loom — Implementation Plan

Each phase is a falsifiable experiment. If a phase fails, we reassess before continuing.
Tasks marked `(parallel)` within a phase can be worked on simultaneously.

## Phase 1: Foundation

**Goal:** Self-hosted ClojureScript running in an Apple container.

- [x] 1.0 Create `shadow-cljs.edn` and `deps.edn` for the project
- [ ] 1a. Verify `cljs.js/eval-str` works — write a test that compiles and evaluates a form at runtime using self-hosted ClojureScript on Node.js
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

## Phase 3: Supervisor

**Goal:** A host process that creates, starts, stops, and destroys containers.

- [ ] 3a. Container lifecycle — shell out to `container` CLI (create, start, stop, destroy), inject env vars, copy source into container `(parallel)`
- [ ] 3b. Version management — `versions/v001/`, `v002/` directory management, copy source, track current version `(parallel)`
- [ ] 3c. HTTP server + dashboard — `GET /`, `/logs` (SSE), `/stats`, `/versions`, single HTML page `(parallel)`
- [ ] 3d. Proposal/verdict endpoints — `POST /proposal` (receive proposal, create Lab), `POST /verdict` (promote or destroy Lab)

**Dependencies:** 3a, 3b, 3c are parallel. 3d depends on 3a + 3b.

**Falsifiable:** If Apple Containerization is too unstable (>30% failure rate on create/start), fall back to UTM via `utmctl`.

## Phase 4: Agent Loop

**Goal:** A working coding agent in the Prime container that talks to Claude.

- [ ] 4a. Claude API client — direct HTTP to `api.anthropic.com/v1/messages`, non-streaming, tool_use parsing `(parallel)`
- [ ] 4b. Tool implementations — `read-file`, `write-file`, `edit-file`, `bash` (4 of 5; `self-modify` is Phase 5) `(parallel)`
- [ ] 4c. Agentic loop — user message → Claude → tool dispatch → feed back → repeat (context management, conversation history)
- [ ] 4d. Prime HTTP server — `GET /`, `/logs`, `/stats`, `POST /chat` `(parallel with 4a/4b)`

**Dependencies:** 4a, 4b, 4d are parallel. 4c depends on 4a + 4b.

**Falsifiable:** If Claude can't reliably generate valid ClojureScript tool calls with a <1000 token prompt, expand the prompt incrementally.

## Phase 5: Self-Modify Tool

**Goal:** The agent modifies its own code, tests it in a Lab, and promotes or reverts.

- [ ] 5a. `self-modify` tool — generate modified `.cljs` source, construct Malli-validated `Proposal`, POST to Supervisor, connect to Lab eval server, send probe forms, construct `Verdict`, POST verdict
- [ ] 5b. Probe strategy — call key functions, check return values, verify no errors
- [ ] 5c. Version promotion — copy Lab source to `versions/N+1/`, restart Prime
- [ ] 5d. Logging — every proposal, probe, and verdict logged and visible in dashboard

**Dependencies:** Depends on all of Phase 3 + Phase 4. 5b/5c/5d can be developed alongside 5a.

**Falsifiable:** If the agent can't complete one successful self-modification cycle end-to-end, debug each stage independently.

## Phase 6: First Self-Modification

**Goal:** The agent rewrites one of its own functions, tests it, and promotes it.

- [ ] User prompts the agent to improve something (e.g., "make your read-file tool return line numbers")
- [ ] Agent proposes the modification
- [ ] Lab boots with modified code
- [ ] Agent probes the Lab to verify the change works
- [ ] Agent promotes the modification
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

## Not in v0

- Streaming from Claude API
- Multi-provider support
- MCP
- TUI
- Sub-agents
- Autonomous self-modification triggers (user-prompted only)
- Formal verification
- Production deployment
