# WATCHLIST.md

schema_version: 2
timezone: Asia/Seoul

## Open

### WL-20260901-001 - 첨부파일과 이미지 벡터화 확인
- status: open
- priority: P1
- owner: both
- due_at: unscheduled
- created_at: 2026-09-01T00:41:13+09:00
- source: Kotlin backend FOSS parity implementation review
- trigger: 텍스트 수집만 복원하면 첨부파일 처리와 이미지 벡터화가 누락될 수 있다.
- action: File·Confluence 수집과 indexing 구현에서 첨부파일 추출, 이미지 처리, 벡터화를 다시 확인한다.
- done_when: 첨부파일과 이미지 벡터화의 Python 대응 시나리오가 parity matrix와 Kotlin 테스트에 반영된다.
- next_step_on_fail: 누락 시 해당 Python 시나리오를 실패 테스트로 추가하고 구현 계획에 반영한다.

### WL-20260901-002 - OpenSearch 인증서 검증 환경변수 복구
- status: open
- priority: P1
- owner: both
- due_at: unscheduled
- created_at: 2026-09-01T20:20:10+09:00
- source: backend/src/main/resources/application.yml OpenSearch configuration
- trigger: Kotlin 백엔드의 OpenSearch 인증서 검증을 임시로 false에 고정했다.
- action: Kotlin 백엔드에서 OPENSEARCH_VERIFY_CERTS 환경변수로 인증서 검증을 제어한다.
- done_when: true와 false 설정이 각각 적용되고 TLS 연결 테스트가 통과한다.
- next_step_on_fail: Kotlin OpenSearch 클라이언트의 SSL 설정 경로와 환경변수 바인딩을 다시 확인한다.

### WL-20260901-003 - OpenSearch 연결 실패와 재시도 제어
- status: open
- priority: P1
- owner: both
- due_at: unscheduled
- created_at: 2026-09-01T21:17:11+09:00
- source: OpenSearch 장애 동작 확인 대화
- trigger: OpenSearch에 연결할 수 없어도 애플리케이션이 시작되고 connector가 약 1초 간격으로 연결을 반복한다.
- action: 시작 시 OpenSearch 연결을 확인해 실패하면 애플리케이션을 종료하고, 이후 연결 재시도에는 제한된 지수 백오프를 적용한다.
- done_when: OpenSearch 연결 실패 시 애플리케이션이 준비 상태가 되지 않고, 재시도 간격과 최대 빈도가 테스트로 검증된다.
- next_step_on_fail: 시작 의존성 검사 위치와 connector 작업의 재시도 정책을 분리해 다시 확인한다.

### WL-20260901-004 - OpenSearch 장애 중 connector 삭제 복구
- status: open
- priority: P1
- owner: both
- due_at: unscheduled
- created_at: 2026-09-01T21:17:11+09:00
- source: backend/src/main/kotlin/com/onyx/kotlin/connector/ConnectorService.kt; backend/src/main/kotlin/com/onyx/kotlin/ingestion/IngestionQueryService.kt; backend/src/main/kotlin/com/onyx/kotlin/ingestion/JobClaimService.kt; web/src/app/admin/indexing/status/CCPairIndexingStatusTable.tsx
- trigger: OpenSearch 인증 또는 접속 장애 중 삭제하면 DB는 먼저 DELETING이 되지만 외부 삭제 실패 후 복구 경로가 없다. RUNNING job과 IN_PROGRESS attempt도 종료되지 않는다.
- action: 삭제 요청을 재시도 가능하게 만들고, 활성 job과 attempt를 취소 또는 실패 상태로 종료한다. 목록은 DELETING pair를 INITIAL_INDEXING으로 덮어쓰지 않게 수정한다.
- done_when: 인증 정상과 OpenSearch 401·접속 실패 조건의 삭제 테스트가 통과한다. 상세·목록·attempt 화면은 동일한 상태 전이를 표시하고, job·attempt·pair·connector·OpenSearch 데이터가 일관된다.
- last_checked_at: 2026-09-01T21:46:37+09:00
- result: OpenShift에서 삭제 요청이 OpenSearch 401 Unauthorized로 실패했다. Backend은 인증 환경변수를 받지만 WebClient에 Authorization 헤더를 설정하지 않았다. pair는 DELETING, job은 RUNNING, attempt는 IN_PROGRESS로 남았다. 목록 UI는 완료 attempt가 없으면 pair 상태를 무시하고 INITIAL_INDEXING으로 표시했다. 1883fa04b에서 배포의 OPENSEARCH_ADMIN_USERNAME·OPENSEARCH_ADMIN_PASSWORD를 Basic Auth로 연결했다.
- next_step_on_fail: 인증 수정과 삭제 상태 전이를 분리해 테스트한다. 그런 다음 장애 후 재시도 또는 롤백 규칙을 결정한다.

