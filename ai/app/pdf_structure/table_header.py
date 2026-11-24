"""표 머리(여러 줄·병합 칸) 처리. 순수 함수만 있다(DB·AI 없음).

VLM 이 읽은 표의 머리는 줄마다 칸 수가 다를 수 있다(병합 칸). header_spans(colspan/rowspan)로 '머리줄 수 × 전체 칸 수'의 완전한 격자를 만들고,
열마다 경로(예: ["매출", "계획"])를 뽑아 3단계 이후의 값 채우기가 열의 지표·종류를 정할 수 있게 한다.
병합 범위를 알 수 없는 빈 머리 칸은 추측해서 채우지 않고 ambiguous 로 알린다(세로로 이어진 칸만 위 글자를 아래로 펼친다).
"""
from __future__ import annotations


def _txt(c) -> str:
    return str(c.get("text") or "").strip() if isinstance(c, dict) and c.get("type") == "text" else ""


def header_layout(rows: list, hr: int, spans: list | None = None) -> dict:
    """반환: grid(hr줄×ncol칸 글자), cells(줄별 칸 목록: 병합은 colspan/rowspan, 가려진 칸은 hide), paths(열별 경로), ambiguous(병합 범위를 알 수 없는 빈 머리 칸이 있는가)."""
    ncol = max((len(r) for r in rows), default=0)
    hr = max(0, min(int(hr or 0), len(rows)))
    grid = [[(_txt(rows[i][j]) if j < len(rows[i]) else "") for j in range(ncol)] for i in range(hr)]
    origin: dict[tuple[int, int], tuple[int, int]] = {}                # 가려진 칸 → 글자가 있는 칸
    for s in spans or []:                                              # ① VLM 이 알려 준 병합(colspan 은 오른쪽, rowspan 은 아래로)
        try:
            r0, c0, cs, rs = int(s["row"]), int(s["col"]), max(1, int(s.get("colspan") or 1)), max(1, int(s.get("rowspan") or 1))
        except (KeyError, TypeError, ValueError):
            continue
        if not (0 <= r0 < hr and 0 <= c0 < ncol) or not grid[r0][c0]:
            continue
        for r in range(r0, min(hr, r0 + rs)):
            for c in range(c0, min(ncol, c0 + cs)):
                if (r, c) != (r0, c0) and not grid[r][c]:
                    grid[r][c] = grid[r0][c0]
                    origin[(r, c)] = (r0, c0)
    for j in range(ncol):                                              # ② 세로로 이어진 칸: 아래가 전부 비어 있으면 위 글자가 아래까지 이어진 것(rowspan)
        for i in range(1, hr):
            if not grid[i][j] and grid[i - 1][j] and all(not grid[k][j] for k in range(i, hr)):
                grid[i][j] = grid[i - 1][j]
                origin[(i, j)] = origin.get((i - 1, j), (i - 1, j))
    amb_cols = set()                                                   # 병합 범위를 모르는 칸: 같은 머리줄에서 글자 있는 칸(라벨 열 제외) 오른쪽으로 이어진 빈 칸(‘매출 □ □’)
    for i in range(hr):
        seen_text = False
        for j in range(1, ncol):
            if grid[i][j]:
                seen_text = True
            elif seen_text:
                amb_cols.add(j)
    ambiguous = bool(amb_cols)
    span_of: dict[tuple[int, int], list[int]] = {}
    for (r, c), (r0, c0) in origin.items():
        e = span_of.setdefault((r0, c0), [1, 1])
        e[0], e[1] = max(e[0], r - r0 + 1), max(e[1], c - c0 + 1)
    cells = []
    for i in range(hr):
        row = []
        for j in range(ncol):
            if (i, j) in origin:
                row.append({"j": j, "text": grid[i][j], "hide": True})
            else:
                rs, cs = span_of.get((i, j), [1, 1])
                d = {"j": j, "text": grid[i][j]}
                if cs > 1:
                    d["colspan"] = cs
                if rs > 1:
                    d["rowspan"] = rs
                row.append(d)
        cells.append(row)
    paths = []
    for j in range(ncol):
        p: list[str] = []
        for i in range(hr):
            if grid[i][j] and (not p or p[-1] != grid[i][j]):          # 위아래가 같은 글자(병합)면 한 번만
                p.append(grid[i][j])
        paths.append(p)
    return {"grid": grid, "cells": cells, "paths": paths, "ambiguous": ambiguous, "amb_cols": amb_cols}


def header_grid(rows: list, hr: int, spans: list | None = None) -> list[list[str]]:
    return header_layout(rows, hr, spans)["grid"]
