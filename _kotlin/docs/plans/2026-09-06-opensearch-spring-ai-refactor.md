# OpenSearch Communication Refactor: Spring AI & OpenSearch Java Client (Jackson 3 Native)

## 1. Goal Description
Modernize OpenSearch communication in `_kotlin/backend` by removing `WebClient`, adopting Spring AI (`VectorStore`, RAG `DocumentJoiner`), using the official OpenSearch Java Client DSL, standardizing configuration under `spring.ai.vectorstore.opensearch.*`, and ensuring native Jackson 3 (`tools.jackson.*`) integration without any Jackson 2 dependencies.

Phase 1 (test fixture improvements in `OpenSearchIndexerTest.kt`) has been completed, verified 100% green, and committed (`4bfb2b936`). This plan details the execution of Phase 2 (production refactoring) and Phase 3 (verification).

---

## 2. Issues to Address
1. **Elimination of Jackson 2 Artifacts**:
   - The project runs on Spring Boot 4 (`4.0.7`), which natively uses Jackson 3 (`tools.jackson.*`).
   - Introducing `com.fasterxml.jackson.module:jackson-module-kotlin` (Jackson 2) violated the architecture and framework standard.
   - Solution: Use `opensearch-java:3.10.0` which natively provides `org.opensearch.client.json.jackson3.JacksonJsonpMapper(tools.jackson.databind.ObjectMapper)`, leveraging the already configured `tools.jackson.module:jackson-module-kotlin:3.1.4`.
2. **WebClient Misuse in OpenSearch Layer**:
   - OpenSearch calls were using reactive WebClient inside a non-reactive (blocking) Spring MVC thread pool with manually stitched JSON string maps.
3. **Missing Spring AI Abstractions**:
   - Vector search was not exposed via Spring AI's `VectorStore` abstraction.
   - Hybrid search fusion (min-max score normalization + RRF) was implemented as bespoke private methods rather than reusable Spring AI RAG components (`DocumentJoiner`, `DocumentRetriever`).
4. **Configuration Standardization**:
   - Configuration was under custom `onyx.opensearch.*` instead of standard `spring.ai.vectorstore.opensearch.*`.
5. **Operational Parity**:
   - Maintain 30s connection/socket timeouts, 10-minute migration timeouts, write-block retry policies, SSL certificate verification bypass (`verifyCerts = false`), cluster health checks (`ping`, `cluster.health`), and full CUD operation semantics.

---

## 3. Important Notes
- **Jackson 3 Integration**: `opensearch-java:3.10.0` provides `org.opensearch.client.json.jackson3.JacksonJsonpMapper`. Passing Spring Boot's Jackson 3 `ObjectMapper` directly into this mapper guarantees Kotlin data class serialization and deserialization via `tools.jackson.module:jackson-module-kotlin`.
- **OpenSearchChunkDocument DTO**: Uses `tools.jackson.databind.annotation.JsonNaming(tools.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy::class)` and `tools.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)`. Clean Kotlin data class without duplicate target annotations.
- **Spring AI Compatibility**: `spring-ai-vector-store:1.0.0` and `spring-ai-rag:1.0.0` provide the core abstractions (`VectorStore`, `Document`, `DocumentJoiner`, `DocumentRetriever`). `OnyxOpenSearchVectorStore` directly implements `VectorStore`, preserving Onyx multi-tenancy, ACL filtering, and custom payload attributes.
- **Test Baseline**: Phase 1 is committed (`4bfb2b936`). MockWebServer response envelopes in `OpenSearchIndexerTest.kt` already contain standard OpenSearch fields (`_shards`, `_seq_no`, `_primary_term`, `took`, `timed_out`, `total`).

---

## 4. Implementation Strategy

### Step 1: Dependency Definition (`build.gradle.kts`)
- Add `platform("org.springframework.ai:spring-ai-bom:1.0.0")`.
- Add `implementation("org.springframework.ai:spring-ai-vector-store")`.
- Add `implementation("org.springframework.ai:spring-ai-rag")`.
- Add `implementation("org.opensearch.client:opensearch-java:3.10.0")`.
- Strictly omit `com.fasterxml.jackson.module:jackson-module-kotlin` (rely exclusively on `tools.jackson.module:jackson-module-kotlin`).

### Step 2: Configuration Standardization
- Create `com.onyx.kotlin.opensearch.OpenSearchVectorStoreProperties`:
  - Bind `@ConfigurationProperties("spring.ai.vectorstore.opensearch")`.
  - Fallback to `onyx.opensearch.*` if `spring.ai.vectorstore.opensearch.*` is unset.
  - Properties: `uris`, `indexName`, `username`, `password`, `ssl.verifyCerts`.
