# Roadmap: Onyx Kotlin

## Overview

기존 `_kotlin` 구현을 인계하고, 승인된 계약과 현재 동작의 차이를 확인한다.
Phase 1에서 구현·테스트 근거를 대조한 뒤, 후속 단계에서 확인된 차이만 보완한다.
이미 충족한 요구사항은 검증 근거를 연결한다. 같은 기능을 다시 구현하지 않는다.

입력 범위는 `_kotlin/docs/`의 분류 문서 11개다. 요구사항 출처는 [REQUIREMENTS.md](REQUIREMENTS.md)에 있다.
과거 체크 표시와 실행 기록은 현재 통과 증거가 아니다. 모든 단계는 현재 검증 전이다.
`config.json`이 없어 `standard`와 `sequential` 기본 관례를 적용했다. 설정 파일은 생성하지 않았다.

## Phases

- [x] **Phase 1: 현재 구현과 검증 범위 확인** - 문서 계약을 실제 구현·테스트 근거와 연결한다. (completed 2026-09-10)
- [x] **Phase 2: 공개 문서 관리 흐름** - 기존 Web에서 관리 수명주기와 공개 접근 모델을 검증하고 차이를 보완한다. (completed 2026-09-10)
- [ ] **Phase 3: Kotlin embedding과 OpenSearch 적재** - 선택된 모델 서버와 색인 경로의 호환성·운영 기준을 확인한다.
- [ ] **Phase 4: 수집 완료와 안전한 복구** - 네 커넥터의 수집, checkpoint, 실패, pruning 차이를 보완한다.
- [ ] **Phase 5: Native hybrid 검색과 MCP** - Web 경유 검색과 MCP 결과 병합을 승인된 계약으로 검증한다.

## Phase Details

### Phase 1: 현재 구현과 검증 범위 확인

**Goal**: 개발자가 유지할 구현과 남은 동작 차이를 현재 근거로 구분할 수 있다.
**Depends on**: Nothing (first phase)
**Requirements**: BASE-01, BASE-02
**Success Criteria** (what must be TRUE):

1. 적용 가능한 FOSS 시나리오와 승인된 변경마다 구현 위치, 검증 위치, 현재 상태를 확인할 수 있다. 제외 항목에는 범위 근거가 있다. (BASE-01)
2. 문서의 과거 완료 기록과 현재 실행 결과를 별도로 볼 수 있다. 현재 검증은 통과·실패·미실행·환경 제약을 구분한다. (BASE-02)
3. 관리, 수집, 모델 서버, 검색의 기존 테스트를 재현할 명령과 누락 검증 목록이 있다. 후속 계획은 확인된 차이만 대상으로 삼는다. (BASE-01, BASE-02)

**Plans**: 2/2 plans executed

Plans:

- [x] 01-01-PLAN.md — 현재 Kotlin 구현·테스트 근거를 기록하고 확인된 차이를 후속 단계로 보낸다.
- [x] 01-02-PLAN.md — Phase 1 근거 경로·언어·상태를 교정하고 금지 조건을 자동 검증한다.

이 단계는 기존 구현과 검증 범위를 확인한다. 실패한 기능을 모두 고쳐야 다음 단계로 가는 구조가 아니다.
현재 코드 조사와 실행 검증은 후속 단계 작업이며, 이번 문서 작성에서는 수행하지 않았다.

### Phase 2: 공개 문서 관리 흐름

**Goal**: 운영자가 기존 Web에서 공개 문서를 관리하고 수집 상태를 확인할 수 있다.
**Depends on**: Phase 1
**Requirements**: ADMIN-01, ADMIN-02, ADMIN-03, ADMIN-04, PUBLIC-01, PUBLIC-02
**Success Criteria** (what must be TRUE):

