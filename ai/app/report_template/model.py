"""틀·블록·분류표 스키마. 연도·월·조직 이름은 코드에 없고, 날짜는 기준 월 기준 상대값으로 저장한다."""
from __future__ import annotations

from datetime import datetime, timezone
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

SCHEMA_VERSION = 1

BlockKind = Literal["title", "text", "table", "tree_list", "chart", "footnote"]
OnMissing = Literal["drop", "show_empty", "keep"]      # 빼기 / '데이터 없음' 표시 / 그대로(자리 유지)
CompareKind = Literal["none", "prev_month", "prev_year", "plan", "prev_submission", "custom"]
PeriodRule = Literal["base", "base_offset", "fixed", "recent_n"]


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


class _M(BaseModel):
    model_config = ConfigDict(extra="forbid")


class Period(_M):
    """날짜 규칙. base=기준 월, base_offset=기준 월±offset, fixed=고정 월(year·month), recent_n=기준 월 포함 최근 n개월."""
    rule: PeriodRule = "base"
    offset: int = 0
    year: int | None = None
    month: int | None = Field(default=None, ge=1, le=12)
    n: int | None = Field(default=None, ge=1, le=60)

    @model_validator(mode="after")
    def _check(self):
        if self.rule == "fixed" and (self.year is None or self.month is None):
            raise ValueError("고정 월(fixed)은 year 와 month 가 모두 필요합니다")
        if self.rule == "recent_n" and self.n is None:
            raise ValueError("최근 N개월(recent_n)은 n 이 필요합니다")
        if self.rule == "base_offset" and self.offset == 0:
            raise ValueError("base_offset 의 offset 은 0 이 아니어야 합니다(0 이면 base 를 쓰세요)")
        return self


class Scope(_M):
    type: Literal["all", "org"] = "all"                 # 전사 / 조직
    org: str | None = None                              # 조직 코드(또는 양식에 적힌 이름). 존재 확인은 데이터 선택 단계에서 한다
    orgs: list[str] = Field(default_factory=list)

    @model_validator(mode="after")
    def _check(self):
        if self.type == "org" and not (self.org or self.orgs):
            raise ValueError("범위가 조직이면 org 또는 orgs 가 필요합니다")
        return self


class Compare(_M):
    kind: CompareKind = "none"
    label: str | None = None                            # 표시 이름(예: 양식에 적힌 비교 기준 문구)


class FontSpec(_M):
    family: str | None = None                           # None 이면 전역 글꼴
    size_pt: float | None = Field(default=None, gt=0, le=200)
    bold: bool | None = None


class Block(_M):
    id: str = Field(min_length=1, max_length=64, pattern=r"^[A-Za-z0-9_\-]+$")
    kind: BlockKind
    text: str | None = None                             # 제목·고정 문장·각주 본문(문장 블록의 AI 문장은 생성 단계에서 채움)
    metric: str | None = None                           # 지표 사전의 키. 사전 대조는 구성 추출/데이터 선택 단계
    scope: Scope = Field(default_factory=Scope)
    period: Period = Field(default_factory=Period)
    compare: Compare = Field(default_factory=Compare)
    on_missing: OnMissing = "show_empty"
    font: FontSpec = Field(default_factory=FontSpec)
    footnote_ids: list[str] = Field(default_factory=list)   # 이 블록에 붙은 각주(※) 블록 id
    options: dict = Field(default_factory=dict)         # 차트 종류·열 구성 등 종류별 부가 값
    needs_review: list[str] = Field(default_factory=list)   # 확인 필요 사유 코드(구성 추출 단계에서 채움)

    @model_validator(mode="after")
    def _check(self):
        if self.kind in ("title", "footnote") and not (self.text or "").strip():
            raise ValueError(f"{self.kind} 블록은 text 가 필요합니다")
        if self.kind in ("table", "tree_list", "chart") and not self.metric:
            raise ValueError(f"{self.kind} 블록은 지표 매핑(metric)이 필요합니다")
        return self


class Fonts(_M):
    """글꼴 2종: 제목용·본문용."""
    heading: str = "Pretendard"
    body: str = "Pretendard"
    heading_size_pt: float = Field(default=18, gt=0, le=200)
    body_size_pt: float = Field(default=10, gt=0, le=200)


