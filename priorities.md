# Loom Improvement Priorities

Read by Prime during the reflect step to guide autonomous self-improvement.
Maintained by the user. Ordered by importance — work top-down.

---

## 1. Add a simple utility function with tests

Create `src/loom/shared/utils.cljs` with a `truncate-string` function that
truncates a string to N characters, adding "..." if truncated. Include tests
in `test/loom/utils_test.cljs`. This is a deliberately simple task to prove
the Lab can create new files, add tests, and pass verification.

## 2. Deeper verification

verify_generation only runs `npm test`. Add a post-test check: after tests pass,
verify that key modules compile without error by running
`node -e "require('./out/test.js')"` as a sanity check. This catches
import-time crashes that tests might miss. Modify `checkout-and-test` in
`src/loom/agent/self_modify.cljs` to add this check.

## 3. Error handling in agent loop

The agent loop (loop.cljs) has minimal error handling. If Claude returns
malformed JSON or an unexpected content type, the loop crashes. Add defensive
parsing and graceful degradation. Focus on the `tool-use-loop` function.

## 4. Reduce token waste

The system prompt and tool definitions consume ~2000 tokens per API call.
Evaluate whether tool definitions can be trimmed (shorter descriptions,
fewer optional fields) without degrading tool-use accuracy.

## 5. Test coverage for self-modification tools

The self-modification tools (spawn_lab, verify_generation, promote_generation,
rollback_generation) need unit tests. This is complex because the functions
make HTTP calls. Tests should mock `client/post-json` by redefining it with
`with-redefs` or by testing the helper functions (parse-test-counts,
parse-shortstat, format-test-summary) which are pure and testable.
NOTE: Start with testing the pure helper functions, not the async HTTP callers.
