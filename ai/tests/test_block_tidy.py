"""블록 정리(block_tidy). 규칙 1~7: 정상 / 해당 없음 / 되돌림. 합성 줄·도형은 좌표를 직접 넣는다(PDF 필요 없음). AI 는 모의 응답만 쓴다."""
from __future__ import annotations

import json
from pathlib import Path

import pytest

from app.pdf_structure import block_tidy as T
from app.pdf_structure import extract as X
from app.pdf_structure import extract_structure
from app.services import openai_client

PAGE = (842.0, 595.0)


def L(lid, top, cells, size=9.0, bold=False):
    """cells: [(x0, 글자)]. 글자 하나 폭 = 글자 크기의 절반."""
    chars, parts = [], []
    for x0, t in cells:
        x = x0
        for ch in t:
            w = size * 0.5 if ch != " " else size * 0.25
            chars.append({"text": ch, "x0": x, "x1": x + w, "top": top, "bottom": top + size, "size": size, "fontname": "Bold" if bold else "Reg"})
            x += w
        parts.append(t)
    return X._Line({"chars": chars, "top": top, "bottom": top + size, "x0": chars[0]["x0"], "x1": chars[-1]["x1"], "text": " ".join(parts)}, 1, lid)


def lines(*ls):
    ls = list(ls)
    X.mark_cuts(ls)
    return ls


def blk(kind, ls, **kw):
    b = {"kind": kind, "page": 1, "bbox": X._bbox(ls), "needs_review": [], "line_ids": [l.lid for l in ls], "from_ai": True, "from_vlm": True}
    if kind in ("paragraph", "title", "footnote"):
        b["text"] = " ".join(l.raw for l in ls)
    if kind == "list":
        b["items"] = X._list_items(ls)
    b.update(kw)
    return b


def rect(i, x0, y0, x1, y1, filled=True):
    return {"id": i, "kind": "rect", "bbox": [x0, y0, x1, y1], "filled": filled, "stroke": False}


def line_shape(i, x0, y0, x1, y1):
    return {"id": i, "kind": "line", "bbox": [x0, y0, x1, y1], "filled": False, "stroke": True}


def body_lines(start=100, n=6, x=40):
    return [L(900 + k, start + k * 12, [(x, f"본문 문장 {k} 입니다")], 9.0) for k in range(n)]


def tidy(blocks, ls, shapes=()):
    return T.tidy_blocks(blocks, ls, list(shapes), PAGE, {})


# ---------------- 규칙 1: 차트 ----------------
def _chart_case(lengths=(60, 90, 40, 75), base=300):
    labels = [L(i, 100 + i * 20, [(255, f"항목{i}")], 8.0) for i in range(4)]
    axis = L(4, 188, [(base + 10, "-5 0 5")], 7.0)
    bars = [rect(i, base, 100 + i * 20, base + w, 108 + i * 20) for i, w in enumerate(lengths)]
    ls = lines(*labels, axis, *body_lines())
    bl = [blk("paragraph", [l]) for l in labels] + [blk("list", [axis])] + [blk("paragraph", [l]) for l in ls[5:]]
    return bl, ls, bars


def test_rule1_bars_with_labels_and_axis_become_one_chart_with_the_bars_in_its_box():
    bl, ls, bars = _chart_case()
    out, notes = tidy(bl, ls, bars)
    ch = [b for b in out if b["kind"] == "chart"]
    assert len(ch) == 1 and sorted(ch[0]["line_ids"]) == [0, 1, 2, 3, 4] and ch[0]["categories"] == ["항목0", "항목1", "항목2", "항목3"]
    assert ch[0]["bbox"][2] >= 300 + 90 and ch[0]["bbox"][0] <= 255                              # 박스 = 글자 줄 + 막대
    assert ch[0]["tidy"] == ["tidy:chart"] and [n["rule"] for n in notes] == ["chart"] and len(out) == 1 + 6   # 라벨 5블록 → 차트 1


def test_rule1_zebra_table_background_is_not_a_chart():
    stripes = [rect(i, 40, 100 + i * 24, 500, 112 + i * 24) for i in range(5)]                  # 길이가 모두 같은 줄무늬
    bl, ls, _ = _chart_case()
    out, notes = tidy(bl, ls, stripes)
    assert not [b for b in out if b["kind"] == "chart"] and out == bl and notes == []


