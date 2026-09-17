# Kotlin Embedding Reindex Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Kotlin 검색과 수집이 DB의 현재 임베딩 설정을 사용하게 하고, Granite와 Harrier 사이의 전체 재색인 및 증분 전환을 기존 커넥터 흐름으로 안전하게 처리한다.

**Architecture:** 백엔드의 정적 JSON 모델 목록을 단일 기준으로 사용한다. 모든 수집 상태를 `(cc_pair_id, search_settings_id)`로 분리하고, 검색과 OpenSearch 쓰기에 대상 설정을 명시한다. 재색인 조정기는 기존 수집 작업을 예약하며, DB 시각으로 고정한 `cutover_at`까지만 최종 증분 수집을 완료한 뒤 자동 전환한다.

**Tech Stack:** Kotlin 2.3, Spring Boot 4, Spring Data JPA, Flyway, PostgreSQL/H2, OpenSearch Java Client, Python 3.13, FastAPI, React 19, Next.js 16, TypeScript, SWR, Jest, Playwright

**Spec:** `docs/superpowers/specs/2026-09-17-kotlin-embedding-reindex-design.md`

## Global Constraints

- 임베딩 모델은 Granite와 Harrier만 지원한다.
- 원격 공급자와 `openai_compatible` 경로를 모두 제거한다.
- 모델 차원, 정규화, 문맥 길이, 접두사는 백엔드 정적 JSON에서만 관리한다.
- `search_settings`에는 모델 선택과 색인 수명 주기 상태만 저장한다.
- 검색과 일반 수집은 항상 DB의 `PRESENT` 설정을 사용한다.
- 전체 재색인과 증분 전환은 기존 커넥터 로더 및 수집 처리기를 재사용한다.
- 최종 전환 상한은 DB 현재 시각을 한 번 읽어 `cutover_at`과 모든 `poll_range_end`에 저장한다.
- 문서 ID, 원본 ID, 자동 증가 번호를 전환 상한으로 사용하지 않는다.
- `COMPLETED_WITH_ERRORS`, `FAILED`, `CANCELED`가 하나라도 있으면 전환하지 않는다.
- `Full Reindex`는 항상 표시하고 위험 버튼으로 렌더링한다.
- 버튼 문구는 `Sync & Switch`, `Full Reindex`, `Cancel`, `Retry`, `Retry All`을 사용한다.
- 진행 문구는 `Syncing…`, `Reindexing…`을 사용한다.
- 새 의존성이나 공급자 호환 계층을 추가하지 않는다.
- V17은 수정하지 않는다. 새 순방향 마이그레이션만 추가한다.

## 해결할 문제

- 현재 검색과 수집은 DB의 `search_settings`가 아니라 시작 환경변수와 고정 OpenSearch 색인을 사용한다.
- 재색인 설정과 화면은 있으나 실제 백그라운드 수집 및 자동 전환 흐름이 없다.
- 현재 수집 상태는 커넥터별 하나뿐이라 현재 색인과 대상 색인을 동시에 처리할 수 없다.
- 공급자, 문서 복사, 사용하지 않는 설정 컬럼이 남아 있다.

## 중요 사항

- 현재 색인은 대상의 첫 수집 동안 계속 검색과 증분 수집을 처리한다.
- 최종 전환 단계에서만 현재 색인의 새 작업 예약을 멈춘다.
- 고정 범위 수집이 실패하면 현재 색인 예약을 재개한다. 실패 범위 재시도가 끝난 뒤 새 DB 시각으로 마지막 범위를 다시 고정한다.
- 대상별 체크포인트와 마지막 성공 범위를 유지하므로 과거 색인으로 돌아갈 때 전체 재색인이 필요하지 않다.
- 일시 중지된 커넥터는 대상 작업에 한 번 참여하지만 저장된 일시 중지 상태는 바뀌지 않는다.
- 커넥터 삭제는 보관 중인 모든 물리 색인에서 해당 커넥터 문서를 제거한다.

## 파일 책임 배치

- `local-embedding-models.json`: 지원 모델과 불변 실행 사양의 단일 기준.
- `LocalEmbeddingModelRegistry.kt`: JSON 로드, 중복/범위 검증, 실행 설정과 물리 색인 후보 계산.
- `IndexSettings*.kt`: 현재/대상/과거 설정 조회와 상태 전환.
- `ReindexCoordinator.kt`: 재색인 시작, 고정 상한 생성, 재시도, 취소, 자동 전환.
- `Ingestion*.kt`: 대상 설정별 시도, 작업, 체크포인트, 문서 메타데이터 처리.
- `OpenSearchIndexer.kt`: 호출자가 지정한 물리 색인과 차원에만 읽고 쓴다.
- `IndexSettingsPage/*`: 로컬 모델 선택과 재색인 상태만 표시한다.

## 구현 전략

1. 공급자와 중복 모델 설정을 제거하고 정적 모델 목록을 만든다.
2. 검색과 OpenSearch 호출에 실행 대상을 명시한다.
3. 수집 상태와 임대를 대상 설정별로 분리한다.
4. 기존 수집 작업을 예약하는 재색인 조정기를 추가한다.
5. 커넥터 생성, 삭제, 일시 중지, 문서 집합 동기화를 대상별로 맞춘다.
6. 관리자 화면을 두 로컬 모델과 승인된 작업 버튼으로 줄인다.
7. 문서와 전체 검증을 마친다.

## 테스트

- DB와 작업 수명 주기는 H2 통합 테스트를 우선한다.
- OpenSearch 대상 라우팅과 차원 검증은 기존 단위 테스트 및 OpenSearch 통합 테스트를 확장한다.
- 모델 서버는 로컬 모델 계약 테스트를 유지하고 원격 공급자 테스트를 삭제한다.
- 화면은 서비스 함수 Jest 테스트와 핵심 Playwright 흐름만 유지한다.

---

### Task 1: 로컬 모델 목록과 설정 스키마 정리

**Files:**
- Create: `_kotlin/backend/src/main/resources/local-embedding-models.json`
- Create: `_kotlin/backend/src/main/kotlin/com/onyx/kotlin/indexing/LocalEmbeddingModelRegistry.kt`
- Create: `_kotlin/backend/src/main/kotlin/com/onyx/kotlin/opensearch/OpenSearchIndexTarget.kt`
- Create: `_kotlin/backend/src/main/resources/db/migration/V18__local_embedding_settings.sql`
- Modify: `_kotlin/backend/src/main/resources/application.yml`
- Modify: `_kotlin/backend/src/main/kotlin/com/onyx/kotlin/indexing/IndexSettingsEntities.kt`
- Modify: `_kotlin/backend/src/main/kotlin/com/onyx/kotlin/indexing/IndexSettingsService.kt`
- Modify: `_kotlin/backend/src/main/kotlin/com/onyx/kotlin/indexing/IndexSettingsApi.kt`
- Modify: `_kotlin/backend/src/test/kotlin/com/onyx/kotlin/indexing/IndexSettingsIntegrationTest.kt`
- Modify: `_kotlin/backend/src/test/kotlin/com/onyx/kotlin/support/schema/MigrationSmokeTest.kt`

**Interfaces:**
- Consumes: `OnyxProperties.opensearch.index`와 `OpenSearchVectorStoreProperties.indexName`의 기존 기본 색인 이름.
- Produces: `LocalEmbeddingModelRegistry.require(modelName: String): LocalEmbeddingModel`.
- Produces: `OpenSearchIndexTarget(name: String, dimension: Int)`.
- Produces: `IndexSettingsService.currentRuntime(): SearchRuntimeSettings`와 `runtime(settingsId: Long): SearchRuntimeSettings`.
- Produces: `GET /admin/embedding/models`, 현재 및 준비 중 설정 조회 API.

- [ ] **Step 1: 모델 목록과 마이그레이션 실패 테스트 작성**

