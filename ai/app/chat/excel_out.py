"""조회 결과 → 엑셀(openpyxl). 시트: 요약 / 결과 / 근거. 금액(_won)은 원 단위 정확값, 옆 억원 열은 수식(=값/100000000).
엑셀의 숫자는 SQL 결과와 정확히 같다(값을 다시 계산하지 않고 그대로 쓴다). 헤더 스타일·틀 고정·필터·차이 열 음수 빨강·막대 차트(행 2개 이상 + 숫자 열)."""
from __future__ import annotations

import json
from datetime import datetime
from pathlib import Path

from openpyxl import Workbook
from openpyxl.chart import BarChart, Reference
from openpyxl.styles import Alignment, Border, Font, PatternFill, Side
from openpyxl.utils import get_column_letter

HEAD_FILL = PatternFill("solid", fgColor="1F3A5F")
HEAD_FONT = Font(bold=True, color="FFFFFF")
THIN = Side(style="thin", color="D9D9D9")
EOK_FORMAT = "#,##0.0"
WON_FORMAT = "#,##0"
DIFF_FORMAT = "#,##0;[Red]-#,##0"
DIFF_EOK_FORMAT = "#,##0.0;[Red]-#,##0.0"


def _is_diff(col: str) -> bool:
    c = col.lower()
    return "diff" in c or "차이" in c


def _is_num(v) -> bool:
    return isinstance(v, (int, float)) and not isinstance(v, bool)


def build_excel(path: Path, *, question: str, version: str, params: dict, columns: list[str], rows: list[tuple], sql: str, ai: dict, answer_text: str,
                answer_source: str, evidence: list[dict], truncated: bool = False) -> dict:
    """엑셀을 path 에 저장한다. 반환: 시트·열 구성 정보(검증용)."""
    wb = Workbook()
    # ---- 결과
    ws = wb.active
    ws.title = "결과"
    out_cols: list[tuple[str, int | None]] = []        # (헤더, 억원 열이면 기준 원본 열 인덱스(0 기준))
    src_pos: dict[int, int] = {}                       # 원본 열 인덱스 → 결과 시트의 열 번호(1 기준)
    for i, c in enumerate(columns):
        out_cols.append((c, None))
        src_pos[i] = len(out_cols)
        if c.endswith("_won"):
            out_cols.append((c[:-4] + "_억원", i))
    for j, (header, _) in enumerate(out_cols, start=1):
        cell = ws.cell(row=1, column=j, value=header)
        cell.fill, cell.font, cell.alignment = HEAD_FILL, HEAD_FONT, Alignment(horizontal="center", vertical="center", wrap_text=True)
    for ridx, r in enumerate(rows, start=2):
        j = 0
        for i, c in enumerate(columns):
            j += 1
            v = r[i]
            cell = ws.cell(row=ridx, column=j, value=v)
            if c.endswith("_won") and _is_num(v):
                cell.number_format = DIFF_FORMAT if _is_diff(c) else WON_FORMAT
                j += 1
                eok = ws.cell(row=ridx, column=j, value=f"={get_column_letter(j - 1)}{ridx}/100000000")
                eok.number_format = DIFF_EOK_FORMAT if _is_diff(c) else EOK_FORMAT
            elif c.endswith("_won"):
                j += 1                                         # 값이 비었으면 억원 열도 비워 둔다
            elif _is_num(v) and _is_diff(c):
                cell.number_format = DIFF_FORMAT
    last_col = len(out_cols)
    ws.freeze_panes = "A2"
    if rows:
        ws.auto_filter.ref = f"A1:{get_column_letter(last_col)}{len(rows) + 1}"
    for j, (header, _) in enumerate(out_cols, start=1):
        width = max([len(str(header))] + [len(str(ws.cell(row=k, column=j).value or "")) for k in range(2, min(len(rows) + 2, 60))]) + 2
        ws.column_dimensions[get_column_letter(j)].width = min(max(width, 8), 42)
    # 차트: 행 2개 이상이고 숫자 열이 있을 때(첫 번째 원 단위 금액 열, 없으면 첫 숫자 열)
    chart_info = None
    num_cols = [i for i, c in enumerate(columns) if rows and _is_num(rows[0][i])]
    if len(rows) >= 2 and num_cols:
        vi = next((i for i in num_cols if columns[i].endswith("_won")), num_cols[0])
        ci = next((i for i in range(len(columns)) if i not in num_cols), 0)
        n = min(len(rows), 30)
        ch = BarChart()
        ch.type, ch.title, ch.style = "col", f"{columns[vi]} (상위 {n}행)", 10
        ch.add_data(Reference(ws, min_col=src_pos[vi], min_row=1, max_row=n + 1), titles_from_data=True)
        ch.set_categories(Reference(ws, min_col=src_pos[ci], min_row=2, max_row=n + 1))
        ch.height, ch.width = 9, 18
        ws.add_chart(ch, f"{get_column_letter(last_col + 2)}2")
        chart_info = {"value_column": columns[vi], "rows": n}
    # ---- 요약
    sm = wb.create_sheet("요약", 0)
    info = [("질문", question), ("기준 버전", version), ("조회 행수", len(rows) if not truncated else f"{len(rows)} (행 상한으로 잘림)"), ("생성 시각", datetime.now().strftime("%Y-%m-%d %H:%M:%S")),
            ("설명 문장", answer_text), ("설명 출처", {"ai": "AI", "template": "기본문구"}.get(answer_source, answer_source))]
    for k, (a, b) in enumerate(info, start=1):
        sm.cell(row=k, column=1, value=a).font = Font(bold=True)
        sm.cell(row=k, column=2, value=b).alignment = Alignment(wrap_text=True, vertical="top")
    sm.column_dimensions["A"].width, sm.column_dimensions["B"].width = 14, 100
    # ---- 근거
    ev = wb.create_sheet("근거")
    ev.column_dimensions["A"].width, ev.column_dimensions["B"].width = 16, 110
    items = [("SQL", sql), ("인자", json.dumps(params, ensure_ascii=False)),
             ("AI 사용", f"호출 {ai.get('calls', 0)}회 · 저장된 SQL 재사용 {ai.get('cache_hits', 0)}회 · 토큰 {ai.get('input_tokens', 0)}+{ai.get('output_tokens', 0)} · 실패 {ai.get('failed', 0)}"
                       + (f" · 이유: {'; '.join(ai.get('reasons', []))}" if ai.get("reasons") else ""))]
    for k, (a, b) in enumerate(items, start=1):
        ev.cell(row=k, column=1, value=a).font = Font(bold=True)
        c = ev.cell(row=k, column=2, value=b)
        c.alignment = Alignment(wrap_text=True, vertical="top")
    r0 = len(items) + 2
    ev.cell(row=r0, column=1, value="근거 항목").font = Font(bold=True)
    for k, e in enumerate(evidence, start=r0 + 1):
        ev.cell(row=k, column=1, value=e.get("type"))
        ev.cell(row=k, column=2, value=" · ".join(str(x) for x in (e.get("version"), e.get("ref"), e.get("src")) if x))
    path.parent.mkdir(parents=True, exist_ok=True)
    wb.save(path)
    return {"sheets": wb.sheetnames, "result_columns": [h for h, _ in out_cols], "chart": chart_info, "rows": len(rows)}
