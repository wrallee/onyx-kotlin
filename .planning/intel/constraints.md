# 기술 제약

## FOSS 대상과 제외 범위
- source: _kotlin/docs/specs/2026-09-01-kotlin-backend-foss-parity-design.md
- type: nfr
- content:

~~~~text
DATA_7cpge3uh_START
- Credential, Connector, CC Pair, Document Set, 파일 관리
- File, Jira, Confluence, GitHub 커넥터
- 커넥터 검증, pagination, checkpoint, 부분 실패, rate limit
- 수집 job, attempt, 상태 전이, 재시도, 복구
- Embedding 요청과 OpenSearch 쓰기 및 삭제
- Pruning과 문서 조회 실패의 안전한 처리
- 위 기능에 사용하는 기존 Web 계약

- Kotlin source와 활성 진입점이 없는 커넥터
- 사용자 가입, 인증, session, 사용자별 설정
- Enterprise 기능과 모든 Enterprise source code
- Multitenancy
- External group sync
- SAML, LDAP, OIDC, SCIM 연동
DATA_7cpge3uh_END
~~~~

## Source 경계
- source: _kotlin/docs/specs/2026-09-01-kotlin-backend-foss-parity-design.md
- type: nfr
- content:

~~~~text
DATA_8f3x4oma_START
다음 순서로 동작 기준을 정합니다.

1. 상위 Python FOSS 구현의 실제 동작
2. 상위 Python FOSS 테스트의 기대 결과
3. 기존 Web 요청 및 응답 계약
4. 현재 Kotlin 문서

Kotlin README가 미완성 port를 설명하면 Python 구현을 우선합니다.
인증, Enterprise, multitenancy, external group sync는 계속 제외합니다.

`ee/` 디렉터리와 Enterprise license source는 사용하지 않습니다.
Port한 동작과 복사한 fixture의 출처를 기록합니다.
DATA_8f3x4oma_END
~~~~

## 기능 경계
- source: _kotlin/docs/specs/2026-09-01-kotlin-backend-foss-parity-design.md
- type: nfr
- content:

~~~~text
DATA_7wqlawxi_START
커넥터 종류를 늘리지 않고 기존 커넥터의 완성도를 높입니다.

구현된 커넥터에서는 Python 구현의 누락 동작을 복원합니다.
Python 테스트가 있다는 이유로 다른 커넥터를 추가하지 않습니다.

포함된 커넥터나 관리 흐름에 필요한 공통 동작만 추가합니다.
추후 사용을 위한 호환 framework는 만들지 않습니다.
DATA_7wqlawxi_END
~~~~

## 관리 수명주기
- source: _kotlin/docs/specs/2026-09-01-kotlin-backend-foss-parity-design.md
- type: api-contract
- content:

~~~~text
DATA_5e1holbm_START
관리 계층은 다음 동작을 담당합니다.

- Credential 생성, masking, 수정, 연결 검사, 삭제
- Connector 생성, 수정, 연결, 일시정지, 실행, 삭제
- CC Pair metadata, 상태, 색인 요약, attempt 기록
- Document Set 구성원, 수정, 삭제, 공개 상태
- 파일 upload, 교체, 제거, metadata, connector 수정

기존 Spring controller와 service 경계가 적합하면 그대로 사용합니다.
승인된 동작을 현재 구조에 넣을 수 없을 때만 변경합니다.
DATA_5e1holbm_END
~~~~

## 동기식 Connector Batch
- source: _kotlin/docs/specs/2026-09-01-kotlin-backend-foss-parity-design.md
- type: protocol
- content:

~~~~text
DATA_m16eut86_START
Python 커넥터는 동기식 generator를 사용합니다.
Kotlin에서는 동기식 `Sequence`를 사용합니다.

각 connector batch는 다음 값을 전달합니다.

- 문서
- Connector failure
- 다음 connector checkpoint
- 남은 작업 여부

File 수집은 batch 하나를 반환할 수 있습니다.
원격 커넥터는 API page 또는 checkpoint 단위로 반환합니다.

Coroutine `Flow`나 비동기 connector framework는 추가하지 않습니다.
DATA_m16eut86_END
~~~~

