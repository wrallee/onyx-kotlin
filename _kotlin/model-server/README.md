# Python 3.13 model server

This service provides the local embedding contract used by the Kotlin backend:

- `POST /encoder/bi-encoder-embed`: Granite 311M Multilingual R2 INT8 OpenVINO

It runs inside `python:3.13-slim`; the host Python version is irrelevant. The
model is mounted read-only from `../models` and is never downloaded at request
time. Inference is serialized by default to keep CPU and memory usage bounded.
