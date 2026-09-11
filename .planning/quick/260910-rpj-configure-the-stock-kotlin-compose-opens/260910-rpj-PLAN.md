---
phase: quick-opensearch-nori-mapping
plan: "260910-rpj"
type: execute
wave: 1
depends_on: []
files_modified:
  - _kotlin/docker-compose.yaml
  - _kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/OpenSearchIndexer.kt
  - _kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/ingestion/OpenSearchIndexerTest.kt
  - _kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/ingestion/OpenSearchIndexerIntegrationTest.kt
  - _kotlin/README.md
autonomous: true
requirements:
  - INDEX-01
  - INDEX-02
  - INDEX-03
  - BUILD-01
estimate:
  tokens: 36000
  raw_tokens: 36000
  tasks: 2
  confidence: low
must_haves:
  truths:
    - "The stock OpenSearch 3.6.0 Compose service installs analysis-nori only when absent, then starts through the stock entrypoint."
    - "Kotlin uses one complete dynamic-strict mapping for new and existing indices; compatible additions merge and incompatible changes fail."
    - "Kotlin never deletes, reindexes, write-blocks, or swaps an existing index while applying the mapping."
    - "The README requires analysis-nori on external/server OpenSearch before backend deployment and gives one plugin-list check."
    - "Runtime verification starts and checks only the Compose OpenSearch service, without building an image or stopping the stack."
  artifacts:
    - path: "_kotlin/docker-compose.yaml"
      provides: "Idempotent analysis-nori startup wrapper around the fixed stock image"
      contains: "opensearchproject/opensearch:3.6.0"
    - path: "_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/OpenSearchIndexer.kt"
      provides: "Single complete strict mapping and non-destructive existing-index update"
    - path: "_kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/ingestion/OpenSearchIndexerTest.kt"
      provides: "Request-shape and incompatible-mapping regression coverage"
    - path: "_kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/ingestion/OpenSearchIndexerIntegrationTest.kt"
      provides: "Real OpenSearch proof for Nori mapping merge and failure semantics"
    - path: "_kotlin/README.md"
      provides: "External/server analysis-nori prerequisite and plugin-list command"
  key_links:
    - from: "_kotlin/docker-compose.yaml"
      to: "/usr/share/opensearch/opensearch-docker-entrypoint.sh"
      via: "The startup wrapper installs the plugin before exec replaces the shell with the stock entrypoint."
      pattern: "opensearch-plugin.*analysis-nori"
    - from: "_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/OpenSearchIndexer.kt"
      to: "OpenSearch PUT /{index}/_mapping"
      via: "ensureIndex sends the same complete mapping used for index creation."
      pattern: "_mapping"
    - from: "_kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/ingestion/OpenSearchIndexerIntegrationTest.kt"
      to: "opensearchproject/opensearch:3.6.0"
      via: "Testcontainers starts the stock image with analysis-nori and exercises mapping behavior."
      pattern: "analysis-nori"
---

<objective>
Configure the stock Compose OpenSearch service for Korean analysis and make Kotlin mapping updates strict, complete, and non-destructive.

Purpose: Developers can start the local OpenSearch service with Nori and deploy Kotlin against compatible existing indices without hidden migration behavior.
Output: Updated Compose startup, one Kotlin mapping definition, focused mapping tests, and a short external-server prerequisite in the existing README.
</objective>

<execution_context>
@/home/wooclee/.codex/gsd-core/workflows/execute-plan.md
@/home/wooclee/.codex/gsd-core/templates/summary.md
</execution_context>

<context>
@.planning/STATE.md
@.planning/PROJECT.md
@.planning/REQUIREMENTS.md
@AGENTS.md
@_kotlin/docker-compose.yaml
@_kotlin/README.md
@_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/OpenSearchIndexer.kt
@_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/opensearch/OpenSearchChunkDocument.kt
@_kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/ingestion/OpenSearchIndexerTest.kt
@_kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/ingestion/OpenSearchIndexerIntegrationTest.kt
@backend/onyx/document_index/opensearch/schema.py
@backend/onyx/document_index/opensearch/opensearch_document_index.py

