# Python 3.13 model server

This service provides the local embedding contract used by the Kotlin backend:

- `POST /encoder/bi-encoder-embed`: local SentenceTransformers embedding with a Granite OpenVINO branch
- `POST /encoder/chunk-and-embed`: sentence-aware chunks with separate title and metadata context budgets
- `POST /encoder/prepare-existing-chunks`: prepare stored chunks without changing their identity

It runs inside `python:3.13-slim`; the host Python version is irrelevant. The
model is mounted read-only from `../models` and is never downloaded at request
time. A local model loads on its first local request. API-only use does not load
local model weights. Inference is serialized by default to keep CPU and memory
usage bounded.

The same endpoints accept an OpenAI-compatible provider configuration. That
branch uses `tiktoken` for chunk limits and calls only the configured API. It
does not resolve Granite or Harrier. Unknown API model names use
`cl100k_base` for chunk-size estimation. The image caches all `tiktoken`
encoding tables at build time and does not download them at runtime.

Harrier is optional. Put its files in `/models/harrier-oss-v1-0.6b` or set
`HARRIER_MODEL_PATH`. Missing Harrier files do not stop the Granite runtime.
`EMBEDDING_MODEL_NAME` defaults to Granite. Harrier uses its mounted
SentenceTransformers artifact and the PyTorch CPU runtime.
