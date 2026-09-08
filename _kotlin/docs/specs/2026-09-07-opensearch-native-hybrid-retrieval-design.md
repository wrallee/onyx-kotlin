# OpenSearch Native Hybrid Retrieval Design

## Goal

Move BM25/vector score fusion from Kotlin to OpenSearch native hybrid search. Keep Spring AI abstractions and MCP-level query fusion.

## Search routing

- `KEYWORD`: BM25 only. Do not call the embedding model.
- `SEMANTIC`: embed once, then k-NN only.
- `HYBRID`: embed once, then one OpenSearch `hybrid` query with BM25 and k-NN clauses.

Hybrid search uses:

- `pagination_depth = 200` by default.
- vector `k = 200` by default.
- request `size = MCP limit`.
- common document-set, source-type, and time filters through `hybrid.filter`.
- explicit request-level search pipeline selection.

## Normalization pipelines

Create two OpenSearch search pipelines as one hybrid-readiness operation:

- `<index>-hybrid-min-max`
- `<index>-hybrid-z-score`

`min_max` is the default and uses the typed OpenSearch Java Client search-pipeline API. `z_score` is optional and uses only `OpenSearchClient.generic()` because `opensearch-java:3.10.0` does not expose `z_score` in `ScoreNormalizationTechnique`.

Both pipelines use `arithmetic_mean` and configured positional weights `[keyword, vector]`. Default weights are `0.5 / 0.5`.

Pipeline failures must fail hybrid retrieval. Keyword and semantic retrieval do not depend on pipeline readiness.

## Configuration

Use `onyx.search.*`:

- `hybrid-candidates`: default `200`, range `1..10000`.
- `hybrid-normalization`: `min_max` or `z_score`, default `min_max`.
- `keyword-weight`: default `0.5`.
- `vector-weight`: default `0.5`.
- `rrf-k`: default `50`.

Weights must be non-negative and sum to `1.0`.

Remove retrieval policy from `onyx.model-server.search-candidates`. Preserve `ONYX_SEARCH_CANDIDATES` as the environment variable.

## MCP WRRF

Keep application-level weighted reciprocal-rank fusion for multiple ranked query results:

`score(d) = sum(weight_i / (k + rank_i(d)))`

Defaults:

- list weights are all `1.0` when omitted.
- `k` comes from `onyx.search.rrf-k`.

Use WRRF for same-intent rewrites, synonyms, or alternate retrieval strategies. Do not blindly fuse independent decomposed subquestions.

## Adjacent chunk diversity

After MCP-level WRRF, group results by document and collapse each maximal consecutive chunk run to its highest-ranked member. Keep non-adjacent chunks independently. Keep results without usable document/chunk identity independently.

Do not concatenate content. Use `get_document_context` for surrounding chunks.

## Non-goals

- No reranker.
- No server-side LLM query expansion.
- No OpenSearch RRF for BM25/vector fusion.
- No fixed `1.3 / 1.0 / 0.7 / 0.5` query-role weights.
- No Kotlin implementation of min-max or z-score math.
- No unrelated connector or ingestion refactor.
