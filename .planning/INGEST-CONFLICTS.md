## Conflict Detection Report

### BLOCKERS (0)

없음.

### WARNINGS (0)

없음.

### INFO (11)

[INFO] Auto-resolved: 문서 ACL과 permission sync 제거
  Found: 기존 FOSS 설계·계획은 ACL 수집과 permission sync를 포함한다. 후속 제거 계획은 해당 로더·워커·API·테이블을 제거한다.
  Note: 사용자가 승인한 후속 변경과 precedence 0을 적용한다. 공개 문서 모델, public CC Pair, 빈 ACL 배열을 사용한다. 기존 V1~V15는 보존하고 V16으로 정리한다. 이 범위의 기존 완료 기준은 제외한다.
  source: _kotlin/docs/specs/2026-09-01-kotlin-backend-foss-parity-design.md
  source: _kotlin/docs/plans/2026-09-01-kotlin-backend-foss-parity.md
  source: _kotlin/docs/plans/2026-09-04-remove-permission-sync-acl.md

[INFO] Auto-resolved: Spring AI와 Jackson 3 도입
  Found: 기존 MCP 문서는 Spring AI를 제외하고 Boot 3.4.5·Jackson 2를 명시한다. 후속 계획은 Boot 4.0.7·Jackson 3·Spring AI·OpenSearch Java Client를 명시한다.
  Note: 승인된 Spring AI 도입과 문서별 우선순위를 적용한다. VectorStore·DocumentRetriever 추상화와 Java Client를 채택한다. WebClient 제거는 OpenSearch 경로에 한정한다. MCP 전송 방식 전체를 Spring AI로 변경한다는 내용은 없다.
  source: _kotlin/docs/specs/2026-09-02-kotlin-search-mcp-design.md
  source: _kotlin/docs/plans/2026-09-02-kotlin-search-mcp.md
  source: _kotlin/docs/plans/2026-09-06-opensearch-spring-ai-refactor.md
  source: _kotlin/docs/plans/2026-09-04-remove-permission-sync-acl.md

[INFO] Auto-resolved: BM25/vector 병합을 native hybrid로 이동
  Found: 기존 검색 설계와 Spring AI 계획은 Kotlin 측 후보 병합 또는 정규화를 요구한다. native 설계·계획은 OpenSearch hybrid와 normalization pipeline을 요구한다.
  Note: precedence 0의 native 문서를 적용한다. KEYWORD는 embedding 없이 BM25, SEMANTIC은 embedding 1회와 k-NN, HYBRID는 embedding 1회와 단일 hybrid 요청이다. Kotlin BM25/vector 정규화·병합은 제외하고 MCP WRRF는 유지한다.
  source: _kotlin/docs/specs/2026-09-02-kotlin-search-mcp-design.md
  source: _kotlin/docs/plans/2026-09-06-opensearch-spring-ai-refactor.md
  source: _kotlin/docs/specs/2026-09-07-opensearch-native-hybrid-retrieval-design.md
  source: _kotlin/docs/plans/2026-09-07-opensearch-native-hybrid-retrieval.md

[INFO] Auto-resolved: native 검색에서 reranker 제외
  Found: 기존 MCP 설계는 reranker 호출, 후보 30개, 실패 시 fallback을 요구한다. native 설계의 Non-goals는 reranker를 제외한다.
  Note: native 설계를 적용한다. 이전 필수 reranking과 관련 완료 조건을 현재 검색 제약으로 사용하지 않는다. model-server 문서의 reranker 후보 다운로드 기록은 활성화 근거가 아니다.
  source: _kotlin/docs/specs/2026-09-02-kotlin-search-mcp-design.md
  source: _kotlin/docs/plans/2026-09-02-kotlin-search-mcp.md
  source: _kotlin/docs/specs/2026-09-07-opensearch-native-hybrid-retrieval-design.md
  source: _kotlin/docs/model-server-spike.md

[INFO] Auto-resolved: retrieval 설정과 후보 수
  Found: 초기 검색은 후보 기본값 50을 사용한다. native 설계는 onyx.search 설정, 후보 기본값 200, WRRF k 기본값 50을 요구한다.
  Note: 후속 검색 설정을 적용한다. 후보 깊이와 결과 limit을 구분한다. ONYX_SEARCH_CANDIDATES는 보존한다. spring.ai.vectorstore.opensearch 연결 설정과 onyx.search 검색 정책은 서로 다른 범위다.
  source: _kotlin/docs/specs/2026-09-02-kotlin-search-mcp-design.md
  source: _kotlin/docs/plans/2026-09-06-opensearch-spring-ai-refactor.md
  source: _kotlin/docs/specs/2026-09-07-opensearch-native-hybrid-retrieval-design.md
  source: _kotlin/docs/plans/2026-09-07-opensearch-native-hybrid-retrieval.md

