# Onyx FOSS Kotlin port

This branch keeps the approved local FOSS admin UI and Kotlin management backend,
and uses a Python 3.13 model-server for embedding. OpenSearch performs hybrid
score fusion.

## Supported scope

- File, Jira, Confluence, and GitHub connector administration and collection
- Connector credentials encrypted at rest with AES-GCM
- PostgreSQL-backed jobs, attempts, checkpoints, and document sets
- Tika extraction, Python model-server calls, and OpenSearch indexing
- Hybrid keyword and vector search through a Streamable HTTP MCP endpoint
- Authentication-free admin UI for local or private networks

All other connector cards and unrelated admin routes remain visible but are
disabled. Document Sets are always public; user and group controls are visible
but disabled.

## Model artifacts

The selected model-server implementation on this branch is Python 3.13. The
following pinned, Apache-2.0 artifacts are downloaded under the ignored `models/`
directory:

- Granite 311M Multilingual R2 INT8 OpenVINO embedding
- GTE multilingual reranker-base
- BGE reranker v2 m3 candidate

See `model-server/MODELS.md` for revisions and SHA-256 values.

The Kotlin backend sends every chunking and embedding request to the Python
model-server. Granite uses OpenVINO INT8. Harrier loads from a user mount through
SentenceTransformers and PyTorch. OpenAI-compatible requests use only the
configured endpoint and do not load a local embedding model. The current search
does not call a reranker. The optional GTE and BGE artifacts remain available for
evaluation.

## Run

Every external or server OpenSearch node must have `analysis-nori` before backend deployment.
Run this check on each node:

```bash
/usr/share/opensearch/bin/opensearch-plugin list | grep -Fx analysis-nori
```

Local Compose installs the plugin idempotently in the stock OpenSearch container.
The application applies compatible mapping additions and reports incompatible mappings.
It never deletes or migrates index data.

Download the pinned Granite artifact, then create the local environment file and set a unique credential key:

```bash
cd _kotlin
python3 scripts/download_models.py
cp .env.example .env
openssl rand -base64 32
# Set ONYX_CREDENTIAL_ENCRYPTION_KEY and a strong OPENSEARCH_ADMIN_PASSWORD in .env.
docker compose config -q
docker compose build
docker compose up -d
```

To use Harrier, mount its files read-only under `models/harrier-oss-v1-0.6b`.
The repository does not download or verify this optional artifact.

For an isolated stack, use a separate project, port, subnet, and read-only model path:

```bash
COMPOSE_PROJECT_NAME=onyx-kotlin-isolated \
WEB_PORT=13300 \
COMPOSE_SUBNET=192.168.241.0/24 \
MODEL_DIR=/absolute/path/to/models \
docker compose up -d --build

COMPOSE_PROJECT_NAME=onyx-kotlin-isolated \
WEB_PORT=13300 \
./scripts/test-kotlin-file-ingestion.sh

COMPOSE_PROJECT_NAME=onyx-kotlin-isolated \
WEB_PORT=13300 \
COMPOSE_SUBNET=192.168.241.0/24 \
MODEL_DIR=/absolute/path/to/models \
docker compose down -v --remove-orphans
```

The script derives `http://localhost:13300` from `WEB_PORT`. Set
`WEB_BASE_URL` only when the Web service uses another address.

Start the UI, API, worker, Python model-server, PostgreSQL, and OpenSearch:

```bash
docker compose up -d
```

Open `http://localhost:3000`. Only the Web service is published to the host.

## MCP search

Connect remote MCP clients to `https://onyx-admin.com/mcp`. The Web service
proxies this path to the Kotlin backend. The backend endpoint is not published
directly.

```json
{
  "mcpServers": {
    "onyx": {
      "url": "https://onyx-admin.com/mcp"
    }
  }
}
```

The `search` tool accepts optional document set and normalized metadata filters.
Metadata filters cover project keys, repositories, Confluence spaces, statuses,
and document types. Values in one filter are ORed. Different filters are ANDed.
BM25 also searches these normalized values with a lower weight than title and content.
It searches the union of selected document sets and returns metadata with a short excerpt and opaque `id`.
Pass that `id` to `get_document_context` to read the same indexed copy. The
tools return each JSON payload once as MCP text content. The default limit is 30.
Semantic and hybrid search retrieve five times the requested limit before
OpenSearch returns one result per logical chunk. A logical chunk combines
`source_document_id` and `chunk_id`, so copies from different connector pairs
collapse while adjacent chunks remain. Set `ONYX_SEARCH_CANDIDATE_MULTIPLIER`
to change the candidate multiplier.

Run a full connector reindex after deployment. Existing chunks do not contain
the new normalized metadata or token-aware contextual chunks. Metadata filters
can omit those chunks until the reindex finishes.

The MCP endpoint has no authentication in this development version. Do not
expose it beyond the intended private environment until authentication exists.

## Verification

```bash
# Backend
cd backend
JAVA_HOME="$HOME/.sdkman/candidates/java/25-zulu" ./gradlew test

# OpenSearch integration profile (starts one shared container)
JAVA_HOME="$HOME/.sdkman/candidates/java/25-zulu" ./gradlew opensearchIntegrationTest
cd ..

# Web-to-PostgreSQL-to-OpenSearch File ingestion
./scripts/test-kotlin-file-ingestion.sh

# Python model-server
cd model-server
python3.13 -m venv .venv
.venv/bin/pip install -r requirements-dev.txt
.venv/bin/pytest -q
cd ..

# Frontend (Node.js 24)
cd web
source "$HOME/.nvm/nvm.sh"
nvm use 24
npm ci --legacy-peer-deps
npm run types:check
npm run build
```

The File, Jira, Confluence, and GitHub loaders support bounded batches,
checkpoints, poll windows, pruning, and document failures.
Document Set changes update existing OpenSearch chunks.

Image-specific vector embedding is not complete. It remains WATCHLIST work.

See `SOURCE_PROVENANCE.md` for source and license boundaries and
`docs/model-server-spike.md` for the model compatibility gate.
