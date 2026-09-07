# OpenSearch Native Hybrid Retrieval Design

## Goal

Move BM25/vector fusion from Kotlin into OpenSearch native hybrid search while preserving the Spring AI abstractions introduced by PR #19. Keep query-level multi-search fusion in the MCP layer as equal-weight Weighted Reciprocal Rank Fusion (WRRF), use a 200-candidate hybrid depth without increasing MCP response size, and isolate optional z-score support from the default typed min-max path.

## Context

PR #14 moved Kotlin search from BM25/vector RRF plus reranking to min-max normalization with 50:50 keyword/vector weighting and introduced WRRF for multi-query fusion. PR #16 added source/time filters, context expansion, and MCP retrieval guidance. PR #19 migrates OpenSearch communication to `opensearch-java:3.10.0` and introduces Spring AI `VectorStore`, `DocumentRetriever`, and `DocumentJoiner` abstractions.

Current upstream Onyx OpenSearch code already supports two normalization pipelines:

- `min_max` — default
- `z_score` — optional

Onyx creates both pipelines and chooses the pipeline ID at search time. It does not calculate either normalization algorithm in Python; OpenSearch performs both. The Kotlin design follows the same operational model, with one Java-client-specific difference: `opensearch-java:3.10.0` exposes typed `min_max` but its generated `ScoreNormalizationTechnique` enum does not expose `z_score`.

## Design Principles

1. BM25/vector score normalization belongs in OpenSearch, not in Kotlin.
2. Spring AI remains the application-level abstraction for `Document`, `VectorStore`, and RAG contracts.
3. Default production behavior uses typed OpenSearch Java Client APIs wherever the client supports the server feature.
4. The Java-client gap for z-score is isolated in a dedicated implementation class instead of forcing the entire pipeline lifecycle through raw JSON.
5. `KEYWORD`, `SEMANTIC`, and `HYBRID` execute only the work they require.
6. Candidate depth and final MCP result count are separate concerns.
7. BM25/vector fusion and multi-query WRRF are separate layers.
8. No reranker or server-side LLM query expansion is introduced.

## Search Type Routing

`SearchService` routes before embedding:

- `KEYWORD`: BM25 only; no embedding call and no k-NN.
- `SEMANTIC`: one embedding call, then k-NN only.
- `HYBRID`: one embedding call, then one OpenSearch hybrid query containing BM25 and k-NN clauses and an explicitly selected normalization pipeline.

The final MCP result `limit` remains `1..20`.

## Native Hybrid Query

Hybrid clause order is fixed because normalization weights are positional:

1. keyword: `multi_match` over `title^2` and `content`
2. vector: k-NN over `embedding`

Document-set, source-type, and updated-after restrictions are composed into one shared hybrid `filter` so both subqueries see the same scope.

Defaults:

- `pagination_depth = 200`
- vector `k = 200`
- request `size = requested MCP limit`
- search pipeline selected explicitly per request

Increasing candidate depth to 200 affects OpenSearch retrieval/fusion work, not the number of chunks returned to Claude.

## Normalization Pipeline Architecture

Normalization support is split by implementation capability.

```text
HybridNormalizationPipeline
├── MinMaxNormalizationPipeline
│   └── typed OpenSearch Search Pipeline API
└── ZScoreNormalizationPipeline
    └── OpenSearchClient.generic() raw pipeline body

HybridNormalizationPipelineRegistry
├── ensures both pipelines are present
└── selects pipeline ID from onyx.search.hybrid-normalization
```

### Common contract

```kotlin
interface HybridNormalizationPipeline {
    val technique: String
    val pipelineId: String
    fun ensureReady()
}
```

Both implementations use the same configured keyword/vector weights and `arithmetic_mean` combination.

Pipeline IDs are deterministic and separate:

- `<index-name>-hybrid-min-max`
- `<index-name>-hybrid-z-score`

The search-infrastructure readiness lifecycle upserts both pipelines together, matching current Onyx behavior. A process-local guard prevents redundant writes after successful initialization. Search execution does not rebuild a pipeline; it only asks the registry for the selected pipeline ID.

### MinMaxNormalizationPipeline

This is the default production path and uses only typed Java Client APIs:

```text
client.searchPipeline().put(...)
  -> normalization-processor
  -> ScoreNormalizationTechnique.MinMax
  -> arithmetic_mean
  -> configured weights
```

