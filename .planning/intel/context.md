# 문서별 원문 근거

## Kotlin Backend FOSS 동작 호환 구현 계획
- source: _kotlin/docs/plans/2026-09-01-kotlin-backend-foss-parity.md

~~~~text
DATA_6urbns5v_START
**Goal:** 기존 Kotlin 관리 기능과 File·Jira·Confluence·GitHub 커넥터를 Python FOSS 동작 수준으로 복원하고 회귀 테스트를 구축한다.

**Architecture:** Python의 동기 generator를 Kotlin `Sequence<ConnectorBatch>`로 대응시킨다. 커넥터별 로더는 분리하지만 공통 retry framework는 만들지 않는다. 실제 PostgreSQL, MockWebServer, Docker Compose를 계층별로 사용한다.

**Tech Stack:** Kotlin 2.1.20, Java 21, Spring Boot 3.4.5, JUnit 5, Mockito, MockWebServer, Testcontainers PostgreSQL, Flyway, Apache Tika, PostgreSQL 15.2, OpenSearch 3.6.0

**Spec:** `docs/superpowers/specs/2026-09-01-kotlin-backend-foss-parity-design.md`

## Global Constraints

- Python FOSS 구현과 테스트를 동작 기준으로 사용한다.
- `ee/` 코드와 Enterprise fixture를 열람, 복사, 번역하지 않는다.
- File, Jira, Confluence, GitHub 외 커넥터를 추가하지 않는다.
- 사용자, 인증, multitenancy, external group sync를 추가하지 않는다.
- 모든 커넥터 호출은 동기식으로 유지한다. Coroutine `Flow`를 추가하지 않는다.
- DB 통합 테스트는 H2가 아닌 PostgreSQL 15.2에서 실행한다.
- 실제 Jira, Confluence, GitHub 계정은 필수 테스트에서 사용하지 않는다.
- Backend live 요청은 `http://localhost:3000`의 Web service를 통한다.
- 변경한 모든 Kotlin과 TypeScript 코드는 엄격한 type을 사용한다.
- 각 작업은 실패 테스트, 최소 구현, 통과 확인, 커밋 순서를 지킨다.

---

## Issues to Address

- 관리 API의 DB 제약, 삭제, pagination, 오류 계약 테스트가 없다.
- 원격 커넥터가 모든 문서를 `List`에 적재한다.
- Checkpoint가 커넥터 cursor가 아닌 마지막 성공 시각만 저장한다.
- 문서 단위 실패와 치명적 실패를 구분하지 않는다.
- 성공 한 번이 모든 과거 오류를 resolved 처리한다.
- 단일 실패가 즉시 repeated-error 상태를 만든다.
- Pruning과 문서별 OpenSearch 삭제가 없다.
- File 교체 시 `file_locations`와 `file_names`가 어긋날 수 있다.
- Jira, Confluence, GitHub의 검증, pagination, retry, permission 동작이 불완전하다.
- Permission sync API가 항상 `applicable=false`를 반환한다.
- 현재 테스트는 실제 PostgreSQL migration과 lock 동작을 검증하지 않는다.

## Important Notes

- 관련 Python 테스트는 현재 186개다. 각 시나리오를 포함, 제외, 대체 검증으로 분류한다.
- 제외는 승인된 범위 밖 기능에만 허용한다.
- Python의 repeated-error 기준은 refresh connector에서 연속 실패 5회다.
- Refresh frequency가 없는 connector에서는 실패 1회가 repeated-error 상태를 만든다.
- `ExternalAccess`는 user email set, external group ID set, public flag로 구성한다.
- Permission ACL은 저장하지만 사용자 기반 검색 필터는 구현하지 않는다.
- Retrieval 실패 문서 ID는 pruning 대상에서 제외한다.
- GitHub, Confluence, Jira retry 정책은 서로 다르다.
- 기존 `V1__connector_admin_and_ingestion.sql`은 수정하지 않는다. 새 migration만 추가한다.
- `.watchlist/WATCHLIST.md`는 local 후속 기록이며 구현 입력으로 사용하지 않는다.

## Implementation strategy

1. Python parity matrix와 PostgreSQL test harness를 먼저 만든다.
2. 관리 API의 현재 DB 동작을 통합 테스트로 고정하고 차이를 수정한다.
3. Batch, checkpoint, failure, ACL domain type과 migration을 추가한다.
4. 수집 processor를 batch 단위로 바꾸고 오류 복구와 pruning을 구현한다.
5. File, Jira, Confluence, GitHub 순서로 Python 시나리오를 port한다.
6. 각 커넥터의 permission retrieval을 구현한 뒤 permission worker를 연결한다.
7. 대표 File 수집을 전체 Docker stack에서 검증한다.

## Tests

- **Unit:** domain validation, cursor parsing, retry 계산, content 변환
- **External dependency unit:** MockWebServer 기반 Jira, Confluence, GitHub 계약
- **Integration:** Testcontainers PostgreSQL 기반 migration, CRUD, lock, 상태 전이
- **Playwright:** 새 UI 동작이 없으므로 추가하지 않는다.
- **Live stack:** Web → API → PostgreSQL → model server → OpenSearch File 수집 1건
- **Optional smoke:** 실제 SaaS credential이 있을 때만 커넥터 validation을 실행한다.

### Task 5: File 커넥터 동작

**Files:**
- Create: `backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/FileConnectorLoader.kt`
- Create: `backend/src/test/kotlin/com/onyx/foss/kotlin/ingestion/FileConnectorLoaderTest.kt`
- Modify: `backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/IngestionWorker.kt`
- Modify: `backend/src/main/kotlin/com/onyx/foss/kotlin/service/FileStorageService.kt`
- Modify: `backend/PYTHON_PARITY.md`

**Interfaces:**
- Consumes: `Sequence<ConnectorBatch>`
- Produces: Python-compatible File documents and metadata

- [ ] **Step 1: Port the five Python File scenarios as failing tests**

Create Kotlin tests corresponding to:

```text
test_single_text_file_with_metadata
test_two_text_files_with_zip_metadata
test_tabular_file_sets_file_id_on_document
test_non_tabular_file_leaves_file_id_none
test_mixed_batch_only_tabular_gets_file_id
```

Use test resources created inside `@TempDir`. Assert document IDs, titles, content, metadata, and file IDs.

- [ ] **Step 2: Run File tests and verify failures**

Expected: zip metadata and tabular file identity assertions fail.

- [ ] **Step 3: Move File loading into `FileConnectorLoader`**

Return one `ConnectorBatch` with `hasMore=false`.
Reuse Tika for supported non-tabular formats.
Use the stored asset ID as the stable source ID.
Remove the old `FileDocumentLoader` declaration from `IngestionWorker.kt`.

- [ ] **Step 4: Implement zip metadata association**

Read the existing upload contract fields `file_locations`, `file_names`, and `zip_metadata_file_id`.
Do not introduce another metadata format.

- [ ] **Step 5: Implement tabular file identity**

Set `metadata["file_id"]` only for tabular input.
Keep non-tabular metadata unchanged.

- [ ] **Step 6: Run File and management tests**

```bash
cd backend
JAVA_HOME="$HOME/.sdkman/candidates/java/21-zulu" ./gradlew test \
  --tests com.onyx.foss.kotlin.ingestion.FileConnectorLoaderTest \
  --tests com.onyx.foss.kotlin.api.AdminApiIntegrationTest
```

Expected: PASS.

- [ ] **Step 7: Mark File rows covered and commit**

```bash
git add _kotlin/backend/src _kotlin/backend/PYTHON_PARITY.md
git commit -m "feat: complete Kotlin file connector behavior"
```

---

### Task 6: Jira 커넥터 동작

