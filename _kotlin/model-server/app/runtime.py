from __future__ import annotations

import json
import math
import time
from dataclasses import dataclass
from pathlib import Path
from threading import BoundedSemaphore, Lock
from typing import Any
from urllib.parse import urlparse

import httpx
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
GRANITE_MODEL_NAME = "ibm-granite/granite-embedding-311m-multilingual-r2"
HARRIER_MODEL_NAME = "microsoft/harrier-oss-v1-0.6b"
HARRIER_QUERY_PREFIX = (
    "Instruct: Given a web search query, retrieve relevant passages that answer "
    "the query\nQuery: "
)
OPENAI_COMPATIBLE_PROVIDER = "openai_compatible"
MAX_PROVIDER_RESPONSE_BYTES = 16 * 1024 * 1024


class ApiTokenizer:
    def __init__(self, encoding: Any) -> None:
        self.encoding = encoding

    def encode(self, text: str, *, add_special_tokens: bool) -> list[int]:
        _ = add_special_tokens
        return self.encoding.encode_ordinary(text)


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
        self._models: dict[str, Any] = {}
        self._provider_tokenizers: dict[str, ApiTokenizer] = {}
        self._http_client: httpx.Client | None = None
        self._semaphore = BoundedSemaphore(settings.inference_concurrency)
        self._load_lock = Lock()
        if settings.embedding_model_name not in {
            GRANITE_MODEL_NAME,
            HARRIER_MODEL_NAME,
        }:
            raise ValueError(
                f"Unsupported embedding model: {settings.embedding_model_name}"
            )
        self._statuses = {
            GRANITE_MODEL_NAME: RuntimeStatus(
                False,
                "NOT_LOADED",
                "Granite embedding runtime is not loaded.",
                GRANITE_MODEL_NAME,
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
        self._load_model(self.settings.embedding_model_name)

    def close(self) -> None:
        if self._http_client is not None:
            self._http_client.close()
            self._http_client = None

    def _load_model(self, model_name: str) -> None:
        if model_name not in self._statuses:
            raise ValueError(f"Unsupported embedding model: {model_name}")

        with self._load_lock:
            if self._statuses[model_name].ready:
                return
            if model_name == HARRIER_MODEL_NAME and not self._harrier_files_present(
                self.settings.harrier_model_path
            ):
                raise RuntimeError(self._statuses[model_name].message)
            try:
                if model_name == GRANITE_MODEL_NAME:
                    self._load_granite()
                    message = "Granite INT8 OpenVINO embedding runtime is ready."
                else:
                    self._load_sentence_transformer(model_name)
                    message = "Harrier SentenceTransformers CPU runtime is ready."
                self._statuses[model_name] = RuntimeStatus(
                    True, "READY", message, model_name
                )
            except Exception as error:
                self._statuses[model_name] = RuntimeStatus(
                    False,
                    "ERROR",
                    f"{model_name} embedding runtime failed to load: {error}",
                    model_name,
                )
                raise RuntimeError(self._statuses[model_name].message) from error

    def _load_granite(self) -> None:
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

    def _load_sentence_transformer(self, model_name: str) -> None:
        import torch
        from sentence_transformers import SentenceTransformer

        torch.set_num_threads(self.settings.torch_threads)
        self._models[model_name] = SentenceTransformer(
            model_name_or_path=str(self.settings.harrier_model_path),
            local_files_only=True,
            trust_remote_code=False,
            device="cpu",
        )

    def embed(self, request: EmbedRequest) -> list[list[float]]:
        if not request.texts or any(not text for text in request.texts):
            raise ValueError("texts must contain only non-empty strings.")

        if request.provider_type is not None:
            self._validate_provider(request)
            prefix = (
                request.manual_query_prefix
                if request.text_type == EmbedTextType.QUERY
                else request.manual_passage_prefix
            )
            texts = [f"{prefix}{text}" if prefix else text for text in request.texts]
            return self._remote_embeddings(texts, request)

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
        embedding_texts = [embedding_text for _, embedding_text, _ in prepared]
        if request.provider_type is not None:
            embeddings = self._remote_embeddings(embedding_texts, request)
        else:
            embeddings = self._embed_texts(
                embedding_texts,
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

        if request.provider_type is None:
            model_name = self._resolve_model(request.model_name)
            tokenizer = self._tokenizer(model_name)
        else:
            self._validate_provider(request)
            model_name = request.model_name or ""
            tokenizer = self._provider_tokenizer(model_name)
        prefix = self._embedding_prefix(
            tokenizer,
            model_name,
            request.title,
            request.metadata_context,
            request.manual_passage_prefix,
            request.max_context_length,
            provider=request.provider_type is not None,
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
        if request.provider_type is None:
            model_name = self._resolve_model(request.model_name)
            tokenizer = self._tokenizer(model_name)
        else:
            self._validate_provider(request)
            model_name = request.model_name or ""
            tokenizer = self._provider_tokenizer(model_name)
        prepared: list[tuple[str, str, int]] = []
        for chunk in request.chunks:
            prefix = self._embedding_prefix(
                tokenizer,
                model_name,
                chunk.title,
                chunk.search_context,
                request.manual_passage_prefix,
                request.max_context_length,
                provider=request.provider_type is not None,
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
        if not self._statuses[model_name].ready:
            self._load_model(model_name)
        status = self._statuses[model_name]
        if not status.ready:
            raise RuntimeError(status.message)
        return model_name

    def _tokenizer(self, model_name: str) -> Any:
        if model_name == GRANITE_MODEL_NAME:
            return self._granite_tokenizer
        return self._models[model_name].tokenizer

    def _provider_tokenizer(self, model_name: str) -> ApiTokenizer:
        if model_name not in self._provider_tokenizers:
            import tiktoken

            try:
                encoding = tiktoken.encoding_for_model(model_name)
            except KeyError:
                encoding = tiktoken.get_encoding("cl100k_base")
            self._provider_tokenizers[model_name] = ApiTokenizer(encoding)
        return self._provider_tokenizers[model_name]

    @staticmethod
    def _validate_provider(
        request: EmbedRequest | ChunkEmbedRequest | PrepareExistingChunksRequest,
    ) -> None:
        if request.provider_type != OPENAI_COMPATIBLE_PROVIDER:
            raise ValueError(f"Unsupported embedding provider: {request.provider_type}")
        if not request.model_name or not request.model_name.strip():
            raise ValueError("model_name is required for an embedding provider.")
        if not request.api_url:
            raise ValueError("api_url is required for an embedding provider.")
        try:
            parsed = urlparse(request.api_url)
            hostname = parsed.hostname
        except ValueError as error:
            raise ValueError("api_url must be an absolute HTTP(S) URL.") from error
        if (
            parsed.scheme.lower() not in {"http", "https"}
            or not hostname
            or parsed.username is not None
            or parsed.password is not None
            or parsed.fragment
        ):
            raise ValueError("api_url must be an absolute HTTP(S) URL.")

    def _provider_client(self) -> httpx.Client:
        if self._http_client is None:
            self._http_client = httpx.Client(
                follow_redirects=False,
                timeout=httpx.Timeout(
                    self.settings.provider_read_timeout_seconds,
                    connect=self.settings.provider_connect_timeout_seconds,
                ),
            )
        return self._http_client

    def _remote_embeddings(
        self,
        texts: list[str],
        request: EmbedRequest | ChunkEmbedRequest,
    ) -> list[list[float]]:
        self._validate_provider(request)
        headers = (
            {"Authorization": f"Bearer {request.api_key}"} if request.api_key else {}
        )
        try:
            with self._provider_client().stream(
                "POST",
                request.api_url or "",
                headers=headers,
                json={
                    "input": texts,
                    "model": request.model_name,
                    "encoding_format": "float",
                },
            ) as response:
                if response.status_code == 429 or response.status_code >= 500:
                    raise RuntimeError(
                        f"Embedding provider returned HTTP {response.status_code}."
                    )
                if response.status_code >= 300:
                    raise ValueError(
                        f"Embedding provider returned HTTP {response.status_code}."
                    )
                body = bytearray()
                for part in response.iter_bytes():
                    body.extend(part)
                    if len(body) > MAX_PROVIDER_RESPONSE_BYTES:
                        raise RuntimeError("Embedding provider response is too large.")
        except httpx.HTTPError as error:
            raise RuntimeError("Embedding provider request failed.") from error

        try:
            payload = json.loads(body)
            data = payload["data"]
            if not isinstance(data, list) or len(data) != len(texts):
                raise ValueError
            indexed: dict[int, list[float]] = {}
            for item in data:
                index = item["index"]
                vector = item["embedding"]
                if (
                    not isinstance(index, int)
                    or isinstance(index, bool)
                    or index in indexed
                    or not isinstance(vector, list)
                    or not vector
                    or any(
                        not isinstance(value, (int, float))
                        or isinstance(value, bool)
                        or not math.isfinite(value)
                        for value in vector
                    )
                ):
                    raise ValueError
                indexed[index] = [float(value) for value in vector]
            if set(indexed) != set(range(len(texts))):
                raise ValueError
            vectors = [indexed[index] for index in range(len(texts))]
        except (KeyError, TypeError, ValueError, json.JSONDecodeError) as error:
            raise RuntimeError(
                "Embedding provider returned an invalid response."
            ) from error

        if request.normalize_embeddings:
            normalized: list[list[float]] = []
            for vector in vectors:
                norm = math.sqrt(sum(value * value for value in vector))
                if norm == 0 or not math.isfinite(norm):
                    raise RuntimeError("Embedding provider returned a zero vector.")
                normalized.append([value / norm for value in vector])
            return normalized
        return vectors

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
        if model_name == GRANITE_MODEL_NAME:
            return self._embed_openvino(
                texts,
                max_context_length,
                normalize_embeddings,
                reduced_dimension,
                truncate=truncate,
            )
        return self._embed_sentence_transformer(
            texts,
            model_name,
            max_context_length,
            normalize_embeddings,
            reduced_dimension,
        )

    def _embed_openvino(
        self,
        texts: list[str],
        max_context_length: int,
        normalize_embeddings: bool,
        reduced_dimension: int | None,
        *,
        truncate: bool,
    ) -> list[list[float]]:
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

    def _embed_sentence_transformer(
        self,
        texts: list[str],
        model_name: str,
        max_context_length: int,
        normalize_embeddings: bool,
        reduced_dimension: int | None,
    ) -> list[list[float]]:
        model = self._models[model_name]
        model.max_seq_length = max_context_length
        for _ in range(3):
            try:
                with self._semaphore:
                    vectors = model.encode(
                        texts,
                        normalize_embeddings=normalize_embeddings,
                    )
                break
            except RuntimeError:
                time.sleep(0.1)
        else:
            with self._semaphore:
                vectors = model.encode(
                    texts,
                    normalize_embeddings=normalize_embeddings,
                )
        vectors_array = np.asarray(vectors, dtype=np.float32)
        if reduced_dimension is not None:
            vectors_array = vectors_array[:, :reduced_dimension]
        return vectors_array.tolist()

    @staticmethod
    def _harrier_files_present(model_path: Path) -> bool:
        return all(
            (model_path / name).is_file()
            for name in (
                "config.json",
                "model.safetensors",
                "tokenizer.json",
                "modules.json",
                "1_Pooling/config.json",
            )
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

    @classmethod
    def _trim_to_tokens(cls, tokenizer: Any, text: str, max_tokens: int) -> str:
        if not text or max_tokens < 1:
            return ""
        if not isinstance(tokenizer, ApiTokenizer):
            token_ids = tokenizer.encode(text, add_special_tokens=False)
            if len(token_ids) <= max_tokens:
                return text
            return tokenizer.decode(
                token_ids[:max_tokens], skip_special_tokens=True
            ).strip()
        if cls._content_token_count(tokenizer, text) <= max_tokens:
            return text
        low = 0
        high = len(text)
        while low < high:
            middle = (low + high + 1) // 2
            if cls._content_token_count(tokenizer, text[:middle]) <= max_tokens:
                low = middle
            else:
                high = middle - 1
        return text[:low].strip()

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
        *,
        provider: bool = False,
    ) -> str:
        context_limit = min(MAX_CONTEXT_TOKENS, max_context_length // 4)
        context = self._build_context(tokenizer, title, metadata_context, context_limit)
        model_prefix = (
            manual_passage_prefix
            if (provider or model_name != HARRIER_MODEL_NAME) and manual_passage_prefix
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
        if isinstance(tokenizer, ApiTokenizer):
            return self._split_api_to_limit(tokenizer, text, prefix, max_tokens)

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

    def _split_api_to_limit(
        self, tokenizer: ApiTokenizer, text: str, prefix: str, max_tokens: int
    ) -> list[str]:
        pieces: list[str] = []
        start = 0
        while start < len(text):
            low = start + 1
            high = len(text)
            candidate_end = start
            while low <= high:
                middle = (low + high) // 2
                if (
                    self._token_count(tokenizer, prefix + text[start:middle])
                    <= max_tokens
                ):
                    candidate_end = middle
                    low = middle + 1
                else:
                    high = middle - 1
            if candidate_end <= start:
                raise ValueError(
                    "A single token does not fit within max_context_length."
                )
            pieces.append(text[start:candidate_end])
            start = candidate_end
        return pieces

    @staticmethod
    def _output(outputs: Any, name: str) -> np.ndarray:
        for key, value in outputs.items():
            if getattr(key, "any_name", None) == name:
                return np.asarray(value)
        if len(outputs) != 1:
            raise RuntimeError(f"OpenVINO output {name} was not found.")
        return np.asarray(next(iter(outputs.values())))