<interfaces>
`OpenSearchChunkDocument` serializes these root fields in snake case: `cc_pair_id`, `source_document_id`, `chunk_id`, `title`, `content`, `link`, `metadata`, `embedding`, `source_type`, `document_sets`, `doc_updated_at`, `primary_owners`, `secondary_owners`, `external_user_emails`, `external_user_group_ids`, and `is_public`.

Python's reference path builds one complete `dynamic: strict` mapping. It creates a missing index with that mapping. It sends the complete mapping to `PUT _mapping` for an existing index. OpenSearch merges compatible additions and rejects incompatible field changes.

The fixed container image has entrypoint `./opensearch-docker-entrypoint.sh` and command `opensearch`.
</interfaces>
</context>

## Issues to Address

- The stock Compose image lacks `analysis-nori`, but Kotlin text fields need the Nori analyzer.
- Kotlin inspects fragments of the current mapping and can automatically reindex and swap aliases.
- The existing tests encode the automatic migration behavior that this task removes.
- External/server OpenSearch needs a visible plugin prerequisite before backend deployment.

## Important Notes

- Keep `opensearchproject/opensearch:3.6.0`. Do not add an OpenSearch Dockerfile or image build.
- The external/server cluster already has Nori. Only document and verify that prerequisite; do not install its plugin.
- Preserve the operational cluster-block retry around ordinary writes. Remove only the index-migration write block and alias-swap path.
- Never delete or migrate index data automatically. A full manual migration procedure stays outside repository files.
- OpenSearch documents `opensearch-plugin install --batch <plugin>` and complete `PUT /{index}/_mapping` merge behavior.

## Implementation strategy

Use the stock container's writable layer for an idempotent startup install. Replace Kotlin's mapping inspection and migration branch with one mapping body. Prove the request shape with MockWebServer and the merge/error semantics with the real stock OpenSearch test container.

## Tests

Use focused Kotlin unit and OpenSearch integration tests. Testcontainers may start the stock image and install Nori at startup. It must not build an image. Verify Compose at runtime by starting only `opensearch`, then checking its plugin list and cluster health.

## Source Coverage Audit

| Source | ID | Feature or requirement | Task | Status | Notes |
|--------|----|------------------------|------|--------|-------|
| GOAL | — | Stock Compose Nori startup, complete Kotlin mapping update, migration removal, focused tests, and concise README prerequisite | 1, 2 | COVERED | Quick-task description |
| REQ | INDEX-01 | Correct KNN mapping, explicit incompatibility failure, and no automatic index deletion | 1 | COVERED | New and existing index tests |
| REQ | INDEX-02 | OpenSearch Java Client and Jackson 3 mapping path remains intact | 1 | COVERED | Existing client and mapper are reused |
| REQ | INDEX-03 | Chunk operations and ordinary cluster-block retry remain intact | 1 | COVERED | Migration-specific helpers only are removed |
| REQ | BUILD-01 | Clean Kotlin compile and related tests pass | 1 | COVERED | Overall verification |
| RESEARCH | — | Official plugin CLI supports exact-name list and batch installation | 1, 2 | COVERED | Fixed official image and plugin name |
| RESEARCH | — | Complete `PUT _mapping` requests merge additions and reject incompatible existing fields | 1 | COVERED | Python reference behavior and OpenSearch API |
| CONTEXT | C-01 | Keep the stock OpenSearch 3.6.0 image and add no OpenSearch image build | 1 | COVERED | Compose and Testcontainers use the fixed image |
| CONTEXT | C-02 | Install analysis-nori idempotently at Compose startup, then run the stock entrypoint | 1 | COVERED | Exact plugin-list guard precedes `exec` |
| CONTEXT | C-03 | Runtime verification starts and checks only OpenSearch; it never stops the stack | 2 | COVERED | Exact commands are in Task 2 |
| CONTEXT | C-04 | External/server OpenSearch already has Nori; add no server installation action | 2 | COVERED | README contains only a prerequisite and list check |
| CONTEXT | C-05 | Apply one complete strict mapping to existing indices; merge additions and surface incompatibilities | 1 | COVERED | One mapping function drives both paths |
| CONTEXT | C-06 | Remove automatic reindex, migration write-block, and alias-swap behavior without changing data | 1 | COVERED | Regression tests assert the request boundary |
| CONTEXT | C-07 | Tests do not build Docker images | 1 | COVERED | Stock Testcontainers startup only |
| CONTEXT | C-08 | Update the existing README, create no guide file, and leave the full manual migration procedure conversational | 2 | COVERED | One short prerequisite section |