**Files:**
- Create: `backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/JiraConnectorLoader.kt`
- Create: `backend/src/test/kotlin/com/onyx/foss/kotlin/ingestion/JiraConnectorLoaderTest.kt`
- Modify: `backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/RemoteConnectorLoaders.kt`
- Modify: `backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/RemoteJsonClient.kt`
- Modify: `backend/PYTHON_PARITY.md`

**Interfaces:**
- Consumes: `ConnectorBatch`, `ExternalAccess`, `RemoteJsonClient`
- Produces: `Sequence<ConnectorBatch>` with `JiraCheckpoint`

- [ ] **Step 1: Define and test the Jira checkpoint**

Use this shape:

```kotlin
data class JiraCheckpoint(
    val hasMore: Boolean = true,
    val allIssueIds: List<List<String>> = emptyList(),
    val idsDone: Boolean = false,
    val cursor: String? = null,
    val offset: Int? = null,
    val seenHierarchyNodeIds: Set<String> = emptySet(),
)
```

Test JSON round-trip and cursor resume.

- [ ] **Step 2: Port Jira validation and query tests**

Port the applicable scenarios from:

```text
test_jira_basic.py
test_jira_checkpointing.py
test_jira_error_handling.py
test_jira_bulk_fetch.py
test_jira_large_ticket_handling.py
test_jira_slim_retrieval.py
test_jira_permission_sync.py
```

Create explicit Kotlin test methods for project JQL, custom JQL, scoped token, skipped label, batch split, large issue, typed 401/403/404/429 errors, and permissions.

- [ ] **Step 3: Run Jira tests and verify failures**

Expected: cursor, validation, partial failure, permission, and typed error tests fail.

- [ ] **Step 4: Implement Jira page batches**

Support Jira Cloud cursor pagination and Server/Data Center offset pagination.
Yield one batch per API page.
Return the updated `JiraCheckpoint` with every batch.

- [ ] **Step 5: Implement Jira document conversion and failures**

Preserve summary, description, comments allowed by config, updated time, labels, links, and hierarchy IDs.
Convert one bad issue into `ConnectorFailure` without discarding the page's good issues.

- [ ] **Step 6: Implement Jira-specific validation and rate-limit behavior**

Map Python's typed validation cases to stable `IllegalArgumentException` messages.
Do not add generic retry behavior to `RemoteJsonClient`.

- [ ] **Step 7: Implement Jira permission retrieval**

Populate `ExternalAccess` from project and issue permissions.
Keep group IDs unprefixed for permission sync output and prefixed where the Python indexing path does so.

- [ ] **Step 8: Run all Jira tests**

```bash
cd backend
JAVA_HOME="$HOME/.sdkman/candidates/java/21-zulu" ./gradlew test \
  --tests com.onyx.foss.kotlin.ingestion.JiraConnectorLoaderTest
```

Expected: PASS.

- [ ] **Step 9: Mark Jira rows and commit**

```bash
git add _kotlin/backend/src _kotlin/backend/PYTHON_PARITY.md
git commit -m "feat: complete Kotlin Jira connector behavior"
```

---

### Task 7: Confluence 커넥터 동작

**Files:**
- Create: `backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/ConfluenceConnectorLoader.kt`
- Create: `backend/src/test/kotlin/com/onyx/foss/kotlin/ingestion/ConfluenceConnectorLoaderTest.kt`
- Modify: `backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/RemoteConnectorLoaders.kt`
- Modify: `backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/RemoteJsonClient.kt`
- Modify: `backend/PYTHON_PARITY.md`

**Interfaces:**
- Consumes: `ConnectorBatch`, `ExternalAccess`, Tika HTML parsing, `RemoteJsonClient`
- Produces: `Sequence<ConnectorBatch>` with `ConfluenceCheckpoint`

- [ ] **Step 1: Define and test the Confluence checkpoint**

```kotlin
data class ConfluenceCheckpoint(val hasMore: Boolean = true, val nextPageUrl: String? = null)
```

Test first page, resumed next link, and completion.

- [ ] **Step 2: Port basic, checkpoint, and HTML scenarios**

Port applicable tests from:

```text
test_confluence_basic.py
test_confluence_html_parser.py
test_confluence_checkpointing.py
test_extract_text.py
test_include_attachments_skip.py
test_slim_doc_image_skip.py
```

Assert page text, links, tables, date lozenges, image policy, CQL, and checkpoint progression.

- [ ] **Step 3: Port attachment and resolver scenarios**

Port applicable tests from:

```text
test_attachment_pagination_400.py
test_confluence_attachment_links.py
test_confluence_resolver.py
```

Assert partial attachment preservation, targeted refetch, platform-specific links, and `ConnectorFailure` IDs.

- [ ] **Step 4: Port pagination, retry, and permission scenarios**

Port applicable tests from:

```text
test_onyx_confluence.py
test_confcloud_77618_fallback.py
test_confluence_permissions_basic.py
test_confluence_user_email_overrides.py
test_rate_limit_handler.py
```

Exclude only `test_per_ancestor_shim_resolves_to_ee_implementation`.
Assert Cloud and Server limit reduction, 429 `Retry-After`, 500/504 fallback, space permission, page restriction, and cache isolation.

- [ ] **Step 5: Run Confluence tests and verify failures**

Expected: attachments, comments, HTML, retry, Server pagination, and permission tests fail.

- [ ] **Step 6: Implement page and attachment batches**

Yield page results incrementally.
Fetch comments and attachments only when enabled.
Use Tika's HTML parser and a focused SAX handler; do not add another HTML dependency.

- [ ] **Step 7: Implement Confluence-specific fallback rules**

Honor `Retry-After` on 429.
Reduce Cloud and Server page sizes exactly where Python does.
Implement the CONFCLOUD-77618 per-page permission fallback without EE code.

- [ ] **Step 8: Implement permission retrieval**

Combine space access, page restriction, ancestor restriction, and user email override results into `ExternalAccess`.
Use private empty access when Python cannot determine a document's permissions safely.

- [ ] **Step 9: Run all Confluence tests**

```bash
cd backend
JAVA_HOME="$HOME/.sdkman/candidates/java/21-zulu" ./gradlew test \
  --tests com.onyx.foss.kotlin.ingestion.ConfluenceConnectorLoaderTest
```

Expected: PASS.

- [ ] **Step 10: Mark Confluence rows and commit**

```bash
git add _kotlin/backend/src _kotlin/backend/PYTHON_PARITY.md
git commit -m "feat: complete Kotlin Confluence connector behavior"
```

---

### Task 8: GitHub 커넥터 동작

**Files:**
- Create: `backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/GithubConnectorLoader.kt`
- Create: `backend/src/test/kotlin/com/onyx/foss/kotlin/ingestion/GithubConnectorLoaderTest.kt`
- Modify: `backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/RemoteConnectorLoaders.kt`
- Modify: `backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/RemoteJsonClient.kt`
- Modify: `backend/PYTHON_PARITY.md`

**Interfaces:**
- Consumes: `ConnectorBatch`, `ExternalAccess`, `RemoteJsonClient`
- Produces: `Sequence<ConnectorBatch>` with staged `GithubCheckpoint`

- [ ] **Step 1: Define and test the GitHub checkpoint**

Model repository ID, stage, page cursor, branch, and `hasMore`.
Test JSON round-trip, cursor resume, cursor expiration, and branch change.

- [ ] **Step 2: Port checkpoint and validation tests**

Port all applicable scenarios from `test_github_checkpointing.py`.
Assert empty repo, PR-only, issue-only, cursor fallback, cursor completion, rate reset, and typed validation errors.

- [ ] **Step 3: Port file tests**

Port all applicable scenarios from `test_github_files.py`.
Assert extension and size filtering, binary failure, undecodable failure, truncated tree failure, branch URL, and stage progression.

- [ ] **Step 4: Port basic and slim permission tests**

