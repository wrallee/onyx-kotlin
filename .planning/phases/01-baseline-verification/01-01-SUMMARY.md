---
phase: 01-baseline-verification
plan: 01
subsystem: testing
tags: [kotlin, gradle, opensearch, evidence-ledger]
requires: []
provides:
  - Canonical evidence ledger for all 35 approved Kotlin contracts
  - Current compile, unit/integration, and OpenSearch command results
  - Explicit Phase 2-5 routing for six confirmed active gaps
affects: [public-document-management, model-server, opensearch, ingestion, connectors, mcp]
actuals:
  tokens: 5425
  tasks: 2
  commits: 1
plan_head_before: 0872c173c3983a1eecd880876c7c1665ba8a63fd
tech-stack:
  added: []
  patterns: [one-row-per-contract evidence, current-command-only pass evidence]
key-files:
  created:
    - .planning/phases/01-baseline-verification/01-BASELINE.md
    - .planning/phases/01-baseline-verification/01-01-SUMMARY.md
  modified:
    - .planning/STATE.md
    - .planning/ROADMAP.md
    - .planning/REQUIREMENTS.md
key-decisions:
  - "Only a current Kotlin command can create PASS evidence."
  - "A passing test remains GAP when it preserves behavior that conflicts with an approved contract."
  - "Root Python and root Web remain reference-only."
patterns-established:
  - "Evidence rows use fixed source, implementation, test, status, result, gap, and route fields."
  - "Executed checks record the commit, exact command, exit result, duration, warning summary, and environment limit."
requirements-completed: [BASE-01, BASE-02]
coverage:
  - id: D1
    description: "The ledger maps all 35 approved contracts and all scope exclusions."
    requirement: BASE-01
    verification:
      - kind: other
        ref: "01-01-PLAN.md Task 1 document audit"
        status: pass
    human_judgment: false
  - id: D2
    description: "The ledger records three independent current Kotlin verification commands."
    requirement: BASE-02
    verification:
      - kind: integration
        ref: "./gradlew clean compileKotlin compileTestKotlin --warning-mode all --no-daemon"
        status: pass
      - kind: integration
        ref: "./gradlew test --no-daemon"
        status: pass
      - kind: integration
        ref: "./gradlew opensearchIntegrationTest --no-daemon"
        status: pass
    human_judgment: false
duration: 17min
completed: 2026-09-10
status: complete
---

# Phase 1 Plan 1: Baseline Verification Summary

**A 35-contract Kotlin evidence ledger with current Gradle results and explicit routing for six confirmed gaps.**

## Performance

- **Duration:** 17 min
- **Started:** 2026-09-10T05:46:00Z
- **Completed:** 2026-09-10T06:02:39Z
- **Tasks:** 2
- **Files modified:** 5

## Accomplishments

- Mapped all 35 requirement IDs to source, implementation, verification, status, gap, and follow-up phase.
- Recorded three current Gradle command results at commit `0872c173c3983a1eecd880876c7c1665ba8a63fd`.
- Preserved six confirmed gaps and routed them to Phases 2 through 5.
- Kept the two watchlist issues excluded without changing the watchlist file.

## Task Commits

The user requested one consolidated commit for both documentation tasks.

1. **Task 1: Build the complete contract and gap evidence ledger** - consolidated execution commit
2. **Task 2: Run current Kotlin verification and finalize the evidence states** - consolidated execution commit

## Files Created/Modified

- `.planning/phases/01-baseline-verification/01-BASELINE.md` - Canonical contract evidence and gap-routing ledger.
- `.planning/phases/01-baseline-verification/01-01-SUMMARY.md` - Plan results and verification record.

## Decisions Made

- Current commands are the only source of `PASS` evidence.
- Static code and historical results remain separate from current execution.
- Existing tests do not close D-06, D-07, D-08, D-09, D-13, or D-14.
- Docker image execution and remote MCP deployment remain `NOT_RUN`.
- The OpenSearch command passed from the local Gradle build cache. It did not start a new container.

## Verification Results

| Command | Result | Duration | Evidence note |
|---|---|---:|---|
| `./gradlew clean compileKotlin compileTestKotlin --warning-mode all --no-daemon` | PASS | 13s | Exit 0; no Kotlin compiler or deprecation warning. |
| `./gradlew test --no-daemon` | PASS | 93s | Exit 0; 315 tests, no failures, errors, or skipped tests. |
| `./gradlew opensearchIntegrationTest --no-daemon` | PASS | 13s | Exit 0; Gradle restored eight passing test results from local build cache. |
| Task 1 document audit | PASS | Less than 1s | All IDs, required fields, routes, markers, exclusions, and file scope passed. |
| Task 2 result audit | PASS | Less than 1s | All three commands have an allowed final execution state. |

The full suite emitted JVM CDS and Netty native-access warnings. No command was environment-blocked.

## Deviations from Plan

None - plan actions and scope were completed as specified.

The consolidated commit cadence follows the explicit execution instruction.

## Issues Encountered

The OpenSearch task used Gradle build-cache output. The ledger records this limit without promoting a new container run.

## Authentication Gates

None.

## User Setup Required

None - no external service configuration is required.

## Next Phase Readiness

Phase 2 can use the D-14 file rollback evidence. Phases 3 through 5 have explicit routes for the other gaps.

The two deferred watchlist items remain excluded. They do not block the planned phases.

## Self-Check: PASSED

Both execution artifacts exist. The ledger has 35 unique contract rows and all three command results.

---
*Phase: 01-baseline-verification*
*Completed: 2026-09-10*
