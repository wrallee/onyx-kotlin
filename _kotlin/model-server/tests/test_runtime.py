from __future__ import annotations

import sys
from pathlib import Path
from types import ModuleType
from typing import Any

import pytest
from app.config import Settings
from app.contracts import ChunkEmbedRequest, EmbedRequest, EmbedTextType
from app.runtime import (
    GRANITE_MODEL_NAME,
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
            embedding_openvino_file="unused.xml",
            harrier_model_path=Path("/missing"),
            inference_concurrency=1,
            torch_threads=1,
        )
    )
    value._statuses[GRANITE_MODEL_NAME] = RuntimeStatus(
        True, "READY", "ready", GRANITE_MODEL_NAME
    )
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
        assert model_name == GRANITE_MODEL_NAME
        assert normalize_embeddings is True
        assert reduced_dimension is None
        assert truncate is False
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
            model_name=GRANITE_MODEL_NAME,
            max_context_length=16,
        )
    )

    assert "".join(content for content, _, _ in chunks) == text
    assert len(chunks) > 1
    assert all(token_count <= 16 for _, _, token_count in chunks)


def test_harrier_query_uses_official_web_search_instruction(
    runtime: EmbeddingRuntime, monkeypatch
) -> None:
    runtime._statuses[HARRIER_MODEL_NAME] = RuntimeStatus(
        True, "READY", "ready", HARRIER_MODEL_NAME
    )
    runtime._models[HARRIER_MODEL_NAME] = type(
        "Model", (), {"tokenizer": CharacterTokenizer()}
    )()
    captured: list[str] = []

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
        return [[1.0]]

    monkeypatch.setattr(runtime, "_embed_texts", fake_embed)
    runtime.embed(
        EmbedRequest(
            texts=["한국어 검색"],
            model_name=HARRIER_MODEL_NAME,
            text_type=EmbedTextType.QUERY,
        )
    )

    assert captured == [HARRIER_QUERY_PREFIX + "한국어 검색"]


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
                text_type=EmbedTextType.QUERY,
            )
        )


def test_harrier_loader_uses_only_local_files(tmp_path: Path, monkeypatch) -> None:
    (tmp_path / "1_Pooling").mkdir()
    for name in (
        "config.json",
        "model.safetensors",
        "tokenizer.json",
        "modules.json",
        "1_Pooling/config.json",
    ):
        (tmp_path / name).touch()
    value = EmbeddingRuntime(
        Settings(
            embedding_model_path=Path("/unused"),
            embedding_openvino_file="unused.xml",
            harrier_model_path=tmp_path,
            inference_concurrency=1,
            torch_threads=2,
        )
    )
    calls: list[tuple[str, Path | str, dict[str, Any]]] = []

    fake_torch = ModuleType("torch")
    fake_torch.set_num_threads = lambda threads: calls.append(
        ("threads", tmp_path, {"value": threads})
    )
    fake_sentence_transformers = ModuleType("sentence_transformers")

    class FakeSentenceTransformer:
        def __init__(self, **options: Any) -> None:
            calls.append(("model", options.pop("model_name_or_path"), options))
            self.tokenizer = CharacterTokenizer()

    fake_sentence_transformers.SentenceTransformer = FakeSentenceTransformer
    monkeypatch.setitem(sys.modules, "torch", fake_torch)
    monkeypatch.setitem(
        sys.modules, "sentence_transformers", fake_sentence_transformers
    )

    value._load_model(HARRIER_MODEL_NAME)

    _, model_path, model_options = next(call for call in calls if call[0] == "model")
    assert model_path == str(tmp_path)
    assert model_options == {
        "local_files_only": True,
        "trust_remote_code": False,
        "device": "cpu",
    }


def test_existing_chunks_keep_their_identity_when_embedding_input_is_trimmed(
    runtime: EmbeddingRuntime,
) -> None:
    from app.contracts import ExistingChunk, PrepareExistingChunksRequest

    content = "가나다라마바사아자차카타파하" * 10
    prepared = runtime.prepare_existing_chunks(
        PrepareExistingChunksRequest(
            chunks=[
                ExistingChunk(
                    content=content, title="제목", search_context="Source: jira"
                )
            ],
            model_name=GRANITE_MODEL_NAME,
            max_context_length=64,
        )
    )

    assert prepared[0][0] == content
    assert prepared[0][1].startswith("Source:")
    assert prepared[0][2] <= 64
