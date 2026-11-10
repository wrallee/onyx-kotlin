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
    embedding_openvino_file: str
    harrier_model_path: Path
    inference_concurrency: int
    torch_threads: int
    access_log: bool = False
    access_log_healthchecks: bool = False

    @classmethod
    def from_environment(cls) -> "Settings":
        return cls(
            embedding_model_path=Path(
                os.getenv(
                    "EMBEDDING_MODEL_PATH",
                    "/models/granite-embedding-311m-multilingual-r2-int8-openvino",
                )
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
            access_log=_boolean("MODEL_SERVER_ACCESS_LOG", False),
            access_log_healthchecks=_boolean("MODEL_SERVER_ACCESS_LOG_HEALTHCHECKS", False),
        )
