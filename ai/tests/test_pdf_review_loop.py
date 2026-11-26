"""박스 확인 루프 + 줄 쪼개기(자를 위치 후보). AI 는 모의 응답만 쓴다."""
from __future__ import annotations

import io
import json
from types import SimpleNamespace

import fitz
import pytest
from PIL import Image

from app.pdf_structure import extract as X
from app.pdf_structure import extract_structure
from app.pdf_structure import vlm as V
from app.services import openai_client

PAGE = {"page": 1, "width": 842.0, "height": 595.0}


def _two_zone_pdf() -> bytes:
    """한 줄에 서로 다른 두 제목(왼쪽·오른쪽)이 있고, 아래는 한 칸 본문. 같은 자리에 세로로 이어진 간격이 없어 자동으로는 안 쪼개진다."""
    doc = fitz.open()
    pg = doc.new_page(width=842, height=595)
    pg.insert_text((50, 60), "Alpha heading", fontname="helv", fontsize=12)
    pg.insert_text((520, 60), "Beta heading", fontname="helv", fontsize=12)
    for i in range(3):
        pg.insert_text((50, 120 + i * 16), f"Plain body sentence number {i} of the report.", fontname="helv", fontsize=10)
    out = doc.tobytes()
    doc.close()
    return out


def _lines(pdf: bytes):
    return X.read_pdf(pdf)[1][0]


def _png(w=842, h=595) -> bytes:
    b = io.BytesIO()
    Image.new("RGB", (w, h), "white").save(b, format="PNG")
    return b.getvalue()


# ---------------- 후보 생성 ----------------
def test_cut_candidates_need_a_gap_wider_than_1_5_times_the_text_size():
    ls = _lines(_two_zone_pdf())
    head, body = ls[0], ls[1:]
    assert [c["cut"] for c in head.cuts] == [1] and head.cuts[0]["left"].startswith("Alpha") and head.cuts[0]["right"].startswith("Beta")
    assert all(not l.cuts for l in body)                          # 단어 사이 간격(글자 크기의 0.3배 안팎)은 후보가 아니다
    gap = head.cells[1]["x0"] - head.cells[0]["x1"]
    assert gap > X.CUT_GAP_EM * head.size


def test_table_column_gaps_are_candidates_and_vertical_runs_are_marked_but_not_cut():
    from tests.test_pdf_structure import make_pdf
    ls = _lines(make_pdf())
    rows = [l for l in ls if len(l.cells) == 3]
    assert len(rows) >= 3 and all(len(l.cuts) == 2 for l in rows)  # 표의 열 간격도 후보
    assert all(c["vert"] for l in rows for c in l.cuts)          # 세 줄 이상 같은 자리에 이어짐
    assert all(len(l.cells) == 3 for l in rows)                  # 표시만 했을 뿐 줄은 그대로(자동으로 자르지 않는다)
    head = _lines(_two_zone_pdf())[0]
    assert head.cuts[0]["vert"] is False                         # 한 줄짜리 간격은 '이어짐'이 아니다


def test_line_rows_send_cut_numbers_and_text_stubs_but_no_coordinates_or_numbers():
    from app.pdf_structure.ai_extract import _line_rows
    doc = fitz.open()
    pg = doc.new_page(width=842, height=595)
    pg.insert_text((50, 60), "Revenue", fontname="helv", fontsize=12)
    pg.insert_text((520, 60), "1,234", fontname="helv", fontsize=12)
    data = doc.tobytes()
    doc.close()
    rows = _line_rows(_lines(data))
    txt = json.dumps(rows)
    assert rows[0]["cuts"] == [{"n": 1, "l": "Revenu", "r": "{수치}"}]
    assert "1,234" not in txt and '"x"' not in json.dumps(rows[0]["cuts"])


# ---------------- 쪼개기 ----------------
def _fake_out(blocks, splits):
    return {"blocks": blocks, "splits": splits, "ignored_lines": []}


