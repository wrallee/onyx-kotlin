# Coding Conventions

**Analysis Date:** 2026-09-09

## Naming Patterns

**Files:**
- Python uses descriptive snake_case modules. Put data models in `models.py`, routes under `backend/onyx/server/`, and database access under `backend/onyx/db/`. Follow `CONTRIBUTING.md` and `backend/AGENTS.md`.
- Kotlin uses PascalCase files in domain packages, such as `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/service/SearchService.kt`.
- Kotlin also groups related declarations in `api/ApiModels.kt`, `domain/Domain.kt`, and `domain/Repositories.kt` under `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/`.
- React components use PascalCase; hooks use `use` plus camelCase. Examples: `web/src/providers/UserProvider.test.tsx` and `web/src/hooks/useVisibilityGatedInterval.test.tsx`.

**Functions:**
- Use descriptive snake_case Python names and type annotations. See `backend/tests/unit/onyx/tools/tool_implementations/open_url/test_onyx_web_crawler_playwright_fallback.py`.
- Use camelCase Kotlin functions. Backtick sentence names are common in tests; camelCase test names also occur. See `_kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/service/SearchServiceTest.kt`.
- Use `useX` for React hooks. Place feature hooks in `web/src/lib/<feature>/hooks.ts`, as required by `web/AGENTS.md`.

**Variables:**
- Prefer explicit names. Keep the same domain term through the call chain. Avoid single-letter names outside small utilities. Source: `CONTRIBUTING.md`.
- Prefer Kotlin `val` and explicit nullable types. Use named arguments when they clarify configuration. Source: `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/service/SearchService.kt`.

**Types:**
- Prefer Pydantic models to Python dataclasses. Use explicit `None` and string enums. Source: `CONTRIBUTING.md`.
- Kotlin uses typed service inputs, enums, data classes, and Spring repositories. See `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/domain/Domain.kt` and `service/SearchService.kt`.
- Keep TypeScript strictly typed. Use type-only imports where applicable, as in `web/src/hooks/useSearchSettings.test.ts`.

## Code Style

**Formatting:**
- Python: Ruff formatter, line length 88, configured in `pyproject.toml`.
- Web: oxfmt 0.59.0; `format` and `format:check` target `src`. Sources: `web/package.json`, `_kotlin/web/package.json`.
- Mobile: Prettier with Tailwind class sorting. Sources: `mobile/.prettierrc.json`, `mobile/package.json`.
- Kotlin samples use four spaces and trailing commas in multiline lists. No formatter or lint plugin is declared in `_kotlin/backend/build.gradle.kts`.

**Linting:**
- Python enables Ruff ARG, B, C901, E, F, G004, I, PERF, S, and W rules. Complexity limit is 20, with file exemptions. Source: `pyproject.toml`.
- Python type checking uses `ty check`; respect scoped exceptions in `pyproject.toml`.
- Ruff versions differ: `pyproject.toml` declares 0.16.1; `.pre-commit-config.yaml` hooks request 0.16.0. Do not assume identical results.
- Web uses oxlint and a separate TypeScript check. Sources: `web/.oxlintrc.json`, `web/package.json`.
- Mobile uses Expo ESLint and Prettier rather than web tooling. Sources: `mobile/eslint.config.js`, `mobile/package.json`.
- Kotlin enables strict JSR-305 handling and JVM 25 compilation. Source: `_kotlin/backend/build.gradle.kts`.

## Import Organization

**Order:**
1. Python: standard library, third-party packages, then first-party modules. Ruff manages sorting in `pyproject.toml`.
2. Python first-party groups include `onyx`, `ee`, `tests`, `shared_configs`, and `model_server`. Source: `pyproject.toml`.
3. Kotlin and TypeScript samples group related imports, but no universal ordering rule is established by the inspected configs. Follow adjacent code, such as `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/service/SearchService.kt`.