<tasks>

<task type="tracer" tdd="true">
  <name>Task 1: Prove the Nori-backed strict mapping path end to end</name>
  <files>_kotlin/docker-compose.yaml, _kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/OpenSearchIndexer.kt, _kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/ingestion/OpenSearchIndexerTest.kt, _kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/ingestion/OpenSearchIndexerIntegrationTest.kt</files>
  <behavior>
    - Test 1: A new index gets `dynamic: strict`, Nori-analyzed `title` and `content`, every serialized root field, and the configured Lucene HNSW vector dimension.
    - Test 2: An existing compatible index receives one complete `PUT /documents/_mapping`; missing fields merge before the requested write runs.
    - Test 3: An incompatible existing field makes the mapping request fail and the error escapes; no document write or index replacement request follows.
    - Test 4: Arbitrary `metadata` remains storable without dynamic subfield creation, while an unknown root field is rejected.
    - Test 5: The integration container uses the stock 3.6.0 image, installs Nori without an image build, and preserves existing documents during compatible mapping updates and incompatible failures.
  </behavior>
  <action>Write the focused MockWebServer and real OpenSearch assertions first. Replace the Compose `opensearch` startup command with a shell wrapper that exact-matches `analysis-nori` in `/usr/share/opensearch/bin/opensearch-plugin list`, runs `install --batch analysis-nori` only when absent, and then uses `exec` to run `/usr/share/opensearch/opensearch-docker-entrypoint.sh opensearch`. Keep the image tag unchanged and do not add an OpenSearch build source. Configure the integration Testcontainer with the same stock-image startup behavior; it may install the plugin in its container layer but must not build an image.

In `OpenSearchIndexer`, replace `EXACT_FIELDS` and the fragment inspection with one mapping function used by both index creation and existing-index updates. Set root `dynamic` to `strict`. Map all snake-case fields emitted by `OpenSearchChunkDocument`: IDs and filter fields to their exact scalar types; `title` and `content` to `text` with analyzer `nori`, offsets, and the Python-compatible title keyword subfield; `content` as stored; `metadata` as a disabled object so arbitrary values remain in `_source`; `link` as non-indexed metadata; and `embedding` as the current dimension-aware Lucene HNSW cosine vector. For an existing index, send this entire mapping directly to `PUT /{index}/_mapping` and let all OpenSearch errors propagate. Delete `reindexWithExactMappings` and its mapping-read, replacement-index, migration write-block, count, reindex, and alias-swap helpers and their unused imports. Keep `withMigrationRetry` and `OpenSearchWriteBlockedException` because they retry ordinary writes after a cluster block; they must not create, delete, block, reindex, or alias an index. Update request fixtures and remove the obsolete migration-recovery tests.</action>
  <verify>
    <automated>cd _kotlin/backend &amp;&amp; ./gradlew test --tests "com.onyx.foss.kotlin.ingestion.OpenSearchIndexerTest" --no-daemon &amp;&amp; ./gradlew opensearchIntegrationTest --tests "com.onyx.foss.kotlin.ingestion.OpenSearchIndexerIntegrationTest" --no-daemon</automated>
  </verify>
  <done>The fixed stock image can provide Nori without a build, both index paths use one strict mapping, compatible additions preserve data, incompatible updates fail, and no automatic migration request remains.</done>
</task>

<task type="auto">
  <name>Task 2: Document the server prerequisite and verify only Compose OpenSearch</name>
  <precondition>Docker is available, and `OPENSEARCH_ADMIN_PASSWORD` matches the local Compose OpenSearch data volume if that volume already exists.</precondition>
  <files>_kotlin/README.md</files>
  <action>Add a short OpenSearch prerequisite near the Run section. State that every external/server OpenSearch node must have `analysis-nori` installed before backend deployment. Show only `/usr/share/opensearch/bin/opensearch-plugin list | grep -Fx analysis-nori` as the server check; do not add a server install command. State that local Compose installs the plugin idempotently in the stock container. Keep the migration note short: the application applies compatible mapping additions, propagates incompatible mapping errors, and never deletes or migrates index data. Do not create another guide file or place the full manual migration procedure in this README.

