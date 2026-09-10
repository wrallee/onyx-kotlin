---
phase: 01-baseline-verification
verified: 2026-09-10T07:38:29Z
status: passed
score: 8/8 must-haves verified
covered_files:
  - .planning/REQUIREMENTS.md
  - .planning/ROADMAP.md
  - .planning/phases/01-baseline-verification/01-01-PLAN.md
  - .planning/phases/01-baseline-verification/01-01-SUMMARY.md
  - .planning/phases/01-baseline-verification/01-02-PLAN.md
  - .planning/phases/01-baseline-verification/01-02-SUMMARY.md
  - .planning/phases/01-baseline-verification/01-BASELINE.md
  - .planning/phases/01-baseline-verification/01-CONTEXT.md
  - .planning/phases/01-baseline-verification/01-VALIDATION.md
  - .planning/phases/01-baseline-verification/01-evidence-prohibitions.test.cjs
  - .planning/phases/01-baseline-verification/fixtures/01-BASELINE-violation.md
  - _kotlin/backend/Dockerfile
  - _kotlin/backend/build.gradle.kts
  - _kotlin/backend/gradle/wrapper/gradle-wrapper.properties
  - _kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/ConfluenceConnectorLoader.kt
  - _kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/IngestionWorker.kt
  - _kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/OpenSearchIndexer.kt
  - _kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/security/CredentialCipher.kt
  - _kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/service/AdminService.kt
  - _kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/api/AdminApiIntegrationTest.kt
  - _kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/ingestion/ConfluenceConnectorLoaderTest.kt
  - _kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/ingestion/OpenSearchIndexerIntegrationTest.kt
  - _kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/ingestion/OpenSearchIndexerTest.kt
  - _kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/security/CredentialCipherTest.kt
  - _kotlin/model-server/Dockerfile
  - _kotlin/model-server/README.md
  - _kotlin/model-server/app/config.py
  - _kotlin/model-server/app/contracts.py
  - _kotlin/model-server/app/main.py
  - _kotlin/model-server/app/runtime.py
  - _kotlin/model-server/tests/test_api.py
covered_digest: "v1:sha256:e563185ac19e209d14dc91a7729653933130264b6cd35294b668c43e873950fa"
behavior_unverified: 0
overrides_applied: 0
prohibitions_verified: 2
re_verification:
  previous_status: gaps_found
  previous_score: 4/8
  gaps_closed:
    - "ADMIN-01이 존재하는 credential 구현과 테스트의 전체 경로를 인용한다."
    - "MODEL-01~05가 _kotlin/model-server를 Python 3.13 참고 구현으로 분류하고 Kotlin PASS 근거로 사용하지 않는다."
    - "D-06의 현재 Kotlin STATIC_MATCH/PASS와 식별되지 않은 원격 NOT_RUN 근거가 분리된다."
    - "두 prohibition이 독립적인 fail-first와 clean 통과 근거를 가진다."
  gaps_remaining: []
  regressions: []
deferred:
  - truth: "D-14 파일 rollback 제품 차이"
    addressed_in: "Phase 2"
    evidence: "Phase 2 관리 파일 수명주기 성공 기준"
  - truth: "D-13 Docker/Gradle/Java 정합성 제품 차이"
    addressed_in: "Phase 3"
    evidence: "Phase 3 Kotlin build와 image 검증 기준"
  - truth: "D-07 GitHub PR review comment 누락"
    addressed_in: "Phase 4"
    evidence: "Phase 4 GitHub PR 수집 기준"
  - truth: "D-06 Confluence와 검색 time cutoff 원격 재현"
    addressed_in: "Phase 4, Phase 5"
    evidence: "Phase 4 Confluence 수집과 Phase 5 검색 필터 기준"
  - truth: "D-08 알 수 없는 source_types 처리"
    addressed_in: "Phase 5"
    evidence: "Phase 5 source-type 필터 기준"
  - truth: "D-09 중복 MCP 검색 도구와 Document Set 별칭"
    addressed_in: "Phase 5"
    evidence: "Phase 5 Web /mcp 도구 계약"
---

# Phase 1: 현재 구현과 검증 범위 확인 검증 보고서

**Phase Goal:** 개발자가 유지할 구현과 남은 동작 차이를 현재 근거로 구분할 수 있다.
**Verified:** 2026-09-10T07:38:29Z
**Status:** passed
**Re-verification:** 예 — gap closure 뒤 재검증했습니다.

## 목표 달성 결과

이전 네 gap을 모두 닫았습니다. 원장은 현재 Kotlin delivery, Python 참고 구현, 원격 미실행 근거를 구분합니다. 후속 제품 차이는 계획된 Phase 2~5에 남아 있습니다.

### 관찰 가능한 진실