```kotlin
@Test
fun `registry exposes only Granite and Harrier with fixed dimensions`() {
    assertThat(registry.all().map { it.modelName to it.dimension }).containsExactly(
        "ibm-granite/granite-embedding-311m-multilingual-r2" to 768,
        "microsoft/harrier-oss-v1-0.6b" to 1024,
    )
}

@Test
fun `migration removes provider and port state and creates Granite current setting`() {
    assertThat(tableExists("embedding_providers")).isFalse()
    assertThat(tableExists("reindex_port_attempts")).isFalse()
    assertThat(columnExists("connector_credential_pairs", "full_recollect_requested")).isFalse()
    assertThat(columnExists("search_settings", "provider_type")).isFalse()
    assertThat(columnExists("search_settings", "model_dim")).isFalse()
    assertThat(jdbc.queryForObject(
        "SELECT model_name FROM search_settings WHERE status = 'PRESENT'",
        String::class.java,
    )).isEqualTo("ibm-granite/granite-embedding-311m-multilingual-r2")
}

private fun tableExists(table: String): Boolean = jdbc.queryForObject(
    "SELECT COUNT(*) > 0 FROM information_schema.tables WHERE table_schema = 'public' AND table_name = ?",
    Boolean::class.java,
    table,
) == true

private fun columnExists(table: String, column: String): Boolean = jdbc.queryForObject(
    "SELECT COUNT(*) > 0 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
    Boolean::class.java,
    table,
    column,
) == true
```

- [ ] **Step 2: 대상 테스트가 실패하는지 확인**

Run: `cd _kotlin/backend && ./gradlew --no-daemon --max-workers=1 test --tests '*IndexSettingsIntegrationTest' --tests '*MigrationSmokeTest'`

Expected: FAIL. 모델 목록 파일과 V18 마이그레이션이 아직 없고 기존 공급자 테이블이 남아 있다.

- [ ] **Step 3: 정적 JSON과 레지스트리 구현**

```json
{
  "models": [
    {
      "model_name": "ibm-granite/granite-embedding-311m-multilingual-r2",
      "display_name": "Granite",
      "dimension": 768,
      "normalize": true,
      "max_context_length": 512,
      "query_prefix": null,
      "passage_prefix": null,
      "index_key": "granite"
    },
    {
      "model_name": "microsoft/harrier-oss-v1-0.6b",
      "display_name": "Harrier",
      "dimension": 1024,
      "normalize": true,
      "max_context_length": 512,
      "query_prefix": null,
      "passage_prefix": null,
      "index_key": "harrier"
    }
  ]
}
```

```kotlin
data class LocalEmbeddingModel(
    val modelName: String,
    val displayName: String,
    val dimension: Int,
    val normalize: Boolean,
    val maxContextLength: Int,
    val queryPrefix: String?,
    val passagePrefix: String?,
    val indexKey: String,
)

data class OpenSearchIndexTarget(val name: String, val dimension: Int)

@Service
class LocalEmbeddingModelRegistry(
    mapper: ObjectMapper,
    resourceLoader: ResourceLoader,
) {
    fun all(): List<LocalEmbeddingModel>
    fun require(modelName: String): LocalEmbeddingModel
    fun indexNames(modelName: String, baseIndexName: String): List<String>
}
```

`indexNames`는 Granite에 `[base, "$base-granite-alt"]`, Harrier에 `["$base-harrier", "$base-harrier-alt"]`를 반환한다. 시작 시 빈 이름, 중복 모델명, 중복 `index_key`, 0 이하 차원과 문맥 길이를 거부한다.

- [ ] **Step 4: V18 순방향 마이그레이션과 설정 엔티티 정리**

```sql
INSERT INTO search_settings(model_name, model_dim, normalize, index_name, status, singleton_marker)
SELECT
    'ibm-granite/granite-embedding-311m-multilingual-r2',
    768,
    TRUE,
    '${opensearchIndex}',
    'PRESENT',
    1
WHERE NOT EXISTS (SELECT 1 FROM search_settings WHERE status = 'PRESENT');

ALTER TABLE search_settings DROP COLUMN provider_type;
ALTER TABLE search_settings DROP COLUMN model_dim;
ALTER TABLE search_settings DROP COLUMN normalize;
ALTER TABLE search_settings DROP COLUMN query_prefix;
ALTER TABLE search_settings DROP COLUMN passage_prefix;
ALTER TABLE search_settings ADD COLUMN cutover_at TIMESTAMP WITH TIME ZONE;

DROP TABLE reindex_port_attempts;
DROP TABLE embedding_providers;
ALTER TABLE connector_credential_pairs DROP COLUMN full_recollect_requested;
```

`application.yml`의 `spring.flyway.placeholders.opensearchIndex`는 `${OPENSEARCH_INDEX:onyx-kotlin-chunks}`를 사용한다. V17은 그대로 둔다.

- [ ] **Step 5: 설정 서비스와 API를 모델명 중심으로 축소**

```kotlin
data class SearchRuntimeSettings(
    val settingsId: Long,
    val modelName: String,
    val embedding: EmbeddingExecutionConfig,
    val index: OpenSearchIndexTarget,
)

data class LocalEmbeddingModelResponse(
    val modelName: String,
    val displayName: String,
    val dimension: Int,
    val available: Boolean,
    val status: String,
    val compatiblePastSettingsId: Long?,
)
```

`EmbeddingProviderType`, `EmbeddingProviderEntity`, 공급자 저장소와 CRUD API를 삭제한다. `SearchSettingsResponse`는 `id`, `model_name`, `index_name`, `status`, 세 수명 주기 시각만 반환한다. 현재 설정이 없으면 Granite와 기본 OpenSearch 색인 이름으로 한 행을 만든다.

- [ ] **Step 6: 설정 및 마이그레이션 테스트 실행**

Run: `cd _kotlin/backend && ./gradlew --no-daemon --max-workers=1 test --tests '*IndexSettingsIntegrationTest' --tests '*MigrationSmokeTest'`

Expected: PASS. 모델 목록은 두 개뿐이며 제거 대상 테이블과 컬럼이 존재하지 않는다.

- [ ] **Step 7: 커밋**

```bash
git add _kotlin/backend/src/main/resources/local-embedding-models.json \
  _kotlin/backend/src/main/resources/db/migration/V18__local_embedding_settings.sql \
  _kotlin/backend/src/main/resources/application.yml \
  _kotlin/backend/src/main/kotlin/com/onyx/kotlin/indexing \
  _kotlin/backend/src/main/kotlin/com/onyx/kotlin/opensearch/OpenSearchIndexTarget.kt \
  _kotlin/backend/src/test/kotlin/com/onyx/kotlin/indexing/IndexSettingsIntegrationTest.kt \
  _kotlin/backend/src/test/kotlin/com/onyx/kotlin/support/schema/MigrationSmokeTest.kt
git commit -m "feat(kotlin): use local embedding model registry"
```

### Task 2: 모델 서버와 클라이언트를 로컬 전용으로 축소

**Files:**
- Modify: `_kotlin/model-server/app/contracts.py`
- Modify: `_kotlin/model-server/app/runtime.py`
- Modify: `_kotlin/model-server/app/config.py`
- Modify: `_kotlin/model-server/app/main.py`
- Modify: `_kotlin/model-server/tests/test_api.py`
- Modify: `_kotlin/model-server/tests/test_runtime.py`
- Modify: `_kotlin/model-server/requirements.txt`
- Modify: `_kotlin/model-server/Dockerfile`
- Modify: `_kotlin/backend/src/main/kotlin/com/onyx/kotlin/model/ModelServerClient.kt`
- Modify: `_kotlin/backend/src/test/kotlin/com/onyx/kotlin/model/ModelServerClientTest.kt`

**Interfaces:**
- Consumes: `LocalEmbeddingModel`에서 만든 `EmbeddingExecutionConfig`.
- Produces: 공급자 필드가 없는 로컬 모델 요청 계약.
- Preserves until Task 3: 기존 무인자 메서드. Task 3에서 모든 호출자를 바꾼 뒤 삭제한다.
- Produces: `/api/model-status`의 Granite와 Harrier별 준비 상태.

- [ ] **Step 1: 공급자 필드 거부와 명시 설정 전송 테스트 작성**

