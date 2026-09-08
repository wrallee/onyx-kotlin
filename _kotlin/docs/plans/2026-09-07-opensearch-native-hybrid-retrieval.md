# OpenSearch Native Hybrid Retrieval Implementation Plan

## Issues to Address

1. Retrieval configuration is mixed into model-server settings and uses a shallow 50-candidate default.
2. All search types currently embed and retrieve both BM25 and vector candidates.
3. Kotlin performs BM25/vector score normalization and fusion instead of OpenSearch.
4. MCP WRRF hard-codes its default `k` outside search configuration.
5. Adjacent chunks can consume several final result slots.

## Important Notes

- Runtime OpenSearch is `3.6.0` and Java Client is `3.10.0`.
- OpenSearch 3.6 supports `hybrid.filter`, `pagination_depth`, `min_max`, and `z_score`.
- Java Client 3.10.0 has typed `MinMax` and `ArithmeticMean`, but no typed `ZScore` enum.
- Hybrid candidate depth and returned MCP result count are independent.
- Keyword search must remain usable when embedding or hybrid pipeline setup is unavailable.

## Implementation Strategy

### Task 1 — Search configuration

Create validated `SearchProperties` under `onyx.search`. Move candidate depth, normalization, weights, and WRRF `k` into it. Update application, Compose, and example environment defaults.

### Task 2 — Normalization pipeline lifecycle

Create `HybridNormalizationPipeline`, typed `MinMaxNormalizationPipeline`, isolated generic `ZScoreNormalizationPipeline`, and `HybridNormalizationPipelineRegistry`. Register them with the existing OpenSearch client.

### Task 3 — Native search routing

Replace `searchCandidates` with independent keyword, vector, and hybrid methods on `OpenSearchIndexer`. Reuse one filter builder. Hybrid uses one typed `HybridQuery`, `pagination_depth`, configured candidate depth, `size=limit`, and explicit pipeline selection. Remove Kotlin fusion and obsolete Spring AI fusion bridge classes.

### Task 4 — MCP fusion and diversity

Use configured WRRF `k`, keep equal default list weights, correct guidance for same-intent fusion versus independent facets, and collapse adjacent chunk runs after WRRF.

### Task 5 — OpenSearch verification

Add OpenSearch 3.6 integration coverage for both pipeline definitions, native hybrid retrieval, candidate depth versus returned size, filters, and keyword independence.

## Tests

- Configuration binding/validation tests.
- MockWebServer tests for min-max and z-score pipeline payloads.
- SearchService routing tests proving keyword does not embed and semantic/hybrid embed once.
- OpenSearchIndexer request-shape tests for keyword/vector/hybrid.
- MCP WRRF default and adjacent-run tests.
- Testcontainers OpenSearch 3.6 integration tests.
- Final CI: `./gradlew test --no-daemon` and `./gradlew opensearchIntegrationTest --no-daemon`.
