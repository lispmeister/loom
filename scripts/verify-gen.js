#!/usr/bin/env node
// Standalone script to test verify_generation with LLM review.
// Usage: ANTHROPIC_API_KEY=... node scripts/verify-gen.js <gen-num> [repo-path]

const { execFile, exec } = require("node:child_process");
const fs = require("node:fs");
const path = require("node:path");
const https = require("node:https");

const genNum = parseInt(process.argv[2], 10);
const repoPath = process.argv[3] || ".";

if (!genNum) {
  console.error("Usage: node scripts/verify-gen.js <gen-num> [repo-path]");
  process.exit(1);
}

function gitExec(repo, ...args) {
  return new Promise((resolve) => {
    execFile("git", args, { cwd: repo, maxBuffer: 10 * 1024 * 1024 }, (err, stdout, stderr) => {
      if (err) resolve({ error: true, message: stderr });
      else resolve({ ok: true, output: stdout });
    });
  });
}

function runTests(repo) {
  return new Promise((resolve) => {
    exec("npm test 2>&1 && node out/test.js 2>&1", { cwd: repo, timeout: 120000, maxBuffer: 10 * 1024 * 1024 }, (err, stdout) => {
      resolve({ ok: !err, output: stdout, exit: err ? (err.code || 1) : 0 });
    });
  });
}

function parseTestCounts(output, passed) {
  const ran = output.match(/Ran (\d+) tests containing (\d+) assertions/);
  const fail = output.match(/(\d+) failures/);
  const errs = output.match(/(\d+) errors/);
  return {
    "tests-run": ran ? parseInt(ran[1]) : 0,
    assertions: ran ? parseInt(ran[2]) : 0,
    failures: fail ? parseInt(fail[1]) : 0,
    errors: errs ? parseInt(errs[1]) : 0,
    "passed?": passed,
  };
}

function parseShortstat(output) {
  const files = output.match(/(\d+) files? changed/);
  const ins = output.match(/(\d+) insertions?/);
  const dels = output.match(/(\d+) deletions?/);
  return {
    "files-changed": files ? parseInt(files[1]) : 0,
    insertions: ins ? parseInt(ins[1]) : 0,
    deletions: dels ? parseInt(dels[1]) : 0,
  };
}

function loadProgramMd(repo, gen) {
  const filepath = path.join(repo, "tmp", "programs", `gen-${gen}.md`);
  try { return fs.readFileSync(filepath, "utf8"); }
  catch { return null; }
}

function llmReviewDiff(diff, programMd) {
  const apiKey = process.env.ANTHROPIC_API_KEY;
  const model = process.env.LOOM_MODEL || "claude-sonnet-4-20250514";
  if (!apiKey) {
    return Promise.resolve({ approved: false, issues: ["No ANTHROPIC_API_KEY"], confidence: "low", summary: "Skipped: no API key" });
  }

  const truncDiff = diff.length > 8000 ? diff.slice(0, 8000) + "\n... (truncated)" : diff;
  const body = JSON.stringify({
    model,
    max_tokens: 1024,
    system: `You are a code reviewer for the Loom self-modification system.
Your job is to verify whether a Lab's code changes completely and correctly
implement the task described in program.md.

You MUST respond with valid JSON matching this schema:
{"approved": boolean,
 "issues": ["description of each issue"],
 "confidence": "high" | "medium" | "low",
 "summary": "one sentence summary of your assessment"}

Be strict. Check for:
- Completeness: did the Lab address ALL requirements in program.md?
- Correctness: are the changes logically correct?
- Missing edits: are there locations that should have been changed but weren't?
- Regressions: do the changes break existing behavior?

If the diff is empty or trivially cosmetic (whitespace, comments only), reject it.`,
    messages: [{
      role: "user",
      content: `## program.md (task spec)\n\n${programMd}\n\n## Diff (changes made by Lab)\n\n\`\`\`\n${truncDiff}\n\`\`\`\n\nDoes this diff completely and correctly implement the program.md task? Respond with JSON only.`
    }]
  });

  return new Promise((resolve) => {
    const url = new URL(process.env.ANTHROPIC_API_BASE || "https://api.anthropic.com");
    const req = https.request({
      hostname: url.hostname,
      port: url.port || 443,
      path: "/v1/messages",
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "x-api-key": apiKey,
        "anthropic-version": "2023-06-01",
      },
    }, (res) => {
      let data = "";
      res.on("data", (chunk) => data += chunk);
      res.on("end", () => {
        try {
          const parsed = JSON.parse(data);
          if (parsed.error) {
            resolve({ approved: false, issues: [`API error: ${parsed.error.message}`], confidence: "low", summary: "API error" });
            return;
          }
          const text = parsed.content?.[0]?.text || "";
          const cleaned = text.replace(/^```json?\s*/, "").replace(/\s*```\s*$/, "").trim();
          const verdict = JSON.parse(cleaned);
          resolve({
            approved: !!verdict.approved,
            issues: verdict.issues || [],
            confidence: verdict.confidence || "low",
            summary: verdict.summary || "",
          });
        } catch (e) {
          resolve({ approved: false, issues: [`Parse error: ${e.message}, raw: ${data.slice(0, 500)}`], confidence: "low", summary: "Parse failure" });
        }
      });
    });
    req.on("error", (e) => {
      resolve({ approved: false, issues: [`Network error: ${e.message}`], confidence: "low", summary: "Network error" });
    });
    req.write(body);
    req.end();
  });
}