No `generic()` call or hand-written pipeline JSON is used for min-max.

### ZScoreNormalizationPipeline

This class exists only because `opensearch-java:3.10.0` cannot express `z_score` in its generated `ScoreNormalizationTechnique` enum even though OpenSearch 3.6.0 supports it.

It uses the same native client's `generic()` transport and sends only the pipeline definition that the typed client cannot represent:

```json
{
  "phase_results_processors": [
    {
      "normalization-processor": {
        "normalization": { "technique": "z_score" },
        "combination": {
          "technique": "arithmetic_mean",
          "parameters": { "weights": [0.5, 0.5] }
        }
      }
    }
  ]
}
```

No WebClient, second OpenSearch transport, or Kotlin z-score calculation is introduced.

### HybridNormalizationPipelineRegistry

The registry owns both implementations and the configured selection:

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

`ensureReady()` initializes both pipelines. `selectedPipelineId()` maps `min_max` to the min-max pipeline and `z_score` to the z-score pipeline.

If pipeline initialization fails, hybrid retrieval fails clearly. There is no fallback to Kotlin-side score normalization or a different fusion algorithm. Keyword and semantic retrieval remain independent of normalization pipelines.

## Spring AI Boundary

Keep `OnyxOpenSearchVectorStore` and Spring AI's `Document`/`VectorStore`/RAG contracts from PR #19.

Responsibilities:

- Spring AI: document/vector-store/RAG abstractions.
- OpenSearch Java Client: BM25, k-NN, hybrid query, filters, `pagination_depth`, search execution, min-max typed pipeline management, and z-score generic pipeline transport.

Production hybrid ranking no longer depends on `ScoreNormalizationDocumentJoiner` or `OpenSearchHybridDocumentRetriever`; those PR #19 bridge classes are removed after the native hybrid path replaces them. `OnyxOpenSearchVectorStore` remains.

## Configuration

Move retrieval policy out of `onyx.model-server` into `onyx.search`:

```yaml
onyx:
  search:
    hybrid-candidates: ${ONYX_SEARCH_CANDIDATES:200}
    hybrid-normalization: ${ONYX_HYBRID_NORMALIZATION:min_max}
    keyword-weight: ${ONYX_HYBRID_KEYWORD_WEIGHT:0.5}
    vector-weight: ${ONYX_HYBRID_VECTOR_WEIGHT:0.5}
    rrf-k: ${ONYX_RRF_K:50}
```

Requirements:

- Preserve `ONYX_SEARCH_CANDIDATES`; default changes from 50 to 200.
- `hybrid-candidates`: `1..10000`.
- `hybrid-normalization`: `min_max` or `z_score`; default `min_max`.
- keyword/vector weights: non-negative and sum to 1.0 within floating-point tolerance.
- `rrf-k`: positive; default 50.
- Remove `searchCandidates` from `OnyxProperties.ModelServer`.

## Query-Level WRRF

The MCP `weighted_reciprocal_rank_fusion` remains application-level and is not replaced by OpenSearch RRF.

Formula:

`score(d) = sum(weight_i / (k + rank_i(d)))`

Defaults:

- `k = onyx.search.rrf-k`, default 50
- omitted list weights = 1.0 for every list

Do not adopt upstream Onyx's fixed `1.3 / 1.0 / 0.7 / 0.5` query-role weights. Claude Code performs query planning externally, so equal weights remain the neutral default.

MCP guidance distinguishes:

- same intent via rewrites, synonyms, or alternate retrieval strategies: WRRF is appropriate;
- independent decomposed subquestions/facets: preserve evidence coverage per subquestion instead of blindly fusing all lists.

The MCP schema does not hard-code a second `k=50` default; when omitted, the server-configured value is used.

## Adjacent Chunk Diversity

After query-level WRRF:

1. Group identifiable results by `source_document_id`.
2. Sort each document's chunks by `chunk_id`.
3. Partition them into maximal consecutive runs.
4. Keep the best original fused-rank member from each run.
5. Keep non-adjacent chunks independently.
6. Keep results without usable document/chunk identifiers independently.
7. Restore global order by original fused rank.

Do not concatenate contents. The existing `get_document_context` MCP tool remains the mechanism for surrounding text.

## Error Handling