```python
def test_provider_fields_are_not_part_of_the_contract() -> None:
    fields = EmbedRequest.model_fields
    assert "provider_type" not in fields
    assert "api_url" not in fields
    assert "api_key" not in fields
```

```kotlin
@Test
fun `query embedding uses the supplied local model config`(): Unit = MockWebServer().use { server ->
    server.enqueue(
        MockResponse().setHeader("Content-Type", "application/json")
            .setBody("""{"embeddings":[[0.1,0.2]]}"""),
    )
    server.start()
    val harrier = "microsoft/harrier-oss-v1-0.6b"

    client(server).embedQuery("검색어", EmbeddingExecutionConfig(harrier, 2, true, 512))

    val body = jacksonObjectMapper().readTree(server.takeRequest().body.readUtf8())
    assertThat(body.path("model_name").asString()).isEqualTo(harrier)
    assertThat(body.has("provider_type")).isFalse()
}
```

- [ ] **Step 2: 모델 서버와 클라이언트 테스트 실패 확인**

Run: `cd _kotlin/model-server && .venv/bin/python -m pytest -q tests/test_api.py tests/test_runtime.py`

Run: `cd _kotlin/backend && ./gradlew --no-daemon --max-workers=1 test --tests '*ModelServerClientTest'`

Expected: FAIL. 공급자 계약과 요청 필드가 아직 존재한다.

- [ ] **Step 3: Python 공급자 구현 삭제**

```python
class EmbedRequest(BaseModel):
    model_config = ConfigDict(protected_namespaces=())
    texts: list[str]
    model_name: str
    max_context_length: int = Field(default=512, ge=1, le=32768)
    normalize_embeddings: bool = True
    text_type: EmbedTextType
    manual_query_prefix: str | None = None
    manual_passage_prefix: str | None = None
```

`ApiTokenizer`, `_provider_tokenizer`, `_validate_provider`, `_provider_client`, `_remote_embeddings`, 공급자 시간 제한 설정을 삭제한다. `tiktoken`과 Docker의 토큰 캐시 준비도 삭제한다. `httpx`는 FastAPI `TestClient`가 사용하므로 유지한다. `EMBEDDING_MODEL_NAME`은 실행 모델 선택에 사용하지 않고 모든 요청의 `model_name`을 필수로 한다.

- [ ] **Step 4: Kotlin 클라이언트의 공급자 경로 삭제**

```kotlin
fun embed(texts: List<String>, config: EmbeddingExecutionConfig): List<List<Double>>
fun embedQuery(query: String, config: EmbeddingExecutionConfig): List<Double>
fun chunkAndEmbed(
    text: String,
    title: String,
    metadataContext: String,
    config: EmbeddingExecutionConfig,
): List<ChunkEmbedding>
```

`OpenAiCompatibleEmbeddingProvider`, `providerFields`, URL 검증 함수를 삭제한다. Task 3이 호출자를 한 번에 바꿀 수 있도록 기존 무인자 메서드와 `defaultConfig()`는 이 커밋에서만 유지한다. 모든 명시 설정 응답은 전달된 `modelDim`과 유한값 조건을 계속 검증한다.

- [ ] **Step 5: 로컬 모델 테스트 실행**

Run: `cd _kotlin/model-server && .venv/bin/python -m pytest -q`

Run: `cd _kotlin/backend && ./gradlew --no-daemon --max-workers=1 test --tests '*ModelServerClientTest'`

Expected: PASS. 공급자 테스트는 사라지고 Granite 및 Harrier 테스트는 유지된다.

- [ ] **Step 6: 커밋**

```bash
git add _kotlin/model-server _kotlin/backend/src/main/kotlin/com/onyx/kotlin/model/ModelServerClient.kt \
  _kotlin/backend/src/test/kotlin/com/onyx/kotlin/model/ModelServerClientTest.kt
git commit -m "refactor(kotlin): remove remote embedding providers"
```

### Task 3: 검색과 OpenSearch에 실행 대상을 명시

**Files:**
- Modify: `_kotlin/backend/src/main/kotlin/com/onyx/kotlin/config/OnyxProperties.kt`
- Modify: `_kotlin/backend/src/main/resources/application.yml`
- Modify: `_kotlin/backend/src/main/kotlin/com/onyx/kotlin/opensearch/OpenSearchIndexer.kt`
- Modify: `_kotlin/backend/src/main/kotlin/com/onyx/kotlin/search/SearchService.kt`
- Modify: `_kotlin/backend/src/main/kotlin/com/onyx/kotlin/ingestion/IngestionProcessor.kt`
- Modify: `_kotlin/backend/src/main/kotlin/com/onyx/kotlin/ingestion/PruningService.kt`
- Modify: `_kotlin/backend/src/main/kotlin/com/onyx/kotlin/documentset/DocumentSetSyncWorker.kt`
- Modify: `_kotlin/backend/src/test/kotlin/com/onyx/kotlin/opensearch/OpenSearchIndexerTest.kt`
- Modify: `_kotlin/backend/src/test/kotlin/com/onyx/kotlin/opensearch/OpenSearchIndexerIntegrationTest.kt`
- Modify: `_kotlin/backend/src/test/kotlin/com/onyx/kotlin/search/SearchServiceTest.kt`
- Modify: `_kotlin/backend/src/test/kotlin/com/onyx/kotlin/ingestion/IngestionProcessorIntegrationTest.kt`

**Interfaces:**
- Consumes: `IndexSettingsService.currentRuntime()`.
- Consumes: `OpenSearchIndexTarget(name: String, dimension: Int)`.
- Produces: 모든 OpenSearch 읽기와 쓰기 메서드의 첫 인자 `target: OpenSearchIndexTarget`.
- Produces: 설정 인자가 필수인 `ModelServerClient` 메서드. 무인자 메서드와 `defaultConfig()`는 이 작업에서 삭제한다.

- [ ] **Step 1: DB 현재 설정 라우팅 테스트 작성**

```kotlin
@Test
fun `semantic search uses the current database model and index`() {
    val embedding = EmbeddingExecutionConfig(
        modelName = "microsoft/harrier-oss-v1-0.6b",
        modelDim = 1024,
        normalize = true,
        maxContextLength = 512,
    )
    val runtime = SearchRuntimeSettings(
        settingsId = 2,
        modelName = embedding.modelName,
        embedding = embedding,
        index = OpenSearchIndexTarget("chunks-harrier", 1024),
    )
    whenever(settings.currentRuntime()).thenReturn(runtime)
    whenever(modelServer.embedQuery("deployment guide", embedding)).thenReturn(List(1024) { 0.1 })
    whenever(indexer.vectorSearch(runtime.index, List(1024) { 0.1 }, emptyList(), 30, emptyList(), null))
        .thenReturn(emptyList())

    service.search("deployment guide", searchType = SearchType.SEMANTIC)

    verify(modelServer).embedQuery("deployment guide", embedding)
    verify(indexer).vectorSearch(runtime.index, List(1024) { 0.1 }, emptyList(), 30, emptyList(), null)
}

@Test
fun `index mapping uses the target dimension`(): Unit = MockWebServer().use { server ->
    server.enqueue(MockResponse().setResponseCode(404))
    server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
    server.enqueue(MockResponse().setResponseCode(200).setBody("""{"result":"created"}"""))
    server.start()
    val indexer = OpenSearchIndexer(testProperties(server), null, mapper, externalWrites)

    indexer.upsert(
        target = OpenSearchIndexTarget("alternate", 1024),
        pairId = 1,
        sourceDocumentId = "doc-1",
        chunkId = 0,
        title = "Title",
        content = "Content",
        link = null,
        metadata = emptyMap(),
        embedding = List(1024) { 0.0 },
    )

    server.takeRequest()
    val mapping = mapper.readTree(server.takeRequest().body.readUtf8())
    assertThat(mapping.at("/mappings/properties/embedding/dimension").asInt()).isEqualTo(1024)
}
```

- [ ] **Step 2: 검색과 OpenSearch 테스트 실패 확인**

Run: `cd _kotlin/backend && ./gradlew --no-daemon --max-workers=1 test --tests '*SearchServiceTest' --tests '*OpenSearchIndexerTest'`

