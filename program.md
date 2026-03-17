# Task: Add tests for parse-test-counts and parse-shortstat helpers

## Objective

Add unit tests for two pure helper functions in the self-modification module.

## Background

`src/loom/agent/self_modify.cljs` contains two private helper functions:
- `parse-test-counts` — parses test runner output like "Ran 42 tests containing 100 assertions.\n0 failures, 0 errors." into {:tests-run 42 :assertions 100 :failures 0 :errors 0 :passed? true}
- `parse-shortstat` — parses git diff --shortstat output like " 3 files changed, 37 insertions(+), 11 deletions(-)" into {:files-changed 3 :insertions 37 :deletions 11}

These are private (defn-). To test them, either make them public or access via var: `(#'loom.agent.self-modify/parse-test-counts ...)`. Note: in ClojureScript, `#'` var access may not work for private fns — check if you need to change `defn-` to `defn` instead.

## Requirements

1. Create `test/loom/self_modify_helpers_test.cljs`
2. Test `parse-test-counts` with:
   - Passing output (0 failures, 0 errors)
   - Failing output (2 failures, 1 error)
   - Output with no match (garbage string)
3. Test `parse-shortstat` with:
   - Normal output (files, insertions, deletions)
   - Only insertions (no deletions)
   - Empty/no-match output
4. All existing tests must still pass

## Acceptance Criteria

- New test file compiles and runs
- At least 6 test assertions pass
- Zero regressions in existing tests