1. Credential을 생성·수정·검증·삭제할 수 있다. 조회 결과는 masking하며 오류와 로그에 비밀값이 드러나지 않는다. (ADMIN-01)
2. Connector와 CC Pair의 연결·일시정지·실행·삭제 및 잘못된 연결 거부가 동작한다. 기존 화면에서 상태·색인 요약·attempt와 pagination을 확인한다. (ADMIN-02)
3. Document Set의 구성원을 수정·삭제하고 공개 상태를 확인할 수 있다. 파일 교체·제거 후 이름, 위치, metadata, connector 설정이 일치한다. (ADMIN-03, ADMIN-04)
4. 모든 CC Pair는 `public`이며 문서는 공개 상태와 빈 ACL 배열로 색인된다. 원격 로더는 권한 수집용 API를 호출하지 않는다. (PUBLIC-01)
5. 실제 PostgreSQL migration에서 V1~V15 체크섬을 보존하고 V16의 권한 테이블 제거를 확인한다. 기존 Web은 일반 색인 화면을 사용하며 제거된 API를 호출하지 않는다. (PUBLIC-02)

**Plans**: TBD

- [x] 02-01-PLAN.md

**UI hint**: yes

검증은 관리 API·삭제·migration 통합 테스트와 공개 상태의 기존 Web 흐름을 사용한다.
관련 구현이 이미 있으면 그대로 유지한다. UI 재설계는 포함하지 않는다.

### Phase 3: Kotlin embedding과 OpenSearch 적재

**Goal**: 개발자가 선택된 Kotlin 모델 서버의 embedding과 OpenSearch 적재 결과를 재현할 수 있다.
**Depends on**: Phase 2
**Requirements**: MODEL-01, MODEL-02, MODEL-03, MODEL-04, MODEL-05, INDEX-01, INDEX-02, INDEX-03, BUILD-01
**Success Criteria** (what must be TRUE):

1. 모델 서버가 embedding 입력 순서와 개수를 보존한다. 빈 입력·잘못된 provider의 오류, health·GPU status·metrics 계약을 확인할 수 있다. (MODEL-01)
2. 영어·한국어 및 query/passage 요청에서 승인된 tokenizer, prefix, context, normalization 설정을 확인한다. 고정 artifact·license·cache와 CPU quota 대응 근거가 있다. (MODEL-02, MODEL-03)
3. 같은 승인 artifact의 golden 비교와 동일 조건 benchmark를 재현할 수 있다. 원문 수치의 적용 조건, INT8 차이, 제안 기준 미달을 보고한다. (MODEL-04, MODEL-05)
4. Spring AI·Java Client·Jackson 3 경로에서 chunk 쓰기·조회·삭제·Document Set 갱신이 보존된다. 부적합 vector mapping은 명확히 실패하며 index를 자동 삭제하지 않는다. (INDEX-01, INDEX-02, INDEX-03)
5. clean compile에서 원문 대상 compiler/deprecation 경고가 없고 관련 테스트가 통과한다. 과거 경고 제거 기록을 현재 결과로 대체한다. (BUILD-01)

**Plans**: TBD

모델 golden·benchmark와 MockWebServer·실제 OpenSearch 검증을 사용한다.
benchmark는 원문처럼 warm-up 뒤 독립 측정 3회를 사용한다. 하드웨어와 cgroup 조건을 고정한다.
Kotlin 선택을 다시 열거나 다른 런타임으로 자동 교체하지 않는다.

### Phase 4: 수집 완료와 안전한 복구

**Goal**: 운영자가 File·Jira·Confluence·GitHub 문서를 수집하고 실패 뒤에도 안전하게 이어갈 수 있다.
**Depends on**: Phase 3
**Requirements**: INGEST-01, INGEST-02, INGEST-03, INGEST-04, CONNECTOR-01, CONNECTOR-02, CONNECTOR-03, CONNECTOR-04
**Success Criteria** (what must be TRUE):

