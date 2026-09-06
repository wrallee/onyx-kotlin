# OpenSearch Communication Refactor to Spring AI & OpenSearch Java Client

## 1. Goal Description
Modernize OpenSearch communication in `_kotlin/backend` by removing `WebClient`, adopting Spring AI (`VectorStore`, RAG `DocumentJoiner`), using the official OpenSearch Java Client DSL, standardizing configuration under `spring.ai.vectorstore.opensearch`, and ensuring full connection stability, timeout guarantees, health checking, and CUD parity.

Before touching production code, the test architecture will be overhauled to decouple from fragile WebClient HTTP string matching, followed by an isolated commit.

---

## 2. Issues to Address
1. **Fragile WebClient-era Mock Tests**:
   - `OpenSearchIndexerTest` hardcoded request counts (`takeRequest()` 4 times) and exact raw query string formats (`_update_by_query?refresh=true&conflicts=proceed`), making it break when SDK abstractions format parameters or make internal mapping queries.
   - Minimal mock JSON responses lacked standard OpenSearch envelope fields (`_shards`, `_seq_no`, `_primary_term`, `batches`, `version_conflicts`).
2. **WebClient Misuse**:
   - OpenSearch calls were using reactive WebClient in a non-reactive (blocking) Spring MVC environment with hand-stitched JSON Maps.
3. **Missing Spring AI Abstractions**:
   - Vector search was not using Spring AI's `VectorStore`.
   - Custom hybrid search fusion (Score normalization + RRF) was not utilizing Spring AI RAG abstractions (`DocumentJoiner`, `DocumentRetriever`).
4. **Configuration Inconsistency**:
   - Configuration was under custom `onyx.opensearch.*` rather than Spring AI's standard `spring.ai.vectorstore.opensearch.*`.
5. **Kotlin-Jackson Serialization Mismatch**:
   - Kotlin data class constructors without Jackson Kotlin module cause camelCase/snake_case mismatches when transitioning from raw Maps to DTOs.

---

## 3. Implementation Strategy

### Phase 1: Test Infrastructure & Resiliency Improvement (Commit First)
1. **Spec-Compliant OpenSearch Mock Responses**:
   - Create helper fixtures in `OpenSearchIndexerTest.kt` returning realistic OpenSearch responses with full envelope fields:
     - `indexSuccessResponse`: `_index`, `_id`, `_version`, `result: "created"`, `_shards`, `_seq_no: 0`, `_primary_term: 1`.
     - `updateByQueryResponse`: `took`, `timed_out: false`, `total`, `updated`, `batches`, `version_conflicts: 0`, `noops`, `failures: []`.
     - `deleteByQueryResponse`: `took`, `timed_out: false`, `total`, `deleted`, `batches`, `version_conflicts: 0`, `failures: []`.
     - `acknowledgedResponse`: `acknowledged: true`, `shards_acknowledged: true`, `indices: []`.
     - `searchResponse`: `took`, `timed_out: false`, `_shards`, `hits: { total: { value, relation }, hits: [...] }`.
2. **Semantic Request Matching**:
   - Parse URI query parameters semantically using `request.requestUrl?.queryParameter(...)` rather than exact raw query string comparison (parameter order independence).
   - Support both OpenSearch scalar term queries (`{"term": {"field": val}}`) and object term queries (`{"term": {"field": {"value": val}}}`).
   - Retrieve operation requests by matching endpoint pattern (e.g. `findRecordedRequest(method, pathPredicate)`) rather than fixed call counts.
3. **Verification & Intermediate Commit**:
   - Run `./gradlew test` to ensure 100% of existing tests pass against the baseline.
   - **Commit**: `test(kotlin): improve opensearch test architecture and isolate contracts`.

---

### Phase 2: Main Refactor (Spring AI & OpenSearch Java Client)
1. **Dependency Updates (`build.gradle.kts`)**:
   - Add `platform("org.springframework.ai:spring-ai-bom:1.0.0")`.
   - Add `implementation("org.springframework.ai:spring-ai-opensearch-store")`.
   - Add `implementation("org.springframework.ai:spring-ai-rag")`.
   - Add `implementation("com.fasterxml.jackson.module:jackson-module-kotlin")` (ensures Jackson 2 in OpenSearch client recognizes Kotlin data classes natively).
2. **Configuration Standardization**:
   - Create `OpenSearchVectorStoreProperties.kt` binding `@ConfigurationProperties("spring.ai.vectorstore.opensearch")` with fallback to `onyx.opensearch.*`.
   - Update `application.yml` with `spring.ai.vectorstore.opensearch` standard settings.
3. **Client Factory & Connection Management**:
   - Create `OpenSearchClientFactory.kt` and `OpenSearchConfiguration.kt`.
   - Configure Apache HttpClient5 with:
     - 30s connect and socket timeouts.
     - SSL trust-all support when `verifyCerts = false`.
     - Basic authentication.
     - `JacksonJsonpMapper` registered with `KotlinModule`.
4. **DTO & Spring AI VectorStore**:
   - Create `OpenSearchChunkDocument.kt` with `@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)` and Spring AI `Document` conversion.
   - Implement `OnyxOpenSearchVectorStore.kt` implementing Spring AI `VectorStore` using OpenSearch Java Client KNN queries.
5. **Spring AI RAG Hybrid Search Fusion**:
   - Create `HybridFusionJoiner.kt` implementing Spring AI `DocumentJoiner` (`ScoreNormalizationDocumentJoiner`, `ReciprocalRankFusionDocumentJoiner`).
   - Create `OpenSearchHybridDocumentRetriever.kt` implementing Spring AI `DocumentRetriever`.
   - Update `SearchService.kt` to delegate fusion to `ReciprocalRankFusionDocumentJoiner`.
6. **OpenSearch Indexer DSL**:
   - Refactor `OpenSearchIndexer.kt` from WebClient to OpenSearch Java Client DSL:
     - `searchCandidates`: Keyword query via bool query DSL, Vector query via KNN DSL.
     - `chunksInRange`: Search sorted by `chunk_id`.
     - `upsert`: Strongly-typed `IndexRequest` with `OpenSearchChunkDocument`.
     - `deletePair`, `deleteDocuments`, `deleteStaleChunks`: `DeleteByQueryRequest` DSL.
     - `updateDocumentSets`: `UpdateByQueryRequest` DSL with inline Painless script.
     - `ensureIndex`, mapping validation, write-block migration retries.
     - `ping()` and `clusterHealth()` via OpenSearch client.
7. **Clean up WebClient**:
   - Remove WebClient references from OpenSearch code paths (retained only in `ModelServerClient`).

---

### Phase 3: Verification & Final Commit
1. Run `./gradlew test` across all backend test suites.
2. Run `./gradlew test --tests "*OpenSearchIndexerIntegrationTest*"` with Docker container.
3. Review ASD-STE100 compliance, strict typing, and produce clean final commit.

---

## 4. Verification Plan

### Automated Tests
- `./gradlew test --tests "com.onyx.foss.kotlin.ingestion.OpenSearchIndexerTest"` (Unit tests with MockWebServer).
- `./gradlew test --tests "com.onyx.foss.kotlin.service.SearchServiceTest"` (Hybrid search & fusion).
- `./gradlew test --tests "com.onyx.foss.kotlin.mcp.McpSearchToolTest"` (MCP search tools).
- `./gradlew test --tests "com.onyx.foss.kotlin.ingestion.OpenSearchIndexerIntegrationTest"` (Testcontainers integration).
- `./gradlew test` (Full backend test suite).
