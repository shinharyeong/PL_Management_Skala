"""블록 정리: 만들어진 블록(AI 결과든 코드 규칙 결과든)을 도형·줄 좌표로 한 번 더 다듬는다. 코드만 쓴다(AI 호출·API 없음).

너무 잘게 쪼개진 것(차트 막대 라벨·축 숫자, 표 머리 조각, 소제목과 그 항목)은 합치고,
잘못 합쳐진 것(나란한 카드·제목, 쪽 하단 각주)은 쪼갠다. 규칙 7개를 순서대로 적용한다:
  chart → cards → table_header → headings → hierarchy → footnote → labels
- 규칙마다 입력 블록을 바꾸지 않고 새 목록을 만든다. 적용 뒤 '모든 글자 줄은 정확히 한 블록에 속한다'를 검사해 어긋나면 그 규칙은 되돌린다.
- 임계값은 쪽의 글자 크기 중앙값(med)이나 쪽 크기 비율로만 정한다(문서·문구·좌표로 분기하지 않는다). 경계 근처(±_BAND)는 적용하지 않고 tidy_uncertain 만 남긴다.
- 적용한 블록에는 b["tidy"] = ["tidy:규칙"] 을 남기고 notes 로도 돌려준다.
"""
from __future__ import annotations

import re
from statistics import median

from . import extract as X
from . import table_header as TH

# ---- 임계값(이름·근거). 길이 단위는 med = 그 쪽 글자 크기 중앙값 ----
_BAND = 0.15              # 임계값의 ±15% 안은 '불확실'
BAR_MIN = 3               # 막대 묶음의 최소 개수(비교가 되려면 셋은 있어야 한다)
BAR_THICK_TOL = (0.6, 1.6)  # 한 묶음 막대의 두께는 중앙값의 0.6~1.6배
BAR_LEN_VARY = 0.10       # 막대는 길이가 서로 10% 이상 다르다(표의 줄무늬 배경은 한 칸 건너 칠해도 길이가 모두 같다)
LABEL_SIZE_MAX = 1.1      # 막대 이름·축 숫자는 본문 글자 크기 중앙값의 1.1배 이하(그보다 크거나 굵은 글자는 차트 제목)
BAR_AREA_MAX = 0.5        # 막대 묶음이 쪽의 절반을 넘으면 막대가 아니다
CHART_LABEL_REACH = 6.0   # 막대 이름은 막대 끝에서 글자 크기(med)의 6배 이내(막대와 이름 사이 눈금선·여백)
CHART_AXIS_GAP = 2.5      # 축 숫자는 막대 묶음 끝에서 글자 크기의 2.5배 이내
CARD_SIZE_DIFF = 1.5      # 카드: 같은 칸의 줄 글자 크기가 서로 1.5pt 이상 다르다(이름·큰 숫자·보조 글자). extract._find_gutters 의 카드 기준과 같다
CARD_GAP_MIN = 3.0        # 나란한 카드 사이 간격은 글자 크기의 3배 이상
HEADER_RECT_MAX = 6.0     # 표 머리 배경 사각형의 높이는 표 첫 줄 글자 크기의 6배 이하(쪽 전체 배경이 아니다)
HEADER_FRAG_LINES = 2     # 표 머리 조각 블록은 1~2줄
HEADER_GAP = 1.5          # 머리 조각과 그 아래 줄 사이 간격은 글자 크기의 1.5배 이하(줄 간격 안팎)
TITLE_MAX_CHARS = 40      # 제목형 조각의 최대 글자 수
TITLE_SIZE = 1.15         # 제목형 줄은 본문 글자 크기 중앙값의 1.15배 이상(굵기만으로는 표 머리·강조 글자와 가를 수 없다)
HIER_GAP = 1.2            # 소제목과 첫 항목, 항목 사이 간격은 글자 크기의 1.2배 이하
HIER_INDENT = 0.5         # 항목은 소제목보다 글자 크기의 0.5배 이상 들여쓴다
FOOT_RATIO = 0.92         # 각주 글자는 쪽 글자 크기 중앙값의 92% 미만
FOOT_ZONE = 0.85          # 쪽 높이의 85% 아래쪽
FOOT_RULE = 3.0           # 또는 바로 위(글자 크기의 3배 이내)에 가로선이 있다
LABEL_CHARS = 12          # 잔 라벨: 12자 이하 한 줄
LABEL_NEAR = 1.5          # 표·차트 경계에서 글자 크기의 1.5배 이내

SENT_END = re.compile(r"[.!?。]$|다\.?$|요\.?$")
HEAD_MARK = re.compile(r"^(?:[가-힣]\.|[A-Za-z]\.|[IVXⅠⅡⅢⅣⅤ]+\.|\d{1,2}[.)]|[•·▪◦●○■□▶\-–—※*])\s*")
FOOT_MARK = re.compile(r"^(?:※|\*\d{0,2}|\d{1,2}\))\s*")
_STALE = ("이 블록의 줄 사이에", "한 줄에 간격이 큰 두 덩어리")


def _lid(n) -> int:
    return n if isinstance(n, int) else int(n[:-1])


def _key(n) -> tuple:
    return (n, "") if isinstance(n, int) else (int(n[:-1]), n[-1])


class _C:
    """한 쪽의 정리 환경: 줄(이름→줄), 도형, 쪽 크기, 글자 크기 중앙값."""

    def __init__(self, lines, shapes, page_size, pieces):
        self.W, self.H = page_size
        self.L: dict = {l.lid: l for l in lines}
        self.pieces = pieces
        self.L.update(pieces)
        self.shapes = shapes or []
        sizes = [l.size for l in lines]
        self.med = median(sizes) if sizes else 10.0
        self.page_lines = list(lines)

    def lines(self, b) -> list:
        return [self.L[n] for n in b.get("line_ids") or [] if n in self.L]