# ---------------- 규칙 2: 카드 ----------------
def test_rule2_lines_in_side_by_side_filled_rects_are_split_by_rect():
    ls = lines(L(0, 100, [(60, "이름 A")], 8.0), L(1, 112, [(60, "보조 A")], 8.0), L(2, 100, [(360, "이름 B")], 8.0), L(3, 112, [(360, "보조 B")], 8.0))
    b = blk("paragraph", ls)
    out, notes = tidy([b], ls, [rect(0, 40, 90, 300, 130), rect(1, 340, 90, 600, 130)])
    assert [x["line_ids"] for x in out] == [[0, 1], [2, 3]] and all(x["tidy"] == ["tidy:cards"] for x in out) and notes[0]["how"] == "rect"
    out2, n2 = tidy([b], ls, [rect(0, 40, 90, 300, 112), rect(1, 40, 112, 300, 130)])         # 위아래로 쌓인 사각형(표 줄무늬)은 쪼개지 않는다
    assert out2 == [b] and n2 == []


def _card_lines(sizes=(8.0, 17.0, 8.0)):
    return lines(*[L(i, 100 + i * 20, [(60, f"카드 {i}"), (400, f"카드 우 {i}")], s) for i, s in enumerate(sizes)])


def test_rule2_vertical_cut_with_different_sizes_splits_cards_into_pieces():
    ls = _card_lines()
    out, notes = tidy([blk("paragraph", ls)], ls)
    assert [x["line_ids"] for x in out] == [["0a", "1a", "2a"], ["0b", "1b", "2b"]] and out[0]["bbox"][2] < out[1]["bbox"][0]
    assert notes[0]["how"] == "cut"
    ls2 = _card_lines((9.0, 9.0, 9.0))                                                           # 글자 크기가 같으면 표의 열이지 카드가 아니다
    b2 = blk("paragraph", ls2)
    assert tidy([b2], ls2)[0] == [b2]


def test_rule2_card_lines_that_are_separate_one_line_blocks_are_regrouped_into_two_cards():
    ls = _card_lines()
    out, notes = tidy([blk("paragraph", [l]) for l in ls], ls)                                    # 줄마다 따로 블록(너무 잘게 쪼갠 경우)
    assert [x["line_ids"] for x in out] == [["0a", "1a", "2a"], ["0b", "1b", "2b"]]
    far = lines(L(0, 100, [(60, "이름"), (400, "이름 우")], 8.0), L(1, 300, [(60, "숫자"), (400, "숫자 우")], 17.0))   # 줄 사이가 멀면(다른 덩어리) 묶지 않는다
    out2, n2 = tidy([blk("paragraph", [l]) for l in far], far)
    assert not any(n["rule"] == "cards" for n in n2) and not any("tidy:cards" in b.get("tidy", []) for b in out2)


# ---------------- 규칙 3: 표 머리 ----------------
def _table_case():
    head = [L(0, 100, [(60, "구분"), (200, "계획"), (300, "실적")], 9.0)]
    body = [L(i, 100 + i * 14, [(60, f"사업부{i}"), (200, "1.0"), (300, "2.0")], 9.0) for i in range(1, 4)]
    frag = L(10, 86, [(200, "영업이익")], 9.0, bold=True)
    ls = lines(frag, *head, *body)
    t, fl = X._table(head + body)
    tb = blk("table", head + body, table=t)
    tb["from_vlm"] = False
    return ls, tb, frag


def test_rule3_header_fragment_in_the_header_band_or_just_above_is_absorbed_and_rows_rebuilt():
    ls, tb, frag = _table_case()
    fb = blk("paragraph", [frag])
    for shapes in ([rect(0, 50, 80, 380, 112)], []):                                            # (가) 머리 배경 사각형 안 / (나) 도형 없이 바로 위에 붙음
        out, notes = tidy([fb, tb], ls, shapes)
        t = out[0]
        assert t["kind"] == "table" and len(out) == 1 and 10 in t["line_ids"] and t["bbox"][1] <= frag.top and t["tidy"] == ["tidy:table_header"]
        assert t["table"]["header_rows"] == 2 and t["table"]["header_grid"][0][1] == "영업이익" or t["table"]["header_grid"][0][2] == "영업이익"


