"""PDF → 블록 구조. 종류: title / paragraph / table / list / footnote.

- 글자·위치는 pdfplumber 로 읽고, 줄을 만든 뒤 간격·글자 크기·기호로 종류를 가른다.
- 숫자 값은 내보내지 않는다. 표의 숫자 칸은 {"type": "number"} 로만 표시하고, 문장 속 금액·비율·건수는 "{수치}" 로 가린다. 날짜 표현(연·월·차)은 구조 정보라 그대로 둔다.
- needs_review: (1) 종류 애매 (2) 표 열 정렬 불확실 (3) 각주 연결 불명. 코드 검사 결과이며 모델 확신도가 아니다.
"""
from __future__ import annotations

import io
import re
from collections import Counter


MAX_PAGES = 30
NUM_CELL = re.compile(r"^[△▲+\-−]?\d[\d,]*(\.\d+)?%?$")
# 날짜·차수 표현은 그대로 두고, 그 밖의 금액·비율·건수·소수·부호 숫자는 가린다
_DATE = r"'?\d{2,4}\.\d{1,2}월|\d{4}년|20\d{2}\.(?:0?[1-9]|1[0-2])\.(?:0?[1-9]|[12]\d|3[01])(?!\d)|20\d{2}\.(?:0?[1-9]|1[0-2])(?![\d.])|\d{1,2}월|\d{1,2}차"
_NUM = r"[△▲+\-−]?\d[\d,]*\.\d+\s?(?:억|%|원|건|개)?|[△▲+\-−]\d[\d,]*\s?(?:억|%|원|건|개)?|\d[\d,]*\s?(?:억|%|원|건|개)"
TOKEN = re.compile(rf"({_DATE})|({_NUM})")
BULLET = re.compile(r"^(\(cid:\d+\)|[•·▪◦●○■□▶\-–])\s*")
NUMBERED = re.compile(r"^(\d{1,2})[.)]\s+")
FOOT = re.compile(r"^(※|\*)(\d{1,2})?(?=\s)\s*")


def mask(text: str) -> tuple[str, int]:
    n = 0

    def sub(m):
        nonlocal n
        if m.group(1):
            return m.group(1)
        n += 1
        return "{수치}"
    return TOKEN.sub(sub, text), n


def _clean(t: str) -> str:
    t = re.sub(r"\(cid:\d+\)", "•", t)
    return re.sub(r"[ \t]+", " ", t.replace("\x00", " ")).strip()


class _Line:
    def __init__(self, ln: dict, page: int, lid: int = -1):
        self.lid = lid
        self.src = ln                                     # 원본 줄(글자 목록 포함): 세로 여백 기준 쪼개기에 쓴다
        self.grp = None                                   # 나란한 카드 덩어리 표시(같은 값이면 한 블록으로 묶는다)
        self.edges = ()                                   # 쪽의 도형(사각형) 바깥 경계 목록: (x, top, bottom). 칸 쪼개기의 단서
        chars = [c for c in ln["chars"] if c["text"].strip()]
        self.page = page
        self.top, self.bottom, self.x0, self.x1 = ln["top"], ln["bottom"], ln["x0"], ln["x1"]
        sizes = Counter(round(c["size"], 1) for c in chars)
        self.size = sizes.most_common(1)[0][0] if sizes else 10.0
        self.chars_n = len(chars)
        self.all_bold = bool(chars) and all("bold" in c["fontname"].lower() for c in chars)
        self.cells = self._cells(chars)
        self.raw = _clean(ln["text"])

    def _cells(self, chars):
        cells, cur, prev = [], [], None
        thr = max(8.0, 0.9 * self.size)
        for c in chars:
            if prev is not None and c["x0"] - prev["x1"] > thr:
                cells.append(cur)
                cur = []
            if prev is not None and cur and c["x0"] - prev["x1"] > 0.2 * self.size:
                cur.append({"text": " ", "x0": prev["x1"], "x1": c["x0"]})
            cur.append(c)
            prev = c
        if cur:
            cells.append(cur)
        out = []
        for cs in cells:
            real = [c for c in cs if c["text"].strip()]
            out.append({"x0": real[0]["x0"], "x1": real[-1]["x1"], "text": _clean("".join(c["text"] for c in cs))})
        return out