- Invalid search configuration fails application startup.
- Pipeline initialization errors fail hybrid retrieval; no hidden ranking fallback.
- KEYWORD remains usable when the embedding/model server is unavailable.
- SEMANTIC and HYBRID propagate embedding failures through the existing model-server path.
- Existing document-set validation and filter semantics remain unchanged.

## Testing Strategy

### Configuration

Verify defaults, property overrides, normalization values, candidate/`rrf-k` bounds, and weight-sum validation.

### Pipeline implementations

Verify separately:

- `MinMaxNormalizationPipeline` uses `client.searchPipeline().put(...)` and never `client.generic()`.
- typed min-max payload contains `MinMax`, `arithmetic_mean`, and configured float weights.
- `ZScoreNormalizationPipeline` uses `client.generic()` and emits exactly `technique: z_score`.
- registry initializes both once and selects the correct ID for both configuration values.

### Search routing

Verify:

- KEYWORD never embeds and performs only keyword retrieval.
- SEMANTIC embeds once and performs only vector retrieval.
- HYBRID embeds once and performs one native hybrid retrieval.
- hybrid request uses the selected registry pipeline ID.
- all paths preserve document-set/source/time filters.
- returned results never exceed `limit`.

### OpenSearch 3.6.0 integration

Verify:

- both min-max and z-score pipelines are created in the same index lifecycle;
- switching configuration changes only the selected pipeline ID, not query construction;
- lexical-only, semantic-only, overlap, and distractor fixtures work through native hybrid search;
- candidate depth 200 still returns only `limit` hits;
- keyword retrieval works independently of embedding availability.

### MCP

Verify equal WRRF defaults, configurable `rrf-k`, explicit overrides, guidance semantics, and adjacent-run collapse.

## Migration and Compatibility

- Stacked on `refactor/opensearch-spring-ai` (PR #19).
- No reindex is required solely for native hybrid retrieval.
- Existing MCP request/response shapes remain compatible.
- `ONYX_SEARCH_CANDIDATES` remains supported and now defaults to 200.
- `search_type` remains `hybrid`, `keyword`, and `semantic`.
- Candidate depth does not change normal MCP context size.

## Non-Goals

- No reranker.
- No server-side LLM query expansion.
- No OpenSearch RRF for BM25/vector fusion.
- No fixed Onyx query-role WRRF weights.
- No learned/query-dependent BM25/vector weighting.
- No Kotlin implementation of min-max or z-score math.
- No automatic adjacent-content concatenation.
- No unrelated ingestion/connector refactor.

## Upstream Alignment

Aligned with current Onyx:

- score-based BM25/vector hybrid fusion;
- default 50:50 keyword/vector weighting;
- min-max default with z-score as an optional pipeline;
- both normalization pipelines created in OpenSearch and selected by pipeline ID at search time;
- WRRF with `k=50` for multiple query-result lists;
- chunk retrieval plus separate context expansion.

Intentional differences:

- query expansion belongs to external Claude Code instead of a server-side LLM flow;
- WRRF list weights default to equal 1.0;
- Kotlin uses a typed min-max pipeline implementation and isolates z-score generic JSON because of the Java Client 3.10.0 enum gap;
- adjacent chunks are collapsed for MCP result diversity and expanded on demand.

## References

- Onyx OpenSearch normalization configuration: `backend/onyx/document_index/opensearch/search.py`
- Onyx OpenSearch pipeline lifecycle: `backend/onyx/document_index/opensearch/opensearch_document_index.py`
- OpenSearch hybrid query: https://docs.opensearch.org/latest/query-dsl/compound/hybrid/
- OpenSearch hybrid pagination: https://docs.opensearch.org/latest/vector-search/ai-search/hybrid-search/pagination/
- OpenSearch normalization processor: https://docs.opensearch.org/latest/search-plugins/search-pipelines/normalization-processor/
- OpenSearch Java Client 3.10.0 `OpenSearchClientBase.searchPipeline()`: https://github.com/opensearch-project/opensearch-java/blob/v3.10.0/java-client/src/generated/java/org/opensearch/client/opensearch/OpenSearchClientBase.java
- OpenSearch Java Client 3.10.0 `ScoreNormalizationTechnique`: https://github.com/opensearch-project/opensearch-java/blob/v3.10.0/java-client/src/generated/java/org/opensearch/client/opensearch/search_pipeline/ScoreNormalizationTechnique.java