def test_split_gives_pieces_with_own_boxes_and_all_pieces_land_in_blocks():
    ls = _lines(_two_zone_pdf())
    rep = {}
    out = _fake_out([{"kind": "title", "lines": ["L0a"], "text": "Alpha heading", "level": 2}, {"kind": "title", "lines": ["L0b"], "text": "Beta heading", "level": 2},
                     {"kind": "paragraph", "lines": [1, 2, 3], "text": "Plain body sentence number of the report."}], [{"line": "L0", "cuts": [1]}])
    bl = V.build_blocks(out, PAGE, ls, rep)
    assert len(bl) == 3 and bl[0]["line_ids"] == ["0a"] and bl[1]["line_ids"] == ["0b"]
    assert bl[0]["bbox"][2] < bl[1]["bbox"][0]                    # 두 조각의 박스가 좌우로 갈라졌다
    p = rep["pieces"]
    assert bl[0]["bbox"] == X._bbox([p["0a"]]) and bl[1]["bbox"] == X._bbox([p["0b"]])      # 조각 좌표에서 계산
    assert rep["missing"] == [] and rep["overlap"] == [] and rep["split_invalid"] == []
    assert not any(f["code"] == "split_invalid" for b in bl for f in b["needs_review"])


def test_invalid_split_requests_are_dropped_and_flagged():
    ls = _lines(_two_zone_pdf())
    for splits in ([{"line": "L0", "cuts": [9]}], [{"line": "L99", "cuts": [1]}], [{"line": "L0", "cuts": "x"}], "bad"):
        rep = {}
        out = _fake_out([{"kind": "paragraph", "lines": [0], "text": "Alpha heading Beta heading"}, {"kind": "paragraph", "lines": [1, 2, 3], "text": "Plain body sentence number of the report."}], splits)
        bl = V.build_blocks(out, PAGE, ls, rep)
        assert rep["split_invalid"] and bl[0]["line_ids"] == [0] and rep["pieces"] == {}      # 줄은 쪼개지지 않고 그대로
        assert any(f["code"] == "split_invalid" for b in bl for f in b["needs_review"])


def test_piece_name_of_an_unsplit_line_is_read_as_the_whole_line_and_flagged():
    ls = _lines(_two_zone_pdf())
    rep = {}
    out = _fake_out([{"kind": "paragraph", "lines": ["L0a", "L0b"], "text": "Alpha heading Beta heading"}, {"kind": "paragraph", "lines": [1, 2, 3], "text": "Plain body sentence number of the report."}], [])
    bl = V.build_blocks(out, PAGE, ls, rep)
    assert bl[0]["line_ids"] == [0] and rep["split_invalid"] == [0]


def test_piece_left_out_of_every_block_is_filled_as_vlm_missed():
    ls = _lines(_two_zone_pdf())
    rep = {}
    out = _fake_out([{"kind": "title", "lines": ["L0a"], "text": "Alpha heading", "level": 2}, {"kind": "paragraph", "lines": [1, 2, 3], "text": "Plain body sentence number of the report."}], [{"line": "L0", "cuts": [1]}])
    V.build_blocks(out, PAGE, ls, rep)
    assert rep["missing"] == ["0b"]
    added = V.fill_missing_lines({1: rep["missing"]}, {1: ls}, [], {1: rep["pieces"]})
    assert len(added) == 1 and added[0]["line_ids"] == ["0b"] and added[0]["bbox"] == X._bbox([rep["pieces"]["0b"]])
    assert any(f["code"] == "vlm_missed" for f in added[0]["needs_review"])