def _cp(b: dict) -> dict:
    return {**b, "needs_review": list(b["needs_review"]), **({"tidy": list(b["tidy"])} if "tidy" in b else {}), **({"_g": list(b["_g"])} if "_g" in b else {})}


def _mark(b: dict, rule: str) -> dict:
    t = f"tidy:{rule}"
    if t not in b.setdefault("tidy", []):
        b["tidy"].append(t)
    return b


def _uncertain(b: dict, why: str) -> dict:
    if not any(f["code"] == "tidy_uncertain" and f["reason"] == why for f in b["needs_review"]):
        b["needs_review"].append(X._flag("tidy_uncertain", why))
    return b


def _unsure(v: float, thr: float) -> bool:
    return abs(v - thr) <= _BAND * abs(thr)


def _inherit(src: dict | list, extra_g: bool = True) -> dict:
    """새 블록이 물려받는 것: 출처 표시(from_*), 원래 AI 묶음 번호(_g), 낡지 않은 노란 표시."""
    srcs = src if isinstance(src, list) else [src]
    out: dict = {"needs_review": []}
    for s in srcs:
        for k in ("from_ai", "from_vlm", "from_rules_fill"):
            if k in s:
                out[k] = s[k]
        if "_g" in s:
            out.setdefault("_g", [])
            out["_g"] += [g for g in s["_g"] if g not in out["_g"]]
        for f in s["needs_review"]:
            if not f["reason"].startswith(_STALE) and f not in out["needs_review"]:
                out["needs_review"].append(f)
    return out


def _mk(kind: str, names: list, c: _C, src, page: int, level: int = 2) -> dict:
    """줄(이름) 목록으로 블록을 새로 만든다. 글자는 PDF 줄에서 읽는다(숫자는 가림)."""
    ls = [c.L[n] for n in names]
    b = {"kind": kind, "page": page, "bbox": X._bbox(ls), "line_ids": list(names), **_inherit(src)}
    if kind == "title":
        text, cnt = X.mask(" ".join(l.raw for l in ls))
        b.update({"level": level, "text": text, "numbers_masked": cnt})
    elif kind == "list":
        b["items"] = X._list_items(ls)
    elif kind == "footnote":
        raw = " ".join(l.raw for l in ls)
        m = FOOT_MARK.match(raw)
        text, cnt = X.mask(raw[m.end():] if m else raw)
        b.update({"marker": (m.group(0).strip() if m else "※")[:4], "text": text, "numbers_masked": cnt, "link": None})
    else:
        text, cnt = X.mask(" ".join(l.raw for l in ls))
        b.update({"kind": "paragraph", "text": text, "numbers_masked": cnt})
    return b


def _replace(blocks: list, old: set, new: list) -> list:
    at = min(old)
    return [b for i, b in enumerate(blocks) if i < at and i not in old] + new + [b for i, b in enumerate(blocks) if i > at and i not in old]


def _owner(blocks: list) -> dict:
    return {n: i for i, b in enumerate(blocks) for n in b.get("line_ids") or []}


def _in(box, x, y) -> bool:
    return box[0] <= x <= box[2] and box[1] <= y <= box[3]


def _center(l) -> tuple:
    return (l.x0 + l.x1) / 2, (l.top + l.bottom) / 2


def _plain(b: dict) -> bool:
    return b["kind"] in ("title", "paragraph", "list", "footnote") and bool(b.get("line_ids"))


# ============ 규칙 1: 차트 영역 묶기 ============
def _find_bars(c: _C) -> list[dict]:
    """같은 기준선에서 자라는, 두께가 비슷하고 길이가 서로 다른 채움 사각형 3개 이상 = 막대 묶음. 반환: [{orient, bars, bbox}]"""
    rects = [s for s in c.shapes if s["kind"] == "rect" and s["filled"]]
    out, used = [], set()
    for orient, (e0, e1), (t0, t1) in (("h", (0, 2), (1, 3)), ("v", (1, 3), (0, 2))):       # h: 가로 막대(기준선은 x, 쌓이는 방향 y)
        for B in sorted({round(r["bbox"][i]) for r in rects for i in (e0, e1)}):
            mem = [r for r in rects if r["id"] not in used and (abs(r["bbox"][e0] - B) <= 1.5 or abs(r["bbox"][e1] - B) <= 1.5) and r["bbox"][e1] - r["bbox"][e0] > 1.5]
            if len(mem) < BAR_MIN:
                continue
            tm = median(r["bbox"][t1] - r["bbox"][t0] for r in mem)
            mem = sorted((r for r in mem if BAR_THICK_TOL[0] * tm <= r["bbox"][t1] - r["bbox"][t0] <= BAR_THICK_TOL[1] * tm), key=lambda r: r["bbox"][t0])
            if len(mem) < BAR_MIN or any(a["bbox"][t1] > b["bbox"][t0] + 1 for a, b in zip(mem, mem[1:])):
                continue                                                  # 쌓이는 방향으로 서로 겹치면 막대가 아니다
            lens = [r["bbox"][e1] - r["bbox"][e0] for r in mem]
            if (max(lens) - min(lens)) / max(lens) < BAR_LEN_VARY:
                continue                                                  # 길이가 같은 줄무늬(표 배경)
            bb = [min(r["bbox"][0] for r in mem), min(r["bbox"][1] for r in mem), max(r["bbox"][2] for r in mem), max(r["bbox"][3] for r in mem)]
            if (bb[2] - bb[0]) * (bb[3] - bb[1]) > BAR_AREA_MAX * c.W * c.H:
                continue
            used |= {r["id"] for r in mem}
            out.append({"orient": orient, "bars": mem, "bbox": bb})
    return out


