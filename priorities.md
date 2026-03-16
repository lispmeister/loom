# Loom Improvement Priorities

Read by Prime during the reflect step to guide autonomous self-improvement.
Maintained by the user. Ordered by importance — work top-down.

---

## 1. Test coverage for self-modification tools

The self-modification tools (spawn_lab, verify_generation, promote_generation,
rollback_generation) have zero unit tests. Add tests that exercise the tool
functions with mocked HTTP responses. This is the highest-risk untested code.

## 2. Deeper verification

verify_generation only runs `npm test`. Add at least one eval probe: after
checkout of the lab branch, verify that key modules load without error
(e.g., `node -e "require('./out/supervisor.js')"` or similar). This catches
import-time crashes that tests might miss.

## 3. Error handling in agent loop

The agent loop (loop.cljs) has minimal error handling. If Claude returns
malformed JSON or an unexpected content type, the loop crashes. Add defensive
parsing and graceful degradation.

## 4. Generation report completeness

After verify_generation runs, the test-results and diff-stats should be
written back into the generation report (currently they're only stored in
the last-verification atom on Prime). Wire the Supervisor's promote/rollback
handlers to include verification data in the final report.

## 5. Reduce token waste

The system prompt and tool definitions consume ~2000 tokens per API call.
Evaluate whether tool definitions can be trimmed (shorter descriptions,
fewer optional fields) without degrading tool-use accuracy.
