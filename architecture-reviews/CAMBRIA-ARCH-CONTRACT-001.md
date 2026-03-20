# CAMBRIA Architecture Contract 001

Status: Draft for implementation
Owner: Loom Prime/Supervisor architecture
Scope: CAMBRIA mode only (`LOOM_CAMBRIA_MODE=true`)

---

## 1) Intent

This contract defines the CAMBRIA execution model and replaces ambiguous assumptions.

Core rule set:

1. Prime synthesizes a full generation artifact from spec context.
2. Supervisor stages and hands off that artifact to a Lab.
3. Lab executes viability checks only.
4. Selection/promotion decisions are made at spec/artifact lineage level, not Lab-authored diffs.

---

## 2) Component Responsibilities

### Prime (author + selector)

- Reads CAMBRIAN spec and generation context.
- Produces a generation artifact and manifest.
- Requests Supervisor to run viability.
- Evaluates viability + policy and decides promote/rollback outcome.
- Records lineage and fitness at spec level.

Prime must not rely on Lab-generated code edits in CAMBRIA mode.

### Supervisor (orchestrator + transporter)

- Accepts Prime artifact handoff.
- Creates Lab workspace and injects the provided artifact.
- Launches Lab and collects lifecycle status.
- Persists generation/viability reports.
- Exposes deterministic spawn/promote/rollback APIs.

Supervisor must not mutate synthesized artifacts except transport/staging concerns.

### Lab (executor only)

- Loads the provided artifact.
- Runs viability pipeline (startup/smoke/tests/health checks).
- Emits structured viability status and metrics.
- Never authors code, never commits generation code in CAMBRIA mode.

---

## 3) Mode Gating

`LOOM_CAMBRIA_MODE` is the canonical mode switch.

- `false`/unset: legacy patch-based flow allowed.
- `true`: CAMBRIA contract enforced.

In CAMBRIA mode:

- Lab authoring tools/path must be disabled.
- Prime must use synthesis path, not task-edit Lab mutation path.
- Verify/promotion logic uses artifact/lineage contracts.

If CAMBRIA-required inputs are missing, fail fast with explicit contract errors.

---

## 4) Data Contracts

## 4.1 Generation Artifact Manifest (conceptual)

Minimum required fields:

```json
{
  "generation": 123,
  "spec_hash": "sha256:...",
  "artifact_hash": "sha256:...",
  "producer": {
    "model": "...",
    "provider": "...",
    "token_usage": {"input": 0, "output": 0}
  },
  "entrypoints": {
    "supervisor": "out/supervisor.js",
    "agent": "out/agent.js",
    "lab_worker": "out/lab-worker.js"
  },
  "checks": {
    "startup": true,
    "smoke": true,
    "tests": true
  },
  "created_at": "ISO-8601"
}
```

## 4.2 Viability Report (Lab -> Prime/Supervisor)

Minimum required fields:

```json
{
  "generation": 123,
  "status": "viable|non-viable",
  "failure_class": "none|build|startup|runtime|tests|contract",
  "checks": {
    "startup": {"passed": true, "duration_ms": 0},
    "smoke": {"passed": true, "duration_ms": 0},
    "tests": {"passed": true, "duration_ms": 0}
  },
  "metrics": {
    "duration_ms": 0,
    "cpu_ms": 0,
    "memory_peak_mb": 0
  },
  "logs_ref": "path-or-id",
  "completed_at": "ISO-8601"
}
```

## 4.3 Spec-level Lineage Record

Minimum required fields:

```json
{
  "generation": 123,
  "parent_generation": 122,
  "parent_spec_hash": "sha256:...",
  "offspring_spec_hash": "sha256:...",
  "artifact_hash": "sha256:...",
  "outcome": "promoted|rolled-back|failed",
  "viability_score": 0.0,
  "fitness_score": 0.0,
  "timestamp": "ISO-8601"
}
```

---

## 5) Lifecycle (CAMBRIA mode)

1. Prime loads spec + context.
2. Prime synthesizes artifact + manifest.
3. Prime asks Supervisor to spawn Lab with artifact reference.
4. Supervisor stages artifact and launches Lab.
5. Lab runs viability checks and reports structured result.
6. Prime verifies policy conditions and writes lineage decision.
7. Supervisor finalizes generation outcome (promote/rollback semantics adapted to CAMBRIA).

No step in this lifecycle requires Lab code authoring.

---

## 6) Invariants

1. In CAMBRIA mode, Lab never mutates generation source.
2. Artifact hash must match manifest before Lab executes.
3. Missing artifact, missing manifest, or hash mismatch is immediate non-viable outcome.
4. Promotion eligibility requires valid viability report and satisfied policy thresholds.
5. All decisions are recorded in append-only lineage/fitness logs.

---

## 7) Compatibility and Migration

- Legacy flow remains available outside CAMBRIA mode.
- CAMBRIA-mode code paths must be isolated and testable independently.
- Mixed-mode assumptions are forbidden (e.g., CAMBRIA synthesis + Lab mutation).

---

## 8) Acceptance Criteria for This Contract

1. Prime can produce a manifest-backed generation artifact without invoking Lab mutation.
2. Supervisor can run a Lab using only the staged artifact.
3. Lab can return viability outcomes without any commit/edit flow.
4. Promotion/rollback paths can operate using viability + lineage data in CAMBRIA mode.
5. Conformance tests fail if Lab mutation occurs in CAMBRIA mode.

---

## 9) Out of Scope (for Contract 001)

- Multi-Lab tournament selection
- Crossover between sibling specs
- Distributed/cloud scheduling details
- Financial optimization strategy

Contract 001 establishes the minimal deterministic CAMBRIA path.
