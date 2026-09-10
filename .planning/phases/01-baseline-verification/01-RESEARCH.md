# Phase 1: 현재 구현과 검증 범위 확인 - Research

**Researched:** 2026-09-10
**Domain:** Kotlin 구현·테스트 근거 감사
**Confidence:** HIGH

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

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

### Deferred Ideas (OUT OF SCOPE)

- `WL-20260910-001`: 동일 문서 chunk의 검색 결과 독식 방지
- `WL-20260910-002`: limit 증가에 따른 MCP 응답 토큰 과대 방지
</user_constraints>

## Summary

Phase 1은 제품 수정이 아니다. 한 개의 baseline 문서에서 35개 요구사항과 6개 활성 차이를 구현, 테스트, 현재 결과, 후속 단계에 연결해야 한다. `[VERIFIED: .planning/phases/01-baseline-verification/01-CONTEXT.md:7-9; .planning/ROADMAP.md:23-37]`

현재 Kotlin 코드는 Confluence 갱신 시각을 색인 필드와 검색 필터까지 전달한다. 관련 단위 테스트와 OpenSearch 통합 테스트도 현재 통과했다. 단, 평가 PDF의 실제 서버 증상과 Kotlin 배포를 연결한 재현은 없다. 따라서 이 항목은 해결 완료가 아니라 `정적 구현 확인 + 부분 통과 + live 미실행`으로 기록해야 한다. `[VERIFIED: _kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/ConfluenceConnectorLoader.kt:360-407; _kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/IngestionWorker.kt:334-352; _kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/OpenSearchIndexer.kt:202-230,379-415] [VERIFIED: local Gradle runs at 6b997ea573d5766aa044d11c706647597db32068, 2026-09-10]`

나머지 5개 항목은 현재 코드와 승인 계약 사이에 실제 차이가 있다. Phase 1은 이를 고치지 않고 Phase 2, 3, 4, 5로 보낸다. `[VERIFIED: .planning/phases/01-baseline-verification/01-CONTEXT.md:26-40; current implementation map below]`

**Primary recommendation:** 한 개의 `01-BASELINE.md`를 만든다. 각 행에 `contract`, `implementation`, `verification`, `current result`, `gap`, `follow-up phase`를 기록한다.

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|---|---|---|---|
| Baseline evidence ledger | Planning documentation | — | Phase 1 결과는 제품 코드가 아니라 현재 근거 문서다. `[VERIFIED: .planning/phases/01-baseline-verification/01-CONTEXT.md:7-9]` |
| Connector metadata and review collection | API / Backend | External GitHub·Confluence API | 두 loader가 `SourceDocument`를 생성한다. `[VERIFIED: _kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/ConfluenceConnectorLoader.kt:360-407; _kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/GithubConnectorLoader.kt:347-431,594-637]` |
| MCP schema and validation | API / Backend | OpenSearch | `McpSearchTool`이 입력을 변환하고 `SearchService`가 검색한다. `[VERIFIED: _kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/mcp/McpSearchTool.kt:19-59; _kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/service/SearchService.kt:19-65]` |
| File rollback | API / Backend | Filesystem and database | 서비스가 파일 쓰기와 asset 저장을 같은 요청에서 수행한다. `[VERIFIED: _kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/service/FileStorageService.kt:35-46,67-117,180-220]` |
| Container build consistency | Build / Delivery | JVM runtime | Dockerfile과 Gradle toolchain이 다른 JDK와 Gradle을 선택한다. `[VERIFIED: _kotlin/backend/Dockerfile:1-14; _kotlin/backend/build.gradle.kts:17-28; _kotlin/backend/gradle/wrapper/gradle-wrapper.properties:1-7]` |
| MCP Web pass-through | Frontend server proxy | API / Backend | `_kotlin/web`은 method, headers, body, stream을 일반 proxy로 전달한다. 현재 활성 차이는 backend 계약에 있다. `[VERIFIED: _kotlin/web/src/app/api/[...path]/route.ts:55-102]` |

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|---|---|---|
| BASE-01 | 개발자가 적용 가능한 FOSS 시나리오와 승인된 계약을 현재 구현·테스트 위치에 연결할 수 있다. 제외·대체 검증에는 원문 근거를 남긴다. | 35개 요구사항과 6개 활성 차이를 한 행 형식으로 기록한다. `[VERIFIED: .planning/REQUIREMENTS.md:33-36]` |
| BASE-02 | 개발자가 문서상 완료와 현재 검증 상태를 구분할 수 있다. 재현 명령, 실행 결과, 미실행·환경 제약과 후속 검증 차이를 기록한다. | 상태 어휘와 실행 근거를 분리한다. `[VERIFIED: .planning/REQUIREMENTS.md:33-36; .planning/ROADMAP.md:30-37]` |
</phase_requirements>