Port `test_github_basic.py` and applicable `test_github_slim_connector.py` scenarios.
Assert PR filtering from issue results and permission-populated slim documents.

- [ ] **Step 5: Run GitHub tests and verify failures**

Expected: checkpoint stage, cursor, branch, error, and permission tests fail.

- [ ] **Step 6: Implement staged GitHub batches**

Process repositories one at a time through file, PR, and issue stages.
Save checkpoint state after each API page.
Do not cap files with the current arbitrary `take(500)` limit.

- [ ] **Step 7: Implement GitHub rate-limit behavior**

Read the API rate reset value after a rate-limit response.
Wait until reset using an injected sleeper in tests.
Do not apply this policy to Jira or Confluence.

- [ ] **Step 8: Implement repository permissions**

Populate public state, user emails, and team IDs in `ExternalAccess`.
Preserve permission data on PRs, issues, and files.

- [ ] **Step 9: Run all GitHub tests**

```bash
cd backend
JAVA_HOME="$HOME/.sdkman/candidates/java/21-zulu" ./gradlew test \
  --tests com.onyx.foss.kotlin.ingestion.GithubConnectorLoaderTest
```

Expected: PASS.

- [ ] **Step 10: Mark GitHub rows and commit**

```bash
git add _kotlin/backend/src _kotlin/backend/PYTHON_PARITY.md
git commit -m "feat: complete Kotlin GitHub connector behavior"
```

---
DATA_6urbns5v_END
~~~~

## Kotlin 검색 MCP 구현 계획
- source: _kotlin/docs/plans/2026-09-02-kotlin-search-mcp.md

~~~~text
DATA_5ppsybj3_START
**Goal:** 원격 MCP client가 `https://onyx-admin.com/mcp`에서 Document Set 합집합 필터를 지원하는 검색 도구를 사용하게 한다.

**Architecture:** Web은 공개 `/mcp` 요청을 내부 Kotlin backend `/mcp`로 전달한다. MCP transport는 `SearchService`를 직접 호출한다. `SearchService`는 query embedding, keyword/vector 후보 검색, reciprocal-rank 병합, reranking을 순서대로 수행한다.

**Tech Stack:** Kotlin 2.1.20, Java 21, Spring Boot 3.4.5, MCP Java SDK 2.0.1, Jackson 2, OpenSearch 3.6.0, JUnit 5, MockWebServer, Testcontainers

**Spec:** `docs/superpowers/specs/2026-09-02-kotlin-search-mcp-design.md`

## Global Constraints

- Spring Boot와 Spring AI는 이번 작업에서 변경하지 않는다.
- MCP client는 Web의 `https://onyx-admin.com/mcp`만 사용한다.
- Backend 주소와 port를 외부에 노출하지 않는다.
- 첫 버전은 인증과 ACL 필터를 구현하지 않는다.
- 여러 Document Set은 합집합으로 적용한다.
- 일반 검색은 기본 30개 후보만 rerank한다.
- Agentic retrieval 지침은 기본 1회, 최대 3회 검색을 권장한다.
- 새 production 동작은 실패 테스트를 먼저 작성한다.
- Enterprise 코드와 `_kotlinmania`는 참조하지 않는다.

---

## Issues to Address

- Kotlin backend에 query embedding 호출이 없다.
- OpenSearch index의 embedding은 vector search mapping이 아니다.
- Kotlin backend에 keyword 또는 vector retrieval이 없다.
- 여러 Document Set 이름을 검색 필터로 받을 수 없다.
- 검색과 reranking을 묶는 공통 service가 없다.
- MCP endpoint와 search tool이 없다.
- Web에 공개 `/mcp` 진입점이 없다.
- Agentic retrieval 비용을 제한하는 MCP 지침이 없다.

## Important Notes

- Python 검색은 Document Set 이름 목록을 OpenSearch `terms` query로 처리한다.
- `terms` query는 목록 중 하나와 일치하면 포함하므로 합집합이다.
- Kotlin의 `RERANKER_MAX_DOCUMENTS=100`은 안전 상한이다.
- 새 검색의 기본 rerank 후보 수는 30이다.
- OpenSearch의 기존 숫자 배열 embedding mapping은 `knn_vector`로 직접 변경할 수 없다.
- 구축 단계이므로 기존 embedding은 migration하지 않는다.
- 사용자가 정확한 Onyx index를 삭제하고 connector를 다시 실행한다.
- 애플리케이션은 index를 자동 삭제하지 않는다.
- MCP Java SDK core Servlet transport를 사용한다. Spring AI transport는 사용하지 않는다.
- 인증 전에는 `/mcp` 접근자가 전체 검색 대상 문서를 조회할 수 있다.
- Web proxy는 MCP headers, request body, response stream을 보존해야 한다.

## Implementation strategy

1. MCP SDK와 검색 설정을 추가한다.
2. `ModelServerClient`에 query embedding 경로를 추가한다.
3. 새 OpenSearch index를 768차원 `knn_vector` mapping으로 생성한다.
4. keyword와 vector 후보 검색에 같은 Document Set `terms` 필터를 적용한다.
5. `SearchService`에서 후보를 reciprocal rank로 병합하고 기존 reranker를 호출한다.
6. MCP `search` tool과 제한적인 agentic retrieval 지침을 등록한다.
7. Web `/mcp`를 내부 `/api/mcp` proxy로 연결한다.
8. source provenance와 운영 문서를 갱신한다.

## Tasks

### Task 1: 검색 설정과 query embedding

- [x] Query embedding payload를 검증하는 실패 테스트를 작성한다.
- [x] 실패 이유가 `text_type=query` 지원 부재인지 확인한다.
- [x] 검색 후보 50, rerank 후보 30, embedding dimension 768 설정을 추가한다.
- [x] `ModelServerClient`가 passage와 query embedding을 공통 호출로 처리하게 한다.
- [x] 관련 단위 테스트를 통과시킨다.

### Task 2: OpenSearch vector mapping과 reset 검사

- [x] 새 index의 `knn_vector` mapping을 검증하는 실패 통합 테스트를 작성한다.
- [x] 기존 동적 embedding mapping의 reset 안내 실패 테스트를 작성한다.
- [x] `index.knn`, dimension, Lucene HNSW cosine mapping을 추가한다.
- [x] 기존 incompatible embedding mapping을 감지하고 쓰기와 검색을 중단한다.
- [x] mapping과 reset 검사 통합 테스트를 통과시킨다.

### Task 3: Document Set 합집합 retrieval

- [x] keyword와 vector 검색의 Document Set 합집합 동작을 검증하는 실패 테스트를 작성한다.
- [x] 존재하지 않는 Document Set 이름의 실패 동작을 검증한다.
- [x] `DocumentSetRepository`에 이름 일괄 조회를 추가한다.
- [x] OpenSearch keyword와 vector 후보 검색을 추가한다.
- [x] 두 검색에 같은 `terms` 필터를 적용한다.
- [x] 단위 및 OpenSearch 통합 테스트를 통과시킨다.

### Task 4: 공통 SearchService

- [x] 후보 중복 제거와 reciprocal-rank 병합의 실패 테스트를 작성한다.
- [x] reranker 성공과 fallback limit의 실패 테스트를 작성한다.
- [x] 검색 input, candidate, result model을 추가한다.
- [x] query embedding, retrieval, fusion, reranking 순서를 구현한다.
- [x] 기본 30개 후보와 설정 상한을 적용한다.
- [x] SearchService 단위 테스트를 통과시킨다.

### Task 5: MCP server와 search tool

- [x] `tools/list`와 `tools/call`의 실패 통합 테스트를 작성한다.
- [x] 잘못된 query, limit, Document Set 입력의 tool error를 검증한다.
- [x] MCP Java SDK core Servlet transport를 `/mcp`에 등록한다.
- [x] `search` tool schema와 structured result를 등록한다.
- [x] 기본 1회와 최대 3회를 권장하는 server instructions를 추가한다.
- [x] MCP 통합 테스트를 통과시킨다.

