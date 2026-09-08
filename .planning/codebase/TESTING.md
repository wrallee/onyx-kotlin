# Testing Patterns

**Analysis Date:** 2026-09-09

This map records source and configuration evidence. No tests, builds, services, or installations were run.

## Test Framework

**Runner:**
- Python: pytest 9.0.3 with pytest-asyncio, xdist, repeat, mock, and Alembic plugins. Sources: `pyproject.toml`, `backend/pytest.ini`.
- Kotlin: JUnit Platform through Spring Boot test dependencies. The JUnit version is dependency-managed, not directly pinned. Source: `_kotlin/backend/build.gradle.kts`.
- Web: Jest ^30.4.2 and SWC, with separate Node and jsdom projects. Sources: `web/package.json`, `web/jest.config.js`.
- Kotlin web copy: separate Jest and Playwright configuration. Sources: `_kotlin/web/package.json`, `_kotlin/web/jest.config.js`, `_kotlin/web/playwright.config.ts`.
- Mobile: jest-expo ~57.0.4. Sources: `mobile/package.json`, `mobile/jest.config.js`.
- Go: standard `go test` runner across modules. Source: `.github/workflows/pr-golang-tests.yml`.

**Assertion Library:**
- Python uses pytest assertions and `unittest.mock`. See `backend/tests/unit/onyx/tools/tool_implementations/open_url/test_onyx_web_crawler_playwright_fallback.py`.
- Kotlin uses AssertJ, JUnit assertions, and Mockito. See `_kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/service/SearchServiceTest.kt`.
- Web uses Jest expectations and React Testing Library. See `web/src/hooks/useVisibilityGatedInterval.test.tsx`.
- Browser tests use Playwright expectations. Source: `web/tests/e2e/README.md`.

**Run Commands:**

```bash
# Repository root; documented Python lanes
uv run pytest -xv backend/tests/unit
uv run --env-file .vscode/.env pytest backend/tests/external_dependency_unit
uv run --env-file .vscode/.env pytest backend/tests/integration

# _kotlin/backend; both are required by Kotlin CI
./gradlew test --no-daemon
./gradlew opensearchIntegrationTest --no-daemon

# web; corresponding scripts also exist in _kotlin/web
bun run test
bun run test:watch
bun run test:coverage
bun run playwright <TEST_NAME>

# mobile
bun run test

# Within each Go module
go test -race ./...
```

Sources: `backend/AGENTS.md`, `.github/workflows/custom-kotlin-backend-checks.yml`, `web/package.json`, `_kotlin/web/package.json`, `mobile/package.json`, `.github/workflows/pr-golang-tests.yml`.

## Test File Organization

**Location:**
- Python tests are separate and mirror production domains under `backend/tests/`.
- Kotlin tests mirror packages under `_kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/`.
- Web tests are beside source files or in local `__tests__/`; browser tests are separate. Source: `web/jest.config.js`.
- Mobile discovery requires `src/**/__tests__/**/*.test.ts?(x)`. Source: `mobile/jest.config.js`.

**Naming:**
- Python: `test_*.py`; example `backend/tests/external_dependency_unit/document_index/test_document_index.py`.
- Kotlin: `*Test.kt` and `*IntegrationTest.kt`; examples in `_kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/ingestion/`.
- Web/mobile: `.test.ts` and `.test.tsx`; Playwright: `.spec.ts`. Sources: `web/jest.config.js`, `mobile/jest.config.js`, `web/playwright.config.ts`.

**Structure:**

```text
backend/tests/
  unit/
  external_dependency_unit/
  integration/common_utils/
  integration/tests/
_kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/
  api/ domain/ ingestion/ mcp/ opensearch/ service/ support/
web/src/**/                    # Jest tests
web/tests/setup/              # Shared setup and mocks
web/tests/e2e/pages/          # Browser page objects
mobile/src/**/__tests__/      # Native tests
```

## Test Structure

**Suite Organization:**

Example from `web/src/hooks/useSearchSettings.test.ts`:

```typescript
describe("secondaryRefreshInterval", () => {
  test("returns 60000 when there is no secondary model", () => {
    expect(secondaryRefreshInterval(null)).toBe(60000);
  });
});
```

**Patterns:**
- Python shared fixtures live in `backend/tests/conftest.py`; subdirectories supply scoped fixtures. Source: `backend/tests/README.md`.
- Use the `enable_ee` fixture instead of setting enterprise mode directly. Source: `backend/tests/README.md`.
- Kotlin tests use `@Test` methods and local factories. See `_kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/service/SearchServiceTest.kt`.
- H2 tests extend `H2IntegrationTest`, disable the worker, and reset tables. Contexts are dirtied after each class. Source: `_kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/support/H2IntegrationTest.kt`.
- HTTP controller tests combine this base with `@AutoConfigureMockMvc` and selected `@MockitoBean` dependencies. Source: `_kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/api/AdminApiIntegrationTest.kt`.
- Web timer tests restore real timers and visibility after each test. Source: `web/src/hooks/useVisibilityGatedInterval.test.tsx`.

## Mocking

**Framework:** `unittest.mock` for Python; Mockito and MockWebServer for Kotlin; Jest for web/mobile. Sources: test files cited below.

**Patterns:**

Kotlin example from `_kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/service/SearchServiceTest.kt`:

```kotlin
private val modelServer = mock(ModelServerClient::class.java)
// A keyword-only request must not call the embedding service.
verifyNoInteractions(modelServer)
```

