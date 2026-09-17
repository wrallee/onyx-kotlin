# CodeGraph 다중 저장소 게이트웨이 준비안

- 상태: 준비 문서, 구현 아님
- 작성일: 2026-09-17
- 대상 규모: 메인 브랜치를 추적하는 저장소 약 10개

## 목적

현재 저장소 구현을 현행 답변의 가장 강한 근거로 제공한다.
GitHub 커넥터로 소스 코드를 수집하지 않는다.
대신 각 저장소의 메인 브랜치를 CodeGraph로 색인한다.

Onyx는 문서 근거와 코드 근거를 따로 조회한다.
답변 모델은 둘이 다르면 현재 구현과 문서상 설명을 구분해 표시한다.

## 권장 구성

```text
메인 브랜치 전용 체크아웃 약 10개
        │
        ▼
Node.js CodeGraph 게이트웨이
  - 저장소 목록과 허용 경로
  - 공개 Node API 기반 색인과 조회
  - 동기화 상태와 오류
        │ 내부 HTTP
        ▼
Kotlin Onyx MCP
        │
        ▼
답변 모델
```

게이트웨이는 먼저 내부 HTTP 서비스로 제공한다.
Onyx가 이미 외부 MCP 경계를 제공하므로 두 번째 외부 MCP는 만들지 않는다.
독립 클라이언트의 직접 접근 요구가 생기면 같은 서비스 메서드에 MCP 전송 계층을 추가한다.
CodeGraph 기본 MCP는 표준 입출력 방식이다. 그 자체를 네트워크 서비스로 보지 않는다.

## CodeGraph 연결 방식

### 1차 선택: 공개 Node API

CodeGraph는 `@colbymchenry/codegraph` 패키지에 공개 TypeScript API를 제공한다.
게이트웨이는 다음 API만 사용한다.

- `CodeGraph.init(path)`: 새 색인 생성
- `CodeGraph.open(path)`: 기존 색인 열기
- `indexAll()`: 최초 전체 색인
- `sync()`: 메인 브랜치 변경분 반영
- `searchNodes()`: 저장소와 심볼 후보 찾기
- `buildContext()`: 답변 모델에 전달할 코드 문맥 생성
- `close()`: 자원 해제

이 방식은 프로세스 실행과 CLI 출력 파싱을 없앤다.
게이트웨이는 색인 객체의 수명과 저장소별 잠금을 직접 관리한다.

도입 전에는 다음 조건을 확인한다.

- 운영 Node.js가 API 요구 버전인 22.5 이상이다.
- 게이트웨이와 CodeGraph 패키지 버전을 함께 고정할 수 있다.
- 10개 색인을 연 상태의 메모리와 파일 설명자 사용량이 허용 범위다.
- `buildContext`의 JSON 또는 Markdown 결과가 필요한 근거를 보존한다.

### 대체안: CodeGraph CLI

공개 API의 런타임 조건이나 안정성이 맞지 않으면 CLI를 사용한다.
CLI 번들은 자체 런타임을 포함하므로 배포 환경의 Node 버전 영향을 줄인다.

필요한 명령은 다음 범위로 제한한다.

- `codegraph init <repo>`
- `codegraph sync <repo>`
- `codegraph status --json`
- `codegraph query --json`
- `codegraph context --format json`
- `codegraph explore --path <repo>`

Node.js는 `child_process.spawn`에 인자 배열을 전달한다.
셸 문자열을 만들지 않는다.
요청이 임의 경로와 임의 명령을 전달하지 못하게 한다.

공개 API와 CLI를 동시에 구현하지 않는다.
간단한 운영 시험으로 하나를 선택한다.

## 최소 HTTP 계약

초기 서비스는 세 엔드포인트면 충분하다.

### `GET /v1/repositories`

허용된 저장소와 색인 상태를 반환한다.

```json
{
  "repositories": [
    {
      "id": "orders",
      "branch": "main",
      "checkoutCommit": "abc123",
      "indexedCommit": "abc123",
      "lastSyncedAt": "2026-09-17T10:00:00Z",
      "state": "ready"
    }
  ]
}
```

### `POST /v1/code/context`

저장소 ID와 자연어 질문을 받아 관련 코드 문맥을 반환한다.

```json
{
  "repository": "orders",
  "query": "Which event types are currently supported?"
}
```

응답에는 최소한 저장소 ID, 색인 커밋, 파일 경로, 줄 범위, 코드 문맥을 넣는다.
저장소를 모르면 오류로 끝낸다.
임의 파일 시스템 경로는 받지 않는다.

### `POST /internal/repositories/{repository}/sync`

체크아웃이 새 메인 커밋으로 이동한 뒤 해당 색인을 동기화한다.
운영 스케줄러나 배포 파이프라인만 호출한다.

## 저장소 동기화

