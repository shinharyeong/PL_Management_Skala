"""일반 모드 엑셀 읽기: 시트 이름·헤더 위치·단위를 데이터에서 찾고, 못 읽은 것은 멈추지 않고 경고로 남긴다.

읽는 구조(조직 월마감 파일): 매출 시트(조직·분류·중분류·소분류·담당자 + 월별 블록)와 비용(OpEx) 시트.
월별 블록의 머리는 "'YY년 계획", "'YY년 실적/추정" 처럼 연도 두 자리와 계획/실적/추정 낱말로 찾는다(위치 고정 아님).
마감월 이하는 ACTUAL, 이후는 ESTIMATE, 계획 블록은 PLAN. 값은 원 단위 정수로 바꿔 돌려준다.
"""
from __future__ import annotations

import io
import re
import unicodedata

import openpyxl
from openpyxl.utils import get_column_letter

UNITS = {"원": 1, "천원": 1_000, "만원": 10_000, "백만원": 1_000_000, "억원": 100_000_000}
DEFAULT_UNIT = "천원"
UNIT_RE = re.compile(r"단위\s*[:：]?\s*\(?\s*(백만원|천원|만원|억원|원)|\(\s*(백만원|천원|만원|억원|원)\s*\)")
HEAD_RE = re.compile(r"(?<!\d)(?:20)?(\d{2})\s*년")      # 앞에 숫자가 붙은 '2026년' 의 '26년' 은 제목 문구이므로 연도 머리로 오인하지 않는다
SCAN_ROWS = 10
MAX_HEAD = 24


def _w(file: str, sheet: str | None, code: str, message: str, **extra) -> dict:
    return {"file": file, "sheet": sheet, "code": code, "message": message, **extra}


def _blocks(row_values, yy: int) -> dict:
    """머리 행에서 YY년 블록의 시작 열(1 기준)을 찾는다. 반환 {'PLAN': 열, 'CUR': 열}."""
    found: dict = {}
    for col, v in enumerate(row_values, start=1):
        if not isinstance(v, str) or len(v) > MAX_HEAD:       # 제목·설명 문장(긴 글)은 블록 머리가 아니다
            continue
        m = HEAD_RE.search(v)
        if not m or int(m.group(1)) != yy:
            continue
        if "계획" in v and "실적" not in v and "추정" not in v:
            found.setdefault("PLAN", col)
        elif "실적" in v or "추정" in v:
            found.setdefault("CUR", col)
    return found


def _is_error_text(v) -> bool:
    return isinstance(v, str) and (v.strip().startswith("#") or "INVALID" in v.upper())


def _unit(rows: list) -> str | None:
    for r in rows:
        for v in r:
            if isinstance(v, str):
                m = UNIT_RE.search(v)
                if m:
                    return m.group(1) or m.group(2)
    return None


def _pick_sheets(names: list[str]) -> tuple[str | None, str | None]:
    norm = [(n, unicodedata.normalize("NFC", n)) for n in names]
    rev = next((n for n, k in norm if "매출" in k and "답안" not in k and ("세부" in k or "상세" in k)), None) or next((n for n, k in norm if "매출" in k and "답안" not in k), None)
    opx = next((n for n, k in norm if re.search(r"opex|비용", k, re.I) and "답안" not in k and ("세부" in k or "상세" in k)), None) or \
        next((n for n, k in norm if re.search(r"opex|비용", k, re.I) and "답안" not in k), None)
    return rev, opx