Expected: FAIL. 현재 메서드는 고정 색인과 환경변수 차원을 사용한다.

- [ ] **Step 3: OpenSearch 호출을 대상 인자 방식으로 변경**

```kotlin
fun keywordSearch(
    target: OpenSearchIndexTarget,
    query: String,
    documentSets: List<String>,
    count: Int,
    sourceTypes: List<String> = emptyList(),
    updatedAfter: Instant? = null,
    metadataFilters: SearchMetadataFilters = SearchMetadataFilters(),
): List<SearchCandidate>
fun vectorSearch(
    target: OpenSearchIndexTarget,
    queryEmbedding: List<Double>,
    documentSets: List<String>,
    count: Int,
    sourceTypes: List<String> = emptyList(),
    updatedAfter: Instant? = null,
    metadataFilters: SearchMetadataFilters = SearchMetadataFilters(),
): List<SearchCandidate>
fun upsert(
    target: OpenSearchIndexTarget,
    pairId: Long,
    sourceDocumentId: String,
    chunkId: Int,
    title: String,
    content: String,
    link: String?,
    metadata: Map<String, Any?>,
    embedding: List<Double>,
    documentSets: List<String> = emptyList(),
    updatedAt: Instant? = null,
    primaryOwners: List<String> = emptyList(),
    secondaryOwners: List<String> = emptyList(),
    sourceType: ConnectorSource? = null,
    indexedMetadata: IndexedMetadata = IndexedMetadata(),
)
fun deleteDocuments(target: OpenSearchIndexTarget, pairId: Long, sourceDocumentIds: Set<String>)
fun updateDocumentSets(target: OpenSearchIndexTarget, pairId: Long, sourceDocumentIds: Set<String>, names: List<String>)
fun resetIndex(target: OpenSearchIndexTarget)
fun deleteIndex(indexName: String)
```

고정 `properties.indexName`과 `modelServerDimension` 사용을 제거한다. 준비 완료 상태는 `ConcurrentHashMap.newKeySet<String>()`로 물리 색인별 관리한다. `resetIndex`와 `deleteIndex`는 `PairExternalWriteFence.withOpenSearchIndex` 안에서 실행한다.

- [ ] **Step 4: 검색과 임시 수집 경로에서 현재 런타임 설정 사용**

```kotlin
val runtime = indexSettings.currentRuntime()
val queryEmbedding = modelServer.embedQuery(query, runtime.embedding)
return indexer.hybridSearch(runtime.index, query, queryEmbedding, documentSets, limit, sourceTypes, timeCutoff)
```

Task 4의 대상별 수집 전까지 `IngestionProcessor`도 `currentRuntime()`을 사용한다. `PruningService`와 `DocumentSetSyncWorker`는 호출자가 `OpenSearchIndexTarget`을 전달하도록 바꾼다.

모든 호출자가 명시 설정을 넘기게 된 뒤 `ModelServerClient`의 무인자 `embed`, `embedQuery`, `chunkAndEmbed`와 `defaultConfig()`를 삭제한다.

- [ ] **Step 5: 임베딩 모델 환경 설정 제거**

`OnyxProperties.ModelServer`와 `application.yml`에서 `modelName`, `embeddingDimension`, `maxContextLength`, `normalizeEmbeddings` 및 해당 `ONYX_EMBEDDING_*` 변수를 삭제한다. 연결 시간 제한과 재시도 설정은 유지한다.

- [ ] **Step 6: 대상 라우팅 테스트 실행**

Run: `cd _kotlin/backend && ./gradlew --no-daemon --max-workers=1 test --tests '*SearchServiceTest' --tests '*OpenSearchIndexerTest' --tests '*IngestionProcessorIntegrationTest'`

Expected: PASS. 검색, 수집, 삭제가 전달된 물리 색인만 사용한다.

- [ ] **Step 7: 커밋**

```bash
git add _kotlin/backend/src/main/kotlin/com/onyx/kotlin/config \
  _kotlin/backend/src/main/resources/application.yml \
  _kotlin/backend/src/main/kotlin/com/onyx/kotlin/opensearch/OpenSearchIndexer.kt \
  _kotlin/backend/src/main/kotlin/com/onyx/kotlin/search/SearchService.kt \
  _kotlin/backend/src/main/kotlin/com/onyx/kotlin/ingestion \
  _kotlin/backend/src/main/kotlin/com/onyx/kotlin/documentset/DocumentSetSyncWorker.kt \
  _kotlin/backend/src/test/kotlin/com/onyx/kotlin
git commit -m "refactor(kotlin): route search through current index settings"
```

### Task 4: 수집 상태를 색인 설정별로 분리

**Files:**
- Create: `_kotlin/backend/src/main/resources/db/migration/V19__target_scoped_ingestion.sql`
- Modify: `_kotlin/backend/src/main/kotlin/com/onyx/kotlin/connector/ConnectorEntities.kt`
- Modify: `_kotlin/backend/src/main/kotlin/com/onyx/kotlin/ingestion/IngestionEntities.kt`
- Modify: `_kotlin/backend/src/main/kotlin/com/onyx/kotlin/ingestion/IngestionRepositories.kt`
- Modify: `_kotlin/backend/src/main/kotlin/com/onyx/kotlin/ingestion/IngestionCommandService.kt`
- Modify: `_kotlin/backend/src/main/kotlin/com/onyx/kotlin/ingestion/IngestionScheduler.kt`
- Modify: `_kotlin/backend/src/main/kotlin/com/onyx/kotlin/ingestion/JobClaimService.kt`
- Modify: `_kotlin/backend/src/main/kotlin/com/onyx/kotlin/ingestion/IngestionProcessor.kt`
- Modify: `_kotlin/backend/src/main/kotlin/com/onyx/kotlin/ingestion/PruningService.kt`
- Modify: `_kotlin/backend/src/main/kotlin/com/onyx/kotlin/ingestion/IngestionQueryService.kt`
- Modify: `_kotlin/backend/src/test/kotlin/com/onyx/kotlin/ingestion/IngestionProcessorIntegrationTest.kt`
- Modify: `_kotlin/backend/src/test/kotlin/com/onyx/kotlin/support/schema/MigrationSmokeTest.kt`

**Interfaces:**
- Consumes: `IndexSettingsService.runtime(settingsId)`와 `OpenSearchIndexTarget`.
- Produces: `enqueuePair(pairId: Long, searchSettingsId: Long, fromBeginning: Boolean, pruneOnly: Boolean = false, pollRangeEnd: Instant? = null): Long`.
- Produces: 대상별 최신 시도, 체크포인트, 문서 조회 저장소 메서드.

- [ ] **Step 1: 병렬 대상과 체크포인트 분리 테스트 작성**

```kotlin
@Test
fun `same pair can queue one job per search setting`() {
    val currentJob = commands.enqueuePair(pairId, currentId, fromBeginning = false)
    val futureJob = commands.enqueuePair(pairId, futureId, fromBeginning = true)
    assertThat(currentJob).isNotEqualTo(futureJob)
    assertThat(commands.enqueuePair(pairId, futureId, fromBeginning = true)).isEqualTo(futureJob)
}

@Test
fun `future ingestion writes only its checkpoint documents and index`() {
    processor.process(futureJob)
    assertThat(checkpoints.findById(IngestionCheckpointId(pairId, futureId))).isPresent
    assertThat(documents.countByCcPairIdAndSearchSettingsId(pairId, futureId)).isPositive()
    verify(indexer).upsert(eq(futureRuntime.index), eq(pairId), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
}
```

- [ ] **Step 2: 수집 분리 테스트 실패 확인**

Run: `cd _kotlin/backend && ./gradlew --no-daemon --max-workers=1 test --tests '*IngestionProcessorIntegrationTest' --tests '*MigrationSmokeTest'`

Expected: FAIL. 동일 커넥터의 두 대상 작업이 현재 고유 제약과 체크포인트를 공유한다.

- [ ] **Step 3: V19에서 기존 상태를 현재 설정으로 이관**

