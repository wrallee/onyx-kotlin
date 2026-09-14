from __future__ import annotations

from pathlib import Path
from typing import Any

import pytest

from app.config import Settings
from app.contracts import ChunkEmbedRequest
from app.runtime import EmbeddingRuntime, RuntimeStatus


class CharacterTokenizer:
    def encode(self, text: str, *, add_special_tokens: bool) -> list[int]:
        tokens = [ord(character) for character in text]
        return [-1, *tokens, -2] if add_special_tokens else tokens

    def decode(self, tokens: list[int], *, skip_special_tokens: bool) -> str:
        return "".join(chr(token) for token in tokens if token >= 0)

    def __call__(self, text: str, **options: Any) -> dict[str, Any]:
        assert options["add_special_tokens"] is False
        assert options["return_offsets_mapping"] is True
        return {"offset_mapping": [(index, index + 1) for index in range(len(text))]}


@pytest.fixture
def runtime(monkeypatch) -> EmbeddingRuntime:
    value = EmbeddingRuntime(
        Settings(
            embedding_model_path=Path("/unused"),
            embedding_model_name="test-model",
            embedding_openvino_file="unused.xml",
            inference_concurrency=1,
            torch_threads=1,
        )
    )
    value.status = RuntimeStatus(True, "READY", "ready", "test-model")
    value._tokenizer = CharacterTokenizer()

    def fake_embed(
        texts: list[str],
        max_context_length: int,
        normalize_embeddings: bool,
        reduced_dimension: int | None,
        *,
        truncate: bool,
    ) -> list[list[float]]:
        assert truncate is False
        assert all(value._token_count(text) <= max_context_length for text in texts)
        return [[float(index)] for index, _ in enumerate(texts)]

    monkeypatch.setattr(value, "_embed_texts", fake_embed)
    return value


def test_chunk_and_embed_preserves_long_text_within_model_limit(runtime: EmbeddingRuntime) -> None:
    text = "가나다라마바사아자차카타파하" * 3

    chunks = runtime.chunk_and_embed(
        ChunkEmbedRequest(
            text=text,
            title="매우 긴 문서 제목",
            metadata_context="Source: jira",
            model_name="test-model",
            max_context_length=16,
        )
    )

    assert "".join(content for content, _, _ in chunks) == text
    assert len(chunks) > 1
    assert all(token_count <= 16 for _, _, token_count in chunks)
    assert [embedding for _, embedding, _ in chunks] == [
        [float(index)] for index in range(len(chunks))
    ]


def test_chunk_and_embed_keeps_sentence_boundaries_when_they_fit(runtime: EmbeddingRuntime) -> None:
    chunks = runtime.chunk_and_embed(
        ChunkEmbedRequest(
            text="First sentence. Second sentence.",
            model_name="test-model",
            max_context_length=20,
        )
    )

    assert [content for content, _, _ in chunks] == ["First sentence. ", "Second sentence."]


def test_context_keeps_metadata_before_truncating_a_long_title(runtime: EmbeddingRuntime) -> None:
    context = runtime._build_context(
        title="very long title " * 20,
        metadata_context="Repository: org/api\nStatus: open",
        max_tokens=64,
    )

    assert context.startswith("Repository: org/api\nStatus: open\nTitle:")
    assert runtime._token_count(f"{context}\n\n") <= 64


def test_chunk_and_embed_rejects_blank_text(runtime: EmbeddingRuntime) -> None:
    with pytest.raises(ValueError, match="text must not be blank"):
        runtime.chunk_and_embed(ChunkEmbedRequest(text="   ", model_name="test-model"))
