---
phase: "02"
slug: "public-document-management"
status: draft
nyquist_compliant: true
wave_0_complete: true
created: "2026-09-10"
---

# Phase 2 — Validation Strategy

> D-14 uses one request-level integration test at the real Spring transaction boundary.

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit Platform, Spring Boot integration test, H2, MockMvc |
| **Config file** | `_kotlin/backend/build.gradle.kts` |
| **Quick run command** | `cd _kotlin/backend && ./gradlew test --tests com.onyx.foss.kotlin.api.AdminApiIntegrationTest.failedZipUploadRemovesFilesCreatedByTheRolledBackRequest --no-daemon` |
| **Regression command** | `cd _kotlin/backend && ./gradlew test --tests com.onyx.foss.kotlin.api.AdminApiIntegrationTest --no-daemon` |
| **Compile command** | `cd _kotlin/backend && ./gradlew compileKotlin compileTestKotlin --warning-mode all --no-daemon` |
| **Expected feedback latency** | Less than 3 minutes for all three commands |

## Failure Direction

Before the service change, the named test must fail because the storage-root entry set grows after HTTP 400.
If it passes before the service change, the executor must stop and repair the test before implementation.

After the service change, the same test must pass. A remaining file or row must fail an explicit assertion.

## Sampling Rate

- **Before implementation:** Run the named test once and record the expected failure.
- **After implementation:** Run the named test, then the full admin integration class.
- **Before the single commit:** Run the compile command with warnings enabled.
- **Max feedback latency:** 3 minutes

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirements | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|--------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 02-01-01 | 01 | 1 | ADMIN-01, ADMIN-02, ADMIN-03, ADMIN-04, PUBLIC-01, PUBLIC-02 | T-02-01, T-02-02, T-02-03 | Delete only request-created paths after rollback | Spring integration | `cd _kotlin/backend && ./gradlew test --tests com.onyx.foss.kotlin.api.AdminApiIntegrationTest.failedZipUploadRemovesFilesCreatedByTheRolledBackRequest --no-daemon` | ✅ task adds test before implementation | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

## Wave 0 Requirements

None. The Gradle test task, Spring context, H2 schema, MockMvc, and storage root already exist.

## Regression Coverage

| Contract | Evidence |
|----------|----------|
| ADMIN-01 through ADMIN-03 | Existing methods in `AdminApiIntegrationTest`; rerun the full class. |
| ADMIN-04 successful upload and metadata | Existing plain-file and ZIP tests in `AdminApiIntegrationTest`; rerun the full class. |
| PUBLIC-01 and PUBLIC-02 | Preserve Phase 1 current evidence; this plan does not change those product paths. |

## Manual-Only Verifications

None for D-14. Remote Web and actual PostgreSQL checks do not gate this narrow product change.

## Validation Sign-Off

- [x] The code-producing task has a named automated integration test.
- [x] The test has an explicit pre-fix failure direction.
- [x] The test checks both physical storage and database state.
- [x] Existing successful uploads remain in the regression class.
- [x] Commands use no watch-mode flags.
- [x] No test dependency or new test class is required.

**Approval:** ready for plan checker
