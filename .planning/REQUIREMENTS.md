# Requirements: Onyx Kotlin

**Defined:** 2026-09-09
**Core Value:** 기존 커넥터의 문서를 안전하게 수집하고, 공개 MCP 검색에서 정확한 근거 chunk를 제공한다.

## Derivation and Evidence

PRD와 원문 REQ ID는 없다. 아래 항목은 SPEC 계약과 승인된 DOC 변경에서 도출한 검증 대상이다.
체크 해제는 현재 검증 전이라는 뜻이다. 기존 기능을 모두 새로 구현한다는 뜻이 아니다.
문서의 과거 완료 표시는 현재 통과 근거로 사용하지 않았다. LOCKED ADR는 0개다.

ACL 제거·Spring AI 도입·native hybrid 변경은 [INGEST-CONFLICTS.md](INGEST-CONFLICTS.md)의 승인 우선순위를 따른다.
각 `[Sxx]`는 다음 원문을 가리킨다. 이 목록 밖에서 요구사항을 추가하지 않았다.

| Source | 유형 | 원문 |
|--------|------|------|
| S01 | SPEC | [FOSS 동작 호환 설계](../_kotlin/docs/specs/2026-09-01-kotlin-backend-foss-parity-design.md) |
| S02 | DOC | [FOSS 동작 호환 계획](../_kotlin/docs/plans/2026-09-01-kotlin-backend-foss-parity.md) |
| S03 | SPEC | [검색 MCP 설계](../_kotlin/docs/specs/2026-09-02-kotlin-search-mcp-design.md) |
| S04 | DOC | [검색 MCP 계획](../_kotlin/docs/plans/2026-09-02-kotlin-search-mcp.md) |
| S05 | DOC | [ACL·permission sync 제거](../_kotlin/docs/plans/2026-09-04-remove-permission-sync-acl.md) |
| S06 | DOC | [Spring AI·Java Client 전환](../_kotlin/docs/plans/2026-09-06-opensearch-spring-ai-refactor.md) |
| S07 | DOC | [빌드 경고 제거 계획](../_kotlin/docs/plans/2026-09-07-kotlin-build-warnings-cleanup.md) |
| S08 | DOC | [빌드 경고 제거 walkthrough](../_kotlin/docs/walkthroughs/2026-09-07-kotlin-build-warnings-cleanup.md) |
| S09 | SPEC | [Native hybrid 설계](../_kotlin/docs/specs/2026-09-07-opensearch-native-hybrid-retrieval-design.md) |
| S10 | DOC | [Native hybrid 계획](../_kotlin/docs/plans/2026-09-07-opensearch-native-hybrid-retrieval.md) |
| S11 | DOC | [Model-server spike](../_kotlin/docs/model-server-spike.md) |

## v1 Requirements

이번 v1은 기존 구현의 계약 확인과 차이 보완 범위다. 새 제품의 최초 구현 목록이 아니다.

### 현재 구현과 검증 근거

- [x] **BASE-01**: 개발자가 적용 가능한 FOSS 시나리오와 승인된 계약을 현재 구현·테스트 위치에 연결할 수 있다. 제외·대체 검증에는 원문 근거를 남긴다. [S01, S02, S05, S09]
- [x] **BASE-02**: 개발자가 문서상 완료와 현재 검증 상태를 구분할 수 있다. 재현 명령, 실행 결과, 미실행·환경 제약과 후속 검증 차이를 기록한다. [S01, S04, S06, S08, S10, S11]

### 관리 수명주기와 공개 문서

- [x] **ADMIN-01**: 운영자가 Credential을 생성·masking 조회·수정·연결 검사·삭제할 수 있다. 응답·로그·예외는 credential 값을 노출하지 않는다. [S01, S02]
- [x] **ADMIN-02**: 운영자가 Connector·CC Pair를 생성·수정·연결·일시정지·실행·삭제할 수 있다. 잘못된 연결 거부, metadata·상태·색인 요약·attempt·pagination은 기존 Web 계약을 따른다. [S01, S02, S05]
- [x] **ADMIN-03**: 운영자가 Document Set의 구성원을 관리하고 수정·삭제·공개 상태를 확인할 수 있다. 구성원 변경은 관련 색인 metadata와 일치한다. [S01, S02, S06]
- [x] **ADMIN-04**: 운영자가 파일을 upload·교체·제거할 수 있다. `file_locations`, `file_names`, metadata와 connector 설정이 함께 일치한다. [S01, S02]
- [x] **PUBLIC-01**: 모든 CC Pair가 `access_type="public"`이며 문서는 공개 상태로 색인된다. `external_user_emails`·`external_user_group_ids`는 빈 배열이고 권한 수집용 외부 API 호출은 없다. [S05]
- [x] **PUBLIC-02**: Permission sync worker·scheduler·API·관련 entity와 테이블이 제거된 상태를 검증한다. V1~V15 체크섬과 V16 제거 계약을 보존하며 기존 Web은 제거된 API를 호출하지 않는다. [S05]

