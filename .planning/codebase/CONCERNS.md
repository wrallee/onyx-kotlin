# Codebase Concerns

**Analysis Date:** 2026-09-09

## Tech Debt

**Two backend implementations have separate contracts:**
- Issue: The Python application and Kotlin port retain different authentication, ingestion, and search contracts.
- Files: `backend/onyx/document_index/factory.py`, `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/api/AuthController.kt`, `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/OpenSearchIndexer.kt`.
- Impact: A working Python feature does not establish Kotlin parity. Paths and deployment instructions need explicit runtime scope.
- Fix approach: Name the target runtime in each change. Verify contracts against that implementation and its tests.

**Kotlin container build bypasses the wrapper:**
- Issue: The Dockerfile invokes installed Gradle 8.14.3. The repository wrapper selects Gradle 9.5.1.
- Files: `_kotlin/backend/Dockerfile`, `_kotlin/backend/gradle/wrapper/gradle-wrapper.properties`.
- Impact: Container and CI dependency resolution and build behavior can differ.
- Fix approach: Use the committed wrapper in container builds. Align the JDK and runtime target together.

## Known Bugs

**Kotlin image cannot run the configured Java 25 output:**
- Evidence: Confirmed configuration mismatch; no container build or runtime test was performed during mapping.
- Symptoms: The build requests Java 25, but the image supplies JDK 21. The final runtime also supplies Java 21.
- Files: `_kotlin/backend/build.gradle.kts`, `_kotlin/backend/Dockerfile`, `_kotlin/backend/settings.gradle.kts`.
- Trigger: Build the backend image using its checked-in Dockerfile. A Java 25 toolchain is not supplied there.
- Impact: Even if toolchain acquisition succeeds elsewhere, JVM 25 bytecode cannot run on the final Java 21 runtime.
- Workaround: Use the Java 25 wrapper-based workflow until the image is aligned: `.github/workflows/custom-kotlin-backend-checks.yml`.

**Kotlin upload rollback leaves files outside the database transaction:**
- Evidence: Confirmed failure-path gap from static inspection; not reproduced during mapping.
- Symptoms: Files can remain on disk after the transaction rolls back their asset rows.
- Files: `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/service/FileStorageService.kt`.
- Trigger: Store one ZIP entry successfully, then fail a later entry, metadata parse, or asset save.
- Cause: `store()` writes before saving the row. `storeZipEntry()` deletes only its current file on copy failure.
- Workaround: No batch cleanup is present in this service. Add cleanup for all files created by a failed upload.

## Security Considerations

**Kotlin endpoints trust every caller as an administrator:**
- Evidence: Intentional implementation boundary, not an authentication bypass regression.
- Risk: Network access to the service grants administrative operations and document access.
- Files: `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/api/AuthController.kt`, `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/api/AdminController.kt`, `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/mcp/McpConfiguration.kt`.
- Current mitigation: `AuthController` explicitly states that the port has no authentication system. No external access-control layer was verified.
- Recommendations: Restrict access to trusted callers. Add authentication and authorization before supporting untrusted or separate users.

**Kotlin indexed documents are public within the service:**
- Risk: Source permissions do not restrict indexed content for distinct callers.
- Files: `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/OpenSearchIndexer.kt`, `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/service/SearchService.kt`.
- Current mitigation: The port has a single trusted-admin model. Index writes set public access and empty external permission lists.
- Recommendations: Treat this as a deployment constraint. Implement identity-aware retrieval before enabling multi-user access.

**Python worker tenant context defaults silently:**
- Risk: A task without `tenant_id` executes against the default schema. This is a caller-contract risk, not confirmed cross-tenant exposure.
- Files: `backend/onyx/background/celery/apps/app_base.py`, `backend/AGENTS.md`.
- Current mitigation: `TenantAwareTask` clears the context after execution. Project guidance requires explicit tenant propagation.
- Recommendations: Test direct task publishers for tenant propagation. Reject missing tenant context where multi-tenant work requires it.

## Performance Bottlenecks

**Kotlin ingestion refreshes OpenSearch for each chunk under a database lock:**
- Problem: Each chunk requires an index request and immediate refresh while holding a pair row lock.
- Files: `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/IngestionWorker.kt`, `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/OpenSearchIndexer.kt`, `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/PairExternalWriteFence.kt`, `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/domain/Repositories.kt`.
- Cause: The processor loops over chunks. `upsert()` uses `Refresh.True`; `withPair()` wraps the request in a transaction.
- Impact: Large documents amplify refresh cost. Slow index responses extend database lock duration.
- Improvement path: Measure chunk throughput and lock waits. Batch writes and refresh at a deliberate boundary while preserving ownership fences.

