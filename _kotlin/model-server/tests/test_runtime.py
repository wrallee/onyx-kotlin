from __future__ import annotations

import sys
from pathlib import Path
from types import ModuleType, SimpleNamespace
from typing import Any

import numpy as np
import pytest
from app.config import Settings
from app.contracts import ChunkEmbedRequest, EmbedRequest, EmbedTextType
from app.runtime import (
    HARRIER_DIMENSION,
    HARRIER_MODEL_NAME,
    HARRIER_QUERY_PREFIX,
    EmbeddingRuntime,
    RuntimeStatus,
)


class CharacterTokenizer:
    def encode(self, text: str, *, add_special_tokens: bool) -> list[int]:
        tokens = [ord(character) for character in text]
        return [-1, *tokens, -2] if add_special_tokens else tokens

    def decode(self, tokens: list[int], *, skip_special_tokens: bool) -> str:
        assert skip_special_tokens is True
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
            harrier_model_path=Path("/missing"),
            inference_concurrency=1,
            torch_threads=1,
        )
    )
    value._statuses["test-model"] = RuntimeStatus(True, "READY", "ready", "test-model")
    value._granite_tokenizer = CharacterTokenizer()

    def fake_embed(
        texts: list[str],
        model_name: str,
        max_context_length: int,
        normalize_embeddings: bool,
        reduced_dimension: int | None,
        *,
        truncate: bool,
    ) -> list[list[float]]:
        assert truncate is False
        assert model_name == "test-model"
        assert normalize_embeddings is True
        assert reduced_dimension is None
        assert all(
            value._token_count(value._granite_tokenizer, text) <= max_context_length
            for text in texts
        )
        return [[float(index)] for index, _ in enumerate(texts)]

    monkeypatch.setattr(value, "_embed_texts", fake_embed)
    return value


def test_chunk_and_embed_preserves_long_text_within_model_limit(
    runtime: EmbeddingRuntime,
) -> None:
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


def test_chunk_and_embed_keeps_sentence_boundaries_when_they_fit(
    runtime: EmbeddingRuntime,
) -> None:
    chunks = runtime.chunk_and_embed(
        ChunkEmbedRequest(
            text="First sentence. Second sentence.",
            model_name="test-model",
            max_context_length=20,
        )
    )

    assert [content for content, _, _ in chunks] == [
        "First sentence. ",
        "Second sentence.",
    ]


def test_context_keeps_metadata_before_truncating_a_long_title(
    runtime: EmbeddingRuntime,
) -> None:
    context = runtime._build_context(
        runtime._granite_tokenizer,
        title="very long title " * 20,
        metadata_context="Repository: org/api\nStatus: open",
        max_tokens=64,
    )

    assert context.startswith("Repository: org/api\nStatus: open\nTitle:")
    assert runtime._token_count(runtime._granite_tokenizer, f"{context}\n\n") <= 64


def test_chunk_and_embed_rejects_blank_text(runtime: EmbeddingRuntime) -> None:
    with pytest.raises(ValueError, match="text must not be blank"):
        runtime.chunk_and_embed(ChunkEmbedRequest(text="   ", model_name="test-model"))


def test_harrier_query_uses_official_web_search_instruction(
    runtime: EmbeddingRuntime, monkeypatch
) -> None:
    captured: list[str] = []
    runtime._statuses[HARRIER_MODEL_NAME] = RuntimeStatus(
        True, "READY", "ready", HARRIER_MODEL_NAME
    )

    def fake_embed(
        texts: list[str],
        model_name: str,
        _max_context_length: int,
        _normalize_embeddings: bool,
        _reduced_dimension: int | None,
        *,
        truncate: bool,
    ) -> list[list[float]]:
        captured.extend(texts)
        assert model_name == HARRIER_MODEL_NAME
        assert truncate is True
        return [[1.0] * HARRIER_DIMENSION]

    monkeypatch.setattr(runtime, "_embed_texts", fake_embed)

    runtime.embed(
        EmbedRequest(
            texts=["한국어 검색"],
            model_name=HARRIER_MODEL_NAME,
            max_context_length=512,
            normalize_embeddings=True,
            text_type=EmbedTextType.QUERY,
        )
    )

    assert captured == [HARRIER_QUERY_PREFIX + "한국어 검색"]