def test_rule3_nothing_to_absorb_or_far_above_leaves_blocks_alone():
    ls, tb, frag = _table_case()
    far = L(11, 20, [(200, "멀리 있는 글")], 9.0)
    ls2 = lines(far, *[l for l in ls if l.lid != 10])
    out, _ = tidy([blk("paragraph", [far]), tb], ls2, [])
    assert len(out) == 2 and "tidy" not in out[1]
    assert tidy([tb], [l for l in ls if l.lid != 10], [])[0] == [tb]


# ---------------- 규칙 4: 나란한 제목 ----------------
def test_rule4_two_short_headings_far_apart_are_split_but_body_size_or_columns_are_not():
    big = L(0, 100, [(40, "I. 첫째 제목"), (425, "II. 둘째 제목")], 12.0, bold=True)
    ls = lines(big, *body_lines())
    out, notes = tidy([blk("paragraph", [big])] + [blk("paragraph", [l]) for l in ls[1:]], ls)
    assert [b["line_ids"] for b in out[:2]] == [["0a"], ["0b"]] and out[0]["kind"] == "title" and out[0]["bbox"][2] < out[1]["bbox"][0]
    small = L(0, 100, [(40, "기준일 통일 여부"), (425, "담당 경영기획")], 9.0)               # 본문 크기: 제목이 아니다
    ls2 = lines(small, *body_lines())
    b2 = [blk("paragraph", [small])] + [blk("paragraph", [l]) for l in ls2[1:]]
    assert tidy(b2, ls2)[0] == b2
    cols = [L(i, 100 + i * 14, [(40, f"행 {i}"), (425, f"담당 {i}")], 12.0, bold=True) for i in range(3)]   # 세로로 이어진 간격 = 표·목록의 열
    ls3 = lines(*cols, *body_lines(300))
    b3 = [blk("paragraph", [l]) for l in ls3]
    assert tidy(b3, ls3)[0] == b3


# ---------------- 규칙 5: 계층 목록 ----------------
def _hier(indent=56):
    h = L(0, 100, [(46, "가. 시기 이동")], 9.5, bold=True)
    a = L(1, 114, [(indent, "– 사업부9 일정 변경")], 8.8)
    b = L(2, 126, [(indent, "– 사업부10 조기 완료")], 8.8)
    nxt = L(3, 140, [(46, "나. 계약 변동")], 9.5, bold=True)
    ls = lines(h, a, b, nxt, *body_lines(400))
    bl = [blk("title", [h], level=2), blk("list", [a, b]), blk("title", [nxt], level=2)] + [blk("paragraph", [l]) for l in ls[4:]]
    return bl, ls


def test_rule5_subheading_with_indented_items_becomes_one_list_and_stops_at_next_subheading():
    bl, ls = _hier()
    out, notes = tidy(bl, ls)
    lst = out[0]
    assert lst["kind"] == "list" and lst["line_ids"] == [0, 1, 2] and lst["items"][0]["marker"] == "가." and len(lst["items"][0]["children"]) == 2
    assert out[1]["kind"] == "title" and out[1]["line_ids"] == [3] and lst["tidy"] == ["tidy:hierarchy"]
    bl2, ls2 = _hier(indent=46)                                                                  # 들여쓰지 않았으면 묶지 않는다
    assert tidy(bl2, ls2)[0] == bl2


# ---------------- 규칙 6: 쪽 하단 각주 ----------------
def test_rule6_small_marker_line_at_page_bottom_leaves_the_list_block():
    items = [L(i, 100 + i * 12, [(46, f"• 항목 {i}")], 9.0) for i in range(3)]
    foot = L(3, 560, [(46, "1) 주석 글자는 작다")], 7.0)
    ls = lines(*items, foot, *body_lines(300))
    bl = [blk("list", items + [foot])] + [blk("paragraph", [l]) for l in ls[4:]]
    out, notes = tidy(bl, ls)
    assert [b["kind"] for b in out[:2]] == ["list", "footnote"] and out[0]["line_ids"] == [0, 1, 2] and out[1]["line_ids"] == [3] and out[1]["marker"] == "1)"
    big = L(3, 560, [(46, "1) 본문과 같은 크기")], 9.0)                                          # 글자가 작지 않으면 각주가 아니다
    ls2 = lines(*items, big, *body_lines(300))
    b2 = [blk("list", items + [big])] + [blk("paragraph", [l]) for l in ls2[4:]]
    assert tidy(b2, ls2)[0] == b2


