# Loom — Architecture & Design Decisions

## Goal

A self-modifying coding agent that can rewrite its own code, test modifications in isolated Lab containers, and promote successful changes — all in pure ClojureScript on Node.js.

**Status:** First autonomous promotion achieved (gen-72, Opus 4.6). Full pipeline validated end-to-end: reflect → spawn → verify (tests + LLM review) → promote. 17 autonomous generations across 3 models (Haiku, Minimax M2.5, Opus 4.6), 1 promoted. Multi-provider LLM support operational (Anthropic + Minimax). 236 tests, 607 assertions. Tasks tracked in [beads](https://github.com/lispmeister/beads) (`bd ready`). Architecture reviews in [`architecture-reviews/`](architecture-reviews/).

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
5. Supervisor enforces a hard timeout per Lab run (`LOOM_LAB_TIMEOUT_MS`, default 5 min).
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
- **Lab artifacts in .gitignore** — `lab-worker.js` is added to workspace `.gitignore` during `setup-lab-repo` (with dedup check). `program.md` is NOT gitignored — it's tracked in Lab branch history so verify can inspect it.
- **Deterministic host ports** — Lab containers publish on port `18400 + gen-num` (e.g., gen-5 → `18405:8402`) to avoid collisions and allow direct debugging.

## Critical Assessment (2026-03-16)

### What Works

The infrastructure is proven. Spawn → execute → detect → fetch → cleanup runs reliably (5/5 stable generations, gen-13 through gen-17, zero failures). The pipeline handles timeouts, port allocation, API key injection, branch propagation, and container lifecycle correctly.

### What's Missing

1. ~~**No generation has modified agent code.**~~ **Resolved (gen-72).** Opus 4.6 autonomously modified `self_modify.cljs` (made 2 private fns public) and created a 63-line test file with 18 assertions. Verified (236 tests, 607 assertions, 0 failures) + LLM review approved → promoted to master. First proof that the pipeline can autonomously modify and improve the agent itself.

2. **Prime has no agency.** It's a chatbot with tools. The user writes program.md, tells Prime to execute it, Prime mechanically spawns/verifies/promotes. There's no decision-making, no prioritization, no self-assessment.

3. ~~**Verification is shallow.**~~ **Two-stage verification implemented.** `verify_generation` now runs `npm test` AND an LLM code review (diff analysis with APPROVED/REJECTED verdict). Both must pass for promotion. Still no eval probes or benchmarks, but the LLM review catches regressions that tests miss.

4. ~~**No fitness function.**~~ **Fitness function defined** (see below). Metrics: test health, token efficiency, diff impact.

5. ~~**Reports are too thin.**~~ **Reports enriched** as of gen-18. Now includes: token-usage (input/output), test results (tests-run, assertions, failures, errors, passed?), diff stats (files-changed, insertions, deletions).

### Guidance Sources for the Reflect Step

Prime needs input on *what's worth improving*. Options, in order of implementation priority:

| Source | How | Status |
|---|---|---|
| **User priorities file** | `priorities.md` in repo, Prime reads before reflect | Not implemented |
| **Beads integration** | Prime reads `beads list` output, picks highest P1/P2 | Beads exist, not wired to Prime |
| **LLM self-review** | Prime reads its own source, asks "what's weakest?" | Not implemented, defer until fitness function exists |
| **Conversation context** | User feedback from chat sessions persisted | Not implemented |

Start with user priorities + beads, graduate to LLM self-review once fitness function is trusted.

### Progress Documentation

- ~~**Per-generation metrics**~~ — Done: reports include token counts, test results, diff stats (as of gen-18)
- ~~**Fitness log**~~ — Done: append-only JSONL (`tmp/fitness-log.jsonl`) tracking fitness score, token usage (gen + reflect + review), failure categorization, cycle timing (reflect/spawn/verify phases), and program summary per generation
- ~~**Diff summaries**~~ — Done: diff stats in reports

## Next Milestone: Closing the Recursive Loop

The MVP proves the pipeline works (human writes program.md → Lab executes → verify → promote/rollback). The next step is making it genuinely recursive: **Prime autonomously proposes and executes its own next improvement.**

### Prerequisites Before Implementing Reflect

1. ~~**Enrich generation reports**~~ — Done (gen-18+). Reports now include token-usage, test-results, diff-stats, tool-stats.
2. ~~**Define the fitness function**~~ — Done. See below.
3. **Create `priorities.md`** — user-authored file that Prime reads during reflect to know what to work on (not yet created)

**Note:** The reflect step is implemented (`agent/reflect.cljs`) and reads generation history, fitness scores, and codebase source for introspection. It does not yet read a `priorities.md` file.

### Fitness Function

A generation is "better" if it improves the target metric without regressing safety constraints.

**Safety constraints (must not regress):**
- All tests pass (`failures == 0 && errors == 0`)
- Test count does not decrease (`tests-run >= previous tests-run`)

**Improvement metrics (higher = better):**

| Metric | How measured | Why it matters |
|---|---|---|
| Test count | `tests-run` from verify_generation | More tests = more verified behavior |
| Assertion count | `assertions` from verify_generation | Depth of testing |
| Token efficiency | `(input + output) / generation` | Lower cost per improvement cycle |

**Fitness score** (v0, simple weighted sum):
```
score = (tests-run * 10) + (assertions * 1) - (total-tokens / 1000)
```

A generation is promotable if: safety constraints hold AND score >= previous score (or user overrides via priorities.md). The score formula is intentionally simple — we'll refine it once we have data from 10+ reflect-loop generations.

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

**Pipeline prerequisite met:** MVP pipeline stable — 5/5 stability generations (gen-13–17), then 17 autonomous generations across 3 models with first promotion (gen-72).

### Post-Recursion: Switch to Claude Code Token

Once the reflect loop is working, migrate from direct Anthropic API calls to using the Claude Code token. This consolidates billing, removes the need for a separate `ANTHROPIC_API_KEY`, and aligns with the tool's intended usage model. Implement after recursion is proven stable.

---

## Developer Workflow Friction

### The Problem

The codebase itself doesn't shell out to Bash for core operations — the supervisor calls the `container` CLI via `execFile`, HTTP is native Node, file I/O uses `node:fs`. The friction is in **manual testing and dev workflow**:

1. **Running one-off CLJS functions from the terminal** — shadow-cljs compiles to node-script targets that auto-execute `main`. There's no way to call `reflect-and-propose` or any individual tool without starting the full agent HTTP server as a side effect.
2. **Polling loops in zsh** — zsh reserves `$status` (alias for `$?`), shell quoting issues, empty output from dead containers causes silent infinite loops.
3. **Env sourcing before every command** — `set -a && source .env && set +a` required before any supervisor or agent invocation.

### Solutions (in implementation order)

**Option 3: CLI entry point for individual tools (do first)**
Add a CLI mode to the agent build: `node out/agent.js reflect`, `node out/agent.js spawn "program.md"`, `node out/agent.js poll 30`. Detects argv to run a single tool function and exit, bypassing the HTTP server. No new dependencies, directly addresses the "can't call one function" problem.

**Option 1: Babashka task runner for dev commands (deferred)**
Add `bb.edn` with tasks like `bb reflect`, `bb spawn`, `bb poll`, `bb supervisor`. Babashka as a dev-time convenience layer only — NOT a production component. The codebase stays pure ClojureScript/Node. Adds a dev dependency but is more ergonomic than raw shell for multi-step workflows.

**Option 2: REPL-friendly dev build target (deferred)**
Add a `:dev` or `:node-repl` shadow-cljs build target for interactive CLJS evaluation. Standard Clojure workflow — connect Calva or nREPL, call any function directly. Requires a running REPL session.

---

## Session Learnings (2026-03-16)

### Lab Read-Only Bug (loom-bjd) — Root Cause & Fix

Labs consistently failed with "Agent completed but made no file changes" across both Minimax M2.5 and Haiku (gens 30–38). Investigation via `container logs` revealed the Lab spent all 25 iterations reading files and never writing.

**Root cause (two factors):**

1. **Tool pollution.** Labs received all 9 tools including `spawn_lab`, `verify_generation`, `reflect_and_propose` — tools they can't use. The LLM saw these tools, got confused about its role, and defaulted to passive analysis.
2. **Passive system prompt.** "Read files before editing. Make minimal, focused changes." encouraged over-reading.

**Fix:**
- Per-agent tool filtering: `create-agent` now accepts `:tool-definitions` and `:tool-registry`. Labs receive only 4 base tools (read_file, write_file, edit_file, bash).
- Lab system prompt rewritten: emphasizes "LIMITED number of tool calls", "START WRITING immediately", explicit 4-step workflow.
- Max iterations raised from 25 to 40 (configurable via `LOOM_MAX_ITERATIONS`).

**Result:** Gen-39 succeeded immediately after the fix — first success after 5+ consecutive failures.

### Reflect Step Validation (Sub-phase B)

Three manual reflect cycles confirmed:
- Priorities from `priorities.md` are correctly incorporated into proposals
- After a rollback, reflect narrows scope and changes approach
- Bootstrap case (no history) produces reasonable first task

### Autonomous Loop (Sub-phase C)

Implemented and unit-tested (12 tests). Stopping conditions: generation cap, token budget, plateau detection, SIGINT. Fitness log persists as append-only JSONL. End-to-end validation (C.4) still pending.

### Dev Workflow

CLI entry point (`node out/agent.js <command>`) eliminated the main friction point — individual tools callable without starting HTTP server. Babashka and REPL dev target deferred.

---

## Session Learnings (2026-03-17)

### First Autonomous Promotion (gen-72)

**17 generations, 4 autonomous runs, 3 models, 1 promotion.**

| Run | Model | Generations | Promoted | Key Finding |
|-----|-------|-------------|----------|-------------|
| 1 | Haiku | 5 (gen-43–47) | 0 | Discovered branch fetch race, local changes blocking checkout |
| 2 | Haiku | 5 (gen-53–58) | 0 | gen-58 tests passed but LLM reviewer too strict |
| 3 | Anthropic | 3 (gen-59–61) | 0 | API credits exhausted (grant propagation delay) |
| 4 | Minimax M2.5 | 3 (gen-65–68) | 0 | M2.5 read-only behavior — reads files but never writes |
| 5 | Opus 4.6 | 1 (gen-72) | 1 | Clean success in 56s, first agent code modification |

### Infrastructure Bugs Fixed During Autonomous Runs

1. **Branch fetch race condition** — Agent polls Lab directly, gets "done" before supervisor's async `git/fetch-branch` completes. Verify fails with "pathspec did not match". Fix: retry loop (3 attempts, 2s delay) in `checkout-and-test`.
2. **Local changes blocking checkout** — Uncommitted edits prevent `git checkout lab/gen-N`. Fix: `git stash --include-untracked` before checkout, `git stash pop` after.
3. **.gitignore duplication** — Every spawn appended entries even if present. Fix: regex check before appending.
4. **program.md gitignored** — When .gitignore already had entries and nothing changed, `git commit` failed with "nothing to commit". Fix: removed program.md from .gitignore (it should be tracked in branch history).
5. **LLM reviewer too strict** — Rejected valid changes due to .gitignore diffs and container path artifacts. Fix: updated review prompt to ignore Lab environment artifacts.
6. **Timeout handler missing branch fetch** — Supervisor only fetched Lab branch on "done", not timeout. Fix: fetch in both `on-lab-done` (all outcomes) and `on-timeout`.

### Multi-Provider LLM Support

Implemented split-provider configuration via `.env`:
- **Prime** (reflect, LLM review, chat): `ANTHROPIC_API_KEY`, `ANTHROPIC_API_BASE`, `LOOM_MODEL`
- **Lab** (autonomous work): `LOOM_LAB_API_KEY`, `LOOM_LAB_API_BASE`, `LOOM_LAB_MODEL`
- Lab inherits from Prime unless overridden. Documented in `env-template`.
- Tested with: Anthropic (Haiku, Sonnet, Opus 4.6) and Minimax (M2.5).

### Model Observations

- **Haiku**: Can execute simple tasks but struggles with complex ClojureScript (async, mocking). Gets close but not clean enough to pass two-stage verification.
- **Minimax M2.5**: Reads files extensively but never writes code. Appears to be a model-level limitation, not a pipeline bug. All 3 generations produced only program.md.
- **Opus 4.6**: First-try success. Clean, correct code in 56 seconds. Made the right architectural call (defn- → defn for testability). Worth the cost (~$1-2/generation) for non-trivial tasks.

### Cost Analysis

Added `scripts/check-credits.sh` for pre-run cost validation. Observed costs per generation:
- Haiku: ~$0.10/gen (cheap but low success rate)
- Sonnet: ~$0.50/gen (Prime reflect + review)
- Opus 4.6: ~$1-2/gen (expensive but high success rate)

---

## Not in v0

- Streaming from Claude API
- MCP
- TUI
- Sub-agents
- Parallel Lab exploration (tree search)
- Generational chaining (Labs spawning children)
- Formal verification
- Production deployment
- Live user↔Lab interaction (Labs are autonomous for now)
- Lab REPL for deep inspection (eval server available but not wired to Prime)