### WL-20260903-002 - Jira 크레덴셜 등록 단계의 Base URL 입력 및 검증 분리
- status: open
- priority: P2
- owner: both
- due_at: unscheduled
- created_at: 2026-09-03T17:42:00+09:00
- source: web/src/lib/connectors/credentials.ts; web/src/lib/connectors/connectors.tsx; backend/src/main/kotlin/com/onyx/kotlin/connector/loader/JiraConnectorLoader.kt
- trigger: 현재 Jira 커넥터는 Credential 생성 단계에 Base URL 입력 필드가 없어 커넥터 설정 단계에서 jira_base_url을 받고 있다. 이로 인해 Credential 생성 시점에 Cloud/Server 여부를 알 수 없고 사전 검증도 불가능한 UI/모델 설계 문제가 있다.
- action: Jira Credential 등록 단계에 Base URL 필드를 추가하여 크레덴셜 생성 시점에 사전 검증과 Cloud/Server 분기를 수행하도록 개선하고, 커넥터 설정 단계에서는 크레덴셜의 Base URL을 연동하도록 재설계한다.
- done_when: Jira 크레덴셜 생성 UI에서 Base URL을 입력받아 사전 검증할 수 있고, 커넥터 설정과 분리된 온프레미스/클라우드 자격 증명 관리가 테스트로 검증된다.
- next_step_on_fail: 커넥터 설정 단계의 jira_base_url 기반 판별 로직을 유지하면서 점진적 UI 개편을 진행한다.

### WL-20260910-003 - GitHub PR 리뷰 댓글 문서 정규화
- status: open
- priority: P2
- owner: both
- due_at: unscheduled
- created_at: 2026-09-10T21:02:44+09:00
- source: https://github.com/wrallee/onyx-kotlin/pull/26
- trigger: 현재 PR 본문과 리뷰 댓글을 하나의 content 필드에 결합한다. 기존 검색 구조에는 적합하지만 댓글별 식별자와 스레드 관계는 보존하지 않는다.
- action: 리뷰 댓글 검색 요구가 커지면 PR, 리뷰 스레드, 댓글을 안정적인 ID와 parent_pr_id로 정규화하고 전체 GitHub connector 재수집 절차를 정의한다.
- done_when: 댓글이 PR 또는 리뷰 스레드 단위 문서로 색인되고 parent 기반 결과 중복 억제, 삭제 반영, 전체 재수집이 테스트로 검증된다.
- next_step_on_fail: 현재 PR content 결합 방식을 유지하고 댓글 누적 크기 제한과 문서 단위 collapse를 계속 적용한다.

