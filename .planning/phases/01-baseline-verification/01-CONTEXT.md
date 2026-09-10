# Phase 1: 현재 구현과 검증 범위 확인 - Context

**Gathered:** 2026-09-10
**Status:** Ready for planning

<domain>
## Phase Boundary

현재 `_kotlin` 구현과 테스트를 승인된 계약에 연결한다. 각 항목을 구현됨, 통과, 실패, 미실행, 환경 제약으로 구분한다. 확인된 차이는 후속 단계로 보낸다. 이 단계는 제품 코드를 수정하지 않는다.

</domain>

<decisions>
## Implementation Decisions

### 변경 대상

- **D-01:** 제품 변경은 `_kotlin/backend`에만 적용한다. 화면이나 Web proxy 계약이 필요할 때만 `_kotlin/web`을 수정한다.
- **D-02:** 루트 `backend`의 Python 구현은 기존 동작 참고용이다. Python 코드는 수정하지 않는다.
- **D-03:** 루트 `web`은 참고용이다. Frontend 변경이 필요하면 `_kotlin/web`만 수정한다.

### MCP 평가 항목 처리

- **D-04:** 동일 문서 chunk 독식은 `WL-20260910-001`로 보류한다. 이번 활성 수정 범위에 넣지 않는다.
- **D-05:** limit 증가에 따른 MCP 응답 토큰 과대는 `WL-20260910-002`로 보류한다. 이번 활성 수정 범위에 넣지 않는다.
- **D-06:** Confluence 문서의 갱신 시각과 `time_cutoff` 적용 실패를 확인한다. 조용한 필터 무시는 허용하지 않는다.
- **D-07:** GitHub PR review comment의 수집과 검색 누락을 확인한다. 코드와 diff 색인은 이번 요구사항에 포함하지 않는다.
- **D-08:** 인식하지 못한 `source_types`가 전체 필터를 해제하지 않게 한다. 실패 계약은 Document Set 오류와 일관되게 정한다.
- **D-09:** 중복 MCP 검색 도구와 Document Set 파라미터는 하나의 명확한 계약으로 정리한다. 호환성 처리는 기존 호출 근거로 결정한다.

### 검증 근거

- **D-10:** 정적 코드 근거와 현재 실행 결과를 분리한다. 테스트를 실행하지 못한 항목은 통과로 기록하지 않는다.
- **D-11:** Kotlin 단위 테스트와 MockWebServer 계약 테스트를 우선 사용한다. OpenSearch 동작은 기존 통합 테스트 경로로 검증한다.
- **D-12:** Python 테스트와 루트 Web 테스트는 Kotlin 동작 계약을 찾는 참고 자료다. Kotlin 변경의 통과 근거로 사용하지 않는다.

### 즉시 해결할 기존 문제

- **D-13:** `_kotlin/backend/Dockerfile`은 Gradle wrapper와 Java 25 build/runtime을 사용하게 한다. 루트 CI 파일은 수정하지 않는다.
- **D-14:** `FileStorageService`는 upload나 connector file update가 실패하면 해당 요청에서 새로 만든 파일을 남기지 않는다.

### the agent's Discretion

- 현재 상태 표의 세부 형식과 필요한 최소 테스트 묶음
- 기존 호출자를 확인한 뒤 유지할 MCP 도구 이름과 Document Set 파라미터 이름
- `_kotlin/web` 변경이 실제로 필요한지 여부

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### 프로젝트 계약

- `.planning/PROJECT.md` — 프로젝트 범위, 제외 항목, 기술 제약
- `.planning/REQUIREMENTS.md` — Phase 1 근거 기준과 Phase 4·5 검색·수집 계약
- `.planning/ROADMAP.md` — 단계 경계와 후속 단계 배치
- `_kotlin/docs/specs/2026-09-01-kotlin-backend-foss-parity-design.md` — Connector 동작 비교 기준
- `_kotlin/docs/specs/2026-09-02-kotlin-search-mcp-design.md` — MCP 검색과 반환 계약
- `_kotlin/docs/specs/2026-09-07-opensearch-native-hybrid-retrieval-design.md` — 필터, WRRF, chunk 처리 계약

### 사용자 제공 평가

- `/home/wooclee/.codex/attachments/c5ab4b93-03d3-4133-a6b0-700e1fc5415f/Onyx MCP는 쓸 만한가.pdf` — 실측 질의, 토큰 비용, 확인된 MCP 문제

### 코드베이스 지도

- `.planning/codebase/TESTING.md` — Kotlin 테스트 종류와 실행 경로
- `.planning/codebase/STRUCTURE.md` — `_kotlin`과 루트 구현의 분리 경계
- `.planning/codebase/CONCERNS.md` — 두 backend 계약 차이와 기존 검증 공백

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets

- `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/mcp/McpSearchTool.kt`: 검색 도구 정의, 필터 파싱, context 조회가 모여 있다.
- `_kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/mcp/McpSearchToolTest.kt`: 도구 schema, 별칭 파라미터, 잘못된 필터 입력의 기존 테스트가 있다.
- `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/GithubConnectorLoader.kt`: File, PR, issue 수집과 checkpoint 흐름이 있다.
- `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/OpenSearchIndexer.kt`: Document Set, source type, time 필터가 OpenSearch 요청으로 연결된다.

### Established Patterns

- Kotlin 서비스 경계는 JUnit과 Mockito로 확인한다.
- 원격 Connector 계약은 MockWebServer의 요청과 응답으로 확인한다.
- 검색 mapping과 native query는 실제 OpenSearch 통합 테스트로 확인한다.
- MCP는 `/mcp` endpoint 통합 테스트와 도구 단위 테스트를 분리한다.

### Integration Points

- MCP schema와 호출 처리: `McpSearchTool`, `McpConfiguration`
- 검색 필터 적용: `SearchService`, `OpenSearchIndexer`
- Confluence metadata 생성: `ConfluenceConnectorLoader`
- GitHub review 수집: `GithubConnectorLoader`
- Web 전달 계약이 필요할 때만 `_kotlin/web/src/app/api/[...path]/route.ts`

</code_context>

<specifics>
## Specific Ideas

- 평가 PDF의 재현 질의를 현재 Kotlin 구현에 적용한다.
- 활성 수정은 `time_cutoff`, GitHub review comment, 잘못된 source type, 중복 MCP 계약, Docker image 정합성, file rollback이다.
- 보류한 두 항목은 P0를 유지하지만 이번 계획에는 넣지 않는다.

</specifics>

<deferred>
## Deferred Ideas

- `WL-20260910-001`: 동일 문서 chunk의 검색 결과 독식 방지
- `WL-20260910-002`: limit 증가에 따른 MCP 응답 토큰 과대 방지

</deferred>

---

*Phase: 1-현재 구현과 검증 범위 확인*
*Context gathered: 2026-09-10*