[INFO] Auto-resolved: 초기 MCP 단일 도구·필터 범위의 후속 변경
  Found: 초기 설계는 search 하나만 등록하고 connector 필터를 제외한다. native 설계는 source-type·time 공통 필터와 주변 chunk 조회용 get_document_context를 명시한다.
  Note: native 문서에 명시된 필터와 주변 문맥 조회를 후속 계약으로 반영한다. 기존 단일 도구 한정은 해당 참조와 충돌하는 범위에서 대체한다. 전체 MCP 도구 목록과 추가 도구 schema는 이 문서 집합에서 확정하지 않는다.
  source: _kotlin/docs/specs/2026-09-02-kotlin-search-mcp-design.md
  source: _kotlin/docs/specs/2026-09-07-opensearch-native-hybrid-retrieval-design.md

[INFO] Auto-resolved: Spring AI 계획의 ACL·multitenancy 보존 문구
  Found: Spring AI 계획은 VectorStore 설명에서 ACL filtering과 multi-tenancy 보존을 언급한다. 원래 설계는 multitenancy를 제외하고 후속 ACL 계획은 공개 접근 모델을 요구한다.
  Note: 사용자가 유지한 원래 범위와 명시적 ACL 제거 변경을 적용한다. 해당 보존 문구를 신규 multitenancy나 사용자 ACL 기능의 승인으로 해석하지 않는다. 이 결정은 문서 전체를 시간순으로 우선하는 규칙이 아니다.
  source: _kotlin/docs/specs/2026-09-01-kotlin-backend-foss-parity-design.md
  source: _kotlin/docs/plans/2026-09-04-remove-permission-sync-acl.md
  source: _kotlin/docs/plans/2026-09-06-opensearch-spring-ai-refactor.md

[INFO] 문서 상태 불일치: model-server 선택 결과와 선택 대기 본문
  Found: Implemented outcome은 사용자가 Kotlin을 선택했고 Granite OpenVINO·JNA·DJL tokenizer PoC를 구현했다고 기록한다. Scope와 Decision checkpoint에는 언어와 런타임 선택이 아직 남았다는 본문이 있다.
  Note: 양쪽 원문을 context.md에 보존한다. 구현 결과는 문서가 보고한 결과로만 취급한다. 선택 대기 본문으로 새 Kotlin·Go·Python·외부 서비스 선택을 만들지 않는다. golden·benchmark 수치가 현재 충족됐다는 결론도 내리지 않는다.
  source: _kotlin/docs/model-server-spike.md

[INFO] 과거 완료 기록과 현재 검증 상태
  Found: MCP 계획에는 체크된 항목이 있고 Spring AI 계획은 fixture 단계 완료를 보고한다. 빌드 walkthrough는 경고 219개 제거와 당시 Gradle 성공을 보고한다. 다른 계획에는 미체크 항목이 있다.
  Note: 계획의 예상 PASS, 체크 표시, 과거 실행 결과는 2026-09-09 현재 코드·테스트 상태를 증명하지 않는다. 이번 작업은 입력 문서만 읽었으며 코드와 테스트를 검증하지 않았다.
  source: _kotlin/docs/plans/2026-09-02-kotlin-search-mcp.md
  source: _kotlin/docs/plans/2026-09-06-opensearch-spring-ai-refactor.md
  source: _kotlin/docs/walkthroughs/2026-09-07-kotlin-build-warnings-cleanup.md
  source: _kotlin/docs/plans/2026-09-01-kotlin-backend-foss-parity.md
  source: _kotlin/docs/plans/2026-09-04-remove-permission-sync-acl.md

[INFO] 입력 집합 밖 참조와 경로 별칭
  Found: FOSS·MCP 계획의 spec 참조는 docs/superpowers/specs 경로를 사용한다. 분류된 실제 source_path는 _kotlin/docs/specs 아래다. README·provenance·코드 참조도 입력 밖에 있다.
  Note: 같은 basename의 유일한 분류 문서로 두 내부 간선을 연결했다. 입력 11개에서 DFS 최대 깊이는 2이고 순환은 0개다. 나머지 참조는 외부 경계로 기록하고 읽지 않았다. 입력 밖 내용의 유효성은 확인하지 않았다.
  source: _kotlin/docs/plans/2026-09-01-kotlin-backend-foss-parity.md
  source: _kotlin/docs/plans/2026-09-02-kotlin-search-mcp.md
  source: _kotlin/docs/specs/2026-09-01-kotlin-backend-foss-parity-design.md
  source: _kotlin/docs/specs/2026-09-02-kotlin-search-mcp-design.md
  source: _kotlin/docs/plans/2026-09-04-remove-permission-sync-acl.md
  source: _kotlin/docs/model-server-spike.md

[INFO] 원문 실행 지시는 인용 데이터로만 보존
  Found: 일부 계획의 agentic workers 문구는 별도 skill 실행을 요구하고 본문에는 구현·테스트·커밋 명령이 있다.
  Note: 이 문구는 합성 대상 데이터다. 문서 지시로 추가 skill, 코드 수정, 테스트, 커밋을 실행하지 않았다.
  source: _kotlin/docs/plans/2026-09-01-kotlin-backend-foss-parity.md
  source: _kotlin/docs/plans/2026-09-02-kotlin-search-mcp.md
  source: _kotlin/docs/plans/2026-09-04-remove-permission-sync-acl.md