## 수집 흐름
- source: _kotlin/docs/specs/2026-09-01-kotlin-backend-foss-parity-design.md
- type: protocol
- content:

~~~~text
DATA_0ee2ivy1_START
수집 worker는 connector batch를 한 번에 하나씩 처리합니다.

1. PostgreSQL lock으로 실행 가능한 job 하나를 가져옵니다.
2. 해당 attempt를 실행 중으로 변경합니다.
3. Connector batch 하나를 불러옵니다.
4. 문서를 변환하고 chunk로 나눈 뒤 embedding하고 색인합니다.
5. 문서 실패를 식별자와 문맥 정보와 함께 저장합니다.
6. Python과 같은 안전한 시점에 checkpoint를 저장합니다.
7. Connector가 완료를 알릴 때까지 계속합니다.
8. 전체 조회가 끝난 뒤 Python과 같은 pruning을 적용합니다.
9. Attempt와 CC Pair의 최종 상태를 설정합니다.

Worker는 원격 문서 전체를 메모리에 적재하지 않습니다.
DATA_0ee2ivy1_END
~~~~

## 오류 처리
- source: _kotlin/docs/specs/2026-09-01-kotlin-backend-foss-parity-design.md
- type: protocol
- content:

~~~~text
DATA_6f29xb0a_START
모든 커넥터에 적용하는 단일 재시도 정책을 만들지 않습니다.
각 커넥터의 Python 동작을 개별적으로 port합니다.

- 문서 단위 `ConnectorFailure`는 부분 실패로 처리합니다.
- 부분 실패 결과는 `COMPLETED_WITH_ERRORS`로 끝날 수 있습니다.
- 처리하지 못한 치명적 오류는 attempt를 `FAILED`로 끝냅니다.
- 성공한 문서에 해당하는 이전 오류만 해결 상태로 변경합니다.
- 반복 오류 상태는 Python의 연속 실패 규칙을 따릅니다.
- 조회에 실패한 문서 ID는 pruning 중에도 보존합니다.
- Checkpoint는 Python과 같은 안전한 시점에만 전진합니다.
- Python이 문서 실패로 처리하는 embedding과 index 오류도 동일하게 처리합니다.

Rate limit 처리도 커넥터별로 유지합니다.
GitHub, Confluence, Jira는 각 Python 정책을 따라야 합니다.

API 응답, log, 예외에 credential 값을 노출하지 않습니다.
DATA_6f29xb0a_END
~~~~

## 빠른 테스트
- source: _kotlin/docs/specs/2026-09-01-kotlin-backend-foss-parity-design.md
- type: nfr
- content:

~~~~text
DATA_v2k0xurv_START
검증, 변환, 상태 계산에는 JUnit을 사용합니다.
새 utility를 추가하기 전에 기존 helper와 dependency를 사용합니다.
DATA_v2k0xurv_END
~~~~

## PostgreSQL 통합 테스트
- source: _kotlin/docs/specs/2026-09-01-kotlin-backend-foss-parity-design.md
- type: nfr
- content:

~~~~text
DATA_jusxrkb1_START
DB 통합 테스트에는 격리된 PostgreSQL container를 사용합니다.
실제 Flyway migration을 실행합니다.

DB 통합 테스트에 H2를 사용하지 않습니다.
현재 schema는 PostgreSQL JSONB, cast, timestamp, identity 동작을 사용합니다.
Job 선점에는 `FOR UPDATE SKIP LOCKED` 동작도 필요합니다.

CRUD, foreign key, unique 제약, transaction, pagination, 동시 선점을 검증합니다.
DATA_jusxrkb1_END
~~~~

## 전체 수집 테스트
- source: _kotlin/docs/specs/2026-09-01-kotlin-backend-foss-parity-design.md
- type: nfr
- content:

~~~~text
DATA_05q05ecy_START
대표 File 전체 수집 흐름에는 Docker Compose를 사용합니다.
이 흐름은 PostgreSQL, model server, OpenSearch를 포함합니다.

Backend 요청은 Web service를 통해 전송합니다.
최종 API 상태, DB row, OpenSearch 문서를 확인합니다.

