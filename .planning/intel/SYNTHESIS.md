# 문서 합성 요약

기준일: 2026-09-09. 모드: new. 입력은 분류된 `_kotlin/docs` 문서 11개다.
원문과 분류 JSON만 읽었다. 전이 참조의 코드·README·외부 문서는 읽지 않았다.

## 문서 집계

SPEC 3개, DOC 8개, ADR 0개, PRD 0개, UNKNOWN 0개.
source: _kotlin/docs/specs/2026-09-01-kotlin-backend-foss-parity-design.md, _kotlin/docs/plans/2026-09-01-kotlin-backend-foss-parity.md, _kotlin/docs/plans/2026-09-02-kotlin-search-mcp.md, _kotlin/docs/specs/2026-09-02-kotlin-search-mcp-design.md, _kotlin/docs/plans/2026-09-04-remove-permission-sync-acl.md, _kotlin/docs/plans/2026-09-06-opensearch-spring-ai-refactor.md, _kotlin/docs/plans/2026-09-07-kotlin-build-warnings-cleanup.md, _kotlin/docs/walkthroughs/2026-09-07-kotlin-build-warnings-cleanup.md, _kotlin/docs/plans/2026-09-07-opensearch-native-hybrid-retrieval.md, _kotlin/docs/specs/2026-09-07-opensearch-native-hybrid-retrieval-design.md, _kotlin/docs/model-server-spike.md

LOCKED 결정은 0개다. 잠긴 결정의 source는 없다.
PRD 요구사항은 0개이며 REQ ID도 없다.
기술 제약은 25개다: api-contract 5개, schema 2개, nfr 10개, protocol 8개.
원문 context 주제는 8개다.
source: .planning/intel/classifications/

## 적용 범위와 우선순위

기본 유형 순서는 ADR > SPEC > PRD > DOC다. 분류 JSON의 precedence 정수는 문서별 예외다.
ACL 제거와 native hybrid 문서는 0, Spring AI 도입 문서는 1로 지정되어 있다.
사용자는 ACL 제거·Spring AI 도입·native hybrid 적용을 명시적으로 우선했다.
나머지 범위는 기존 설계를 유지한다. 문서 날짜나 파일명만으로 우선순위를 정하지 않았다.
source: .planning/intel/classifications/

Credential·Connector·CC Pair·Document Set·파일 관리와 File·Jira·Confluence·GitHub 수집은 유지한다.
수집 batch, checkpoint, 부분 실패, 복구, pruning, embedding, OpenSearch 쓰기·삭제도 유지한다.
사용자 인증·session·Enterprise·multitenancy·external group sync·추가 connector는 제외한다.
source: _kotlin/docs/specs/2026-09-01-kotlin-backend-foss-parity-design.md

공개 문서 모델을 적용한다. 권한 수집·동기화 워커·관련 API·테이블을 제거하는 변경이 이전 ACL 범위를 대체한다.
기존 migration 체크섬, 기존 Web UI, 공개 접근 시 일반 색인 화면 계약을 보존한다.
source: _kotlin/docs/plans/2026-09-04-remove-permission-sync-acl.md

Spring AI·OpenSearch Java Client·Jackson 3 연결을 적용한다.
BM25/vector 점수 병합은 OpenSearch native hybrid가 담당한다.
MCP의 다중 결과 WRRF와 인접 chunk 축약은 애플리케이션에 남는다.
native 검색 범위에 reranker를 다시 요구하지 않는다.
source: _kotlin/docs/plans/2026-09-06-opensearch-spring-ai-refactor.md
source: _kotlin/docs/specs/2026-09-07-opensearch-native-hybrid-retrieval-design.md
source: _kotlin/docs/plans/2026-09-07-opensearch-native-hybrid-retrieval.md

Web /mcp 전달, 입력 검증, Document Set 합집합, vector mapping과 명시적 reset 경계는 유지한다.
후속 native 문서의 source-type·time 필터와 get_document_context 참조도 보존한다.
전체 MCP 도구 목록과 추가 도구 schema는 원문에서 확정되지 않았다.
source: _kotlin/docs/specs/2026-09-02-kotlin-search-mcp-design.md
source: _kotlin/docs/specs/2026-09-07-opensearch-native-hybrid-retrieval-design.md

