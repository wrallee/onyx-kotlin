# Technology Stack

**Analysis Date:** 2026-09-09

## Languages

**Primary:**
- Python >=3.13: Onyx API, workers, and model server. See `pyproject.toml`, `backend/onyx/`, and `backend/model_server/`.
- Kotlin 2.3.20: separate backend implementation. See `_kotlin/backend/build.gradle.kts` and `_kotlin/backend/src/main/kotlin/`.
- TypeScript: Next.js web clients and mobile client. See `web/package.json`, `_kotlin/web/package.json`, and `mobile/package.json`.
- Web manifests declare TypeScript ^5.9.3 and a TypeScript 7 alias for type checks. Mobile declares ~6.0.3.

**Secondary:**
- Go 1.26.5: terminal client in `cli/go.mod`. Go 1.26.4: Terraform provider in `terraform-provider-onyx/go.mod`.
- Rust, edition 2021: desktop shell in `desktop/src-tauri/Cargo.toml`.
- SQL: schema migrations in `backend/alembic/` and `_kotlin/backend/src/main/resources/db/migration/`.

## Runtime

**Environment:**
- Python 3.13 is the shared backend target in `pyproject.toml` and `_kotlin/model-server/Dockerfile`.
- Java 25 is the Kotlin toolchain and bytecode target in `_kotlin/backend/build.gradle.kts`.
- CI installs Java 25 in `.github/workflows/custom-kotlin-backend-checks.yml`.
- Kotlin container definitions conflict with that target: `_kotlin/backend/Dockerfile` uses Gradle 8.14.3/JDK 21 and Java 21 runtime.
- Browser and server JavaScript run through Next.js. See `web/package.json` and `_kotlin/web/package.json`.

**Package Manager:**
- Root JavaScript uses Bun 1.3.13, declared in `package.json`; `bun.lock` is present.
- Root Python uses uv with `uv.lock`. Dependency groups separate backend, development, enterprise, and model-server packages in `pyproject.toml`.
- Kotlin uses Gradle 9.5.1 through `_kotlin/backend/gradle/wrapper/gradle-wrapper.properties`.
- Kotlin web uses npm scripts and has `_kotlin/web/package-lock.json`.
- Kotlin model-server dependencies use pinned requirements and pip in `_kotlin/model-server/Dockerfile`.
- Go modules use `cli/go.sum` and `terraform-provider-onyx/go.sum`; Rust dependencies are declared in `desktop/src-tauri/Cargo.toml`.

## Frameworks

**Core:**
- FastAPI 0.133.1, Uvicorn 0.49.0, Pydantic 2.12.5: Python APIs and contracts in `pyproject.toml`.
- SQLAlchemy 2.0.50 and Alembic 1.18.4: Python persistence and migrations in `pyproject.toml`.
- Celery 5.5.1: background jobs in `backend/onyx/background/celery/`; versions in `pyproject.toml`.
- Spring Boot 4.0.7: Kotlin MVC API, WebClient, validation, JPA, and Flyway in `_kotlin/backend/build.gradle.kts`.
- Hibernate 7.3.13.Final is explicitly selected for Jackson 3 JSON support in `_kotlin/backend/build.gradle.kts`.
- Spring AI 1.0.0 supplies vector-store and RAG contracts in `_kotlin/backend/build.gradle.kts`.
- Next.js 16.3.3, React 19.2.4, Tailwind CSS ^4.3.3: both web clients in `web/package.json` and `_kotlin/web/package.json`.
- Expo ~57.0.15, React Native 0.86.2, React 19.2.3, NativeWind ^4.2.6: mobile in `mobile/package.json`.
- Tauri 2.11: desktop shell in `desktop/src-tauri/Cargo.toml`.

**Testing:**
- pytest 9.0.3, pytest-asyncio 1.4.0, pytest-xdist 3.8.0: Python tests in `pyproject.toml`.
- Jest ^30.4.2, Testing Library React ^16.3.0, Playwright ^1.39.0: web declarations in `web/package.json`.
- jest-expo ~57.0.4 and React Native Testing Library ^13.3.3: mobile in `mobile/package.json`.
- JUnit Platform through Spring Boot test starters, Kotlin test, MockWebServer 4.12.0, H2 2.3.232, Testcontainers 1.20.6: `_kotlin/backend/build.gradle.kts`.
- Kotlin tests use one fork and a 768 MB heap. OpenSearch integration tests have a separate Gradle task in `_kotlin/backend/build.gradle.kts`.