### WL-20260911-001 - cross-pair 중복 문서의 최신 복사본 선택
- status: open
- priority: P1
- owner: both
- due_at: unscheduled
- created_at: 2026-09-11T07:20:19+09:00
- source: PR #25; backend/src/main/kotlin/com/onyx/kotlin/opensearch/OpenSearchIndexer.kt; fix/mcp-filter-contract f1e82cfd1
- trigger: 서로 다른 base URL은 source_document_id로 구분되지만, 같은 논리 문서를 여러 ccPair가 다른 시점에 색인하면 hybrid collapse가 최신본 대신 검색 점수가 높은 복사본을 선택할 수 있다. keyword와 semantic 검색은 cross-pair collapse를 적용하지 않는다.
- action: 정규화한 논리 문서 ID와 최신본 우선 atomic upsert를 PR #25 이후 통합하고, pair별 membership과 삭제 동작을 함께 검증한다.
- done_when: 서로 다른 base URL의 동일 문서 번호는 분리되고, 같은 논리 문서의 구버전과 신버전이 함께 있어도 keyword·semantic·hybrid 검색과 context가 최신 내용만 반환한다.
- next_step_on_fail: 색인 단계의 최신본 통합과 검색 단계의 최신본 선택 중 더 작은 공통 수정 지점을 다시 비교한다.

### WL-20260911-002 - GitHub GraphQL 전환과 증분 수집·PR N+1 개선
- status: open
- priority: P1
- owner: both
- due_at: unscheduled
- created_at: 2026-09-11T07:51:36+09:00
- source: PR #26; backend/src/main/kotlin/com/onyx/kotlin/connector/loader/GithubConnectorLoader.kt
- trigger: REST 기반 PR 수집은 목록 뒤 각 PR 상세와 리뷰 댓글을 개별 조회해 호출 수가 PR 수에 비례한다. 파일 수집은 저장소에 push가 있으면 전체 tree와 대상 파일을 다시 읽고, 리뷰 댓글만 변경된 경우의 증분 반영 계약도 명확하지 않다.
- action: GitHub.com과 GitHub Enterprise Server에서 GraphQL로 PR 본문, 메타데이터, 리뷰 스레드와 댓글을 페이지 단위로 조회한다. PR·댓글·파일 변경을 독립적으로 추적하는 시간 범위와 checkpoint를 정하고, 현재 overlap, 삭제 prune, rate limit 비용과 실패 후 재개 계약을 보존한다.
- done_when: 일반 페이지는 PR별 상세 REST 호출 없이 수집되고, 100개를 넘는 PR·리뷰 스레드·댓글도 누락 없이 이어서 조회된다. 댓글만 변경된 PR과 파일 변경이 불필요한 전체 재조회 없이 반영되며, pagination·checkpoint 재개·삭제 prune이 회귀 테스트로 검증된다.
- next_step_on_fail: GraphQL 또는 변경 파일 API의 호환성이 부족하면 REST 저장소 단위 댓글 조회와 현재 전체 파일 수집을 분리해 단계적으로 개선한다.

## Done

### WL-20260911-003 - Connector 상세 화면 Resolve All 동작 확인
- status: done
- priority: P1
- owner: both
- due_at: unscheduled
- created_at: 2026-09-11T01:53:32+09:00
- source: Connector 상세 화면 Resolve All 동작 확인 대화
- trigger: Resolve All 뒤 full reindex가 끝나도 오류 알림이 남았다.
- action: Python과 같은 오류 재색인 및 해결 규칙을 Kotlin에 적용한다.
- done_when: 성공, 삭제, 재실패와 불완전 열거 시나리오가 통합 테스트를 통과한다.
- last_checked_at: 2026-09-11T22:44:02+09:00
- result: Confluence targeted reindex와 완전한 full reindex의 과거 오류 정리를 구현했다. 삭제 문서는 색인과 DB에서 제거하며 재실패와 불완전 열거 오류는 보존한다. 단위 테스트 344개와 OpenSearch 통합 테스트 9개가 통과했다.

### WL-20260903-001 - OpenSearch 검색 응답 버퍼 한도 임시 상향
- status: dropped
- priority: P2
- owner: both
- due_at: unscheduled
- created_at: 2026-09-03T00:00:00+09:00
- source: e41aa6847; backend/src/main/kotlin/com/onyx/kotlin/opensearch/OpenSearchIndexer.kt
- trigger: WebClient의 256KB 응답 제한 때문에 OpenSearch 검색 버퍼를 16MB로 임시 상향했다.
- action: 임시 버퍼 상향을 제거할 수 있는지 확인한다.
- done_when: OpenSearch 검색이 WebClient codec 제한에 의존하지 않는다.
- last_checked_at: 2026-09-11T23:45:58+09:00
- result: OpenSearch가 공식 Java client로 전환되어 WebClient codec 제한과 16MB 임시 설정이 모두 제거됐다. 기존 후속 작업은 더 이상 적용되지 않는다.