# ---------------- 나란히 놓인 덩어리(칸) 쪼개기 ----------------
# 같은 높이의 글자는 한 줄로 합쳐지므로, 좌우로 나란한 카드·제목·표/차트는 줄 하나에 섞인다.
# "여러 줄에 걸쳐 글자가 하나도 없는 세로 띠(gutter)"가 있으면 그 줄들을 띠 기준으로 조각으로 쪼갠다.
# 표의 열 간격과 구분하기 위해: ① 띠 폭이 24pt 이상이고 ② 3줄 이상 이어져야 하며
# ③ 양쪽에 3줄 이상 글자가 있어야 하고 ④ 띠를 건너는 줄이 3줄 이상이고 모두 2칸뿐이면(라벨-값 표) 쪼개지 않는다.
GUTTER_FRAC, GUTTER_MIN_PT, GUTTER_MIN_LINES, GUTTER_WIDE_PT, NARROW_PROSE_PT, SHRINK = 0.0, 24.0, 3, 90.0, 80.0, 0.6
PROSE_COLS_PT, GUTTER_FLOOR_PT = 110.0, 16.0          # 24pt ≈ 본문 글자 크기의 2.5배(2단 본문의 칸 사이 간격은 25~40pt). 20pt 까지 낮추면 표가 쪼개지는 오탐이 생김          # 30pt ≈ 본문 글자 크기의 3배(2단 본문의 칸 사이 간격은 보통 30pt 안팎). 24pt 까지 낮추면 표가 쪼개지는 오탐이 생김


def _free(line, lo: float, hi: float) -> list[tuple[float, float]]:
    out, cur = [], lo
    for a, b in sorted((c["x0"] - 1.0, c["x1"] + 1.0) for c in line.cells):
        if a > cur:
            out.append((cur, a))
        cur = max(cur, b)
    if cur < hi:
        out.append((cur, hi))
    return out