작은 테스트로 증명할 수 없는 동작에만 전체 수집 테스트를 추가합니다.
DATA_05q05ecy_END
~~~~

## Connector 계약 테스트
- source: _kotlin/docs/specs/2026-09-01-kotlin-backend-foss-parity-design.md
- type: nfr
- content:

~~~~text
DATA_vo0pk20n_START
원격 connector 테스트에는 MockWebServer를 사용합니다.
네 커넥터에 적용되는 Python 시나리오를 모두 port합니다.

Pagination, 인증 header, 검증, checkpoint, 실패, rate limit을 검사합니다.

일반 테스트에는 실제 Jira, Confluence, GitHub 계정이 필요하지 않습니다.
실제 credential을 사용하는 smoke test는 선택 테스트로 분리합니다.
DATA_vo0pk20n_END
~~~~

## FOSS 완료 기준의 유지 항목
- source: _kotlin/docs/specs/2026-09-01-kotlin-backend-foss-parity-design.md
- type: nfr
- content:

~~~~text
DATA_yiglmex5_START
- 적용 가능한 각 Python FOSS 시나리오에 Kotlin 테스트 또는 동등한 검증이 있습니다.
- 테스트가 구현 변경 전에 누락 동작을 재현합니다.
- 빠른 테스트와 connector 계약 테스트가 모두 통과합니다.
- PostgreSQL 통합 테스트가 실제 PostgreSQL에서 통과합니다.
- 대표 Docker File 수집 테스트가 Web service를 통해 통과합니다.
- Checkpoint, 부분 실패, 복구, pruning이 Python 동작과 일치합니다.
- 새 connector 종류를 추가하지 않습니다.
- Enterprise code와 fixture를 복사하거나 번역하지 않습니다.
- 기존 Web 관리 흐름이 계속 동작합니다.
DATA_yiglmex5_END
~~~~

## 검색 입력과 Document Set 확인
- source: _kotlin/docs/specs/2026-09-02-kotlin-search-mcp-design.md
- type: api-contract
- content:

~~~~text
DATA_xg7jrwkb_START
1. `SearchService`는 공백이 아닌 query와 `1..20` 범위의 limit을 받습니다.
2. `document_sets`가 있으면 DB에서 이름을 확인합니다. 없는 이름은 tool
   execution error로 반환합니다.
DATA_xg7jrwkb_END
~~~~

## 검색 서비스와 MCP 경계
- source: _kotlin/docs/specs/2026-09-02-kotlin-search-mcp-design.md
- type: protocol
- content:

~~~~text
DATA_igx1nahn_START
MCP 계층은 protocol 변환만 담당합니다. 검색 정책은 `SearchService`에 둡니다.
MCP가 backend의 REST endpoint를 다시 호출하지 않습니다.
DATA_igx1nahn_END
~~~~

## OpenSearch vector mapping과 명시적 index reset
- source: _kotlin/docs/specs/2026-09-02-kotlin-search-mcp-design.md
- type: schema
- content:

~~~~text
DATA_upoosja1_START
`embedding` 필드를 다음 값으로 명시합니다.

- type: `knn_vector`
- dimension: `768`
- engine: `lucene`
- space type: `cosinesimil`
- method: `hnsw`

인덱스 설정에서 `index.knn=true`를 사용합니다. Embedding 차원은
`ONYX_EMBEDDING_DIMENSION`으로 설정하며 기본값은 현재 Granite 모델의 768입니다.

기존 인덱스에서 `embedding`이 동적 숫자 배열로 생성됐으면 mapping을 직접
변경할 수 없습니다. 이 시스템은 아직 구축 단계이므로 기존 embedding을
migration하지 않습니다. 사용자가 배포 전에 해당 Onyx index를 명시적으로
삭제하고 모든 connector를 다시 실행해 새 mapping으로 적재합니다.

애플리케이션은 기존 `embedding` mapping이 `knn_vector`가 아니면 검색과 쓰기를
실패시킵니다. 애플리케이션과 이번 구현 작업은 index를 삭제하지 않습니다.
DATA_upoosja1_END
~~~~

