# External Integrations

**Analysis Date:** 2026-09-09

## APIs & External Services

**LLM providers and embedding services:**
- Root Onyx routes LLM calls through its provider abstraction and LiteLLM. See `backend/onyx/llm/` and `pyproject.toml`.
  - Clients include LiteLLM, OpenAI, Google GenAI, Cohere, and Voyage AI.
  - Provider configuration is deployment data; package presence does not prove a configured account.
- Root embedding services have separate query and indexing host settings in `backend/shared_configs/configs.py`.
  - Configuration: `MODEL_SERVER_HOST`, `INDEXING_MODEL_SERVER_HOST`.
- Kotlin calls its model sidecar using Spring WebClient. See `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/config/ModelServerHttpClient.kt`.
  - Configuration: `MODEL_SERVER_URL`, connect/read timeouts, embedding model and dimension settings in `_kotlin/backend/src/main/resources/application.yml`.
  - Sidecar loads local OpenVINO artifacts through `_kotlin/model-server/app/runtime.py` and `app/config.py`.
  - The container enables offline model loading in `_kotlin/model-server/Dockerfile`.

**Document connectors:**
- Root connectors include Slack, Google Drive, GitHub, GitLab, Jira, Confluence, Notion, SharePoint, Salesforce, and web ingestion under `backend/onyx/connectors/`.
  - SDK declarations include slack-sdk, google-api-python-client, PyGithub, python-gitlab, jira, atlassian-python-api, and simple-salesforce in `pyproject.toml`.
  - Authentication and retrieval differ by connector. Use each connector's implementation and configured account.
- Kotlin remote sources are Jira, Confluence, and GitHub. Dispatch is explicit in `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/RemoteConnectorLoaders.kt`.
  - Client: shared HTTP handling in `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/RemoteJsonClient.kt`.
  - Jira supports API token fields and Basic or Bearer authentication in `JiraConnectorLoader.kt` in that directory.
  - Confluence supports token plus optional username and Basic or Bearer authentication in `ConfluenceConnectorLoader.kt`.
  - GitHub uses access-token Bearer authentication in `GithubConnectorLoader.kt`.
  - These tokens are connector data, not a single mandatory process variable.
- Kotlin file ingestion uses local uploaded assets through `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/FileConnectorLoader.kt`.

**MCP:**
- Kotlin exposes a Streamable HTTP servlet at `/mcp`. See `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/mcp/McpConfiguration.kt`.
  - SDK: MCP Java 2.0.1 with Jackson 3 mapping in `_kotlin/backend/build.gradle.kts`.
  - Tools support indexed search, legacy search, fusion, and document context through `McpSearchTool.kt` in the same package.
  - Default document-set selection comes from `X-Onyx-Document-Sets`, `X-Document-Sets`, or the `document_sets` query parameter.
- Root MCP dependencies and tool integration exist in `pyproject.toml` and `backend/onyx/server/features/mcp/`.

## Data Storage

**Databases:**
- PostgreSQL stores root application data. See `backend/onyx/db/engine/sql_engine.py` and `backend/onyx/db/models.py`.
  - Configuration: `POSTGRES_HOST` and related database settings in `backend/onyx/configs/app_configs.py`.
  - Client: SQLAlchemy, psycopg2, asyncpg; migrations in `backend/alembic/`.
- PostgreSQL stores Kotlin application and ingestion state. See `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/domain/Repositories.kt`.
  - Configuration: `POSTGRES_URL`, `POSTGRES_USER`, `POSTGRES_PASSWORD` in `_kotlin/backend/src/main/resources/application.yml`.
  - Client: Spring Data JPA/Hibernate; Flyway scripts in `_kotlin/backend/src/main/resources/db/migration/`.
- OpenSearch stores Kotlin chunks and provides hybrid retrieval through `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/opensearch/OnyxOpenSearchVectorStore.kt`.
  - Configuration: `OPENSEARCH_URL`, `OPENSEARCH_INDEX`, `OPENSEARCH_ADMIN_USERNAME`, `OPENSEARCH_ADMIN_PASSWORD`, `OPENSEARCH_VERIFY_CERTS`.
  - Client: opensearch-java; configuration binding in `_kotlin/backend/src/main/resources/application.yml`.
- Root index selection is configuration and database dependent in `backend/onyx/document_index/factory.py`.
  - OpenSearch and Vespa implementations both remain executable branches. Do not infer active retrieval from package names alone.
  - Retrieval checks database migration state. Index writes check `ONYX_DISABLE_VESPA` and `ENABLE_OPENSEARCH_INDEXING_FOR_ONYX`.

**File Storage:**
- Root file-store factory supports S3, PostgreSQL, Google Cloud Storage, and Azure Blob in `backend/onyx/file_store/file_store.py`.
  - `FILE_STORE_BACKEND` defaults to S3 in `backend/onyx/configs/app_configs.py`.
  - S3 configuration includes `S3_ENDPOINT_URL`, `S3_FILE_STORE_BUCKET_NAME`, and AWS credential variable names.
  - GCS and Azure implementations live in `backend/onyx/file_store/gcs_file_store.py` and `azure_blob_file_store.py`.