def _find_gutters(ls: list, W: float, gmin: float) -> list[dict]:
    """줄 순서대로 '빈 세로 띠'를 추적한다. 띠는 이어지는 모든 줄에서 비어 있는 x 구간(앞 줄들과의 교집합)이다."""
    tracks: list[tuple[float, float, int]] = []
    ended: list[tuple[float, float, int, int]] = []
    for i, l in enumerate(ls):
        fr = _free(l, 0.0, W)
        nxt: list[tuple[float, float, int]] = []
        for a, b, s0 in tracks:
            kids = [(max(a, fa), min(b, fb)) for fa, fb in fr if min(b, fb) - max(a, fa) >= gmin]
            if not kids or any(kb - ka < SHRINK * (b - a) for ka, kb in kids):          # 띠가 갑자기 좁아지면(다른 종류의 줄이 시작) 앞 구간을 거기서 끝내고 새 띠로 시작
                ended.append((a, b, s0, i - 1))                                       # 넓었던 앞 구간은 여기서 끝(카드 줄 등)
                nxt += [(ka, kb, s0) for ka, kb in kids]                              # 좁아진 띠는 앞 구간 줄도 포함해 계속(2단 본문의 칸 사이: 앞쪽 제목 줄도 같은 띠를 건넌다). 좁은 띠는 본문 폭 조건을 따로 통과해야 한다
            else:
                nxt += [(ka, kb, s0) for ka, kb in kids]
        for fa, fb in fr:
            if fb - fa >= gmin:
                nxt.append((fa, fb, i))
        best: dict[tuple[int, int], tuple[float, float, int]] = {}      # 같은 구간은 가장 먼저 시작한 것만
        for t in nxt:
            k = (round(t[0]), round(t[1]))
            if k not in best or t[2] < best[k][2]:
                best[k] = t
        tracks = list(best.values())
    ended += [(a, b, s0, len(ls) - 1) for a, b, s0 in tracks]
    out = []
    for a, b, s0, e in ended:
        if e - s0 + 1 < GUTTER_MIN_LINES:
            continue
        cells = [c for l in ls[s0:e + 1] for c in l.cells]
        if not (any(c["x1"] <= a + 1 for c in cells) and any(c["x0"] >= b - 1 for c in cells)):
            continue                                                   # 한쪽에만 글자가 있는 빈 공간
        sub = ls[s0:e + 1]
        left = lambda l: any(c["x1"] <= a + 1 for c in l.cells)
        right = lambda l: any(c["x0"] >= b - 1 for c in l.cells)
        cross = [l for l in sub if left(l) and right(l)]
        if sum(1 for l in sub if left(l)) < 3 or sum(1 for l in sub if right(l)) < 3:      # 양쪽 모두 3줄 이상의 글자가 있어야 한다(쪽 번호·날짜 같은 한 줄짜리 오른쪽 글자는 칸이 아니다)
            continue
        # 표의 열 간격 구분. 한쪽에만 글자가 있는 줄 중 '앞 줄에 바짝 붙은 줄바꿈'이 아닌 혼자 선 줄(alone)이 많으면 두 덩어리가 각자의 줄 격자를 가진 것(표+차트, 2단 본문).
        alone = 0
        for side in (left, right):
            prev = None
            for l in sub:
                if side(l):
                    if not (left(l) and right(l)) and (prev is None or l.top - prev.bottom >= 0.8 * l.size):
                        alone += 1
                    prev = l
        sizes = [l.size for l in cross]
        wide = b - a >= GUTTER_WIDE_PT
        card = wide and bool(cross) and max(sizes) - min(sizes) >= 1.5   # 넓은 띠: 건너는 줄의 글자 크기가 서로 다르면 카드(이름·큰 숫자·보조 글자)
        def prose(side, lo, hi):                                       # 그 쪽 줄들의 가장 긴 칸 폭의 75번째 백분위: 2단 본문은 칸이 길고(문장), 표의 열은 짧다(숫자·짧은 라벨)
            ws = sorted(max((c["x1"] - c["x0"] for c in l.cells if lo(c) and hi(c)), default=0) for l in sub if side(l))
            return ws[min(len(ws) - 1, int(len(ws) * 0.75))] if ws else 0         # 75번째 백분위(꼬리말·번호처럼 짧은 칸 몇 개에 흔들리지 않게)
        texty = wide or (prose(left, lambda c: c["x1"] <= a + 1, lambda c: True) >= NARROW_PROSE_PT and prose(right, lambda c: c["x0"] >= b - 1, lambda c: True) >= NARROW_PROSE_PT)
        grids = texty and len(sub) >= 8 and sum(1 for l in sub if left(l)) >= 5 and sum(1 for l in sub if right(l)) >= 5 and alone >= 3 and alone >= 0.3 * len(sub) and len(cross) <= (0.35 if not wide else 0.5) * len(sub)       # 좁은 띠(2단 본문 간격 등)는 건너는 줄이 적어야 한다: 표의 열 간격은 거의 모든 줄이 건넌다
        firsts = sorted(l.cells[0]["x1"] - l.cells[0]["x0"] for l in sub if left(l))       # 각 줄의 첫 칸 폭: 신문형 다단은 첫 칸부터 문장(긴 폭), 표는 첫 칸이 짧은 라벨
        prose_cols = len(sub) >= 12 and sum(1 for l in sub if left(l)) >= 10 and sum(1 for l in sub if right(l)) >= 10 \
            and prose(left, lambda c: c["x1"] <= a + 1, lambda c: True) >= PROSE_COLS_PT and prose(right, lambda c: c["x0"] >= b - 1, lambda c: True) >= PROSE_COLS_PT \
            and firsts[len(firsts) // 4] >= PROSE_COLS_PT                                  # 신문형 다단: 칸마다 긴 문장이 같은 높이로 이어진다(줄 격자가 같아도 열 간격이 아니라 단)
        edges = ls[0].edges if ls else ()
        covered = [l for l in cross if any(a - 6 <= ex <= b + 6 and et <= l.top + 2 and eb >= l.bottom - 2 for ex, et, eb in edges)]
        shaped = len(cross) >= 3 and len(covered) >= 0.7 * len(cross)               # 도형 단서: 띠 안에 사각형(카드·패널·표)의 바깥 경계가 있고 띠를 건너는 줄을 세로로 덮는다
        if not (card or prose_cols or shaped) and len(cross) >= 3 and all(len(l.cells) == 2 for l in cross):      # 라벨-값 두 칸 표는 쪼개지 않는다. 단, 카드(글자 크기가 다른 줄)나 긴 문장 열은 예외
            continue
        if b - a < GUTTER_MIN_PT and not (prose_cols or shaped):                      # 24pt 미만의 아주 좁은 간격은 문장 열(신문형)일 때만 단으로 본다
            continue
        if not (card or grids or prose_cols or shaped):
            continue
        out.append({"a": a, "b": b, "s": s0, "e": e, "wide": wide})
    return out


def _segment(l: _Line, centers: list[float], page: int) -> list[tuple[int, _Line]]:
    """줄을 띠 중심선 기준으로 칸별 조각으로 나눈다. 한 칸에만 글자가 있으면 원본 줄을 그대로 쓴다."""
    cols: dict[int, list] = {}
    for ch in l.src["chars"]:
        cols.setdefault(sum(1 for m in centers if m < (ch["x0"] + ch["x1"]) / 2), []).append(ch)
    if len(cols) == 1:
        return [(next(iter(cols)), l)]
    out = []
    for c in sorted(cols):
        chars, txt, prev = cols[c], [], None
        for ch in chars:
            if not ch["text"].strip():
                txt.append(" ")
            else:
                if prev is not None and ch["x0"] - prev["x1"] > 0.2 * ch.get("size", 10.0) and (not txt or txt[-1] != " "):
                    txt.append(" ")
                txt.append(ch["text"])
                prev = ch
        real = [ch for ch in chars if ch["text"].strip()]
        if not real:
            continue
        ln = {"chars": chars, "top": min(ch["top"] for ch in real), "bottom": max(ch["bottom"] for ch in real),
              "x0": min(ch["x0"] for ch in real), "x1": max(ch["x1"] for ch in real), "text": "".join(txt)}
        seg = _Line(ln, page, -1)
        if seg.chars_n:
            out.append((c, seg))
    return out


def _split_columns(ls: list[_Line], W: float, H: float, page: int) -> list[_Line]:
    """세로 여백이 있는 구간만 칸 단위 조각으로 바꾸고 읽는 순서(왼쪽 칸 위→아래, 다음 칸)로 다시 늘어놓는다. 여백이 없으면 입력을 그대로 돌려준다."""
    if len(ls) < GUTTER_MIN_LINES:
        return ls
    gs = _find_gutters(ls, W, min(GUTTER_FLOOR_PT, max(GUTTER_MIN_PT, GUTTER_FRAC * max(W, H))))
    if not gs:
        return ls
    chosen: list[list[int]] = []                                       # 줄마다 겹치지 않는 띠 선택(범위가 긴 것 우선)
    for i in range(len(ls)):
        cand = sorted((k for k, g in enumerate(gs) if g["s"] <= i <= g["e"]), key=lambda k: -(gs[k]["e"] - gs[k]["s"]))
        pick: list[int] = []
        for k in cand:
            if all(gs[k]["b"] <= gs[m]["a"] or gs[k]["a"] >= gs[m]["b"] for m in pick):
                pick.append(k)
        chosen.append(sorted(pick, key=lambda k: gs[k]["a"]))
    out: list[_Line] = []
    i, region = 0, 0
    while i < len(ls):
        if not chosen[i]:
            out.append(ls[i])
            i += 1
            continue
        j = i
        while j + 1 < len(ls) and chosen[j + 1] == chosen[i]:
            j += 1
        G = [(gs[k]["a"] + gs[k]["b"]) / 2 for k in chosen[i]]
        segs = [(idx, _segment(ls[idx], G, page)) for idx in range(i, j + 1)]
        last = max((idx for idx, ss in segs if any(c > 0 for c, _ in ss)), default=-1)      # 오른쪽 칸 글자가 끝난 뒤의 왼쪽 칸 줄은 구간 밖(뒤)으로(넓은 띠: 카드·표+차트)
        if any(not gs[k]["wide"] for k in chosen[i]):                                         # 좁은 띠(2단 본문)는 왼쪽 칸이 더 길어도 그대로 한 칸의 흐름이다
            last = j
        body = [(idx, ss) for idx, ss in segs if idx <= last]
        tail = [ls[idx] for idx, ss in segs if idx > last]
        if last < 0:
            out += [ls[idx] for idx in range(i, j + 1)]
            i = j + 1
            continue
        cols = sorted({c for _, ss in body for c, _ in ss})
        per = {c: [sg for _, ss in body for cc, sg in ss if cc == c] for c in cols}
        for c in cols:
            out += per[c]
        if len(cols) >= 3:                                              # 칸이 3개 이상 나란하면 카드 줄: 칸마다 세로로 붙은 줄(최대 4줄)을 한 덩어리로 묶는다
            for c in cols:
                chunk, prev = 0, None
                for sg in per[c]:
                    if prev is not None and (sg.top - prev.bottom > 1.2 * max(prev.size, sg.size) or sg.top < prev.top):
                        chunk += 1
                    sg.grp, prev = (region, c, chunk), sg
        region += 1
        out += tail
        i = j + 1
    return out


def _rect_like(c: dict) -> bool:
    """닫힌 경로의 점들이 모두 바깥 사각형의 네 변 위에 있으면 사각형."""
    pts = c.get("pts") or []
    if not (4 <= len(pts) <= 6):
        return False
    xs, ys = (c["x0"], c["x1"]), (c["top"], c["bottom"])
    return all(any(abs(p[0] - x) < 0.6 for x in xs) and any(abs(p[1] - y) < 0.6 for y in ys) for p in pts)


def _boundary_edges(rects: list[tuple]) -> list[tuple]:
    """사각형들의 '바깥 경계'(맞닿은 사각형이 없는 왼쪽·오른쪽 변)를 세로로 이어 붙여 (x, top, bottom) 목록으로. 표 안쪽 칸 경계(서로 맞닿은 사각형)는 제외한다. 사각형이 너무 많으면 쓰지 않는다."""
    if not rects or len(rects) > 300:
        return []
    raw = []
    for i, (x0, t, x1, b) in enumerate(rects):
        for x, other in ((x0, 1), (x1, 0)):
            touching = False
            for j, r2 in enumerate(rects):
                if j != i and abs(r2[other * 2] - x) <= 3 and min(b, r2[3]) - max(t, r2[1]) >= 0.5 * (b - t):
                    touching = True
                    break
            if not touching:
                raw.append((x, t, b))
    raw.sort()
    out: list[list[float]] = []
    for x, t, b in raw:
        for o in out:
            if abs(o[0] - x) <= 2 and t <= o[2] + 3 and b >= o[1] - 3:      # 같은 x 에서 세로로 이어지는 변은 하나로
                o[1], o[2] = min(o[1], t), max(o[2], b)
                break
        else:
            out.append([x, t, b])
    return [tuple(o) for o in out if o[2] - o[1] >= 40]


def _bbox(lines: list[_Line]) -> list[float]:
    return [round(min(l.x0 for l in lines), 1), round(min(l.top for l in lines), 1), round(max(l.x1 for l in lines), 1), round(max(l.bottom for l in lines), 1)]


def _flag(code: str, reason: str, detail: dict | None = None) -> dict:
    f = {"code": code, "reason": reason}
    if detail:
        f["detail"] = detail                      # 어느 부분이 다른지(단어·줄)를 화면에 보여 주기 위한 참고 정보
    return f


def _table(lines: list[_Line], unit_rx=re.compile(r"단위")) -> tuple[dict, list[dict]]:
    flags, rows = [], []
    ncols = Counter(len(l.cells) for l in lines).most_common(1)[0][0]
    for l in lines:
        rows.append([{"type": "number"} if NUM_CELL.match(c["text"]) else {"type": "text", "text": mask(c["text"])[0]} for c in l.cells])
    if any(len(l.cells) != ncols for l in lines):
        flags.append(_flag("table_alignment", f"행마다 칸 수가 다릅니다(대부분 {ncols}칸)"))
    bad = []
    for j in range(ncols):
        cs = [l.cells[j] for l in lines if len(l.cells) == ncols]
        L, R = [c["x0"] for c in cs], [c["x1"] for c in cs]
        C = [(c["x0"] + c["x1"]) / 2 for c in cs]
        if not any(max(v) - min(v) <= 4.0 for v in (L, R, C)):
            bad.append(j + 1)
    if bad:
        flags.append(_flag("table_alignment", f"열 {', '.join(map(str, bad))} 의 칸 위치가 왼쪽·오른쪽·가운데 어느 쪽으로도 맞지 않습니다"))
    header_rows = 1 if not any(c["type"] == "number" for c in rows[0]) and any(c["type"] == "number" for r in rows[1:] for c in r) else 0
    unit = next((c["text"] for c in rows[0] if c["type"] == "text" and unit_rx.search(c["text"])), None)
    return {"columns": ncols, "header_rows": header_rows, "unit_hint": unit, "rows": rows,
            "number_cells": sum(c["type"] == "number" for r in rows for c in r)}, flags


def _list_items(lines: list[_Line]) -> list[dict]:
    items, parent = [], None
    for l in lines:
        m = BULLET.match(l.raw)
        n = NUMBERED.match(l.raw)
        body = (l.raw[m.end():] if m else l.raw[n.end():]) if (m or n) else l.raw
        text, cnt = mask(body)
        it = {"text": text, "marker": ("•" if m else n.group(1) + "."), "level": 0, "children": [], "numbers_masked": cnt}
        if m and parent is not None:
            it["level"] = 1
            parent["children"].append(it)
        else:
            items.append(it)
            parent = it if n else None
    return items


def read_pdf(pdf_bytes: bytes, *, with_images: bool = False, resolution: int = 100, masked_images: bool = False):
    """PDF → (페이지 정보, 페이지별 줄 목록, 본문 글자 크기, 이미지 PNG 목록). masked_images=True 면 숫자 칸을 검게 칠한 이미지(AI 전송용)."""
    import pdfplumber
    pages, images = [], []
    all_lines: list[list[_Line]] = []
    sizes, lid = Counter(), 0
    with pdfplumber.open(io.BytesIO(pdf_bytes)) as pdf:
        if len(pdf.pages) > MAX_PAGES:
            raise ValueError(f"페이지가 너무 많습니다(최대 {MAX_PAGES})")
        for pi, page in enumerate(pdf.pages, 1):
            pages.append({"page": pi, "width": round(float(page.width), 1), "height": round(float(page.height), 1)})
            ls = []
            for l in page.extract_text_lines(return_chars=True):
                if not l["text"].strip():
                    continue
                ln = _Line(l, pi, -1)
                if ln.chars_n:
                    ls.append(ln)
            try:
                shapes = list(page.rects) + [c for c in page.curves if _rect_like(c)]            # 사각형은 're' 로 그려지기도, 닫힌 경로로 그려지기도 한다
                edges = _boundary_edges([(r["x0"], r["top"], r["x1"], r["bottom"]) for r in shapes if (r["x1"] - r["x0"]) >= 20 and (r["bottom"] - r["top"]) >= 6])
            except Exception:  # noqa: BLE001  (도형을 못 읽어도 글자 줄 처리는 계속한다)
                edges = []
            for ln in ls:
                ln.edges = edges
            ls = _split_columns(ls, float(page.width), float(page.height), pi)       # 나란한 칸이 있으면 조각으로 쪼갠다(없으면 그대로)
            for ln in ls:
                ln.lid = lid
                lid += 1
            all_lines.append(ls)
            for l in ls:
                sizes[l.size] += l.chars_n
            if with_images or masked_images:
                img = page.to_image(resolution=resolution).original.convert("RGB")
                if masked_images:
                    _mask_image(img, page, resolution / 72.0)
                buf = io.BytesIO()
                img.save(buf, format="PNG")
                images.append(buf.getvalue())
    body = sizes.most_common(1)[0][0] if sizes else 10.0
    return pages, all_lines, body, images


def _mask_image(img, page, scale: float) -> None:
    """금액·비율·숫자 단어를 검게 칠한다(AI 에 숫자가 가지 않게). 연·월 같은 날짜 표현은 남긴다."""
    from PIL import ImageDraw
    d = ImageDraw.Draw(img)
    for w in page.extract_words():
        t = w["text"]
        if NUM_CELL.match(t) or mask(t)[1] > 0:
            d.rectangle([w["x0"] * scale - 1, w["top"] * scale - 1, w["x1"] * scale + 1, w["bottom"] * scale + 1], fill=(0, 0, 0))


def is_image(b: bytes) -> bool:
    return b.startswith(b"\x89PNG") or b.startswith(b"\xff\xd8")


def read_image(data: bytes):
    """PNG/JPG 한 장 → read_pdf 와 같은 모양. 글자 줄이 없으니 AI 로만 읽을 수 있다."""
    from PIL import Image
    im = Image.open(io.BytesIO(data)).convert("RGB")
    buf = io.BytesIO()
    im.save(buf, format="PNG")
    return [{"page": 1, "width": float(im.width), "height": float(im.height)}], [[]], 10.0, [buf.getvalue()]


def extract_structure(pdf_bytes: bytes, *, with_images: bool = False, resolution: int = 100, use_ai: bool = False, allow_raw_image: bool = False, engine: str = "vlm", known: dict | None = None) -> dict:
    blocks: list[dict] = []
    if is_image(pdf_bytes):
        pages, all_lines, body, images = read_image(pdf_bytes)
    else:
        pages, all_lines, body, images = read_pdf(pdf_bytes, with_images=with_images, resolution=resolution)
    for pi, ls in enumerate(all_lines, 1):
        page_h = pages[pi - 1]["height"]
        blocks += _page_blocks(ls, pi, body, page_h, first_page=(pi == 1))
    for i, b in enumerate(blocks, 1):
        b["id"] = f"b{i}"
    _link(blocks, body)
    vinfo = {"used": False, "mode": "rules", "note": "VLM 호출 없음 — 코드 규칙만으로 추출했습니다."}
    if use_ai:
        from . import ai_extract
        if engine == "lines":                                  # 예전 방식(줄 묶기만 AI): 비교용
            blocks, vinfo = ai_extract.refine_with_ai(pdf_bytes, pages, all_lines, blocks, body, allow_raw_image=allow_raw_image, images=images if is_image(pdf_bytes) else None)
        else:                                                   # 기본: VLM 이 주역
            rule_blocks = blocks
            blocks, vinfo = ai_extract.vlm_primary(pages, all_lines, rule_blocks, body, allow_raw_image=allow_raw_image, images=images if is_image(pdf_bytes) else None, pdf_bytes=pdf_bytes, known=known)
            if vinfo.get("used"):
                vinfo["rule_summary"] = dict(Counter(b["kind"] for b in rule_blocks))
    kinds = Counter(b["kind"] for b in blocks)
    stats = {"pages": len(pages), "blocks": len(blocks), "by_kind": dict(kinds), "needs_review": sum(1 for b in blocks if b["needs_review"]),
             "body_font_size": body}
    out = {"pages": pages, "blocks": blocks, "stats": stats, "vlm": vinfo}
    if with_images:
        out["_images"] = images
    return out


def _is_cand_row(l: _Line) -> bool:
    return len(l.cells) >= 3 and not BULLET.match(l.raw) and not NUMBERED.match(l.raw)


def _page_blocks(ls: list[_Line], pi: int, body: float, page_h: float, first_page: bool) -> list[dict]:
    out, i, title_done = [], 0, False
    while i < len(ls):
        l = ls[i]
        gapf = lambda a, b: b.top - a.bottom
        # 표: 칸이 3개 이상인 줄이 이어질 때
        if _is_cand_row(l):
            j = i
            while j + 1 < len(ls) and _is_cand_row(ls[j + 1]) and gapf(ls[j], ls[j + 1]) < 3 * l.size:
                j += 1
            grp = ls[i:j + 1]
            if len(grp) >= 2:
                t, flags = _table(grp)
                out.append({"kind": "table", "page": pi, "bbox": _bbox(grp), "table": t, "needs_review": flags, "line_ids": [g.lid for g in grp]})
                i = j + 1
                continue
        # 각주(※, *): 본문보다 작거나 맨 아래에 있거나 기호로 시작
        fm = FOOT.match(l.raw)
        prev_size = ls[i - 1].size if i > 0 else l.size
        if fm and (l.raw.startswith("※") or l.size < prev_size - 0.4 or l.size < body * 0.95 or l.top > page_h * 0.85):
            text, cnt = mask(l.raw[fm.end():])
            out.append({"kind": "footnote", "page": pi, "bbox": _bbox([l]), "marker": ("※" if l.raw.startswith("※") else "*") + (fm.group(2) or ""),
                        "text": text, "numbers_masked": cnt, "link": None, "needs_review": [], "line_ids": [l.lid]})
            i += 1
            continue
        # 목록: 번호(1.)·불릿 줄이 이어질 때
        if BULLET.match(l.raw) or NUMBERED.match(l.raw):
            j = i
            while j + 1 < len(ls) and (BULLET.match(ls[j + 1].raw) or NUMBERED.match(ls[j + 1].raw)) and gapf(ls[j], ls[j + 1]) < 3 * l.size:
                j += 1
            grp = ls[i:j + 1]
            out.append({"kind": "list", "page": pi, "bbox": _bbox(grp), "items": _list_items(grp), "needs_review": [], "line_ids": [g.lid for g in grp]})
            i = j + 1
            continue
        # 제목
        flags = []
        if first_page and not title_done and l.size >= body * 1.3:
            kind, level = "title", 1
            title_done = True
        elif re.match(r"^\[.*\]$", l.raw):
            kind, level = "title", 2
        elif l.all_bold and l.size >= body and len(l.cells) == 1:
            kind, level = "title", 2
            flags.append(_flag("kind_ambiguous", "굵은 글씨 한 줄이라 소제목인지 강조 문장인지 코드로 가를 수 없습니다"))
        else:
            kind, level = "paragraph", None
        if kind == "paragraph":
            j = i
            while j + 1 < len(ls) and ((abs(ls[j + 1].size - l.size) < 0.5 and abs(ls[j + 1].x0 - l.x0) < 3 and gapf(ls[j], ls[j + 1]) < 0.6 * l.size) or (l.grp is not None and ls[j + 1].grp == l.grp)) \
                    and not _is_cand_row(ls[j + 1]) and not BULLET.match(ls[j + 1].raw) and not NUMBERED.match(ls[j + 1].raw) and not FOOT.match(ls[j + 1].raw):
                j += 1
            grp = ls[i:j + 1]
            if len(l.cells) == 2:
                flags.append(_flag("kind_ambiguous", "한 줄에 간격이 큰 두 덩어리가 있어 문장인지 표의 한 줄인지 가를 수 없습니다"))
            text, cnt = mask(" ".join(g.raw for g in grp))
            out.append({"kind": "paragraph", "page": pi, "bbox": _bbox(grp), "text": text, "numbers_masked": cnt, "needs_review": flags, "line_ids": [g.lid for g in grp]})
            i = j + 1
        else:
            text, cnt = mask(l.raw)
            out.append({"kind": "title", "page": pi, "bbox": _bbox([l]), "level": level, "text": text, "numbers_masked": cnt, "needs_review": flags, "line_ids": [l.lid]})
            i += 1
    return out


def _link(blocks: list[dict], body: float) -> None:
    """각주 연결. 본문에 같은 번호 기호(※1, *1)가 있으면 확정, 아니면 바로 앞 블록과 가까움(추측)으로만 이어 확인 필요로 표시."""
    for k, b in enumerate(blocks):
        if b["kind"] != "footnote":
            continue
        prev = blocks[k - 1] if k > 0 and blocks[k - 1]["page"] == b["page"] else None
        nxt = blocks[k + 1] if k + 1 < len(blocks) and blocks[k + 1]["page"] == b["page"] else None
        numbered = re.search(r"\d", b["marker"])
        explicit = bool(numbered) and any(o is not b and o["kind"] in ("paragraph", "title") and b["marker"] in o.get("text", "") for o in blocks)
        if prev is not None and prev["kind"] != "footnote" and b["bbox"][1] - prev["bbox"][3] < 30:
            b["link"] = {"to": prev["id"], "basis": "marker" if explicit else "proximity"}
        if nxt is not None and nxt["kind"] == "list" and nxt["bbox"][1] - b["bbox"][3] < 30:
            b["attached_list"] = nxt["id"]
            nxt["footnote_of"] = b["id"]
        if not explicit:
            why = "본문에 같은 번호 기호가 없어 어느 블록에 붙은 각주인지 확정할 수 없습니다"
            if b["link"] is None:
                why += "(바로 앞에 이어지는 블록도 없음)"
            else:
                why += f"(바로 앞 블록 {b['link']['to']} 에 붙은 것으로 추측)"
            b["needs_review"].append(_flag("footnote_link", why))