```sql
ALTER TABLE ingestion_attempts ADD COLUMN search_settings_id BIGINT REFERENCES search_settings(id) ON DELETE CASCADE;
ALTER TABLE ingestion_jobs ADD COLUMN search_settings_id BIGINT REFERENCES search_settings(id) ON DELETE CASCADE;
ALTER TABLE ingestion_checkpoints ADD COLUMN search_settings_id BIGINT REFERENCES search_settings(id) ON DELETE CASCADE;
ALTER TABLE indexed_documents ADD COLUMN search_settings_id BIGINT REFERENCES search_settings(id) ON DELETE CASCADE;

UPDATE ingestion_attempts SET search_settings_id = (SELECT id FROM search_settings WHERE status = 'PRESENT');
UPDATE ingestion_jobs SET search_settings_id = (SELECT id FROM search_settings WHERE status = 'PRESENT');
UPDATE ingestion_checkpoints SET search_settings_id = (SELECT id FROM search_settings WHERE status = 'PRESENT');
UPDATE indexed_documents SET search_settings_id = (SELECT id FROM search_settings WHERE status = 'PRESENT');

ALTER TABLE ingestion_attempts ALTER COLUMN search_settings_id SET NOT NULL;
ALTER TABLE ingestion_jobs ALTER COLUMN search_settings_id SET NOT NULL;
ALTER TABLE ingestion_checkpoints ALTER COLUMN search_settings_id SET NOT NULL;
ALTER TABLE indexed_documents ALTER COLUMN search_settings_id SET NOT NULL;

ALTER TABLE ingestion_jobs DROP CONSTRAINT uq_ingestion_job_active_pair;
ALTER TABLE ingestion_jobs ADD CONSTRAINT uq_ingestion_job_active_target
    UNIQUE (cc_pair_id, search_settings_id, active_marker);
ALTER TABLE indexed_documents DROP CONSTRAINT uq_indexed_document_source;
ALTER TABLE indexed_documents ADD CONSTRAINT uq_indexed_document_target_source
    UNIQUE (cc_pair_id, search_settings_id, source_document_id);
```

체크포인트 기본 키를 `(cc_pair_id, search_settings_id)`로 바꾼다. 대상별 작업 임대를 사용하므로 `connector_credential_pairs.ingestion_claim_token`과 `ingestion_lease_expires_at`도 제거한다.

- [ ] **Step 4: 엔티티와 저장소를 복합 대상 기준으로 변경**

```kotlin
data class IngestionCheckpointId(
    var ccPairId: Long = 0,
    var searchSettingsId: Long = 0,
) : Serializable

fun findFirstByCcPairIdAndSearchSettingsIdOrderByIdDesc(
    ccPairId: Long,
    searchSettingsId: Long,
): IngestionAttemptEntity?

fun findByCcPairIdAndSearchSettingsIdAndSourceDocumentId(
    ccPairId: Long,
    searchSettingsId: Long,
    sourceDocumentId: String,
): IndexedDocumentEntity?
```

오류와 열거 행은 `attempt_id`를 통해 대상이 정해지므로 중복 `search_settings_id` 컬럼을 추가하지 않는다. 조회 쿼리는 연결된 시도의 대상 ID를 조건에 넣는다.

- [ ] **Step 5: 작업 임대와 처리기를 대상별로 변경**

```kotlin
data class IngestionClaim(
    val jobId: Long,
    val pairId: Long,
    val searchSettingsId: Long,
    val attemptId: Long,
    val token: UUID,
)

enum class JobState { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELED }
```

처리 시작 시 `indexSettings.runtime(claim.searchSettingsId)`를 한 번 읽는다. 해당 설정의 임베딩 구성과 물리 색인을 모든 배치에 사용한다. `setPollRange`는 같은 커넥터와 같은 설정의 이전 시도만 조회하고 기존 30분 중복 구간을 유지한다.

- [ ] **Step 6: 현재 대상만 커넥터 운영 상태를 바꾸도록 제한**

`JobClaimService.start`, `complete`, `fail`은 작업의 `searchSettingsId`가 현재 `PRESENT`일 때만 `PairStatus`, 반복 오류, `lastPrunedAt`을 변경한다. `FUTURE` 작업은 일시 중지 상태를 무시하고 처리하되 커넥터의 저장 상태를 수정하지 않는다.

- [ ] **Step 7: 대상 분리 통합 테스트 실행**

Run: `cd _kotlin/backend && ./gradlew --no-daemon --max-workers=1 test --tests '*IngestionProcessorIntegrationTest' --tests '*AdminDeletionIntegrationTest' --tests '*MigrationSmokeTest'`

Expected: PASS. 같은 커넥터의 현재 및 대상 작업이 별도 시도, 체크포인트, 문서 메타데이터를 갖는다.

- [ ] **Step 8: 커밋**

```bash
git add _kotlin/backend/src/main/resources/db/migration/V19__target_scoped_ingestion.sql \
  _kotlin/backend/src/main/kotlin/com/onyx/kotlin/connector/ConnectorEntities.kt \
  _kotlin/backend/src/main/kotlin/com/onyx/kotlin/ingestion \
  _kotlin/backend/src/test/kotlin/com/onyx/kotlin/ingestion \
  _kotlin/backend/src/test/kotlin/com/onyx/kotlin/support/schema/MigrationSmokeTest.kt
git commit -m "feat(kotlin): scope ingestion state by index settings"
```

### Task 5: 재색인 조정기와 고정 범위 자동 전환

**Files:**
- Create: `_kotlin/backend/src/main/kotlin/com/onyx/kotlin/indexing/ReindexCoordinator.kt`
- Create: `_kotlin/backend/src/test/kotlin/com/onyx/kotlin/indexing/ReindexCoordinatorIntegrationTest.kt`
- Modify: `_kotlin/backend/src/main/kotlin/com/onyx/kotlin/indexing/IndexSettingsEntities.kt`
- Modify: `_kotlin/backend/src/main/kotlin/com/onyx/kotlin/indexing/IndexSettingsService.kt`
- Modify: `_kotlin/backend/src/main/kotlin/com/onyx/kotlin/indexing/IndexSettingsApi.kt`
- Modify: `_kotlin/backend/src/main/kotlin/com/onyx/kotlin/ingestion/IngestionRepositories.kt`
- Modify: `_kotlin/backend/src/main/kotlin/com/onyx/kotlin/ingestion/IngestionCommandService.kt`
- Modify: `_kotlin/backend/src/main/kotlin/com/onyx/kotlin/ingestion/IngestionScheduler.kt`
- Modify: `_kotlin/backend/src/main/kotlin/com/onyx/kotlin/ingestion/IngestionProcessor.kt`

**Interfaces:**
- Consumes: 대상별 수집 명령과 `OpenSearchIndexer.resetIndex/deleteIndex`.
- Produces: `startFull(modelName)`, `startSync(modelName)`, `cancel()`, `retry(pairId)`, `retryAll()`, `advance()`.
- Produces: 재색인 진행 및 오류 API.

- [ ] **Step 1: 전체 재색인과 고정 상한 테스트 작성**

```kotlin
@Test
fun `full reindex keeps current active then switches after one fixed final range`() {
    coordinator.startFull(HARRIER_MODEL)
    assertThat(settings.current().modelName).isEqualTo(GRANITE_MODEL)
    completeInitialTargetAttempts()

    coordinator.advance()
    assertThat(activeCurrentJobs()).isEmpty()
    assertThat(settings.future()!!.cutoverAt).isNotNull()
    assertThat(latestFutureAttempts().map { it.pollRangeEnd }.distinct())
        .containsExactly(settings.future()!!.cutoverAt)

    completeFinalDeltaAndPruneAttempts()
    coordinator.advance()
    assertThat(settings.current().modelName).isEqualTo(HARRIER_MODEL)
}

@Test
fun `new source writes cannot extend the persisted final range`() {
    val cutover = startFinalRange()
    clock.advanceBy(Duration.ofDays(1))
    coordinator.advance()
    assertThat(latestFutureAttempts().map { it.pollRangeEnd }.distinct()).containsExactly(cutover)
}
```

- [ ] **Step 2: 실패 및 재시도 테스트 작성**