def test_harrier_last_token_pool_handles_right_padding() -> None:
    hidden = np.asarray(
        [
            [[1.0], [2.0], [99.0]],
            [[3.0], [4.0], [5.0]],
        ]
    )
    attention = np.asarray([[1, 1, 0], [1, 1, 1]])

    pooled = EmbeddingRuntime._last_token_pool(hidden, attention)

    assert pooled.tolist() == [[2.0], [5.0]]


def test_missing_harrier_files_are_reported_without_affecting_granite(
    runtime: EmbeddingRuntime,
) -> None:
    assert runtime.model_statuses()[HARRIER_MODEL_NAME].code == "UNAVAILABLE"
    assert runtime.status.ready is True

    with pytest.raises(RuntimeError, match="model files are missing"):
        runtime.embed(
            EmbedRequest(
                texts=["query"],
                model_name=HARRIER_MODEL_NAME,
                max_context_length=512,
                normalize_embeddings=True,
                text_type=EmbedTextType.QUERY,
            )
        )


def test_harrier_loader_uses_only_local_files(tmp_path: Path, monkeypatch) -> None:
    for name in ("config.json", "model.safetensors", "tokenizer.json"):
        (tmp_path / name).touch()
    value = EmbeddingRuntime(
        Settings(
            embedding_model_path=Path("/unused"),
            embedding_model_name="granite",
            embedding_openvino_file="unused.xml",
            harrier_model_path=tmp_path,
            inference_concurrency=1,
            torch_threads=2,
        )
    )
    calls: list[tuple[str, Path, dict[str, Any]]] = []

    class FakeModel:
        def eval(self) -> None:
            calls.append(("eval", tmp_path, {}))

    fake_torch = ModuleType("torch")
    fake_torch.float32 = object()
    fake_torch.set_num_threads = lambda threads: calls.append(
        ("threads", tmp_path, {"value": threads})
    )
    fake_transformers = ModuleType("transformers")
    fake_transformers.AutoTokenizer = SimpleNamespace(
        from_pretrained=lambda path, **options: (
            calls.append(("tokenizer", path, options)) or object()
        )
    )
    fake_transformers.AutoModel = SimpleNamespace(
        from_pretrained=lambda path, **options: (
            calls.append(("model", path, options)) or FakeModel()
        )
    )
    monkeypatch.setitem(sys.modules, "torch", fake_torch)
    monkeypatch.setitem(sys.modules, "transformers", fake_transformers)

    value._load_harrier()

    tokenizer_options = next(
        options for kind, _, options in calls if kind == "tokenizer"
    )
    model_options = next(options for kind, _, options in calls if kind == "model")
    assert tokenizer_options == {"local_files_only": True, "trust_remote_code": False}
    assert model_options["local_files_only"] is True
    assert model_options["trust_remote_code"] is False
    assert model_options["dtype"] is fake_torch.float32
    assert value.model_statuses()[HARRIER_MODEL_NAME].code == "READY"


def test_harrier_vectors_are_l2_normalized() -> None:
    vectors = np.zeros((1, HARRIER_DIMENSION), dtype=np.float32)
    vectors[0, :2] = [3.0, 4.0]

    embedding = EmbeddingRuntime._normalize_vectors(vectors)[0]

    assert len(embedding) == HARRIER_DIMENSION
    assert embedding[:2] == pytest.approx([0.6, 0.8])


def test_existing_chunks_keep_their_identity_when_embedding_input_is_trimmed(
    runtime: EmbeddingRuntime,
) -> None:
    from app.contracts import ExistingChunk, PrepareExistingChunksRequest

    content = "가나다라마바사아자차카타파하" * 10
    prepared = runtime.prepare_existing_chunks(
        PrepareExistingChunksRequest(
            chunks=[
                ExistingChunk(
                    content=content,
                    title="제목",
                    search_context="Source: jira",
                )
            ],
            model_name="test-model",
            max_context_length=64,
        )
    )

    assert len(prepared) == 1
    assert prepared[0][0] == content
    assert prepared[0][1].startswith("Source:")
    assert prepared[0][2] <= 64
