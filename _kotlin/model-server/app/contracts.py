from __future__ import annotations

from enum import Enum

from pydantic import BaseModel, ConfigDict, Field


class EmbedTextType(str, Enum):
    QUERY = "query"
    PASSAGE = "passage"


class EmbedRequest(BaseModel):
    model_config = ConfigDict(protected_namespaces=())

    texts: list[str]
    model_name: str | None = None
    deployment_name: str | None = None
    max_context_length: int = Field(default=512, ge=1, le=32768)
    normalize_embeddings: bool = True
    api_key: str | None = None
    provider_type: str | None = None
    text_type: EmbedTextType
    manual_query_prefix: str | None = None
    manual_passage_prefix: str | None = None
    api_url: str | None = None
    api_version: str | None = None
    reduced_dimension: int | None = None


class EmbedResponse(BaseModel):
    embeddings: list[list[float]]


class ChunkEmbedRequest(BaseModel):
    model_config = ConfigDict(protected_namespaces=())

    text: str
    title: str = ""
    metadata_context: str = ""
    model_name: str | None = None
    max_context_length: int = Field(default=512, ge=1, le=32768)
    normalize_embeddings: bool = True
    manual_passage_prefix: str | None = None


class ExistingChunk(BaseModel):
    content: str
    title: str = ""
    search_context: str = ""


class PrepareExistingChunksRequest(BaseModel):
    model_config = ConfigDict(protected_namespaces=())

    chunks: list[ExistingChunk]
    model_name: str | None = None
    max_context_length: int = Field(default=512, ge=1, le=32768)
    manual_passage_prefix: str | None = None


class EmbeddedChunk(BaseModel):
    content: str
    embedding: list[float]
    token_count: int


class ChunkEmbedResponse(BaseModel):
    chunks: list[EmbeddedChunk]


class PreparedChunk(BaseModel):
    content: str
    embedding_text: str
    token_count: int


class ChunkResponse(BaseModel):
    chunks: list[PreparedChunk]


class ApiError(BaseModel):
    code: str
    message: str
