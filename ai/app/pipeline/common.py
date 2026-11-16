"""공통 설정·숫자 도구. 모든 금액은 원 단위 Decimal."""
from __future__ import annotations

import json
import re
import unicodedata
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path

from ..config import get_settings

REPO = Path(__file__).resolve().parents[3]
PARSER_SRC = REPO / "parser" / "src"
DOMAIN_RULES = REPO / "docs" / "domain-rules.md"


REV_SHEET = "매출_세부_IFRS15後"
OPX_SHEET = "OpEx_세부_IFRS15後"
EOK = Decimal(10) ** 8

def out_dir() -> Path:
    """결과 폴더(설정 OUTPUT_DIR)."""
    return get_settings().output_dir


def inputs() -> dict:
    """파이프라인 입력 경로(설정). 없으면 무엇을 설정하라는 ConfigError 를 낸다."""
    return get_settings().require_pipeline_inputs()


REQUEST_YEAR = 2026
REQUEST_MONTH = 11


def nfc(s: str) -> str:
    return unicodedata.normalize("NFC", s)


def d(v) -> Decimal:
    return v if isinstance(v, Decimal) else Decimal(str(v))


def eok(won: Decimal, places: int = 1) -> Decimal:
    """원 → 억원. 모든 화면·문장의 억원 표시는 이 함수 한 곳에서만 계산한다(소수 1자리, ROUND_HALF_UP)."""
    q = Decimal(1).scaleb(-places)
    return (won / EOK).quantize(q, rounding=ROUND_HALF_UP)


def pct1(rate):
    """비율(%) 표시 한 곳: 소수 1자리, ROUND_HALF_UP. None 은 그대로."""
    return None if rate is None else rate.quantize(Decimal("0.1"), rounding=ROUND_HALF_UP)


def fmt_eok(v: Decimal, signed: bool = False) -> str:
    """이미 반올림된 억원 Decimal 의 문자열(쉼표, 음수는 −). 다시 반올림하지 않는다."""
    s = format(v, ",f")
    s = s.replace("-", "−")
    return ("+" + s) if signed and v > 0 else s


def fmt_pct(v, signed: bool = True) -> str | None:
    if v is None:
        return None
    s = format(v, "f").replace("-", "−")
    return (("+" if v > 0 and signed else "") + s + "%")


def rate_percent(diff: Decimal, base: Decimal):
    """Gap률 = diff / |base| * 100. base가 0이면 None('기준 없음')."""
    if base == 0:
        return None
    return diff / abs(base) * 100


def jdefault(o):
    if isinstance(o, Decimal):
        return format(o, "f")
    if isinstance(o, (set, tuple)):
        return list(o)
    raise TypeError(type(o))


def write_json(name: str, obj) -> Path:
    out = out_dir()
    out.mkdir(parents=True, exist_ok=True)
    p = out / name
    p.write_text(json.dumps(obj, ensure_ascii=False, indent=2, default=jdefault), encoding="utf-8")
    return p


def compress_rows(rows) -> str:
    rows = sorted(set(rows))
    out, start, prev = [], None, None
    for r in rows:
        if start is None:
            start = prev = r
        elif r == prev + 1:
            prev = r
        else:
            out.append(f"{start}-{prev}" if prev != start else str(start))
            start = prev = r
    if start is not None:
        out.append(f"{start}-{prev}" if prev != start else str(start))
    return ",".join(out)


def load_thresholds() -> dict:
    """docs/domain-rules.md 의 '기준' 절에서 실적·추정 기준을 읽는다. 문서에는 '예)'로만 존재한다."""
    text = DOMAIN_RULES.read_text(encoding="utf-8")
    m = re.search(
        r"예\)\s*실적\s*±(\d+(?:\.\d+)?)%[·\s]*(\d+)(천만|만|억)\s*원,\s*추정\s*±(\d+(?:\.\d+)?)%[·\s]*(\d+)(천만|만|억)\s*원",
        text,
    )
    if not m:
        raise RuntimeError("문서에 없음: 실적·추정 기준 값을 docs/domain-rules.md에서 찾지 못했습니다.")
    unit = {"천만": Decimal(10) ** 7, "만": Decimal(10) ** 4, "억": Decimal(10) ** 8}
    line = next(l for l in text.splitlines() if "예) 실적" in l)
    return {
        "ACTUAL": {"percent": Decimal(m.group(1)), "amount_won": Decimal(m.group(2)) * unit[m.group(3)]},
        "ESTIMATE": {"percent": Decimal(m.group(4)), "amount_won": Decimal(m.group(5)) * unit[m.group(6)]},
        "source": "docs/domain-rules.md §4 '기준' (문서에는 '예)' 예시값으로만 기재, 확정 기준값 아님)",
        "source_line": line.strip(),
        "rule": "abs(Gap률) > percent AND abs(Gap금액) > amount (엄격 초과, 반올림 전 십진값)",
    }
