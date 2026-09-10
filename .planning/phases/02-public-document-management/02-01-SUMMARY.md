---
phase: 02-public-document-management
plan: 01
subsystem: storage
tags: [kotlin, spring-transaction, file-storage, rollback]
requires:
  - phase: 01-baseline-verification
    provides: D-14 evidence and Phase 2 contract baseline
provides:
  - Rollback cleanup for request-created file assets
  - Immediate cleanup for partial plain and ZIP writes
  - Integration proof for metadata, completed entry, and partial entry cleanup
affects: [file-connectors, public-document-management]
actuals:
  tokens: 1700
  tasks: 1
  commits: 1
tech-stack:
  added: []
  patterns: [transaction synchronization for non-database resource cleanup]
key-files:
  created:
    - .planning/phases/02-public-document-management/02-01-SUMMARY.md
  modified:
    - _kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/service/FileStorageService.kt
    - _kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/api/AdminApiIntegrationTest.kt
key-decisions:
  - "Register cleanup at the shared file-asset save boundary."
  - "Delete only paths created by the current request and only after rollback."
patterns-established:
  - "Filesystem writes paired with a DB transaction register rollback cleanup after repository save."
requirements-completed: [ADMIN-01, ADMIN-02, ADMIN-03, ADMIN-04, PUBLIC-01, PUBLIC-02]
coverage:
  - id: D1
    description: "A failed ZIP request leaves no request-created file or file_assets row."
    requirement: ADMIN-04
    verification:
      - kind: integration
        ref: "AdminApiIntegrationTest#failedZipUploadRemovesFilesCreatedByTheRolledBackRequest"
        status: pass
    human_judgment: false
  - id: D2
    description: "Existing admin and successful file-management behavior remains unchanged."
    requirement: ADMIN-01
    verification:
      - kind: integration
        ref: "./gradlew test --tests com.onyx.foss.kotlin.api.AdminApiIntegrationTest --no-daemon"
        status: pass
    human_judgment: false
duration: 5min
completed: 2026-09-10
status: complete
---

# Phase 2 Plan 1: File Rollback Cleanup Summary

**Failed file uploads now remove every physical file created before their database transaction rolls back.**

## Performance

- **Duration:** 5 min
- **Tasks:** 1
- **Files modified:** 3

## Accomplishments

- Added shared rollback cleanup for plain files, ZIP entries, and generated metadata.
- Preserved the original exception while recording any cleanup error as suppressed.
- Added one request-level integration test that covers completed and partial ZIP writes.

## Task Commits

The code, test, and summary use one consolidated commit.

1. **Task 1: Remove request-owned files when their transaction rolls back** - consolidated execution commit

## Verification Results

| Command | Result |
|---|---|
| Focused rollback test before service change | Expected FAIL |
| Focused rollback test after service change | PASS |
| Full `AdminApiIntegrationTest` | PASS |
| Warning-enabled production and test compile | PASS |

## Decisions Made

- Reused Spring transaction synchronization already on the classpath.
- Kept controller, entity, repository, API, and configuration contracts unchanged.
- Did not change removal behavior for existing file IDs.

## Deviations from Plan

None.

## Issues Encountered

None.

## User Setup Required

None.

## Next Phase Readiness

D-14 is closed. Phase 3 can address the Kotlin backend container toolchain mismatch.

## Self-Check: PASSED

The focused failure direction and all post-change commands passed. Product edits are limited to `_kotlin/backend`.

---
*Phase: 02-public-document-management*
*Completed: 2026-09-10*