### Task 6: Web proxy와 문서

- [x] Web `/mcp`의 request와 streaming response 보존 테스트를 작성한다.
- [x] Web `/mcp`를 기존 `/api/mcp` catch-all proxy로 연결한다.
- [x] 원격 URL과 무인증 보안 경계를 README에 기록한다.
- [x] 새 검색 코드의 FOSS 참고 경로를 `SOURCE_PROVENANCE.md`에 기록한다.
- [x] Web type check와 proxy 테스트를 통과시킨다.

### Task 7: 전체 검증

- [x] Backend 단위 테스트를 실행한다.
- [x] OpenSearch 통합 테스트를 실행한다.
- [x] 사용자의 index 삭제와 connector 재실행이 필요한 상태를 보고한다.
- [x] MCP 요청을 Web 경유로 live 검증한다.
- [x] 변경 파일에 대한 formatting과 정적 검사를 실행한다.
- [x] 실패와 경고가 없는지 확인한다.

## Tests

- **Unit:** query embedding payload, 입력 검증, reciprocal-rank fusion, reranker fallback
- **External dependency unit:** MockWebServer 기반 model-server와 OpenSearch request 계약
- **Integration:** OpenSearch `knn_vector` mapping, reset 검사, keyword/vector retrieval, Document Set 합집합
- **MCP integration:** tool discovery, tool call, structured result, tool execution error
- **Web:** `/mcp` header, body, stream proxy 보존
- **Live:** `https://onyx-admin.com/mcp`와 같은 Web 경로를 통한 MCP discovery와 search 호출
- **Playwright:** UI 변경이 없으므로 추가하지 않는다.
DATA_5ppsybj3_END
~~~~

## Kotlin 백엔드 Permission Sync / Document ACL 기능 제거 계획
- source: _kotlin/docs/plans/2026-09-04-remove-permission-sync-acl.md

~~~~text
DATA_lzwgg1rv_START
**Goal:** 미완성 상태로 과도한 외부 API 호출(DDOS 현상) 및 주기적 부하를 유발하는 Permission Sync 및 Document Access Control(ACL) 관련 백엔드 로직을 완전히 제거하고, 문서는 기본 공개(Public) 접근 모델로 단순화한다.

**Architecture:** 프론트엔드 UI(`web/`)는 상위 FOSS 코드베이스와 동일하게 유지하고, 커넥터 페어는 항상 공개(`access_type = "public"`)로 운영하여 탭 UI 자체를 렌더링하지 않는다. 컨트롤러 내의 권한 동기화 엔드포인트는 stub 없이 완전히 삭제한다. Jira·Confluence·GitHub 수집기에서 권한 스크래핑/동기화 로직을 완전히 걷어내고, 백그라운드 `PermissionSyncWorker` 및 관련 스케줄러를 삭제한다. DB 스키마는 V16 마이그레이션으로 잔존 테이블(`permission_sync_staging`, `permission_sync_attempts`)을 DROP하여 완전히 정리한다.

**Tech Stack:** Kotlin 2.3.20, Java 25, Spring Boot 4.0.7, PostgreSQL, Flyway, OpenSearch

---

## Global Constraints

- 프론트엔드(`_kotlin/web`)의 UI 컴포넌트는 상위 FOSS 원본과 일치하도록 유지하고 불필요한 UI 변경을 하지 않는다.
- Jira, Confluence, GitHub 수집기에서 권한 관련 추가 API 호출(space permissions, page restrictions, collaborator emails, project permission schemes)을 일체 수행하지 않는다.
- 모든 커넥터 페어의 `access_type`은 `"public"`으로 고정하며, 모든 문서는 FOSS 기본인 공개(`ExternalAccess(isPublic = true)`)로 인덱싱한다.
- 컨트롤러 내 권한 동기화 관련 엔드포인트 코드는 stub 없이 완전히 삭제한다.
- Flyway 마이그레이션은 기존 V1~V15의 체크섬을 건드리지 않고 `V16__drop_permission_sync.sql`로 테이블을 DROP한다.
- Watchlist `WL-20260831-001`을 정리하고 관련 문서(`README.md`)를 갱신한다.

---

## Issues to Address

- **외부 서비스 대상 과도한 API 호출 (DDOS 원인)**:
  - `JiraConnectorLoader`: 프로젝트별 permission scheme 조회(`/rest/api/.../permissionscheme`) 및 그룹 전개로 인한 다량의 API 호출.
  - `ConfluenceConnectorLoader`: space 권한 전수 조회(REST/JsonRpc) 및 모든 수집 페이지마다 per-page restriction 조회(`/rest/api/content/.../restriction/...`) 수행.
  - `GithubConnectorLoader`: `GithubStage.PERMISSIONS` 단계에서 collaborator 전원에 대해 개별 `/users/{login}` API를 호출하여 이메일을 수집하고 팀 권한을 조회하는 N+1 호출.
- **주기적 Permission Sync 워커 부하**:
  - `PermissionSyncScheduledWorker`가 5초마다 주기적으로 DB 락 및 claim을 걸며 외부 로더를 호출하고 OpenSearch `updateByQuery`를 실행함.
- **불필요한 DB 테이블, 엔티티, 컨트롤러 코드 잔존**:
  - `permission_sync_attempts`, `permission_sync_staging` 테이블 및 JPA Entity, Repository.
  - `AdminController`의 `permission-sync-attempts`, `external-group-sync-attempts` 엔드포인트.
- **미정리된 Watchlist 및 문서**:
  - `_kotlin/.watchlist/WATCHLIST.md`의 `WL-20260831-001` 항목 및 `_kotlin/README.md`의 "permission sync" 지원 문구.

---

## Important Notes

- 프론트엔드(`web/src/app/admin/connector/[ccPairId]/page.tsx`)는 `ccPair.access_type === "sync"`일 때만 권한 탭(`SyncAttemptsTabs`)을 렌더링하고 엔드포인트를 호출함.
- `access_type`이 `"public"`이면 일반 인덱싱 테이블(`IndexAttemptsTable`)만 렌더링되므로, `access_type`을 `"public"`으로 고정하면 컨트롤러 엔드포인트를 완전히 삭제해도 프론트엔드가 해당 API를 전혀 호출하지 않음.
- OpenSearch 청크의 `external_user_emails` 및 `external_user_group_ids` 필드는 기존 인덱스 매핑과의 호환성을 유지하기 위해 빈 배열(`[]`)로 저장.
- `OpenSearchIndexer.updateAccess` 메서드는 `PermissionSyncWorker` 전용이었으므로 완전히 삭제.

---

## Implementation strategy

1. **Connector Loaders 권한 스크래핑 로직 제거**:
   - `JiraConnectorLoader.kt`: `projectAccess`, `permissionscheme` 호출, `includePermissions` 관련 분기 제거.
   - `ConfluenceConnectorLoader.kt`: `allSpacePermissions`, `supportsRestSpacePermissions`, `resolveSlimPermission`, space/page restrictions 관련 분기 및 예외 클래스 제거.
   - `GithubConnectorLoader.kt`: `GithubStage.PERMISSIONS` 단계 및 collaborator/team email 조회 로직 제거.
2. **PermissionSyncWorker 및 스케줄러 삭제**:
   - `PermissionSyncWorker.kt` 파일 전체 삭제 (ScheduledWorker, Worker, ClaimService 포함).