### WL-20260901-005 - Confluence space probe의 lazy 계약 복구
- status: done
- priority: P1
- owner: both
- due_at: unscheduled
- created_at: 2026-09-01T21:42:27+09:00
- source: backend/src/main/kotlin/com/onyx/kotlin/connector/loader/ConfluenceConnectorLoader.kt
- trigger: Kotlin의 eager space 목록 조회를 피하려고 credential 검증에서 첫 페이지만 직접 읽는 임시 수정을 적용했다.
- action: Confluence space 조회를 Python generator와 같은 lazy 계약으로 정리하고 probe와 전체 pagination의 책임을 분리한다.
- done_when: probe는 첫 space에서 중단되고 전체 space 조회는 모든 페이지를 처리하며 Cloud v2 fallback까지 테스트로 검증된다.
- last_checked_at: 2026-09-11T01:55:02+09:00
- result: ae515b531에서 credential probe가 첫 space 뒤 중단되도록 수정했고, 전체 pagination과 Cloud v2 fallback 테스트를 분리해 검증했다.
- next_step_on_fail: probe 전용 API와 전체 space pagination API를 분리하는 설계를 다시 검토한다.

### WL-20260901-006 - OpenSearch 공식 Java client 전환
- status: done
- priority: P1
- owner: both
- due_at: unscheduled
- created_at: 2026-09-01T21:54:32+09:00
- source: backend/src/main/kotlin/com/onyx/kotlin/opensearch/OpenSearchIndexer.kt; backend/src/main/kotlin/com/onyx/kotlin/opensearch/OpenSearchClientFactory.kt
- trigger: Kotlin backend이 WebClient로 OpenSearch REST API와 JSON, 인증, TLS, 오류 처리를 직접 관리한다.
- action: OpenSearchIndexer를 opensearch-java와 ApacheHttpClient5Transport 기반으로 전환한다.
- done_when: 현재 OpenSearch index 생성, mapping, 검색과 쓰기 작업이 공식 client로 동작하고 인증·TLS·timeout 설정이 통합 테스트로 검증된다.
- last_checked_at: 2026-09-11T01:55:02+09:00
- result: e41aa6847부터 OpenSearch Java Client와 ApacheHttpClient5Transport를 사용한다. 이후 자동 reindex와 alias 교체는 요구사항 변경으로 제거했고, 현재 strict mapping과 검색·쓰기 계약을 통합 테스트로 검증한다.
- next_step_on_fail: 공식 client에 typed API가 없는 작업만 generic transport로 분리한다.

### WL-20260902-001 - Spring 업그레이드와 Spring AI 도입 검토
- status: done
- priority: P2
- owner: both
- due_at: unscheduled
- created_at: 2026-09-02T10:36:44+09:00
- source: backend/build.gradle.kts; Kotlin 검색·MCP 구현 대화
- trigger: 벡터 데이터베이스 검색을 직접 구현하기 전에 Spring AI의 Vector Store 지원을 활용할 수 있는지 확인해야 한다.
- action: Spring Boot를 호환 버전으로 올리고 Spring AI를 추가하는 방안을 우선 검토한다.
- done_when: 호환 버전과 마이그레이션 범위가 정해지고 Spring AI 기반 벡터 검색 도입 여부가 검증 결과와 함께 결정된다.
- last_checked_at: 2026-09-11T01:55:02+09:00
- result: Spring Boot 4.0.7과 Spring AI 1.0.0을 도입했다. OpenSearch Java Client 기반 vector store와 native hybrid 검색 계약을 구현하고 관련 테스트를 추가했다.
- next_step_on_fail: 현재 OpenSearch Java Client 기반 구현과 Spring AI 경계를 다시 확인한다.

