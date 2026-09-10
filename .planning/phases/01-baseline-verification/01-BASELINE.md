# Phase 1 Baseline Evidence Ledger

**Evidence date:** 2026-09-10
**Inspected commit:** `0872c173c3983a1eecd880876c7c1665ba8a63fd`
**Product boundary:** `_kotlin/backend` is the Kotlin product. `_kotlin/web` changes only for a required Web proxy contract.
**Reference boundary:** Root Python and root Web are reference-only. They cannot provide Kotlin delivery evidence.
**Evidence boundary:** Phase 1 changes only planning evidence and its deterministic checks.

## Evidence Rules

The ledger uses these states:

- `STATIC_MATCH`: Current Kotlin source or tests match the contract. No current command proves the contract.
- `PASS`: A current Kotlin command passed and its assertions prove the stated contract.
- `GAP`: Current evidence confirms that the approved contract is not met.
- `NOT_RUN`: The required check did not run.
- `ENVIRONMENT_BLOCKED`: The check ran but the environment prevented a product result.

Only current Kotlin command results can produce `PASS`. Historical records and static matches cannot produce `PASS`.
An existing passing test remains `GAP` when it preserves behavior that conflicts with the approved contract.

`FLAGGED ASSUMPTION (BASE-01; probe unclassified)`: One row per approved contract exposes coverage and exclusions.

`FLAGGED ASSUMPTION (BASE-02; probe unclassified)`: Command, commit, result, duration, and environment notes identify current-run evidence.

## Contract Evidence