각 저장소는 읽기 전용 운영 계정의 전용 체크아웃을 사용한다.
동기화 작업은 다음 순서를 지킨다.

1. 원격 메인 브랜치를 가져온다.
2. 전용 체크아웃을 확인된 커밋으로 이동한다.
3. 새 저장소면 색인을 초기화한다.
4. 기존 저장소면 `sync()` 또는 `codegraph sync`를 실행한다.
5. 성공한 색인 커밋과 시각을 상태 저장소에 기록한다.

저장소별로 동기화 잠금을 하나만 둔다.
실패하면 마지막 성공 색인을 계속 조회할 수 있게 한다.
응답 상태는 `degraded`와 실제 색인 커밋을 표시한다.
실패한 동기화를 성공으로 기록하지 않는다.

CodeGraph MCP는 한 세션에서 여러 `projectPath`를 조회할 수 있다.
그러나 여러 하위 색인이 있는 루트에는 기본 프로젝트가 없다.
교차 프로젝트 조회에는 실시간 감시자가 붙지 않을 수 있다.
따라서 이 구성은 감시자에 의존하지 않고 메인 갱신 뒤 명시적으로 동기화한다.

공개 Node API의 `watch()`도 첫 구현에서는 사용하지 않는다.
메인 커밋과 색인 커밋을 정확히 맞추는 명시적 동기화가 더 단순하다.

## 다중 저장소 조회

CodeGraph는 저장소별 색인을 하나의 그래프로 합치지 않는다.
모든 조회는 하나 이상의 허용된 저장소 ID를 명시한다.

저장소를 알고 있으면 해당 색인만 조회한다.
저장소를 모르면 다음 최소 절차를 사용한다.

1. 허용된 저장소에서 가벼운 심볼 검색을 수행한다.
2. 일치한 저장소만 고른다.
3. 선택한 저장소에서 깊은 문맥을 만든다.

초기 구현에서 별도 전역 코드 색인을 만들지 않는다.

## Onyx 답변 흐름

현행 질문은 두 근거 경로를 사용할 수 있다.

1. Onyx에서 위키와 Jira의 관련 근거를 찾는다.
2. 게이트웨이에서 메인 브랜치의 코드 근거를 찾는다.
3. 코드가 실제 동작을 명확히 정의하면 현재 구현으로 답한다.
4. 문서가 다른 정책이나 의도를 말하면 충돌을 함께 표시한다.
5. 코드로 확인할 수 없는 업무 규칙은 문서 근거로 답한다.

예를 들어 위키의 Enum이 다섯 종류이고 메인 브랜치 Enum이 세 종류라면
현재 구현은 세 종류라고 답한다.
위키가 오래된 다섯 종류를 기록한다는 사실도 함께 알린다.

## 보안 경계

- 전용 운영 계정은 허용된 10개 저장소만 읽을 수 있다.
- 외부 요청은 저장소 ID만 보낸다.
- 서비스가 ID를 고정 경로로 변환한다.
- 임의 `projectPath`, 셸 명령, Git URL을 받지 않는다.
- 내부 HTTP 인증과 호출 기록을 둔다.
- 응답에는 비밀 파일과 제외 경로가 들어가지 않게 한다.

이 경계는 CodeGraph의 교차 프로젝트 경로 기능을 그대로 외부에 노출하지 않게 한다.

## 운영 확인 항목

- 색인 커밋과 체크아웃 커밋이 같은가
- 동기화 지연 시간이 허용 범위인가
- 실패 뒤 마지막 성공 색인을 읽을 수 있는가
- 한 저장소의 긴 동기화가 다른 저장소 조회를 막지 않는가
- 10개 색인의 메모리와 디스크 사용량이 허용 범위인가
- 결과에 파일 경로와 줄 범위가 항상 포함되는가

## 보류 항목

- 외부 MCP 서버 제공
- 저장소 간 통합 그래프
- 모든 저장소의 상시 파일 감시
- 코드와 문서의 자동 진실 판정
- 답변 생성 모델이 없는 독립 요약 계층

실제 독립 클라이언트 요구가 생기면 공식 MCP TypeScript SDK나 FastMCP를 검토한다.
같은 조회 메서드를 재사용하고 HTTP와 MCP 구현을 분리하지 않는다.

## 참고 자료

- [CodeGraph TypeScript API](https://github.com/colbymchenry/codegraph/blob/main/site/src/content/docs/reference/api.md)
- [CodeGraph MCP server](https://github.com/colbymchenry/codegraph/blob/main/site/src/content/docs/reference/mcp-server.md)
- [CodeGraph repository](https://github.com/colbymchenry/codegraph)
- [MCP TypeScript SDK server guide](https://github.com/modelcontextprotocol/typescript-sdk/blob/main/docs/server.md)
- [FastMCP](https://github.com/punkpeye/fastmcp)
