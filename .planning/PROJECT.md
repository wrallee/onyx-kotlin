# Onyx Kotlin

## What This Is

Onyx Kotlin은 기존 Onyx Web을 사용하는 Kotlin backend·수집·검색·MCP 프로젝트다.
File·Jira·Confluence·GitHub에서 문서를 수집하고 Kotlin model-server의 embedding을 OpenSearch에 적재한다.
운영자는 기존 관리 화면을 사용하고, MCP client는 Web `/mcp`에서 공개 문서를 검색한다.

## Core Value

기존 커넥터의 문서를 안전하게 수집하고, 공개 MCP 검색에서 정확한 근거 chunk를 제공한다.

## Requirements

### Validated

현재 검증으로 확정한 항목은 없다. 기존 구현이 없다는 뜻은 아니다.
문서에는 완료·구현 기록이 있으나, 이번 작업에서는 코드와 제품 테스트를 확인하지 않았다.

### Active

- [ ] 기존 구현·검증 범위와 남은 차이를 원문 계약에 연결한다.
- [ ] 기존 Web에서 Credential·Connector·CC Pair·Document Set·파일 관리 계약을 유지한다.
- [ ] 공개 문서 모델을 적용하고 ACL 수집·permission sync를 제거한 상태를 검증한다.
- [ ] 선택된 Kotlin model-server의 HTTP·tokenizer·embedding·운영 계약을 검증한다.
- [ ] Spring AI·OpenSearch Java Client·Jackson 3 경로에서 색인과 삭제 의미를 유지한다.
- [ ] 네 커넥터의 batch·checkpoint·부분 실패·복구·pruning 차이를 보완한다.
- [ ] Native hybrid 검색, Web MCP 전달, 다중 결과 WRRF와 chunk 다양성을 검증한다.

상세 요구사항 35개와 원문 근거는 [REQUIREMENTS.md](REQUIREMENTS.md)에 있다.

### Out of Scope

- 사용자 가입·인증·session·사용자별 설정·SAML·LDAP·OIDC·SCIM — 승인된 FOSS 범위 밖이다.
- 사용자 ACL 검색 필터·permission sync·external group sync — 공개 접근 모델을 사용한다.
- Enterprise 코드·fixture와 multitenancy — 원래 범위에서 제외했다.
- File·Jira·Confluence·GitHub 외 connector — 기존 기능군의 동작 호환만 다룬다.
- Reranker, 서버 내부 LLM·agentic loop·query expansion·검색 session — native 검색 범위에서 제외했다.
- Kotlin BM25/vector 정규화·병합, OpenSearch RRF, 고정 query-role 가중치 — 승인된 native normalization과 충돌한다.
- 새 REST 검색 API·전문 조회용 `fetch` 도구·UI 재설계 — 문서가 승인하지 않았다.
- Kotlin/Go/Python 모델 서버 재선정·자동 index reset — 문서상 선택과 명시적 reset 경계를 유지한다.
- 루트 Craft 문서와 별도 제품 기획 — 이번 입력은 `_kotlin/docs/`의 11개 문서뿐이다.

## Context

### 입력과 요구사항 도출

기준일은 2026-09-09다. SPEC 3개와 DOC 8개를 합성한 결과를 사용했다.
PRD와 ADR는 없고, LOCKED 결정도 없다. 원문에는 REQ ID가 없었다.
이번 요구사항은 SPEC 계약과 승인된 DOC 변경에서 도출했다. PRD 인용이나 승인된 ADR로 가장하지 않는다.

합성 입구는 [intel/SYNTHESIS.md](intel/SYNTHESIS.md)다.
[INGEST-CONFLICTS.md](INGEST-CONFLICTS.md)의 해결 내용을 먼저 적용한다.
ACL 제거·Spring AI 도입·native hybrid는 사용자가 우선한 변경이다. 문서 날짜만으로 우선순위를 정하지 않았다.

### 기존 구현 기록

| 문서 근거 | 과거 기록 | 현재 해석 |
|-----------|-----------|-----------|
| MCP 구현 계획 | 구현·테스트·Web 검증 항목에 완료 표시 | 후속 검색 변경을 적용해 현재 상태를 다시 확인한다. |
| Spring AI 계획 | fixture 단계 완료와 커밋 `4bfb2b936` 기록 | 전체 전환 완료를 뜻하지 않는다. |
| 빌드 walkthrough | 당시 경고 219개 제거와 Gradle 성공 기록 | 현재 경고 수와 테스트 통과를 증명하지 않는다. |
| model-server spike | Kotlin 선택, Granite INT8 OpenVINO·JNA·DJL 구현 기록 | 현재 golden·benchmark 합격을 증명하지 않는다. |
| FOSS·ACL 제거 계획 | 미체크 작업 포함 | 현재 미구현으로 단정하지 않는다. |