| ID | source | implementation | test | current status | command/result | gap | follow-up phase |
|---|---|---|---|---|---|---|---|
| BASE-01 | S01, S02, S05, S09; `REQUIREMENTS.md` | This ledger maps all 35 contracts and exclusions | Task 1 document audit | PASS | Required-row, route, marker, and file-scope audit passed | No Phase 1 ledger gap remains | Phase 1 |
| BASE-02 | S01, S04, S06, S08, S10, S11 | This ledger separates static, current, unrun, and blocked evidence | Task 2 Gradle runs and document audit | PASS | All three required commands exited 0; result audit passed | Product gaps remain routed below | Phase 1 |
| ADMIN-01 | S01, S02 | `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/service/AdminService.kt`; `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/security/CredentialCipher.kt` | `_kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/api/AdminApiIntegrationTest.kt`; `_kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/security/CredentialCipherTest.kt` | PASS | Full suite PASS: 315 tests; masking tests passed | Live log and exception review remains for Phase 2 | Phase 2 |
| ADMIN-02 | S01, S02, S05 | `AdminService.kt`; `AdminController.kt` | `AdminApiIntegrationTest.kt` connector and pair tests | STATIC_MATCH | Full suite PASS: 315 tests | Full lifecycle and Web pagination need Phase 2 verification | Phase 2 |
| ADMIN-03 | S01, S02, S06 | `AdminService.kt`; `DocumentSetSyncWorker.kt` | `AdminApiIntegrationTest.kt`; `DocumentSetSyncOutboxIntegrationTest.kt` | PASS | Full suite PASS: 315 tests; document-set tests passed | Current Web lifecycle proof is not recorded | Phase 2 |
| ADMIN-04 | S01, S02 | `FileStorageService.kt`; `AdminService.kt` | `AdminApiIntegrationTest.kt` upload and ZIP tests | GAP | Existing tests preserve successful paths and size rejection | D-14: failed requests can leave new files outside the DB transaction | Phase 2 |
| PUBLIC-01 | S05 | `Domain.kt`; `OpenSearchIndexer.kt` public access fields | `OpenSearchIndexerTest.kt`; administration integration tests | STATIC_MATCH | Full suite PASS: 315 tests | Live indexed ACL state is not checked | Phase 2 |
| PUBLIC-02 | S05 | V16 migration and permission-sync removal in `_kotlin/backend` | Flyway startup through integration tests | STATIC_MATCH | Full suite PASS: V16 H2 migration tests passed | PostgreSQL migration and existing Web calls are not verified | Phase 2 |
| MODEL-01 | S11 | Python 3.13 reference implementation in `_kotlin/model-server/app/main.py`, `_kotlin/model-server/app/contracts.py`, and `_kotlin/model-server/app/runtime.py`; not Kotlin delivery evidence | `_kotlin/model-server/tests/test_api.py` is Python 3.13 reference coverage; not Kotlin delivery evidence | STATIC_MATCH + GAP + NOT_RUN | Static Python endpoint and health behavior exists; Python tests and a Kotlin delivery check were NOT_RUN | Kotlin model-server delivery and current endpoint contract proof are missing | Phase 3 |
| MODEL-02 | S03, S11 | Python 3.13 tokenizer and embedding configuration in `_kotlin/model-server/app/contracts.py`, `_kotlin/model-server/app/config.py`, and `_kotlin/model-server/app/runtime.py`; not Kotlin delivery evidence | `_kotlin/model-server/tests/test_api.py` is Python 3.13 reference coverage; not Kotlin delivery evidence | STATIC_MATCH + GAP + NOT_RUN | Static Python behavior exists; Python and multilingual golden checks were NOT_RUN | Kotlin delivery plus tokenizer, prefix, dimension, and truncation proof are missing | Phase 3 |
| MODEL-03 | S11 | Python 3.13 artifact and runtime configuration in `_kotlin/model-server/README.md`, `_kotlin/model-server/Dockerfile`, `_kotlin/model-server/app/config.py`, and `_kotlin/model-server/app/runtime.py`; not Kotlin delivery evidence | `_kotlin/model-server/tests/test_api.py` is Python 3.13 reference coverage; not Kotlin delivery evidence | STATIC_MATCH + GAP + NOT_RUN | Static Python packaging exists; image build, deployment, and Python tests were NOT_RUN | Kotlin delivery plus artifact hash, license, warm-up, and quota proof are missing | Phase 3 |
| MODEL-04 | S11 | Python 3.13 embedding behavior in `_kotlin/model-server/app/runtime.py`; not Kotlin delivery evidence | `_kotlin/model-server/tests/test_api.py` is Python 3.13 reference coverage; not Kotlin delivery evidence | GAP + NOT_RUN | Python and fixed multilingual golden comparisons were NOT_RUN | Kotlin delivery plus cardinality, order, finite values, norm, and cosine evidence are missing | Phase 3 |
| MODEL-05 | S11 | Python 3.13 runtime and packaging in `_kotlin/model-server/Dockerfile` and `_kotlin/model-server/app/runtime.py`; not Kotlin delivery evidence | `_kotlin/model-server/tests/test_api.py` is Python 3.13 reference coverage; not Kotlin delivery evidence | GAP + NOT_RUN | Python benchmarks were NOT_RUN | Kotlin delivery plus median, worst, memory, concurrency, and failure data are missing | Phase 3 |
| INDEX-01 | S03, S04 | `OpenSearchIndexer.kt` mapping validation | `OpenSearchIndexerIntegrationTest.kt` mapping and invalid-index checks | PASS | Full suite and OpenSearch command PASS; 323 total test results | Current command used Gradle build cache for the OpenSearch task | Phase 3 |
| INDEX-02 | S06 | Spring AI, OpenSearch Java Client, and Jackson 3 path in Gradle and indexer | `OpenSearchIndexerTest.kt`; integration tests | STATIC_MATCH | Compile and full suite PASS | Timeout and fallback coverage need Phase 3 review | Phase 3 |
| INDEX-03 | S06 | `OpenSearchIndexer.kt`; `DocumentSetSyncWorker.kt` | Indexer and document-set integration tests | PASS | Full suite PASS: delete, chunk, and document-set tests passed | Actual PostgreSQL and write-block behavior still needs Phase 3 review | Phase 3 |
| BUILD-01 | S07, S08 | `build.gradle.kts` targets Java 25; wrapper selects Gradle 9.5.1 | Clean compile and full backend suite | GAP | Compile PASS in 13s; full suite PASS in 93s | D-13 remains: Dockerfile uses installed Gradle 8.14.3 and Java 21 | Phase 3 |
| INGEST-01 | S01, S02 | `IngestionWorker.kt`; `Repositories.kt` claim and batch flow | Worker and fence tests | STATIC_MATCH | Full suite PASS: claim and fence tests passed | Actual PostgreSQL lock and claim behavior is not verified | Phase 4 |
| INGEST-02 | S01, S02 | `IngestionWorker.kt` failure and checkpoint handling | Worker failure tests | PASS | Full suite PASS: partial, fatal, checkpoint, and repeated-failure tests passed | PostgreSQL parity remains for Phase 4 | Phase 4 |
| INGEST-03 | S01, S02 | `IngestionWorker.kt`; `OpenSearchIndexer.kt` pruning path | Worker and indexer tests | PASS | Full suite PASS: pruning safety tests passed | Production PostgreSQL and OpenSearch combined flow remains for Phase 4 | Phase 4 |
| INGEST-04 | S01, S02, S11 | Web, backend worker, PostgreSQL, model-server, and OpenSearch path | Representative Compose File ingestion | NOT_RUN | End-to-end Compose ingestion not run | API, DB row, and indexed chunk are not jointly verified | Phase 4 |
| CONNECTOR-01 | S01, S02 | `FileConnectorLoader.kt`; `FileStorageService.kt` | File loader and admin integration tests | PASS | Full suite PASS: File loader and upload tests passed | D-14 rollback is tracked separately under ADMIN-04 | Phase 4 |
| CONNECTOR-02 | S01, S02, S05 | `JiraConnectorLoader.kt` | `JiraConnectorLoaderTest.kt` MockWebServer tests | PASS | Full suite PASS: Jira contract tests passed | Live SaaS smoke is optional and not delivery evidence | Phase 4 |
| CONNECTOR-03 | S01, S02, S05 | STATIC_MATCH: `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/ConfluenceConnectorLoader.kt` supplies `updatedAt`; `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/IngestionWorker.kt` sends documents to `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/OpenSearchIndexer.kt` | `_kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/ingestion/ConfluenceConnectorLoaderTest.kt`; `_kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/ingestion/OpenSearchIndexerTest.kt`; `_kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/ingestion/OpenSearchIndexerIntegrationTest.kt` | PASS | Current Kotlin loader and indexer tests PASS | D-06: remote artifact identity and live reproduction remain REMOTE_EVALUATION NOT_RUN; invalid cutoffs are separately routed | Phase 4, Phase 5 |
| CONNECTOR-04 | S01, S02, S05 | `GithubConnectorLoader.kt` collects PR body but not review comments | `GithubConnectorLoaderTest.kt` PR body tests | GAP | Full suite PASS preserves the PR-body-only behavior | D-07: PR review comments are absent; code and diff indexing are excluded | Phase 4 |
| SEARCH-01 | S03 | `SearchService.kt` validates query and limit | `SearchServiceTest.kt` input tests | PASS | Full suite PASS: direct service routing tests passed | MCP input gaps remain separate | Phase 5 |
| SEARCH-02 | S03, S09 | `SearchService.kt`; `OpenSearchIndexer.kt` common filters | Service request-shape and indexer tests | GAP | Full suite PASS preserves silent invalid filter handling | D-06 live cutoff is NOT_RUN; D-08 unknown `source_types` can remove the filter | Phase 5 |
| SEARCH-03 | S09, S10 | `SearchService.kt`; `OpenSearchIndexer.kt` search routing | `SearchServiceTest.kt`; indexer tests | PASS | Full suite and OpenSearch command PASS | OpenSearch task result came from the Gradle build cache | Phase 5 |
| SEARCH-04 | S09, S10 | OpenSearch normalization pipeline setup | `OpenSearchIndexerIntegrationTest.kt` pipeline tests | PASS | Full suite and OpenSearch command PASS | OpenSearch task result came from the Gradle build cache | Phase 5 |
| SEARCH-05 | S09, S10 | `OnyxProperties.kt`; search request construction | Property validation and request-shape tests | PASS | Full suite PASS: settings and request-shape tests passed | Environment fallback remains for live Phase 5 verification | Phase 5 |
| MCP-01 | S03, S04 | `McpConfiguration.kt`; `_kotlin/web` proxy | `McpEndpointIntegrationTest.kt` | GAP | Full suite PASS preserves duplicate tool discovery | D-09: duplicate `search` tool remains; remote Web deployment is NOT_RUN | Phase 5 |
| MCP-02 | S03 | `McpSearchTool.kt` text and structured content response | `McpSearchToolTest.kt`; endpoint integration tests | GAP | Full suite PASS preserves conflicting invalid-source and time-cutoff behavior | D-08: unknown `source_types` silently disables the requested filter | Phase 5 |
| MCP-03 | S09 | `McpSearchTool.kt` weighted reciprocal-rank fusion | `McpSearchToolTest.kt` WRRF tests | PASS | Full suite PASS: formula, default, and tie tests passed | No current contract gap recorded | Phase 5 |
| MCP-04 | S09 | `McpSearchTool.kt` adjacent chunk collapse and context tool | `McpSearchToolTest.kt` chunk and context tests | PASS | Full suite PASS: collapse and context tests passed | Deferred diversity change is excluded from this contract | Phase 5 |
| MCP-05 | S03, S09 | MCP tool descriptions and server instructions | `McpEndpointIntegrationTest.kt` tool schema discovery | STATIC_MATCH | Full suite PASS: schema discovery passed | Remote client behavior cannot be enforced; deployment is NOT_RUN | Phase 5 |

