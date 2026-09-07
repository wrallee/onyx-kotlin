# OpenSearch Native Hybrid Retrieval Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move BM25/vector fusion to OpenSearch native hybrid search, use a 200-candidate fusion depth without increasing MCP result size, eliminate unnecessary retrieval work by search type, isolate optional z-score handling from the default typed min-max path, and keep query-level MCP WRRF as equal-weight fusion with adjacent-chunk diversity.

**Architecture:** Preserve Spring AI `VectorStore`/RAG abstractions from PR #19. `KEYWORD` executes BM25 only, `SEMANTIC` executes k-NN only, and `HYBRID` executes one typed OpenSearch hybrid query. Two normalization-pipeline implementations exist: typed `MinMaxNormalizationPipeline` and isolated generic `ZScoreNormalizationPipeline`. `HybridNormalizationPipelineRegistry` initializes both as part of OpenSearch search-infrastructure readiness and only selects a pipeline ID at query time. Query-level multi-search fusion remains application-level WRRF.

**Tech Stack:** Kotlin 2.3.20, Spring Boot 4.0.7, Spring AI 1.0.0, OpenSearch Java Client 3.10.0, OpenSearch 3.6.0, JUnit 5, Mockito, AssertJ, MockWebServer, Testcontainers.

**Spec:** `_kotlin/docs/specs/2026-09-07-opensearch-native-hybrid-retrieval-design.md`

## Global Constraints