- Update `application.yml` with standard `spring.ai.vectorstore.opensearch.*` keys.

### Step 3: OpenSearch Client Factory with Jackson 3
- Create `com.onyx.kotlin.opensearch.OpenSearchClientFactory`:
  - Configure `ApacheHttpClient5TransportBuilder` with `org.opensearch.client.json.jackson3.JacksonJsonpMapper(objectMapper)`, accepting `tools.jackson.databind.ObjectMapper`.
  - Set connect timeout (30s) and socket/response timeout (30s).
  - Configure SSL context: bypass certificate verification when `verifyCerts == false`.
  - Configure basic credentials provider if username/password present.
- Create `com.onyx.kotlin.opensearch.OpenSearchConfiguration`:
  - Define beans: `OpenSearchVectorStoreProperties`, `OpenSearchClient` (injecting Spring's Jackson 3 `ObjectMapper`), and `VectorStore`.

### Step 4: DTO & Spring AI VectorStore
- Create `com.onyx.kotlin.opensearch.OpenSearchChunkDocument`:
  - Pure Kotlin data class with `@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)`.
  - Helper functions: `toSpringAiDocument(id, score)` and `fromSpringAiDocument(doc)`.
- Create `com.onyx.kotlin.opensearch.OnyxOpenSearchVectorStore`:
  - Implement `org.springframework.ai.vectorstore.VectorStore`.
  - Implement `similaritySearch` using `opensearch-java:3.10.0`'s `KnnQuery` builder (`field("embedding")`, `vector(floatVector)`, `k(count)`).
  - Translate document filters to OpenSearch query DSL.

### Step 5: Spring AI RAG Hybrid Search Fusion
- Create `com.onyx.kotlin.service.HybridFusionJoiner`:
  - `ScoreNormalizationDocumentJoiner`: Implements `DocumentJoiner` for min-max score normalization.
  - `ReciprocalRankFusionDocumentJoiner`: Implements `DocumentJoiner` for RRF score fusion ($1 / (60 + \text{rank})$).
- Create `com.onyx.kotlin.service.OpenSearchHybridDocumentRetriever`:
  - Implements `DocumentRetriever` combining keyword and vector queries via Spring AI Joiner.
- Update `SearchService.kt`:
  - Delegate hybrid fusion to `ReciprocalRankFusionDocumentJoiner`.

### Step 6: OpenSearchIndexer DSL Implementation
- Migrate `com.onyx.kotlin.ingestion.OpenSearchIndexer`:
  - Remove all `WebClient` fields and references.
  - Provide secondary constructor matching test harness `(OnyxProperties, Any?, ObjectMapper, PairExternalWriteFence)` for backwards compatibility.
  - Implement CUD operations via `OpenSearchClient` typed DSL:
    - `upsert`: `client.index(...)` with `OpenSearchChunkDocument`.
    - `searchCandidates`: Keyword query via `BoolQuery`, Vector query via `KnnQuery`.
    - `chunksInRange`: Search query sorted by `chunk_id`.
    - `deletePair`, `deleteDocuments`, `deleteStaleChunks`: `client.deleteByQuery(...)`.
    - `updateDocumentSets`: `client.updateByQuery(...)` with inline Painless script.
    - `ensureIndex`, mapping validation, write-block migration retries.
    - `ping()` and `clusterHealth()` via OpenSearch client.

### Step 7: WebClient Clean Up
- Verify that `WebClient` is removed from OpenSearch code paths, retained only for `ModelServerClient`.

---

## 5. Tests & Verification

### Automated Tests
1. **Unit Tests**:
   - `./gradlew test --tests "com.onyx.kotlin.ingestion.OpenSearchIndexerTest"` (14 MockWebServer tests verifying CUD, search, mapping, migration retries).
2. **Search Service Tests**:
   - `./gradlew test --tests "com.onyx.kotlin.service.SearchServiceTest"` (Hybrid search and RRF fusion).
3. **MCP Tool Tests**:
   - `./gradlew test --tests "com.onyx.kotlin.mcp.McpSearchToolTest"`.
4. **Integration Tests**:
   - `./gradlew opensearchIntegrationTest` (runs `OpenSearchIndexerIntegrationTest` against real OpenSearch container).
5. **Full Suite**:
   - `./gradlew test` (verifies entire backend test suite).
