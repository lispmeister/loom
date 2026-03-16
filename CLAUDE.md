# Loom — Project Instructions for Claude Code

## What This Is

Loom is a self-modifying coding agent in ClojureScript. See [README.md](README.md) for full architecture, schemas, and setup. See [PLAN.md](PLAN.md) for architecture and design decisions.

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


<!-- BEGIN BEADS INTEGRATION v:1 profile:full hash:d4f96305 -->
## Issue Tracking with bd (beads)

**IMPORTANT**: This project uses **bd (beads)** for ALL issue tracking. Do NOT use markdown TODOs, task lists, or other tracking methods.

### Why bd?

- Dependency-aware: Track blockers and relationships between issues
- Git-friendly: Dolt-powered version control with native sync
- Agent-optimized: JSON output, ready work detection, discovered-from links
- Prevents duplicate tracking systems and confusion

### Quick Start

**Check for ready work:**

```bash
bd ready --json
```

**Create new issues:**

```bash
bd create "Issue title" --description="Detailed context" -t bug|feature|task -p 0-4 --json
bd create "Issue title" --description="What this issue is about" -p 1 --deps discovered-from:bd-123 --json
```

**Claim and update:**

```bash
bd update <id> --claim --json
bd update bd-42 --priority 1 --json
```

**Complete work:**

```bash
bd close bd-42 --reason "Completed" --json
```

### Issue Types

- `bug` - Something broken
- `feature` - New functionality
- `task` - Work item (tests, docs, refactoring)
- `epic` - Large feature with subtasks
- `chore` - Maintenance (dependencies, tooling)

### Priorities

- `0` - Critical (security, data loss, broken builds)
- `1` - High (major features, important bugs)
- `2` - Medium (default, nice-to-have)
- `3` - Low (polish, optimization)
- `4` - Backlog (future ideas)

### Workflow for AI Agents

1. **Check ready work**: `bd ready` shows unblocked issues
2. **Claim your task atomically**: `bd update <id> --claim`
3. **Work on it**: Implement, test, document
4. **Discover new work?** Create linked issue:
   - `bd create "Found bug" --description="Details about what was found" -p 1 --deps discovered-from:<parent-id>`
5. **Complete**: `bd close <id> --reason "Done"`

### Auto-Sync

bd automatically syncs via Dolt:

- Each write auto-commits to Dolt history
- Use `bd dolt push`/`bd dolt pull` for remote sync
- No manual export/import needed!

### Important Rules

- ✅ Use bd for ALL task tracking
- ✅ Always use `--json` flag for programmatic use
- ✅ Link discovered work with `discovered-from` dependencies
- ✅ Check `bd ready` before asking "what should I work on?"
- ❌ Do NOT create markdown TODO lists
- ❌ Do NOT use external issue trackers
- ❌ Do NOT duplicate tracking systems

For more details, see README.md and docs/QUICKSTART.md.

## Landing the Plane (Session Completion)

**When ending a work session**, you MUST complete ALL steps below. Work is NOT complete until `git push` succeeds.

**MANDATORY WORKFLOW:**

1. **File issues for remaining work** - Create issues for anything that needs follow-up
2. **Run quality gates** (if code changed) - Tests, linters, builds
3. **Update issue status** - Close finished work, update in-progress items
4. **PUSH TO REMOTE** - This is MANDATORY:
   ```bash
   git pull --rebase
   bd dolt push
   git push
   git status  # MUST show "up to date with origin"
   ```
5. **Clean up** - Clear stashes, prune remote branches
6. **Verify** - All changes committed AND pushed
7. **Hand off** - Provide context for next session

**CRITICAL RULES:**
- Work is NOT complete until `git push` succeeds
- NEVER stop before pushing - that leaves work stranded locally
- NEVER say "ready to push when you are" - YOU must push
- If push fails, resolve and retry until it succeeds

<!-- END BEADS INTEGRATION -->