3. **Admin Controller & Service 완전 정리**:
   - `AdminController.kt`: `permission-sync-attempts`, `external-group-sync-attempts` 엔드포인트 완전 삭제.
   - `IngestionQueryService.kt`: `permissionAttempts` 메서드 삭제 및 `PermissionSyncAttemptRepository` 의존성 제거.
   - `AdminService.kt`: `PermissionSyncAttemptRepository` 의존성 제거, ccPair의 `accessType`을 `"public"`으로 처리, `last_permission_sync_attempt_*` 필드 제거.
4. **Domain Entity, Repository, Flyway 마이그레이션 정리 (방안 B)**:
   - `V16__drop_permission_sync.sql` 작성 (`DROP TABLE IF EXISTS permission_sync_staging; DROP TABLE IF EXISTS permission_sync_attempts;`).
   - `Domain.kt` & `Repositories.kt`: `PermissionSyncAttemptEntity`, `PermissionSyncStageEntity` 및 관련 리포지토리 완전 삭제.
5. **OpenSearchIndexer 정리**:
   - `OpenSearchIndexer.kt`: `updateAccess` 메서드 완전 삭제.
6. **Watchlist 및 README 갱신**:
   - `_kotlin/.watchlist/WATCHLIST.md`: `WL-20260831-001` 항목 제거.
   - `_kotlin/README.md`: `permission sync` 문구 삭제/정리.
7. **테스트 코드 정리 및 빌드 검증**:
   - 권한 동기화 전용 테스트(`PermissionSyncIntegrationTest.kt`, `ConfluencePermissionSyncIntegrationTest.kt`) 삭제.
   - 기존 통합/단위 테스트(`MigrationSmokeTest.kt`, `AdminApiIntegrationTest.kt`, `AdminDeletionIntegrationTest.kt`, `GithubConnectorLoaderTest.kt`, `JiraConnectorLoaderTest.kt`, `OpenSearchIndexerTest.kt`, `OpenSearchIndexerIntegrationTest.kt`, `SchemaV2IntegrationTest.kt`, `SchemaV4IntegrationTest.kt`)에서 권한 관련 검증 정리 및 통과 확인.
   - 전체 오프라인 컴파일 및 테스트 통과 검증.

---

## Tasks

### Task 1: Watchlist 및 문서 갱신
- [ ] `_kotlin/.watchlist/WATCHLIST.md`에서 `WL-20260831-001` 항목 삭제.
- [ ] `_kotlin/README.md`에서 `permission sync` 지원 범위 문구 제거.

### Task 2: Connector Loader 권한 로직 제거 (DDOS 방지)
- [ ] `JiraConnectorLoader.kt`에서 `projectAccess` 및 `permissionscheme` 조회 로직 제거, 모든 문서 `ExternalAccess(isPublic = true)` 처리.
- [ ] `ConfluenceConnectorLoader.kt`에서 `supportsRestSpacePermissions`, `allSpacePermissions`, `resolveSlimPermission` 등 공간/페이지 권한 조회 로직 및 예외 제거.
- [ ] `GithubConnectorLoader.kt`에서 `GithubStage.PERMISSIONS` 및 collaborator 이메일 순회 조회 로직 제거.
- [ ] 관련 커넥터 단위 테스트(`GithubConnectorLoaderTest.kt`, `JiraConnectorLoaderTest.kt`) 수정.

### Task 3: 백그라운드 PermissionSyncWorker 삭제 및 Controller 엔드포인트 완전 제거
- [ ] `PermissionSyncWorker.kt` 삭제.
- [ ] `AdminController.kt`에서 `permission-sync-attempts` 및 `external-group-sync-attempts` 엔드포인트 완전 삭제.
- [ ] `AdminService.kt` 및 `IngestionQueryService.kt`에서 `PermissionSyncAttemptRepository` 의존성 및 응답 필드 제거.

### Task 4: DB 스키마 마이그레이션(V16) 및 도메인 엔티티 정리
- [ ] `V16__drop_permission_sync.sql` 작성 (`permission_sync_staging`, `permission_sync_attempts` DROP).
- [ ] `Domain.kt` 및 `Repositories.kt`에서 PermissionSync 관련 엔티티/리포지토리 완전 제거.
- [ ] `OpenSearchIndexer.kt`에서 `updateAccess` 완전 제거.

### Task 5: 테스트 코드 정리 및 전체 빌드 검증
- [ ] `PermissionSyncIntegrationTest.kt`, `ConfluencePermissionSyncIntegrationTest.kt` 삭제.
- [ ] `AdminApiIntegrationTest.kt`, `AdminDeletionIntegrationTest.kt`, `MigrationSmokeTest.kt`, `SchemaV2IntegrationTest.kt`, `SchemaV4IntegrationTest.kt`, `OpenSearchIndexerTest.kt`, `OpenSearchIndexerIntegrationTest.kt` 수정.
- [ ] 백엔드 컴파일 및 테스트 실행으로 정상 동작 확인.

---

## Tests

- **Unit / Loader Tests**: `GithubConnectorLoaderTest`, `JiraConnectorLoaderTest`에서 권한 스크래핑 없이 문서 수집 흐름이 정상 작동하는지 검증.
- **Integration Tests**: `MigrationSmokeTest` (V16 마이그레이션 정상 적용 및 테이블 정리 확인), `AdminApiIntegrationTest` (cc-pair 상세 조회 및 커넥터 삭제 시 오류 없이 동작 확인), `OpenSearchIndexerTest`.
- **E2E / Playwright**: `access_type = "public"` 조건에서 일반 인덱싱 테이블만 렌더링되며 프론트엔드가 삭제된 엔드포인트를 호출하지 않음을 확인.
DATA_lzwgg1rv_END
~~~~

## OpenSearch Communication Refactor: Spring AI & OpenSearch Java Client (Jackson 3 Native)
- source: _kotlin/docs/plans/2026-09-06-opensearch-spring-ai-refactor.md

~~~~text
DATA_ocrjlrh4_START
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
- Create `com.onyx.foss.kotlin.opensearch.OpenSearchVectorStoreProperties`:
  - Bind `@ConfigurationProperties("spring.ai.vectorstore.opensearch")`.
  - Fallback to `onyx.opensearch.*` if `spring.ai.vectorstore.opensearch.*` is unset.
  - Properties: `uris`, `indexName`, `username`, `password`, `ssl.verifyCerts`.
- Update `application.yml` with standard `spring.ai.vectorstore.opensearch.*` keys.

### Step 3: OpenSearch Client Factory with Jackson 3
- Create `com.onyx.foss.kotlin.opensearch.OpenSearchClientFactory`:
  - Configure `ApacheHttpClient5TransportBuilder` with `org.opensearch.client.json.jackson3.JacksonJsonpMapper(objectMapper)`, accepting `tools.jackson.databind.ObjectMapper`.
  - Set connect timeout (30s) and socket/response timeout (30s).
  - Configure SSL context: bypass certificate verification when `verifyCerts == false`.
  - Configure basic credentials provider if username/password present.