**Kotlin ingestion processes one job per poll invocation:**
- Problem: The scheduled method claims one job and processes it synchronously.
- Files: `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/IngestionWorker.kt`.
- Cause: `work()` calls `claimNext()?.let(processor::process)` and uses a fixed delay after completion.
- Impact: A long connector run delays the next claim on that scheduling lane.
- Improvement path: Measure queue delay before adding bounded concurrency. Preserve claim tokens, lease renewal, and pair exclusion.

## Fragile Areas

**Kotlin external writes, leases, and deletion fences:**
- Files: `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/IngestionWorker.kt`, `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/PairExternalWriteFence.kt`, `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/DocumentSetSyncWorker.kt`.
- Why fragile: Database ownership and OpenSearch writes do not share an atomic transaction. Correctness depends on lock and renewal order.
- Safe modification: Preserve stale-owner checks before external writes. Exercise deletion and lease-loss interleavings when changing this order.
- Test coverage: `PairExternalWriteFenceTest.kt` and `DocumentSetSyncOutboxIntegrationTest.kt` under `_kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/ingestion/` cover these contracts; database behavior still needs PostgreSQL validation.

**Python worker time limits depend on application code:**
- Files: `backend/AGENTS.md`, `backend/onyx/background/celery/apps/docprocessing.py`, `backend/onyx/background/celery/configs/docprocessing.py`.
- Why fragile: Thread workers cannot rely on Celery process time limits. Long external calls can retain worker capacity.
- Safe modification: Keep explicit client timeouts, cancellation, and watchdog behavior. Do not replace them with task time-limit settings.
- Test coverage: Validate stalled operations and recovery for the changed worker. No timeout coverage completeness claim is made here.

## Scaling Limits

**Kotlin remote responses are buffered with a fixed cap:**
- Current capacity: Each response is capped at 16 MiB. Calls block for at most 30 seconds.
- Files: `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/RemoteJsonClient.kt`.
- Limit: Larger attachment or JSON responses fail. Concurrent requests multiply memory use within the cap.
- Scaling path: Keep the cap. Add paging or streamed file handling for sources that exceed it.

**Kotlin ZIP extraction uses a per-archive byte budget:**
- Current capacity: The implementation limits extracted contents to 100 MiB per archive.
- Files: `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/service/FileStorageService.kt`.
- Limit: Each archive resets the extraction counter. The method does not enforce a total storage quota or an entry-count limit.
- Scaling path: Add request-wide and storage limits when upload volume requires them. Preserve existing path checks and extraction limits.

**Python ID-only OpenSearch helper returns one result window:**
- Current capacity: One search response; it warns when hits reach `DEFAULT_OPENSEARCH_MAX_RESULT_WINDOW`.
- Files: `backend/onyx/document_index/opensearch/client.py`.
- Limit: `search_for_document_ids()` does not page. No active caller was detected in the inspected OpenSearch package.
- Scaling path: Do not use this helper for exhaustive enumeration. The same client exposes PIT helpers for consistent scans.

## Dependencies at Risk

**Kotlin Java and Gradle requirements differ between delivery paths:**
- Risk: CI uses Java 25 and the wrapper; Docker uses Java 21 and installed Gradle 8.14.3.
- Files: `.github/workflows/custom-kotlin-backend-checks.yml`, `_kotlin/backend/Dockerfile`, `_kotlin/backend/build.gradle.kts`.
- Impact: Passing backend tests does not establish that the packaged service starts.
- Migration plan: Align container build and runtime versions with the build target. No vulnerability or support-lifecycle audit was performed.

## Missing Critical Features

**Kotlin multi-user permission enforcement:**
- Problem: The port exposes a single admin identity and public indexed content.
- Files: `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/api/AuthController.kt`, `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/OpenSearchIndexer.kt`.
- Blocks: Deployments requiring separate users or source-specific document visibility. This is not required for a trusted single-admin deployment.

## Test Coverage Gaps

**Kotlin PostgreSQL locking and migration parity:**
- What's not tested: The shared integration base uses H2 PostgreSQL mode. A PostgreSQL container test was not detected.
- Files: `_kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/support/H2IntegrationTest.kt`, `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/domain/Repositories.kt`.
- Risk: H2 cannot establish production lock, isolation, or migration behavior.
- Priority: High for changes to leases, claims, outbox ownership, and schema migrations.

**Kotlin packaged runtime:**
- What's not tested: The backend CI workflow runs unit and OpenSearch integration tasks, but does not build or start the image.
- Files: `.github/workflows/custom-kotlin-backend-checks.yml`, `_kotlin/backend/Dockerfile`.
- Risk: The Java version mismatch can survive those CI tasks.
- Priority: High.

**Kotlin upload failure cleanup:**
- What's not tested: Upload tests cover ZIP metadata, MIME variants, and size rejection; batch rollback file cleanup was not detected.
- Files: `_kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/api/AdminApiIntegrationTest.kt`, `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/service/FileStorageService.kt`.
- Risk: A rejected upload can consume persistent disk space without tracked asset rows.
- Priority: Medium.

---

*Concerns audit: 2026-09-09*