def test_rule6_size_near_the_threshold_only_flags_tidy_uncertain():
    items = [L(i, 100 + i * 12, [(46, f"• 항목 {i}")], 9.0) for i in range(3)]
    near = L(3, 560, [(46, "1) 경계 크기")], 8.5)                                                # 중앙값 9.0 의 0.94 배: 임계값 0.92 근처
    ls = lines(*items, near, *body_lines(300))
    bl = [blk("list", items + [near])] + [blk("paragraph", [l]) for l in ls[4:]]
    out, _ = tidy(bl, ls)
    assert out[0]["line_ids"] == [0, 1, 2, 3] and any(f["code"] == "tidy_uncertain" for f in out[0]["needs_review"])


# ---------------- 규칙 7: 잔 라벨 ----------------
def test_rule7_short_label_next_to_a_chart_is_absorbed_but_a_title_is_not():
    bl, ls, bars = _chart_case()
    out, _ = tidy(bl, ls, bars)
    ch = next(b for b in out if b["kind"] == "chart")
    lab = L(30, 171, [(310, "단위 억")], 7.5)                                               # 차트 아래 바로 옆의 짧은 라벨
    ls2 = lines(*ls, lab)
    out2, notes2 = tidy(bl + [blk("paragraph", [lab])], ls2, bars)
    c2 = next(b for b in out2 if b["kind"] == "chart")
    assert 30 in c2["line_ids"] and any(n["rule"] == "labels" for n in notes2)
    ttl = L(31, 171, [(310, "큰 제목")], 14.0, bold=True)
    ls3 = lines(*ls, ttl)
    out3, _ = tidy(bl + [blk("title", [ttl])], ls3, bars)
    assert 31 not in next(b for b in out3 if b["kind"] == "chart")["line_ids"]


# ---------------- 되돌림 ----------------
@pytest.mark.parametrize("rule", [n for n, _ in T.RULES])
def test_every_rule_is_reverted_when_it_would_duplicate_or_drop_lines(monkeypatch, rule):
    bl, ls = _hier()
    bad = [dict(bl[0]), dict(bl[0])] + bl[1:]                                                   # 같은 줄이 두 블록에 속한다
    monkeypatch.setattr(T, "RULES", tuple((n, (lambda b, c, _n=n: (bad, [{"rule": _n}])) if n == rule else (lambda b, c: (b, []))) for n, f in T.RULES))      # 시험할 규칙만 남긴다
    out, notes = tidy(bl, ls)
    assert out == bl and {"rule": rule, "reverted": True} in notes
    drop = bl[1:]                                                                              # 줄이 빠진다
    monkeypatch.setattr(T, "RULES", tuple((n, (lambda b, c, _n=n: (drop, [{"rule": _n}])) if n == rule else (lambda b, c: (b, []))) for n, f in T.RULES))
    assert tidy(bl, ls)[0] == bl


def test_scan_pages_without_text_lines_are_skipped_and_input_blocks_are_not_mutated():
    assert T.tidy_blocks([{"kind": "paragraph"}], [], [], PAGE) == ([{"kind": "paragraph"}], [])
    bl, ls = _hier()
    snap = json.dumps(bl, sort_keys=True, default=str)
    tidy(bl, ls)
    assert json.dumps(bl, sort_keys=True, default=str) == snap


# ---------------- 실제 PDF(AI 호출 없음) ----------------
D = Path.home() / "Downloads" / "Claude outputs"
BRIEF = next(iter(D.glob("26년11월_사업부*.pdf")), None)
needs_pdf = pytest.mark.skipif(BRIEF is None, reason="샘플 PDF 없음")


def _seven(r):
    bl = r["blocks"]
    chart = next((b for b in bl if b["kind"] == "chart"), None)
    table = next((b for b in bl if b["kind"] == "table"), None)
    lists = [b for b in bl if b["kind"] == "list"]
    foot = [b for b in bl if b["kind"] == "footnote"]
    return {
        "chart": bool(chart) and len(chart["line_ids"]) >= 9 and chart["bbox"][2] >= 706,                       # 막대 이름 8 + 축 숫자, 막대(x≈706)까지
        "table_header": bool(table) and 12 in table["line_ids"] and 13 in table["line_ids"] and table["bbox"][1] < 190,
        "headings": sum(1 for b in bl if b["kind"] == "title" and b["line_ids"] in (["11a"], ["11b"])) == 2,
        "cards": sum(1 for b in bl if b["line_ids"] in (["4a", "5a", "6a"], ["4b", "5b", "6b"])) == 2,
        "hierarchy": [b["line_ids"] for b in lists] == [[34, 35, 36], [37, 38, 39], [40, 41, 42]],
        "footnote": [b["line_ids"] for b in foot] == [[43]],
        "no_label_blocks": not [b for b in bl if b["kind"] == "paragraph" and len(b["line_ids"]) == 1 and b["line_ids"][0] in range(23, 32)],
    }