### WL-20260910-001 - 동일 문서 chunk의 검색 결과 독식 방지
- status: done
- priority: P0
- owner: assistant_on_review
- due_at: unscheduled
- created_at: 2026-09-10T13:51:07+09:00
- source: Onyx MCP 평가 PDF, 2026-09-08
- trigger: 긴 문서의 유사 chunk가 상위 결과를 독식해 다른 문서의 근거를 밀어낸다. 설계 범위가 커서 이번 수정에서 보류한다.
- action: 문서별 chunk 반환 상한과 space·repository 범위 필터를 비교하고 최소 변경으로 결과 다양성을 보장한다.
- done_when: 재현 질의에서 동일 logical document는 검색 결과 하나만 차지하고 다른 문서가 상위 결과에 남는 동작이 테스트로 검증된다.
- last_checked_at: 2026-09-11T01:55:02+09:00
- result: 9fa27fec3에서 source_document_id 기준 OpenSearch collapse를 추가해 동일 logical document의 중복 chunk를 제거했고 검색 회귀 테스트로 검증했다.
- next_step_on_fail: collapse 이후에도 다양성이 부족하면 connector 범위 필터와 후보 배수를 별도로 측정한다.

### WL-20260910-002 - limit 증가에 따른 MCP 응답 토큰 과대 방지
- status: done
- priority: P0
- owner: assistant_on_review
- due_at: unscheduled
- created_at: 2026-09-10T13:51:07+09:00
- source: Onyx MCP 평가 PDF, 2026-09-08
- trigger: limit 증가 시 긴 chunk 전문이 함께 반환돼 응답 토큰이 비선형으로 증가한다. 반환 계약 검토가 필요해 이번 수정에서 보류한다.
- action: 기본 limit, 반환 필드, chunk 축약 방식을 검토하고 품질을 유지하는 최소 응답 계약을 정한다.
- done_when: limit 3·5·10·20 회귀 측정에서 응답 크기 상한을 지키고 필요한 근거와 text·structuredContent 계약을 보존한다.
- last_checked_at: 2026-09-10T15:16:09+09:00
- result: chunk 전문 대신 검색어 주변 300자 excerpt와 metadata를 반환하고 상세 조회를 get_document_context로 분리했다. limit 3·5·10·20에서 excerpt 계약과 text·structuredContent 동등성을 검증했다. 기본 limit은 30, 최대는 50이다.
- next_step_on_fail: 기본 limit 하향과 선택적 상세 조회를 분리해 단계적으로 적용한다.

## Archive

### WL-20260911-004 - Resilience4j 기반 커넥터 부하 및 장애 격리
- status: dropped
- priority: P1
- owner: both
- due_at: unscheduled
- created_at: 2026-09-11T08:11:09+09:00
- source: PR #28; backend/src/main/kotlin/com/onyx/kotlin/connector/loader/RemoteJsonClient.kt; backend/src/main/kotlin/com/onyx/kotlin/ingestion/IngestionWorker.kt
- trigger: 여러 worker Pod가 같은 pair와 job을 중복 실행할 가능성을 확인해야 했다.
- action: 기존 active-job 제약, pair lock, lease와 claim token 보호를 검증한다.
- done_when: 동시 enqueue와 claim, lease reclaim, stale worker 차단과 checkpoint 보존 테스트가 통과한다.
- last_checked_at: 2026-09-11T22:24:24+09:00
- result: 추가 처리가 필요 없다. 각 Pod의 ingestion worker는 기본 단일 스레드 scheduler에서 job을 하나씩 동기 처리한다. 여러 Pod가 동시에 polling해도 active-job unique constraint, pair 비관적 잠금과 조건부 claim이 같은 pair와 job의 실행자를 하나로 제한한다. lease 만료 후에는 claim token 검증이 이전 worker의 쓰기와 완료 처리를 차단한다. 동시 claim과 lease reclaim 테스트로 이 동작을 확인했으므로 Resilience4j와 추가 락을 도입하지 않는다.
