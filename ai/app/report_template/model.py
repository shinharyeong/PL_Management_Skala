"""기준 월 상대 날짜 규칙과 백엔드 틀 저장 봉투. 업무 분류·영속 저장은 백엔드가 담당한다."""
from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

PeriodRule = Literal["base", "base_offset", "fixed", "recent_n"]


class Period(BaseModel):
    """날짜 규칙. base=기준 월, base_offset=기준 월±offset, fixed=고정 월(year·month), recent_n=기준 월 포함 최근 n개월."""
    model_config = ConfigDict(extra="forbid")
    rule: PeriodRule = "base"
    offset: int = 0
    year: int | None = None
    month: int | None = Field(default=None, ge=1, le=12)
    n: int | None = Field(default=None, ge=1, le=60)

    @model_validator(mode="after")
    def _check(self):
        """날짜 규칙에 필요한 값이 모두 있는지 확인한다.

        Args:
            없음. 검증된 모델 필드를 사용한다.
        Returns:
            유효한 현재 Period 모델.
        Raises:
            ValueError: 고정 연월·최근 개월 수 누락 또는 0인 상대 월.
        Side Effects:
            없음. 날짜나 저장 데이터를 만들지 않는다.
        """
        if self.rule == "fixed" and (self.year is None or self.month is None):
            raise ValueError("고정 월(fixed)은 year 와 month 가 모두 필요합니다")
        if self.rule == "recent_n" and self.n is None:
            raise ValueError("최근 N개월(recent_n)은 n 이 필요합니다")
        if self.rule == "base_offset" and self.offset == 0:
            raise ValueError("base_offset 의 offset 은 0 이 아니어야 합니다(0 이면 base 를 쓰세요)")
        return self


# 백엔드 RPT-04·05는 저장 봉투만 검사하며 블록의 배치·서식은 열어 둔다.
class StoredBlock(BaseModel):
    model_config = ConfigDict(extra="allow")
    id: str = Field(min_length=1, max_length=64)
    kind: str = Field(min_length=1, max_length=40)


class StoredTemplate(BaseModel):
    """백엔드에 저장하는 자료 틀. schema_version 으로 형식 변경을 구분한다."""
    model_config = ConfigDict(extra="allow")
    schema_version: int = Field(ge=1)
    name: str = Field(min_length=1, max_length=120)
    blocks: list[StoredBlock] = Field(max_length=500)
