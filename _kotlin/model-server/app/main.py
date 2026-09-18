from __future__ import annotations

import logging
import time
from contextlib import asynccontextmanager
from typing import Any

from fastapi import FastAPI, Response
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import JSONResponse
from prometheus_client import (
    CONTENT_TYPE_LATEST,
    Counter,
    Gauge,
    Histogram,
    generate_latest,
)

from app.config import Settings
from app.contracts import (
    ApiError,
    ChunkEmbedRequest,
    ChunkEmbedResponse,
    ChunkResponse,
    EmbeddedChunk,
    EmbedRequest,
    EmbedResponse,
    PreparedChunk,
    PrepareExistingChunksRequest,
)
from app.runtime import EmbeddingRuntime


class HealthCheckFilter(logging.Filter):
    EXCLUDED_PREFIXES = (
        "/actuator/health",
        "/api/health",
        "/metrics",
    )

    def filter(self, record: logging.LogRecord) -> bool:
        if isinstance(record.args, tuple) and len(record.args) >= 3:
            path = str(record.args[2]).split("?")[0]
            return not any(path.startswith(prefix) for prefix in self.EXCLUDED_PREFIXES)
        msg = record.getMessage()
        return not any(prefix in msg for prefix in self.EXCLUDED_PREFIXES)


def configure_logging(current_settings: Settings) -> None:
    access_logger = logging.getLogger("uvicorn.access")
    if not current_settings.access_log:
        access_logger.disabled = True
    else:
        access_logger.disabled = False
        if not current_settings.access_log_healthchecks:
            if not any(isinstance(f, HealthCheckFilter) for f in access_logger.filters):
                access_logger.addFilter(HealthCheckFilter())
        else:
            for f in list(access_logger.filters):
                if isinstance(f, HealthCheckFilter):
                    access_logger.removeFilter(f)


REQUESTS = Counter(
    "onyx_model_server_requests_total",
    "Model server requests",
    ("operation", "status"),
)
LATENCY = Histogram(
    "onyx_model_server_request_seconds",
    "Model server request latency",
    ("operation",),
)
READY = Gauge(
    "onyx_model_server_ready",
    "Model readiness",
    ("model",),
)

settings = Settings.from_environment()
embedding_runtime = EmbeddingRuntime(settings)
startup_errors: dict[str, str] = {}
configure_logging(settings)


@asynccontextmanager
async def lifespan(_: FastAPI):
    configure_logging(settings)
    READY.labels("embedding").set(1)
    try:
        yield
    finally:
        READY.labels("embedding").set(0)


app = FastAPI(title="Onyx Python Model Server", version="1.0.0", lifespan=lifespan)


@app.exception_handler(ValueError)
async def invalid_request(_: Any, error: ValueError):
    return JSONResponse(
        status_code=400,
        content=ApiError(code="INVALID_REQUEST", message=str(error)).model_dump(),
    )


@app.exception_handler(RuntimeError)
async def runtime_unavailable(_: Any, error: RuntimeError):
    return JSONResponse(
        status_code=503,
        content=ApiError(
            code="MODEL_RUNTIME_UNAVAILABLE", message=str(error)
        ).model_dump(),
    )


@app.get("/api/health", status_code=200)
def health() -> None:
    return None


@app.get("/api/gpu-status")
def gpu_status() -> dict[str, Any]:
    return {"gpu_available": False, "type": "NONE"}


@app.get("/api/model-status")
def model_status() -> dict[str, Any]:
    return {
        "embedding": embedding_runtime.status.__dict__,
        "models": {
            name: status.__dict__
            for name, status in embedding_runtime.model_statuses().items()
        },
        "startup_errors": startup_errors,
    }


@app.get("/actuator/health/readiness")
def readiness() -> JSONResponse:
    return JSONResponse(
        status_code=200,
        content={
            "status": "UP",
            "components": {
                "embedding": embedding_runtime.status.__dict__,
            },
        },
    )


@app.get("/metrics")
def metrics() -> Response:
    return Response(generate_latest(), media_type=CONTENT_TYPE_LATEST)


@app.post("/encoder/bi-encoder-embed", response_model=EmbedResponse)
async def embed(request: EmbedRequest) -> EmbedResponse:
    started = time.perf_counter()
    try:
        embeddings = await run_in_threadpool(embedding_runtime.embed, request)
        REQUESTS.labels("embed", "success").inc()
        return EmbedResponse(embeddings=embeddings)
    except Exception:
        REQUESTS.labels("embed", "error").inc()
        raise
    finally:
        LATENCY.labels("embed").observe(time.perf_counter() - started)


@app.post("/encoder/chunk-and-embed", response_model=ChunkEmbedResponse)
async def chunk_and_embed(request: ChunkEmbedRequest) -> ChunkEmbedResponse:
    started = time.perf_counter()
    try:
        chunks = await run_in_threadpool(embedding_runtime.chunk_and_embed, request)
        REQUESTS.labels("chunk_embed", "success").inc()
        return ChunkEmbedResponse(
            chunks=[
                EmbeddedChunk(
                    content=content,
                    embedding=embedding,
                    token_count=token_count,
                )
                for content, embedding, token_count in chunks
            ]
        )
    except Exception:
        REQUESTS.labels("chunk_embed", "error").inc()
        raise
    finally:
        LATENCY.labels("chunk_embed").observe(time.perf_counter() - started)


@app.post("/encoder/chunk", response_model=ChunkResponse)
async def chunk(request: ChunkEmbedRequest) -> ChunkResponse:
    _, chunks = await run_in_threadpool(embedding_runtime.prepare_chunks, request)
    return ChunkResponse(
        chunks=[
            PreparedChunk(
                content=content,
                embedding_text=embedding_text,
                token_count=token_count,
            )
            for content, embedding_text, token_count in chunks
        ]
    )


@app.post("/encoder/prepare-existing-chunks", response_model=ChunkResponse)
async def prepare_existing_chunks(
    request: PrepareExistingChunksRequest,
) -> ChunkResponse:
    chunks = await run_in_threadpool(embedding_runtime.prepare_existing_chunks, request)
    return ChunkResponse(
        chunks=[
            PreparedChunk(
                content=content,
                embedding_text=embedding_text,
                token_count=token_count,
            )
            for content, embedding_text, token_count in chunks
        ]
    )