```kotlin
@Test
fun `failed final attempt resumes current scheduling and retry keeps its range`() {
    val failed = failOneFinalAttempt()
    coordinator.advance()
    assertThat(settings.future()!!.cutoverAt).isNull()
    assertThat(coordinator.currentSchedulingBlocked()).isFalse()

    coordinator.retry(failed.ccPairId)
    val retried = latestAttempt(failed.ccPairId, futureId)
    assertThat(retried.pollRangeStart).isEqualTo(failed.pollRangeStart)
    assertThat(retried.pollRangeEnd).isEqualTo(failed.pollRangeEnd)
}

@Test
fun `retry all queues only latest failed connectors`() {
    coordinator.retryAll()
    assertThat(queuedPairIds(futureId)).containsExactlyInAnyOrder(failedPair1, failedPair2)
    assertThat(queuedPairIds(futureId)).doesNotContain(successfulPair)
}

@Test
fun `cancel waits for the running writer before deleting the future index`() {
    coordinator.cancel()
    coordinator.advance()
    verify(indexer, never()).deleteIndex(futureIndex.name)

    cancelRunningFutureJobAtBatchBoundary()
    coordinator.advance()
    verify(indexer).deleteIndex(futureIndex.name)
    assertThat(settings.future()).isNull()
}

@Test
fun `full rebuild of the current model uses and reuses only the inactive slot`() {
    coordinator.startFull(GRANITE_MODEL)
    assertThat(settings.future()!!.indexName).isEqualTo("onyx-kotlin-chunks-granite-alt")
    cancelAndFinishCleanup()
    coordinator.startFull(GRANITE_MODEL)
    assertThat(settings.future()!!.indexName).isEqualTo("onyx-kotlin-chunks-granite-alt")
    assertThat(searchSettings.findAllByModelName(GRANITE_MODEL)).hasSizeLessThanOrEqualTo(2)
}
```

- [ ] **Step 3: 테스트 실패 확인**

Run: `cd _kotlin/backend && ./gradlew --no-daemon --max-workers=1 test --tests '*ReindexCoordinatorIntegrationTest'`

Expected: FAIL. 조정기와 작업 API가 아직 없다.

- [ ] **Step 4: 작업 시작과 진행 상태 구현**

```kotlin
enum class ReindexMode { FULL, SYNC }

data class ReindexProgressResponse(
    val mode: ReindexMode,
    val total: Int,
    val waiting: Int,
    val inProgress: Int,
    val completed: Int,
    val failed: Int,
)

data class ReindexErrorResponse(
    val ccPairId: Long,
    val name: String,
    val status: AttemptStatus,
    val errorMessage: String?,
)

@Service
class ReindexCoordinator(
    private val settings: IndexSettingsService,
    private val commands: IngestionCommandService,
    private val attempts: IngestionAttemptRepository,
    private val jobs: IngestionJobRepository,
    private val pairs: ConnectorCredentialPairRepository,
    private val indexer: OpenSearchIndexer,
    private val jdbc: JdbcTemplate,
) {
    fun startFull(modelName: String): Long
    fun startSync(modelName: String): Long
    fun cancel()
    fun retry(pairId: Long)
    fun retryAll()
    fun progress(): ReindexProgressResponse?
    fun errors(): List<ReindexErrorResponse>
    fun currentSchedulingBlocked(): Boolean
    fun advance()
}
```

`startFull`은 선택 모델의 현재 미사용 색인 슬롯을 초기화한다. 같은 이름의 `PAST` 설정 행이 있으면 재사용한다. 이때 해당 설정의 이전 시도, 작업, 체크포인트, 색인 문서 메타데이터를 지우고 물리 색인도 초기화한다. 모델별 설정 행과 물리 색인 이름은 두 개를 넘기지 않는다. 대상 커넥터별 첫 시도에 하나의 DB 시각을 `pollRangeEnd`로 저장한다.

`startSync`는 `compatiblePastSettingsId`가 가리키는 `PAST` 설정과 기존 체크포인트를 재사용하고 물리 색인과 대상별 상태를 초기화하지 않는다. 호환 과거 설정이 없으면 `BAD_REQUEST`를 반환한다. `ReindexMode`는 해당 설정의 최초 대상 시도 `fromBeginning` 값에서 계산하므로 별도 모드 컬럼을 추가하지 않는다.

- [ ] **Step 5: 고정 범위 전환 상태 기계 구현**

```kotlin
@Scheduled(fixedDelayString = "\${onyx.scheduler.poll-delay-ms:15000}")
fun advanceScheduled() {
    if (properties.worker.enabled) advance()
}
```

`advance()`는 다음 순서만 수행한다.

1. 취소 요청이 있으면 대상 작업 종료와 물리 색인 삭제를 처리한다.
2. 첫 대상 작업의 최신 상태가 모두 `SUCCESS`이면 현재 설정의 새 작업 예약을 차단한다.
3. 대기 중인 현재 작업을 `CANCELED`로 바꾸고 실행 중인 현재 작업이 없을 때까지 기다린다.
4. `SELECT CURRENT_TIMESTAMP`를 한 번 실행해 `cutover_at`에 저장한다.
5. 모든 대상 커넥터에 같은 `poll_range_end`를 가진 증분 시도를 만든다.
6. 증분 시도가 모두 성공하면 대상별 `prune_only=true` 시도를 만든다.
7. 정리 시도가 모두 성공하면 한 트랜잭션에서 기존 `PRESENT`의 `singleton_marker`를 비우고 `PAST`로 바꾼 뒤, `FUTURE`를 `PRESENT`로 바꾼다.
8. 커밋 후 새 `PRESENT`의 일시 중지되지 않은 커넥터에 첫 증분 작업을 예약한다. 이 작업은 `cutover_at`부터 기존 30분 중복 구간을 적용한다.

최종 증분 또는 정리 시도가 실패하거나 `COMPLETED_WITH_ERRORS`로 끝나면 `cutover_at`을 비우고 현재 작업 예약을 재개한다. 실패 시도의 고정 범위는 시도 행에 남긴다. 재시도 성공 후 새 DB 시각으로 2~8단계를 다시 실행한다.

- [ ] **Step 6: 취소와 재시도 API 구현**

```kotlin
@PostMapping("/search-settings/reindex/full")
fun full(@RequestBody request: ReindexRequest) = IdResponse(reindex.startFull(request.modelName))

@PostMapping("/search-settings/reindex/sync-and-switch")
fun sync(@RequestBody request: ReindexRequest) = IdResponse(reindex.startSync(request.modelName))

@PostMapping("/search-settings/cancel-new-embedding")
fun cancel() = reindex.cancel()

@PostMapping("/search-settings/reindex/{pairId}/retry")
fun retry(@PathVariable pairId: Long) = reindex.retry(pairId)

@PostMapping("/search-settings/reindex/retry-all")
fun retryAll() = reindex.retryAll()

@GetMapping("/search-settings/reindex-progress")
fun progress() = reindex.progress()

@GetMapping("/search-settings/reindex-errors")
fun errors() = reindex.errors()
```

`IngestionProcessor`는 각 배치 전후에 `FUTURE.cancel_requested_at`을 확인한다. 취소 조정기는 대기 작업을 취소하고 실행 작업과 만료되지 않은 임대가 사라진 뒤에만 물리 색인과 `FUTURE` 행을 삭제한다.

- [ ] **Step 7: 재색인 조정기 테스트 실행**

Run: `cd _kotlin/backend && ./gradlew --no-daemon --max-workers=1 test --tests '*ReindexCoordinatorIntegrationTest' --tests '*IndexSettingsIntegrationTest'`

Expected: PASS. 원본 생성 속도와 관계없이 모든 최종 시도는 저장된 같은 상한에서 끝난다.

- [ ] **Step 8: 커밋**

```bash
git add _kotlin/backend/src/main/kotlin/com/onyx/kotlin/indexing \
  _kotlin/backend/src/main/kotlin/com/onyx/kotlin/ingestion \
  _kotlin/backend/src/test/kotlin/com/onyx/kotlin/indexing
git commit -m "feat(kotlin): coordinate bounded embedding reindex"
```