### Kotlin model-server

- [ ] **MODEL-01**: `/encoder/bi-encoder-embed`가 입력 순서대로 embedding 배열을 반환한다. 빈 `texts`의 400, 잘못된 provider·빈 문자열·모델명 오류와 health·GPU status·metrics 계약을 보존한다. [S11]
- [ ] **MODEL-02**: 승인된 tokenizer·pooling으로 query/passage prefix, context 길이, 차원, normalization 설정을 처리한다. 기본 768차원·512 context만 고정하지 않으며 영어·한국어·Unicode·truncation을 검증한다. [S03, S11]
- [ ] **MODEL-03**: 선택된 Granite INT8 OpenVINO·JNA·DJL artifact의 source·version/hash·license를 추적할 수 있다. 고정 cache, warm-up, CPU quota, disable·별도 instance 운영과 기존 관측 header를 보존한다. [S11]
- [ ] **MODEL-04**: 같은 승인 artifact·설정의 Python baseline으로 cardinality·순서·차원·finite 값과 golden 결과를 비교한다. 정규화 norm 오차 `<=1e-5`, 동일 float artifact cosine `>=0.99999`, 고정 다국어 corpus top-10 기준의 적용 여부를 명시한다. [S11]
- [ ] **MODEL-05**: 같은 image/model hash·하드웨어·cgroup·batch·warm-up 조건에서 benchmark를 재현할 수 있다. 독립 3회 측정의 median·worst와 원문의 성능·메모리 제안 기준 대비 결과를 보고한다. [S11]

`MODEL-01`의 빈 문자열·모델명 오류는 기존 상태 코드를 유지하거나 client 계약과 함께 명시적으로 변경한다.
관측용 `X-Onyx-Tenant-ID` 수용은 tenant 권한이나 multitenancy 구현을 뜻하지 않는다.
`MODEL-04`의 동일 float 수치를 INT8에 무조건 적용하지 않는다. 수치 차이는 보고하고 새 임계값은 승인 없이 낮추지 않는다.
`MODEL-05`는 cold-start, 첫 embedding, warm p50/p95·처리량, RSS·CPU·해당 GPU 메모리, 동시성·실패율, image/cache 크기를 포함한다.
원문의 Python 대비 성능·메모리 악화 20% 이내는 제안된 planning gate다. 확정 SLA나 자동 런타임 교체 조건이 아니다.

### OpenSearch 적재와 빌드 호환

- [ ] **INDEX-01**: 새 embedding mapping은 설정 차원의 `knn_vector`, Lucene HNSW cosine과 `index.knn=true`를 사용한다. 부적합 기존 mapping에서는 쓰기·검색이 실패하며 index를 자동 삭제하지 않는다. [S03, S04]
- [ ] **INDEX-02**: Spring AI `VectorStore`, OpenSearch Java Client와 Jackson 3 경로로 chunk·metadata를 손실 없이 변환한다. 표준 연결 설정과 구형 설정 fallback, 인증·TLS 선택, 30초 연결/응답 timeout, 10분 migration timeout을 보존한다. [S06]
- [ ] **INDEX-03**: Chunk upsert·범위 조회·pair/document/stale chunk 삭제·Document Set 갱신이 기존 의미를 유지한다. Write-block 재시도와 `ping`·cluster health 동작을 검증한다. [S06]
- [ ] **BUILD-01**: Backend clean `compileKotlin`·`compileTestKotlin --warning-mode all`에서 대상 compiler/deprecation 경고가 없다. 관련 backend 테스트도 통과한다. [S07, S08]

OpenSearch 경로의 `WebClient` 제거를 다른 경로까지 확대하지 않는다.
Jackson 3 연결을 유지하며 Jackson 2 dependency를 다시 도입하지 않는다.
Spring AI의 Kotlin score joiner 요구는 native hybrid 계약으로 대체했다.

### 수집과 복구