## Active Issue Baseline

상태는 `STATIC_MATCH`, `PASS`, `GAP`, `NOT_RUN`, `ENVIRONMENT_BLOCKED`만 사용한다. 한 항목에 여러 상태를 함께 쓸 수 있다.

| Active issue | Implementation evidence | Verification evidence | Current status | Route |
|---|---|---|---|---|
| Confluence `updatedAt` → `time_cutoff` | Page의 `version.when`을 필수로 읽고 `updatedAt`에 저장한다. Worker가 이를 `OpenSearchIndexer.upsert`로 넘긴다. 검색은 `doc_updated_at >= cutoff` range를 만든다. `[VERIFIED: _kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/ConfluenceConnectorLoader.kt:370-407; _kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/IngestionWorker.kt:334-352; _kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/OpenSearchIndexer.kt:202-230,398-415]` | Loader timestamp assertion, request-shape filter test, actual OpenSearch stored date test가 있다. `[VERIFIED: _kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/ingestion/ConfluenceConnectorLoaderTest.kt:72-97; _kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/ingestion/OpenSearchIndexerTest.kt:85-111; _kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/ingestion/OpenSearchIndexerIntegrationTest.kt:71-109]` | `STATIC_MATCH + PASS`; 평가 서버 재현과 동일 문서 end-to-end cutoff test는 `NOT_RUN`. `[VERIFIED: local Gradle runs, 2026-09-10]` | Phase 4에서 connector 전달을 확인하고 Phase 5에서 실제 cutoff 검색을 확인한다. |
| GitHub PR review comments | Stage 값은 `REPOSITORIES`, `PULL_REQUESTS`, `ISSUES`, `FILES`다. Collection 값은 `PULL_REQUEST`, `ISSUE`다. PR content는 `item.path("body").asString()`만 쓴다. `[VERIFIED: _kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/GithubConnectorLoader.kt:19-24,594-637,1044-1052]` | 기존 MockWebServer test는 PR detail body와 metadata를 검사한다. review-comment endpoint 기대값은 없다. `[VERIFIED: _kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/ingestion/GithubConnectorLoaderTest.kt:193-245]` | `GAP`; 55개 기존 loader test는 통과했지만 이 동작을 검사하지 않는다. `[VERIFIED: local Gradle test XML summary, 2026-09-10]` | Phase 4. 기존 PR 처리 경계와 MockWebServer test를 확장한다. 코드·diff는 제외한다. |
| Invalid `source_types` | `parseSourceTypes`는 문자열이 아니거나 `ConnectorSource.fromValue`가 실패한 값을 버린다. 지원 값은 `FILE("file"), JIRA("jira"), CONFLUENCE("confluence"), GITHUB("github")`다. `[VERIFIED: _kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/mcp/McpSearchTool.kt:81-85; _kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/domain/Domain.kt:24-36]` | 현재 test 이름과 기대값은 `search tool skips unknown source types instead of failing`이다. Document Set은 `Unknown document sets`로 실패한다. `[VERIFIED: _kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/mcp/McpSearchToolTest.kt:131-147; _kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/service/SearchService.kt:28-36]` | `GAP`; 기존 test 통과는 승인 계약과 반대인 과거 동작을 증명한다. | Phase 5. 전체 입력을 검증하고, 잘못된 값이 하나라도 있으면 downstream search 전에 tool error를 반환한다. |
| Duplicate MCP tool and parameter | 등록된 검색 도구 값은 `"search_indexed_documents"`, `"search"`다. schema에는 `"document_set_names"`, `"document_sets"`가 함께 있다. `[VERIFIED: _kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/mcp/McpSearchTool.kt:151-155,208-236; _kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/mcp/McpConfiguration.kt:40-67]` | endpoint test는 `"search", "search_indexed_documents", "weighted_reciprocal_rank_fusion", "get_document_context"` 네 값을 기대한다. `[VERIFIED: _kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/mcp/McpEndpointIntegrationTest.kt:41-73]` | `GAP`; 현재 4개 endpoint test는 기존 중복 계약으로 통과한다. `[VERIFIED: local Gradle run, 2026-09-10]` | Phase 5. `search_indexed_documents`와 `document_set_names`를 유지한다. tool 입력의 legacy alias만 제거한다. Header/query fallback `document_sets`는 transport 기본값이라 별도 계약으로 유지한다. |
| Docker wrapper and Java 25 | 현재 Dockerfile은 `FROM gradle:8.14.3-jdk21`, `RUN gradle --no-daemon bootJar`, `FROM eclipse-temurin:21-jre`다. Gradle 설정은 `JavaLanguageVersion.of(25)`와 `JvmTarget.JVM_25`다. wrapper URL은 `gradle-9.5.1-bin.zip`이다. `[VERIFIED: _kotlin/backend/Dockerfile:1-14; _kotlin/backend/build.gradle.kts:17-28; _kotlin/backend/gradle/wrapper/gradle-wrapper.properties:1-7]` | CI는 `java-version: '25'`, `./gradlew test --no-daemon`, `./gradlew opensearchIntegrationTest --no-daemon`을 쓴다. Image build/start test는 없다. `[VERIFIED: .github/workflows/custom-kotlin-backend-checks.yml:38-49; .planning/codebase/CONCERNS.md:10-29,129-136]` | `GAP`; container build/start는 `NOT_RUN`. | Phase 3. Dockerfile만 wrapper와 Java 25 build/runtime으로 맞춘다. 루트 CI는 건드리지 않는다. |
| FileStorage failure rollback | `store`는 파일을 먼저 복사한 뒤 asset을 저장한다. `storeZipEntry`는 현재 entry copy 실패만 삭제한다. `upload`와 `updateConnectorFiles` 전체 실패 시 이미 만든 파일의 batch cleanup은 없다. `[VERIFIED: _kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/service/FileStorageService.kt:35-46,67-117,141-220]` | 기존 tests는 성공, ZIP MIME, 크기 거부를 검사한다. 실패 후 storage directory와 asset row가 모두 비었는지는 검사하지 않는다. `[VERIFIED: _kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/api/AdminApiIntegrationTest.kt:530-634]` | `GAP`; rollback 재현 test는 없다. | Phase 2. 파일 생성 공통 경계에서 rollback cleanup을 등록하고 upload·update failure를 한 집중 test 묶음으로 확인한다. |