### Task 6: 커넥터 수명 주기와 문서 집합 정합성 연결

**Files:**
- Create: `_kotlin/backend/src/main/kotlin/com/onyx/kotlin/documentset/DocumentSetIndexSyncService.kt`
- Modify: `_kotlin/backend/src/main/kotlin/com/onyx/kotlin/connector/ConnectorService.kt`
- Modify: `_kotlin/backend/src/main/kotlin/com/onyx/kotlin/documentset/DocumentSetSyncWorker.kt`
- Modify: `_kotlin/backend/src/main/kotlin/com/onyx/kotlin/indexing/ReindexCoordinator.kt`
- Modify: `_kotlin/backend/src/main/kotlin/com/onyx/kotlin/ingestion/IngestionCommandService.kt`
- Modify: `_kotlin/backend/src/test/kotlin/com/onyx/kotlin/ingestion/AdminDeletionIntegrationTest.kt`
- Modify: `_kotlin/backend/src/test/kotlin/com/onyx/kotlin/ingestion/IngestionProcessorIntegrationTest.kt`
- Modify: `_kotlin/backend/src/test/kotlin/com/onyx/kotlin/documentset/DocumentSetSyncOutboxIntegrationTest.kt`
- Modify: `_kotlin/backend/src/test/kotlin/com/onyx/kotlin/indexing/ReindexCoordinatorIntegrationTest.kt`

**Interfaces:**
- Consumes: 활성 `FUTURE` 설정과 대상별 수집 명령.
- Produces: `DocumentSetIndexSyncService.syncPair(pairId: Long, runtime: SearchRuntimeSettings, renew: () -> Boolean): Boolean`.

- [ ] **Step 1: 새 커넥터, 일시 중지, 삭제 테스트 작성**

```kotlin
@Test
fun `new pair during reindex joins current and future targets`() {
    val pairId = createPairWhileFutureExists()
    assertThat(activeJob(pairId, currentId)).isNotNull
    assertThat(activeJob(pairId, futureId)).isNotNull
}

@Test
fun `paused pair reindexes without becoming active`() {
    pause(pairId)
    coordinator.startFull(HARRIER_MODEL)
    processFuture(pairId)
    assertThat(pair(pairId).status).isEqualTo(PairStatus.PAUSED)
}

@Test
fun `deleting a pair removes it from every retained index`() {
    connectorService.deletePair(request)
    verify(indexer).deletePair(granitePrimary, pairId)
    verify(indexer).deletePair(harrierPrimary, pairId)
}
```

- [ ] **Step 2: 수명 주기 테스트 실패 확인**

Run: `cd _kotlin/backend && ./gradlew --no-daemon --max-workers=1 test --tests '*AdminDeletionIntegrationTest' --tests '*DocumentSetSyncOutboxIntegrationTest' --tests '*ReindexCoordinatorIntegrationTest'`

Expected: FAIL. 커넥터 생성과 삭제가 현재 고정 색인만 처리한다.

- [ ] **Step 3: 생성과 삭제를 모든 활성 설정에 연결**

새 커넥터 쌍은 `PRESENT`에 전체 수집을 만들고, `FUTURE`가 있으면 대상에도 `fromBeginning=true` 작업을 만든다. 대상 작업은 저장된 `PAUSED` 상태를 무시한다. 재색인을 시작할 때 일시 중지된 쌍은 현재 설정에 색인 문서가 한 개 이상 있을 때만 대상 목록에 넣는다. 삭제는 먼저 쌍을 `DELETING`으로 바꾸고 모든 설정의 물리 색인에서 `deletePair(target, pairId)`를 실행한 뒤 DB 행을 삭제한다.

- [ ] **Step 4: 문서 집합 동기화 로직 추출 및 대상 전환에 사용**

```kotlin
@Service
class DocumentSetIndexSyncService(
    private val documents: IndexedDocumentRepository,
    private val documentSets: DocumentSetRepository,
    private val indexer: OpenSearchIndexer,
) {
    fun syncPair(
        pairId: Long,
        runtime: SearchRuntimeSettings,
        renew: () -> Boolean = { true },
    ): Boolean
}
```

기존 `DocumentSetSyncWorker`는 현재 설정을 넘겨 이 서비스를 호출한다. 재색인 조정기는 최종 정리 성공 후 대상 설정에 같은 서비스를 호출한다. 모든 커넥터의 문서 집합 동기화가 성공한 경우에만 상태를 전환한다.

- [ ] **Step 5: 커넥터 수명 주기 통합 테스트 실행**

Run: `cd _kotlin/backend && ./gradlew --no-daemon --max-workers=1 test --tests '*AdminDeletionIntegrationTest' --tests '*IngestionProcessorIntegrationTest' --tests '*DocumentSetSyncOutboxIntegrationTest' --tests '*ReindexCoordinatorIntegrationTest'`

Expected: PASS. 새 쌍과 삭제 쌍이 전환 조건에 정확히 반영되고 일시 중지 상태가 유지된다.

- [ ] **Step 6: 커밋**

```bash
git add _kotlin/backend/src/main/kotlin/com/onyx/kotlin/connector/ConnectorService.kt \
  _kotlin/backend/src/main/kotlin/com/onyx/kotlin/documentset \
  _kotlin/backend/src/main/kotlin/com/onyx/kotlin/indexing/ReindexCoordinator.kt \
  _kotlin/backend/src/main/kotlin/com/onyx/kotlin/ingestion/IngestionCommandService.kt \
  _kotlin/backend/src/test/kotlin/com/onyx/kotlin
git commit -m "feat(kotlin): keep connectors consistent across index targets"
```

### Task 7: Kotlin 관리자 화면을 로컬 모델 작업 화면으로 교체

**Files:**
- Modify: `_kotlin/web/src/lib/admin-sidebar-utils.ts`
- Modify: `_kotlin/web/src/lib/swr-keys.ts`
- Modify: `_kotlin/web/src/lib/indexing/types.ts`
- Modify: `_kotlin/web/src/lib/indexing/svc.ts`
- Modify: `_kotlin/web/src/lib/indexing/hooks.ts`
- Modify: `_kotlin/web/src/views/admin/IndexSettingsPage/index.tsx`
- Modify: `_kotlin/web/src/views/admin/IndexSettingsPage/ReindexProgressBanner.tsx`
- Modify: `_kotlin/web/src/views/admin/IndexSettingsPage/ReindexErrorsModal.tsx`
- Delete: `_kotlin/web/src/views/admin/IndexSettingsPage/modals.tsx`
- Delete: `_kotlin/web/src/views/admin/IndexSettingsPage/shared.tsx`
- Delete: `_kotlin/web/src/lib/indexing/index.ts`
- Delete: `_kotlin/web/src/lib/indexing/utils.ts`
- Delete: `_kotlin/web/src/lib/indexing/modelSelection.test.ts`
- Modify: `_kotlin/web/src/i18n/messages/en.json`
- Modify: `_kotlin/web/src/i18n/messages/de.json`
- Modify: `_kotlin/web/src/i18n/messages/es.json`
- Modify: `_kotlin/web/src/i18n/messages/fr.json`
- Modify: `_kotlin/web/src/i18n/messages/ja.json`
- Modify: `_kotlin/web/src/i18n/messages/ko.json`
- Modify: `_kotlin/web/src/i18n/messages/pt.json`
- Modify: `_kotlin/web/src/i18n/messages/zh.json`
- Modify: `_kotlin/web/tests/e2e/admin/index-settings/IndexSettingsPage.ts`
- Modify: `_kotlin/web/tests/e2e/admin/index-settings/index_settings.spec.ts`

**Interfaces:**
- Consumes: 로컬 모델, 현재/준비 중 설정, 진행, 오류, 시작, 취소, 재시도 API.
- Produces: Granite/Harrier 모델 카드와 승인된 영어 작업 버튼.

- [ ] **Step 1: 서비스 함수와 화면 동작 테스트 작성**