## Web MCP 전달과 search 요청 계약
- source: _kotlin/docs/specs/2026-09-02-kotlin-search-mcp-design.md
- type: api-contract
- content:

~~~~text
DATA_u0dsoa66_START
서버는 backend `/mcp`에 stateless Streamable HTTP endpoint를 제공합니다.
Web은 `/mcp`를 기존 `/api/mcp` catch-all proxy로 rewrite합니다. 요청의 MCP
headers와 streaming response를 그대로 전달합니다.

MCP client는 다음 원격 URL만 등록하면 됩니다.

```text
https://onyx-admin.com/mcp
```

`onyx-admin.com`은 Onyx Web frontend입니다. Web `/mcp`가 요청을 내부 backend
`/mcp`로 전달합니다. Backend port와 주소는 공개하지 않습니다. 첫 버전은 인증
header 또는 credential을 요구하지 않습니다.

따라서 `https://onyx-admin.com/mcp`에 접근할 수 있는 client는 모든 검색 대상
문서를 조회할 수 있습니다. 인증이 추가되기 전까지 reverse proxy 또는 배포
네트워크가 접근 범위를 제한해야 합니다. MCP 구현은 client가 보낸 사용자
정보를 신뢰하지 않습니다.

```json
{
  "query": "검색어",
  "document_sets": ["Engineering", "Operations"],
  "limit": 10
}
```

`limit` 기본값은 10이며 최대값은 20입니다. 결과는 text content와
`structuredContent`에 같은 검색 결과를 제공합니다. `document_sets`를 생략하거나
빈 배열로 보내면 모든 Document Set을 검색합니다.
DATA_u0dsoa66_END
~~~~

## MCP 검색 호출 지침의 한계
- source: _kotlin/docs/specs/2026-09-02-kotlin-search-mcp-design.md
- type: protocol
- content:

~~~~text
DATA_tk1lo82l_START
도구 설명과 server instructions에 다음 지침을 넣습니다.

- 기본적으로 검색을 한 번만 수행합니다.
- 첫 결과가 부족하거나 질문이 모호할 때만 query를 바꿔 추가 검색합니다.
- 한 요청을 처리할 때 총 검색 호출을 세 번 이하로 제한합니다.
- 같은 의미의 query를 반복하지 않습니다.
- 충분한 근거를 찾으면 추가 검색을 중단합니다.
- 필요한 최소 limit을 사용합니다.
- 결과의 링크와 본문을 근거로 답합니다.

MCP client와 model이 도구 호출을 제어하므로 이 동작은 보장하지 않습니다.
반복 검색을 반드시 보장해야 하면 이후 `SearchService` 내부에 deterministic
multi-query 정책을 추가합니다. MCP sampling은 사용하지 않습니다.
호출 횟수 제한은 server instruction이므로 client 동작을 강제하지 않습니다.
인증과 client 식별이 추가되기 전에는 server-side 호출 횟수 상태를 저장하지
않습니다.
DATA_tk1lo82l_END
~~~~

## 검색 오류와 비밀정보 보호
- source: _kotlin/docs/specs/2026-09-02-kotlin-search-mcp-design.md
- type: api-contract
- content:

~~~~text
DATA_qyuai6fm_START
- 잘못된 query와 limit은 MCP tool execution error로 반환합니다.
- 존재하지 않는 Document Set 이름은 이름을 포함한 MCP tool execution error로
  반환합니다.
- embedding 또는 OpenSearch 실패는 검색 실패로 반환합니다.
- protocol 오류와 검색 실행 오류를 구분합니다.
- 오류 응답에 credential, 내부 header 또는 전체 backend URL을 넣지 않습니다.
DATA_qyuai6fm_END
~~~~

## 검색 범위의 유지 제외 항목
- source: _kotlin/docs/specs/2026-09-02-kotlin-search-mcp-design.md
- type: nfr
- content:

~~~~text
DATA_a712r81g_START
- 사용자 인증과 문서 ACL 필터
- 서버 내부 LLM 또는 agentic loop
- 검색 세션 상태
- 별도 REST 검색 엔드포인트
- 전문 조회용 `fetch` MCP 도구
DATA_a712r81g_END
~~~~

