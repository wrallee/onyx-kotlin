from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from threading import BoundedSemaphore, Lock
from typing import Any

import numpy as np
from chonkie import SentenceChunker

from app.config import Settings
from app.contracts import (
    ChunkEmbedRequest,
    EmbedRequest,
    EmbedTextType,
    PrepareExistingChunksRequest,
)

MAX_CONTEXT_TOKENS = 128
MAX_METADATA_CONTEXT_TOKENS = 48
HARRIER_MODEL_NAME = "microsoft/harrier-oss-v1-0.6b"
HARRIER_DIMENSION = 1024
HARRIER_QUERY_PREFIX = (
    "Instruct: Given a web search query, retrieve relevant passages that answer "
    "the query\nQuery: "
)


@dataclass(frozen=True)
class RuntimeStatus:
    ready: bool
    code: str
    message: str
    model_name: str
    device: str = "CPU"


class EmbeddingRuntime:
    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self._granite_tokenizer: Any = None
        self._compiled_model: Any = None
        self._harrier_tokenizer: Any = None
        self._harrier_model: Any = None
        self._semaphore = BoundedSemaphore(settings.inference_concurrency)
        self._load_lock = Lock()
        self._statuses = {
            settings.embedding_model_name: RuntimeStatus(
                False,
                "NOT_LOADED",
                "Granite embedding runtime is not loaded.",
                settings.embedding_model_name,
            ),
            HARRIER_MODEL_NAME: self._harrier_initial_status(
                settings.harrier_model_path
            ),
        }

    @property
    def status(self) -> RuntimeStatus:
        return self._statuses[self.settings.embedding_model_name]

    def model_statuses(self) -> dict[str, RuntimeStatus]:
        return dict(self._statuses)

    def load(self) -> None:
        from openvino import Core
        from transformers import AutoTokenizer

        model_path = self.settings.embedding_model_path
        tokenizer_path = model_path / "tokenizer.json"
        openvino_path = model_path / self.settings.embedding_openvino_file
        for required in (tokenizer_path, openvino_path):
            if not required.is_file():
                raise FileNotFoundError(
                    f"Required embedding artifact is missing: {required}"
                )

        self._granite_tokenizer = AutoTokenizer.from_pretrained(
            model_path,
            local_files_only=True,
            trust_remote_code=False,
        )
        self._compiled_model = Core().compile_model(str(openvino_path), "CPU")
        self._statuses[self.settings.embedding_model_name] = RuntimeStatus(
            True,
            "READY",
            "Granite INT8 OpenVINO embedding runtime is ready.",
            self.settings.embedding_model_name,
        )

    def embed(self, request: EmbedRequest) -> list[list[float]]:
        if request.provider_type is not None:
            raise ValueError("provider_type must be null for a local embedding model.")
        if not request.texts or any(not text for text in request.texts):
            raise ValueError("texts must contain only non-empty strings.")

        model_name = self._resolve_model(request.model_name)
        prefix = None
        if (
            model_name == HARRIER_MODEL_NAME
            and request.text_type == EmbedTextType.QUERY
        ):
            prefix = HARRIER_QUERY_PREFIX
        elif request.text_type == EmbedTextType.QUERY:
            prefix = request.manual_query_prefix
        elif request.text_type == EmbedTextType.PASSAGE:
            prefix = request.manual_passage_prefix
        texts = [f"{prefix}{text}" if prefix else text for text in request.texts]

        return self._embed_texts(
            texts,
            model_name,
            request.max_context_length,
            request.normalize_embeddings,
            request.reduced_dimension,
            truncate=True,
        )

    def chunk_and_embed(
        self,
        request: ChunkEmbedRequest,
    ) -> list[tuple[str, list[float], int]]:
        model_name, prepared = self.prepare_chunks(request)
        embeddings = self._embed_texts(
            [embedding_text for _, embedding_text, _ in prepared],
            model_name,
            request.max_context_length,
            request.normalize_embeddings,
            reduced_dimension=None,
            truncate=False,
        )
        return [
            (content, embedding, token_count)
            for (content, _, token_count), embedding in zip(
                prepared, embeddings, strict=True
            )
        ]

    def prepare_chunks(
        self,
        request: ChunkEmbedRequest,
    ) -> tuple[str, list[tuple[str, str, int]]]:
        if not request.text.strip():
            raise ValueError("text must not be blank.")

        model_name = self._resolve_model(request.model_name)
        tokenizer = self._tokenizer(model_name)
        prefix = self._embedding_prefix(
            tokenizer,
            model_name,
            request.title,
            request.metadata_context,
            request.manual_passage_prefix,
            request.max_context_length,
        )
        content_limit = request.max_context_length - self._token_count(
            tokenizer, prefix
        )
        if content_limit < 1:
            raise ValueError("context leaves no tokens for chunk content.")

        splitter = SentenceChunker(
            tokenizer_or_token_counter=lambda text: self._content_token_count(
                tokenizer, text
            ),
            chunk_size=content_limit,
            chunk_overlap=0,
            return_type="texts",
        )
        candidates = list(splitter.chunk(request.text))
        contents = [
            piece
            for candidate in candidates
            for piece in self._split_to_limit(
                tokenizer, candidate, prefix, request.max_context_length
            )
            if piece.strip()
        ]
        if not contents:
            raise ValueError("text produced no non-empty chunks.")

        embedding_texts = [prefix + content for content in contents]
        token_counts = [self._token_count(tokenizer, text) for text in embedding_texts]
        if any(count > request.max_context_length for count in token_counts):
            raise RuntimeError("chunking produced text above max_context_length.")
        return model_name, list(
            zip(contents, embedding_texts, token_counts, strict=True)
        )

    def prepare_existing_chunks(
        self, request: PrepareExistingChunksRequest
    ) -> list[tuple[str, str, int]]:
        if not request.chunks or any(
            not chunk.content.strip() for chunk in request.chunks
        ):
            raise ValueError("chunks must contain only non-blank content.")
        model_name = self._resolve_model(request.model_name)
        tokenizer = self._tokenizer(model_name)
        prepared: list[tuple[str, str, int]] = []
        for chunk in request.chunks:
            prefix = self._embedding_prefix(
                tokenizer,
                model_name,
                chunk.title,
                chunk.search_context,
                request.manual_passage_prefix,
                request.max_context_length,
            )
            embedding_content = self._split_to_limit(
                tokenizer,
                chunk.content,
                prefix,
                request.max_context_length,
            )[0]
            embedding_text = prefix + embedding_content
            prepared.append(
                (
                    chunk.content,
                    embedding_text,
                    self._token_count(tokenizer, embedding_text),
                )
            )
        return prepared

    def _resolve_model(self, requested_name: str | None) -> str:
        model_name = requested_name or self.settings.embedding_model_name
        if model_name not in self._statuses:
            raise ValueError(f"Unsupported embedding model: {model_name}")
        if model_name == HARRIER_MODEL_NAME and not self._statuses[model_name].ready:
            self._load_harrier()
        status = self._statuses[model_name]
        if not status.ready:
            raise RuntimeError(status.message)
        return model_name

    def _load_harrier(self) -> None:
        with self._load_lock:
            if self._statuses[HARRIER_MODEL_NAME].ready:
                return
            if not self._harrier_files_present(self.settings.harrier_model_path):
                raise RuntimeError(self._statuses[HARRIER_MODEL_NAME].message)
            try:
                import torch
                from transformers import AutoModel, AutoTokenizer

                torch.set_num_threads(self.settings.torch_threads)
                model_path = self.settings.harrier_model_path
                self._harrier_tokenizer = AutoTokenizer.from_pretrained(
                    model_path,
                    local_files_only=True,
                    trust_remote_code=False,
                )
                self._harrier_model = AutoModel.from_pretrained(
                    model_path,
                    local_files_only=True,
                    trust_remote_code=False,
                    dtype=torch.float32,
                )
                self._harrier_model.eval()
                self._statuses[HARRIER_MODEL_NAME] = RuntimeStatus(
                    True,
                    "READY",
                    "Harrier PyTorch CPU embedding runtime is ready.",
                    HARRIER_MODEL_NAME,
                )
            except Exception as error:
                self._statuses[HARRIER_MODEL_NAME] = RuntimeStatus(
                    False,
                    "ERROR",
                    f"Harrier embedding runtime failed to load: {error}",
                    HARRIER_MODEL_NAME,
                )
                raise RuntimeError(
                    self._statuses[HARRIER_MODEL_NAME].message
                ) from error

    def _tokenizer(self, model_name: str) -> Any:
        if model_name == HARRIER_MODEL_NAME:
            return self._harrier_tokenizer
        return self._granite_tokenizer

    def _embed_texts(
        self,
        texts: list[str],
        model_name: str,
        max_context_length: int,
        normalize_embeddings: bool,
        reduced_dimension: int | None,
        *,
        truncate: bool,
    ) -> list[list[float]]:
        if model_name == HARRIER_MODEL_NAME:
            return self._embed_harrier(texts, max_context_length, truncate)

        tokenizer_options: dict[str, Any] = {
            "padding": True,
            "return_tensors": "np",
        }
        if truncate:
            tokenizer_options.update(truncation=True, max_length=max_context_length)
        encoded = self._granite_tokenizer(texts, **tokenizer_options)
        inputs = {
            "input_ids": encoded["input_ids"].astype(np.int64, copy=False),
            "attention_mask": encoded["attention_mask"].astype(np.int64, copy=False),
        }
        with self._semaphore:
            outputs = self._compiled_model(inputs)
        hidden = self._output(outputs, "last_hidden_state")
        vectors = np.asarray(hidden[:, 0, :], dtype=np.float32)
        if normalize_embeddings:
            norms = np.linalg.norm(vectors, axis=1, keepdims=True)
            if np.any(norms == 0):
                raise RuntimeError("Embedding model returned a zero vector.")
            vectors = vectors / norms
        if reduced_dimension is not None:
            vectors = vectors[:, :reduced_dimension]
        return vectors.tolist()

    def _embed_harrier(
        self, texts: list[str], max_context_length: int, truncate: bool
    ) -> list[list[float]]:
        import torch

        options: dict[str, Any] = {"padding": True, "return_tensors": "pt"}
        if truncate:
            options.update(truncation=True, max_length=max_context_length)
        encoded = self._harrier_tokenizer(texts, **options)
        with self._semaphore, torch.inference_mode():
            outputs = self._harrier_model(**encoded)
        vectors = self._last_token_pool(
            outputs.last_hidden_state, encoded["attention_mask"]
        )
        if vectors.shape[1] != HARRIER_DIMENSION:
            raise RuntimeError(
                f"Harrier returned {vectors.shape[1]} dimensions; "
                f"expected {HARRIER_DIMENSION}."
            )
        vectors_array = np.asarray(vectors.detach().float().cpu(), dtype=np.float32)
        return self._normalize_vectors(vectors_array).tolist()

    @staticmethod
    def _last_token_pool(last_hidden_states: Any, attention_mask: Any) -> Any:
        positions = [
            len(row) - 1 if row[-1] else int(sum(row)) - 1
            for row in attention_mask.tolist()
        ]
        return last_hidden_states[list(range(len(positions))), positions]

    @staticmethod
    def _normalize_vectors(vectors: np.ndarray) -> np.ndarray:
        norms = np.linalg.norm(vectors, axis=1, keepdims=True)
        if np.any(norms == 0):
            raise RuntimeError("Embedding model returned a zero vector.")
        return vectors / norms

    @staticmethod
    def _harrier_files_present(model_path: Path) -> bool:
        return all(
            (model_path / name).is_file()
            for name in ("config.json", "model.safetensors", "tokenizer.json")
        )

    @classmethod
    def _harrier_initial_status(cls, model_path: Path) -> RuntimeStatus:
        if cls._harrier_files_present(model_path):
            return RuntimeStatus(
                False,
                "AVAILABLE",
                "Harrier model files are available and will load on first use.",
                HARRIER_MODEL_NAME,
            )
        return RuntimeStatus(
            False,
            "UNAVAILABLE",
            f"Harrier model files are missing from {model_path}.",
            HARRIER_MODEL_NAME,
        )

    @staticmethod
    def _content_token_count(tokenizer: Any, text: str) -> int:
        return len(tokenizer.encode(text, add_special_tokens=False))

    @staticmethod
    def _token_count(tokenizer: Any, text: str) -> int:
        return len(tokenizer.encode(text, add_special_tokens=True))

    @staticmethod
    def _trim_to_tokens(tokenizer: Any, text: str, max_tokens: int) -> str:
        if not text or max_tokens < 1:
            return ""
        token_ids = tokenizer.encode(text, add_special_tokens=False)
        if len(token_ids) <= max_tokens:
            return text
        return tokenizer.decode(
            token_ids[:max_tokens], skip_special_tokens=True
        ).strip()

    def _build_context(
        self, tokenizer: Any, title: str, metadata_context: str, max_tokens: int
    ) -> str:
        metadata = self._fit_context(
            tokenizer,
            metadata_context.strip(),
            min(MAX_METADATA_CONTEXT_TOKENS, max_tokens),
        )
        if not title.strip():
            return metadata
        title_prefix = f"{metadata}\nTitle: " if metadata else "Title: "
        context = self._fit_context(tokenizer, title_prefix + title.strip(), max_tokens)
        return (
            context
            if context.startswith(title_prefix) and len(context) > len(title_prefix)
            else metadata
        )

    def _embedding_prefix(
        self,
        tokenizer: Any,
        model_name: str,
        title: str,
        metadata_context: str,
        manual_passage_prefix: str | None,
        max_context_length: int,
    ) -> str:
        context_limit = min(MAX_CONTEXT_TOKENS, max_context_length // 4)
        context = self._build_context(tokenizer, title, metadata_context, context_limit)
        model_prefix = (
            manual_passage_prefix
            if model_name != HARRIER_MODEL_NAME and manual_passage_prefix
            else ""
        )
        context_prefix = f"{context}\n\n" if context else ""
        return model_prefix + context_prefix

    def _fit_context(self, tokenizer: Any, text: str, max_tokens: int) -> str:
        context = self._trim_to_tokens(tokenizer, text, max_tokens)
        while context and self._token_count(tokenizer, f"{context}\n\n") > max_tokens:
            context = self._trim_to_tokens(
                tokenizer,
                context,
                self._content_token_count(tokenizer, context) - 1,
            )
        return context

    def _split_to_limit(
        self, tokenizer: Any, text: str, prefix: str, max_tokens: int
    ) -> list[str]:
        if self._token_count(tokenizer, prefix + text) <= max_tokens:
            return [text]

        offsets = tokenizer(
            text,
            add_special_tokens=False,
            return_offsets_mapping=True,
        )["offset_mapping"]
        pieces: list[str] = []
        start = 0
        offset_index = 0
        while start < len(text):
            while offset_index < len(offsets) and offsets[offset_index][1] <= start:
                offset_index += 1
            if offset_index >= len(offsets):
                if text[start:].strip():
                    pieces.append(text[start:])
                break

            content_budget = max_tokens - self._token_count(tokenizer, prefix)
            end_index = min(offset_index + content_budget, len(offsets))
            candidate_end = offsets[end_index - 1][1]
            while (
                end_index > offset_index
                and self._token_count(tokenizer, prefix + text[start:candidate_end])
                > max_tokens
            ):
                end_index -= 1
                if end_index > offset_index:
                    candidate_end = offsets[end_index - 1][1]
            if end_index <= offset_index or candidate_end <= start:
                raise ValueError(
                    "A single token does not fit within max_context_length."
                )
            pieces.append(text[start:candidate_end])
            start = candidate_end
            offset_index = end_index
        return pieces

    @staticmethod
    def _output(outputs: Any, name: str) -> np.ndarray:
        for key, value in outputs.items():
            if getattr(key, "any_name", None) == name:
                return np.asarray(value)
        if len(outputs) != 1:
            raise RuntimeError(f"OpenVINO output {name} was not found.")
        return np.asarray(next(iter(outputs.values())))
