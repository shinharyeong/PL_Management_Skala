"""핵심 메시지 API 스키마(초안, 백엔드와 합의 전)."""
from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field, field_validator

Kind = Literal["매출", "비용", "영업이익", "연간", "소명", "주의"]


class Fact(BaseModel):
    id: str = Field(pattern=r"^F\d{1,3}$")
    kind: Kind
    text: str = Field(min_length=1, max_length=600)


class KeyMessagesRequest(BaseModel):
    status: Literal["provisional", "final"]
    facts: list[Fact] = Field(min_length=1, max_length=30)

    @field_validator("facts")
    @classmethod
    def _unique_ids(cls, v):
        ids = [f.id for f in v]
        if len(ids) != len(set(ids)):
            raise ValueError("facts 의 id 가 중복되었습니다")
        return v


class Sentence(BaseModel):
    text: str
    fact_ids: list[str]
    source: Literal["ai", "template"]
    reason: str | None = None


class Usage(BaseModel):
    input_tokens: int = 0
    output_tokens: int = 0
    cached: bool = False


class KeyMessagesResponse(BaseModel):
    sentences: list[Sentence]
    usage: Usage
