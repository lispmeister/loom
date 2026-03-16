# Loom — Architecture & Design Decisions

## Goal

A self-modifying coding agent that can rewrite its own code, test modifications in isolated Lab containers, and promote successful changes — all in pure ClojureScript on Node.js.

**Status:** MVP phase complete. Pipeline proven stable with 5/5 successful generations (gen-13 through gen-17, 2026-03-16) — zero failures, zero timeouts, all branches propagated. Earlier hardening (2026-03-16) fixed 5 critical bugs. Next phase: close the recursive loop (reflect step). Tasks tracked in [beads](https://github.com/lispmeister/beads) (`beads list`). Architecture reviews in [`architecture-reviews/`](architecture-reviews/).

---

## Architecture

### Roles

- **Prime Container (Gen 0)** — The orchestrator. Reads `program.md`, spawns Labs via the Supervisor, observes results, decides promote or rollback. The user interacts only with Prime.
- **Supervisor** — Host-side infrastructure. Manages container lifecycle (create, start, stop, destroy) and hard timeouts. Does NOT decide on pruning — that's Prime's job. Handles git operations (branching, tagging) as part of the lifecycle.
- **Lab Container** — A full clone of the agent that executes tasks from `program.md` autonomously. Runs the complete agent loop with LLM access. Has full network access. Commits work to its generation branch before executing new code.

### Execution Model

1. User refines `program.md` (task spec + acceptance criteria) in conversation with Prime. Prime generates it, user approves.
2. Prime constructs the complete container environment: full repo clone on a `lab/gen-N` branch with `program.md` included.
3. Supervisor creates the Lab container on the `loom-net` network. Boot auto-starts the research task — no explicit "go" signal.
4. Prime polls `http://lab-gen-N:PORT/status` (with connect-retry for readiness) to monitor progress.
5. Supervisor enforces a hard timeout per Lab run (`LOOM_LAB_TIMEOUT_MS`, default 10 min).
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

## Known Constraints

- **Labs cannot self-test** — Shadow-cljs compilation takes ~25s inside the container VM, leaving insufficient time within the 5-min timeout. Labs must NOT run `npm test` or any compilation commands. Prime's `verify_generation` tool runs tests host-side after the Lab reports done.
- **Tailscale VPN breaks containers** — Apple Containerization vmnet routing fails with Tailscale active. Disconnect before running Labs, then `container system stop && container system start`.
- **Lab artifacts excluded via .gitignore** — `lab-worker.js` and `program.md` are written to workspace `.gitignore` during `setup-lab-repo` to prevent merge into main.
- **Deterministic host ports** — Lab containers publish on port `18400 + gen-num` (e.g., gen-5 → `18405:8402`) to avoid collisions and allow direct debugging.

## Critical Assessment (2026-03-16)

### What Works

The infrastructure is proven. Spawn → execute → detect → fetch → cleanup runs reliably (5/5 stable generations, gen-13 through gen-17, zero failures). The pipeline handles timeouts, port allocation, API key injection, branch propagation, and container lifecycle correctly.

### What's Missing

1. **No generation has modified agent code.** Every successful program.md has been a trivial file operation (add a comment, create a text file, count source files). Gen-1 (add line numbers to read_file) is the only one that touched agent functionality, and that was manually orchestrated. We haven't proven that a Lab can make a meaningful change to the agent and have Prime verify and promote it.

2. **Prime has no agency.** It's a chatbot with tools. The user writes program.md, tells Prime to execute it, Prime mechanically spawns/verifies/promotes. There's no decision-making, no prioritization, no self-assessment.

3. **Verification is shallow.** `verify_generation` runs `npm test` — that's it. No eval probes, no benchmarks, no code quality checks. For trivial tasks this is fine. For actual self-modification (rewrite the agentic loop) this is dangerously insufficient. A Lab could introduce a subtle regression that passes all 94 tests.

4. **No fitness function.** Self-improvement requires measuring improvement. We track timing and outcome (done/failed/timeout) but not: test count, code coverage, token efficiency, tool call counts. Without quantitative metrics across generations, "improvement" is unmeasurable.

5. **Reports are too thin.** `gen-N-report.json` has 7 fields. No token usage, no test results, no diff summary, no fitness score. The reflect step needs rich data to reason about what to do next.

### Guidance Sources for the Reflect Step

Prime needs input on *what's worth improving*. Options, in order of implementation priority:

| Source | How | Status |
|---|---|---|
| **User priorities file** | `priorities.md` in repo, Prime reads before reflect | Not implemented |
| **Beads integration** | Prime reads `beads list` output, picks highest P1/P2 | Beads exist, not wired to Prime |
| **LLM self-review** | Prime reads its own source, asks "what's weakest?" | Not implemented, defer until fitness function exists |
| **Conversation context** | User feedback from chat sessions persisted | Not implemented |

Start with user priorities + beads, graduate to LLM self-review once fitness function is trusted.

### Progress Documentation Gaps

- **Per-generation metrics** — reports need token counts, test results, diff stats
- **Fitness log** — append-only file tracking key metrics across generations, plottable over time
- **Diff summaries** — what actually changed, stored alongside the report

## Next Milestone: Closing the Recursive Loop

The MVP proves the pipeline works (human writes program.md → Lab executes → verify → promote/rollback). The next step is making it genuinely recursive: **Prime autonomously proposes and executes its own next improvement.**

### Prerequisites Before Implementing Reflect

These must be done first — without them the reflect loop won't have enough information to make good decisions:

1. **Enrich generation reports** — add token counts, test pass/fail counts, diff stats, duration breakdown
2. **Define the fitness function** — what metrics must improve? (test count, code coverage, token efficiency, or user-defined)
3. **Create `priorities.md`** — user-authored file that Prime reads during reflect to know what to work on

### The Reflect Step

After every promote or rollback, Prime enters a `reflect` phase:

1. Analyze the generation report (what changed, outcome, timing, metrics)
2. Read `priorities.md` for user-directed goals
3. Review the current codebase state (test results, architecture gaps, open beads)
4. Generate the next `program.md` — the single smallest change that most improves the target metric
5. Spawn a new Lab and continue the loop

### Stopping Conditions

The loop needs safety valves:
- Generation cap (`LOOM_MAX_GENERATIONS`)
- Token budget
- Fitness plateau (no measurable improvement over N generations)
- Human interrupt (SIGINT gracefully stops the loop)

### What This Enables

- **The compelling demo:** "I pointed it at itself and walked away. Here's what it improved."
- **Fitness tracking:** Each generation produces quantitative metrics (test count, code size, token usage, tool calls). Tracked in generation reports and fitness log.
- **Auto-refine on failure:** If a generation fails verification, reflect analyzes the failure and produces an improved program.md before retrying.

**Pipeline prerequisite met:** MVP pipeline stable — 5/5 generations (gen-13 through gen-17) completed successfully with zero failures.

### Post-Recursion: Switch to Claude Code Token

Once the reflect loop is working, migrate from direct Anthropic API calls to using the Claude Code token. This consolidates billing, removes the need for a separate `ANTHROPIC_API_KEY`, and aligns with the tool's intended usage model. Implement after recursion is proven stable.

---

## Not in v0

- Streaming from Claude API
- Multi-provider support
- MCP
- TUI
- Sub-agents
- Parallel Lab exploration (tree search)
- Generational chaining (Labs spawning children)
- Formal verification
- Production deployment
- Live user↔Lab interaction (Labs are autonomous for now)
- Lab REPL for deep inspection (eval server available but not wired to Prime)