**Path Aliases:**
- Web Jest maps `@/` to `src/`, `@tests/` to `tests/`, and `@opal/` to `lib/opal/src/`. Source: `web/jest.config.js`.
- Mobile maps `@/` to `src/`. Source: `mobile/jest.config.js`.
- Kotlin packages start with `com.onyx.foss.kotlin`. Source: `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/OnyxKotlinApplication.kt`.

## Error Handling

**Patterns:**
- Python APIs must raise `OnyxError` with `OnyxErrorCode`. The shared handler returns `error_code` and `detail`. Source: `backend/AGENTS.md`.
- Keep catches at meaningful boundaries. Do not hide failed work. Source: `CONTRIBUTING.md`.
- Kotlin uses `require` for service validation. See `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/service/SearchService.kt`.
- Kotlin controller advice maps `ApiException`, invalid arguments, and integrity conflicts to HTTP responses with `detail`. Do not assume the Python error envelope applies. Source: `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/api/ApiExceptionHandler.kt`.
- Mobile HTTP calls use `apiFetch<T>` and normalized `ApiError`. Paths omit the existing `/api` prefix. Source: `mobile/AGENTS.md`.

## Logging

**Framework:** Python uses `setup_logger()` from `onyx.utils.logger`, as shown in `backend/onyx/db/document.py`.

**Patterns:**
- Log at the point where the message is created. Do not carry log messages between layers. Source: `CONTRIBUTING.md`.
- Python LLM calls must open a generation span with a registered `LLMFlow`. Source: `backend/AGENTS.md`.
- Use existing service log output for live verification; paths are documented in root `AGENTS.md`. No runtime logs were inspected for this map.

## Comments

**When to Comment:**
- Explain interfaces, assumptions, complicated flows, and non-obvious behavior. Keep comments short. Source: `CONTRIBUTING.md`.
- TODO comments require an owner or issue reference. Avoid commented-out code. Source: `CONTRIBUTING.md`.
- Keep technical prose short and active. Source: `AGENTS.md`.

**JSDoc/TSDoc:**
- Use focused explanatory blocks for behavior and contracts. Example: visibility polling semantics in `web/src/hooks/useVisibilityGatedInterval.test.tsx`.
- Python test modules use docstrings for the behavior under test. Example: `backend/tests/unit/onyx/tools/tool_implementations/open_url/test_onyx_web_crawler_playwright_fallback.py`.

## Function Design

**Size:** Avoid long chains of steps. Extract meaningful helpers, even when only used once. Source: `CONTRIBUTING.md`.

**Parameters:**
- Avoid passing both a value and a state object that already contains it. Source: `CONTRIBUTING.md`.
- Kotlin uses constructor injection and explicit default arguments. Example: `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/service/SearchService.kt`.

**Return Values:**
- Type Python endpoint return values directly; do not add FastAPI `response_model`. Source: `backend/AGENTS.md`.
- Prefer typed Kotlin response objects over loose maps in service code. HTTP error maps are centralized in `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/api/ApiExceptionHandler.kt`.

## Module Design

**Exports:**
- Keep Python database operations within `backend/onyx/db/` or `backend/ee/onyx/db/`. Source: `backend/AGENTS.md`.
- Prefer composition and explicit state; avoid meaningful import-time work. Source: `CONTRIBUTING.md`.
- Web UI should reuse Opal before other components. New entity cards belong in `web/src/sections/cards/`; settings pages use shared layouts. Source: `web/AGENTS.md`.
- Mobile has separate native components and TanStack Query conventions. Do not apply web DOM, SWR, or spacing rules to mobile. Source: `mobile/AGENTS.md`.
- Mobile query keys include `serverUrl`; sensitive data must be excluded from persisted query storage. Source: `mobile/AGENTS.md`.

**Barrel Files:**
- Opal exposes public component, layout, and core barrels. Jest config lazily initializes these modules to support circular imports. Source: `web/jest.config.js`.
- Prefer the documented public Opal imports in `web/AGENTS.md`.

---

*Convention analysis: 2026-09-09*