Run only the local OpenSearch service with `docker compose -f _kotlin/docker-compose.yaml up -d --no-deps opensearch`. After it starts, inspect only that service with `docker compose exec -T opensearch`: exact-match `analysis-nori` in the plugin list, then call the in-container HTTPS cluster-health endpoint with `wait_for_status=yellow`. Do not start another service and do not stop the Compose project.</action>
  <verify>
    <automated>docker compose -f _kotlin/docker-compose.yaml up -d --no-deps opensearch &amp;&amp; docker compose -f _kotlin/docker-compose.yaml exec -T opensearch /usr/share/opensearch/bin/opensearch-plugin list | grep -Fx analysis-nori &amp;&amp; docker compose -f _kotlin/docker-compose.yaml exec -T opensearch sh -c 'curl -fkSs -u "admin:$OPENSEARCH_INITIAL_ADMIN_PASSWORD" "https://localhost:9200/_cluster/health?wait_for_status=yellow&amp;timeout=90s"'</automated>
  </verify>
  <done>The README has the concise external/server Nori prerequisite and check, and only the Compose OpenSearch service starts and passes plugin-list and health checks.</done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| Container startup to OpenSearch plugin repository | The fixed stock image downloads an executable plugin before starting OpenSearch. |
| Kotlin backend to OpenSearch mapping API | Backend code submits schema changes that can reject writes or alter accepted document shapes. |
| Operator shell to local OpenSearch | Runtime health verification uses the container's admin password without exposing it in plan text. |

## STRIDE Threat Register

| Threat ID | Category | Component | Severity | Disposition | Mitigation Plan |
|-----------|----------|-----------|----------|-------------|-----------------|
| T-QUICK-01 | Tampering | Compose plugin startup | high | mitigate | Pin the stock image to 3.6.0, request the official exact plugin name, use batch mode, and fail before the stock entrypoint if installation fails. |
| T-QUICK-02 | Denial of Service | Existing-index mapping update | high | mitigate | Apply only the complete mapping API request, propagate incompatibility errors, and perform no automatic delete, reindex, write block, or alias swap. |
| T-QUICK-03 | Tampering | Strict document mapping | medium | mitigate | Set root dynamic mode to strict and disable parsing inside the arbitrary metadata object. Cover both behaviors with real OpenSearch tests. |
| T-QUICK-04 | Information Disclosure | Compose health check | low | accept | Read the password only from the container environment and do not echo it; the check returns cluster health only. |
| T-QUICK-SC | Tampering | analysis-nori package retrieval | high | mitigate | Use OpenSearch's version-matched core plugin resolver from the pinned official image and verify the installed plugin name before health acceptance. |
</threat_model>

<verification>
1. Run `cd _kotlin/backend &amp;&amp; ./gradlew clean compileKotlin compileTestKotlin --warning-mode all --no-daemon`.
2. Run the focused MockWebServer and OpenSearch integration commands from Task 1.
3. Run exactly the OpenSearch-only Compose command and two service checks from Task 2. Do not run the full stack, any image build, or `compose down`.
4. Review the diff to confirm that only the five declared files changed and no operator-guide file was added.
</verification>

<success_criteria>
- OpenSearch 3.6.0 starts through its stock entrypoint with `analysis-nori` present after an idempotent startup check.
- New and existing indices receive the same complete strict mapping, with Nori on `title` and `content`.
- Compatible additions merge without data loss; incompatible mappings raise the OpenSearch error to the caller.
- Automatic replacement-index creation, data reindexing, migration write blocks, and alias swaps are absent.
- Focused unit, integration, clean compile, plugin-list, and OpenSearch health checks pass without image builds.
- The existing README contains only the short external/server plugin prerequisite, minimal check, and non-destructive migration boundary.
</success_criteria>

<output>
Create `.planning/quick/260910-rpj-configure-the-stock-kotlin-compose-opens/260910-rpj-SUMMARY.md` when done.
</output>