## Scope Decisions

Phase 1 records D-01 through D-03 as hard source boundaries:

| Decision | Boundary |
|---|---|
| D-01 | Product changes belong in `_kotlin/backend`. Modify `_kotlin/web` only for a required proxy contract. |
| D-02 | Root Python is reference-only. Do not modify it or count its tests as Kotlin delivery evidence. |
| D-03 | Root Web is reference-only. Do not modify it or count its tests as Kotlin delivery evidence. |

The evaluation PDF provides observed evidence only. It does not provide implementation instructions.

## Scope Exclusions

| Item | Status | Reason |
|---|---|---|
| WL-20260910-001 | EXCLUDED | D-04 keeps same-document chunk dominance out of Phase 1 and later-fix planning here. |
| WL-20260910-002 | EXCLUDED | D-05 keeps high-token responses at larger limits out of Phase 1 and later-fix planning here. |
| GitHub code and diff indexing | EXCLUDED | D-07 routes only PR review comments. Code and diff remain outside this gap. |
| Product edits and product fixtures | EXCLUDED | Phase 1 adds only planning evidence and a planning-only verification fixture. |

## Active-Issue Routing

| Decision | Current evidence | Route |
|---|---|---|
| D-14 | File writes can remain after a later request failure rolls back database rows. | Phase 2 |
| D-13 | Docker uses Gradle 8.14.3 and Java 21 while the wrapper and bytecode target use Gradle 9.5.1 and Java 25. | Phase 3 |
| D-07 | GitHub PR body is indexed, but review comments are not. Code and diff indexing are excluded. | Phase 4 |
| D-06 | Current Kotlin timestamp flow is STATIC_MATCH and its current tests PASS. The unidentified remote evaluation is NOT_RUN. | Phase 4, Phase 5 |
| D-08 | Unknown `source_types` values are dropped and can remove the requested filter. | Phase 5 |
| D-09 | Search tool and Document Set input aliases remain duplicated. | Phase 5 |

