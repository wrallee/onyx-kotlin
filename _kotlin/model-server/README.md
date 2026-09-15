# Python 3.13 model server

This service provides the local embedding contract used by the Kotlin backend:

- `POST /encoder/bi-encoder-embed`: Granite 311M INT8 OpenVINO or Harrier 0.6B PyTorch CPU
- `POST /encoder/chunk-and-embed`: sentence-aware chunks with separate title and metadata context budgets

It runs inside `python:3.13-slim`; the host Python version is irrelevant. The
model is mounted read-only from `../models` and is never downloaded at request
time. Inference is serialized by default to keep CPU and memory usage bounded.

Harrier is optional. Put its files in `/models/harrier-oss-v1-0.6b` or set
`HARRIER_MODEL_PATH`. Missing Harrier files do not stop the Granite runtime.