def r_chart(blocks: list, c: _C) -> tuple[list, list]:
    groups = _find_bars(c)
    notes = []
    for g in groups:
        o, bb = g["orient"], g["bbox"]
        s = (lambda b: (b[1], b[3], b[0], b[2])) if o == "h" else (lambda b: (b[0], b[2], b[1], b[3]))      # (쌓이는 방향 시작·끝, 따라 자라는 방향 시작·끝)
        S0, S1, A0, A1 = s(bb)
        t = median(s(r["bbox"])[1] - s(r["bbox"])[0] for r in g["bars"])
        own = _owner(blocks)
        labels, cats, rows_ = [], [], []
        for l in c.page_lines:
            if l.lid not in own or blocks[own[l.lid]]["kind"] in ("table", "chart", "numbercard") or l.size > LABEL_SIZE_MAX * c.med:
                continue
            ls0, ls1, la0, la1 = s([l.x0, l.top, l.x1, l.bottom])
            cs_list = [((x["x0"] + x["x1"]) / 2) for x in l.cells] if o == "v" else [(l.top + l.bottom) / 2]       # 쌓이는 방향의 위치(세로 막대면 칸마다)
            on_bar = [any(s(r["bbox"])[0] - 0.5 * t <= cs <= s(r["bbox"])[1] + 0.5 * t for r in g["bars"]) for cs in cs_list]
            before, after = la1 <= A0 + 2 and A0 - la1 <= CHART_LABEL_REACH * c.med, la0 >= A1 - 2 and la0 - A1 <= CHART_LABEL_REACH * c.med
            nums = all(X.NUM_CELL.match(w) for w in l.raw.split())
            if sum(on_bar) >= max(1, 0.5 * len(cs_list)) and (before or after) and not nums:
                rows_.append((l, "lo" if before else "hi", sum(on_bar)))
            ov = min(la1, A1) - max(la0, A0)
            if nums and ((ls0 >= S1 - 2 and ls0 - S1 <= CHART_AXIS_GAP * c.med) or (ls1 <= S0 + 2 and S0 - ls1 <= CHART_AXIS_GAP * c.med)) and ov >= 0.5 * (la1 - la0):
                labels.append(l.lid)                                   # 축 숫자
        if rows_:                                                      # 막대 이름은 한쪽에만 있다: 막대에 맞는 칸이 더 많은 쪽만 쓴다(반대쪽의 제목·꼬리글은 이름이 아니다)
            weight = {"lo": sum(w for _, sd, w in rows_ if sd == "lo"), "hi": sum(w for _, sd, w in rows_ if sd == "hi")}
            side = "lo" if weight["lo"] >= weight["hi"] else "hi"
            for l, sd, _ in rows_:
                if sd == side:
                    labels.append(l.lid)
                    cats.append(l)
        # 블록 단위로만 흡수한다: 블록의 줄이 모두 라벨일 때
        take = set()
        for i, b in enumerate(blocks):
            ids = b.get("line_ids") or []
            if ids and b["kind"] in ("title", "paragraph", "list", "footnote") and all(n in labels for n in ids):
                take.add(i)
        keep_ids = [n for i in sorted(take) for n in blocks[i]["line_ids"]]
        ex = next((i for i, b in enumerate(blocks) if b["kind"] == "chart" and b["page"] and _overlap(b["bbox"], bb) >= 0.3), None)
        ids = sorted(keep_ids, key=_key)
        ls = [c.L[n] for n in ids]
        box = X._bbox(ls + [_Box(bb)]) if ls else [round(v, 1) for v in bb]
        page = blocks[0]["page"] if blocks else 1
        srcs = [blocks[i] for i in take]
        if ex is not None:
            nb = _cp(blocks[ex])
            nb["bbox"], nb["line_ids"] = box, sorted(set(nb.get("line_ids") or []) | set(ids), key=_key)
            if "_g" in nb or any("_g" in x for x in srcs):
                nb["_g"] = sorted({*nb.get("_g", []), *[g_ for x in srcs for g_ in x.get("_g", [])]})
            old = set(take) | {ex}
        else:
            cat_txt = [X.mask(x["text"])[0] for l in sorted(cats, key=lambda l: l.top if o == "h" else l.x0) for x in (l.cells if o == "v" else [{"text": l.raw}])]
            nb = {"kind": "chart", "page": page, "bbox": box, "line_ids": ids, "chart_type": "bar", "title": "", "categories": cat_txt[:40], "series": [], **_inherit(srcs or [{"needs_review": []}])}
            if not srcs:
                nb["from_ai"], nb["from_vlm"] = False, False
            old = set(take)
        if not old:
            at = next((k for k, x in enumerate(blocks) if x["bbox"][1] > box[1]), len(blocks))
            blocks = blocks[:at] + [_mark(nb, "chart")] + blocks[at:]
        else:
            blocks = _replace(blocks, old, [_mark(nb, "chart")])
        notes.append({"rule": "chart", "bars": len(g["bars"]), "line_ids": ids})
    return blocks, notes


class _Box:
    """X._bbox 가 줄처럼 읽을 수 있는 상자(막대 묶음 영역)."""

    def __init__(self, bb):
        self.x0, self.top, self.x1, self.bottom = bb


def _overlap(a, b) -> float:
    w, h = min(a[2], b[2]) - max(a[0], b[0]), min(a[3], b[3]) - max(a[1], b[1])
    area_b = max(1e-6, (b[2] - b[0]) * (b[3] - b[1]))
    return max(0.0, w) * max(0.0, h) / area_b


# ============ 규칙 2: 채움 사각형(카드)·세로로 이어진 간격으로 나란한 카드 쪼개기 ============
def _cell_rect(l, c: _C):
    """줄 중심이 들어 있는 가장 작은 채움 사각형(쪽의 절반 넘는 배경 제외)."""
    cx, cy = _center(l)
    best = None
    for s in c.shapes:
        if s["kind"] == "rect" and s["filled"] and _in(s["bbox"], cx, cy) and (s["bbox"][2] - s["bbox"][0]) * (s["bbox"][3] - s["bbox"][1]) <= 0.5 * c.W * c.H:
            a = (s["bbox"][2] - s["bbox"][0]) * (s["bbox"][3] - s["bbox"][1])
            if best is None or a < best[0]:
                best = (a, s["id"])
    return best[1] if best else None