- Kotlin HTTP client tests use `MockWebServer().use`, enqueue responses, and inspect recorded requests. Source: `_kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/ingestion/ModelServerClientTest.kt`.
- Python patches the imported dependency at its use site. Source: `backend/tests/unit/onyx/tools/tool_implementations/open_url/test_onyx_web_crawler_playwright_fallback.py`.
- Web setup maps CSS, assets, and selected providers to mocks; `clearMocks` is enabled, but reset/restore are disabled. Source: `web/jest.config.js`.

**What to Mock:**
- Mock all outside I/O in Python unit tests. Mock selectively in external-dependency unit tests. Source: `backend/AGENTS.md`.
- Mock Kotlin service boundaries in focused unit tests. Use real HTTP serialization against MockWebServer for client contracts. Sources: `_kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/service/SearchServiceTest.kt`, `ingestion/ModelServerClientTest.kt`.

**What NOT to Mock:**
- Do not mock Python deployment integration tests. Use real HTTP calls through existing Manager utilities. Source: `backend/AGENTS.md`.
- Do not replace OpenSearch with a mock when checking mappings, native fusion, indexing, or deletion semantics. The dedicated suite uses Testcontainers. Source: `_kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/ingestion/OpenSearchIndexerIntegrationTest.kt`.

## Fixtures and Factories

**Test Data:**

Kotlin tests use local typed factories with defaults, then `copy` for individual cases. Source: `_kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/service/SearchServiceTest.kt`.

```kotlin
candidate("center").copy(
    sourceDocumentId = "doc-1",
    chunkId = 1,
    content = "c1",
)
```

**Location:**
- Python fixtures: `backend/tests/conftest.py` and suite-level `conftest.py` files.
- Python deployed API helpers: `backend/tests/integration/common_utils/`.
- Kotlin database base: `_kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/support/H2IntegrationTest.kt`.
- Browser page objects: `web/tests/e2e/pages/`; setup: `web/tests/e2e/global-setup.ts` referenced by `web/playwright.config.ts`.
- Required test configuration is described in `AGENTS.md` and `backend/AGENTS.md`. Secret files were not read.

## Coverage

**Requirements:** No numeric coverage threshold was detected in `web/jest.config.js`, `backend/pytest.ini`, or `_kotlin/backend/build.gradle.kts`.

- Web CI collects coverage and uploads an artifact. Source: `.github/workflows/pr-jest-tests.yml`.
- Web collection covers `src/**/*.{ts,tsx}` and excludes declarations and stories. Opal is outside this collection glob. Source: `web/jest.config.js`.
- No JaCoCo plugin or coverage task is declared in `_kotlin/backend/build.gradle.kts`.

**View Coverage:**

```bash
# From web, or separately from _kotlin/web
bun run test:coverage
```

Sources: `web/package.json`, `_kotlin/web/package.json`.

## Test Types

**Unit Tests:**
- Python pure logic lane runs with xdist in CI. Source: `.github/workflows/pr-python-tests.yml`.
- Kotlin `test` excludes OpenSearch integration classes and the `opensearch-integration` tag. Source: `_kotlin/backend/build.gradle.kts`.
- Web Node and jsdom lanes use explicit match patterns. Add new test locations to those patterns if necessary. Source: `web/jest.config.js`.

**Integration Tests:**
- Python external-dependency tests call code directly with infrastructure available. Full integration tests need a running deployment. Source: `backend/AGENTS.md`.
- Kotlin H2 tests check Spring, repository, migration, and controller behavior. H2 PostgreSQL mode does not establish PostgreSQL engine equivalence. Source: `_kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/support/H2IntegrationTest.kt`.
- Kotlin OpenSearch tests use unique index names and clean indices after each test. Source: `_kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/ingestion/OpenSearchIndexerIntegrationTest.kt`.
- Both Kotlin tasks use one fork, a 768 MB heap, and 256 MB metaspace. Source: `_kotlin/backend/build.gradle.kts`.
- Go CI runs race checks; Terraform has a separate acceptance lane. Sources: `.github/workflows/pr-golang-tests.yml`, `.github/workflows/pr-terraform-provider-tests.yml`.

**E2E Tests:**
- Playwright includes admin, exclusive, and lite projects. CI uses two retries; the exclusive project runs serially. Source: `web/playwright.config.ts`.
- Put locators and UI interactions in page objects. Prefer test IDs and accessible labels, then roles. Source: `web/tests/e2e/README.md`.
- Use auto-retrying Playwright assertions for asynchronous UI state. Source: `web/tests/e2e/README.md`.

## Common Patterns

**Async Testing:**

Example from `web/src/hooks/useVisibilityGatedInterval.test.tsx`:

```typescript
act(() => {
  jest.advanceTimersByTime(3000);
});
expect(callback).toHaveBeenCalledTimes(3);
```

- Restore timers in teardown; do not leak fake timers across tests. Source: `web/src/hooks/useVisibilityGatedInterval.test.tsx`.
- Kotlin HTTP tests bound recorded-request waits with `TimeUnit`. Source: `_kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/ingestion/ModelServerClientTest.kt`.

**Error Testing:**

Example from `_kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/service/SearchServiceTest.kt`:

```kotlin
val error = assertThrows<IllegalArgumentException> {
    service.search("query", listOf("Missing"), 10)
}
assertThat(error.message).contains("Missing")
verifyNoInteractions(modelServer)
```

- Verify rejected input causes no downstream call, not just that an exception exists. Source: the Kotlin example above.
- Keep compiler/type checks separate from Jest: SWC only transpiles. Source: `web/jest.config.js`, `web/package.json`.

---

*Testing analysis: 2026-09-09*