| # | 진실 | 상태 | 근거 |
|---|---|---|---|
| 1 | 승인 계약마다 구현 위치, 검증 위치, 현재 상태와 제외 근거를 확인할 수 있다. | ✓ VERIFIED | 35/35 고유 ID와 모든 필수 셀이 있습니다. Kotlin 경로는 실제 파일과 일치합니다. |
| 2 | 과거 기록과 현재 실행 결과가 분리되고 상태가 구분된다. | ✓ VERIFIED | 상태 규칙, 현재 Gradle 결과, history, NOT_RUN 항목이 분리됩니다. |
| 3 | 기존 테스트의 재현 명령과 누락 검증 목록이 있고 제품 차이는 후속 단계로 이동한다. | ✓ VERIFIED | 세 Gradle 명령과 계약별 gap/route가 유지됩니다. |
| 4 | Kotlin delivery evidence와 Python·root 참고 근거를 구분한다. | ✓ VERIFIED | MODEL-01~05는 Python 3.13 참고 구현과 정확한 경로, GAP/NOT_RUN 상태를 가집니다. Kotlin PASS로 표시하지 않습니다. |
| 5 | D-06~09, D-13, D-14에 현재 근거와 Phase 2~5 route가 있다. | ✓ VERIFIED | 여섯 route가 있습니다. D-06은 CURRENT_KOTLIN `STATIC_MATCH`/`PASS`와 REMOTE_EVALUATION `NOT_RUN`/`NOT_RUN`으로 분리됩니다. |
| 6 | D-04와 D-05는 Phase 1 활성 실행에서 제외된다. | ✓ VERIFIED | 두 watchlist 항목은 계속 `EXCLUDED`입니다. |
| 7 | 계약당 한 행이 coverage와 제외를 노출한다. | ✓ VERIFIED | 행 수뿐 아니라 경로, MODEL 상태, D-06 상태를 deterministic precheck가 검사합니다. |
| 8 | command, commit, result, duration, environment note가 현재 실행 근거를 식별한다. | ✓ VERIFIED | 기존 Gradle XML 합계와 Kotlin tree 동일성이 유지됩니다. |

**Score:** 8/8 truths verified (0 present, behavior-unverified)

### 이전 Gap 폐쇄

| Gap | 상태 | 현재 근거 |
|---|---|---|
| ADMIN credential 경로 | ✓ CLOSED | `AdminService.kt`, `CredentialCipher.kt`와 두 실제 테스트의 전체 경로 |
| MODEL 언어·상태 | ✓ CLOSED | 모든 MODEL 행이 Python 3.13 참고 전용이며 정확한 비-PASS 상태 사용 |
| D-06 근거 혼합 | ✓ CLOSED | CURRENT_KOTLIN과 REMOTE_EVALUATION 표 분리 |
| 두 prohibition 미검증 | ✓ CLOSED | named fail-first, clean Node test, GSD green disposition |

### 보류 항목

| 항목 | 배정 단계 | 상태 |
|---|---|---|
| D-14 파일 rollback | Phase 2 | 제품 gap 유지 |
| D-13 Docker build 정합성 | Phase 3 | 제품 gap 유지 |
| D-07 GitHub review comment | Phase 4 | 제품 gap 유지 |
| D-06 원격 재현 | Phase 4, Phase 5 | NOT_RUN 유지 |
| D-08 source type 오류 | Phase 5 | 제품 gap 유지 |
| D-09 MCP 별칭 중복 | Phase 5 | 제품 gap 유지 |

`WL-20260910-001`과 `WL-20260910-002`는 활성 실행에 들어가지 않았습니다.

### Advisory (New Scope, Unevidenced)

없음. 재검증 범위에서 새 blocker나 회귀를 찾지 못했습니다.

### 필수 산출물

| Artifact | 기대 결과 | 상태 | 세부 정보 |
|---|---|---|---|
| `01-BASELINE.md` | 교정된 경로·언어·상태와 D-06 분리 | ✓ VERIFIED | substantive, 35개 행, precheck 통과 |
| `01-01-PLAN.md` | canonical test-tier prohibition descriptor | ✓ VERIFIED | 두 descriptor가 resolved/test/node-test 형식 |
| `01-evidence-prohibitions.test.cjs` | 현재 PASS 의미와 여섯 route 강제 | ✓ VERIFIED | Node 내장 test 두 개, clean 통과, named RED 확인 |
| `fixtures/01-BASELINE-violation.md` | known-bad fail-first 입력 | ✓ VERIFIED | 혼합 PASS와 route 누락을 각각 재현 |

`verify.artifacts`는 Phase 01-02 산출물 4/4를 통과했습니다.

### 핵심 연결 검증