def _common_cut(ls: list):
    """모든 줄이 같은 자리에 가진 후보(세로로 이어짐 v) 하나: [(줄, 후보 번호)] 와 폭. 없으면 None."""
    first = ls[0]
    for c0 in first.cuts:
        if not c0.get("vert"):
            continue
        picks, a, b = [(first, c0["cut"])], c0["a"], c0["b"]
        for l in ls[1:]:
            hit = next((x for x in l.cuts if x.get("vert") and min(b, x["b"]) - max(a, x["a"]) >= X.CUT_VERT_OVERLAP_PT), None)
            if hit is None:
                break
            a, b = max(a, hit["a"]), min(b, hit["b"])
            picks.append((l, hit["cut"]))
        else:
            return picks, b - a
    return None


def _card_run(blocks: list, i: int, c: _C) -> list[int]:
    """i 번 블록에서 시작해, 같은 자리에 세로로 이어진 간격을 공유하는 한 줄짜리 문장 블록이 연달아 있으면 그 순번들(카드의 이름·숫자·보조 줄이 줄마다 따로 블록이 된 경우)."""
    run = [i]
    while True:
        nb = blocks[run[-1] + 1] if run[-1] + 1 < len(blocks) else None
        prev = c.lines(blocks[run[-1]])
        if nb is None or nb["kind"] != "paragraph" or len(nb["line_ids"]) != 1 or len(c.lines(nb)) != 1 or len(prev) != 1:
            return run
        cand = [c.lines(blocks[k])[0] for k in run] + c.lines(nb)
        if cand[-1].top - cand[-2].bottom > 1.5 * max(cand[-1].size, cand[-2].size) or not _common_cut(cand):
            return run
        run.append(run[-1] + 1)


def r_cards(blocks: list, c: _C) -> tuple[list, list]:
    notes = []
    i = 0
    while i < len(blocks):
        b = blocks[i]
        ls = c.lines(b)
        if b["kind"] in ("table", "chart") or not ls or len(ls) != len(b["line_ids"]):
            i += 1
            continue
        groups: list[list] | None = None
        how = ""
        span = [i]
        rids = [_cell_rect(l, c) for l in ls]
        if len(ls) >= 2 and len({r for r in rids if r is not None}) >= 2 and None not in rids:               # (가) 서로 다른 채움 사각형 안에 있고 사각형이 가로로 나란히
            rs = {r: c.shapes[r]["bbox"] for r in set(rids)}
            side = all(min(a[3], d[3]) - max(a[1], d[1]) >= 0.5 * min(a[3] - a[1], d[3] - d[1]) and (a[2] <= d[0] + 1 or d[2] <= a[0] + 1) for k1, a in rs.items() for k2, d in rs.items() if k1 < k2)
            if side:
                order = sorted(set(rids), key=lambda r: rs[r][0])
                groups, how = [[n for n, r in zip(b["line_ids"], rids) if r == o] for o in order], "rect"
        if groups is None and b["kind"] in ("paragraph", "numbercard"):                                        # (나) 같은 자리에 세로로 이어진 간격 + 줄마다 글자 크기가 다른 카드. 줄마다 따로 블록이어도 이어서 본다
            if b["kind"] == "paragraph" and len(ls) == 1 and len(b["line_ids"]) == 1:
                span = _card_run(blocks, i, c)
            ls2 = [l for k in span for l in c.lines(blocks[k])]
            if len(ls2) >= 2 and max(l.size for l in ls2) - min(l.size for l in ls2) >= CARD_SIZE_DIFF:
                cc = _common_cut(ls2)
                if cc and cc[1] >= CARD_GAP_MIN * c.med:
                    left, right = [], []
                    for l, n in cc[0]:
                        ps = X.split_line(l, [n])
                        if len(ps) != 2:
                            break
                        c.pieces[ps[0].name], c.pieces[ps[1].name] = ps[0], ps[1]
                        c.L[ps[0].name], c.L[ps[1].name] = ps[0], ps[1]
                        left.append(ps[0].name)
                        right.append(ps[1].name)
                    else:
                        groups, how = [left, right], "cut"
        if groups is None or len(groups) < 2 or not all(groups):
            i += 1
            continue
        src = [blocks[k] for k in span]
        if b["kind"] == "numbercard":
            cards = b.get("cards") or []
            if len(cards) != len(groups):
                blocks = blocks[:i] + [_uncertain(_cp(b), "나란한 카드로 보이지만 카드 개수와 맞지 않아 쪼개지 않았습니다")] + blocks[i + 1:]
                i += 1
                continue
            new = []
            for g_, card in zip(groups, cards):
                nb = _mk("paragraph", g_, c, src, b["page"])
                nb.update({"kind": "numbercard", "cards": [card]})
                nb.pop("text", None), nb.pop("numbers_masked", None)
                new.append(_mark(nb, "cards"))
        else:
            new = [_mark(_mk("paragraph", g_, c, src, b["page"]), "cards") for g_ in groups]
        blocks = _replace(blocks, set(span), new)
        notes.append({"rule": "cards", "how": how, "line_ids": [n for g_ in groups for n in g_]})
        i += len(new)
    return blocks, notes


# ============ 규칙 3: 표 머리 포함 ============
def _frag_ok(b: dict, c: _C, tb: list) -> bool:
    ls = c.lines(b)
    return b["kind"] in ("title", "paragraph") and 1 <= len(ls) <= HEADER_FRAG_LINES and len(ls) == len(b["line_ids"]) and not any(SENT_END.search(l.raw.strip()) for l in ls) \
        and all(tb[0] - 4 <= l.x0 and l.x1 <= tb[2] + 4 for l in ls) and all((l.x1 - l.x0) <= 0.6 * (tb[2] - tb[0]) or len(l.cells) >= 2 for l in ls)