## Search routing
- source: _kotlin/docs/specs/2026-09-07-opensearch-native-hybrid-retrieval-design.md
- type: api-contract
- content:

~~~~text
DATA_ixvfgtgz_START
- `KEYWORD`: BM25 only. Do not call the embedding model.
- `SEMANTIC`: embed once, then k-NN only.
- `HYBRID`: embed once, then one OpenSearch `hybrid` query with BM25 and k-NN clauses.

Hybrid search uses:

- `pagination_depth = 200` by default.
- vector `k = 200` by default.
- request `size = MCP limit`.
- common document-set, source-type, and time filters through `hybrid.filter`.
- explicit request-level search pipeline selection.
DATA_ixvfgtgz_END
~~~~

## Normalization pipelines
- source: _kotlin/docs/specs/2026-09-07-opensearch-native-hybrid-retrieval-design.md
- type: protocol
- content:

~~~~text
DATA_0i9djenp_START
Create two OpenSearch search pipelines as one hybrid-readiness operation:

- `<index>-hybrid-min-max`
- `<index>-hybrid-z-score`

`min_max` is the default and uses the typed OpenSearch Java Client search-pipeline API. `z_score` is optional and uses only `OpenSearchClient.generic()` because `opensearch-java:3.10.0` does not expose `z_score` in `ScoreNormalizationTechnique`.

Both pipelines use `arithmetic_mean` and configured positional weights `[keyword, vector]`. Default weights are `0.5 / 0.5`.

Pipeline failures must fail hybrid retrieval. Keyword and semantic retrieval do not depend on pipeline readiness.
DATA_0i9djenp_END
~~~~

## Configuration
- source: _kotlin/docs/specs/2026-09-07-opensearch-native-hybrid-retrieval-design.md
- type: schema
- content:

~~~~text
DATA_qv7vusgd_START
Use `onyx.search.*`:

- `hybrid-candidates`: default `200`, range `1..10000`.
- `hybrid-normalization`: `min_max` or `z_score`, default `min_max`.
- `keyword-weight`: default `0.5`.
- `vector-weight`: default `0.5`.
- `rrf-k`: default `50`.

Weights must be non-negative and sum to `1.0`.

Remove retrieval policy from `onyx.model-server.search-candidates`. Preserve `ONYX_SEARCH_CANDIDATES` as the environment variable.
DATA_qv7vusgd_END
~~~~

## MCP WRRF
- source: _kotlin/docs/specs/2026-09-07-opensearch-native-hybrid-retrieval-design.md
- type: protocol
- content:

~~~~text
DATA_zzu87rty_START
Keep application-level weighted reciprocal-rank fusion for multiple ranked query results:

`score(d) = sum(weight_i / (k + rank_i(d)))`

Defaults:

- list weights are all `1.0` when omitted.
- `k` comes from `onyx.search.rrf-k`.

Use WRRF for same-intent rewrites, synonyms, or alternate retrieval strategies. Do not blindly fuse independent decomposed subquestions.
DATA_zzu87rty_END
~~~~

## Adjacent chunk diversity
- source: _kotlin/docs/specs/2026-09-07-opensearch-native-hybrid-retrieval-design.md
- type: protocol
- content:

~~~~text
DATA_18y07xn3_START
After MCP-level WRRF, group results by document and collapse each maximal consecutive chunk run to its highest-ranked member. Keep non-adjacent chunks independently. Keep results without usable document/chunk identity independently.

Do not concatenate content. Use `get_document_context` for surrounding chunks.
DATA_18y07xn3_END
~~~~

## Non-goals
- source: _kotlin/docs/specs/2026-09-07-opensearch-native-hybrid-retrieval-design.md
- type: nfr
- content:

~~~~text
DATA_s0zs830l_START
- No reranker.
- No server-side LLM query expansion.
- No OpenSearch RRF for BM25/vector fusion.
- No fixed `1.3 / 1.0 / 0.7 / 0.5` query-role weights.
- No Kotlin implementation of min-max or z-score math.
- No unrelated connector or ingestion refactor.
DATA_s0zs830l_END
~~~~