def read_workbook(source, *, file: str, year: int, month: int, org_code: str) -> dict:
    """source: 경로 또는 바이트. 반환 {rows, warnings, stats}. 예외는 경고로 바꾼다(파일을 못 열면 rows=[])."""
    warnings: list[dict] = []
    rows: list[tuple] = []
    stats = {"sheets_read": 0, "cells": 0, "units": {}}
    try:
        wb = openpyxl.load_workbook(io.BytesIO(source) if isinstance(source, (bytes, bytearray)) else source, read_only=True, data_only=True)
    except Exception as e:  # noqa: BLE001
        return {"rows": [], "warnings": [_w(file, None, "UNREADABLE", f"파일을 열 수 없습니다({type(e).__name__}).")], "stats": stats}
    try:
        rev, opx = _pick_sheets(wb.sheetnames)
        for sheet, kind in ((rev, "매출"), (opx, "영업비용")):
            if sheet is None:
                warnings.append(_w(file, None, "NO_SHEET", f"{kind} 시트를 찾지 못해 이 파일의 {kind} 값을 읽지 못했습니다."))
                continue
            try:
                ws = wb[sheet]
                head = [list(r) for r in ws.iter_rows(min_row=1, max_row=SCAN_ROWS, values_only=True)]
            except Exception as e:  # noqa: BLE001
                warnings.append(_w(file, sheet, "SHEET_ERROR", f"시트를 읽는 중 오류({type(e).__name__})."))
                continue
            yy = year % 100
            hdr_row, blocks = None, {}
            for i, r in enumerate(head, start=1):
                b = _blocks(r, yy)
                if b:
                    hdr_row, blocks = i, b
                    break
            if not blocks:
                other = sorted({m.group(1) for r in head for v in r if isinstance(v, str) for m in [HEAD_RE.search(v)] if m})
                warnings.append(_w(file, sheet, "NO_YEAR_BLOCK", f"'{yy:02d}년' 계획·실적 머리를 찾지 못해 이 시트를 읽지 못했습니다" + (f"(있는 연도 머리: {', '.join(other)}년)." if other else ".")))
                continue
            unit = _unit(head) or DEFAULT_UNIT
            mult = UNITS[unit]
            stats["units"][sheet] = unit if _unit(head) else f"{unit}(표기 없어 가정)"
            data_start = hdr_row + 2
            cols = []
            for key in ("PLAN", "CUR"):
                if key in blocks:
                    for m in range(1, 13):
                        cols.append((blocks[key] + m, m, "PLAN" if key == "PLAN" else ("ACTUAL" if m <= month else "ESTIMATE")))
            if not cols:
                continue
            max_col = max(c for c, _, _ in cols)
            bad = blanks = errors = missing = 0
            for ridx, r in enumerate(ws.iter_rows(min_row=data_start, max_col=max_col, values_only=True), start=data_start):
                if not r or r[0] in (None, "") and (len(r) < 4 or r[3] in (None, "")):
                    continue
                if kind == "매출":
                    org_name, cat1, cat2, item, manager, team, detail = r[0], r[1], r[2], r[3], r[4] if len(r) > 4 else None, None, None
                else:
                    org_name, cat1, cat2, item = (r[3] if len(r) > 3 else None), r[0], r[1] if len(r) > 1 else None, r[2] if len(r) > 2 else None
                    manager, team, detail = (r[13] if len(r) > 13 else None), (r[6] if len(r) > 6 else None), (r[8] if len(r) > 8 else None)
                if _is_error_text(org_name) or _is_error_text(item):
                    errors += 1
                if not org_name or _is_error_text(org_name):
                    blanks += 1
                    org_name = ""                       # 이름이 없어도 금액은 파일의 조직 것이므로 버리지 않는다
                for col, m, sc in cols:
                    v = r[col - 1] if col - 1 < len(r) else None
                    if v is None or v == "" or v == "-":
                        if sc == "ACTUAL" and any(isinstance(r[c - 1], (int, float)) and not isinstance(r[c - 1], bool) for c, mm, ss in cols if ss == "ACTUAL" and c - 1 < len(r)):
                            missing += 1                # 같은 행의 다른 실적 월에는 값이 있는데 이 월만 비어 있음
                        continue
                    if isinstance(v, bool) or not isinstance(v, (int, float)):
                        bad += 1
                        continue
                    if v == 0:
                        continue
                    rows.append(("", org_code, str(org_name).strip(), kind, cat1, cat2, item, detail, team, manager, f"{sheet}!{get_column_letter(col)}{ridx}", year, m, sc, int(round(v * mult))))
            stats["sheets_read"] += 1
            if bad:
                warnings.append(_w(file, sheet, "NON_NUMERIC", f"숫자가 아닌 값 {bad}칸은 건너뛰었습니다.", count=bad))
            if blanks:
                warnings.append(_w(file, sheet, "NO_ORG_NAME", f"조직 이름이 비어 있거나 오류값인 행 {blanks}개는 이름 없이 금액만 읽었습니다.", count=blanks))
            if errors:
                warnings.append(_w(file, sheet, "ERROR_VALUE", f"이름·항목에 오류값(#N/A 등)이 있는 행 {errors}개가 있습니다.", count=errors))
            if missing:
                warnings.append(_w(file, sheet, "MISSING_VALUE", f"실적 월 값이 비어 있는 칸 {missing}개가 있습니다(0 으로 처리).", count=missing))
    finally:
        wb.close()
    if not rows:
        warnings.append(_w(file, None, "NO_VALUES", "읽은 금액이 하나도 없습니다(빈 파일이거나 양식이 다릅니다)."))
    return {"rows": rows, "warnings": warnings, "stats": stats}