def _place(cells: list, cols: list) -> list[str]:
    row = [""] * len(cols)
    for cl in cells:
        j = max(range(len(cols)), key=lambda k: min(cl["x1"], cols[k][1]) - max(cl["x0"], cols[k][0]))
        if min(cl["x1"], cols[j][1]) - max(cl["x0"], cols[j][0]) <= 0:
            j = min(range(len(cols)), key=lambda k: abs((cl["x0"] + cl["x1"]) / 2 - (cols[k][0] + cols[k][1]) / 2))
        row[j] = (row[j] + " " + cl["text"]).strip()
    return row


def _spans(grid: list[list[str]], claimed: set | None = None) -> list[dict]:
    """위 머리줄의 글자 칸이 아래 머리줄의 글자 칸들 위에 걸쳐 있으면(양옆 빈 칸 + 아래 칸에 글자) colspan 으로 본다. 위에서부터 차례로, 이미 걸친 칸은 건드리지 않는다."""
    hr, n = len(grid), len(grid[0])
    claimed = set(claimed or ())
    out = []
    for r in range(hr - 1):
        for j in range(n):
            if not grid[r][j] or (r, j) in claimed:
                continue
            lo = hi = j
            while hi + 1 < n and not grid[r][hi + 1] and grid[hr - 1][hi + 1] and (r, hi + 1) not in claimed:
                hi += 1
            while lo - 1 >= 0 and not grid[r][lo - 1] and grid[hr - 1][lo - 1] and (r, lo - 1) not in claimed:
                lo -= 1
            if hi > lo:
                grid[r][lo], grid[r][j] = grid[r][j], (grid[r][lo] if lo != j else grid[r][j])
                if lo != j:
                    grid[r][j] = ""
                out.append({"row": r, "col": lo, "colspan": hi - lo + 1, "rowspan": 1})
                for k in range(r, hr):
                    for jj in range(lo, hi + 1):
                        claimed.add((k, jj))
    return out


def _rebuild_table(b: dict, frag_lines: list, c: _C) -> dict:
    """코드가 만든 표(from_vlm 아님)의 머리를 흡수한 줄들로 다시 만든다: 열 위치는 본문 줄에서, 머리줄은 열에 맞춰 놓고 병합 범위는 위 칸이 아래 칸들에 걸친 것으로 읽는다."""
    fid = {id(l) for l in frag_lines}
    body = [l for l in c.lines(b) if id(l) not in fid]                  # 흡수한 머리 줄은 본문에서 뺀다(b 의 줄 목록에는 이미 들어 있다)
    ncols = b["table"]["columns"]
    full = [l for l in body if len(l.cells) == ncols]
    cols = [(min(l.cells[j]["x0"] for l in full), max(l.cells[j]["x1"] for l in full)) for j in range(ncols)] if full else None
    if not cols:
        return b
    allls = sorted(frag_lines + body, key=lambda l: (l.top, l.x0))
    first_num = next((k for k, l in enumerate(allls) if any(X.NUM_CELL.match(x["text"]) for x in l.cells)), len(allls))
    hr = max(first_num, 1)
    head = allls[:hr]
    grid = [_place(l.cells, cols) for l in head]
    tall: list[int] = []                                               # 가운데 줄에 홀로 떠 있는 칸(다른 머리줄의 같은 열은 모두 비어 있음) = 세로로 병합된 칸이 가운데 정렬된 것 → 첫 줄로 올리고 줄 수를 줄인다
    r = 1
    while r < len(grid) - 1:
        cs = [j for j, t in enumerate(grid[r]) if t]
        if cs and all(not grid[k][j] for k in range(len(grid)) if k != r for j in cs):
            for j in cs:
                grid[0][j] = grid[r][j]
                tall.append(j)
            del grid[r]
        else:
            r += 1
    hr = len(grid)
    spans = _spans(grid, {(k, j) for k in range(hr) for j in tall}) if len(grid) > 1 else []
    spans += [{"row": 0, "col": j, "colspan": 1, "rowspan": hr} for j in tall]
    rows = [[{"type": "text", "text": t} for t in r] for r in grid]
    for l in allls[len(head):]:
        rows.append([{"type": "number"} if X.NUM_CELL.match(x["text"]) else {"type": "text", "text": X.mask(x["text"])[0]} for x in l.cells])
    t = dict(b["table"])
    t.update({"rows": rows, "header_rows": hr, "number_cells": sum(x["type"] == "number" for r in rows for x in r)})
    if spans:
        t["header_spans"] = spans
    lay = TH.header_layout(rows, hr, spans)
    t["header_grid"], t["header_cells"] = lay["grid"], lay["cells"]
    b["table"] = t
    b["needs_review"] = [f for f in b["needs_review"] if not (f["code"] == "table_alignment" and f["reason"].startswith("행마다 칸 수가"))]
    if lay["ambiguous"] and hr > 1 and not any(f["code"] == "header_ambiguous" for f in b["needs_review"]):
        b["needs_review"].append(X._flag("header_ambiguous", "머리가 여러 줄인데 병합된 칸의 범위를 알 수 없습니다(병합 칸으로 읽을 근거가 부족함). 열 이름을 확인하세요"))
    return b