## Current Verification Evidence

모든 명령은 commit `6b997ea573d5766aa044d11c706647597db32068`에서 `_kotlin/backend`를 작업 디렉터리로 실행했다. `[VERIFIED: git rev-parse and local command output, 2026-09-10]`

| Command | Result | Meaning |
|---|---|---|
| `./gradlew test --no-daemon --tests 'com.onyx.foss.kotlin.ingestion.ConfluenceConnectorLoaderTest' --tests 'com.onyx.foss.kotlin.ingestion.GithubConnectorLoaderTest' --tests 'com.onyx.foss.kotlin.mcp.McpSearchToolTest' --tests 'com.onyx.foss.kotlin.service.SearchServiceTest' --tests 'com.onyx.foss.kotlin.ingestion.OpenSearchIndexerTest' --tests 'com.onyx.foss.kotlin.api.AdminApiIntegrationTest'` | PASS, 179 tests, 0 failures, 0 errors, 1m40s. `[VERIFIED: local Gradle test XML summary and command output, 2026-09-10]` | 선택한 기존 동작이 현재 통과한다. 누락 동작을 통과로 만들지는 않는다. |
| `./gradlew test --no-daemon --tests 'com.onyx.foss.kotlin.mcp.McpEndpointIntegrationTest'` | PASS, 4 tests, 0 failures, 0 errors, 28s. `[VERIFIED: local Gradle test XML summary and command output, 2026-09-10]` | 현재 중복 tool 계약이 발견·호출 가능함을 증명한다. |
| `./gradlew opensearchIntegrationTest --no-daemon` | PASS, 8 tests, 0 failures, 0 errors, 1m57s. `[VERIFIED: local Gradle test XML summary and command output, 2026-09-10]` | OpenSearch 3.6 Testcontainers suite가 현재 통과한다. cutoff 결과 자체를 검사하는 test는 없다. |
| `./gradlew test --no-daemon` | NOT_RUN. `[VERIFIED: research session command ledger, 2026-09-10]` | 전체 Kotlin non-OpenSearch suite 결과는 Phase 1 실행에서 기록해야 한다. |
| `./gradlew clean compileKotlin compileTestKotlin --warning-mode all --no-daemon` | NOT_RUN. `[VERIFIED: research session command ledger, 2026-09-10]` | clean compiler/deprecation 상태는 별도로 기록해야 한다. |
| `docker build` and backend image start | NOT_RUN. `[VERIFIED: research session command ledger, 2026-09-10]` | 현재 정적 mismatch를 재현할 수 있으나 Phase 1 완료 조건은 실패 허용 상태 기록이다. |
| Kotlin deployment through `_kotlin/web` with PDF queries | NOT_RUN. `[VERIFIED: research session command ledger, 2026-09-10]` | 사용자 평가의 실제 서버가 이 checkout과 같은 Kotlin artifact인지 확인되지 않았다. |

