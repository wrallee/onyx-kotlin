# Python 3.13 model server

The Kotlin backend uses this service for all chunking and embedding requests:

- `POST /encoder/bi-encoder-embed`: embed query or passage text
- `POST /encoder/chunk-and-embed`: sentence-aware chunks with separate title and metadata context budgets
- `POST /encoder/chunk`: prepare new chunks without embedding them
- `POST /encoder/prepare-existing-chunks`: prepare stored chunks without changing their identity

It runs inside `python:3.13-slim`; the host Python version is irrelevant. The
model is mounted read-only from `../models` and is never downloaded at request
time. A local model loads on its first local request. API-only use does not load
local model weights. Inference is serialized by default to keep CPU and memory
usage bounded.

Requests without `provider_type` use the selected local model for both
tokenization and embedding. Granite uses OpenVINO INT8. Harrier uses its mounted
SentenceTransformers artifact and PyTorch CPU.

Requests with `provider_type=openai_compatible` use `tiktoken` for chunk limits
and call only the configured API. They do not resolve Granite or Harrier.
Unknown API model names use `cl100k_base` for chunk-size estimation. The API
still creates every vector. The image caches all `tiktoken` encoding tables at
build time and does not download them at runtime.

Harrier is optional. Put its files in `/models/harrier-oss-v1-0.6b` or set
`HARRIER_MODEL_PATH`. Missing Harrier files do not stop Granite or API requests.
`EMBEDDING_MODEL_NAME` defaults to Granite.

## Configuration

| Variable | Default | Purpose |
| --- | --- | --- |
| `EMBEDDING_MODEL_NAME` | Granite 311M Multilingual R2 | Default local model |
| `EMBEDDING_MODEL_PATH` | `/models/granite-embedding-311m-multilingual-r2-int8-openvino` | Granite artifact |
| `HARRIER_MODEL_PATH` | `/models/harrier-oss-v1-0.6b` | Optional Harrier artifact |
| `MODEL_INFERENCE_CONCURRENCY` | `1` | Concurrent local inference calls |
| `TORCH_NUM_THREADS` | `4` | PyTorch threads for Harrier |
| `MODEL_SERVER_CONNECT_TIMEOUT_MS` | `30000` | Provider connection timeout |
| `MODEL_SERVER_READ_TIMEOUT_MS` | `600000` | Provider response timeout |