# ---------------- 확인 루프(모의) ----------------
MERGED = _fake_out([{"kind": "paragraph", "lines": [0], "text": "Alpha heading Beta heading"}, {"kind": "paragraph", "lines": [1, 2, 3], "text": "Plain body sentence number of the report."}], [])
BODY = {"kind": "paragraph", "lines": [1, 2, 3], "text": "Plain body sentence number of the report."}
SPLIT = {"blocks": [{"kind": "title", "lines": ["L0a"], "text": "Alpha heading", "level": 2}, {"kind": "title", "lines": ["L0b"], "text": "Beta heading", "level": 2}, BODY], "splits": [{"line": "L0", "cuts": [1]}]}       # 재질문 답: 영향 범위의 모든 줄을 다시 묶는다


class _Loop:
    def __init__(self, reviews, reasks, ready_error=None):
        self.reviews, self.reasks, self.n_review, self.n_reask, self.ready_error = list(reviews), list(reasks), 0, 0, ready_error

    def ready(self):
        if self.ready_error:
            raise RuntimeError(self.ready_error)

    def review(self, png, text):
        self.n_review += 1
        self.last_review_text = text
        return (self.reviews.pop(0) if self.reviews else {"issues": []}), 10, 5

    def reask(self, png, lines, note):
        self.n_reask += 1
        self.last_reask = (lines, note)
        return (self.reasks.pop(0) if self.reasks else SPLIT), 10, 5


def _run(loop, rounds=2, out=MERGED):
    ls = _lines(_two_zone_pdf())
    rep = {}
    built = V.build_blocks(out, PAGE, ls, rep)
    return V.review_loop(PAGE, ls, out, built, rep, _png(), review=loop.review, reask=loop.reask, max_rounds=rounds, ready=loop.ready)


ISSUE = {"issues": [{"block": 1, "type": "split_needed", "reason": "한 줄에 두 제목"}]}


def test_loop_stops_after_one_review_when_there_is_no_issue_and_changes_nothing():
    lp = _Loop([{"issues": []}], [])
    ls = _lines(_two_zone_pdf())
    rep0 = {}
    before = V.build_blocks(MERGED, PAGE, ls, rep0)
    built, rep, info = _run(lp)
    assert (lp.n_review, lp.n_reask, info["rounds"], info["unresolved"]) == (1, 0, 0, 0)
    assert [(b["kind"], b["bbox"], b["line_ids"], b["needs_review"]) for b in built] == [(b["kind"], b["bbox"], b["line_ids"], b["needs_review"]) for b in before]      # 문제 없으면 결과 동일


def test_loop_fixes_a_merged_line_by_asking_again_only_for_nearby_lines():
    lp = _Loop([ISSUE, {"issues": []}], [SPLIT])
    built, rep, info = _run(lp)
    assert (lp.n_review, lp.n_reask, info["rounds"], info["unresolved"]) == (2, 1, 1, 0)
    assert [b["line_ids"] for b in built[:2]] == [["0a"], ["0b"]] and built[0]["bbox"][2] < built[1]["bbox"][0]
    asked, note = lp.last_reask
    assert 0 in {l.lid for l in asked} and "split_needed" in note
    assert "블록 목록" in lp.last_review_text


def test_loop_stops_at_the_round_limit_and_flags_what_is_left():
    bad = {"blocks": [{"kind": "paragraph", "lines": [0], "text": "Alpha heading Beta heading"}, BODY], "splits": []}       # 재질문 답이 그대로(개선 없음, 나빠지지도 않음)
    lp = _Loop([ISSUE, ISSUE, ISSUE], [bad, bad])
    built, rep, info = _run(lp, rounds=2)
    assert lp.n_reask == 2 and lp.n_review == 3 and info["rounds"] == 2                    # 재질문 2회 + 마지막 점검 1회
    assert info["unresolved"] == 1 and any(f["code"] == "review_unresolved" for f in built[0]["needs_review"])