def _one_table_header(blocks: list, c: _C):
    """머리를 흡수할 표 하나를 찾아 처리한다. 반환: (블록, 노트 또는 None). 흡수할 것이 없으면 (블록, None)."""
    for ti in range(len(blocks)):
        b = blocks[ti]
        if b["kind"] != "table" or not b.get("line_ids") or b.get("tidy_done"):
            continue
        tl = c.lines(b)
        if not tl:
            continue
        tb = b["bbox"]
        first = min(tl, key=lambda l: l.top)
        frag_idx: set[int] = set()
        cx, cy = _center(first)
        rect = None
        for s in c.shapes:
            if s["kind"] == "rect" and s["filled"] and _in(s["bbox"], cx, cy) and (s["bbox"][2] - s["bbox"][0]) >= 0.8 * (tb[2] - tb[0]) and (s["bbox"][3] - s["bbox"][1]) <= HEADER_RECT_MAX * first.size:
                if rect is None or (s["bbox"][3] - s["bbox"][1]) < (rect[3] - rect[1]):
                    rect = s["bbox"]
        unsure = False
        if rect is not None:                                           # (가) 같은 채움 배경(머리 배경) 안의 짧은 줄 조각
            for i, fb in enumerate(blocks):
                if i == ti or not _plain(fb):
                    continue
                fl = c.lines(fb)
                if fl and len(fl) == len(fb["line_ids"]) and len(fl) <= HEADER_FRAG_LINES and all(_in(rect, *_center(l)) for l in fl) and all(tb[0] - 4 <= l.x0 and l.x1 <= tb[2] + 4 for l in fl):
                    frag_idx.add(i)
        else:                                                          # (나) 도형이 없으면 바로 위에 붙은 같은 x범위의 짧은 조각
            top = first.top
            while True:
                cand = None
                for i, fb in enumerate(blocks):
                    if i == ti or i in frag_idx or not _frag_ok(fb, c, tb):
                        continue
                    fl = c.lines(fb)
                    gap = top - max(l.bottom for l in fl)
                    if -1 <= gap <= HEADER_GAP * first.size * (1 + _BAND) and max(l.size for l in fl) <= first.size + 0.5:
                        if _unsure(gap, HEADER_GAP * first.size) and gap > HEADER_GAP * first.size:
                            unsure = True
                            continue
                        if cand is None or gap < cand[0]:
                            cand = (gap, i, min(l.top for l in fl))
                if cand is None:
                    break
                frag_idx.add(cand[1])
                top = cand[2]
        if not frag_idx:
            nb = _cp(b)
            nb["tidy_done"] = True
            if unsure:
                _uncertain(nb, "표 바로 위 글자가 표 머리인지 제목인지 경계라 합치지 않았습니다")
            return blocks[:ti] + [nb] + blocks[ti + 1:], False
        frags = [blocks[i] for i in sorted(frag_idx)]
        names = sorted([n for fb in frags for n in fb["line_ids"]] + list(b["line_ids"]), key=lambda n: (c.L[n].top, c.L[n].x0))
        nb = _cp(b)
        nb["line_ids"], nb["bbox"] = names, X._bbox([c.L[n] for n in names])
        nb["tidy_done"] = True
        inh = _inherit(frags)
        if "_g" in inh:
            nb["_g"] = sorted({*nb.get("_g", []), *inh["_g"]})
        if not b.get("from_vlm"):
            nb = _rebuild_table(nb, [l for fb in frags for l in c.lines(fb)], c)
        return _replace(blocks, frag_idx | {ti}, [_mark(nb, "table_header")]), {"rule": "table_header", "line_ids": names}
    return blocks, None


def r_table_header(blocks: list, c: _C) -> tuple[list, list]:
    notes = []
    for _ in range(len(blocks) + 1):
        blocks, hit = _one_table_header(blocks, c)
        if hit is None:
            break
        if hit:
            notes.append(hit)
    for b in blocks:
        b.pop("tidy_done", None)
    return blocks, notes


# ============ 규칙 4: 나란한 제목 쪼개기 ============
def r_headings(blocks: list, c: _C) -> tuple[list, list]:
    notes = []
    i = 0
    while i < len(blocks):
        b = blocks[i]
        ls = c.lines(b)
        if b["kind"] not in ("title", "paragraph") or len(ls) != 1 or len(b["line_ids"]) != 1 or not ls[0].cuts or not isinstance(b["line_ids"][0], int):
            i += 1
            continue
        l = ls[0]
        ps = X.split_line(l, [x["cut"] for x in l.cuts])
        if len(ps) < 2:
            i += 1
            continue
        txt = [p.raw.strip() for p in ps]
        columnar = any(x.get("vert") for x in l.cuts)                    # 위아래 줄에서 같은 자리에 간격이 이어지면 표·목록의 열이다(제목이 아니다)
        title_like = l.size >= TITLE_SIZE * c.med and not columnar and all(t and not SENT_END.search(t) and not X.NUM_CELL.match(t) for t in txt)
        if not title_like:
            i += 1
            continue
        longest = max(len(t) for t in txt)
        if longest > TITLE_MAX_CHARS * (1 + _BAND):
            i += 1
            continue
        if _unsure(longest, TITLE_MAX_CHARS):
            blocks = blocks[:i] + [_uncertain(_cp(b), "한 줄에 떨어진 두 글자 덩어리가 제목인지 문장인지 경계라 쪼개지 않았습니다")] + blocks[i + 1:]
            i += 1
            continue
        for p in ps:
            c.pieces[p.name] = p
            c.L[p.name] = p
        new = [_mark(_mk("title", [p.name], c, b, b["page"], level=b.get("level", 2)) if l.all_bold or b["kind"] == "title" else _mk("paragraph", [p.name], c, b, b["page"]), "headings") for p in ps]
        blocks = _replace(blocks, {i}, new)
        notes.append({"rule": "headings", "line_ids": [p.name for p in ps]})
        i += len(new)
    return blocks, notes


# ============ 규칙 5: 계층 목록 묶기 ============
def _bullet_items(ls: list, c: _C) -> list[dict]:
    items: list[dict] = []
    for l in ls:
        m = X.BULLET.match(l.raw)
        if not m and items and l.x0 >= items[-1]["_x"] - 1:            # 표지 없는 들여쓴 줄: 앞 항목의 이어지는 줄
            t, n = X.mask(l.raw)
            items[-1]["text"] += " " + t
            items[-1]["numbers_masked"] += n
            continue
        t, n = X.mask(l.raw[m.end():] if m else l.raw)
        items.append({"text": t, "marker": (m.group(0).strip() if m else "•")[:4], "level": 1, "children": [], "numbers_masked": n, "_x": l.x0})
    for it in items:
        it.pop("_x")
    return items