| From | To | Via | 상태 | 세부 정보 |
|---|---|---|---|---|
| `AdminService.kt`, `CredentialCipher.kt` | `01-BASELINE.md` | ADMIN-01 구현 근거 | ✓ WIRED | 원장이 전체 실제 경로를 인용하고 precheck가 파일 존재를 확인합니다. |
| `_kotlin/model-server/app/main.py` 등 | `01-BASELINE.md` | MODEL-01~05 Python 참고 근거 | ✓ WIRED | 각 MODEL 행이 필요한 Python 파일과 정확한 상태를 인용합니다. |
| `01-evidence-prohibitions.test.cjs` | `01-01-PLAN.md` | `check_target`, violation, clean fixture | ✓ WIRED | GSD prohibition-enforcement가 두 항목을 green/unflagged로 판정했습니다. |

자동 key-link 검사는 source 파일이 생성 문서를 역참조해야 한다고 판단해 0/3을 반환했습니다. 이 문서형 연결은 원장과 PLAN에서 정방향으로 확인했습니다.

### Data-Flow Trace (Level 4)

| Artifact | Data Variable | Source | Produces Real Data | 상태 |
|---|---|---|---|---|
| Phase 1 evidence artifacts | N/A | 저장소 파일과 deterministic check | N/A | 동적 렌더링 산출물이 아님 |

### 동작 확인

| 동작 | 명령 | 결과 | 상태 |
|---|---|---|---|
| 경로·상태·35 ID precheck | `01-02-PLAN.md` 첫 automated check | Node clean 포함 전체 종료 0 | ✓ PASS |
| violation fixture 전체 RED | `GSD_PROHIB_SUBJECT=... node --test ...` | 두 test 실패, exit 1 | ✓ PASS |
| 각 prohibition의 독립 RED | `node --test --test-name-pattern=...` | 각 named test 단독 exit 1 | ✓ PASS |
| clean control GREEN | `node --test ...01-evidence-prohibitions.test.cjs` | 2/2 pass | ✓ PASS |
| canonical disposition | `gsd-tools check prohibition-enforcement` | 두 항목 모두 green, unflagged, failFirst=true | ✓ PASS |

제품 Kotlin 전체 suite는 다시 실행하지 않았습니다. gap closure commit은 제품 tree를 바꾸지 않았습니다.

### Probe 실행

선언된 `probe-*.sh`가 없습니다. 별도 probe는 적용하지 않습니다.

### 요구사항 범위

| Requirement | Source Plan | 설명 | 상태 | 근거 |
|---|---|---|---|---|
| BASE-01 | `01-01-PLAN.md`, `01-02-PLAN.md` | 계약을 정확한 구현·검증 위치와 연결한다. | ✓ SATISFIED | precheck가 35 ID, 비어 있지 않은 셀, 실제 Kotlin 경로와 MODEL 경로·상태를 확인합니다. |
| BASE-02 | `01-01-PLAN.md`, `01-02-PLAN.md` | 과거·현재·미실행·환경 제약을 구분한다. | ✓ SATISFIED | D-06 분리와 두 fail-first prohibition check가 상태 혼합과 route 누락을 막습니다. |

Phase 1에 배정된 orphan requirement는 없습니다.

### Test Quality Audit

| Test File | Linked Req | Active | Skipped | Circular | Assertion Level | Verdict |
|---|---|---:|---:|---|---|---|
| `01-evidence-prohibitions.test.cjs` | BASE-01, BASE-02 | 2 | 0 | 아니요 | 값/계약 | PASS — clean 2/2와 각 named violation RED를 확인했습니다. |
| `01-02-PLAN.md` evidence precheck | BASE-01, BASE-02 | 예 | 0 | 아니요 | 값/구조/경로 | PASS — 정확한 ADMIN, MODEL, D-06 상태와 route를 검사합니다. |

**Disabled tests on requirements:** 0
**Circular patterns detected:** 0
**Insufficient assertions:** 0

### Anti-Patterns Found

없음. 수정 산출물에 미참조 `TBD`, `FIXME`, `XXX` marker가 없습니다.

### Prohibition 판정

| Prohibition | 판정 | 근거 |
|---|---|---|
| 과거·정적·미실행·환경 차단 근거를 현재 PASS로 표시하지 않는다. | ✓ VERIFIED | named violation RED, clean GREEN, GSD green/unflagged |
| 확인된 활성 gap과 후속 단계를 누락하지 않는다. | ✓ VERIFIED | named violation RED, clean GREEN, GSD green/unflagged |

### Decision Coverage

14/14 CONTEXT 결정을 shipped artifact에서 확인했습니다. 이 문자열 기반 gate는 비차단입니다.

### 사람 검증 필요

N/A — 문서·기반 단계이며 사용자 화면과 미검증 상태 전이가 없습니다.

### Gap 요약

없음. 이전 근거 정확성 gap 네 항목을 닫았습니다. 후속 제품 gap은 계획된 단계에 남아 있습니다.

---

_Verified: 2026-09-10T07:38:29Z_
_Verifier: the agent (gsd-verifier)_
