from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path


def _boolean(name: str, default: bool) -> bool:
    value = os.getenv(name)
    if value is None:
        return default
    return value.strip().lower() in {"1", "true", "yes", "on"}


@dataclass(frozen=True)
class Settings:
    embedding_model_path: Path
    embedding_model_name: str
    embedding_openvino_file: str
    harrier_model_path: Path
    inference_concurrency: int
    torch_threads: int
    provider_connect_timeout_seconds: float = 30.0
    provider_read_timeout_seconds: float = 600.0

    @classmethod
    def from_environment(cls) -> "Settings":
        return cls(
            embedding_model_path=Path(
                os.getenv(
                    "EMBEDDING_MODEL_PATH",
                    "/models/granite-embedding-311m-multilingual-r2-int8-openvino",
                )
            ),
            embedding_model_name=os.getenv(
                "EMBEDDING_MODEL_NAME",
                "ibm-granite/granite-embedding-311m-multilingual-r2",
            ),
            embedding_openvino_file=os.getenv(
                "EMBEDDING_OPENVINO_FILE",
                "openvino/openvino_model_qint8_quantized.xml",
            ),
            harrier_model_path=Path(
                os.getenv("HARRIER_MODEL_PATH", "/models/harrier-oss-v1-0.6b")
            ),
            inference_concurrency=max(
                1, int(os.getenv("MODEL_INFERENCE_CONCURRENCY", "1"))
            ),
            torch_threads=max(1, int(os.getenv("TORCH_NUM_THREADS", "4"))),
            provider_connect_timeout_seconds=max(
                0.001,
                int(os.getenv("MODEL_SERVER_CONNECT_TIMEOUT_MS", "30000")) / 1000,
            ),
            provider_read_timeout_seconds=max(
                0.001,
                int(os.getenv("MODEL_SERVER_READ_TIMEOUT_MS", "600000")) / 1000,
            ),
        )