**Build/Dev:**
- Ruff 0.16.1 and ty 0.0.63: Python lint, format, and type checks in `pyproject.toml`.
- Oxlint ^1.66.0 and Oxfmt 0.59.0: web checks in `web/package.json`.
- Storybook 10.5.0: component previews in `web/package.json`.
- Pre-commit 3.2.2: checks declared in `pyproject.toml` and `.pre-commit-config.yaml`.
- Mobile uses ESLint and Prettier through `mobile/package.json`; do not substitute web lint rules.

## Key Dependencies

**Critical:**
- LiteLLM 1.93.0, OpenAI 2.38.0, Google GenAI 2.18.1, LangChain Core 1.3.3: Python provider integration in `pyproject.toml` and `backend/onyx/llm/`.
- OpenSearch Python 3.2.0: `pyproject.toml` and `backend/onyx/document_index/opensearch/`.
- OpenSearch Java 3.10.0: `_kotlin/backend/build.gradle.kts` and `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/opensearch/`.
- MCP Python 1.28.1 and FastMCP 3.2.0: `pyproject.toml`; Java MCP BOM 2.0.1: `_kotlin/backend/build.gradle.kts`.
- Apache Tika 3.1.0: Kotlin file extraction in `_kotlin/backend/build.gradle.kts`.
- Python model server: Torch 2.9.1, Transformers 5.14.1, Sentence Transformers 5.4.1 in `pyproject.toml`.
- Kotlin model sidecar: OpenVINO 2026.3.1 and Transformers 4.49.0 in `_kotlin/model-server/requirements.txt`; CPU Torch 2.9.1 in its `Dockerfile`.
- Keep model-server dependency sets separate. Their Transformers requirements differ.

**Infrastructure:**
- PostgreSQL clients: psycopg2-binary 2.9.10 and asyncpg 0.30.0 in `pyproject.toml`; Spring-managed JDBC driver in `_kotlin/backend/build.gradle.kts`.
- Redis 5.0.8 client: Python queues and coordination in `pyproject.toml` and `backend/onyx/redis/`.
- Boto3 1.39.11, Google Cloud Storage >=3,<4, Azure Blob >=12.24,<13: storage clients in `pyproject.toml`.
- Sentry 2.14.0, Langfuse 4.14.4, Braintrust 0.3.9: Python observability clients in `pyproject.toml`.

## Configuration

**Environment:**
- Python reads process variables through `backend/onyx/configs/app_configs.py` and `backend/shared_configs/configs.py`.
- Kotlin binds environment placeholders in `_kotlin/backend/src/main/resources/application.yml` and worker settings in `application-worker.yml`.
- Kotlin model paths and inference limits come from `_kotlin/model-server/app/config.py`.
- Local secret file contents are outside this map. Root `AGENTS.md` describes development environment setup.
- Project skill directories `.codex/skills/` and `.agents/skills/` were not detected. Area constraints live in `backend/AGENTS.md`, `web/AGENTS.md`, `mobile/AGENTS.md`, and `_kotlin/AGENTS.md`.

**Build:**
- Python: `pyproject.toml`, `uv.lock`, `.pre-commit-config.yaml`.
- Web: `web/next.config.js`, `web/tsconfig.json`, `web/tsconfig.types.json`, `web/postcss.config.js`.
- Kotlin: `_kotlin/backend/build.gradle.kts`, `_kotlin/backend/settings.gradle.kts`, and Gradle wrapper.
- Kotlin web keeps separate configuration in `_kotlin/web/next.config.js` and `_kotlin/web/package.json`.

## Platform Requirements

**Development:**
- Use Python 3.13 and uv for root backend work. Follow dependency groups in `pyproject.toml`.
- Use Java 25 and the Gradle wrapper for Kotlin. CI commands are in `.github/workflows/custom-kotlin-backend-checks.yml`.
- Use Docker for Kotlin OpenSearch integration tests; test infrastructure is defined under `_kotlin/backend/src/test/`.
- Model sidecar requires local OpenVINO model files. Offline loading is configured in `_kotlin/model-server/Dockerfile` and `app/config.py`.

**Production:**
- Root deployment assets support Compose, Helm/Kubernetes, ECS Fargate, and Terraform under `deployment/`.
- Kotlin has separate container assets in `_kotlin/docker-compose.yaml`, `_kotlin/backend/Dockerfile`, `_kotlin/web/Dockerfile`, and `_kotlin/model-server/Dockerfile`.
- Source mapping does not establish that any deployment is running. Resolve the Kotlin Java image mismatch before treating its Dockerfile as a working Java 25 deployment.

---

*Stack analysis: 2026-09-09*
