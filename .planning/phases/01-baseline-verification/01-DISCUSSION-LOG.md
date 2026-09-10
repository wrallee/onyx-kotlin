# Phase 1: 현재 구현과 검증 범위 확인 - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md. This log records direct user instructions.

**Date:** 2026-09-10
**Phase:** 1-현재 구현과 검증 범위 확인
**Areas discussed:** 변경 대상, MCP 평가 항목 처리, 즉시 해결할 기존 문제

---

## 변경 대상

**User's choice:** `_kotlin/backend`와 필요한 `_kotlin/web`만 수정한다.

**Notes:** 루트 Python backend와 루트 `web`은 기존 동작 참고용이다. 두 경로는 수정하지 않는다.

---

## MCP 평가 항목 처리

**User's choice:** chunk 독식과 limit 응답 과대는 P0 Watchlist로 보류한다. 나머지 평가 항목은 해결 대상으로 본다.

**Notes:** 추가 질문이 필요하지 않았다. 사용자 지시가 범위와 우선순위를 직접 정했다.

---

## 즉시 해결할 기존 문제

**User's choice:** Dockerfile의 Gradle·Java 불일치와 file upload rollback 잔존을 이번 활성 범위에 포함한다.

**Notes:** Dockerfile은 `_kotlin/backend` 안에서 수정한다. 루트 CI는 변경하지 않는다.

---

## the agent's Discretion

- 기존 호출자 근거에 따른 MCP 이름과 파라미터 정리 방식
- 각 항목에 필요한 최소 Kotlin 테스트

## Deferred Ideas

- `WL-20260910-001`: 동일 문서 chunk 독식
- `WL-20260910-002`: limit 증가에 따른 응답 토큰 과대