def r_hierarchy(blocks: list, c: _C) -> tuple[list, list]:
    notes = []
    i = 0
    while i < len(blocks):
        h = blocks[i]
        hl = c.lines(h)
        if h["kind"] not in ("title", "paragraph") or len(hl) != 1 or len(h["line_ids"]) != 1 or not HEAD_MARK.match(hl[0].raw) or X.FOOT.match(hl[0].raw) or FOOT_MARK.match(hl[0].raw):
            i += 1
            continue
        H = hl[0]
        take: list = []                                                # (블록 순번, 흡수할 줄 이름)
        prev_bottom, j, absorbed_blocks = H.bottom, i + 1, []
        unsure = False
        stop = False
        while j < len(blocks) and not stop:
            nb = blocks[j]
            nl = c.lines(nb)
            if nb["kind"] not in ("list", "paragraph") or not nl or len(nl) != len(nb["line_ids"]):
                break
            got = []
            for n, l in zip(nb["line_ids"], nl):
                gap = l.top - prev_bottom
                ind = l.x0 > H.x0 + HIER_INDENT * c.med
                if not ind or gap > HIER_GAP * l.size * (1 + _BAND) or gap < -1 or HEAD_MARK.match(l.raw) and l.x0 <= H.x0 + HIER_INDENT * c.med:
                    stop = True
                    break
                if _unsure(gap, HIER_GAP * l.size) and gap > HIER_GAP * l.size:
                    unsure = True
                    stop = True
                    break
                got.append(n)
                prev_bottom = l.bottom
            if got:
                absorbed_blocks.append((j, got))
            if not got or len(got) < len(nb["line_ids"]):
                break
            j += 1
        if not absorbed_blocks:
            if unsure:
                blocks = blocks[:i] + [_uncertain(_cp(h), "소제목과 아래 줄의 간격이 경계라 한 목록으로 묶지 않았습니다")] + blocks[i + 1:]
            i += 1
            continue
        ids = [n for _, g_ in absorbed_blocks for n in g_]
        t, cnt = X.mask(H.raw[HEAD_MARK.match(H.raw).end():])
        parent = {"text": t, "marker": HEAD_MARK.match(H.raw).group(0).strip()[:4], "level": 0, "children": _bullet_items([c.L[n] for n in ids], c), "numbers_masked": cnt}
        hh = {**h, "needs_review": [f for f in h["needs_review"] if not f["reason"].startswith("굵은 글씨 한 줄")]}      # 소제목인지 강조 문장인지 애매하다는 표시는 항목이 딸려 있으면 풀린다
        srcs = [hh] + [blocks[k] for k, _ in absorbed_blocks]
        lst = {"kind": "list", "page": h["page"], "line_ids": [h["line_ids"][0]] + ids, "items": [parent], **_inherit(srcs)}
        lst["bbox"] = X._bbox([c.L[n] for n in lst["line_ids"]])
        new = [_mark(lst, "hierarchy")]
        old = {i} | {k for k, _ in absorbed_blocks}
        for k, g_ in absorbed_blocks:                                  # 블록의 일부만 흡수했으면 나머지는 원래 종류로 남긴다
            rest = [n for n in blocks[k]["line_ids"] if n not in g_]
            if rest:
                new.append(_mk(blocks[k]["kind"], rest, c, blocks[k], blocks[k]["page"], level=blocks[k].get("level", 2)))
        blocks = _replace(blocks, old, new)
        notes.append({"rule": "hierarchy", "line_ids": lst["line_ids"]})
        i += 1
    return blocks, notes


# ============ 규칙 6: 쪽 하단 각주 분리 ============
def _is_foot(l, c: _C) -> str:
    """'yes' / 'no' / 'unsure'"""
    if not FOOT_MARK.match(l.raw.strip()):
        return "no"
    ratio = l.size / c.med if c.med else 1.0
    if ratio >= FOOT_RATIO * (1 + _BAND / 3):
        return "no"
    low = l.top >= c.H * FOOT_ZONE or any(s["kind"] == "line" and (s["bbox"][3] - s["bbox"][1]) <= 1.5 and l.top - FOOT_RULE * l.size <= s["bbox"][1] <= l.top + 1 and s["bbox"][0] <= l.x1 and s["bbox"][2] >= l.x0 for s in c.shapes)
    if not low:
        return "no"
    return "unsure" if ratio >= FOOT_RATIO else "yes"


def r_footnote(blocks: list, c: _C) -> tuple[list, list]:
    notes = []
    i = 0
    while i < len(blocks):
        b = blocks[i]
        ls = c.lines(b)
        if b["kind"] not in ("title", "paragraph", "list") or not ls or len(ls) != len(b["line_ids"]):
            i += 1
            continue
        verdict = [_is_foot(l, c) for l in ls]
        if "unsure" in verdict and "yes" not in verdict:
            blocks = blocks[:i] + [_uncertain(_cp(b), "쪽 아래의 작은 글자가 각주인지 경계라 떼어내지 않았습니다")] + blocks[i + 1:]
            i += 1
            continue
        if "yes" not in verdict:
            i += 1
            continue
        segs: list[tuple[str, list]] = []                              # 줄을 순서대로 (각주 / 본래 종류) 구간으로 나눈다. 각주 뒤에 이어진 같은 크기 줄(표지 없음)은 각주의 이어진 줄
        for n, l, v in zip(b["line_ids"], ls, verdict):
            foot = v == "yes" or (segs and segs[-1][0] == "foot" and not FOOT_MARK.match(l.raw.strip()) and abs(l.size - c.L[segs[-1][1][-1]].size) < 0.3 and not HEAD_MARK.match(l.raw))
            if foot and v == "yes" and segs and segs[-1][0] == "foot":
                segs.append(("foot", [n]))
            elif foot:
                (segs[-1][1].append(n) if segs and segs[-1][0] == "foot" else segs.append(("foot", [n])))
            elif segs and segs[-1][0] == "rest":
                segs[-1][1].append(n)
            else:
                segs.append(("rest", [n]))
        new = [_mark(_mk("footnote", g_, c, b, b["page"]), "footnote") if k == "foot" else _mk(b["kind"], g_, c, b, b["page"], level=b.get("level", 2)) for k, g_ in segs]
        blocks = _replace(blocks, {i}, new)
        notes.append({"rule": "footnote", "line_ids": [n for k, g_ in segs if k == "foot" for n in g_]})
        i += len(new)
    return blocks, notes