def test_loop_keeps_the_previous_result_when_the_new_answer_is_worse():
    worse = {"blocks": [{"kind": "title", "lines": ["L0a"], "text": "Alpha heading", "level": 2}, BODY], "splits": [{"line": "L0", "cuts": [1]}]}      # 조각 b 를 빠뜨림 → 놓친 줄 증가
    lp = _Loop([ISSUE], [worse])
    built, rep, info = _run(lp)
    assert info["reverted"] == 1 and lp.n_reask == 1
    assert built[0]["line_ids"] == [0] and rep["missing"] == []                       # 이전 결과 유지
    assert any(f["code"] == "review_unresolved" for f in built[0]["needs_review"])


def test_loop_is_skipped_when_limit_is_zero_or_ai_is_unavailable():
    lp = _Loop([ISSUE], [])
    built, rep, info = _run(lp, rounds=0)
    assert lp.n_review == 0 and info["review_calls"] == 0
    lp2 = _Loop([ISSUE], [], ready_error="오늘 AI 호출 상한에 도달")
    built2, _, info2 = _run(lp2)
    assert lp2.n_review == 0 and info2["stopped"] and not any(f["code"] == "review_unresolved" for b in built2 for f in b["needs_review"])
    assert [b["line_ids"] for b in built2] == [[0], [1, 2, 3]]


def test_review_issues_with_unknown_type_or_block_number_are_dropped():
    assert V._issues({"issues": [{"block": 9, "type": "wrong_kind"}, {"block": 1, "type": "nope"}, {"block": 1, "type": "wrong_kind", "reason": "x"}, "bad"]}, 2) == [{"block": 1, "type": "wrong_kind", "reason": "x"}]
    assert V._issues({"issues": "x"}, 2) == [] and V._issues({}, 2) == []


# ---------------- 실제 호출 경로(가짜 클라이언트) ----------------
class _Client:
    def __init__(self, vlm, review, reask=None):
        self.vlm, self.review, self.reask, self.calls, self.kinds, self.texts = vlm, review, reask, 0, [], []
        self.chat = SimpleNamespace(completions=SimpleNamespace(create=self._create))

    def _create(self, **kw):
        self.calls += 1
        system = kw["messages"][0]["content"]
        text = next(p["text"] for p in kw["messages"][1]["content"] if p["type"] == "text")
        self.texts.append(text)
        if system == V.REVIEW_SYSTEM:
            self.kinds.append("review")
            c = self.review.pop(0) if self.review else {"issues": []}
        elif self.kinds.count("vlm") >= 1 and self.reask is not None:
            self.kinds.append("reask")
            c = self.reask
        else:
            self.kinds.append("vlm")
            c = self.vlm
        return SimpleNamespace(choices=[SimpleNamespace(message=SimpleNamespace(content=json.dumps(c)))], usage=SimpleNamespace(prompt_tokens=100, completion_tokens=40))


def test_end_to_end_merged_headings_are_split_after_one_review_round(monkeypatch):
    monkeypatch.setenv("AI_REVIEW_MAX_ROUNDS", "2")
    body = {"kind": "paragraph", "lines": [1, 2, 3], "text": "Plain body sentence number of the report."}
    c = _Client({"blocks": [{"kind": "paragraph", "lines": [0], "text": "Alpha heading Beta heading"}, body], "splits": [], "ignored_lines": []}, [ISSUE], SPLIT)
    monkeypatch.setattr(openai_client, "make_client", lambda s: c)
    r = extract_structure(_two_zone_pdf(), use_ai=True)
    heads = [b for b in r["blocks"] if b["kind"] == "title"]
    assert len(heads) == 2 and heads[0]["bbox"][2] < heads[1]["bbox"][0]
    assert c.kinds == ["vlm", "review", "reask", "review"] and r["vlm"]["review"]["rounds"] == 1 and r["vlm"]["review"]["unresolved"] == 0
    assert not any(f["code"] in ("vlm_missed", "review_unresolved") for b in r["blocks"] for f in b["needs_review"])
    assert r["vlm"]["prompt_version"] == "pdf-vlm-v6"