1. File의 다중 파일·ZIP metadata·tabular 식별자를 원문 시나리오로 확인한다. Web 경유 대표 수집에서 최종 API 상태, DB row, OpenSearch chunk가 일치한다. (CONNECTOR-01, INGEST-04)
2. Jira의 project/JQL, Cloud cursor, Server/Data Center offset 및 재개 시나리오가 통과한다. 한 issue 실패가 같은 page의 성공 문서를 버리지 않는다. (CONNECTOR-02)
3. Confluence Cloud·Server의 page, 선택적 attachment/comment, HTML 변환과 checkpoint가 동작한다. 문서화된 pagination·rate-limit·부분 실패 검증이 통과한다. (CONNECTOR-03)
4. GitHub의 repository·branch·file·PR·issue 수집이 checkpoint에서 재개된다. cursor 만료, 파일 필터, 오류, rate reset을 원문 시나리오로 확인한다. (CONNECTOR-04)
5. 동시 worker가 같은 job을 중복 선점하지 않는다. batch별 checkpoint, 부분·치명 실패, 문서별 오류 복구, 반복 실패, 안전한 pruning을 실제 PostgreSQL 상태로 확인한다. (INGEST-01, INGEST-02, INGEST-03)

**Plans**: TBD

MockWebServer 계약 테스트, 실제 PostgreSQL 통합 테스트, 대표 Compose File 수집을 사용한다.
원문의 ACL·permission sync 시나리오는 승인된 제거 결정에 따라 제외한다.
원격 계정이 필요한 smoke test는 선택 사항이며 필수 계약 테스트를 대신하지 않는다.

### Phase 5: Native hybrid 검색과 MCP

**Goal**: MCP client가 Web `/mcp`에서 공개 문서를 검색하고 근거 chunk를 얻을 수 있다.
**Depends on**: Phase 4
**Requirements**: SEARCH-01, SEARCH-02, SEARCH-03, SEARCH-04, SEARCH-05, MCP-01, MCP-02, MCP-03, MCP-04, MCP-05
**Success Criteria** (what must be TRUE):

1. Web `/mcp`에서 도구를 발견·호출하고 동일한 text/structured 결과를 받는다. Header·body·stream을 보존하며 입력 오류와 protocol 오류를 구분한다. (SEARCH-01, MCP-01, MCP-02)
2. 여러 Document Set은 합집합으로 검색하고, 없는 이름은 오류로 반환한다. Document Set·source-type·time 필터가 각 검색 경로에 적용된다. (SEARCH-02)
3. `KEYWORD`는 embedding 없이 검색한다. `SEMANTIC`과 `HYBRID`는 한 번만 embed하고, `HYBRID`는 단일 OpenSearch 요청을 사용한다. Reranker 호출은 없다. (SEARCH-03)
4. `min_max`·`z_score`, 가중치, 후보 깊이와 반환 limit을 실제 OpenSearch에서 확인한다. Pipeline 실패는 hybrid를 실패시키며 keyword·semantic 검색은 독립적으로 동작한다. (SEARCH-04, SEARCH-05)
5. MCP WRRF의 기본 가중치·`k`와 연속 chunk 축약이 재현된다. 불완전 식별자·비인접 chunk는 독립적으로 남는다. 주변 문맥 참조와 반복 검색 지침도 계약에 맞는다. (MCP-03, MCP-04, MCP-05)

**Plans**: TBD
**UI hint**: yes

검증은 SearchService·MCP·request-shape 테스트, OpenSearch 3.6 통합 테스트, 기존 Web proxy 검증을 사용한다.
`get_document_context`는 원문의 주변 문맥 참조를 유지한다. 전체 도구 목록과 추가 schema는 입력만으로 확정하지 않는다.
`VectorStore`·`DocumentRetriever`는 유지하고, Kotlin BM25/vector 병합과 정규화는 복원하지 않는다.

## Progress

실행 순서: 1 → 2 → 3 → 4 → 5. 계획 개수는 각 단계 계획 시 확정한다.

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 1. 현재 구현과 검증 범위 확인 | 2/2 | Complete    | 2026-09-10 |
| 2. 공개 문서 관리 흐름 | 1/1 | Complete    | 2026-09-10 |
| 3. Kotlin embedding과 OpenSearch 적재 | 0/TBD | Not started | - |
| 4. 수집 완료와 안전한 복구 | 0/TBD | Not started | - |
| 5. Native hybrid 검색과 MCP | 0/TBD | Not started | - |

**Coverage**: v1 요구사항 35개를 각각 한 단계에 배정했다. 누락 0개, 중복 0개다.

---
*Last updated: 2026-09-09 after scoped document ingestion*
