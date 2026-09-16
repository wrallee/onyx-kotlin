from __future__ import annotations

import json
import sys
from pathlib import Path
from types import ModuleType
from typing import Any

import httpx
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
            embedding_model_name=GRANITE_MODEL_NAME,
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
        assert truncate is False
        assert model_name == GRANITE_MODEL_NAME
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
            model_name=GRANITE_MODEL_NAME,
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
            model_name=GRANITE_MODEL_NAME,
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
        runtime.chunk_and_embed(
            ChunkEmbedRequest(text="   ", model_name=GRANITE_MODEL_NAME)
        )


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
        return [[1.0]]

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


def test_harrier_default_does_not_replace_granite_status() -> None:
    runtime = EmbeddingRuntime(
        Settings(
            embedding_model_path=Path("/unused"),
            embedding_model_name=HARRIER_MODEL_NAME,
            embedding_openvino_file="unused.xml",
            harrier_model_path=Path("/missing"),
            inference_concurrency=1,
            torch_threads=1,
        )
    )

    assert runtime.status.model_name == HARRIER_MODEL_NAME
    assert set(runtime.model_statuses()) == {GRANITE_MODEL_NAME, HARRIER_MODEL_NAME}


def test_environment_defaults_to_granite(monkeypatch) -> None:
    monkeypatch.delenv("EMBEDDING_MODEL_NAME", raising=False)

    assert Settings.from_environment().embedding_model_name == GRANITE_MODEL_NAME


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
            embedding_model_name=GRANITE_MODEL_NAME,
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
            self.tokenizer = object()

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
    assert ("threads", tmp_path, {"value": 2}) in calls
    assert value._models[HARRIER_MODEL_NAME].tokenizer is not None
    assert value.model_statuses()[HARRIER_MODEL_NAME].code == "READY"


def test_harrier_uses_sentence_transformer_encode_and_retries(
    runtime: EmbeddingRuntime, monkeypatch
) -> None:
    calls: list[tuple[list[str], bool]] = []
    sleeps: list[float] = []

    class FakeModel:
        max_seq_length = 0

        def encode(
            self, texts: list[str], *, normalize_embeddings: bool
        ) -> list[list[float]]:
            calls.append((texts, normalize_embeddings))
            if len(calls) == 1:
                raise RuntimeError("Already borrowed")
            return [[3.0, 4.0]]

    model = FakeModel()
    runtime._models[HARRIER_MODEL_NAME] = model
    monkeypatch.setattr("app.runtime.time.sleep", sleeps.append)

    result = EmbeddingRuntime._embed_texts(
        runtime,
        ["query"],
        HARRIER_MODEL_NAME,
        512,
        True,
        1,
        truncate=True,
    )

    assert result == [[3.0]]
    assert model.max_seq_length == 512
    assert calls == [(["query"], True), (["query"], True)]
    assert sleeps == [0.1]


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
            model_name=GRANITE_MODEL_NAME,
            max_context_length=64,
        )
    )

    assert len(prepared) == 1
    assert prepared[0][0] == content
    assert prepared[0][1].startswith("Source:")
    assert prepared[0][2] <= 64


def test_remote_embed_does_not_resolve_a_local_model(
    runtime: EmbeddingRuntime, monkeypatch
) -> None:
    captured: list[str] = []
    monkeypatch.setattr(
        runtime,
        "_resolve_model",
        lambda _name: pytest.fail("remote embedding resolved a local model"),
    )

    def remote_embeddings(
        texts: list[str], _request: EmbedRequest
    ) -> list[list[float]]:
        captured.extend(texts)
        return [[1.0, 2.0]]

    monkeypatch.setattr(runtime, "_remote_embeddings", remote_embeddings, raising=False)

    result = runtime.embed(
        EmbedRequest(
            texts=["question"],
            model_name="remote-model",
            provider_type="openai_compatible",
            api_url="https://embedding.example/v1/embeddings",
            api_key="secret",
            text_type=EmbedTextType.QUERY,
            manual_query_prefix="query: ",
        )
    )

    assert result == [[1.0, 2.0]]
    assert captured == ["query: question"]


def test_unknown_api_model_uses_tokenizer_without_loading_local_weights(
    runtime: EmbeddingRuntime, monkeypatch
) -> None:
    statuses = runtime.model_statuses()
    calls: list[str] = []
    fake_tiktoken = ModuleType("tiktoken")

    class FakeEncoding:
        @staticmethod
        def encode_ordinary(text: str) -> list[int]:
            return [ord(character) for character in text]

    def unknown_model(_model_name: str):
        raise KeyError

    def get_encoding(name: str) -> FakeEncoding:
        calls.append(name)
        return FakeEncoding()

    fake_tiktoken.encoding_for_model = unknown_model
    fake_tiktoken.get_encoding = get_encoding
    monkeypatch.setitem(sys.modules, "tiktoken", fake_tiktoken)

    tokenizer = runtime._provider_tokenizer("unknown-compatible-model")

    assert runtime._content_token_count(tokenizer, "한국어 text") > 0
    assert calls == ["cl100k_base"]
    assert runtime.model_statuses() == statuses