@needs_pdf
def test_brief_pdf_rule_path_all_seven_problems_are_resolved():
    r = extract_structure(BRIEF.read_bytes())
    assert all(_seven(r).values()), _seven(r)


class _FakeVlm:
    def __init__(self, answer):
        from types import SimpleNamespace
        self.answer, self.calls = answer, 0
        self.chat = SimpleNamespace(completions=SimpleNamespace(create=self._create))

    def _create(self, **kw):
        from types import SimpleNamespace
        self.calls += 1
        txt = next(p["text"] for p in kw["messages"][1]["content"] if p["type"] == "text")
        rows = json.loads(txt.split("\n")[1])
        return SimpleNamespace(choices=[SimpleNamespace(message=SimpleNamespace(content=json.dumps(self.answer(rows))))], usage=SimpleNamespace(prompt_tokens=10, completion_tokens=5))


def _per_line(rows):                                                                            # 줄마다 블록 하나(너무 잘게 쪼갠 VLM)
    return {"blocks": [{"kind": "paragraph", "lines": [r["id"]], "text": r["text"] or "-"} for r in rows], "splits": [], "ignored_lines": []}


def _glued(rows):                                                                               # 정리 전의 코드 규칙 묶음을 그대로 합쳐 놓은 VLM
    from unittest import mock
    data = BRIEF.read_bytes()
    with mock.patch.object(T, "tidy_blocks", lambda b, *a, **k: (b, [])):
        raw = extract_structure(data)["blocks"]
    gs = []
    for b in raw:
        g = {"kind": b["kind"], "lines": b["line_ids"], "bbox": [0, 0, 1000, 1000]}
        if b["kind"] in ("title", "paragraph", "footnote"):
            g["text"] = b["text"]
        elif b["kind"] == "table":
            g["rows"] = [[c.get("text") if c["type"] == "text" else None for c in r] for r in b["table"]["rows"]]
        else:
            g["items"] = [{"marker": i["marker"], "text": i["text"], "level": 0} for i in b["items"]]
        gs.append(g)
    return {"blocks": gs, "splits": [], "ignored_lines": []}


@needs_pdf
@pytest.mark.parametrize("answer", [_per_line, _glued], ids=["줄마다_블록_하나", "합쳐_놓은_묶음"])
def test_brief_pdf_with_mock_vlm_tidy_fixes_what_it_can(monkeypatch, answer):
    monkeypatch.setenv("AI_REVIEW_MAX_ROUNDS", "0")
    c = _FakeVlm(answer)
    monkeypatch.setattr(openai_client, "make_client", lambda s: c)
    r = extract_structure(BRIEF.read_bytes(), use_ai=True)
    assert r["vlm"]["mode"] == "vlm" and c.calls == 1                                           # AI 호출 수는 그대로
    got = _seven(r)
    assert got["chart"] and got["hierarchy"] and got["footnote"] and got["no_label_blocks"], got   # 두 답 모두에서 풀린다
    if answer is _glued:
        assert all(got.values()), got                                                           # 묶음이 합쳐진 답은 전부 풀린다
    assert not any(k in b for b in r["blocks"] for k in ("_g",))                                # 내부 표시는 새어 나가지 않는다


@needs_pdf
def test_single_column_document_is_identical_before_and_after_tidy(monkeypatch):
    from unittest import mock
    p = next(iter(D.glob("신규1*.pdf")), None)
    if p is None:
        pytest.skip("신규1 없음")
    now = extract_structure(p.read_bytes())["blocks"]
    with mock.patch.object(T, "tidy_blocks", lambda b, *a, **k: (b, [])):
        before = extract_structure(p.read_bytes())["blocks"]
    assert [(b["kind"], b["bbox"], b["line_ids"], b["needs_review"]) for b in now] == [(b["kind"], b["bbox"], b["line_ids"], b["needs_review"]) for b in before]
    assert not any(b.get("tidy") for b in now)