- [ ] **INGEST-01**: Worker가 PostgreSQL lock으로 job을 중복 없이 선점하고 동기식 batch를 순차 처리한다. 문서·실패·다음 checkpoint·잔여 여부를 전달하며 checkpoint는 안전한 시점에만 전진한다. [S01, S02]
- [ ] **INGEST-02**: 문서 부분 실패와 치명적 실패를 구분하고 성공 문서의 이전 오류만 해결한다. 재개·embedding/index 오류와 반복 실패 상태는 적용 가능한 FOSS 규칙을 따른다. [S01, S02]
- [ ] **INGEST-03**: 전체 조회 완료 뒤 누락 문서를 pruning한다. 조회 실패 문서는 보존하고 OpenSearch 삭제 실패에도 DB·색인 상태가 안전하게 유지된다. [S01, S02]
- [ ] **INGEST-04**: Web을 통한 대표 Compose File 수집이 PostgreSQL·Kotlin model-server·OpenSearch를 거쳐 완료된다. 최종 API 상태, DB row, 색인 chunk를 함께 확인한다. [S01, S02, S11]
- [ ] **CONNECTOR-01**: File 수집이 원문의 단일·다중 파일, ZIP metadata, tabular/non-tabular·혼합 batch 시나리오를 충족한다. 안정적 source ID, 문서 제목·본문·metadata와 tabular 전용 `file_id`를 보존한다. [S01, S02]
- [ ] **CONNECTOR-02**: Jira의 project/JQL, Cloud cursor·Server/Data Center offset, checkpoint 재개와 issue 변환을 보존한다. 검증·typed error·부분 실패·rate limit은 Jira FOSS 계약을 따른다. [S01, S02, S05]
- [ ] **CONNECTOR-03**: Confluence Cloud·Server의 page·설정된 attachment/comment·HTML·링크·checkpoint를 보존한다. Pagination·부분 실패·`Retry-After`·fallback은 권한 조회를 제외한 FOSS 계약을 따른다. [S01, S02, S05]
- [ ] **CONNECTOR-04**: GitHub의 public/private repository·branch·file·PR·issue·cursor 재개를 보존한다. Cursor 만료·파일 필터·typed error·rate reset을 처리하며 권한 수집 stage는 없다. [S01, S02, S05]

반복 실패 기준의 문서 근거는 refresh connector의 연속 5회, refresh frequency가 없는 connector의 1회다.
원문의 당시 Python 테스트 186개는 현재 테스트 개수나 완료 목표로 사용하지 않는다.
권한 수집·permission sync 시나리오는 S05가 대체한다. 실제 SaaS 계정 없는 MockWebServer 계약 검증을 유지한다.

### Native 검색

- [ ] **SEARCH-01**: `SearchService`가 공백 아닌 query, 기본 limit 10과 `1..20` 범위를 검증한다. MCP transport와 별도로 직접 호출할 수 있다. [S03]
- [ ] **SEARCH-02**: Document Set 생략·빈 배열은 전체 검색, 여러 이름은 합집합이다. 없는 이름은 오류로 반환하며 document-set·source-type·time 필터를 검색 경로에 공통 적용한다. [S03, S09]
- [ ] **SEARCH-03**: `KEYWORD`는 embedding 없는 BM25, `SEMANTIC`은 embedding 1회와 k-NN이다. `HYBRID`는 embedding 1회와 단일 native `hybrid` 요청이며 reranker를 호출하지 않는다. [S09, S10]
- [ ] **SEARCH-04**: 두 normalization pipeline을 hybrid 준비 작업으로 등록하고 요청에서 명시적으로 선택한다. 기본 `min_max`와 선택적 `z_score`의 `arithmetic_mean`을 사용하며 준비 실패가 keyword·semantic을 막지 않는다. [S09, S10]
- [ ] **SEARCH-05**: `onyx.search.*`에서 후보 깊이 기본 200·범위 `1..10000`, normalization, 비음수 합계 1의 가중치를 검증한다. 기본 가중치는 `0.5/0.5`이며 후보 깊이와 `size=limit`을 구분하고 `ONYX_SEARCH_CANDIDATES`를 보존한다. [S09, S10]

`min_max`는 typed Java Client API를 사용한다. Java Client 3.10.0의 `z_score` 호출만 `OpenSearchClient.generic()`으로 한정한다.
검색의 Spring AI `VectorStore`·`DocumentRetriever` 경계를 유지하되 BM25/vector 정규화는 OpenSearch가 수행한다.

### MCP와 근거 chunk

