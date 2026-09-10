---
phase: "01"
slug: "baseline-verification"
status: draft
nyquist_compliant: false
wave_0_complete: false
created: "2026-09-10"
---

# Phase 1 — Validation Strategy

> 현재 Kotlin 구현과 실행 근거를 분리해서 기록한다.

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit Platform, Gradle 9.5.1 |
| **Config file** | `_kotlin/backend/build.gradle.kts` |
| **Quick run command** | `test -s .planning/phases/01-baseline-verification/01-BASELINE.md` |
| **Full suite command** | `cd _kotlin/backend && ./gradlew test opensearchIntegrationTest --no-daemon` |
| **Estimated runtime** | 약 5분 |

## Sampling Rate

- **After every task commit:** Run the task's evidence command.
- **After every plan wave:** Run `test -s .planning/phases/01-baseline-verification/01-BASELINE.md`.
- **Before `$gsd-verify-work`:** Record both Gradle task results in `01-BASELINE.md`.
- **Max feedback latency:** 5분

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 01-01-01 | 01 | 1 | BASE-01 | — | 비밀 값을 근거 문서에 기록하지 않는다. | documentation audit | `test -s .planning/phases/01-baseline-verification/01-BASELINE.md` | ❌ W0 | ⬜ pending |
| 01-01-02 | 01 | 1 | BASE-02 | — | 과거 완료 기록을 현재 통과로 표시하지 않는다. | existing suites | `cd _kotlin/backend && ./gradlew test opensearchIntegrationTest --no-daemon` | ✅ | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

## Wave 0 Requirements

- [ ] `.planning/phases/01-baseline-verification/01-BASELINE.md` — canonical evidence ledger.
- [ ] No new test framework or fixture is required.

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| 평가 MCP 서버와 현재 Kotlin artifact의 동일성 | BASE-02 | 외부 배포 artifact와 commit 정보가 없다. | `NOT_RUN`으로 기록하고 Phase 5 live 검증으로 보낸다. |
| Docker image build/start | BASE-02 | Phase 1은 제품 수정 단계가 아니다. | 현재 정적 불일치를 `GAP`으로 기록하고 Phase 3으로 보낸다. |

## Validation Sign-Off

- [ ] All tasks have `<automated>` verification or Wave 0 dependencies.
- [ ] Sampling continuity has no three consecutive tasks without automated verification.
- [ ] Wave 0 covers all missing references.
- [ ] Commands use no watch-mode flags.
- [ ] Feedback latency is less than 5 minutes.
- [ ] `nyquist_compliant: true` is set in frontmatter.

**Approval:** pending