async function main() {
  const branch = `lab/gen-${genNum}`;
  console.log(`Verifying generation ${genNum} (branch: ${branch})...`);

  // Step 1: Get diffs
  const diffStat = await gitExec(repoPath, "diff", "--stat", `master...${branch}`);
  const diffFull = await gitExec(repoPath, "diff", `master...${branch}`);
  const shortstat = await gitExec(repoPath, "diff", "--shortstat", `master...${branch}`);

  console.log("\n=== DIFF STAT ===");
  console.log(diffStat.ok ? diffStat.output : `Error: ${diffStat.message}`);

  const diffStats = shortstat.ok ? parseShortstat(shortstat.output) : {};
  const diffText = diffFull.ok ? diffFull.output : "";

  // Step 2: Checkout and run tests
  const checkout = await gitExec(repoPath, "checkout", branch);
  if (checkout.error) {
    console.error(`Cannot checkout ${branch}: ${checkout.message}`);
    process.exit(1);
  }

  console.log("Running tests on branch...");
  const testResult = await runTests(repoPath);

  // Always return to master
  await gitExec(repoPath, "checkout", "master");
  console.log("Returned to master.");

  const testCounts = parseTestCounts(testResult.output, testResult.ok);
  console.log("\n=== TEST RESULTS ===");
  console.log(JSON.stringify(testCounts, null, 2));

  if (!testResult.ok) {
    console.log("\nTests FAILED — skipping LLM review.");
    console.log("\n=== VERIFICATION (would be stored) ===");
    console.log(JSON.stringify({ generation: genNum, "test-results": testCounts, "diff-stats": diffStats }, null, 2));
    process.exit(1);
  }

  // Step 3: LLM review
  const programMd = loadProgramMd(repoPath, genNum);
  if (!programMd) {
    console.log("\nprogram.md not found — skipping LLM review.");
    console.log("\n=== VERIFICATION (would be stored) ===");
    console.log(JSON.stringify({
      generation: genNum,
      "test-results": testCounts,
      "diff-stats": diffStats,
      "llm-verdict": { approved: false, issues: ["program.md not found"], confidence: "low", summary: "Skipped" },
    }, null, 2));
    process.exit(0);
  }

  console.log("\nprogram.md loaded. Sending to LLM for review...");
  const verdict = await llmReviewDiff(diffText, programMd);

  console.log("\n=== LLM VERDICT ===");
  console.log(JSON.stringify(verdict, null, 2));

  console.log("\n=== FULL VERIFICATION (would be stored in report) ===");
  console.log(JSON.stringify({
    generation: genNum,
    "test-results": testCounts,
    "diff-stats": diffStats,
    "llm-verdict": verdict,
  }, null, 2));
}

main().catch((e) => { console.error(e); process.exit(1); });
