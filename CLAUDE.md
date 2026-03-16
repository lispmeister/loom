# Loom — Project Instructions for Claude Code

## What This Is

Loom is a self-modifying coding agent in ClojureScript. See [README.md](README.md) for full architecture, schemas, and setup. See [PLAN.md](PLAN.md) for architecture and design decisions.

## Task Tracking

All tasks, features, and bugs are tracked in **beads** — not in markdown files.

```bash
beads list           # open issues
beads list --all     # include closed
beads show <id>      # details + dependencies
beads create --title "..." --description "..." --priority P2 --type task
beads close <id>     # close a completed issue
```

Do NOT add task lists, backlogs, or checklists to PLAN.md, README.md, or any other markdown file. If work needs tracking, create a bead.

**Bug tracking rule:** Create a bead immediately when a bug is detected (`--type bug`). Only close it once the fix is in place AND verified (tests pass, or the failing scenario is re-tested successfully). We use this history for later analysis of what broke and when.

## Fixed-Point Contracts

DO NOT MODIFY the Malli schemas in `src/loom/shared/schemas.cljs` without explicit user approval. These are the eval protocol contracts (EvalRequest, EvalResponse). The self-modification cycle uses direct HTTP JSON payloads (POST /spawn, /promote, /rollback) documented in PLAN.md.

## Build & Run

```bash
# Install dependencies
npm install

# Compile and run tests
npm test && node out/test.js

# Watch mode (continuous recompilation)
npm run watch:test

# Compile and run components
npm run supervisor
npm run agent

# Build lab-worker (MUST use release for self-contained bundle)
npx shadow-cljs release lab-worker
```

### Environment Setup

Create a `.env` file in the project root (if it doesn't exist):

```bash
ANTHROPIC_API_KEY=sk-ant-...your-key-here...
```

Important: The supervisor must be started with this env loaded — it passes the API key to Lab containers at spawn time.
Always source .env before running:

```bash
set -a && source .env && set +a
node out/supervisor.js
```

Or use a one-liner:
```bash
env $(cat .env | xargs) node out/supervisor.js
```

## Code Style

- Pure ClojureScript. No Java interop, no JVM dependencies.
- Prefer `node:` built-in modules over npm packages.
- Keep functions small. Prefer data transformation over side effects.
- Use Malli for all external-facing data validation.
- No macros unless absolutely necessary — LLMs handle functions better.
- Format with cljfmt (auto-applied by Claude Code hook).
- Components are AOT-compiled by shadow-cljs. The Lab uses `cljs.js/eval-str` at runtime for evaluating modified code.

## Project Structure

```
src/loom/shared/    — Malli schemas, eval protocol, HTTP helpers
src/loom/agent/     — Agentic loop, Claude API client, tools
src/loom/supervisor/ — Container lifecycle, version management, dashboard
src/loom/lab/       — Form evaluation server (~50 lines)
test/loom/          — Tests
```

## References

- [The Prime and the Lab](https://lispmeister.github.io/deeprecursion/posts/2026-03-12-recursive-self-improvement.html) — Architecture spec
- [PLAN.md](PLAN.md) — Architecture and design decisions
