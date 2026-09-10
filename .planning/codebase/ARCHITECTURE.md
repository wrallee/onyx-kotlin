<!-- refreshed: 2026-09-09 -->
# Architecture

**Analysis Date:** 2026-09-09

## System Overview

This repository contains two application systems. Keep their runtime contracts separate.

```text
Root Onyx                             Kotlin application
web/src/app/                          _kotlin/web/src/app/
mobile/src/app/                       MCP clients
       |                                    |
       v                                    v
backend/onyx/main.py                   _kotlin/backend/src/main/kotlin/
backend/onyx/mcp_server_main.py          com/onyx/foss/kotlin/OnyxKotlinApplication.kt
       |                                    |
       v                                    v
Chat, tools, connector workers        Admin services, search, scheduled ingestion
backend/onyx/chat/                    _kotlin/backend/src/main/kotlin/
backend/onyx/background/celery/         com/onyx/foss/kotlin/{service,ingestion}/
       |                                    |
       v                                    v
PostgreSQL, Redis, document index     PostgreSQL, OpenSearch, model-server HTTP
backend/onyx/db/                      _kotlin/backend/src/main/kotlin/
backend/onyx/document_index/           com/onyx/foss/kotlin/{domain,opensearch}/
```

Root Onyx includes chat and enterprise extension points in `backend/onyx/`. The documented `backend/ee/` tree is absent from this checkout.
The Kotlin application implements connector administration, ingestion, and MCP retrieval in `_kotlin/backend/`.
Its home page redirects to indexing administration in `_kotlin/web/src/app/page.tsx`.
The root home page redirects to chat in `web/src/app/page.tsx`.

## Component Responsibilities

| Component | Responsibility | File |
|-----------|----------------|------|
| Python application | Router assembly, lifespan, common handlers | `backend/onyx/main.py` |
| Python chat endpoint | Authorization, limits, streamed or complete response | `backend/onyx/server/query_and_chat/chat_backend.py` |
| Chat orchestration | Build turns, run models, emit and persist results | `backend/onyx/chat/process_message.py` |
| Document-index selection | Choose retrieval backend and indexing targets | `backend/onyx/document_index/factory.py` |
| Python background work | Separate Celery worker applications | `backend/onyx/background/celery/apps/` |
| Embedding service | Separate model-serving HTTP application | `backend/model_server/main.py` |
| Kotlin application | Spring component discovery and scheduling | `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/OnyxKotlinApplication.kt` |
| Kotlin administration | HTTP contracts for connectors and indexing | `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/api/AdminController.kt` |
| Kotlin business operations | Administration and durable job creation | `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/service/AdminService.kt` |
| Kotlin ingestion | Claim, renew, process, and complete jobs | `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/IngestionWorker.kt` |
| Kotlin retrieval | Validate filters and choose retrieval mode | `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/service/SearchService.kt` |
| MCP transport | Register servlet and search tools | `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/mcp/McpConfiguration.kt` |

## Pattern Overview

**Overall:** Separate service applications with layered backend modules and independent frontend trees.

**Key Characteristics:**
- Root API routes delegate into domain modules and database helpers: `backend/onyx/server/`, `backend/onyx/db/`.
- Kotlin controllers delegate to Spring services and JPA repositories: `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/`.
- Embeddings cross an HTTP boundary; Kotlin's `ModelServerClient` lives in `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/IngestionWorker.kt`.
- Kotlin MCP retrieval returns indexed chunks; its tool contract lives in `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/mcp/McpSearchTool.kt`.

## Layers

**Presentation and transport:**
- Purpose: Pages, client state, request forwarding, and HTTP/tool contracts.
- Locations: `web/src/app/`, `_kotlin/web/src/app/`, `mobile/src/app/`, `backend/onyx/server/`.
- Kotlin endpoints: `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/api/` and `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/mcp/`.
- Depends on: Backend service contracts and frontend feature libraries.

