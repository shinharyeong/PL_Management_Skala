"""기준 월 기준 상대 날짜 → 실제 (연, 월) 목록. 순수 계산이라 AI 가 관여하지 않는다."""
from __future__ import annotations

from .model import Period


def shift(year: int, month: int, delta: int) -> tuple[int, int]:
    n = year * 12 + (month - 1) + delta
    return n // 12, n % 12 + 1


def resolve(period: Period, base: tuple[int, int]) -> list[tuple[int, int]]:
    """오래된 월 → 최근 월 순서. base=(연, 월)."""
    by, bm = base
    if period.rule == "base":
        return [(by, bm)]
    if period.rule == "base_offset":
        return [shift(by, bm, period.offset)]
    if period.rule == "fixed":
        return [(period.year, period.month)]
    return [shift(by, bm, -i) for i in range(period.n - 1, -1, -1)]
