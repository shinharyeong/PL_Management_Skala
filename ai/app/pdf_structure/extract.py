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
_DATE = r"'?\d{2,4}\.\d{1,2}월|\d{4}년|\d{1,2}월|\d{1,2}차"
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
                ln = _Line(l, pi, lid)
                if ln.chars_n:
                    ls.append(ln)
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
            while j + 1 < len(ls) and abs(ls[j + 1].size - l.size) < 0.5 and abs(ls[j + 1].x0 - l.x0) < 3 and gapf(ls[j], ls[j + 1]) < 0.6 * l.size \
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