**Application logic:**
- Purpose: Chat orchestration, search, administration, and ingestion processing.
- Root locations: `backend/onyx/chat/`, `backend/onyx/indexing/`, `backend/onyx/tools/`.
- Kotlin locations: `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/service/` and `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/`.
- Depends on: Database helpers, connector adapters, document-index clients, and model clients.
- Used by: API handlers, MCP tools, and background workers.

**Persistence and external adapters:**
- Root locations: `backend/onyx/db/`, `backend/onyx/connectors/`, `backend/onyx/document_index/`, `backend/onyx/llm/`.
- Kotlin locations: `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/domain/` and `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/opensearch/`.
- Purpose: SQL persistence, source retrieval, search indexing, and external model calls.
- Keep Python database operations under `backend/onyx/db/` or `backend/ee/onyx/db/`, as required by `backend/AGENTS.md`.

## Data Flow

### Primary Request Path

1. Root pages call frontend API paths through `web/src/app/api/[...path]/route.ts` during development.
2. The FastAPI application registers chat routes in `backend/onyx/main.py`.
3. `handle_send_chat_message` checks permissions and limits in `backend/onyx/server/query_and_chat/chat_backend.py`.
4. `handle_stream_message_objects` coordinates the turn in `backend/onyx/chat/process_message.py`.
5. The endpoint streams serialized packets or gathers a complete response in `backend/onyx/server/query_and_chat/chat_backend.py`.

The root catch-all proxy rejects production requests unless explicitly enabled in `web/src/app/api/[...path]/route.ts`.
Production routing must provide the API forwarding contract outside that development handler.

### Kotlin MCP Retrieval

1. `/mcp` enters the servlet registered in `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/mcp/McpConfiguration.kt`.
2. Tool arguments become search parameters in `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/mcp/McpSearchTool.kt`.
3. `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/service/SearchService.kt` validates query, limit, and document-set names.
4. Keyword mode searches directly; semantic and hybrid modes first embed the query through `ModelServerClient` in `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/IngestionWorker.kt`.
5. `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/OpenSearchIndexer.kt` executes the selected query and returns chunk candidates.
6. `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/mcp/McpSearchTool.kt` returns text and structured tool content.

Hybrid mode uses an OpenSearch hybrid query and normalization pipeline in `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/OpenSearchIndexer.kt`.
The separate WRRF tool combines supplied ranked lists in `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/service/SearchService.kt`.
Do not describe that tool as the internal implementation of native hybrid retrieval.

### Kotlin Ingestion

1. Administration and periodic scheduling create durable work through `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/service/AdminService.kt` and `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/IngestionScheduler.kt`.
2. `IngestionWorker` polls and `JobClaimService` claims work in `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/IngestionWorker.kt`.
3. Claims lock the connector pair and assign lease ownership in `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/IngestionWorker.kt`.
4. Source loaders return checkpointed document batches using `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/ConnectorModels.kt`.
5. `IngestionProcessor` embeds documents, writes index data, and updates durable state in `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/IngestionWorker.kt`.
6. Document-set updates use a separate durable outbox worker in `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/DocumentSetSyncWorker.kt`.

**State Management:**
- Root durable metadata uses SQLAlchemy models in `backend/onyx/db/models.py`; Redis helpers live in `backend/onyx/redis/`.
- Kotlin jobs, leases, checkpoints, and outbox records use `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/domain/Domain.kt`.
- Repository locking and queries live in `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/domain/Repositories.kt`.
- Mobile cache behavior differs from web; follow `mobile/AGENTS.md` and `mobile/src/query/client.ts`.

## Key Abstractions

**DocumentIndex:**
- Purpose: Isolate root retrieval and indexing backends.
- Examples: `backend/onyx/document_index/interfaces_new.py`, `backend/onyx/document_index/factory.py`.
- Pattern: Interface with runtime-selected implementations.
- The factory selects OpenSearch from retrieval state, otherwise Vespa; vector-disabled mode has a separate implementation.
- Do not infer the live backend from directory names or root documentation alone.

