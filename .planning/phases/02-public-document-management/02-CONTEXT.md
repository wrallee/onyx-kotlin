# Phase 2: 공개 문서 관리 흐름 - Context

**Gathered:** 2026-09-10
**Status:** Ready for planning

<domain>
## Phase Boundary

기존 관리·공개 문서 계약을 현재 Kotlin 테스트로 확인한다. 확인된 제품 차이인 D-14만 수정한다. 업로드 또는 connector file update 트랜잭션이 실패하면 그 요청에서 만든 물리 파일을 제거한다.

</domain>

<decisions>
## Implementation Decisions

### 변경 범위

- **D-01:** 제품 코드는 `_kotlin/backend`만 수정한다.
- **D-02:** 루트 Python backend와 루트 `web`은 참고용이며 수정하지 않는다.
- **D-03:** `_kotlin/web`은 현재 Phase 2 차이에 필요할 때만 수정한다. UI 재설계는 하지 않는다.

### 파일 롤백

- **D-14:** 요청 중 새로 만든 파일은 같은 DB 트랜잭션이 롤백되면 남지 않아야 한다.
- 파일 저장이 DB row 저장 전에 실패해도 부분 파일을 제거한다.
- 성공한 트랜잭션의 파일과 요청 전에 존재한 파일은 제거하지 않는다.
- ZIP entry와 metadata 파일도 일반 업로드와 같은 정리 계약을 사용한다.

### 기존 계약

- ADMIN-01~03과 PUBLIC-01~02는 현재 구현을 재사용한다. 새 차이가 확인되지 않으면 제품 코드를 바꾸지 않는다.
- Phase 2에는 새로운 API, 설정, storage abstraction을 추가하지 않는다.

### the agent's Discretion

- Spring transaction rollback callback의 최소 구현 위치
- 기존 integration test에 추가할 최소 rollback 시나리오

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

- `.planning/phases/01-baseline-verification/01-BASELINE.md` — Phase 2 current evidence and D-14 route
- `.planning/phases/01-baseline-verification/01-VERIFICATION.md` — verified Phase 1 result
- `.planning/REQUIREMENTS.md` — ADMIN-01~04 and PUBLIC-01~02 contracts
- `.planning/ROADMAP.md` — Phase 2 goal and success criteria
- `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/service/FileStorageService.kt` — physical file and DB asset writes
- `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/service/AdminService.kt` — connector update transaction flow
- `_kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/api/AdminApiIntegrationTest.kt` — current upload and ZIP integration coverage
- `.planning/codebase/CONCERNS.md` — D-14 source concern

</canonical_refs>

<code_context>
## Existing Code Insights

- `FileStorageService.upload` and `updateConnectorFiles` are transactional entry points.
- `store` and `storeZipEntry` write physical files before `FileAssetRepository.save` completes.
- One shared save boundary can register rollback cleanup for plain files, ZIP entries, and metadata.
- `AdminApiIntegrationTest` already uses the H2 application context and real file storage service.

</code_context>

<deferred>
## Deferred Ideas

- Same-document chunk dominance and large-limit token output remain in the P0 watchlist.
- Docker build consistency belongs to Phase 3.
- Connector and MCP behavior gaps belong to Phases 4 and 5.

</deferred>

---
*Phase: 2-공개 문서 관리 흐름*
*Context gathered: 2026-09-10*