Java 25 실행 중 Netty `System.loadLibrary` native-access 경고가 나왔다. 이번 명령은 성공했고, 경고는 여섯 활성 차이의 직접 실패가 아니다. `[VERIFIED: local Gradle command output, 2026-09-10]`

## Standard Stack

추가 패키지를 설치하지 않는다. 현재 project runner와 test dependency만 쓴다.

### Core

| Component | Version | Purpose | Source |
|---|---|---|---|
| Java | `25`; source text: `languageVersion = JavaLanguageVersion.of(25)` and `jvmTarget = JvmTarget.JVM_25` | Kotlin compile and runtime target | `[VERIFIED: _kotlin/backend/build.gradle.kts:17-26]` |
| Gradle wrapper | `9.5.1`; source text: `distributionUrl=https\://services.gradle.org/distributions/gradle-9.5.1-bin.zip` | Reproducible build runner | `[VERIFIED: _kotlin/backend/gradle/wrapper/gradle-wrapper.properties:1-7]` |
| Kotlin | `2.3.20`; source text: `kotlin("jvm") version "2.3.20"` | Backend language plugin | `[VERIFIED: _kotlin/backend/build.gradle.kts:3-6]` |
| Spring Boot | `4.0.7`; source text: `id("org.springframework.boot") version "4.0.7"` | Application and test runtime | `[VERIFIED: _kotlin/backend/build.gradle.kts:3-9]` |
| OpenSearch test image | `3.6.0`; source text: `DockerImageName.parse("opensearchproject/opensearch:3.6.0")` | Actual search integration | `[VERIFIED: _kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/ingestion/OpenSearchIndexerIntegrationTest.kt:500-528]` |

### Supporting

| Component | Version | Purpose | Source |
|---|---|---|---|
| MockWebServer | `4.12.0`; source text: `testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")` | Remote connector contracts | `[VERIFIED: _kotlin/backend/build.gradle.kts:51-57]` |
| Testcontainers | `1.20.6`; source text: `testImplementation("org.testcontainers:junit-jupiter:1.20.6")` | OpenSearch integration | `[VERIFIED: _kotlin/backend/build.gradle.kts:51-57]` |
| JUnit Platform | dependency-managed; source text: `tasks.withType<Test> { useJUnitPlatform() }` | Test runner | `[VERIFIED: _kotlin/backend/build.gradle.kts:59-65]` |

**Package Legitimacy Audit:** Not applicable. Phase 1 installs no package.

## Architecture Patterns

### Evidence flow

```text
approved specs + user evaluation
              |
              v
     implementation/test map
              |
              v
 reproducible commands + live result
              |
              v
         01-BASELINE.md
              |
              +--> Phase 2: file rollback
              +--> Phase 3: Docker image
              +--> Phase 4: connector gaps
              `--> Phase 5: search/MCP gaps