def test_end_to_end_loop_off_makes_exactly_one_call_per_page(monkeypatch):
    monkeypatch.setenv("AI_REVIEW_MAX_ROUNDS", "0")
    c = _Client({"blocks": [{"kind": "paragraph", "lines": [0], "text": "Alpha heading Beta heading"}, {"kind": "paragraph", "lines": [1, 2, 3], "text": "Plain body sentence number of the report."}], "splits": []}, [ISSUE])
    monkeypatch.setattr(openai_client, "make_client", lambda s: c)
    r = extract_structure(_two_zone_pdf(), use_ai=True)
    assert c.calls == 1 and "review" not in r["vlm"]


def test_single_column_document_gives_the_same_blocks_with_loop_on_and_off(monkeypatch):
    from tests.test_pdf_structure import make_pdf
    pdf = make_pdf()
    ls = X.read_pdf(pdf)[1][0]
    groups = {"blocks": [{"kind": "paragraph", "lines": [l.lid], "text": l.raw} for l in ls], "splits": [], "ignored_lines": []}
    res = {}
    for rounds in ("0", "2"):
        monkeypatch.setenv("AI_REVIEW_MAX_ROUNDS", rounds)
        monkeypatch.setattr(openai_client, "make_client", lambda s: _Client(groups, [{"issues": []}]))
        r = extract_structure(pdf, use_ai=True)
        res[rounds] = [(b["kind"], b["bbox"], b["line_ids"], [f["code"] for f in b["needs_review"]]) for b in r["blocks"]]
    assert res["0"] == res["2"]


def test_review_prompt_never_carries_numbers(monkeypatch):
    monkeypatch.setenv("AI_REVIEW_MAX_ROUNDS", "1")
    doc = fitz.open()
    pg = doc.new_page(width=842, height=595)
    pg.insert_text((50, 60), "Revenue 1,234", fontname="helv", fontsize=12)
    pg.insert_text((520, 60), "9,876", fontname="helv", fontsize=12)
    data = doc.tobytes()
    doc.close()
    c = _Client({"blocks": [{"kind": "paragraph", "lines": [0], "text": "Revenue"}], "splits": []}, [{"issues": []}])
    monkeypatch.setattr(openai_client, "make_client", lambda s: c)
    extract_structure(data, use_ai=True)
    assert c.calls == 2 and not any("1,234" in t or "9,876" in t for t in c.texts)


def test_config_default_is_two_rounds_and_zero_turns_it_off(monkeypatch):
    from app.config import get_settings
    monkeypatch.delenv("AI_REVIEW_MAX_ROUNDS", raising=False)
    assert get_settings().ai_review_max_rounds == 2
    monkeypatch.setenv("AI_REVIEW_MAX_ROUNDS", "0")
    assert get_settings().ai_review_max_rounds == 0


def test_table_cells_given_as_objects_are_read_as_their_text():
    """모델이 머리 칸을 {"text": ..., "meaning": null} 로 돌려줘도 파이썬 딕셔너리 글자가 칸에 들어가지 않는다."""
    ls = _lines(_two_zone_pdf())
    out = _fake_out([{"kind": "table", "lines": [1, 2, 3], "header_rows": 1,
                      "rows": [[{"text": "구분", "meaning": None}, {"value": "영업이익"}, None], [{"text": "사업부1", "meaning": None}, "{수치}", {"text": None}]]}], [])
    t = V.build_blocks(out, PAGE, ls, {})[0]["table"]
    assert [c.get("text") for c in t["rows"][0]] == ["구분", "영업이익", None]
    assert [c["type"] for c in t["rows"][0]] == ["text", "text", "number"]
    assert t["rows"][1][0]["text"] == "사업부1" and t["rows"][1][1]["type"] == "number" and t["rows"][1][2]["type"] == "number"
    assert "{" not in "".join(c.get("text") or "" for r in t["rows"] for c in r)
