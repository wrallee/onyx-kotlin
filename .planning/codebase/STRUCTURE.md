# Codebase Structure

**Analysis Date:** 2026-09-09

## Directory Layout

```text
onyx-kotlin/
├── backend/                 # Root Python application, workers, model server, tests
│   ├── onyx/                # Community application modules
│   ├── model_server/        # Embedding/model HTTP service
│   ├── alembic/             # Database migrations
│   └── tests/               # Python test suites
├── web/                     # Root Next.js application
│   ├── src/app/             # Routes, layouts, API handlers
│   ├── lib/opal/            # Design system
│   ├── lib/shared/          # Cross-platform tokens and contracts
│   └── tests/               # Frontend tests
├── _kotlin/                 # Separate Kotlin application and frontend
│   ├── backend/             # Spring Boot Gradle project
│   ├── web/                 # Kotlin-facing Next.js application
│   ├── model-server/        # Model deployment reference
│   ├── scripts/             # Operational helpers
│   └── docs/                # Kotlin plans, specifications, walkthroughs
├── mobile/                  # React Native / Expo application
├── desktop/                 # Tauri desktop shell
├── cli/                     # Go CLI
├── widget/                  # Embeddable web client
├── extensions/chrome/       # Browser extension
├── deployment/              # Deployment resources
├── terraform-provider-onyx/  # Terraform provider
├── tools/                   # Development tools
├── docs/                    # Root project documentation
└── .planning/codebase/      # GSD codebase reference maps
```

## Directory Purposes

**`backend/onyx/`:**
- Purpose: Root application business logic and backend adapters.
- Contains: `server/`, `chat/`, `db/`, `connectors/`, `indexing/`, `document_index/`, `llm/`, `tools/`.
- Key files: `backend/onyx/main.py`, `backend/onyx/chat/process_message.py`, `backend/onyx/document_index/factory.py`.
- Keep route handlers in `backend/onyx/server/` and database operations in `backend/onyx/db/`.

**`backend/ee/`:**
- Purpose: Enterprise-specific implementation.
- Checkout status: Absent. Repository guidance describes this optional enterprise tree.
- Key guidance: `backend/AGENTS.md` identifies `backend/ee/onyx/db/` as an enterprise database boundary.
- Do not place enterprise changes in `_kotlin/backend/` merely because both trees contain related features.

**`backend/onyx/background/celery/`:**
- Purpose: Task apps, queue execution, and scheduling.
- Contains: `apps/` and `tasks/`.
- Key file: `backend/onyx/background/celery/tasks/beat_schedule.py`.
- Add shared tasks under the existing task hierarchy and supply expiration values.

**`web/`:**
- Purpose: Root web application and reusable design packages.
- Contains: Route files, feature code, providers, components, tests, and shared packages.
- Key files: `web/src/app/layout.tsx`, `web/src/app/page.tsx`, `web/src/app/api/[...path]/route.ts`.
- UI placement rules: `web/AGENTS.md`.

**`_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/`:**
- Purpose: Kotlin application source packages.
- `api/`: HTTP controllers, payloads, and exception handling.
- `service/`: Administration, search, storage, and query operations.
- `domain/`: JPA entities, domain enums, repository interfaces, and queries.
- `ingestion/`: Connector loaders, job scheduling, processing, pruning, and external-write fences.
- `opensearch/`: Search client configuration, vector store, and normalization pipelines.
- `mcp/`: MCP servlet configuration and tool contracts.
- `config/`: Application properties and shared bean setup.
- `security/`: Security-related helpers; protected filenames were not inspected during mapping.
- Key file: `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/OnyxKotlinApplication.kt`.

**`_kotlin/backend/src/main/resources/`:**
- Purpose: Spring configuration and SQL migrations.
- Contains: `application.yml`, `application-worker.yml`, and `db/migration/`.
- Key migration example: `_kotlin/backend/src/main/resources/db/migration/V11__fenced_ingestion_leases.sql`.
- Schema definitions and runtime entity mappings must agree with `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/domain/Domain.kt`.

**`_kotlin/web/`:**
- Purpose: Web application connected to the Kotlin API.
- Contains: Its own `src/`, `lib/`, package configuration, and frontend guidance.
- Key files: `_kotlin/web/src/app/page.tsx`, `_kotlin/web/src/app/api/[...path]/route.ts`, `_kotlin/web/AGENTS.md`.
- Home routes to indexing administration; the catch-all API handler forwards to the configured Kotlin API.
- Do not assume a change to `web/` also changes `_kotlin/web/`.

**`mobile/`:**
- Purpose: Native mobile client.
- Contains: Expo routes under `mobile/src/app/` and mobile-specific UI, API, and query code.
- Key files: `mobile/src/app/_layout.tsx`, `mobile/src/api/client.ts`, `mobile/src/query/client.ts`.
- Follow `mobile/AGENTS.md`; web component and spacing rules do not directly apply.

**`desktop/`, `cli/`, `widget/`, `extensions/chrome/`:**
- Purpose: Additional client surfaces and integrations.
- Key executable locations: `desktop/src-tauri/src/main.rs`, `cli/main.go`.
- Widget implementation: `widget/src/`; build configuration: `widget/vite.config.ts`.
- Keep client-specific changes within their client tree.

## Key File Locations

**Entry Points:**
- `backend/onyx/main.py`: Root FastAPI assembly.
- `backend/onyx/mcp_server_main.py`: Root MCP entry.
- `backend/model_server/__main__.py`: Model service entry.
- `web/src/app/page.tsx`: Root web redirect to chat.
- `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/OnyxKotlinApplication.kt`: Spring Boot entry.
- `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/mcp/McpConfiguration.kt`: Kotlin MCP registration.
- `_kotlin/web/src/app/page.tsx`: Kotlin indexing-admin landing redirect.