def test_remote_chunking_uses_only_the_provider_tokenizer_and_api(
    runtime: EmbeddingRuntime, monkeypatch
) -> None:
    from app.contracts import ChunkEmbedRequest

    captured: list[str] = []
    monkeypatch.setattr(
        runtime,
        "_resolve_model",
        lambda _name: pytest.fail("remote chunking resolved a local model"),
    )
    monkeypatch.setattr(
        runtime,
        "_provider_tokenizer",
        lambda _name: CharacterTokenizer(),
        raising=False,
    )

    def remote_embeddings(texts: list[str], _request: Any) -> list[list[float]]:
        captured.extend(texts)
        return [[float(index), 1.0] for index, _ in enumerate(texts)]

    monkeypatch.setattr(runtime, "_remote_embeddings", remote_embeddings, raising=False)

    chunks = runtime.chunk_and_embed(
        ChunkEmbedRequest(
            text="First sentence. Second sentence.",
            title="title",
            metadata_context="Source: file",
            model_name="remote-model",
            max_context_length=128,
            manual_passage_prefix="passage: ",
            provider_type="openai_compatible",
            api_url="https://embedding.example/v1/embeddings",
            api_key="secret",
        )
    )

    assert [content for content, _, _ in chunks] == ["First sentence. Second sentence."]
    assert captured == [
        "passage: Source: file\nTitle: title\n\nFirst sentence. Second sentence."
    ]
    assert all(token_count <= 128 for _, _, token_count in chunks)


def test_remote_embedding_validates_order_and_normalizes(
    runtime: EmbeddingRuntime,
) -> None:
    request = EmbedRequest(
        texts=["first", "second"],
        model_name="remote-model",
        provider_type="openai_compatible",
        api_url="https://embedding.example/v1/embeddings",
        api_key="secret",
        text_type=EmbedTextType.PASSAGE,
        normalize_embeddings=True,
    )

    def handler(incoming: httpx.Request) -> httpx.Response:
        assert incoming.headers["Authorization"] == "Bearer secret"
        assert incoming.url == request.api_url
        assert json.loads(incoming.content) == {
            "input": ["first", "second"],
            "model": "remote-model",
            "encoding_format": "float",
        }
        return httpx.Response(
            200,
            json={
                "data": [
                    {"index": 1, "embedding": [0.0, 2.0]},
                    {"index": 0, "embedding": [3.0, 4.0]},
                ]
            },
        )

    runtime._http_client = httpx.Client(transport=httpx.MockTransport(handler))
    try:
        assert runtime._remote_embeddings(request.texts, request) == [
            [0.6, 0.8],
            [0.0, 1.0],
        ]
    finally:
        runtime._http_client.close()


@pytest.mark.parametrize("status", [302, 429, 502])
def test_remote_embedding_rejects_redirects_and_upstream_errors(
    runtime: EmbeddingRuntime, status: int
) -> None:
    request = EmbedRequest(
        texts=["text"],
        model_name="remote-model",
        provider_type="openai_compatible",
        api_url="https://embedding.example/v1/embeddings",
        text_type=EmbedTextType.PASSAGE,
    )
    calls = 0

    def handler(_request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        return httpx.Response(
            status,
            headers={"Location": "https://redirect.example/v1/embeddings"},
        )

    runtime._http_client = httpx.Client(
        transport=httpx.MockTransport(handler), follow_redirects=False
    )
    try:
        with pytest.raises((ValueError, RuntimeError)):
            runtime._remote_embeddings(request.texts, request)
        assert calls == 1
    finally:
        runtime._http_client.close()


@pytest.mark.parametrize(
    "response",
    [
        {"data": []},
        {"data": [{"index": 0, "embedding": [1.0]}, {"index": 0, "embedding": [2.0]}]},
        {
            "data": [
                {"index": 0, "embedding": [float("nan")]},
                {"index": 1, "embedding": [1.0]},
            ]
        },
    ],
)
def test_remote_embedding_rejects_invalid_vectors(
    runtime: EmbeddingRuntime, response: dict[str, Any]
) -> None:
    request = EmbedRequest(
        texts=["first", "second"],
        model_name="remote-model",
        provider_type="openai_compatible",
        api_url="https://embedding.example/v1/embeddings",
        text_type=EmbedTextType.PASSAGE,
    )
    runtime._http_client = httpx.Client(
        transport=httpx.MockTransport(
            lambda _request: httpx.Response(
                200, content=json.dumps(response, allow_nan=True).encode()
            )
        )
    )
    try:
        with pytest.raises(RuntimeError):
            runtime._remote_embeddings(request.texts, request)
    finally:
        runtime._http_client.close()