- Create `com.onyx.foss.kotlin.opensearch.OpenSearchConfiguration`:
  - Define beans: `OpenSearchVectorStoreProperties`, `OpenSearchClient` (injecting Spring's Jackson 3 `ObjectMapper`), and `VectorStore`.

### Step 4: DTO & Spring AI VectorStore
- Create `com.onyx.foss.kotlin.opensearch.OpenSearchChunkDocument`:
  - Pure Kotlin data class with `@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)`.
  - Helper functions: `toSpringAiDocument(id, score)` and `fromSpringAiDocument(doc)`.
- Create `com.onyx.foss.kotlin.opensearch.OnyxOpenSearchVectorStore`:
  - Implement `org.springframework.ai.vectorstore.VectorStore`.
  - Implement `similaritySearch` using `opensearch-java:3.10.0`'s `KnnQuery` builder (`field("embedding")`, `vector(floatVector)`, `k(count)`).
  - Translate document filters to OpenSearch query DSL.

### Step 5: Spring AI RAG Hybrid Search Fusion
- Create `com.onyx.foss.kotlin.service.HybridFusionJoiner`:
  - `ScoreNormalizationDocumentJoiner`: Implements `DocumentJoiner` for min-max score normalization.
  - `ReciprocalRankFusionDocumentJoiner`: Implements `DocumentJoiner` for RRF score fusion ($1 / (60 + \text{rank})$).
- Create `com.onyx.foss.kotlin.service.OpenSearchHybridDocumentRetriever`:
  - Implements `DocumentRetriever` combining keyword and vector queries via Spring AI Joiner.
- Update `SearchService.kt`:
  - Delegate hybrid fusion to `ReciprocalRankFusionDocumentJoiner`.

### Step 6: OpenSearchIndexer DSL Implementation
- Migrate `com.onyx.foss.kotlin.ingestion.OpenSearchIndexer`:
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
   - `./gradlew test --tests "com.onyx.foss.kotlin.ingestion.OpenSearchIndexerTest"` (14 MockWebServer tests verifying CUD, search, mapping, migration retries).
2. **Search Service Tests**:
   - `./gradlew test --tests "com.onyx.foss.kotlin.service.SearchServiceTest"` (Hybrid search and RRF fusion).
3. **MCP Tool Tests**:
   - `./gradlew test --tests "com.onyx.foss.kotlin.mcp.McpSearchToolTest"`.
4. **Integration Tests**:
   - `./gradlew opensearchIntegrationTest` (runs `OpenSearchIndexerIntegrationTest` against real OpenSearch container).
5. **Full Suite**:
   - `./gradlew test` (verifies entire backend test suite).
DATA_ocrjlrh4_END
~~~~

## Plan: Kotlin Backend Build Warnings Cleanup
- source: _kotlin/docs/plans/2026-09-07-kotlin-build-warnings-cleanup.md

~~~~text
DATA_k7rxc8go_START
# Plan: Kotlin Backend Build Warnings Cleanup

## Issues to Address
When building the Kotlin backend (`compileKotlin`, `compileTestKotlin`), 219 compiler and deprecation warnings are emitted:
1. **Jackson 3 API deprecations (212 warnings)**: `JsonNode.asText()` -> `asString()`, `asText(default)` -> `asString(default)`, and `isTextual` -> `isString` across 7 production files and 10 test files.
2. **Kotlin KT-73255 annotation target warning (2 warnings)**: `@JsonAlias` in `ApiModels.kt` on constructor value parameters without explicit target (`@param:JsonAlias`).
3. **Apache HttpClient 5 / OpenSearch Java 3.10.0 deprecations (2 warnings)**: `RequestConfig.Builder.setConnectTimeout` is deprecated in HttpClient 5 (should use `setConnectionConfigCallback`), and `ClientTlsStrategyBuilder.build()` is deprecated in favor of `buildAsync()`.
4. **Kotlin unnecessary non-null assertion (1 warning)**: Redundant `!!` on smart-casted variable `previousChunkId` in `SearchService.kt`.
5. **Netty test utility deprecation (2 warnings)**: `SelfSignedCertificate` in `OpenSearchIndexerTest.kt` for SSL tests.

## Important Notes
- OpenSearch Java Client 3.10.0 introduces `setConnectionConfigCallback` on `ApacheHttpClient5TransportBuilder`, allowing idiomatic connection-level timeout configuration without using deprecated `RequestConfig.setConnectTimeout`.
- Jackson 3 (`tools.jackson.core:jackson-databind:3.1.4`) renamed textual inspection and extraction methods from `asText()` to `asString()` and `isTextual` to `isString()`. The old methods delegate directly to the new ones and are marked `@Deprecated`.
- In Netty 4.2+, `SelfSignedCertificate` is deprecated as a production safety precaution; suppressing deprecation within the specific test function is standard practice for certificate mock testing.

## Implementation Strategy
1. **API Models (`ApiModels.kt`)**: Add `@param:` target to `@JsonAlias` annotations on data class constructor parameters.
2. **OpenSearch Client Factory (`OpenSearchClientFactory.kt`)**:
   - Use `builder.setConnectionConfigCallback` for connection timeouts.
   - Use `ClientTlsStrategyBuilder.buildAsync()` instead of `build()`.
3. **Search Service (`SearchService.kt`)**: Remove redundant `!!` on `previousChunkId`.
4. **Jackson 3 Method Migration (`asString`, `isString`)**:
   - Batch update production files (`ConfluenceConnectorLoader.kt`, `FileConnectorLoader.kt`, `GithubConnectorLoader.kt`, `JiraConnectorLoader.kt`, `OpenSearchIndexer.kt`, `AdminService.kt`, `FileStorageService.kt`).
   - Batch update test files (`AdminApiIntegrationTest.kt`, `ConnectorModelsTest.kt`, `DocumentSetSyncOutboxIntegrationTest.kt`, `JiraConnectorLoaderTest.kt`, `ModelServerClientTest.kt`, `OpenSearchIndexerIntegrationTest.kt`, `OpenSearchIndexerTest.kt`, `MinMaxNormalizationPipelineTest.kt`, `ZScoreNormalizationPipelineTest.kt`, `CredentialCipherTest.kt`).
5. **Test Utility Deprecation (`OpenSearchIndexerTest.kt`)**: Annotate `accepts self-signed OpenSearch certificate when verification is disabled` with `@Suppress("DEPRECATION")`.

## Tests
- Verify zero warnings with `./backend/gradlew -p backend compileKotlin compileTestKotlin --warning-mode all`.
- Run backend unit and mock tests: `./backend/gradlew -p backend test`.
DATA_k7rxc8go_END
~~~~

## Walkthrough: Kotlin Backend Build Warnings Cleanup
- source: _kotlin/docs/walkthroughs/2026-09-07-kotlin-build-warnings-cleanup.md

~~~~text
DATA_y6txn26i_START
# Walkthrough: Kotlin Backend Build Warnings Cleanup

## Summary
Resolved all 219 build and compiler warnings emitted during `./gradlew compileKotlin compileTestKotlin` on branch `fix/kotlin-build-warnings` (branched from `feat/opensearch-native-hybrid`). The build now compiles cleanly with zero warnings.

## Changes by Category

### 1. Jackson 3 API Migration (212 warnings resolved)
- Replaced deprecated `JsonNode.asText()` with `JsonNode.asString()` and `isTextual` with `isString`.
- Replaced method references `JsonNode::asText` with `JsonNode::asString`.
- **Main files**:
  - `ApiModels.kt`
  - `ConfluenceConnectorLoader.kt`
  - `FileConnectorLoader.kt`
  - `GithubConnectorLoader.kt`
  - `JiraConnectorLoader.kt`
  - `OpenSearchIndexer.kt`
  - `AdminService.kt`
  - `FileStorageService.kt`
- **Test files**:
  - `AdminApiIntegrationTest.kt`
  - `ConnectorModelsTest.kt`
  - `DocumentSetSyncOutboxIntegrationTest.kt`
  - `JiraConnectorLoaderTest.kt`
  - `ModelServerClientTest.kt`
  - `OpenSearchIndexerIntegrationTest.kt`
  - `OpenSearchIndexerTest.kt`
  - `MinMaxNormalizationPipelineTest.kt`
  - `ZScoreNormalizationPipelineTest.kt`
  - `CredentialCipherTest.kt`

### 2. Kotlin KT-73255 Annotation Target (2 warnings resolved)
- In `ApiModels.kt`, added explicit `@param:` target to `@JsonAlias` on primary constructor parameters (`@param:JsonAlias("credentialIds")` and `@param:JsonAlias("cc_pair_ids")`).

### 3. OpenSearch Java Client 3.10.0 & Apache HttpClient 5 (2 warnings resolved)
- In `OpenSearchClientFactory.kt`:
  - Replaced deprecated `RequestConfig.Builder.setConnectTimeout` with `builder.setConnectionConfigCallback` and `ConnectionConfig.custom().setConnectTimeout(...)`.
  - Replaced deprecated `ClientTlsStrategyBuilder.build()` with `buildAsync()`.

### 4. Smart Cast Unnecessary Non-Null Assertion (1 warning resolved)
- In `SearchService.kt`: Removed redundant `!!` assertion on `previousChunkId` after smart cast null-check.

### 5. Netty Self-Signed Certificate Test Utility (2 warnings resolved)
- In `OpenSearchIndexerTest.kt`: Removed deprecated top-level import and scoped `@Suppress("DEPRECATION")` to the specific SSL self-signed certificate test method with fully-qualified class reference.

## Verification Proof

### Clean Compilation (Zero Warnings)
```bash
./backend/gradlew -p backend clean compileKotlin compileTestKotlin --warning-mode all
```
Output:
```
> Task :clean
> Task :checkKotlinGradlePluginConfigurationErrors SKIPPED
> Task :processResources
> Task :compileKotlin
> Task :compileJava NO-SOURCE
> Task :classes
> Task :jar
> Task :compileTestKotlin

BUILD SUCCESSFUL in 12s
5 actionable tasks: 5 executed
```

### Test Suite Execution
```bash
./backend/gradlew -p backend test
```
Output:
```
BUILD SUCCESSFUL in 1m 17s
5 actionable tasks: 1 executed, 4 up-to-date
```
All unit and integration tests passed without regression.
DATA_y6txn26i_END
~~~~

## OpenSearch Native Hybrid Retrieval Implementation Plan
- source: _kotlin/docs/plans/2026-09-07-opensearch-native-hybrid-retrieval.md

~~~~text
DATA_ysrarp1e_START
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
DATA_ysrarp1e_END
~~~~

## Model-server porting spike
- source: _kotlin/docs/model-server-spike.md

~~~~text
DATA_iqycywhi_START
# Model-server porting spike

## Implemented outcome

The user selected Kotlin. The production PoC now runs the pinned Granite 311M
Multilingual R2 INT8 OpenVINO artifact through JNA bindings to the bundled
OpenVINO C API and the exact DJL Hugging Face tokenizer. Docker validation as
UID 10001 returned English and Korean 768-dimensional normalized embeddings;
the full Compose File ingestion path also completed and indexed two chunks.

GTE multilingual reranker-base and BGE reranker v2 m3 are downloaded, pinned
candidates. Reranking remains disabled until one candidate has a validated
OpenVINO or ONNX export and passes the score golden suite.

## Scope and source boundary

This note is based only on the local FOSS checkout at
`/home/wrallee/Workspace/onyx-foss`, at commit
`60b2c0c3616bba8bd56c1c8ce02d320c79b0b06f` (2026-08-29). It inspects
`backend/model_server` and its local call sites. `_kotlinmania` and external
repositories were not read or used.

This is a feasibility spike, not an implementation or a language decision.
The final model-server implementation remains a user choice after the
acceptance checks below: **Kotlin/JVM, Go, retain the Python implementation,
or use an external compatible inference service**. A failed Kotlin spike must
not silently select Python.

## Active FOSS model-server contract

`model_server.main` only mounts `management_endpoints.router` and
`encoders.router`. The files under `backend/model_server/legacy` are not
mounted; their reranker and intent routes are commented out. Cloud embedding
providers also bypass this server in the local caller.

| Method and path | Required behaviour | Consumer |
| --- | --- | --- |
| `POST /encoder/bi-encoder-embed` | Embed a non-empty batch with a local model; return `{"embeddings": [[float, ...], ...]}` in input order. | `EmbeddingModel` in `onyx/natural_language_processing/search_nlp_models.py` |
| `GET /api/health` | Return HTTP 200 with no required body. | Compose and platform health checks |
| `GET /api/gpu-status` | Return `{"gpu_available": boolean, "type": "CUDA" | "MAC_MPS" | "NONE"}`. | `onyx/utils/gpu_utils.py` |
| `GET /metrics` | Prometheus metrics endpoint installed by the FastAPI instrumentator. | Operations/monitoring |

The local caller builds the embedding URL as
`http://{MODEL_SERVER_HOST}:{MODEL_SERVER_PORT}/encoder/bi-encoder-embed`.
It forwards optional `X-Onyx-Tenant-ID` and `X-Onyx-Request-ID` headers. A new
service should accept and propagate those headers to logs/traces when present,
but no authentication or tenant authorization is required by this model
endpoint.

The request shape currently sent to a local model is:

```json
{
  "texts": ["..."],
  "model_name": "nomic-ai/nomic-embed-text-v1",
  "max_context_length": 512,
  "normalize_embeddings": true,
  "provider_type": null,
  "text_type": "query",
  "manual_query_prefix": "search_query: ",
  "manual_passage_prefix": "search_document: "
}
```

`deployment_name`, `api_key`, `api_url`, `api_version`, and
`reduced_dimension` are part of the shared request schema but are not used by
the active local model-server route. `provider_type` must be absent or `null`;
non-null provider requests are rejected because cloud providers are called
directly by the application instead.

Compatibility cases to preserve during a port:

- An absent/empty `texts` array returns HTTP 400 with `No texts to be embedded`.
- Empty strings or a missing local `model_name` fail the embedding request. The
  current FastAPI wrapper translates these failures to HTTP 500; a replacement
  should either preserve that behaviour for compatibility or deliberately
  change it together with the Kotlin client contract.
- The caller retries passage embedding network/JSON failures three times and
  rate-limit responses up to ten times. It treats HTTP 429 specially.
- The server retries `RuntimeError` from SentenceTransformers up to three times
  because concurrent encodes can fail with `Already borrowed`.

## Preprocessing and model coupling

The active encoder is smaller than the full historical model-server surface,
but it is not just a vector HTTP wrapper.

1. It caches one `SentenceTransformer` per `model_name`, loads it with
   `trust_remote_code=False`, and sets `model.max_seq_length` from every
   request. The default bundled model is `nomic-ai/nomic-embed-text-v1`.
2. It prepends `manual_query_prefix` only for `text_type=query` and
   `manual_passage_prefix` only for `text_type=passage`, before tokenization.
   The defaults are `search_query: ` and `search_document: `.
3. It calls `SentenceTransformer.encode(texts,
   normalize_embeddings=normalize_embeddings)`. Pooling, the tokenizer,
   special tokens, truncation and normalization are therefore coupled to the
   actual SentenceTransformers model artifact, not expressed in the HTTP API.
4. The application independently loads a Hugging Face tokenizer to split
   source content before requesting embeddings. Chunk-boundary equivalence
   therefore also depends on using the same tokenizer and the requested
   context length.
5. The local defaults are a 512-token context, 768 dimensions and normalized
   embeddings. `DOCUMENT_ENCODER_MODEL`, `DOC_EMBEDDING_DIM`,
   `NORMALIZE_EMBEDDINGS`, both prefixes, and batch size can be overridden by
   environment/configuration; the port must not hard-code only the defaults.

The source tree downloads the default model during the Python Docker build and
does not contain its resolved `modules.json`, tokenizer files, model weights,
or any ONNX export. Before choosing a JVM or Go runtime, inspect the **actual
approved model artifact** used in the target deployment and record:

- tokenizer files and tokenizer version;
- SentenceTransformers `modules.json`, pooling mode, normalization module, and
  model config;
- native weight format and whether a faithful ONNX export exists or can be
  created from the approved artifact;
- model and dependency licenses, hashes, and intended CPU/GPU execution mode.

Without that artifact audit, a source-only review cannot prove exact Kotlin or
Go numerical compatibility.

## Runtime and deployment behaviour to carry forward

- The entry point exits cleanly without importing ML libraries when
  `DISABLE_MODEL_SERVER` is enabled, which is how an external compatible
  endpoint is supported.
- Startup pre-warms the model's RoPE/cache, detects CUDA and Apple MPS, and
  caps Torch threads to the container cgroup CPU quota. A port needs an
  equivalent warm-up, accelerator capability response, and quota-aware worker
  sizing rather than using all host cores.
- The image preloads the default model into the Hugging Face cache. At runtime
  it merges that cache into a mounted cache volume. A replacement needs an
  explicit immutable model-cache/image strategy; it must not download a model
  on each application request.
- Custom CA roots are additive to public roots. Preserve that behaviour if a
  replacement downloads models or calls any HTTPS endpoint.
- The original deployment has separate inference and indexing model-server
  services to prevent ingestion from delaying inference. The planned
  Connector/Document Set system needs only the indexing path initially, but
  its service contract should remain deployable as a separate instance.

## Kotlin/JVM feasibility

Kotlin is feasible **if** the approved model can be represented by a supported
runtime (typically an ONNX graph) and its tokenizer/pooling pipeline can be
made equivalent. The HTTP surface can be implemented simply with Spring Boot
or Ktor, and a JVM ONNX Runtime binding can execute an approved exported graph.

The high-risk portion is model parity, not the web API:

- ONNX Runtime does not by itself reproduce SentenceTransformers module order,
  Hugging Face fast-tokenizer behaviour, prefixing, special tokens, pooling or
  normalization.
- The default source build uses PyTorch + Transformers +
  SentenceTransformers. It proves an ordinary SentenceTransformers load with
  remote code disabled, but does not prove an ONNX artifact is present or that
  its output includes the same pooling stage.
- A JVM tokenizer solution must be tested against the approved model's exact
  tokenizer files, including Korean/Unicode and truncation. Substituting a
  merely similar tokenizer is not acceptable for existing vectors.
- GPU support requires matching native runtime/provider packaging and device
  selection. Start with the target CPU profile unless the deployment requires
  GPU parity.

Recommended Kotlin spike deliverable: a small standalone service exposing only
the three required HTTP endpoints, loading one immutable approved model, with
model, tokenizer and pooling adapters kept explicit and covered by the golden
suite below. Do not integrate it into the main backend until it passes.

## Go feasibility

Go is also feasible **if** the same model is available in an executable native
format and an exact tokenizer/pooling implementation is selected. It has the
same HTTP-contract simplicity as Kotlin, but generally requires a native
inference runtime (for example an approved ONNX Runtime binding) and native
tokenizer integration rather than a pure-Go port of SentenceTransformers.

Its main risks are:

- CGO/native runtime, CPU instruction set and GPU-provider image compatibility;
- exact Hugging Face tokenizer parity and SentenceTransformers pooling outside
  the Python library;
- reproducible cross-platform builds and model-cache ownership;
- operational ownership of the native inference library upgrade cadence.

Recommended Go spike deliverable: the same minimal endpoint contract and the
same artifact manifest/golden suite as Kotlin. Do not treat lower HTTP-service
complexity as evidence of numerical compatibility.

## Concrete golden, benchmark, and acceptance design

Generate the baseline from the existing local Python model server with the
**same approved model artifact and environment values**. Store no proprietary
source documents or credentials in fixtures.

### Golden fixture

- Request cases: one item and the indexing batch size; English, Korean,
  mixed Unicode/emoji, punctuation, an input near the requested token limit,
  an input above it, and text that demonstrates both query and passage
  prefixes.
- Configuration cases: default 512 context/768 dimensions with normalization
  on; normalization off; a smaller supported context; custom query/passage
  prefixes; every approved local model configuration.
- Protocol/error cases: health, GPU status, no `texts`, `texts=[""]`, missing
  model name, and non-null provider type.
- Persist each request, response vector, vector length, finite-value check,
  L2 norm, baseline model artifact hash, config values, server image digest,
  and source commit. Never serialize API keys.

For each candidate server, require the same response cardinality/order and
dimension, finite values, prefix behaviour, and the following per-vector
checks against the Python baseline:

| Check | Initial pass criterion |
| --- | --- |
| Normalized-vector norm | `abs(norm - 1.0) <= 1e-5` |
| Cosine similarity | `>= 0.99999` for the same float model/artifact |
| Retrieval equivalence | identical top-10 on a fixed multilingual corpus; document any tie ordering |
| HTTP behaviour | required paths/statuses and JSON field names match |

If quantization, a changed exported graph, or a different accelerator makes
the cosine threshold unattainable, report the measured distribution and ask
the user to approve a new threshold before reindexing. Do not silently weaken
the criterion.

### Benchmark

Run each candidate and the Python baseline on the same locked image/model hash,
hardware, cgroup CPU quota, batch sizes, and warm-up sequence. Measure:

- cold-start time to health and time to first successful embedding;
- warm p50/p95 latency and throughput for query batch 1 and indexing batches;
- RSS, CPU, and GPU memory where applicable;
- 1, 2, and configured-concurrency request load, including failure/retry rate;
- image size and model-cache size.

Use at least one warm-up phase followed by three independent measured runs.
Report median and worst run, not only the best result. The proposed planning
gate is no more than 20% worse than the Python baseline for warm throughput and
memory; if that is not met, report the data for a user decision rather than
choosing a fallback automatically.

## Decision checkpoint

Stop after the artifact audit and Kotlin/Go proof(s) produce the golden and
benchmark report. Present the following to the user before changing Compose or
selecting a production model-server implementation:

| Option | Evidence to present |
| --- | --- |
| Kotlin/JVM | artifact adapter status, parity results, benchmark, native packaging/image details |
| Go | artifact adapter status, parity results, benchmark, native packaging/image details |
| Existing Python | baseline performance/image/operations data; only if the user explicitly selects it |
| External compatible service | accepted endpoint contract, trust/TLS/model-cache ownership, parity and latency data |

The user must explicitly choose one option. Until then, no Compose service is
added, replaced, or defaulted to Python.

## Licensing and provenance

The checked local checkout's root `LICENSE` is MIT (copyright
2023-present DanswerAI, Inc.), and the local `Dockerfile.model_server` labels
the model-server code/image as MIT-licensed. Preserve the root license,
copyright notice, source attribution and any retained file headers in every
copy or substantial portion.