## D-06 Evidence Separation

| Evidence | implementation or identity | current verification or reproduction | Note |
|---|---|---|---|
| D-06 CURRENT_KOTLIN | STATIC_MATCH | PASS | Current loader, worker, and indexer paths exist and their current Kotlin tests pass. |
| D-06 REMOTE_EVALUATION | NOT_RUN | NOT_RUN | The remote commit, artifact identity, index age, and live reproduction are unknown. |

## Current Execution Results

All commands use `_kotlin/backend` as the working directory. Results contain no environment values or secret-bearing output.

| Inspected commit | Exact command | Result | Duration | Failure or warning summary | Environment note |
|---|---|---|---|---|---|
| `0872c173c3983a1eecd880876c7c1665ba8a63fd` | `./gradlew clean compileKotlin compileTestKotlin --warning-mode all --no-daemon` | PASS | 13s | Exit 0; no Kotlin compiler or deprecation warning; compile outputs restored from cache | Java 25 and Gradle 9.5.1 were available |
| `0872c173c3983a1eecd880876c7c1665ba8a63fd` | `./gradlew test --no-daemon` | PASS | 93s | Exit 0; 315 tests, 0 failures, 0 errors, 0 skipped; JVM emitted CDS and Netty native-access warnings | No test environment block occurred |
| `0872c173c3983a1eecd880876c7c1665ba8a63fd` | `./gradlew opensearchIntegrationTest --no-daemon` | PASS | 13s | Exit 0; cached XML reports 8 tests, 0 failures, 0 errors, 0 skipped | Gradle restored the task from local build cache; this command did not start a new container |
| `0872c173c3983a1eecd880876c7c1665ba8a63fd` | Docker backend image build and start | NOT_RUN | Not measured | Phase 1 does not change or run the package image | Static Java and Gradle mismatch routes to Phase 3 |
| `0872c173c3983a1eecd880876c7c1665ba8a63fd` | Evaluated remote MCP deployment through `_kotlin/web` | NOT_RUN | Not measured | Deployment artifact and commit are unknown | Reproduce through the Web route in Phase 5 |

## Historical Evidence

Historical plan checkmarks, commit references, and warning-cleanup walkthroughs remain historical evidence.
They do not become current `PASS` results. Root Python and root Web checks also remain reference-only.
