from __future__ import annotations

import pytest
from app.contracts import EmbedRequest
from app.main import app, embedding_runtime
from app.runtime import GRANITE_MODEL_NAME
from fastapi.testclient import TestClient


@pytest.fixture
def client() -> TestClient:
    with TestClient(app) as test_client:
        yield test_client


def test_startup_does_not_load_a_local_model(monkeypatch) -> None:
    monkeypatch.setattr(
        embedding_runtime,
        "load",
        lambda: pytest.fail("startup loaded a local embedding model"),
    )

    with TestClient(app) as test_client:
        assert test_client.get("/actuator/health/readiness").status_code == 200


def test_health_contract(client: TestClient) -> None:
    response = client.get("/api/health")
    assert response.status_code == 200


def test_provider_fields_are_not_part_of_the_contract() -> None:
    fields = EmbedRequest.model_fields

    assert "provider_type" not in fields
    assert "api_url" not in fields
    assert "api_key" not in fields


def test_model_status_reports_optional_harrier(client: TestClient) -> None:
    response = client.get("/api/model-status")

    assert response.status_code == 200
    assert "microsoft/harrier-oss-v1-0.6b" in response.json()["models"]


def test_embed_contract_with_fake_runtime(monkeypatch, client: TestClient) -> None:
    monkeypatch.setattr(
        embedding_runtime,
        "embed",
        lambda request: [[1.0, 0.0] for _ in request.texts],
    )
    response = client.post(
        "/encoder/bi-encoder-embed",
        json={
            "texts": ["hello", "안녕하세요"],
            "model_name": GRANITE_MODEL_NAME,
            "max_context_length": 512,
            "normalize_embeddings": True,
            "text_type": "passage",
        },
    )
    assert response.status_code == 200
    assert response.json() == {"embeddings": [[1.0, 0.0], [1.0, 0.0]]}


def test_chunk_and_embed_contract_with_fake_runtime(
    monkeypatch, client: TestClient
) -> None:
    monkeypatch.setattr(
        embedding_runtime,
        "chunk_and_embed",
        lambda request: [(request.text, [1.0, 0.0], 12)],
    )

    response = client.post(
        "/encoder/chunk-and-embed",
        json={
            "text": "첫 문장입니다. 둘째 문장입니다.",
            "title": "테스트",
            "metadata_context": "Source: jira",
            "model_name": GRANITE_MODEL_NAME,
            "max_context_length": 512,
            "normalize_embeddings": True,
        },
    )

    assert response.status_code == 200
    assert response.json() == {
        "chunks": [
            {
                "content": "첫 문장입니다. 둘째 문장입니다.",
                "embedding": [1.0, 0.0],
                "token_count": 12,
            }
        ]
    }


def test_chunk_contract_exposes_prepared_embedding_text(
    monkeypatch, client: TestClient
) -> None:
    monkeypatch.setattr(
        embedding_runtime,
        "prepare_chunks",
        lambda request: (
            request.model_name,
            [(request.text, "Title: 테스트\n\n" + request.text, 12)],
        ),
    )

    response = client.post(
        "/encoder/chunk",
        json={
            "text": "본문",
            "title": "테스트",
            "model_name": GRANITE_MODEL_NAME,
            "max_context_length": 512,
            "normalize_embeddings": True,
        },
    )

    assert response.status_code == 200
    assert response.json()["chunks"] == [
        {
            "content": "본문",
            "embedding_text": "Title: 테스트\n\n본문",
            "token_count": 12,
        }
    ]