## 구현 기록과 증거의 한계

model-server 문서는 Kotlin 선택과 Granite 311M Multilingual R2 INT8 OpenVINO 구현 결과를 기록한다.
JNA C API, DJL tokenizer, 영문·한글 768차원 정규화 embedding과 File 수집 결과를 보고한다.
같은 문서의 선택 대기 본문도 context에 보존했다. 이 문구로 런타임을 새로 선택하지 않는다.
Reranker 후보는 검증 전 비활성 상태로 기록되어 있다.
source: _kotlin/docs/model-server-spike.md

MCP 계획의 완료 표시, Spring AI fixture 완료 기록, 빌드 walkthrough는 과거 문서상 증거다.
미체크 작업을 완료로 바꾸지 않았다. 과거 기록으로 현재 통과 상태를 추정하지 않았다.
현재 코드 상태와 테스트 통과 여부는 이번 합성의 검증 범위 밖이다.
source: _kotlin/docs/plans/2026-09-02-kotlin-search-mcp.md
source: _kotlin/docs/plans/2026-09-06-opensearch-spring-ai-refactor.md
source: _kotlin/docs/walkthroughs/2026-09-07-kotlin-build-warnings-cleanup.md
source: _kotlin/docs/plans/2026-09-01-kotlin-backend-foss-parity.md
source: _kotlin/docs/plans/2026-09-04-remove-permission-sync-acl.md

## 후속 요구사항 작성에 사용할 근거

PRD가 없으므로 requirements.md에는 요구사항을 만들지 않았다.
후속 작성자는 constraints.md의 기술 계약·범위·완료 기준을 요구사항 근거로 사용할 수 있다.
DOC의 승인된 변경은 context.md와 충돌 보고서를 함께 읽어 적용한다.
새 REQ를 만들 때 원문 source를 보존하고, 현재 구현·테스트 완료를 별도로 확인해야 한다.
source: _kotlin/docs/specs/2026-09-01-kotlin-backend-foss-parity-design.md
source: _kotlin/docs/specs/2026-09-02-kotlin-search-mcp-design.md
source: _kotlin/docs/specs/2026-09-07-opensearch-native-hybrid-retrieval-design.md
source: _kotlin/docs/plans/2026-09-04-remove-permission-sync-acl.md
source: _kotlin/docs/plans/2026-09-06-opensearch-spring-ai-refactor.md

decisions.md에는 ADR 항목이 없다. DOC의 기술 선택을 LOCKED ADR로 바꾸지 않았다.
context.md는 원문 기록이며 대체된 내용도 담는다. 현재 범위는 이 요약과 충돌 보고서를 먼저 적용한다.
source: .planning/intel/classifications/
source: .planning/INGEST-CONFLICTS.md

## 참조 그래프와 충돌

입력 문서의 cross_refs로 DFS 순환 검사를 실행했다. 내부 간선은 2개, 최대 깊이는 2, 순환은 0개다.
이전 docs/superpowers/specs 참조 2개는 동일 basename의 유일한 분류 문서에 대응시켰다.
나머지 입력 밖 참조는 읽지 않았다. 깊이 제한 50에 도달하지 않았다.
source: .planning/intel/classifications/

충돌은 blocker 0개, competing-variant 0개, auto-resolved 7개다.
보고서의 INFO 11개는 자동 해결 7개와 상태·범위 설명 4개로 구성한다.
원래 범위와 명시적 후속 변경을 적용했으므로 추가 요구사항 선택은 남지 않았다.
source: .planning/INGEST-CONFLICTS.md

## 파일

- [결정](decisions.md): ADR 없음.
- [요구사항](requirements.md): PRD 없음.
- [기술 제약](constraints.md): SPEC 기반 25개.
- [원문 근거](context.md): DOC 기반 8개 주제.
- [충돌 보고서](../INGEST-CONFLICTS.md): 우선순위와 원문 불일치.