That source-level finding does **not** clear the downloaded model weights,
tokenizer, JVM/Go libraries, or native runtimes. Before implementation, add a
machine-readable provenance/SBOM record with each artifact's exact source,
version/hash and license, and reject an artifact whose license is not approved.
The local configuration itself states that configured models must be MIT or
Apache licensed; verify that assertion from the acquired artifact metadata
instead of bypassing any licensing control.

## Local evidence

- `backend/model_server/encoders.py`: active encoder route, cache, prefixing,
  normalization, request validation, and concurrency retry.
- `backend/model_server/main.py` and `management_endpoints.py`: mounted routes,
  metrics, lifecycle, health, and GPU status.
- `backend/shared_configs/model_server_models.py` and `enums.py`: request and
  response contract.
- `backend/onyx/natural_language_processing/search_nlp_models.py`: caller URL,
  request serialization, headers, and retries.
- `backend/shared_configs/configs.py` and `backend/onyx/configs/model_configs.py`:
  default model, context, dimension, normalization, and prefixes.
- `backend/Dockerfile.model_server` and
  `deployment/docker_compose/docker-compose.yml`: Python dependencies, model
  preload/cache, two-service deployment, and health check.
- `backend/tests/unit/model_server/test_embedding.py` and
  `backend/tests/airgap/test_default_model_server_embeddings_are_finite.py`:
  existing basic concurrency and finite/dimension coverage.
DATA_iqycywhi_END
~~~~
