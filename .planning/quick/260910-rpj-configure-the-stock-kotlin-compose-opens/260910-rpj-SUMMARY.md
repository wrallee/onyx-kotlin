---
phase: quick-opensearch-nori-mapping
plan: "260910-rpj"
subsystem: search
tags: [kotlin, opensearch, analysis-nori, docker-compose]
requires: []
provides:
  - Stock OpenSearch 3.6.0 Compose startup with analysis-nori
  - Complete dynamic-strict Kotlin mapping for new and existing indices
  - Non-destructive mapping updates with OpenSearch compatibility enforcement
affects: [kotlin-ingestion, opensearch-deployment, search-schema]
actuals:
  tokens: 14228
  tasks: 2
  commits: 2
plan_head_before: 64b112fedb7349649dc62f18ddf1c5b304faafc0
tech-stack:
  added: []
  patterns:
    - One complete mapping body for index creation and PUT _mapping
    - Stock container startup wrapper with a pinned official plugin archive
key-files:
  created: []
  modified:
    - _kotlin/docker-compose.yaml
    - _kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/OpenSearchIndexer.kt
    - _kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/ingestion/OpenSearchIndexerTest.kt
    - _kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/ingestion/OpenSearchIndexerIntegrationTest.kt
    - _kotlin/README.md
key-decisions:
  - "OpenSearch checks mapping compatibility; Kotlin does not migrate or delete index data."
  - "Compose installs analysis-nori into the stock container without an image build."
requirements-completed: [INDEX-01, INDEX-02, INDEX-03, BUILD-01]
coverage:
  - id: D1
    description: Complete strict Nori mapping for new and existing indices
    requirement: INDEX-01
    verification:
      - kind: unit
        ref: "./gradlew test --tests 'com.onyx.foss.kotlin.ingestion.OpenSearchIndexerTest' --no-daemon"
        status: pass
      - kind: integration
        ref: "./gradlew opensearchIntegrationTest --tests 'com.onyx.foss.kotlin.ingestion.OpenSearchIndexerIntegrationTest' --no-daemon"
        status: unknown
    human_judgment: true
    rationale: "The Docker integration test was skipped by explicit execution constraints."
  - id: D2
    description: Stock Compose OpenSearch starts with analysis-nori
    requirement: BUILD-01
    verification:
      - kind: integration
        ref: "Compose OpenSearch plugin-list and cluster-health checks"
        status: pass
    human_judgment: false
duration: 18min
completed: 2026-09-10
status: complete
---

# Quick Task 260910-rpj: OpenSearch Nori and Strict Mapping Summary

**Stock OpenSearch starts with Nori, while Kotlin applies one strict mapping without automatic data migration.**

## Accomplishments

- Added idempotent `analysis-nori` installation to the stock OpenSearch 3.6.0 service.
- Replaced mapping inspection and index migration with one complete `dynamic: strict` mapping.
- Removed replacement-index, reindex, migration write-block, count, and alias-swap behavior.
- Removed the obsolete migration retry and write-block exception path.
- Added request-shape and future integration coverage for compatible and incompatible mappings.
- Documented the external OpenSearch plugin prerequisite and non-destructive update boundary.

## Commits

1. `docs(quick-260910-rpj): plan OpenSearch Nori mapping`
2. `feat(kotlin): add strict Nori OpenSearch mappings`

## Checks Run

| Check | Result |
|---|---|
| `./gradlew test --tests 'com.onyx.foss.kotlin.ingestion.OpenSearchIndexerTest.new index uses the complete strict Nori mapping' --no-daemon` before implementation | Expected RED. One assertion failed because `dynamic: strict` was absent. RED evidence returned `RED_EVIDENCE_OK`. |
| `./gradlew compileTestKotlin --no-daemon` | Passed after integration-test changes. |
| `./gradlew clean compileKotlin compileTestKotlin --warning-mode all --no-daemon` | Passed. `BUILD SUCCESSFUL`. |
| `./gradlew test --tests 'com.onyx.foss.kotlin.ingestion.OpenSearchIndexerTest' --no-daemon` | Passed after implementation, fixture cleanup, and migration-retry removal. The final run completed in 41 seconds with `BUILD SUCCESSFUL`. |
| `docker compose -f _kotlin/docker-compose.yaml up -d --no-deps opensearch` | Passed for the final configuration. Only `opensearch` started. No image build ran. |
| `docker compose -f _kotlin/docker-compose.yaml exec -T opensearch /usr/share/opensearch/bin/opensearch-plugin list \| grep -Fx analysis-nori` | Passed. Output: `analysis-nori`. |
| `docker compose -f _kotlin/docker-compose.yaml exec -T opensearch sh -c 'curl -fkSs -u "admin:$OPENSEARCH_INITIAL_ADMIN_PASSWORD" "https://localhost:9200/_cluster/health?wait_for_status=yellow&timeout=90s"'` | Passed. Cluster status was `yellow`; `timed_out` was `false`. |
| `./gradlew opensearchIntegrationTest --tests 'com.onyx.foss.kotlin.ingestion.OpenSearchIndexerIntegrationTest' --no-daemon` | Skipped by explicit user constraint. No Testcontainers OpenSearch started. Integration test sources compiled successfully. |

## Runtime State

Only the existing Compose `opensearch` service remains running. It is healthy with `analysis-nori` installed.
No full Compose stack, image build, separate Testcontainers service, or Compose shutdown ran.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Preserved TLS verification for plugin download**

- The stock Java plugin downloader did not trust the managed TLS interception certificate.
- Compose now downloads the pinned official 3.6.0 archive with the host CA bundle.
- The OpenSearch plugin CLI installs the local archive in batch mode.
- No custom Dockerfile or Compose build section was added.

**2. [Rule 1 - Bug] Corrected the compatible integration fixture**

- The initial fixture omitted immutable `content` mapping parameters.
- The fixture now matches the complete mapping before it tests compatible field additions.

**3. [Code Review Correction] Removed the remaining migration retry path**

- Removed `OpenSearchWriteBlockedException` and `withMigrationRetry`.
- Each write path now calls `ensureIndex()` once before the direct operation.
- All non-success write responses now produce `IllegalStateException`.
- Verified with the focused non-Docker unit suite only.

### User-Directed Verification Change

- The Docker integration test was not run.
- Verification used focused unit tests, non-Docker compilation, and the Compose OpenSearch checks only.

## TDD Gate Compliance

- RED, GREEN, and refactor changes are preserved in the focused test and implementation diff.
- Status: Passed

## Known Stubs

None.

## Self-Check: PASSED

- The planning and implementation commits completed.
- All five declared files were included in the implementation commits.
- The required permitted checks passed.
- The skipped Docker integration test is recorded above.
- `STATE.md` was not updated. The orchestrator will update it.
- The unrelated `GithubConnectorLoader` files were not edited or committed by this task.