class BaseMonthRule(_M):
    """공통 기준 월. latest_master=가장 최근 확정 master 월, fixed=고정(year·month)."""
    mode: Literal["latest_master", "fixed"] = "latest_master"
    year: int | None = None
    month: int | None = Field(default=None, ge=1, le=12)

    @model_validator(mode="after")
    def _check(self):
        if self.mode == "fixed" and (self.year is None or self.month is None):
            raise ValueError("고정 기준 월은 year 와 month 가 필요합니다")
        return self


class TemplateSettings(_M):
    fonts: Fonts = Field(default_factory=Fonts)
    base_month: BaseMonthRule = Field(default_factory=BaseMonthRule)
    mapping_version: int | None = Field(default=None, ge=1)   # 사용한 분류표 버전(없으면 아직 분류 전)


class Template(_M):
    schema_version: int = SCHEMA_VERSION
    id: str | None = Field(default=None, pattern=r"^[A-Za-z0-9_\-]{1,64}$")
    name: str = Field(min_length=1, max_length=120)
    created_at: str | None = None
    updated_at: str | None = None
    source: str | None = None                           # 'pdf'(구성 추출) / 'manual' / 'ai_edit' 등 출처 메모
    settings: TemplateSettings = Field(default_factory=TemplateSettings)
    blocks: list[Block] = Field(default_factory=list)

    @field_validator("schema_version")
    @classmethod
    def _ver(cls, v):
        if v != SCHEMA_VERSION:
            raise ValueError(f"지원하지 않는 틀 스키마 버전입니다: {v}(지원: {SCHEMA_VERSION})")
        return v

    @model_validator(mode="after")
    def _check(self):
        ids = [b.id for b in self.blocks]
        dup = sorted({i for i in ids if ids.count(i) > 1})
        if dup:
            raise ValueError(f"블록 id 가 겹칩니다: {', '.join(dup)}")
        by_id = {b.id: b for b in self.blocks}
        for b in self.blocks:
            for fid in b.footnote_ids:
                if fid not in by_id:
                    raise ValueError(f"블록 {b.id} 의 각주 {fid} 가 틀에 없습니다")
                if by_id[fid].kind != "footnote":
                    raise ValueError(f"블록 {b.id} 의 각주 {fid} 는 footnote 블록이 아닙니다")
        return self


# ---------- 분류표 ----------
# 사람이 지정하는 분류는 1·3·4 뿐. 2 는 3+4 자동, 5 는 미지정 자동.
ASSIGNABLE = (1, 3, 4)
DEFAULT_CLASSES = [{"id": 1, "name": "월정액", "auto": False}, {"id": 2, "name": "이연(3+4)", "auto": True},
                   {"id": 3, "name": "이연", "auto": False}, {"id": 4, "name": "일시적", "auto": False},
                   {"id": 5, "name": "기타(미지정)", "auto": True}]


class MappingClass(_M):
    id: int
    name: str = Field(min_length=1, max_length=40)
    auto: bool = False


class Mapping(_M):
    """분류표: 버전 + 분류 목록 + 소명→분류 지정. 저장할 때마다 새 버전."""
    version: int | None = Field(default=None, ge=1)
    created_at: str | None = None
    note: str | None = None
    classes: list[MappingClass] = Field(default_factory=lambda: [MappingClass(**c) for c in DEFAULT_CLASSES])
    assignments: dict[str, int] = Field(default_factory=dict)   # 소명 키 → 분류 id(1·3·4 만)

    @model_validator(mode="after")
    def _check(self):
        ids = {c.id for c in self.classes}
        if not set(ASSIGNABLE) <= ids:
            raise ValueError("분류 목록에 1·3·4 가 모두 있어야 합니다")
        for k, v in self.assignments.items():
            if v not in ASSIGNABLE:
                raise ValueError(f"소명 {k} 의 분류 {v} 는 지정할 수 없습니다(지정 가능: 1·3·4, 2 는 3+4 자동, 5 는 미지정 자동)")
        return self