**ConnectorBatch and SourceDocument:**
- Purpose: Carry normalized documents, errors, and checkpoints across Kotlin source adapters.
- Example: `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/ConnectorModels.kt`.
- Preserve checkpoint and enumeration semantics when adding connector loaders.

**PairExternalWriteFence:**
- Purpose: Serialize external writes with connector-pair and migration locks.
- Example: `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/PairExternalWriteFence.kt`.
- Keep external writes inside the existing fence when modifying ingestion or deletion.

## Entry Points

- `backend/onyx/main.py`: Root FastAPI application assembly.
- `backend/onyx/mcp_server_main.py`: Root MCP server entry point.
- `backend/model_server/__main__.py`: Model server process entry point.
- `backend/onyx/background/celery/apps/`: Root Celery worker applications.
- `web/src/app/layout.tsx`: Root Next.js page providers and layout.
- `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/OnyxKotlinApplication.kt`: Kotlin Spring Boot application.
- `_kotlin/web/src/app/layout.tsx`: Kotlin frontend layout.
- `mobile/src/app/_layout.tsx`: Expo application layout.
- `desktop/src-tauri/src/main.rs`: Desktop native shell.
- `cli/main.go`: CLI executable.

## Architectural Constraints

- **Threading:** Python Celery workers use thread pools; implement task timeouts inside tasks as required by `backend/AGENTS.md`.
- **Kotlin scheduling:** `IngestionWorker.work` processes one claimed job per invocation in `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/IngestionWorker.kt`.
- **Blocking model calls:** Kotlin's `ModelServerClient` calls `.block()` in `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/IngestionWorker.kt`.
- **Global state:** Spring manages shared service beans in `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/config/RuntimeConfiguration.kt`.
- **Tenant context:** Propagate tenant IDs into root Celery tasks; `backend/AGENTS.md` describes the default-schema fallback.
- **Circular imports:** No complete dependency-cycle analysis was performed; inspect touched module imports before restructuring `backend/onyx/`.
- **Authentication:** Kotlin returns a fixed administrator identity in `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/api/AuthController.kt`.

## Anti-Patterns

### Treating Kotlin compatibility responses as authorization

**What happens:** `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/api/AuthController.kt` returns administrator-shaped identity data.
**Why it's wrong:** This handler implements no authentication or per-user authorization.
**Do this instead:** Treat this as the actual access boundary when designing deployment and security changes.

### Extending legacy frontend components

**What happens:** Legacy components remain under `web/src/components/` alongside newer component directories.
**Why it's wrong:** `web/AGENTS.md` excludes that directory from new component development.
**Do this instead:** Use `web/lib/opal/src/`, then `web/src/refresh-components/` when Opal lacks the component.

## Error Handling

**Strategy:** Each backend normalizes errors at its own transport boundary.

**Patterns:**
- Root APIs use `OnyxError` and central error codes; see `backend/onyx/error_handling/exceptions.py` and `backend/onyx/main.py`.
- Kotlin HTTP errors map to status plus `detail` in `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/api/ApiExceptionHandler.kt`.
- Kotlin MCP handlers return `isError` tool results in `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/mcp/McpSearchTool.kt`.
- Root chat stream failures are serialized inside the stream in `backend/onyx/server/query_and_chat/chat_backend.py`.

## Cross-Cutting Concerns

**Logging:** Root logging helpers live in `backend/onyx/utils/logger.py`; Kotlin workers log processing failures in `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/IngestionWorker.kt`.
**Validation:** Root APIs use typed request models; Kotlin search uses explicit guards in `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/service/SearchService.kt`.
**Authentication:** Root auth lives in `backend/onyx/auth/`; Kotlin's fixed identity lives in `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/api/AuthController.kt`.
**Tracing:** Tag model calls with registered flows as required by `backend/AGENTS.md`; registry: `backend/onyx/tracing/flows.py`.

---

*Architecture analysis: 2026-09-09*