**Configuration:**
- `pyproject.toml`: Root Python project and tool configuration.
- `package.json`: Root JavaScript workspace scripts and packages.
- `web/next.config.js`: Root web routing and build configuration.
- `_kotlin/web/next.config.js`: Kotlin frontend routing and build configuration.
- `_kotlin/backend/build.gradle.kts`: Kotlin dependencies and build tasks.
- `_kotlin/backend/settings.gradle.kts`: Gradle project settings.
- `_kotlin/backend/src/main/resources/application.yml`: Spring runtime configuration.
- `.pre-commit-config.yaml`: Repository checks.
- `backend/AGENTS.md`, `web/AGENTS.md`, `mobile/AGENTS.md`, `_kotlin/AGENTS.md`: Subproject rules.

**Core Logic:**
- `backend/onyx/chat/process_message.py`: Chat-turn orchestration.
- `backend/onyx/indexing/indexing_pipeline.py`: Root indexing pipeline.
- `backend/onyx/document_index/factory.py`: Root index selection.
- `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/service/AdminService.kt`: Kotlin administration operations.
- `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/service/SearchService.kt`: Kotlin retrieval and fusion operations.
- `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/IngestionWorker.kt`: Worker, claims, processor, model client, and search candidate type.
- `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/OpenSearchIndexer.kt`: Kotlin index read/write operations.

**Testing:**
- `backend/tests/unit/`: Isolated Python tests.
- `backend/tests/external_dependency_unit/`: Python tests with external dependencies.
- `backend/tests/integration/`: Tests against a deployed root application.
- `web/tests/e2e/`: Root Playwright scenarios.
- `_kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/`: Kotlin package-aligned tests.
- `_kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/support/H2IntegrationTest.kt`: Kotlin test database support.
- `mobile/src/`: Mobile colocated `__tests__` conventions are specified in `mobile/AGENTS.md`.

## Naming Conventions

**Files:**
- Python modules use snake case: `backend/onyx/chat/process_message.py`.
- Python models commonly use `models.py`: `backend/onyx/indexing/models.py`.
- Kotlin files use PascalCase: `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/service/SearchService.kt`.
- Kotlin files can contain several related classes; inspect `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/IngestionWorker.kt` before searching by class filename.
- Flyway migrations use versioned SQL names: `_kotlin/backend/src/main/resources/db/migration/V10__active_ingestion_job_per_pair.sql`.
- Next.js route entry names follow framework conventions: `web/src/app/page.tsx`, `web/src/app/layout.tsx`.

**Directories:**
- Kotlin packages use lower-case responsibilities beneath `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/`.
- Feature hooks belong under the feature's `lib/` directory, as specified in `web/AGENTS.md`.
- Expo route groups remain under `mobile/src/app/`; parentheses group routes without adding URL segments.

## Where to Add New Code

**New Feature:**
- Root API: Add routes under `backend/onyx/server/`, logic under the appropriate `backend/onyx/` domain, and database helpers under `backend/onyx/db/`.
- Root tests: Choose `backend/tests/integration/` or `backend/tests/external_dependency_unit/` according to `backend/AGENTS.md`.
- Kotlin API: Add controller methods in `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/api/` and operations in `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/service/`.
- Kotlin tests: Mirror the source package under `_kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/`.
- Kotlin schema changes: Add a migration under `_kotlin/backend/src/main/resources/db/migration/` and update domain mappings.

**New Component/Module:**
- Root reusable UI: Prefer `web/lib/opal/src/`; use `web/src/refresh-components/` when required.
- Root feature composites: Use `web/src/sections/`; page layouts belong in `web/src/layouts/`.
- Kotlin UI: Use corresponding directories inside `_kotlin/web/` and its own `AGENTS.md`.
- Kotlin connector: Place a loader under `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/`; reuse `ConnectorModels.kt` contracts.
- Kotlin MCP tool: Extend `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/mcp/` and register the tool in `McpConfiguration.kt`.

**Utilities:**
- Root backend shared helpers: `backend/onyx/utils/`.
- Root feature hooks: `web/src/lib/<feature>/hooks.ts`, as directed by `web/AGENTS.md`.
- Cross-platform contracts: `web/lib/shared/`; extract only proven shared code, as directed by `mobile/AGENTS.md`.
- Kotlin helpers: Keep them in the owning package under `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/`.

## Special Directories

**`.planning/codebase/`:**
- Purpose: Reference maps for GSD planning and execution.
- Generated: Yes, by mapping tasks.
- Committed: Determined by the orchestrator; this task creates no commit.

**`_kotlin/docs/`:**
- Purpose: Kotlin specifications, plans, references, and walkthroughs.
- Generated: No; authored project documentation.
- Committed: Existing project documents are present in the repository.
- Follow date-prefixed kebab-case document naming in `_kotlin/AGENTS.md` for Kotlin project artifacts.

**`web/lib/shared/dist/`:**
- Purpose: Built shared package output consumed by mobile.
- Generated: Yes; `mobile/AGENTS.md` describes the build contract.
- Committed: No, per `mobile/AGENTS.md`.

**`backend/log/`:**
- Purpose: Local service logs described in `AGENTS.md`.
- Generated: Yes, when services run.
- Committed: Not established by this source mapping; do not treat logs as source files.

---

*Structure analysis: 2026-09-09*
