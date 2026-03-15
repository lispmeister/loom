# Task: Add line numbers to read_file tool output

## What to change
In src/loom/agent/tools.cljs, modify the read-file function. After reading file content, split into lines and prepend 1-based line numbers. Format: right-aligned number padded to width of max line number, tab, then line content.

Also update test expectations in test/loom/tools_test.cljs to match the new output format.

## Files to modify
1. src/loom/agent/tools.cljs — the read-file function
2. test/loom/tools_test.cljs — update test expectations

## Steps
1. Read src/loom/agent/tools.cljs
2. Read test/loom/tools_test.cljs
3. Edit read-file to add line numbering
4. Update tests: any test that asserts raw file content from read_file must expect line-numbered output (e.g. "hello world" becomes "1\thello world", "dispatch test" becomes "1\tdispatch test", "hello from dispatch" becomes "1\thello from dispatch")
5. Done — do NOT run npm test or npx shadow-cljs (not available in container). The host will verify tests.

## IMPORTANT
- Do NOT run npm test or npx shadow-cljs. They are not available in the container.
- Just make the edits and let the auto-commit handle the rest.
- Only modify tools.cljs and tools_test.cljs.