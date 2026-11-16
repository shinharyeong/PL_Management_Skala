"""(b) 더미 데이터셋 엑셀(조직별 월마감 파일)의 상세 시트 → 챗봇 DB 적재. 여러 프로세스로 읽고 한 번에 많이 insert 한 뒤 인덱스·ANALYZE.

매출_세부_IFRS15後: 5행부터 · A조직 B관리분류 C중분류 D소분류 E담당자 · 3행의 "'YY년 계획 / 'YY년 실적/추정" 머리(YY = 마감 연도 두 자리) 블록 시작열 +1~+12 가 1~12월 · 단위 천원 → 원(×1000)
OpEx_세부_IFRS15後: 6행부터 · A대분류 B중분류 C소분류 D조직명 G팀명 I실행과제명 N담당자 · 4행이 블록 머리(월은 5행) · 단위 천원
마감월 이하는 ACTUAL, 이후는 ESTIMATE, 계획 블록은 PLAN. 값이 비었거나 0 인 칸은 넣지 않는다(합계에 영향 없음, 행 수를 줄이기 위함).
"""
from __future__ import annotations

import re
import time
from concurrent.futures import ProcessPoolExecutor
from datetime import datetime
from pathlib import Path

import openpyxl
from openpyxl.utils import get_column_letter

from ..config import Settings, get_settings
from . import db, schema

REV_SHEET, OPX_SHEET = "매출_세부_IFRS15後", "OpEx_세부_IFRS15後"
# 조직코드: 영문 1~3자 + 숫자 2~4자리(앞뒤가 영문·숫자가 아닐 때). 다른 코드 형식이면 load_excel(org_regex=...) 로 바꾼다.
ORG_RE = re.compile(r"(?<![A-Za-z0-9])[A-Za-z]{1,3}\d{2,4}(?![A-Za-z0-9])")


def is_org_file(path: Path, org_regex: re.Pattern = ORG_RE) -> bool:
    """조직별 월마감 파일만(이름에 조직코드가 있고, 전체 마스터·임시 잠금 파일은 제외)."""
    n = path.name
    return path.suffix.lower() == ".xlsx" and not n.startswith("~$") and "전체" not in n and "마스터" not in n and bool(org_regex.search(n))


def discover(root: Path, pattern: str = "*.xlsx", org_regex: re.Pattern = ORG_RE) -> list[Path]:
    return sorted(p for p in root.rglob(pattern) if is_org_file(p, org_regex))


def _blocks(ws, row: int, yy: int) -> dict:
    """머리 행에서 YY년(마감 연도 두 자리) 블록의 시작열을 찾는다. 반환: {'PLAN': 시작열, 'CUR': 시작열}(열 번호, 1 기준)."""
    found = {}
    for col, v in enumerate(next(ws.iter_rows(min_row=row, max_row=row, values_only=True)), start=1):
        m = re.search(r"(\d{2})년", v) if isinstance(v, str) else None
        if m and int(m.group(1)) == yy:
            if "계획" in v and "실적" not in v:
                found["PLAN"] = col
            elif "실적" in v or "추정" in v:
                found["CUR"] = col
    return found


def _num(v):
    if isinstance(v, bool) or v is None:
        return None
    if isinstance(v, (int, float)):
        return v
    return None