- [ ] **MCP-01**: Client가 Web `/mcp`에서 stateless Streamable HTTP 도구를 발견·호출할 수 있다. 기존 proxy가 MCP header·body·stream을 보존하며 backend 주소 노출이나 REST 재호출 없이 `SearchService`에 연결한다. [S03, S04]
- [ ] **MCP-02**: 제목·본문 chunk·링크·점수를 같은 text content와 `structuredContent`로 반환한다. 결과는 limit을 지키며 tool 실행 오류·protocol 오류를 구분하고 비밀정보·내부 header·전체 backend URL을 숨긴다. [S03]
- [ ] **MCP-03**: 다중 순위 결과의 WRRF는 `sum(weight_i / (k + rank_i))`를 사용한다. 기본 list 가중치는 모두 `1.0`, `onyx.search.rrf-k` 기본값은 `50`이다. [S09]
- [ ] **MCP-04**: WRRF 뒤 같은 문서의 최대 연속 chunk 구간에서 최고 순위 하나만 남긴다. 비인접·불완전 식별자 결과는 독립 보존하고 본문을 합치지 않으며 주변 문맥은 `get_document_context` 참조를 따른다. [S09]
- [ ] **MCP-05**: 도구 설명은 기본 1회·최대 3회 검색 권고, 충분한 근거에서 중단, 최소 limit을 안내한다. WRRF는 같은 의도의 변형에 사용하고 독립 하위 질문은 무조건 합치지 않도록 안내한다. [S03, S09]

호출 횟수는 client 지침이다. 서버가 호출 상태를 저장하거나 강제하는 요구사항이 아니다.
S09의 추가 필터와 `get_document_context` 참조를 보존한다. 전체 MCP 도구 목록과 추가 schema는 입력에 없어 확정하지 않았다.
초기 문서의 단일 도구 한정은 후속 참조와 충돌하는 범위에서 대체했다.

## v2 Requirements

새로 승인된 v2 요구사항은 없다. 모델 후보·오래된 후속 제안을 자동으로 제품 약속에 추가하지 않는다.

## Out of Scope

| Feature | Reason |
|---------|--------|
| 인증·사용자 session·설정·SAML·LDAP·OIDC·SCIM | 원래 승인 범위에서 제외했다. |
| 문서 ACL 필터·permission sync·external group sync | 공개 접근 모델과 승인된 제거 변경을 따른다. |
| Enterprise code·fixture·multitenancy | FOSS 경계 밖이다. |
| 네 종류 외 connector | 기존 기능군의 호환성만 다룬다. |
| Reranker·서버 내부 LLM·query expansion·검색 session | Native 검색의 명시적 제외 항목이다. |
| Kotlin BM25/vector 병합·정규화·OpenSearch RRF·고정 query-role 가중치 | OpenSearch normalization과 MCP WRRF 경계를 유지한다. |
| 별도 REST 검색·전문 조회 `fetch`·UI 재설계 | 입력에서 승인하지 않았다. |
| 자동 index 삭제·embedding migration·런타임 재선정 | 명시적 reset과 문서상 Kotlin 선택을 유지한다. |
| 루트 Craft 기획·입력 밖 요구사항 | `_kotlin/docs/` 11개로 제한했다. |

## Traceability

| Requirement | Phase | Status |
|-------------|-------|--------|
| BASE-01 | Phase 1 | Complete |
| BASE-02 | Phase 1 | Complete |
| ADMIN-01 | Phase 2 | Complete |
| ADMIN-02 | Phase 2 | Complete |
| ADMIN-03 | Phase 2 | Complete |
| ADMIN-04 | Phase 2 | Complete |
| PUBLIC-01 | Phase 2 | Complete |
| PUBLIC-02 | Phase 2 | Complete |
| MODEL-01 | Phase 3 | Pending |
| MODEL-02 | Phase 3 | Pending |
| MODEL-03 | Phase 3 | Pending |
| MODEL-04 | Phase 3 | Pending |
| MODEL-05 | Phase 3 | Pending |
| INDEX-01 | Phase 3 | Pending |
| INDEX-02 | Phase 3 | Pending |
| INDEX-03 | Phase 3 | Pending |
| BUILD-01 | Phase 3 | Pending |
| INGEST-01 | Phase 4 | Pending |
| INGEST-02 | Phase 4 | Pending |
| INGEST-03 | Phase 4 | Pending |
| INGEST-04 | Phase 4 | Pending |
| CONNECTOR-01 | Phase 4 | Pending |
| CONNECTOR-02 | Phase 4 | Pending |
| CONNECTOR-03 | Phase 4 | Pending |
| CONNECTOR-04 | Phase 4 | Pending |
| SEARCH-01 | Phase 5 | Pending |
| SEARCH-02 | Phase 5 | Pending |
| SEARCH-03 | Phase 5 | Pending |
| SEARCH-04 | Phase 5 | Pending |
| SEARCH-05 | Phase 5 | Pending |
| MCP-01 | Phase 5 | Pending |
| MCP-02 | Phase 5 | Pending |
| MCP-03 | Phase 5 | Pending |
| MCP-04 | Phase 5 | Pending |
| MCP-05 | Phase 5 | Pending |

**Coverage:**

- v1 requirements: 35
- Mapped to phases: 35
- Unmapped: 0
- Duplicate phase assignments: 0

---
*Requirements defined: 2026-09-09*
*Last updated: 2026-09-09 after scoped document ingestion*