### 기술 기준

문서의 후속 기준은 Kotlin 2.3.20·Java 25·Spring Boot 4.0.7과 Jackson 3다.
Spring AI 1.0.0, OpenSearch Java Client 3.10.0, OpenSearch 3.6.0 연결을 기술한다.
이 값은 원문 기준이며 현재 dependency 해석 결과가 아니다. Phase 1에서 실제 환경과 대조한다.

Kotlin model-server는 Granite 311M Multilingual R2 INT8 OpenVINO artifact와 JNA C API·정확한 DJL tokenizer를 기록한다.
오래된 언어 선택 대기 본문으로 새 런타임 선택을 시작하지 않는다. Reranker 후보 다운로드는 활성화 근거가 아니다.

## Constraints

- **Source**: 이번 계획의 요구사항 출처는 `_kotlin/docs/` 11개로 제한한다. 코드베이스 지도는 요구사항을 추가하지 않는다.
- **FOSS 호환**: 후속 검증에서 적용 가능한 Python FOSS 동작·테스트·Web 계약을 비교한다. 승인된 변경과 제외 범위를 먼저 적용한다.
- **구현 범위**: `_kotlin/backend`, 기존 `_kotlin/web`, Kotlin model-server의 문서화된 흐름만 다룬다. 기존 구현을 재사용한다.
- **수집**: 동기식 `Sequence<ConnectorBatch>`를 유지한다. 전체 문서를 메모리에 모으거나 공통 retry framework를 추가하지 않는다.
- **데이터 보존**: 기존 Flyway V1~V15 체크섬을 보존한다. 권한 테이블 정리는 문서의 V16 계약을 따른다.
- **색인**: 부적합 vector mapping은 실패시킨다. 애플리케이션은 index를 삭제하지 않는다.
- **접근**: Web `/mcp` 접근자는 전체 공개 검색 문서를 조회한다. 배포 네트워크·reverse proxy가 접근 범위를 정한다.
- **비밀정보**: Credential은 기존 저장 보호를 유지한다. API·로그·예외에 값, 내부 header, 전체 backend URL을 노출하지 않는다.
- **검증**: 실제 PostgreSQL·Flyway와 OpenSearch를 사용한다. 원격 connector 계약에는 MockWebServer를 사용하며 H2로 대체하지 않는다.
- **전체 흐름**: Backend live 요청은 Web을 통한다. 대표 File 수집에서 API 상태·DB row·OpenSearch 결과를 함께 확인한다.
- **모델 검증**: 원문의 golden 수치와 benchmark 제안 조건을 구분한다. 새 SLA나 무조건적인 INT8 동등성 기준을 만들지 않는다.
- **초기화 범위**: 문서 합성과 계획 작성만 수행했다. 코드 수정·제품 테스트·배포는 수행하지 않았다.

## Key Decisions

아래는 사용자 지시와 문서상 기술 선택이다. ADR 또는 LOCKED 결정 목록이 아니다.

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| 공개 문서 모델과 ACL·permission sync 제거 | 사용자가 후속 제거 변경을 우선했다. | 사용자 승인; 현재 구현 미검증 |
| Spring AI·Java Client·Jackson 3 연결 | 사용자가 후속 전환 변경을 우선했다. | 사용자 승인; 현재 구현 미검증 |
| OpenSearch native hybrid·reranker 제외 | 사용자가 후속 검색 설계를 우선했다. | 사용자 승인; 현재 구현 미검증 |
| MCP WRRF·인접 chunk 축약 유지 | native 설계가 다중 결과 병합을 애플리케이션에 남긴다. | 문서 계약; 현재 구현 미검증 |
| Kotlin model-server 선택 유지 | spike의 구현 결과에 사용자 선택을 기록했다. | 역사적 문서 근거; 현재 수치 미검증 |
| 기존 구현의 차이만 보완 | 새 프로젝트 구축이 아닌 기존 구현 인계다. | Phase 1에서 검증 범위 확인 |

---
*Last updated: 2026-09-09 after scoped document ingestion*