```typescript
test("shows only local models and the approved actions", async ({ page }) => {
  await page.route("**/api/admin/embedding/models", (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify([
        {
          model_name: "ibm-granite/granite-embedding-311m-multilingual-r2",
          display_name: "Granite",
          dimension: 768,
          available: true,
          status: "READY",
          compatible_past_settings_id: null,
        },
        {
          model_name: "microsoft/harrier-oss-v1-0.6b",
          display_name: "Harrier",
          dimension: 1024,
          available: false,
          status: "UNAVAILABLE",
          compatible_past_settings_id: null,
        },
      ]),
    })
  );
  const indexSettings = new IndexSettingsPage(page);
  await indexSettings.goto();
  await expect(page.getByText("Granite")).toBeVisible();
  await expect(page.getByText("Harrier")).toBeVisible();
  await expect(page.getByRole("button", { name: "Full Reindex" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Sync & Switch" })).not.toBeVisible();
  await expect(page.getByRole("button", { name: /Harrier/ })).toBeDisabled();
});

test("retry all calls only the retry-all endpoint", async ({ page }) => {
  const request = page.waitForRequest(
    (value) =>
      value.url().endsWith("/api/search-settings/reindex/retry-all") &&
      value.method() === "POST"
  );
  await page.getByRole("button", { name: "Retry All" }).click();
  await request;
});
```

- [ ] **Step 2: Playwright 테스트 실패 확인**

Run: `cd _kotlin/web && bun run playwright tests/e2e/admin/index-settings/index_settings.spec.ts`

Expected: FAIL. 기존 공급자 탭과 전환 전략 UI가 표시된다.

- [ ] **Step 3: API 타입과 SWR 훅 축소**

```typescript
export interface LocalEmbeddingModel {
  model_name: string;
  display_name: string;
  dimension: number;
  available: boolean;
  status: string;
  compatible_past_settings_id: number | null;
}

export interface ReindexProgress {
  mode: "FULL" | "SYNC";
  total: number;
  waiting: number;
  in_progress: number;
  completed: number;
  failed: number;
}
```

공급자, 자격 증명, 사용자 지정 모델, 전환 전략 타입과 서비스 함수를 삭제한다. 새 서비스 함수는 `startFullReindex(modelName)`, `startSyncAndSwitch(modelName)`, `cancelReindex()`, `retryReindex(pairId)`, `retryAllReindex()`만 제공한다.

- [ ] **Step 4: 화면을 모델 카드와 작업 상태로 단순화**

`SettingsLayouts`, Opal `Card`, `Button`, `Text`를 사용한다. 현재 모델에는 `Current`를 표시한다. 선택한 모델의 `Full Reindex`는 항상 보이며 `variant="danger"`를 사용한다. 호환 가능한 과거 설정이 API 응답에 있을 때만 `Sync & Switch`를 표시한다. 준비되지 않은 Harrier 작업은 비활성화한다.

진행 배너는 모드에 따라 `Syncing…` 또는 `Reindexing…`을 표시하고 전체, 완료, 실행 중, 실패 수를 보여준다. 오류 모달은 행별 `Retry`, 상단 `Retry All`, `/admin/indexing/status` 링크를 제공한다.

- [ ] **Step 5: Kotlin 사이드바에서 색인 설정 활성화**

```typescript
const KOTLIN_ADMIN_ENABLED_ITEMS = new Set<AdminNavItemId>([
  "existingConnectors",
  "addConnector",
  "documentSets",
  "indexSettings",
]);
```

- [ ] **Step 6: 모든 언어 카탈로그의 키 모양 유지**

`admin.indexSettings`에 승인된 영어 버튼 키를 추가한다. 다른 언어 파일에도 같은 ICU 키를 넣어 키 일치 검사를 통과시킨다. 버튼 값은 요구대로 모든 언어에서 영어를 유지한다.

- [ ] **Step 7: 웹 검증 실행**

Run: `cd _kotlin/web && npm test -- --runInBand src/hooks/useSearchSettings.test.ts`

Run: `cd _kotlin/web && npm run types:check`

Run: `cd _kotlin/web && npm run lint`

Run: `cd _kotlin/web && bun run playwright tests/e2e/admin/index-settings/index_settings.spec.ts`

Expected: PASS. 공급자 관련 UI가 없고 승인된 작업만 표시된다.

- [ ] **Step 8: 커밋**

```bash
git add -A _kotlin/web/src/lib/indexing _kotlin/web/src/views/admin/IndexSettingsPage \
  _kotlin/web/src/lib/admin-sidebar-utils.ts _kotlin/web/src/lib/swr-keys.ts \
  _kotlin/web/src/i18n/messages _kotlin/web/tests/e2e/admin/index-settings
git commit -m "feat(kotlin): add local embedding reindex controls"
```

### Task 8: 실행 문서와 전체 회귀 검증

**Files:**
- Modify: `_kotlin/docker-compose.yaml`
- Modify: `_kotlin/README.md`
- Modify: `_kotlin/model-server/README.md`
- Modify: `_kotlin/model-server/MODELS.md`
- Modify: `docs/superpowers/specs/2026-09-17-kotlin-embedding-reindex-design.md` only if implementation names changed without changing the approved behavior.

**Interfaces:**
- Consumes: Task 1~7의 최종 환경변수, API, 버튼, 상태 이름.
- Produces: 운영자가 그대로 사용할 수 있는 실행 및 모델 파일 안내.

- [ ] **Step 1: 사용하지 않는 환경변수와 공급자 설명 제거**

`ONYX_EMBEDDING_MODEL_NAME`, `ONYX_EMBEDDING_DIMENSION`, `ONYX_EMBEDDING_MAX_CONTEXT_LENGTH`, `ONYX_EMBEDDING_NORMALIZE`, 모델 서버의 `EMBEDDING_MODEL_NAME`을 Compose와 문서에서 삭제한다. `OPENSEARCH_INDEX`, Granite 모델 경로, 선택적인 `HARRIER_MODEL_PATH`는 유지한다.

- [ ] **Step 2: DB 기반 실행 흐름 문서화**

문서에 다음 사실을 짧게 기록한다.

```text
Granite is the initial model stored in search_settings.
Granite and Harrier are the only supported embedding models.
Search and ingestion resolve the PRESENT setting from PostgreSQL.
Full Reindex rebuilds an inactive index. Sync & Switch reuses a compatible past index.
The final incremental range ends at one persisted database timestamp.
```

- [ ] **Step 3: 백엔드 전체 검사**

Run: `cd _kotlin/backend && ./gradlew --no-daemon --max-workers=1 check`

Expected: PASS.

- [ ] **Step 4: OpenSearch 통합 검사**

Run: `cd _kotlin/backend && ./gradlew --no-daemon --max-workers=1 opensearchIntegrationTest`

Expected: PASS. 두 대상 이름과 차원으로 색인 생성, 검색, 삭제가 동작한다.

- [ ] **Step 5: 모델 서버 전체 검사**

Run: `cd _kotlin/model-server && .venv/bin/python -m pytest -q`

Expected: PASS.

- [ ] **Step 6: 웹 전체 검사**

Run: `cd _kotlin/web && npm run test:ci`

Run: `cd _kotlin/web && npm run types:check && npm run lint && npm run format:check`

Expected: PASS.

- [ ] **Step 7: 제거 대상 잔존 여부 검사**

Run: `rg -n "openai_compatible|EmbeddingProvider|reindex_port_attempts|full_recollect_requested|ONYX_EMBEDDING_(MODEL_NAME|DIMENSION|MAX_CONTEXT_LENGTH|NORMALIZE)" _kotlin`

Expected: 검색 결과 없음. V17의 과거 마이그레이션 정의만 예외이며, 해당 파일은 수정하지 않는다.

- [ ] **Step 8: 최종 상태와 diff 확인**

Run: `git status --short && git diff --check && git log --oneline -8`

Expected: 의도하지 않은 파일과 공백 오류가 없고 Task별 커밋이 보인다.

- [ ] **Step 9: 커밋**

```bash
git add _kotlin/docker-compose.yaml _kotlin/README.md _kotlin/model-server/README.md _kotlin/model-server/MODELS.md
git commit -m "docs(kotlin): document database-backed embedding indexes"
```
