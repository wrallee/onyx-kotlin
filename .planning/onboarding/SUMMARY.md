# Onboarding Summary

기준일: 2026-09-09. 프로젝트: Onyx Kotlin.

## Project State

- [PROJECT.md](../PROJECT.md): 작성 완료.
- [REQUIREMENTS.md](../REQUIREMENTS.md): 요구사항 35개.
- [ROADMAP.md](../ROADMAP.md): 5개 단계. 요구사항 누락·중복 없음.
- [STATE.md](../STATE.md): Phase 1 계획 준비.

## Codebase Context

- 기존 코드가 있는 저장소다.
- Map readiness: complete.
- [코드베이스 맵](../codebase/): 필수 문서 7개 작성 완료.
- Fast map에 필요한 문서도 포함한다.
- 맵은 루트 Onyx와 `_kotlin/`을 다룬다. 계획의 요구사항 출처는 아래 문서 범위로 한정한다.

## Docs Context

- 자동 탐지한 Craft 후보 2개는 사용자 지정 범위에서 제외했다.
- 실제 반영: `_kotlin/docs/` 11개 문서. SPEC 3개, DOC 8개.
- [입력 목록](kotlin-docs-manifest.yaml)에 범위와 승인된 우선순위를 기록했다.
- ACL 제거·공개 접근, Spring AI 도입, native hybrid·reranker 제외를 우선 반영했다.
- [종합 결과](../intel/SYNTHESIS.md)와 [충돌 보고서](../INGEST-CONFLICTS.md)에 근거를 보존했다.
- 미해결 충돌 0개. 자동 해결 7개, 추가 설명 4개.
- 과거 완료 기록은 현재 검증 결과와 구분한다. 이번 온보딩은 제품 테스트를 실행하지 않았다.

## Recommended Next Step

- `$gsd-manager`
- 현재 단계: Phase 1 — 현재 구현과 검증 범위 확인.
- 온보딩은 완료됐다. 구현 단계 실행과 배포는 수행하지 않았다.
