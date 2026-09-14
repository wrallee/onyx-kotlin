# MCP 비용 재평가 기준

이 문서는 Codex와 Claude Code가 MCP 검색 결과를 처리할 때 사용한 토큰을 비교하는 기준입니다.
현재 변경의 비용을 측정하지 않습니다. 다음 벤치마크에서 이 기준을 사용합니다.

## 측정 원칙

- 각 시나리오는 새 대화형 세션에서 실행합니다.
- 세션 ID, 모델, 추론 강도, 시나리오, 시작 시각, 종료 시각을 기록합니다.
- 한 세션에서는 모델과 추론 강도를 바꾸지 않습니다.
- 평가가 끝난 뒤 저장된 세션 기록에서 누적 사용량을 읽습니다.
- 기존의 긴 세션은 다른 작업의 사용량이 섞이므로 비교 대상에서 제외합니다.
- 하위 에이전트가 있으면 각 세션의 마지막 누적값을 한 번씩 합산합니다.
- `reasoning_output_tokens`는 출력 토큰에 이미 포함되므로 다시 더하지 않습니다.
- 검색 응답 원문의 토큰 추정치는 세션 사용량과 분리한 참고값으로만 기록합니다.
- API 요금표로 환산한 금액은 Codex 또는 Claude 구독의 실제 청구액으로 보지 않습니다.

## Codex

Codex는 세션 기록을 `~/.codex/sessions/YYYY/MM/DD/rollout-*.jsonl`에 저장합니다.
세션 ID가 포함된 파일을 찾아 마지막 누적 사용량을 사용합니다.

| 기록 형식 | 사용할 값 |
| --- | --- |
| 현재 `token_count` 이벤트 | `payload.info.total_token_usage` |
| 이전 `token_usage_record` 이벤트 | `payload.thread_token_usage` |

입력, 캐시 입력, 출력, 추론 출력, 전체 토큰을 각각 기록합니다.

Codex의 `transcript_path`로 세션 파일을 찾을 수 있습니다. 다만 OpenAI는 transcript 형식을 안정된 인터페이스로 보장하지 않습니다. 평가할 때 실제 이벤트 이름과 필드를 먼저 확인합니다. 자세한 내용은 [OpenAI Hooks 문서](https://learn.chatgpt.com/docs/hooks)를 참고합니다.

## Claude Code

Claude Code는 기본적으로 세션 기록을 `~/.claude/projects/<project>/<session-id>.jsonl`에 저장합니다. 다른 저장소를 쓰는 경우 `CLAUDE_CONFIG_DIR`를 기준으로 찾습니다. 자세한 위치는 [Claude Code 세션 문서](https://code.claude.com/docs/en/sessions)를 참고합니다.

현재 검사한 환경에는 비교할 Claude Code 세션 파일이 없습니다. 다음 평가에서는 먼저 저장된 세션 한 개를 확인합니다. 그 파일에 있는 assistant 또는 result 사용량을 합산하되, 같은 API 호출을 중복 집계하지 않습니다. 내부 JSONL 형식은 바뀔 수 있으므로 고정된 필드 경로를 가정하지 않습니다.

Claude Code의 `/usage` 값은 검산에만 사용합니다. 구독 사용자의 표시 금액은 실제 청구액이 아닐 수 있습니다. 자세한 내용은 [Claude Code 비용 문서](https://code.claude.com/docs/en/costs)를 참고합니다.

## 대체 측정

세션 기록에 필요한 사용량이 없을 때만 비대화형 실행 결과를 사용합니다.

- Codex: `codex exec --json`의 `turn.completed.usage`
- Claude Code: `claude -p --output-format json`의 usage와 cost

두 방식은 새 호출을 만들기 때문에 기본 측정 경로로 사용하지 않습니다. 출력 형식은 [Codex 비대화형 실행 문서](https://learn.chatgpt.com/docs/non-interactive-mode)와 [Claude Code 비대화형 실행 문서](https://code.claude.com/docs/en/headless)를 참고합니다.

## 비교 결과 형식

각 시나리오마다 다음 값을 표 한 행에 기록합니다.

- 클라이언트와 버전
- 모델과 추론 강도
- MCP 서버와 검색 조건
- 검색 호출 수와 결과 수
- 입력 토큰과 캐시 입력 토큰
- 출력 토큰과 추론 출력 토큰
- 전체 토큰
- 원문 응답 바이트 수와 참고용 토큰 추정치
- 측정 출처와 세션 ID