- Base branch: `refactor/opensearch-spring-ai` (PR #19). Implementation branch: `feat/opensearch-native-hybrid`.
- Keep Spring AI and `OnyxOpenSearchVectorStore`.
- Default hybrid candidate depth: exactly `200`.
- Final MCP `limit`: unchanged, `1..20`; candidate depth must not enlarge the returned payload.
- Default normalization: `min_max`; optional `z_score`.
- Default keyword/vector weights: `0.5 / 0.5`.
- Default WRRF `k`: `50`; omitted result-list weights: `1.0` each.
- Create both normalization pipelines as one OpenSearch readiness unit, matching current Onyx behavior.
- `min_max` must use `OpenSearchClient.searchPipeline().put(...)` and must not use `generic()`.
- `z_score` is the only normalization-pipeline path allowed to use `OpenSearchClient.generic()` because Java Client 3.10.0 cannot represent it in `ScoreNormalizationTechnique`.
- No Kotlin implementation of min-max or z-score math.
- No reranker, server-side LLM query expansion, OpenSearch RRF for BM25/vector fusion, or Onyx `1.3/1.0/0.7/0.5` WRRF defaults.
- `KEYWORD` must work when the embedding/model server is unavailable.
- Hybrid filters are shared across both hybrid subqueries.
- Preserve Jackson 3 (`tools.jackson.*`).
- Every task follows RED → GREEN → focused verification → commit.

---

### Task 1: Separate and validate retrieval configuration

**Files:**
- Create: `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/config/SearchProperties.kt`
- Modify: `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/config/RuntimeConfiguration.kt`
- Modify: `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/config/OnyxProperties.kt`
- Modify: `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/service/SearchService.kt`
- Modify: `_kotlin/backend/src/main/resources/application.yml`
- Modify: `_kotlin/docker-compose.yaml`
- Modify: `_kotlin/.env.example`
- Create: `_kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/config/SearchPropertiesTest.kt`
- Modify: `_kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/service/SearchServiceTest.kt`

**Interfaces:**

```kotlin
@ConfigurationProperties("onyx.search")
data class SearchProperties(
    val hybridCandidates: Int = 200,
    val hybridNormalization: String = "min_max",
    val keywordWeight: Double = 0.5,
    val vectorWeight: Double = 0.5,
    val rrfK: Int = 50,
)
```

- Removes `OnyxProperties.ModelServer.searchCandidates`.
- Preserves `ONYX_SEARCH_CANDIDATES`, now mapped to `onyx.search.hybrid-candidates`.
- `SearchService` receives `SearchProperties`; until Task 3 it uses `searchProperties.hybridCandidates` with the old retrieval path so Task 1 remains independently compilable.

- [ ] **Step 1: Write failing configuration tests**

Create `SearchPropertiesTest.kt`:

```kotlin
package com.onyx.foss.kotlin.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration

class SearchPropertiesTest {
    private val runner = ApplicationContextRunner()
        .withUserConfiguration(TestConfig::class.java)

    @Test
    fun `defaults match retrieval design`() {
        runner.run { context ->
            val p = context.getBean(SearchProperties::class.java)
            assertThat(p.hybridCandidates).isEqualTo(200)
            assertThat(p.hybridNormalization).isEqualTo("min_max")
            assertThat(p.keywordWeight).isEqualTo(0.5)
            assertThat(p.vectorWeight).isEqualTo(0.5)
            assertThat(p.rrfK).isEqualTo(50)
        }
    }

    @Test
    fun `z score and custom weights bind`() {
        runner.withPropertyValues(
            "onyx.search.hybrid-candidates=320",
            "onyx.search.hybrid-normalization=z_score",
            "onyx.search.keyword-weight=0.4",
            "onyx.search.vector-weight=0.6",
            "onyx.search.rrf-k=60",
        ).run { context ->
            val p = context.getBean(SearchProperties::class.java)
            assertThat(p.hybridCandidates).isEqualTo(320)
            assertThat(p.hybridNormalization).isEqualTo("z_score")
            assertThat(p.keywordWeight).isEqualTo(0.4)
            assertThat(p.vectorWeight).isEqualTo(0.6)
            assertThat(p.rrfK).isEqualTo(60)
        }
    }

    @Test
    fun `unsupported normalization fails startup`() {
        runner.withPropertyValues("onyx.search.hybrid-normalization=rank_magic")
            .run { assertThat(it).hasFailed() }
    }

    @Test
    fun `weights must be nonnegative and sum to one`() {
        runner.withPropertyValues(
            "onyx.search.keyword-weight=0.8",
            "onyx.search.vector-weight=0.8",
        ).run { assertThat(it).hasFailed() }
    }

    @Test
    fun `candidate depth and rrf k must be positive`() {
        runner.withPropertyValues(
            "onyx.search.hybrid-candidates=0",
            "onyx.search.rrf-k=0",
        ).run { assertThat(it).hasFailed() }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SearchProperties::class)
    class TestConfig
}
```

- [ ] **Step 2: Run RED**

```bash
./gradlew test --tests "com.onyx.foss.kotlin.config.SearchPropertiesTest"
```

Expected: compilation fails because `SearchProperties` does not exist.

- [ ] **Step 3: Implement validated `SearchProperties`**

```kotlin
package com.onyx.foss.kotlin.config

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import kotlin.math.abs

@Validated
@ConfigurationProperties("onyx.search")
data class SearchProperties(
    @field:Min(1)
    @field:Max(10_000)
    val hybridCandidates: Int = 200,
    val hybridNormalization: String = "min_max",
    val keywordWeight: Double = 0.5,
    val vectorWeight: Double = 0.5,
    @field:Min(1)
    val rrfK: Int = 50,
) {
    init {
        require(hybridNormalization in setOf("min_max", "z_score")) {
            "onyx.search.hybrid-normalization must be min_max or z_score"
        }
        require(keywordWeight >= 0.0 && vectorWeight >= 0.0) {
            "onyx.search keyword/vector weights must be non-negative"
        }
        require(abs(keywordWeight + vectorWeight - 1.0) <= 1e-9) {
            "onyx.search keyword-weight + vector-weight must equal 1.0"
        }
    }
}
```

Enable it in `RuntimeConfiguration`:

```kotlin
@EnableConfigurationProperties(OnyxProperties::class, SearchProperties::class)
```

Remove `searchCandidates` from `OnyxProperties.ModelServer`.

- [ ] **Step 4: Move environment-backed settings**

`application.yml`:

```yaml
onyx:
  search:
    hybrid-candidates: ${ONYX_SEARCH_CANDIDATES:200}
    hybrid-normalization: ${ONYX_HYBRID_NORMALIZATION:min_max}
    keyword-weight: ${ONYX_HYBRID_KEYWORD_WEIGHT:0.5}
    vector-weight: ${ONYX_HYBRID_VECTOR_WEIGHT:0.5}
    rrf-k: ${ONYX_RRF_K:50}
```

`docker-compose.yaml` shared backend environment:

```yaml
ONYX_SEARCH_CANDIDATES: ${ONYX_SEARCH_CANDIDATES:-200}
ONYX_HYBRID_NORMALIZATION: ${ONYX_HYBRID_NORMALIZATION:-min_max}
ONYX_HYBRID_KEYWORD_WEIGHT: ${ONYX_HYBRID_KEYWORD_WEIGHT:-0.5}
ONYX_HYBRID_VECTOR_WEIGHT: ${ONYX_HYBRID_VECTOR_WEIGHT:-0.5}
ONYX_RRF_K: ${ONYX_RRF_K:-50}
```

Mirror the same variables/defaults in `_kotlin/.env.example`.

- [ ] **Step 5: Inject `SearchProperties` into current `SearchService`**

```kotlin
class SearchService(
    private val properties: OnyxProperties,
    private val searchProperties: SearchProperties,
    private val modelServer: ModelServerClient,
    private val indexer: OpenSearchIndexer,
    private val documentSetRepository: DocumentSetRepository,
)
```

Until Task 3, replace only the old candidate count with:

```kotlin
searchProperties.hybridCandidates
```

- [ ] **Step 6: Run GREEN**

```bash
./gradlew test \
  --tests "com.onyx.foss.kotlin.config.SearchPropertiesTest" \
  --tests "com.onyx.foss.kotlin.service.SearchServiceTest"
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/onyx/foss/kotlin/config \
        src/main/kotlin/com/onyx/foss/kotlin/service/SearchService.kt \
        src/main/resources/application.yml ../docker-compose.yaml ../.env.example \
        src/test/kotlin/com/onyx/foss/kotlin/config/SearchPropertiesTest.kt \
        src/test/kotlin/com/onyx/foss/kotlin/service/SearchServiceTest.kt
git commit -m "refactor(kotlin): separate search retrieval configuration"
```

---

### Task 2: Split min-max and z-score normalization pipeline implementations

**Files:**
- Create: `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/opensearch/HybridNormalizationPipeline.kt`
- Create: `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/opensearch/MinMaxNormalizationPipeline.kt`
- Create: `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/opensearch/ZScoreNormalizationPipeline.kt`
- Create: `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/opensearch/HybridNormalizationPipelineRegistry.kt`
- Modify: `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/opensearch/OpenSearchConfiguration.kt`
- Create: `_kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/opensearch/MinMaxNormalizationPipelineTest.kt`
- Create: `_kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/opensearch/ZScoreNormalizationPipelineTest.kt`
- Create: `_kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/opensearch/HybridNormalizationPipelineRegistryTest.kt`

**Interfaces:**

```kotlin
interface HybridNormalizationPipeline {
    val technique: String
    val pipelineId: String
    fun ensureReady()
}
```

```kotlin
class HybridNormalizationPipelineRegistry(
    private val minMax: MinMaxNormalizationPipeline,
    private val zScore: ZScoreNormalizationPipeline,
    private val searchProperties: SearchProperties,
) {
    fun ensureReady()
    fun selectedPipelineId(): String
}
```

IDs:

```text
<index-name>-hybrid-min-max
<index-name>-hybrid-z-score
```

The registry initializes both pipelines in one readiness operation. Search-time normalization selection is only a pipeline-ID lookup.

- [ ] **Step 1: Write RED test for typed min-max path**

Use a real `OpenSearchClient` against `MockWebServer`, invoke `MinMaxNormalizationPipeline.ensureReady()`, and assert:

```kotlin
val request = server.takeRequest()
assertThat(request.method).isEqualTo("PUT")
assertThat(request.path).isEqualTo("/_search/pipeline/documents-hybrid-min-max")

val processor = mapper.readTree(request.body.readUtf8())
    .path("phase_results_processors").first().path("normalization-processor")
assertThat(processor.path("normalization").path("technique").asText()).isEqualTo("min_max")
assertThat(processor.path("combination").path("technique").asText()).isEqualTo("arithmetic_mean")
assertThat(processor.path("combination").path("parameters").path("weights").map { it.asDouble() })
    .containsExactly(0.5, 0.5)
```

Use a spy/mock boundary to verify the min-max implementation never calls `client.generic()`.

- [ ] **Step 2: Run min-max RED**

```bash
./gradlew test --tests "com.onyx.foss.kotlin.opensearch.MinMaxNormalizationPipelineTest"
```

Expected: compilation fails because the pipeline types do not exist.

- [ ] **Step 3: Implement typed `MinMaxNormalizationPipeline`**

`opensearch-java:3.10.0` exposes the required constants `ScoreNormalizationTechnique.MinMax` and `ScoreCombinationTechnique.ArithmeticMean`.

```kotlin
class MinMaxNormalizationPipeline(
    private val client: OpenSearchClient,
    private val vectorStoreProperties: OpenSearchVectorStoreProperties,
    private val searchProperties: SearchProperties,
) : HybridNormalizationPipeline {
    private val ready = AtomicBoolean(false)

    override val technique: String = "min_max"
    override val pipelineId: String
        get() = "${vectorStoreProperties.indexName}-hybrid-min-max"

    override fun ensureReady() {
        if (ready.get()) return
        synchronized(ready) {
            if (ready.get()) return
            val response = client.searchPipeline().put { request ->
                request.id(pipelineId)
                    .description("Onyx Kotlin min-max hybrid normalization")
                    .phaseResultsProcessors { processor ->
                        processor.normalizationProcessor { normalization ->
                            normalization
                                .normalization { score ->
                                    score.technique(ScoreNormalizationTechnique.MinMax)
                                }
                                .combination { combination ->
                                    combination
                                        .technique(ScoreCombinationTechnique.ArithmeticMean)
                                        .parameters { parameters ->
                                            parameters.weights(
                                                searchProperties.keywordWeight.toFloat(),
                                                searchProperties.vectorWeight.toFloat(),
                                            )
                                        }
                                }
                        }
                    }
            }
            check(response.acknowledged()) {
                "OpenSearch did not acknowledge min-max search pipeline $pipelineId"
            }
            ready.set(true)
        }
    }
}
```

- [ ] **Step 4: Run min-max GREEN**

```bash
./gradlew test --tests "com.onyx.foss.kotlin.opensearch.MinMaxNormalizationPipelineTest"
```

Expected: PASS.

- [ ] **Step 5: Write RED test for isolated z-score path**

Use `SearchProperties(keywordWeight = 0.4, vectorWeight = 0.6)` and assert:

```kotlin
assertThat(request.method).isEqualTo("PUT")
assertThat(request.path).isEqualTo("/_search/pipeline/documents-hybrid-z-score")
val processor = mapper.readTree(request.body.readUtf8())
    .path("phase_results_processors").first().path("normalization-processor")
assertThat(processor.path("normalization").path("technique").asText()).isEqualTo("z_score")
assertThat(processor.path("combination").path("technique").asText()).isEqualTo("arithmetic_mean")
assertThat(processor.path("combination").path("parameters").path("weights").map { it.asDouble() })
    .containsExactly(0.4, 0.6)
```

- [ ] **Step 6: Implement `ZScoreNormalizationPipeline`**

The raw body is isolated to this class because Java Client 3.10.0 has no `ZScore` enum member.

```kotlin
class ZScoreNormalizationPipeline(
    private val client: OpenSearchClient,
    private val vectorStoreProperties: OpenSearchVectorStoreProperties,
    private val searchProperties: SearchProperties,
    private val mapper: ObjectMapper,
) : HybridNormalizationPipeline {
    private val ready = AtomicBoolean(false)

    override val technique: String = "z_score"
    override val pipelineId: String
        get() = "${vectorStoreProperties.indexName}-hybrid-z-score"

    override fun ensureReady() {
        if (ready.get()) return
        synchronized(ready) {
            if (ready.get()) return
            val body = mapOf(
                "description" to "Onyx Kotlin z-score hybrid normalization",
                "phase_results_processors" to listOf(
                    mapOf(
                        "normalization-processor" to mapOf(
                            "normalization" to mapOf("technique" to "z_score"),
                            "combination" to mapOf(
                                "technique" to "arithmetic_mean",
                                "parameters" to mapOf(
                                    "weights" to listOf(
                                        searchProperties.keywordWeight,
                                        searchProperties.vectorWeight,
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            )
            val request = Requests.builder()
                .method("PUT")
                .endpoint("/_search/pipeline/$pipelineId")
                .json(mapper.writeValueAsString(body))
                .build()
            client.generic().execute(request).use { response ->
                val responseBody = response.body.map { it.bodyAsString() }.orElse("")
                check(response.status in 200..299) {
                    "OpenSearch z-score search pipeline update failed: HTTP ${response.status} $responseBody"
                }
            }
            ready.set(true)
        }
    }
}
```

- [ ] **Step 7: Run z-score GREEN**

```bash
./gradlew test --tests "com.onyx.foss.kotlin.opensearch.ZScoreNormalizationPipelineTest"
```

Expected: PASS.

- [ ] **Step 8: Write registry RED tests**

Verify one readiness operation initializes both implementations:

```kotlin
registry.ensureReady()
verify(minMax).ensureReady()
verify(zScore).ensureReady()
```

Call registry readiness twice and verify both implementations are called once.

Verify selection:

```kotlin
assertThat(registryFor("min_max").selectedPipelineId()).isEqualTo("documents-hybrid-min-max")
assertThat(registryFor("z_score").selectedPipelineId()).isEqualTo("documents-hybrid-z-score")
```

- [ ] **Step 9: Implement registry**

```kotlin
class HybridNormalizationPipelineRegistry(
    private val minMax: MinMaxNormalizationPipeline,
    private val zScore: ZScoreNormalizationPipeline,
    private val searchProperties: SearchProperties,
) {
    private val ready = AtomicBoolean(false)

    fun ensureReady() {
        if (ready.get()) return
        synchronized(ready) {
            if (ready.get()) return
            minMax.ensureReady()
            zScore.ensureReady()
            ready.set(true)
        }
    }

    fun selectedPipelineId(): String = when (searchProperties.hybridNormalization) {
        "min_max" -> minMax.pipelineId
        "z_score" -> zScore.pipelineId
        else -> error("Unsupported hybrid normalization: ${searchProperties.hybridNormalization}")
    }
}
```

- [ ] **Step 10: Register pipeline beans**

Modify `OpenSearchConfiguration` to expose `MinMaxNormalizationPipeline`, `ZScoreNormalizationPipeline`, and `HybridNormalizationPipelineRegistry` using the existing `OpenSearchClient`, `OpenSearchVectorStoreProperties`, `SearchProperties`, and Jackson 3 `ObjectMapper`.

- [ ] **Step 11: Run Task 2 GREEN suite**

```bash
./gradlew test \
  --tests "com.onyx.foss.kotlin.opensearch.MinMaxNormalizationPipelineTest" \
  --tests "com.onyx.foss.kotlin.opensearch.ZScoreNormalizationPipelineTest" \
  --tests "com.onyx.foss.kotlin.opensearch.HybridNormalizationPipelineRegistryTest"
```

Expected: PASS. Min-max uses typed `searchPipeline()`; z-score alone uses generic transport.

- [ ] **Step 12: Commit**

```bash
git add src/main/kotlin/com/onyx/foss/kotlin/opensearch \
        src/test/kotlin/com/onyx/foss/kotlin/opensearch
git commit -m "feat(kotlin): split opensearch normalization pipelines"
```

---

### Task 3: Route keyword, semantic, and native hybrid retrieval independently

**Files:**
- Modify: `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/OpenSearchIndexer.kt`
- Modify: `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/service/SearchService.kt`
- Delete: `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/service/HybridFusionJoiner.kt`
- Delete: `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/service/OpenSearchHybridDocumentRetriever.kt`
- Modify: `_kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/ingestion/OpenSearchIndexerTest.kt`
- Modify: `_kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/service/SearchServiceTest.kt`
- Delete dedicated tests for the two superseded bridge classes if present.

**Interfaces:**

```kotlin
fun keywordSearch(
    query: String,
    documentSets: List<String>,
    count: Int,
    sourceTypes: List<String> = emptyList(),
    updatedAfter: Instant? = null,
): List<SearchCandidate>

fun vectorSearch(
    queryEmbedding: List<Double>,
    documentSets: List<String>,
    count: Int,
    sourceTypes: List<String> = emptyList(),
    updatedAfter: Instant? = null,
): List<SearchCandidate>

fun hybridSearch(
    query: String,
    queryEmbedding: List<Double>,
    documentSets: List<String>,
    limit: Int,
    sourceTypes: List<String> = emptyList(),
    updatedAfter: Instant? = null,
): List<SearchCandidate>
```

`OpenSearchIndexer` receives `SearchProperties` and `HybridNormalizationPipelineRegistry`.

- [ ] **Step 1: Rewrite SearchService tests for routing**

KEYWORD:

```kotlin
`when`(indexer.keywordSearch("ABC-123", emptyList(), 10, emptyList(), null))
    .thenReturn(listOf(candidate("k1", 10.0)))

val response = service.search("ABC-123", emptyList(), 10, SearchType.KEYWORD)

verify(indexer).keywordSearch("ABC-123", emptyList(), 10, emptyList(), null)
verifyNoInteractions(modelServer)
assertThat(response.results.map { it.sourceDocumentId }).containsExactly("doc-k1")
```

SEMANTIC verifies exactly one `embedQuery()` and one `vectorSearch()`.

HYBRID verifies exactly one `embedQuery()` and one `hybridSearch()`.

- [ ] **Step 2: Run SearchService RED**

```bash
./gradlew test --tests "com.onyx.foss.kotlin.service.SearchServiceTest"
```

Expected: missing retrieval methods.

- [ ] **Step 3: Replace old candidate tests with request-shape tests**

Add to `OpenSearchIndexerTest`:

1. keyword search sends one BM25 request with filters;
2. vector search sends one k-NN request with filters;
3. hybrid search sends one hybrid request with `pagination_depth=200`, vector `k=200`, `size=limit`, and the registry-selected pipeline ID;
4. OpenSearch readiness initializes both normalization pipelines before search requests are served.

- [ ] **Step 4: Run indexer RED**

```bash
./gradlew test --tests "com.onyx.foss.kotlin.ingestion.OpenSearchIndexerTest"
```

Expected: missing methods/request shape.

- [ ] **Step 5: Add one reusable filter builder**

```kotlin
private fun searchFilter(
    documentSets: List<String>,
    sourceTypes: List<String>,
    updatedAfter: Instant?,
): Query? {
    val clauses = mutableListOf<Query>()
    // add terms(document_sets), terms(source_type), range(doc_updated_at)
    return clauses.takeIf { it.isNotEmpty() }
        ?.let { Query.of { q -> q.bool { b -> b.filter(it) } } }
}
```

All three retrieval methods consume this helper.

- [ ] **Step 6: Implement keyword and vector searches**

`keywordSearch`: only `multi_match`, `size=count`.

`vectorSearch`: validate embedding dimension, only k-NN, `k=count`, `size=count`.

Both map hits through existing `OpenSearchChunkDocument.toSearchCandidate(...)`.

- [ ] **Step 7: Wire `SearchProperties` and registry into index readiness**

Primary/autowired construction receives:

```kotlin
private val searchProperties: SearchProperties
private val pipelineRegistry: HybridNormalizationPipelineRegistry
```

Keep compatibility/test constructors explicit by accepting test instances; do not construct hidden production registries inside secondary constructors.

Extend the existing index/search-infrastructure readiness path so successful readiness performs:

```kotlin
ensureIndexMappingAndSettings()
pipelineRegistry.ensureReady()
```

Use the existing process-local index readiness guard so both pipelines are prepared with index readiness, not lazily only when the first hybrid query happens.

- [ ] **Step 8: Implement native hybrid query**

```kotlin
val keywordQuery = Query.of { q ->
    q.multiMatch { mm -> mm.query(query).fields(listOf("title^2", "content")) }
}

val vectorQuery = Query.of { q ->
    q.knn { knn ->
        knn.field(EMBEDDING_FIELD)
            .vector(queryEmbedding.map { it.toFloat() })
            .k(searchProperties.hybridCandidates)
    }
}

val hybridQuery = Query.of { q ->
    q.hybrid { hybrid ->
        hybrid.queries(listOf(keywordQuery, vectorQuery))
            .paginationDepth(searchProperties.hybridCandidates)
            .apply { searchFilter(documentSets, sourceTypes, updatedAfter)?.let(::filter) }
    }
}
```

Search-time normalization selection is only:

```kotlin
val pipelineId = pipelineRegistry.selectedPipelineId()
```

Request:

```kotlin
OpenSearchSearchRequest.Builder()
    .index(properties.indexName)
    .size(limit)
    .searchPipeline(pipelineId)
    .query(hybridQuery)
    .build()
```

Do not branch hybrid query construction by normalization technique.

- [ ] **Step 9: Replace SearchService Kotlin fusion with routing**

```kotlin
val ranked = when (searchType) {
    SearchType.KEYWORD -> indexer.keywordSearch(
        query, selectedSets, limit, sourceTypes, timeCutoff,
    )
    SearchType.SEMANTIC -> indexer.vectorSearch(
        modelServer.embedQuery(query), selectedSets, limit, sourceTypes, timeCutoff,
    )
    SearchType.HYBRID -> indexer.hybridSearch(
        query, modelServer.embedQuery(query), selectedSets, limit, sourceTypes, timeCutoff,
    )
}
```

Delete `normalize()` and `fuse()`.

- [ ] **Step 10: Remove superseded Spring AI fusion bridge classes**

Run:

```bash
rg "ScoreNormalizationDocumentJoiner|ReciprocalRankFusionDocumentJoiner|OpenSearchHybridDocumentRetriever" src/main src/test
```

Expected production references are only the bridge files. Delete `HybridFusionJoiner.kt`, `OpenSearchHybridDocumentRetriever.kt`, and their dedicated tests. If another production caller exists, stop and reconcile that caller before deleting the files.

- [ ] **Step 11: Run GREEN**

```bash
./gradlew test \
  --tests "com.onyx.foss.kotlin.service.SearchServiceTest" \
  --tests "com.onyx.foss.kotlin.ingestion.OpenSearchIndexerTest"
```

Expected: PASS. KEYWORD has zero model-server interaction; hybrid `size` is requested limit while candidate depth is 200; readiness creates both pipelines.

- [ ] **Step 12: Commit**

```bash
git add -A src/main/kotlin/com/onyx/foss/kotlin/ingestion/OpenSearchIndexer.kt \
           src/main/kotlin/com/onyx/foss/kotlin/service \
           src/test/kotlin/com/onyx/foss/kotlin/ingestion/OpenSearchIndexerTest.kt \
           src/test/kotlin/com/onyx/foss/kotlin/service
git commit -m "feat(kotlin): use native opensearch hybrid retrieval"
```

---

### Task 4: Make MCP WRRF defaults configurable and collapse adjacent chunk runs

**Files:**
- Modify: `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/service/SearchService.kt`
- Modify: `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/mcp/McpSearchTool.kt`
- Modify: `_kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/service/SearchServiceTest.kt`
- Modify: `_kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/mcp/McpSearchToolTest.kt`

**Interfaces:**

```kotlin
fun defaultRrfK(): Int

fun <T> collapseAdjacentChunks(
    rankedResults: List<T>,
    documentIdExtractor: (T) -> String?,
    chunkIdExtractor: (T) -> Int?,
): List<T>
```

- [ ] **Step 1: Add RED adjacent-run tests**

```kotlin
@Test
fun `collapseAdjacentChunks keeps earliest ranked member of each adjacent run`() {
    data class Item(val doc: String, val chunk: Int)
    val ranked = listOf(
        Item("A", 8),
        Item("B", 0),
        Item("A", 7),
        Item("A", 9),
        Item("A", 20),
        Item("B", 2),
    )

    val collapsed = service.collapseAdjacentChunks(ranked, Item::doc, Item::chunk)

    assertThat(collapsed).containsExactly(
        Item("A", 8),
        Item("B", 0),
        Item("A", 20),
        Item("B", 2),
    )
}
```

Add a test that missing document/chunk identity remains independent.

- [ ] **Step 2: Run RED**

```bash
./gradlew test --tests "com.onyx.foss.kotlin.service.SearchServiceTest"
```

Expected: missing `collapseAdjacentChunks`.

- [ ] **Step 3: Implement adjacent-run collapse**

Algorithm:

1. record original rank;
2. group identifiable items by document ID;
3. sort by chunk ID;
4. partition maximal consecutive runs;
5. retain the smallest original rank in each run;
6. retain unidentified items independently;
7. restore global original-rank order.

Do not concatenate content or collapse non-adjacent chunks.

- [ ] **Step 4: Add RED MCP default/override tests**

Verify:

- omitted weights -> `[1.0, 1.0]`;
- omitted `k` -> `search.defaultRrfK()`;
- explicit weights and `k=60` pass through;
- adjacent fused chunks collapse after WRRF.

- [ ] **Step 5: Update `callFusion`**

```kotlin
val weights = weightsRaw?.map { (it as? Number)?.toDouble() ?: 1.0 }
    ?: List(rankedResults.size) { 1.0 }
val k = (arguments["k"] as? Number)?.toInt() ?: search.defaultRrfK()

val fused = search.weightedReciprocalRankFusion(
    rankedResults = rankedResults,
    weights = weights,
    idExtractor = ::extractItemId,
    k = k,
)

val merged = search.collapseAdjacentChunks(
    fused,
    documentIdExtractor = ::extractDocumentId,
    chunkIdExtractor = ::extractChunkId,
)
```

- [ ] **Step 6: Rewrite MCP guidance/schema**

Fusion guidance:

```text
Use WRRF for ranked searches targeting the same underlying information through rewrites, synonyms, or alternate search strategies. Omitted weights are equal (1.0 each). Do not blindly fuse independent decomposed subquestions or facets; preserve evidence coverage for each subquestion.
```

Remove schema `default: 50` from `k`; describe the server-configured default instead.

- [ ] **Step 7: Run GREEN**

```bash
./gradlew test \
  --tests "com.onyx.foss.kotlin.service.SearchServiceTest" \
  --tests "com.onyx.foss.kotlin.mcp.McpSearchToolTest" \
  --tests "com.onyx.foss.kotlin.mcp.McpEndpointIntegrationTest"
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/com/onyx/foss/kotlin/service/SearchService.kt \
        src/main/kotlin/com/onyx/foss/kotlin/mcp/McpSearchTool.kt \
        src/test/kotlin/com/onyx/foss/kotlin/service/SearchServiceTest.kt \
        src/test/kotlin/com/onyx/foss/kotlin/mcp/McpSearchToolTest.kt
git commit -m "feat(kotlin): tune agentic search fusion guidance"
```

---

### Task 5: Verify both normalization pipelines and native hybrid behavior against OpenSearch 3.6.0

**Files:**
- Modify: `_kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/ingestion/OpenSearchIndexerIntegrationTest.kt`

**Interfaces:**
- Uses final Tasks 1-4 production APIs.
- Adds no production API.

- [ ] **Step 1: Replace legacy candidate-search integration coverage**

Add:

1. `keywordSearchAppliesDocumentSetFilter`
2. `vectorSearchAppliesDocumentSetFilter`
3. `indexReadinessCreatesBothNormalizationPipelines`
4. `minMaxHybridSearchUsesSelectedPipelineAndFinalLimit`
5. `zScoreHybridSearchUsesSelectedPipelineAndFinalLimit`
6. `normalizationPipelineInitializationIsIdempotent`

Use deterministic vectors:

```kotlin
private fun basisVector(position: Int): List<Double> =
    List(768) { index -> if (index == position) 1.0 else 0.0 }
```

Hybrid fixture contains lexical-strong, semantic-strong, overlap, and at least three distractors.

- [ ] **Step 2: Run integration RED**

```bash
./gradlew opensearchIntegrationTest
```

Expected after adding tests before final corrections: at least one new test fails.

- [ ] **Step 3: Assert both stored pipeline definitions**

GET:

```text
/_search/pipeline/<index>-hybrid-min-max
/_search/pipeline/<index>-hybrid-z-score
```

Assert min-max:

```text
normalization.technique = min_max
combination.technique = arithmetic_mean
weights = [0.5, 0.5]
```

Assert z-score:

```text
normalization.technique = z_score
combination.technique = arithmetic_mean
weights = [0.5, 0.5]
```

Both must exist after one index/search-infrastructure readiness cycle, regardless of which normalization is selected.

- [ ] **Step 4: Verify search-time selection only changes pipeline ID**

Run otherwise-identical hybrid requests with:

- `SearchProperties(hybridNormalization = "min_max")` -> `<index>-hybrid-min-max`
- `SearchProperties(hybridNormalization = "z_score")` -> `<index>-hybrid-z-score`

Query clauses, filters, `pagination_depth`, vector `k`, and request `size` must be identical. Call with `limit=2`; assert exactly two hits while candidate depth remains 200.

- [ ] **Step 5: Run integration GREEN**

```bash
./gradlew opensearchIntegrationTest
```

Expected: PASS against OpenSearch 3.6.0.

- [ ] **Step 6: Run focused suite**

```bash
./gradlew test \
  --tests "com.onyx.foss.kotlin.config.SearchPropertiesTest" \
  --tests "com.onyx.foss.kotlin.opensearch.MinMaxNormalizationPipelineTest" \
  --tests "com.onyx.foss.kotlin.opensearch.ZScoreNormalizationPipelineTest" \
  --tests "com.onyx.foss.kotlin.opensearch.HybridNormalizationPipelineRegistryTest" \
  --tests "com.onyx.foss.kotlin.service.SearchServiceTest" \
  --tests "com.onyx.foss.kotlin.mcp.McpSearchToolTest" \
  --tests "com.onyx.foss.kotlin.mcp.McpEndpointIntegrationTest" \
  --tests "com.onyx.foss.kotlin.ingestion.OpenSearchIndexerTest"
./gradlew opensearchIntegrationTest
```

Expected: PASS.

- [ ] **Step 7: Run full backend suite**

```bash
./gradlew test
```

Expected: PASS. Any failure must be reproduced on `refactor/opensearch-spring-ai` before being classified as pre-existing.

- [ ] **Step 8: Verify boundaries and remove stale hybrid fusion**

```bash
rg "ScoreNormalizationDocumentJoiner|OpenSearchHybridDocumentRetriever|KEYWORD_WEIGHT|VECTOR_WEIGHT|searchCandidates|private fun normalize|private fun fuse" src/main/kotlin
```

Expected: no old production hybrid-fusion path.

```bash
rg "searchPipeline\(\)|generic\(\)|z_score|MinMax" src/main/kotlin/com/onyx/foss/kotlin/opensearch
```

Expected:

- typed `searchPipeline()` in `MinMaxNormalizationPipeline`;
- z-score pipeline `generic()` PUT isolated to `ZScoreNormalizationPipeline`;
- no raw min-max pipeline JSON implementation.

- [ ] **Step 9: Commit integration verification**

```bash
git add src/test/kotlin/com/onyx/foss/kotlin/ingestion/OpenSearchIndexerIntegrationTest.kt
git commit -m "test(kotlin): verify native hybrid normalization pipelines"
```

- [ ] **Step 10: Final verification before PR**

```bash
git status --short
git log --oneline refactor/opensearch-spring-ai..HEAD
```

Expected: clean worktree and a reviewable sequence containing spec/plan plus Tasks 1-5 commits.

Do not open the PR until all verification above is green. While PR #19 is open, the new PR base is `refactor/opensearch-spring-ai`; after #19 merges, retarget it to `main-v4.6.5`.