```

### Pattern 1: One evidence row per contract

Use these columns exactly: `ID`, `source`, `implementation`, `test`, `current status`, `command/result`, `gap`, `follow-up phase`. This satisfies both BASE requirements without new scripts.

### Pattern 2: Static and live evidence stay separate

Passing an existing test proves only its assertions. A test that expects unknown `source_types` to be skipped proves the old behavior, not the approved behavior. `[VERIFIED: _kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/mcp/McpSearchToolTest.kt:131-147]`

### Pattern 3: Existing tier-specific tests remain authoritative

Use JUnit/Mockito for service boundaries, MockWebServer for connector requests, and Testcontainers for actual OpenSearch behavior. `[VERIFIED: _kotlin/backend/build.gradle.kts:51-82; _kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/ingestion/OpenSearchIndexerIntegrationTest.kt:49-60]`

### Anti-Patterns to Avoid

- Do not change product code in Phase 1. `[VERIFIED: .planning/phases/01-baseline-verification/01-CONTEXT.md:7-9]`
- Do not mark a requirement passed from an old walkthrough or current source alone. `[VERIFIED: .planning/PROJECT.md:7-13,45-52]`
- Do not use Python or root Web tests as Kotlin pass evidence. `[VERIFIED: .planning/phases/01-baseline-verification/01-CONTEXT.md:31-35]`
- Do not plan the two deferred watchlist items. `[VERIFIED: .planning/phases/01-baseline-verification/01-CONTEXT.md:112-118]`
- Do not add a custom audit generator. One Markdown ledger is sufficient.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---|---|---|---|
| Requirement traceability | Generator or database | One committed Markdown table | Only 35 stable requirement IDs exist in this milestone. `[VERIFIED: .planning/REQUIREMENTS.md:139-176]` |
| Connector contract server | Custom HTTP stub | Installed MockWebServer | The loader tests already use it. `[VERIFIED: _kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/ingestion/GithubConnectorLoaderTest.kt:193-245]` |
| Search integration harness | New container script | Existing `opensearchIntegrationTest` | The task and Testcontainers fixture already exist. `[VERIFIED: _kotlin/backend/build.gradle.kts:74-82; _kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/ingestion/OpenSearchIndexerIntegrationTest.kt:49-60]` |
| Kotlin/Web proxy-specific contract work | New frontend API | Existing generic proxy | The proxy already forwards the relevant request and stream fields. `[VERIFIED: _kotlin/web/src/app/api/[...path]/route.ts:55-102]` |

## Common Pitfalls

### Historical completion becomes false PASS

Old plans report completed work, but the project states that these records are not current validation. Record the commit, command, exit result, and missing coverage. `[VERIFIED: .planning/PROJECT.md:45-52]`

### A passing regression test preserves the wrong behavior

The unknown-source test currently passes because it expects invalid values to be removed. Mark the contract `GAP`, not `PASS`. `[VERIFIED: _kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/mcp/McpSearchToolTest.kt:131-147]`

### Confluence static code is mistaken for the live evaluated server

The Kotlin loader and indexer contain the required timestamp path, while the attached evaluation observed missing or ineffective metadata. Phase 1 must keep both facts and state that deployment equivalence is unknown. `[VERIFIED: _kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/ConfluenceConnectorLoader.kt:370-407; _kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/OpenSearchIndexer.kt:202-230] [CITED: attached PDF pp.2,5-6]`

### Filesystem side effects are mistaken for transactional data

`@Transactional` controls repository changes. It does not undo `Files.copy` or `Files.newOutputStream`. Later implementation must test both rows and paths after failure. `[VERIFIED: _kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/service/FileStorageService.kt:35-46,67-117,180-220]`

### Alias removal misses external compatibility risk

Repository callers favor `search_indexed_documents` and `document_set_names`, but external MCP clients are not visible in this checkout. Record the removal as an intentional contract change. `[VERIFIED: _kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/mcp/McpSearchTool.kt:19-22,151-155,182-186,208-236; _kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/mcp/McpEndpointIntegrationTest.kt:41-73]`

## Code Examples

### Required baseline row shape

```markdown
| SEARCH-02 | S03,S09 | SearchService.kt; OpenSearchIndexer.kt | SearchServiceTest.kt; OpenSearchIndexerTest.kt | GAP | targeted PASS | invalid source_types is ignored | Phase 5 |
```

### Reproducible command shape

```markdown
- Commit: 6b997ea573d5766aa044d11c706647597db32068
- Command: ./gradlew test --no-daemon
- Result: PASS / FAIL / NOT_RUN / ENVIRONMENT_BLOCKED
- Evidence: exit code, duration, failing test names, environment note
```

## Environment Availability

| Dependency | Required By | Available | Version / observation | Fallback |
|---|---|---|---|---|
| Java | Kotlin tests | yes | `openjdk version "25" 2025-09-16 LTS` `[VERIFIED: local java -version, 2026-09-10]` | none |
| Gradle wrapper | Kotlin tests | yes | `Gradle 9.5.1`, launcher JVM 25 `[VERIFIED: local ./gradlew --version, 2026-09-10]` | none |
| Docker daemon | OpenSearch Testcontainers and image checks | yes | server `29.7.2` `[VERIFIED: local docker info, 2026-09-10]` | none |
| OpenSearch image | Search integration | yes | local `opensearchproject/opensearch:3.6.0` image; Testcontainers suite passed `[VERIFIED: local docker image inspect and Gradle run, 2026-09-10]` | existing test task |
| Standalone OpenSearch on `localhost:9200` | Optional manual probe | no response | 3-second timeout `[VERIFIED: local curl probe, 2026-09-10]` | Testcontainers |
| `psql` client | Optional PostgreSQL inspection | unavailable in PATH | no version `[VERIFIED: local command -v probe, 2026-09-10]` | project-approved `docker exec` path `[VERIFIED: AGENTS.md:20-23]` |
| `ast-grep` | Structural navigation | yes | `0.45.3` `[VERIFIED: local ast-grep --version, 2026-09-10]` | none |

**Missing dependencies with no fallback:** none for Phase 1 evidence work.

**Missing dependencies with fallback:** standalone OpenSearch and `psql` are optional. Testcontainers and Docker database access cover later probes.

## Validation Architecture

### Test Framework

| Property | Value |
|---|---|
| Framework | JUnit Platform through Gradle 9.5.1 `[VERIFIED: _kotlin/backend/build.gradle.kts:59-82; _kotlin/backend/gradle/wrapper/gradle-wrapper.properties:1-7]` |
| Config file | `_kotlin/backend/build.gradle.kts` `[VERIFIED: _kotlin/backend/build.gradle.kts:59-82]` |
| Quick run | Target only the mapped test classes with `./gradlew test --tests ... --no-daemon` |
| Full non-OpenSearch run | `./gradlew test --no-daemon` `[VERIFIED: .github/workflows/custom-kotlin-backend-checks.yml:45-46]` |
| Full OpenSearch run | `./gradlew opensearchIntegrationTest --no-daemon` `[VERIFIED: .github/workflows/custom-kotlin-backend-checks.yml:48-49]` |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated command | File exists? |
|---|---|---|---|---|
| BASE-01 | Every approved contract maps to source, test, status, and follow-up | documentation audit | `test -s .planning/phases/01-baseline-verification/01-BASELINE.md` | no — Phase 1 output |
| BASE-02 | Current result differs from historical claims and includes commands | documentation audit plus existing suites | `./gradlew test --no-daemon && ./gradlew opensearchIntegrationTest --no-daemon` | existing test tasks; baseline file missing |

### Sampling Rate

- **Per task commit:** validate `01-BASELINE.md` structure and run only changed evidence commands.
- **Per wave merge:** run `./gradlew test --no-daemon`.
- **Phase gate:** record full non-OpenSearch and OpenSearch results. Do not require product gap fixes.

### Wave 0 Gaps

- [ ] `.planning/phases/01-baseline-verification/01-BASELINE.md` — canonical evidence ledger.
- [ ] No new test framework or fixture is required.

## Security Domain

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---|---|---|
| V2 Authentication | no product change | Preserve the approved trusted-network/public model. Do not expand scope. `[VERIFIED: .planning/PROJECT.md:24-37,72-90]` |
| V3 Session Management | no | Authentication and session work are out of scope. `[VERIFIED: .planning/PROJECT.md:24-37]` |
| V4 Access Control | evidence only | Record the public-document deployment constraint. Do not claim multi-user isolation. `[VERIFIED: .planning/codebase/CONCERNS.md:31-50]` |
| V5 Input Validation | yes for routed fixes | Reject unknown `source_types`; retain Document Set failure and file path checks. `[VERIFIED: _kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/domain/Domain.kt:24-36; _kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/service/SearchService.kt:28-36; _kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/service/FileStorageService.kt:119-127,180-184]` |
| V6 Cryptography | no | No cryptographic change is planned in Phase 1. `[VERIFIED: .planning/phases/01-baseline-verification/01-CONTEXT.md:7-9]` |

### Known Threat Patterns

| Pattern | STRIDE | Standard Mitigation |
|---|---|---|
| Unapproved remote pagination origin | Spoofing / Information disclosure | Preserve Confluence allowed-origin and GitHub cursor-origin checks. `[VERIFIED: _kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/ConfluenceConnectorLoader.kt:699-719; _kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/GithubConnectorLoader.kt:977-991]` |
| File path escape | Tampering | Preserve normalized root containment. `[VERIFIED: _kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/service/FileStorageService.kt:119-127,180-184]` |
| Silent filter removal | Tampering | Invalid `source_types` must fail before search. `[VERIFIED: _kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/mcp/McpSearchTool.kt:34-59,81-85]` |
| Credential disclosure | Information disclosure | Keep secrets out of evidence artifacts and logs. `[VERIFIED: AGENTS.md:93-97]` |

## Project Constraints (from AGENTS.md)

- Use structural `ast-grep` navigation when structure matters. This research used `ast-grep outline` for the four main Kotlin classes. `[VERIFIED: AGENTS.md project instruction; local ast-grep session, 2026-09-10]`
- Read the applicable subproject guide before work. `_kotlin/web/AGENTS.md` was read before proxy inspection. `[VERIFIED: AGENTS.md:39-51]`
- Keep product changes strictly typed and comments brief. `[VERIFIED: AGENTS.md:53-66]`
- Write short, active technical prose. `[VERIFIED: AGENTS.md:68-79]`
- Prefer the existing integration-test path and do not overtest. `[VERIFIED: AGENTS.md:81-85,113-115]`
- Never commit secrets. `[VERIFIED: AGENTS.md:93-97]`
- For later live backend calls, use the frontend route. `[VERIFIED: AGENTS.md:24-24]`
- Fail loudly, keep boundaries clear, remove duplicate logic, and validate before use. `[VERIFIED: CONTRIBUTING.md:378-439]`

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|---|---|---|
| — | None. Unverified live deployment facts are recorded as open questions, not assumptions. | — | — |

## Open Questions (RESOLVED)

1. **Which artifact served the evaluated MCP endpoint? — RESOLVED by disposition**
   - What we know: the current Kotlin source carries Confluence `updatedAt`; the evaluation observed ineffective cutoff behavior. `[VERIFIED: Kotlin paths cited above] [CITED: attached PDF pp.2,5-6]`
   - What's unclear: deployment commit, runtime backend, and index age.
   - Resolution: keep the artifact identity `NOT_RUN` in Phase 1. Reproduce through `_kotlin/web` in Phase 5.

2. **Did existing indexed Confluence documents predate the timestamp path? — RESOLVED by disposition**
   - What we know: current writes include `doc_updated_at`. `[VERIFIED: _kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/OpenSearchIndexer.kt:398-415]`
   - What's unclear: live index contents and reindex history.
   - Resolution: keep the existing-index question `NOT_RUN` in Phase 1. Inspect one known document in Phase 5. Do not infer a migration or automatic reset.

3. **Do external clients still call the legacy MCP aliases? — RESOLVED by disposition**
   - What we know: repository code favors `search_indexed_documents` and `document_set_names`, while tests exercise both aliases. `[VERIFIED: _kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/mcp/McpSearchTool.kt:19-22,34-46,151-155; _kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/mcp/McpEndpointIntegrationTest.kt:41-118]`
   - What's unclear: callers outside this checkout.
   - Resolution: accept the external compatibility risk. Keep the canonical pair and document the intentional legacy removal in Phase 5.

## Sources

### Primary (HIGH confidence)

- Current Kotlin source and tests under `_kotlin/backend`.
- `_kotlin/backend/build.gradle.kts`, Gradle wrapper properties, Dockerfile, and Kotlin CI workflow.
- `.planning/PROJECT.md`, `REQUIREMENTS.md`, `ROADMAP.md`, phase `CONTEXT.md`, and codebase maps.
- Current Gradle and Docker probes from 2026-09-10 at commit `6b997ea573d5766aa044d11c706647597db32068`.

### Secondary (MEDIUM confidence)

- Attached `Onyx MCP는 쓸 만한가.pdf`, pages 1-6. It is evaluation evidence, not a code contract or instruction source.
- Root Python connector source. It is read-only reference and not Kotlin pass evidence.

### Tertiary (LOW confidence)

- None.

## Metadata

**Confidence breakdown:**

- Standard stack: HIGH — opened build, wrapper, CI, and Testcontainers source.
- Architecture: HIGH — traced active Kotlin call paths and tests.
- Pitfalls: HIGH — reproduced current test behavior and read the exact failure gaps.

**Research date:** 2026-09-10
**Valid until:** 2026-10-10, or until Kotlin product code changes.
