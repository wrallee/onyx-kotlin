from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from threading import BoundedSemaphore
from typing import Any

from chonkie import SentenceChunker
import numpy as np

from app.config import Settings
from app.contracts import ChunkEmbedRequest, EmbedRequest, EmbedTextType

MAX_CONTEXT_TOKENS = 128
MAX_METADATA_CONTEXT_TOKENS = 48


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
        self._tokenizer: Any = None
        self._compiled_model: Any = None
        self._semaphore = BoundedSemaphore(settings.inference_concurrency)
        self.status = RuntimeStatus(
            False,
            "NOT_LOADED",
            "Granite embedding runtime is not loaded.",
            settings.embedding_model_name,
        )

    def load(self) -> None:
        from openvino import Core
        from transformers import AutoTokenizer

        model_path = self.settings.embedding_model_path
        tokenizer_path = model_path / "tokenizer.json"
        openvino_path = model_path / self.settings.embedding_openvino_file
        for required in (tokenizer_path, openvino_path):
            if not required.is_file():
                raise FileNotFoundError(f"Required embedding artifact is missing: {required}")

        self._tokenizer = AutoTokenizer.from_pretrained(
            model_path,
            local_files_only=True,
            trust_remote_code=False,
        )
        self._compiled_model = Core().compile_model(str(openvino_path), "CPU")
        self.status = RuntimeStatus(
            True,
            "READY",
            "Granite INT8 OpenVINO embedding runtime is ready.",
            self.settings.embedding_model_name,
        )

    def embed(self, request: EmbedRequest) -> list[list[float]]:
        self._validate_model(request.model_name)
        if request.provider_type is not None:
            raise ValueError("provider_type must be null for a local embedding model.")
        if not request.texts or any(not text for text in request.texts):
            raise ValueError("texts must contain only non-empty strings.")

        prefix = None
        if request.text_type == EmbedTextType.QUERY:
            prefix = request.manual_query_prefix
        elif request.text_type == EmbedTextType.PASSAGE:
            prefix = request.manual_passage_prefix
        texts = [f"{prefix}{text}" if prefix else text for text in request.texts]

        return self._embed_texts(
            texts,
            request.max_context_length,
            request.normalize_embeddings,
            request.reduced_dimension,
            truncate=True,
        )

    def chunk_and_embed(
        self,
        request: ChunkEmbedRequest,
    ) -> list[tuple[str, list[float], int]]:
        self._validate_model(request.model_name)
        if not request.text.strip():
            raise ValueError("text must not be blank.")

        context_limit = min(MAX_CONTEXT_TOKENS, request.max_context_length // 4)
        context = self._build_context(request.title, request.metadata_context, context_limit)
        prefix = f"{context}\n\n" if context else ""
        content_limit = request.max_context_length - self._token_count(prefix)
        if content_limit < 1:
            raise ValueError("context leaves no tokens for chunk content.")

        splitter = SentenceChunker(
            tokenizer_or_token_counter=self._content_token_count,
            chunk_size=content_limit,
            chunk_overlap=0,
            return_type="texts",
        )
        candidates = list(splitter.chunk(request.text))
        contents = [
            piece
            for candidate in candidates
            for piece in self._split_to_limit(candidate, prefix, request.max_context_length)
            if piece.strip()
        ]
        if not contents:
            raise ValueError("text produced no non-empty chunks.")

        embedding_texts = [prefix + content for content in contents]
        token_counts = [self._token_count(text) for text in embedding_texts]
        if any(count > request.max_context_length for count in token_counts):
            raise RuntimeError("chunking produced text above max_context_length.")
        embeddings = self._embed_texts(
            embedding_texts,
            request.max_context_length,
            request.normalize_embeddings,
            reduced_dimension=None,
            truncate=False,
        )
        return list(zip(contents, embeddings, token_counts, strict=True))

    def _validate_model(self, model_name: str | None) -> None:
        if not self.status.ready:
            raise RuntimeError(self.status.message)
        if model_name not in (None, self.settings.embedding_model_name):
            raise ValueError(f"Unsupported embedding model: {model_name}")

    def _embed_texts(
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
        encoded = self._tokenizer(texts, **tokenizer_options)
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

    def _content_token_count(self, text: str) -> int:
        return len(self._tokenizer.encode(text, add_special_tokens=False))

    def _token_count(self, text: str) -> int:
        return len(self._tokenizer.encode(text, add_special_tokens=True))

    def _trim_to_tokens(self, text: str, max_tokens: int) -> str:
        if not text or max_tokens < 1:
            return ""
        token_ids = self._tokenizer.encode(text, add_special_tokens=False)
        if len(token_ids) <= max_tokens:
            return text
        return self._tokenizer.decode(token_ids[:max_tokens], skip_special_tokens=True).strip()

    def _build_context(self, title: str, metadata_context: str, max_tokens: int) -> str:
        metadata = self._fit_context(
            metadata_context.strip(),
            min(MAX_METADATA_CONTEXT_TOKENS, max_tokens),
        )
        if not title.strip():
            return metadata
        title_prefix = f"{metadata}\nTitle: " if metadata else "Title: "
        context = self._fit_context(title_prefix + title.strip(), max_tokens)
        return context if context.startswith(title_prefix) and len(context) > len(title_prefix) else metadata

    def _fit_context(self, text: str, max_tokens: int) -> str:
        context = self._trim_to_tokens(text, max_tokens)
        while context and self._token_count(f"{context}\n\n") > max_tokens:
            context = self._trim_to_tokens(context, self._content_token_count(context) - 1)
        return context

    def _split_to_limit(self, text: str, prefix: str, max_tokens: int) -> list[str]:
        if self._token_count(prefix + text) <= max_tokens:
            return [text]

        offsets = self._tokenizer(
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

            content_budget = max_tokens - self._token_count(prefix)
            end_index = min(offset_index + content_budget, len(offsets))
            candidate_end = offsets[end_index - 1][1]
            while (
                end_index > offset_index
                and self._token_count(prefix + text[start:candidate_end]) > max_tokens
            ):
                end_index -= 1
                if end_index > offset_index:
                    candidate_end = offsets[end_index - 1][1]
            if end_index <= offset_index or candidate_end <= start:
                raise ValueError("A single token does not fit within max_context_length.")
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