def parse_workbook(args: tuple) -> dict:
    """파일 하나를 읽어 fact 행 목록을 돌려준다(프로세스 병렬용 최상위 함수). args = (path, version, close_year, close_month, org_code)"""
    path, version, close_year, close_month, org_code = args
    t0 = time.perf_counter()
    wb = openpyxl.load_workbook(path, read_only=True, data_only=True)
    rows: list[tuple] = []
    for sheet, hdr_row, data_start, kind in ((REV_SHEET, 3, 5, "매출"), (OPX_SHEET, 4, 6, "영업비용")):
        ws = wb[sheet]
        blocks = _blocks(ws, hdr_row, close_year % 100)
        month_cols = [(sc, start + m, m) for key, sc_fixed in (("PLAN", "PLAN"), ("CUR", None)) if key in blocks for start in [blocks[key]] for m in range(1, 13)
                      for sc in [sc_fixed or ("ACTUAL" if m <= close_month else "ESTIMATE")]]
        max_col = max((c for _, c, _ in month_cols), default=1)
        for ridx, r in enumerate(ws.iter_rows(min_row=data_start, max_col=max_col, values_only=True), start=data_start):
            if not r or r[0] in (None, ""):
                continue
            if kind == "매출":
                org_name, cat1, cat2, item, manager, team, detail = r[0], r[1], r[2], r[3], r[4], None, None
            else:
                org_name, cat1, cat2, item, manager, team, detail = r[3], r[0], r[1], r[2], r[13] if len(r) > 13 else None, r[6] if len(r) > 6 else None, r[8] if len(r) > 8 else None
            for sc, col, m in month_cols:
                v = _num(r[col - 1]) if col - 1 < len(r) else None
                if not v:
                    continue
                rows.append((version, org_code, org_name, kind, cat1, cat2, item, detail, team, manager, f"{sheet}!{get_column_letter(col)}{ridx}", close_year, m, sc, int(round(v * 1000))))
    wb.close()
    return {"path": str(path), "org_code": org_code, "rows": rows, "seconds": time.perf_counter() - t0}


def org_code_of(path: Path, org_regex: re.Pattern = ORG_RE) -> str:
    return org_regex.search(path.name).group(0)


def load_excel(files: list[Path], version: str, close_year: int, close_month: int, *, workers: int | None = None, replace: bool = True,
               settings: Settings | None = None, label: str | None = None, kind: str = "close", insert_batch: int = 50000, org_regex: re.Pattern = ORG_RE) -> dict:
    """files(조직별 파일)를 version 으로 적재한다. 반환: 행 수와 단계별 시간(파일 읽기, insert, 인덱스, ANALYZE)."""
    s = settings or get_settings()
    T = {"files": len(files)}
    t_all = time.perf_counter()
    jobs = [(str(p), version, close_year, close_month, org_code_of(p, org_regex)) for p in files]
    w = workers if workers is not None else max(1, min(len(jobs), s.parse_workers or 8))
    t0 = time.perf_counter()
    if w > 1 and len(jobs) > 1:
        with ProcessPoolExecutor(max_workers=w) as ex:
            parts = list(ex.map(parse_workbook, jobs))
    else:
        parts = [parse_workbook(j) for j in jobs]
    T["read_seconds"] = round(time.perf_counter() - t0, 2)
    T["per_file_seconds"] = [round(p["seconds"], 2) for p in parts]
    T["avg_file_seconds"] = round(sum(T["per_file_seconds"]) / max(1, len(parts)), 2)
    T["workers"] = w
    con = db.open_writer(s)
    try:
        con.execute("PRAGMA synchronous=OFF")
        t1 = time.perf_counter()
        total = 0
        with con:
            if replace:
                con.execute("DELETE FROM fact WHERE version=?", (version,))
                con.execute("DELETE FROM dim_version WHERE version=?", (version,))
            for p in parts:
                rows = p["rows"]
                for i in range(0, len(rows), insert_batch):
                    con.executemany("INSERT INTO fact VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", rows[i:i + insert_batch])
                total += len(rows)
                if rows:
                    con.execute("INSERT OR REPLACE INTO dim_org(org_code, org_name) VALUES(?,?)", (p["org_code"], rows[0][2]))
            con.execute("INSERT INTO dim_version(version, kind, label, year, month, loaded_at, source, row_count, is_latest) VALUES(?,?,?,?,?,?,?,?,0)",
                        (version, kind, label or f"{close_month}월 마감", close_year, close_month, datetime.now().isoformat(timespec="seconds"), f"excel:{len(files)}files", total))
        T["insert_seconds"] = round(time.perf_counter() - t1, 2)
        t2 = time.perf_counter()
        con.executescript(schema.INDEXES)
        T["index_seconds"] = round(time.perf_counter() - t2, 2)
        t3 = time.perf_counter()
        with con:
            db.mark_latest(con)
        con.execute("ANALYZE")
        T["analyze_seconds"] = round(time.perf_counter() - t3, 2)
    finally:
        con.close()
    T["rows"] = total
    T["total_seconds"] = round(time.perf_counter() - t_all, 2)
    return T