# ============ 규칙 7: 잔 라벨 흡수 방지 ============
def r_labels(blocks: list, c: _C) -> tuple[list, list]:
    notes = []
    host_idx = [i for i, b in enumerate(blocks) if b["kind"] in ("table", "chart")]
    absorbed: dict[int, list[int]] = {}
    for i, b in enumerate(blocks):
        ls = c.lines(b)
        if b["kind"] != "paragraph" or len(ls) != 1 or len(b["line_ids"]) != 1:
            continue
        l = ls[0]
        t = l.raw.strip()
        if len(t) > LABEL_CHARS or SENT_END.search(t) or len(l.cells) != 1 or (l.all_bold and l.size > c.med * 1.1):
            continue
        for h in host_idx:
            hb = blocks[h]["bbox"]
            near = LABEL_NEAR * c.med
            dx = max(hb[0] - l.x1, l.x0 - hb[2], 0)
            dy = max(hb[1] - l.bottom, l.top - hb[3], 0)
            touching_x = min(l.x1, hb[2]) - max(l.x0, hb[0]) > 0
            touching_y = min(l.bottom, hb[3]) - max(l.top, hb[1]) > 0
            if (dx == 0 and touching_x and dy <= near) or (dy == 0 and touching_y and dx <= near):
                if _unsure(max(dx, dy), near) and max(dx, dy) > near * (1 - _BAND):
                    continue
                if blocks[h]["kind"] == "table" and not touching_x and not touching_y:
                    continue
                absorbed.setdefault(h, []).append(i)
                break
    if not absorbed:
        return blocks, notes
    drop: set[int] = set()
    repl: dict[int, dict] = {}
    for h, idxs in absorbed.items():
        nb = _cp(blocks[h])
        extra = [n for k in idxs for n in blocks[k]["line_ids"]]
        nb["line_ids"] = sorted(set(nb["line_ids"]) | set(extra), key=lambda n: (c.L[n].top, c.L[n].x0))
        eb = X._bbox([c.L[n] for n in extra])
        nb["bbox"] = [round(min(nb["bbox"][0], eb[0]), 1), round(min(nb["bbox"][1], eb[1]), 1), round(max(nb["bbox"][2], eb[2]), 1), round(max(nb["bbox"][3], eb[3]), 1)]      # 차트 박스에는 막대(도형) 영역이 들어 있다: 줄만으로 다시 계산하지 않는다
        inh = _inherit([blocks[k] for k in idxs])
        if "_g" in inh:
            nb["_g"] = sorted({*nb.get("_g", []), *inh["_g"]})
        repl[h] = _mark(nb, "labels")
        drop |= set(idxs)
        notes.append({"rule": "labels", "line_ids": extra})
    return [repl.get(i, b) for i, b in enumerate(blocks) if i not in drop], notes


RULES = (("chart", r_chart), ("cards", r_cards), ("table_header", r_table_header), ("headings", r_headings), ("hierarchy", r_hierarchy), ("footnote", r_footnote), ("labels", r_labels))


def _coverage_ok(before: list, after: list, c: _C) -> bool:
    """모든 글자 줄이 정확히 한 블록에 속한다(조각은 줄의 조각이 모두 있어야 한다)."""
    names = [n for b in after for n in b.get("line_ids") or []]
    if len(names) != len(set(names)):
        return False
    lids_b = {_lid(n) for b in before for n in b.get("line_ids") or []}
    lids_a = {_lid(n) for n in names}
    if lids_b != lids_a:
        return False
    for lid in lids_a:
        mine = [n for n in names if _lid(n) == lid]
        if any(isinstance(n, int) for n in mine):
            if mine != [lid]:
                return False
        else:
            want = {n for n, l in c.pieces.items() if _lid(n) == lid}
            if set(mine) != want:
                return False
    return True


def tidy_blocks(blocks: list[dict], lines: list, shapes: list[dict], page_size: tuple[float, float], pieces: dict | None = None) -> tuple[list[dict], list[dict]]:
    """한 쪽의 블록을 정리한다. lines 는 그 쪽의 줄 목록(조각은 pieces={이름: 줄})이고, 줄이 없는 쪽(스캔)은 그대로 돌려준다.
    반환: (정리된 블록, notes). 입력 블록은 바꾸지 않는다. 새 조각은 pieces 에 등록된다."""
    notes: list[dict] = []
    if not lines or not blocks:
        return blocks, notes
    c = _C(lines, shapes, page_size, pieces if pieces is not None else {})
    for name, fn in RULES:
        snap = dict(c.pieces), dict(c.L)
        try:
            new, hit = fn(blocks, c)
        except Exception as e:  # noqa: BLE001  (정리 실패가 추출을 막으면 안 된다)
            c.pieces.clear(), c.pieces.update(snap[0]), c.L.clear(), c.L.update(snap[1])
            notes.append({"rule": name, "reverted": True, "error": type(e).__name__})
            continue
        if not hit and new == blocks:
            continue
        if hit and not _coverage_ok(blocks, new, c):
            c.pieces.clear(), c.pieces.update(snap[0]), c.L.clear(), c.L.update(snap[1])
            notes.append({"rule": name, "reverted": True})
            continue
        blocks = new
        notes += hit
    return blocks, notes