- Kotlin stores uploads on the local filesystem through `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/service/FileStorageService.kt`.
  - Configure `ONYX_FILE_STORAGE_ROOT` in `_kotlin/backend/src/main/resources/application.yml` and persist that directory across restarts.

**Caching:**
- Root Redis provides queue and coordination state through `backend/onyx/redis/redis_pool.py` and `backend/onyx/background/celery/`.
  - Configure `REDIS_HOST` and related settings in `backend/onyx/configs/app_configs.py`.
- A Redis client dependency is not declared in `_kotlin/backend/build.gradle.kts`; its worker implementation lives in `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/`.

## Authentication & Identity

**Auth Provider:**
- Root authentication uses FastAPI Users and project-specific session, OAuth, API-key, and permission code in `backend/onyx/auth/`.
  - See `backend/onyx/auth/users.py`, `session_tokens.py`, `api_key.py`, and `permissions.py`.
  - SSO provider management exists in `backend/onyx/server/manage/sso/`; SAML dependencies are declared in `pyproject.toml`.
  - `WEB_DOMAIN` controls login redirect origin in `backend/onyx/configs/app_configs.py`.
- Kotlin has no user authentication system. `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/api/AuthController.kt` returns one built-in administrator.
  - Do not apply root authentication assumptions to Kotlin `/me`, admin endpoints, or MCP.
  - `ONYX_CREDENTIAL_ENCRYPTION_KEY` is a separate connector-storage setting in `_kotlin/backend/src/main/resources/application.yml`.

## Monitoring & Observability

**Error Tracking:**
- Root Python declares Sentry in `pyproject.toml`; `SENTRY_DSN` and sampling settings are in `backend/shared_configs/configs.py`.
- Web uses `@sentry/nextjs` with `web/sentry.server.config.ts` and `web/sentry.edge.config.ts`.
- Kotlin backend has no Sentry dependency declared in `_kotlin/backend/build.gradle.kts`.

**Logs:**
- Root logging helpers are in `backend/onyx/utils/logger.py`; structured logging dependencies are in `pyproject.toml`.
- Root LLM traces support Braintrust and Langfuse processors in `backend/onyx/tracing/`.
  - Configuration names include `BRAINTRUST_API_KEY`, `BRAINTRUST_PROJECT`, `LANGFUSE_PUBLIC_KEY`, `LANGFUSE_SECRET_KEY`, and `LANGFUSE_HOST` in `backend/onyx/configs/app_configs.py`.
- Kotlin model server declares Prometheus support in `_kotlin/model-server/requirements.txt` and implements its API in `app/main.py`.

## CI/CD & Deployment

**Hosting:**
- Root deployment definitions cover Compose, Kubernetes/Helm, ECS Fargate, and Terraform in `deployment/`.
- Kotlin has its own Compose manifest and Dockerfiles under `_kotlin/`.
- Hosting accounts and live deployment state are not established by these source files.

**CI Pipeline:**
- GitHub Actions workflows live in `.github/workflows/`.
- `.github/workflows/custom-kotlin-backend-checks.yml` runs Java 25 Gradle unit/slice tests and OpenSearch integration tests.
- Python, web, desktop, Go, Terraform provider, and deployment workflows are separate files in `.github/workflows/`.

## Environment Configuration

**Required env vars:**
- Required values depend on the chosen services; source defaults are not deployment validation.
- Kotlin dependencies: `POSTGRES_URL`, `MODEL_SERVER_URL`, `OPENSEARCH_URL`, storage root, and connector encryption key. See `_kotlin/backend/src/main/resources/application.yml`.
- Kotlin model files: `EMBEDDING_MODEL_PATH`, `EMBEDDING_MODEL_NAME`, and `EMBEDDING_OPENVINO_FILE`. See `_kotlin/model-server/app/config.py`.
- Root dependencies: PostgreSQL, Redis, file-store, model-server, and provider settings. See `backend/onyx/configs/app_configs.py` and `backend/shared_configs/configs.py`.
- Optional trace and storage credentials apply only to enabled integrations in those configuration modules.

**Secrets location:**
- Root `AGENTS.md` documents process environment, a gitignored local test environment file, and AWS Secrets Manager for test setup.
- Kotlin connector token values are supplied as connector data; loader code consumes parsed token fields in `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/`.
- Secret files and actual credential values were not inspected for this map.

## Webhooks & Callbacks

**Incoming:**
- Root OAuth callback handling includes `/auth/oauth/callback`; see `backend/onyx/server/auth/captcha_api.py`.
- Root SSO callback URI construction is referenced by `backend/onyx/server/manage/sso/models.py`.
- Kotlin exposes `/mcp` as a request transport in `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/mcp/McpConfiguration.kt`.
- Kotlin remote document loading uses polling and worker execution in `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/IngestionScheduler.kt` and `IngestionWorker.kt`.

**Outgoing:**
- Kotlin connector loaders issue outbound requests to configured Jira, Confluence, and GitHub services in `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/`.
- No generic outgoing webhook publisher was identified in that Kotlin integration path.
- Root outbound provider and connector requests are implemented in `backend/onyx/llm/` and `backend/onyx/connectors/`.

---

*Integration audit: 2026-09-09*
