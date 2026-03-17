# Changelog

All notable changes to Loom are documented here. Format follows [Keep a Changelog](https://keepachangelog.com/).

## [0.1.0] — 2026-03-17

First public release. The self-modification pipeline is functional end-to-end: Prime can autonomously reflect, spawn Labs, verify results, and promote successful generations.

### Highlights

- **First autonomous self-modification** — Gen-72 rewrote its own source and promoted to master in 56 seconds, with two-stage verification (tests + LLM review)
- **72 generations** attempted across development, validating the full pipeline
- **236 tests, 607 assertions** covering agent loop, container lifecycle, eval server, fitness scoring, and self-modification tools

### Added

- Agent loop with Claude API client (multi-turn, tool dispatch, up to 40 iterations)
- Self-modification tools: `spawn_lab`, `verify_generation`, `promote_generation`, `rollback_generation`
- Reflect step: autonomous analysis and proposal of next improvement
- Autonomous loop driver: reflect → spawn → verify → promote/rollback → repeat
- Supervisor: container lifecycle management, generation history, HTTP dashboard (:8400)
- Lab worker: autonomous agent reading `program.md`, commits to `lab/gen-N` branch
- Fitness scoring with test results, diff stats, token usage, and user-task success rate
- Generation reports with full metrics
- Prime state serialization for cross-generation continuity
- Rolling-window API prompt budget tracker
- Pre-spawn build artifact validation
- EvalResponse size/depth guard on eval server
- Rate-limit-aware retry (429 with `retry-after` header)
- Multi-provider LLM support (`LOOM_LAB_API_KEY`, `LOOM_LAB_API_BASE`, `LOOM_LAB_MODEL`)
- CLI tools: `node out/agent.js reflect`, `spawn`, `autonomous`
- SSE log streaming on all HTTP endpoints
- MIT license, GitHub Actions CI, README badges
- Architecture SVG with light/dark mode support

[0.1.0]: https://github.com/lispmeister/loom/releases/tag/v0.1.0
