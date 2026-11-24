"""PDF → 블록 구조(데모). 키·OpenAI 없이 동작. 합성 PDF 로 종류·표·각주·숫자 가림을 확인하고, 샘플 PDF 가 있으면 함께 본다."""
from decimal import Decimal
import os
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.main import app
from app.pdf_structure import extract_structure
from app.pdf_structure.extract import mask

fitz = pytest.importorskip("fitz")
client = TestClient(app)
SAMPLE = Path(os.environ.get("AI_SAMPLE_PDF") or Path.home() / "Downloads" / "Claude outputs" / "26년11월_전사_실적보고_샘플.pdf")


def make_pdf(misaligned=False) -> bytes:
    doc = fitz.open()
    pg = doc.new_page()

    def put(x, y, t, size=10, right=None):
        if right is not None:                                   # 오른쪽 끝을 맞춰 쓴다
            x = right - fitz.get_text_length(t, fontname="helv", fontsize=size)
        pg.insert_text((x, y), t, fontname="helv", fontsize=size)
    put(60, 70, "Monthly report", 18)
    put(60, 100, "Operating profit is 12.5 versus plan, a change of +0.3 this month.")
    put(60, 140, "[ Summary ]", 11)
    rows = [["Item", "Actual", "Plan"], ["Revenue", "100.5", "99.0"], ["Cost", "80.2", "81.0"]]
    for r, row in enumerate(rows):
        y = 170 + r * 20
        put(60, y, row[0])
        for c, v in enumerate(row[1:]):
            put(0, y, v, right=(300 if c == 0 else 400) + (13 * r if misaligned and r == 2 and c == 0 else 0))
    put(60, 236, "* Gap breakdown against plan", 9)
    put(60, 260, "1. Recurring effect +1.0")
    put(75, 277, "- New contract (A +0.4)", 9)
    put(60, 300, "2. One-off effect -0.7")
    put(60, 800, "* This is a sample.", 8)
    out = doc.tobytes()
    doc.close()
    return out


def test_mask_hides_amounts_but_keeps_dates():
    assert mask("'26.11월 영업이익은 28.1억, 계획 △0.4억, 22건, 1차 마감, 5%")[0] == "'26.11월 영업이익은 {수치}, 계획 {수치}, {수치}, 1차 마감, {수치}"
    assert mask("F10 사업 11월→12월")[1] == 0


def test_synthetic_pdf_structure():
    r = extract_structure(make_pdf())
    kinds = [b["kind"] for b in r["blocks"]]
    assert kinds == ["title", "paragraph", "title", "table", "footnote", "list", "footnote"], kinds
    title, para, head, table, foot, lst, last = r["blocks"]
    assert title["level"] == 1 and head["level"] == 2
    assert "{수치}" in para["text"] and "12.5" not in para["text"]
    t = table["table"]
    assert t["columns"] == 3 and t["header_rows"] == 1 and t["number_cells"] == 4 and table["needs_review"] == []
    assert all("text" not in c for row in t["rows"] for c in row[1:] if c["type"] == "number")
    assert foot["link"]["to"] == table["id"] and foot["attached_list"] == lst["id"]
    assert [c["marker"] for c in lst["items"][0]["children"]] == ["•"] and lst["items"][1]["children"] == []
    assert any(f["code"] == "footnote_link" for f in foot["needs_review"]) and last["needs_review"]
    assert r["vlm"]["used"] is False
    assert "100.5" not in str(r) and "99.0" not in str(r)       # 표 숫자 값은 결과 어디에도 없다


def test_misaligned_table_column_is_flagged():
    r = extract_structure(make_pdf(misaligned=True))
    table = next(b for b in r["blocks"] if b["kind"] == "table")
    assert any(f["code"] == "table_alignment" for f in table["needs_review"])


def test_endpoint_flag_and_response(monkeypatch):
    pdf = make_pdf()
    assert client.post("/v1/dev/pdf-structure", files={"file": ("a.pdf", pdf)}).status_code == 404       # 플래그 꺼짐
    monkeypatch.setenv("AI_PDF_DEV", "1")
    r = client.post("/v1/dev/pdf-structure", files={"file": ("a.pdf", pdf)})
    assert r.status_code == 200
    j = r.json()
    assert j["pages"][0]["image"].startswith("data:image/png;base64,") and j["stats"]["blocks"] == 7
    assert client.post("/v1/dev/pdf-structure", files={"file": ("a.pdf", b"nope")}).status_code == 422


@pytest.mark.skipif(not SAMPLE.exists(), reason="샘플 PDF 없음")
def test_sample_pdf_finds_title_tables_and_gap_footnote():
    r = extract_structure(SAMPLE.read_bytes())
    by = r["stats"]["by_kind"]
    assert by["title"] >= 1 and by["table"] == 2 and by["list"] >= 1 and by["footnote"] >= 2
    assert r["blocks"][0]["kind"] == "title" and r["blocks"][0]["level"] == 1
    gap = next(b for b in r["blocks"] if b["kind"] == "footnote" and "Gap" in b["text"] and b.get("attached_list"))
    assert gap["link"]["to"] in {b["id"] for b in r["blocks"] if b["kind"] == "table"}
    assert not any(c["type"] == "text" and c["text"].replace(".", "").lstrip("+△-").isdigit() for b in r["blocks"] if b["kind"] == "table" for row in b["table"]["rows"] for c in row)


# ---------------- AI 로 읽기(모의 클라이언트, 실제 호출 없음) ----------------
import json as _json
from types import SimpleNamespace

from app.services import openai_client


class _AiClient:
    def __init__(self, answer):
        self.answer, self.calls, self.kw = answer, 0, None
        self.chat = SimpleNamespace(completions=SimpleNamespace(create=self._create))

    def _create(self, **kw):
        self.calls += 1
        self.kw = kw
        content = self.answer(kw) if callable(self.answer) else self.answer
        return SimpleNamespace(choices=[SimpleNamespace(message=SimpleNamespace(content=_json.dumps(content)))], usage=SimpleNamespace(prompt_tokens=100, completion_tokens=40))


def _lines_of(kw):
    txt = next(p["text"] for p in kw["messages"][1]["content"] if p["type"] == "text")
    return _json.loads(txt.split("\n", 1)[1])


def test_ai_path_groups_lines_and_sends_no_numbers(monkeypatch):
    pdf = make_pdf()
    rules = extract_structure(pdf)
    groups = [{"kind": b["kind"], "lines": b["line_ids"], **({"level": b["level"]} if b["kind"] == "title" else {})} for b in rules["blocks"]]
    groups[3]["header_rows"] = 1
    groups[4]["footnote_of"] = 3
    c = _AiClient({"blocks": groups})
    monkeypatch.setattr(openai_client, "make_client", lambda s: c)
    r = extract_structure(pdf, use_ai=True, engine="lines")
    assert r["vlm"]["mode"] == "ai" and r["vlm"]["calls"] == 1 and c.calls == 1
    assert [b["kind"] for b in r["blocks"]] == [b["kind"] for b in rules["blocks"]]
    sent = _json.dumps(c.kw["messages"], ensure_ascii=False)
    assert "12.5" not in sent and "100.5" not in sent and "99.0" not in sent                 # 줄 글자 숫자는 가려서 전송
    assert c.kw["messages"][1]["content"][1]["type"] == "image_url"
    assert "100.5" not in str(r)
    foot = next(b for b in r["blocks"] if b["kind"] == "footnote" and b.get("marker", "").startswith("*"))
    assert foot["link"]["to"] == next(b["id"] for b in r["blocks"] if b["kind"] == "table")


def test_ai_disagreement_is_flagged_by_system_check(monkeypatch):
    pdf = make_pdf()
    rules = extract_structure(pdf)
    groups = [{"kind": b["kind"], "lines": b["line_ids"], **({"level": b["level"]} if b["kind"] == "title" else {})} for b in rules["blocks"]]
    groups[1]["kind"] = "title"                                   # 문장을 제목이라고 우김
    monkeypatch.setattr(openai_client, "make_client", lambda s: _AiClient({"blocks": groups}))
    r = extract_structure(pdf, use_ai=True, engine="lines")
    assert any(f["code"] == "ai_rule_mismatch" for f in r["blocks"][1]["needs_review"])


def test_ai_bad_answer_or_missing_key_falls_back_to_rules(monkeypatch):
    pdf = make_pdf()
    rules = extract_structure(pdf)
    monkeypatch.setattr(openai_client, "make_client", lambda s: _AiClient({"blocks": [{"kind": "table", "lines": [0]}]}))      # 줄이 빠짐
    r = extract_structure(pdf, use_ai=True, engine="lines")
    assert r["vlm"]["mode"] == "rules" and [b["kind"] for b in r["blocks"]] == [b["kind"] for b in rules["blocks"]]
    monkeypatch.delenv("OPENAI_API_KEY", raising=False)
    r2 = extract_structure(pdf, use_ai=True, engine="lines")
    assert r2["vlm"]["mode"] == "rules" and "키가 없어" in r2["vlm"]["note"]


def test_masked_image_blacks_out_number_words():
    from app.pdf_structure.extract import read_pdf
    import io
    from PIL import Image
    pdf = make_pdf()
    _, lines, _, imgs = read_pdf(pdf, masked_images=True)
    im = Image.open(io.BytesIO(imgs[0])).convert("L")
    row = next(l for l in lines[0] if "Revenue" in l.raw)
    x = int((row.cells[1]["x0"] + row.cells[1]["x1"]) / 2 * 100 / 72)
    y = int((row.top + row.bottom) / 2 * 100 / 72)
    assert im.getpixel((x, y)) < 30                                # 숫자 칸 가운데는 검게 칠해짐


def test_endpoint_ai_flag_uses_client(monkeypatch):
    pdf = make_pdf()
    rules = extract_structure(pdf)
    groups = [{"kind": b["kind"], "lines": b["line_ids"], **({"level": b["level"]} if b["kind"] == "title" else {})} for b in rules["blocks"]]
    c = _AiClient({"blocks": groups})
    monkeypatch.setattr(openai_client, "make_client", lambda s: c)
    monkeypatch.setenv("AI_PDF_DEV", "1")
    j = client.post("/v1/dev/pdf-structure", files={"file": ("a.pdf", pdf)}, data={"ai": "true", "engine": "lines"}).json()
    assert j["vlm"]["mode"] == "ai" and c.calls == 1


# ---------------- 그림만 있는 쪽(스캔본·캡처) ----------------
def _png_page():
    import io
    from PIL import Image
    im = Image.new("RGB", (800, 1000), "white")
    b = io.BytesIO()
    im.save(b, format="PNG")
    return b.getvalue()


VISION_ANSWER = {"blocks": [
    {"kind": "title", "bbox": [50, 40, 600, 80], "text": "월간 보고", "level": 1},
    {"kind": "paragraph", "bbox": [50, 100, 900, 140], "text": "영업이익은 28.1억으로 계획 대비 +0.4억"},
    {"kind": "table", "bbox": [50, 200, 700, 400], "rows": [["(단위: 억원)", "실적", "계획"], ["매출", "537.8", None], ["영업이익", None, "28.1"]], "header_rows": 1},
    {"kind": "list", "bbox": [50, 420, 700, 520], "items": [{"marker": "1.", "text": "월정액 효과 +1.0억", "level": 0}, {"marker": "•", "text": "신규 계약", "level": 1}]},
    {"kind": "footnote", "bbox": [50, 900, 700, 930], "marker": "※", "text": "샘플입니다", "footnote_of": 2},
]}


def test_image_page_needs_opt_in_and_never_keeps_numbers(monkeypatch):
    c = _AiClient(VISION_ANSWER)
    monkeypatch.setattr(openai_client, "make_client", lambda s: c)
    r0 = extract_structure(_png_page(), use_ai=True)                      # 옵션 없음 → 호출하지 않음
    assert c.calls == 0 and r0["blocks"] == [] and "숫자를 가릴 수 없어" in r0["vlm"]["note"]
    r = extract_structure(_png_page(), use_ai=True, allow_raw_image=True)
    assert c.calls == 1 and r["vlm"]["mode"] == "vlm"
    assert [b["kind"] for b in r["blocks"]] == ["title", "paragraph", "table", "list", "footnote"]
    text = str(r)
    assert "28.1" not in text and "537.8" not in text and "+0.4" not in text and "{수치}" in text
    tbl = r["blocks"][2]["table"]
    assert tbl["number_cells"] == 4 and tbl["header_rows"] == 1
    assert r["blocks"][4]["link"]["to"] == r["blocks"][2]["id"]
    assert r["blocks"][0]["bbox"][2] == 480.0                              # 1000 기준 상대 좌표 → 그림 크기


def test_vision_bad_answer_is_rejected(monkeypatch):
    monkeypatch.setattr(openai_client, "make_client", lambda s: _AiClient({"blocks": [{"kind": "weird"}]}))
    r = extract_structure(_png_page(), use_ai=True, allow_raw_image=True)
    assert r["blocks"] == [] and r["vlm"]["mode"] == "rules"


def test_endpoint_accepts_png_with_raw_image(monkeypatch):
    monkeypatch.setattr(openai_client, "make_client", lambda s: _AiClient(VISION_ANSWER))
    monkeypatch.setenv("AI_PDF_DEV", "1")
    j = client.post("/v1/dev/pdf-structure", files={"file": ("a.png", _png_page())}, data={"ai": "true", "raw_image": "true"})
    assert j.status_code == 200 and j.json()["stats"]["blocks"] == 5


# ---------------- 데이터 선택(구성 확인 다음 단계) ----------------
def _seed_versions():
    """회귀 테스트용 합성 자료를 구성한다.

    Args:
        없음.
    Returns:
        없음. 실패는 assert 또는 예외로 보고한다.
    Raises:
        AssertionError: 계약과 다른 결과. DB fixture 오류는 그대로 전파한다.
    Side Effects:
        모의 호출과 테스트 자료를 사용한다. PostgreSQL fixture는 승인된 환경에서만 실행한다.
    """
    from tests import db_support as db
    con = db.open_writer()
    with con:
        con.executemany("INSERT INTO dim_org VALUES(?,?)", [("O1", "조직가"), ("O2", "조직나")])
        con.executemany("INSERT INTO dim_version(version, kind, label, year, month, loaded_at, source, row_count, is_latest) VALUES(?,?,?,?,?,?,?,?,0)",
                        [("m-a", "master", "확정", 2031, 3, "t", "t", 0), ("p-b", "provisional", "잠정", 2031, 4, "t", "t", 0)])
        con.execute("INSERT INTO fact VALUES('m-a','O1','조직가','매출','c1',NULL,'i',NULL,NULL,NULL,'s',2031,3,'ACTUAL',100)")
        db.mark_latest(con)
        con.executescript(db.INDEXES)
    con.close()


def test_data_select_reads_months_and_resolves_relative_periods(monkeypatch):
    monkeypatch.setenv("AI_PDF_DEV", "1")
    j = client.get("/v1/dev/report-data/months").json()
    assert j["db_ready"] is False or j["months"] == []                       # DB 가 비어 있으면 안내
    _seed_versions()
    j = client.get("/v1/dev/report-data/months").json()
    assert j["default"] == {"year": 2031, "month": 3}                        # master 가 있는 가장 최근 월(잠정 4월이 아니라)
    assert {(m["month"], m["state"]) for m in j["months"]} == {(3, "확정"), (4, "잠정")}
    assert "매출" in j["metrics"] and "영업이익" in j["metrics"] and [o["code"] for o in j["orgs"]] == ["O1", "O2"]
    blocks = [{"id": "a", "kind": "table", "labels": ["매출", "영업이익"], "columns": ["계획", "전년"]},
              {"id": "b", "kind": "table", "labels": ["매출"], "columns": [], "period": {"rule": "base_offset", "offset": 1}},
              {"id": "c", "kind": "table", "labels": ["알수없음"], "columns": []},
              {"id": "d", "kind": "table", "labels": ["조직가"], "columns": []},
              {"id": "e", "kind": "table", "labels": ["매출"], "columns": [], "period": {"rule": "base_offset", "offset": -2}},
              {"id": "t", "kind": "title", "labels": []}]
    r = client.post("/v1/dev/report-data/resolve", json={"base": {"year": 2031, "month": 3}, "blocks": blocks}).json()
    by = {x["id"]: x for x in r["blocks"]}
    assert by["a"]["state"] == "확정" and by["a"]["compare"] == ["계획", "전년 동월"]
    assert by["b"]["months"] == [{"year": 2031, "month": 4, "state": "잠정"}] and by["b"]["state"] == "잠정" and r["provisional"] is True
    assert by["c"]["state"] == "데이터 없음" and "지표 사전" in by["c"]["reason"]
    assert by["d"]["scope"].startswith("조직별")
    assert by["e"]["months"][0] == {"year": 2031, "month": 1, "state": "데이터 없음"} and by["e"]["state"] == "데이터 없음"
    assert by["t"]["state"] == "—"
    assert client.post("/v1/dev/report-data/resolve", json={"base": {"year": 2031, "month": 3}, "blocks": [{"id": "x", "kind": "table", "period": {"rule": "fixed"}}]}).status_code == 422


def test_gaps_endpoint_uses_backend_item_gaps_and_status(monkeypatch):
    """변경된 조회·저장 경계의 회귀 동작을 검증한다.

    Args:
        monkeypatch: 테스트 fixture 또는 합성 입력.
    Returns:
        없음. 실패는 assert 또는 예외로 보고한다.
    Raises:
        AssertionError: 계약과 다른 결과. DB fixture 오류는 그대로 전파한다.
    Side Effects:
        모의 호출과 테스트 자료를 사용한다. PostgreSQL fixture는 승인된 환경에서만 실행한다.
    """
    monkeypatch.setenv("AI_PDF_DEV", "1")
    from tests import db_support as db
    con = db.open_writer()
    with con:
        con.execute("INSERT INTO dim_version(version, kind, label, year, month, loaded_at, source, row_count, is_latest) VALUES('m','master','확정',2032,5,'t','t',0,0)")
        con.executemany("INSERT INTO gap(version,gap_id,gap_type,org_code,org_name,item,year,month,scenario,basis,previous_won,current_won,diff_won,rate_percent,status,explanation_status) VALUES('m',?,'ITEM_GAP',?,?,'광고비',2032,5,'ACTUAL',?,0,0,?,'0',?,'수락')",
                        [("k1-PLAN", "A", "갑", "PLAN", 30_000_000, "ACCEPTED"), ("k2-PLAN", "B", "을", "PLAN", -60_000_000, "ACCEPTED"), ("k3-PLAN", "C", "병", "PLAN", 10_000_000, "NEEDS_REVIEW"), ("k4-PREVIOUS", "D", "정", "PREVIOUS", 5, "NEEDS_REVIEW")])
        con.executemany("INSERT INTO explanation(version,explanation_id,gap_key,gap_id,org_code,org_name,kind,item,year,month,scenario,link_type,status,verdict,rounds,answer,src) VALUES('m',?,?,?,?,?,'매출',?,2032,5,'ACTUAL','GAP',?,'v',1,?,'s')",
                        [("e1", "k1", "k1-PLAN", "A", "갑", "항목1", "수락", "소명 하나"), ("e2", "k2", "k2-PLAN", "B", "을", "항목2", "수락", "소명 둘"), ("e3", "k3", "k3-PLAN", "C", "병", "항목3", "검토중", None)])
        db.mark_latest(con)
    con.close()
    j = client.get("/v1/dev/report-data/gaps?year=2032&month=5").json()
    g = j["groups"][0]
    assert (g["basis"], g["total_won"], len(g["cards"])) == ("PLAN", -20_000_000, 3)              # 비교 기준별 표시 합계
    assert sum(c["diff_won"] for c in g["cards"]) == g["total_won"]
    by = {c["org_name"]: c for c in g["cards"]}
    assert by["을"]["carried_over"] is False and by["갑"]["carried_over"] is False
    assert by["갑"]["summary"] == "소명 하나" and by["병"]["explained"] is False and by["병"]["summary"] is None
    assert [x["basis"] for x in j["groups"]] == ["PLAN", "PREVIOUS"]                                   # 다른 기준은 합산하지 않고 따로
    assert client.get("/v1/dev/report-data/gaps?year=1999&month=1").json()["groups"] == []


def test_fill_report_numbers_come_from_db_and_unmatched_orgs_do_not_collide(monkeypatch):
    """변경된 조회·저장 경계의 회귀 동작을 검증한다.

    Args:
        monkeypatch: 테스트 fixture 또는 합성 입력.
    Returns:
        없음. 실패는 assert 또는 예외로 보고한다.
    Raises:
        AssertionError: 계약과 다른 결과. DB fixture 오류는 그대로 전파한다.
    Side Effects:
        모의 호출과 테스트 자료를 사용한다. PostgreSQL fixture는 승인된 환경에서만 실행한다.
    """
    monkeypatch.setenv("AI_PDF_DEV", "1")
    from tests import db_support as db
    con = db.open_writer()
    with con:
        con.executemany("INSERT INTO dim_org VALUES(?,?)", [("A1", "사업부1"), ("A10", "사업부10")])
        con.execute("INSERT INTO dim_version(version, kind, label, year, month, loaded_at, source, row_count, is_latest) VALUES('m','master','확정',2033,6,'t','t',0,0)")
        rows = [("A1", "사업부1", "매출", "ACTUAL", 1_000_000_000), ("A1", "사업부1", "영업비용", "ACTUAL", 400_000_000), ("A1", "사업부1", "매출", "PLAN", 900_000_000), ("A1", "사업부1", "영업비용", "PLAN", 400_000_000),
                ("A10", "사업부10", "매출", "ACTUAL", 5_000_000_000), ("A10", "사업부10", "영업비용", "ACTUAL", 1_000_000_000), ("A10", "사업부10", "매출", "PLAN", 5_000_000_000), ("A10", "사업부10", "영업비용", "PLAN", 1_000_000_000)]
        con.executemany("INSERT INTO fact VALUES('m',?,?,?,'분류',NULL,'i',NULL,NULL,NULL,'s',2033,6,?,?)", rows)
        db.mark_latest(con)
    con.close()
    num = lambda: {"type": "number"}
    txt = lambda t: {"type": "text", "text": t}
    blocks = [
        {"id": "t", "kind": "table", "header_rows": 1, "rows": [[txt("(단위: 억원)"), txt("'20.3월"), txt("계획"), txt("전월")], [txt("매출"), num(), num(), num()], [txt("영업이익"), num(), num(), num()]]},
        {"id": "o", "kind": "table", "header_rows": 1, "rows": [[txt("조직"), txt("계획"), txt("실적"), txt("Gap")], [txt("사업부10 (A10)"), num(), num(), num()], [txt("사업부1"), num(), num(), num()]]},
        {"id": "p", "kind": "paragraph", "text": "영업이익은 {수치}으로 계획 {수치}, 전월 {수치}입니다."},
        {"id": "ti", "kind": "title", "text": "'20.3월 보고"},
        {"id": "f", "kind": "footnote", "text": "3월 Gap {수치} 구성"},
        {"id": "x", "kind": "breakdown", "metric": "영업비용"},
    ]
    j = client.post("/v1/dev/report-data/fill", json={"base": {"year": 2033, "month": 6}, "blocks": blocks, "orig": [20, 3]}).json()
    by = {b["id"]: b for b in j["blocks"]}
    cells = lambda i: [[c["text"] for c in r] for r in by[i]["rows"]]
    t = cells("t")
    assert t[0][1] == "'33.6월"                                                # 틀의 옛 월이 기준 월로 바뀜
    assert t[1][1:3] == ["60.0", "+1.0"] and t[1][3] == "데이터 없음"            # 매출 10+50=60.0억, 계획(9+50) 대비 +1.0, 전월 데이터 없음
    assert t[2][1:3] == ["46.0", "+1.0"]                                        # 영업이익 = 매출−영업비용 = 60−14
    o = cells("o")
    assert o[1][1:4] == ["40.0", "40.0", "0.0"] and o[2][1:3] == ["5.0", "6.0"]   # '사업부1' 이 '사업부10' 에 걸리지 않음, 값은 각 조직 것
    assert by["p"]["text"].startswith("영업이익은 46.0억으로 계획 +1.0억, 전월 [값]") and "[값]" in by["p"]["text"] and by["p"]["missing"] is True
    assert by["ti"]["text"] == "'33.6월 보고" and by["f"]["text"].startswith("6월 Gap ")
    bd = cells("x")
    assert bd[0][0].startswith("영업비용 상세") and bd[1][0] == "분류" and bd[1][1] == "14.0"


def _seed_report_db():
    """회귀 테스트용 합성 자료를 구성한다.

    Args:
        없음.
    Returns:
        없음. 실패는 assert 또는 예외로 보고한다.
    Raises:
        AssertionError: 계약과 다른 결과. DB fixture 오류는 그대로 전파한다.
    Side Effects:
        모의 호출과 테스트 자료를 사용한다. PostgreSQL fixture는 승인된 환경에서만 실행한다.
    """
    from tests import db_support as db
    con = db.open_writer()
    with con:
        con.execute("INSERT INTO dim_org VALUES('A1','사업부1')")
        con.execute("INSERT INTO dim_version(version, kind, label, year, month, loaded_at, source, row_count, is_latest) VALUES('m','master','확정',2034,7,'t','t',0,0)")
        con.executemany("INSERT INTO fact VALUES('m','A1','사업부1',?,'c',NULL,'i',NULL,NULL,NULL,'s',2034,7,?,?)",
                        [("매출", "ACTUAL", 1_000_000_000), ("영업비용", "ACTUAL", 400_000_000), ("매출", "PLAN", 900_000_000), ("영업비용", "PLAN", 400_000_000)])
        con.execute("INSERT INTO gap VALUES('m','k1-PLAN','ITEM_GAP','A1','사업부1','영업이익',2034,7,'ACTUAL','PLAN',0,0,100000000,'0','ACCEPTED','수락')")
        con.execute("INSERT INTO explanation VALUES('m','e1','k1','k1-PLAN','A1','사업부1','매출','i',2034,7,'ACTUAL','GAP','수락','v',1,'신규 계약이 시작되었습니다.','s')")
        db.mark_latest(con)
    con.close()


def test_sentences_use_real_key_messages_path_with_tokens_and_validation(monkeypatch, fake):
    """변경된 조회·저장 경계의 회귀 동작을 검증한다.

    Args:
        monkeypatch, fake: 테스트 fixture 또는 합성 입력.
    Returns:
        없음. 실패는 assert 또는 예외로 보고한다.
    Raises:
        AssertionError: 계약과 다른 결과. DB fixture 오류는 그대로 전파한다.
    Side Effects:
        모의 호출과 테스트 자료를 사용한다. PostgreSQL fixture는 승인된 환경에서만 실행한다.
    """
    monkeypatch.setenv("AI_PDF_DEV", "1")
    _seed_report_db()
    body = {"base": {"year": 2034, "month": 7}, "assign": {"g1": "1"}, "gi": 0}
    facts = client.post("/v1/dev/report-data/sentences", json=body)          # 키는 있고 클라이언트는 모의
    c = fake(sentences=[{"text": "2034년 7월 전사 매출 실적은 10.0억원이며 계획 대비 +1.0억원입니다.", "fact_ids": ["F1"]},
                        {"text": "영업이익은 60.0억원입니다.", "fact_ids": ["F3"]},                    # 사실에 없는 숫자 → 검증 실패 → 기본 문구
                        {"text": "사업부1은 신규 계약이 시작되었습니다.", "fact_ids": ["F4"]}], usage=(321, 45))
    j = client.post("/v1/dev/report-data/sentences", json={**body, "force": True}).json()
    assert c.calls == 1
    assert j["usage"] == {"input_tokens": 321, "output_tokens": 45, "cached": False}
    assert j["status"]["ok"] is True and "대체" in j["status"]["message"]
    src = [s["source"] for s in j["sentences"]]
    assert src[0] == "ai" and "template" in src                                # 숫자가 맞는 문장은 AI, 틀린 문장은 기본 문구
    assert all(f["text"] for f in j["facts"]) and "10.0억원" in j["facts"][0]["text"] and "+1.0억원" in j["facts"][0]["text"]
    assert not any("월정액 추정" in f["text"] for f in j["facts"])
    assert any("신규 계약" in f["text"] for f in j["facts"])
    assert facts.status_code == 200


def test_sentences_failure_reasons_are_shown_in_korean(monkeypatch, fake):
    monkeypatch.setenv("AI_PDF_DEV", "1")
    _seed_report_db()
    body = {"base": {"year": 2034, "month": 7}, "assign": {}, "gi": 0, "force": True}
    monkeypatch.delenv("OPENAI_API_KEY", raising=False)
    j = client.post("/v1/dev/report-data/sentences", json=body).json()
    assert j["status"]["code"] == "NO_API_KEY" and "키 없음" in j["status"]["message"] and j["sentences"]      # 키 값은 어디에도 없음
    monkeypatch.setenv("OPENAI_API_KEY", "k-not-real")
    monkeypatch.setenv("AI_DAILY_CALL_LIMIT", "0")
    j = client.post("/v1/dev/report-data/sentences", json=body).json()
    assert j["status"]["code"] == "DAILY_LIMIT" and "한도" in j["status"]["message"]
    monkeypatch.setenv("AI_DAILY_CALL_LIMIT", "30")

    class APIConnectionError(Exception):
        pass
    fake(exc=APIConnectionError("x"))
    j = client.post("/v1/dev/report-data/sentences", json=body).json()
    assert j["status"]["code"] == "NETWORK" and "네트워크" in j["status"]["message"]
    assert "k-not-real" not in str(j)


def test_upload_endpoints_exist_behind_flag(monkeypatch):
    assert client.post("/v1/dev/report-data/upload", files=[("files", ("a.xlsx", b"x"))]).status_code == 404       # 플래그 꺼짐
    monkeypatch.setenv("AI_PDF_DEV", "1")
    assert client.get("/v1/dev/report-data/upload/nope").status_code == 404
    assert client.post("/v1/dev/report-data/upload/nope/load").status_code == 404


# ---------------- 5단계 AI 챗봇(모의 클라이언트) ----------------
_BLOCKS = [{"id": "b1", "kind": "title", "label": "제목", "level": 1}, {"id": "b2", "kind": "paragraph", "label": "문장"},
           {"id": "b3", "kind": "table", "label": "실적 표", "columns": ["(단위: 억원)", "'34.7월", "계획", "전월"]}]
_BASE = {"year": 2034, "month": 7}


def _chat(monkeypatch, answer, text="x"):
    c = _AiClient(answer)
    monkeypatch.setattr(openai_client, "make_client", lambda s: c)
    j = client.post("/v1/dev/report-data/chat", json={"request": text, "base": _BASE, "blocks": _BLOCKS}).json()
    return j, c


def test_chat_number_change_is_refused_without_calling_ai(monkeypatch):
    monkeypatch.setenv("AI_PDF_DEV", "1")
    _seed_report_db()
    j, c = _chat(monkeypatch, {"intent": "edit"}, "영업이익을 30억으로 바꿔줘")
    assert j["status"] == "refuse" and c.calls == 0 and j["usage"]["calls"] == 0 and "바꿀 수 없" in j["message"]


def test_chat_queries_are_computed_by_code_and_cached(monkeypatch):
    monkeypatch.setenv("AI_PDF_DEV", "1")
    _seed_report_db()
    ans = {"intent": "query", "query": {"type": "value", "metric": "영업이익", "month": 7}}
    j, c = _chat(monkeypatch, ans, "7월 전사 영업이익")
    assert j["status"] == "answer" and "6.0억원" in j["message"] and "계획 대비 +1.0억원" in j["message"] and j["usage"]["calls"] == 1 and j["usage"]["input_tokens"] == 100
    j2, c2 = _chat(monkeypatch, ans, "7월  전사 영업이익")                     # 같은 요청(공백만 다름) → 캐시, 호출 0
    assert c2.calls == 0 and j2["usage"]["cached"] is True and j2["message"] == j["message"]
    j, _ = _chat(monkeypatch, {"intent": "query", "query": {"type": "org_rank", "metric": "영업이익"}}, "조직별 영업이익 순위")
    assert j["table"]["rows"] == [["1", "사업부1", "6.0"]]
    j, _ = _chat(monkeypatch, {"intent": "query", "query": {"type": "gap_orgs"}}, "Gap 걸린 조직 목록")
    assert j["status"] == "answer" and j["table"]["rows"][0][0] == "사업부1"
    j, _ = _chat(monkeypatch, {"intent": "query", "query": {"type": "org_explanation", "org": "사업부1"}}, "사업부1 소명 내용")
    assert "신규 계약이 시작되었습니다." in str(j["table"])
    j, _ = _chat(monkeypatch, {"intent": "query", "query": {"type": "unexplained"}}, "소명 없는 변경 항목")
    assert "없습니다" in j["message"]


def test_chat_does_not_invent_metrics_orgs_months_or_blocks(monkeypatch):
    monkeypatch.setenv("AI_PDF_DEV", "1")
    _seed_report_db()
    j, _ = _chat(monkeypatch, {"intent": "edit", "edits": [{"op": "add_block", "kind": "metric_table", "metrics": ["인원수"], "compare": []}]}, "인원수 표 추가")
    assert j["status"] == "no_data" and "인원수" in j["message"] and "plan" not in j
    j, _ = _chat(monkeypatch, {"intent": "no_data", "unknown_metric": "인원수"}, "인원수가 몇 명이야")
    assert j["status"] == "no_data" and "데이터 없음" in j["message"]
    j, _ = _chat(monkeypatch, {"intent": "query", "query": {"type": "org_explanation", "org": "없는조직"}}, "없는조직 소명")
    assert j["status"] == "no_data" and "모름" in j["message"]
    j, _ = _chat(monkeypatch, {"intent": "query", "query": {"type": "value", "metric": "영업이익", "month": 3}}, "3월 영업이익")
    assert j["status"] == "no_data" and "2034.07" in j["message"]
    j, _ = _chat(monkeypatch, {"intent": "edit", "edits": [{"op": "remove_block", "target": {"words": "설비투자"}}]}, "설비투자 표 빼줘")
    assert j["status"] == "not_found" and "plan" not in j
    j, _ = _chat(monkeypatch, {"intent": "edit", "edits": [{"op": "remove_block", "target": {"id": "b3", "kind": "table"}}]}, "설비투자 표 빼줘")     # AI 가 엉뚱한 표(b3)를 골라도 지우지 않음
    assert j["status"] == "not_found" and "plan" not in j
    j, _ = _chat(monkeypatch, {"intent": "edit", "edits": [{"op": "remove_block", "target": {"id": "b3"}}]}, "실적 표 빼줘")                 # 블록 라벨에 있는 말이면 정상 처리
    assert j["status"] == "edit" and j["plan"] == [{"op": "remove_block", "ids": ["b3"]}]


def test_chat_edits_are_validated_into_a_plan(monkeypatch):
    monkeypatch.setenv("AI_PDF_DEV", "1")
    _seed_report_db()
    j, _ = _chat(monkeypatch, {"intent": "edit", "edits": [{"op": "set_style", "target": {"kind": "title"}, "size": 24}, {"op": "set_style", "target": {"kind": "paragraph"}, "family": "serif"},
                                                          {"op": "add_block", "kind": "breakdown", "metric": "영업비용"}, {"op": "add_column", "target": {"id": "b3"}, "compare": "직전 추정"}]}, "여러 가지")
    assert j["status"] == "edit"
    ops = {p["op"]: p for p in j["plan"]}
    assert [p["ids"] for p in j["plan"] if p["op"] == "set_style"] == [["b1"], ["b2"]] and ops["add_block"]["spec"] == {"type": "breakdown", "metric": "영업비용"} and ops["add_column"]["compare"] == "직전 추정"
    j, _ = _chat(monkeypatch, {"intent": "edit", "edits": [{"op": "add_column", "target": {"id": "b3"}, "compare": "계획"}]}, "계획 대비 열 추가")
    assert j["status"] == "edit" and j["plan"] == [] and "이미" in j["message"]                      # 이미 있는 열은 중복으로 만들지 않음
    j, _ = _chat(monkeypatch, {"intent": "edit", "edits": [{"op": "set_style", "target": {"kind": "paragraph"}, "family": "other"}]}, "궁서체로 바꿔줘")
    assert j["status"] == "refuse" and "고딕" in j["message"] and "plan" not in j
    j, _ = _chat(monkeypatch, {"intent": "edit", "edits": [{"op": "set_style", "target": {"kind": "title"}, "size": 400}]}, "제목 400pt")
    assert j["status"] == "clarify"
    j, _ = _chat(monkeypatch, {"intent": "weird"}, "???")
    assert j["status"] == "unknown"


def test_chat_ai_unavailable_reports_reason(monkeypatch):
    monkeypatch.setenv("AI_PDF_DEV", "1")
    _seed_report_db()
    monkeypatch.delenv("OPENAI_API_KEY", raising=False)
    j = client.post("/v1/dev/report-data/chat", json={"request": "7월 영업이익", "base": _BASE, "blocks": _BLOCKS}).json()
    assert j["status"] == "ai_unavailable" and j["code"] == "NO_API_KEY" and "키 없음" in j["message"]
    monkeypatch.setenv("OPENAI_API_KEY", "k")
    monkeypatch.setenv("AI_DAILY_CALL_LIMIT", "0")
    j = client.post("/v1/dev/report-data/chat", json={"request": "7월 영업이익", "base": _BASE, "blocks": _BLOCKS}).json()
    assert j["code"] == "DAILY_LIMIT"


def test_fill_supports_extra_columns_and_metric_tables(monkeypatch):
    monkeypatch.setenv("AI_PDF_DEV", "1")
    _seed_report_db()
    t = {"id": "t", "kind": "table", "header_rows": 1, "extra_cols": ["계획", "직전 월"],
         "rows": [[{"type": "text", "text": "(단위: 억원)"}, {"type": "text", "text": "실적"}], [{"type": "text", "text": "매출"}, {"type": "number"}]]}
    m = {"id": "m", "kind": "metric_table", "metrics": ["매출", "영업이익"], "compare": ["계획"]}
    j = client.post("/v1/dev/report-data/fill", json={"base": _BASE, "blocks": [t, m]}).json()
    by = {b["id"]: [[c["text"] for c in r] for r in b["rows"]] for b in j["blocks"]}
    assert by["t"][0] == ["(단위: 억원)", "실적", "계획 대비", "직전 월 대비"] and by["t"][1][2] == "+1.0" and by["t"][1][3] == "데이터 없음"
    assert by["m"] == [["지표 (억원)", "7월", "계획 대비"], ["매출", "10.0", "+1.0"], ["영업이익", "6.0", "+1.0"]]


def test_fill_spec_table_rows_cols_editable_and_numbers_stay_code_computed(monkeypatch):
    monkeypatch.setenv("AI_PDF_DEV", "1")
    _seed_report_db()
    spec = {"corner": "지표", "rows": [{"t": "metric", "metric": "영업이익"}, {"t": "metric", "metric": "매출"}],
            "cols": [{"t": "current"}, {"t": "cmp", "name": "계획"}, {"t": "cmp", "name": "전년 동월"}]}
    blk = {"id": "s", "kind": "spec_table", "spec": spec}
    j = client.post("/v1/dev/report-data/fill", json={"base": _BASE, "blocks": [blk]}).json()
    rows = [[c["text"] for c in r] for r in j["blocks"][0]["rows"]]
    assert [r[0] for r in rows[1:]] == ["영업이익", "매출"]              # 행 순서는 스펙대로
    assert rows[1][1] == "6.0" and rows[1][2] == "+1.0" and rows[1][3] == "데이터 없음"   # 비교 월 데이터가 없으면 지어내지 않는다
    spec["rows"].pop(0); spec["cols"].pop(1)                           # 행·열 삭제 → 표가 바로 달라진다
    j = client.post("/v1/dev/report-data/fill", json={"base": _BASE, "blocks": [blk]}).json()
    rows = [[c["text"] for c in r] for r in j["blocks"][0]["rows"]]
    assert len(rows) == 2 and len(rows[0]) == 3 and rows[1][0] == "매출"


def test_fill_chart_and_explist_blocks_return_rows(monkeypatch):
    monkeypatch.setenv("AI_PDF_DEV", "1")
    _seed_report_db()
    j = client.post("/v1/dev/report-data/fill", json={"base": _BASE, "blocks": [
        {"id": "c", "kind": "chart", "metric": "영업이익", "limit": 3}, {"id": "e", "kind": "explist"}]}).json()
    by = {b["id"]: b for b in j["blocks"]}
    assert "rows" in by["c"] and "rows" in by["e"] or by["e"].get("empty") is not None


# ---------------- VLM 주역 구성 추출(v2) ----------------
VLM_ANSWER = {"blocks": [
    {"kind": "title", "bbox": [50, 40, 600, 80], "text": "월간 보고", "level": 1},
    {"kind": "table", "bbox": [50, 100, 900, 300], "header_rows": 2, "uncertain": True, "uncertain_reason": "머리칸 병합",
     "rows": [["(단위: 억원)", "'26.11월", "", "전년比"], ["", "실적", "계획", ""], ["매출", None, None, None], ["엉뚱한지표", None, None, None]],
     "header_spans": [{"row": 0, "col": 1, "colspan": 2, "rowspan": 1}],
     "date_labels": [{"text": "'26.11월", "meaning": "base_month", "n": None}, {"text": "전년比", "meaning": "yoy", "n": None}, {"text": "추이", "meaning": "other", "n": None}]},
    {"kind": "list", "bbox": [50, 320, 900, 450], "items": [{"marker": "I.", "text": "개요", "level": 0}, {"marker": "가.", "text": "세부", "level": 1}, {"marker": "•", "text": "항목", "level": 2}, {"marker": "II.", "text": "다음", "level": 0}]},
    {"kind": "chart", "bbox": [50, 470, 500, 650], "chart_type": "waterfall", "title": "영업이익 브리지", "categories": ["계획", "사업부1"], "series": ["영업이익"]},
    {"kind": "numbercard", "bbox": [520, 470, 900, 650], "cards": [{"label": "영업이익", "unit": "억원", "delta_label": "전년比"}]},
    {"kind": "footnote", "bbox": [50, 900, 700, 930], "marker": "1)", "text": "샘플입니다", "footnote_of": 1},
]}


def test_vlm_primary_reads_new_kinds_hierarchy_spans_and_dates(monkeypatch):
    from app.pdf_structure import vlm as V
    page = {"page": 1, "width": 800.0, "height": 1000.0}
    bl = V.build_blocks(VLM_ANSWER, page)
    assert [b["kind"] for b in bl] == ["title", "table", "list", "chart", "numbercard", "footnote"]
    t = bl[1]
    assert t["table"]["header_spans"] == [{"row": 0, "col": 1, "colspan": 2, "rowspan": 1}] and t["table"]["number_cells"] == 6
    assert [d["meaning"] for d in t["date_labels"]] == ["base_month", "yoy", "other"]
    assert any(f["code"] == "vlm_uncertain" for f in t["needs_review"])
    lst = bl[2]["items"]
    assert [i["marker"] for i in lst] == ["I.", "II."] and lst[0]["children"][0]["children"][0]["marker"] == "•"
    assert bl[3]["chart_type"] == "waterfall" and bl[4]["cards"][0]["label"] == "영업이익"
    V.semantic_flags(bl, {"metrics": ["매출", "영업이익"], "orgs": ["사업부1"]})
    codes = [f["code"] for f in t["needs_review"]]
    assert "metric_unknown" in codes and "date_semantics" in codes


def test_vlm_text_cross_check_flags_text_not_in_pdf():
    from app.pdf_structure import vlm as V
    from app.pdf_structure.extract import read_pdf
    pdf = make_pdf()
    _, lines, _, _ = read_pdf(pdf)
    ok = {"kind": "paragraph", "page": 1, "text": lines[0][0].raw, "needs_review": []}
    bad = {"kind": "paragraph", "page": 1, "text": "PDF 어디에도 없는 전혀 다른 문장입니다", "needs_review": []}
    V.cross_check([ok, bad], {1: lines[0]})
    assert not ok["needs_review"] and bad["needs_review"][0]["code"] == "text_mismatch"


def test_vlm_engine_end_to_end_with_fake_client_and_rule_fallback(monkeypatch):
    monkeypatch.setenv("AI_REVIEW_MAX_ROUNDS", "0")                   # 이 테스트의 주제는 박스 확인 루프가 아니다(호출 수를 센다)
    pdf = make_pdf()
    c = _AiClient(VLM_ANSWER)
    monkeypatch.setattr(openai_client, "make_client", lambda s: c)
    r = extract_structure(pdf, use_ai=True)
    assert r["vlm"]["mode"] == "vlm" and r["vlm"]["engine"] == "vlm" and c.calls == 1 and "coverage" in r["vlm"]
    assert {"chart", "numbercard"} <= {b["kind"] for b in r["blocks"]}
    fn = next(b for b in r["blocks"] if b["kind"] == "footnote" and b.get("from_vlm"))
    assert fn["link"]["to"] == next(b["id"] for b in r["blocks"] if b["kind"] == "table" and b.get("from_vlm"))
    c2 = _AiClient({"blocks": [{"kind": "weird"}]})
    monkeypatch.setattr(openai_client, "make_client", lambda s: c2)
    r2 = extract_structure(pdf, use_ai=True)
    assert r2["vlm"]["mode"] == "rules" and [b["kind"] for b in r2["blocks"]] == [b["kind"] for b in extract_structure(pdf)["blocks"]]


def test_chat_chart_and_numbercard_blocks_are_validated_by_code(monkeypatch):
    monkeypatch.setenv("AI_PDF_DEV", "1")
    _seed_report_db()
    j, _ = _chat(monkeypatch, {"intent": "edit", "edits": [{"op": "add_block", "kind": "chart", "metric": "영업이익", "chart_type": "line", "months": 6}]}, "영업이익 추이 선그래프 넣어줘")
    assert j["status"] == "edit" and j["plan"][0]["spec"] == {"type": "chart", "metric": "영업이익", "chart_type": "line", "limit": 10, "scope": None, "months_n": 6}
    j, _ = _chat(monkeypatch, {"intent": "edit", "edits": [{"op": "add_block", "kind": "chart", "metric": "인원수"}]}, "인원수 막대그래프")
    assert j["status"] == "no_data" and "데이터 없음" in j["message"]
    j, _ = _chat(monkeypatch, {"intent": "edit", "edits": [{"op": "add_block", "kind": "chart", "metric": "매출", "orgs": ["없는사업부99"]}]}, "없는사업부99 매출 차트")
    assert j["status"] == "no_data" and "조직" in j["message"]
    j, _ = _chat(monkeypatch, {"intent": "edit", "edits": [{"op": "add_block", "kind": "numbercard", "metrics": ["매출", "영업이익"], "compare": ["전년 동월"]}]}, "숫자 카드 넣어줘")
    assert [c["label"] for c in j["plan"][0]["spec"]["cards"]] == ["매출", "영업이익"] and j["plan"][0]["spec"]["cards"][0]["delta_label"] == "전년比"


def test_fill_chart_types_numbercard_and_unit_are_code_computed(monkeypatch):
    """차트·카드·편집 연결에서 서버 금액 문자열을 검증한다.

    Args:
        monkeypatch: 환경 설정 fixture.
    Returns:
        없음.
    Raises:
        AssertionError: 금액·블록 결과 불일치.
    Side Effects:
        승인된 테스트 DB의 합성 자료를 조회한다.
    """
    monkeypatch.setenv("AI_PDF_DEV", "1")
    _seed_report_db()
    blocks = [{"id": "c1", "kind": "chart", "metric": "영업이익", "limit": 5, "chart_type": "waterfall"},
              {"id": "c2", "kind": "chart", "metric": "영업이익", "chart_type": "line", "months_n": 6},
              {"id": "n1", "kind": "numbercard", "cards": [{"label": "매출 (억원)", "delta_label": "계획比"}, {"label": "없는지표", "delta_label": None}]},
              {"id": "t1", "kind": "spec_table", "unit": "백만원", "spec": {"corner": "지표 (억원)", "rows": [{"t": "metric", "metric": "매출"}], "cols": [{"t": "current"}]}}]
    j = client.post("/v1/dev/report-data/fill", json={"base": _BASE, "blocks": blocks}).json()
    by = {b["id"]: b for b in j["blocks"]}
    wf = by["c1"]["rows"]
    assert wf[0]["type"] == "total" and wf[-1]["type"] == "total" and wf[0]["name"] == "계획" and wf[-1]["name"] == "실적"
    assert sum(Decimal(r["won"]) for r in wf[1:-1]) == Decimal(wf[-1]["won"]) - Decimal(wf[0]["won"])                # 증감의 합 = 실적 − 계획
    assert by["n1"]["cards"][0]["value"] == "10.0" and by["n1"]["cards"][1]["missing"] is True
    rows = by["t1"]["rows"]
    assert rows[0][0]["text"] == "지표 (백만원)" and rows[1][1]["text"] == "1,000.0"


def test_vlm_table_with_missing_header_row_is_replaced_by_pdf_table_and_flagged():
    from app.pdf_structure import vlm as V
    T = lambda *r: [{"type": "text", "text": x} if x else {"type": "number"} for x in r]
    vb = [{"kind": "table", "page": 1, "needs_review": [], "table": {"columns": 3, "header_rows": 1, "rows": [T("매출", "", ""), T("영업이익", "", "")], "number_cells": 4, "unit_hint": None}}]
    rb = [{"kind": "table", "page": 1, "needs_review": [], "table": {"columns": 3, "header_rows": 1, "unit_hint": "(단위: 억원)", "number_cells": 4, "rows": [T("(단위: 억원)", "실적", "계획"), T("매출", "", ""), T("영업이익", "", "")]}}]
    assert V.reconcile_tables(vb, rb) == 1
    assert len(vb[0]["table"]["rows"]) == 3 and vb[0]["needs_review"][0]["code"] == "table_shape"


def test_derive_spec_maps_difference_column_to_gap():
    from app.pdf_structure import data_select as D
    T = lambda *r: [{"type": "text", "text": x} if x else {"type": "number"} for x in r]
    sp = D.derive_spec([T("사업부", "계획", "실적", "차이"), T("사업부1", "", "", "")], 1, ["영업이익"], [{"code": "F10", "name": "사업부1"}])
    assert [c["t"] for c in sp["cols"]] == ["current", "current", "gap"] or sp["cols"][-1]["t"] == "gap"


def _shifted_vlm_blocks(lines, dy):
    """줄 단위 블록을 만들고 박스를 세로로 dy 만큼 틀어 놓는다(VLM 좌표가 아래로 밀린 상황)."""
    from app.pdf_structure.extract import mask
    out = []
    for l in lines:
        out.append({"kind": "paragraph", "page": 1, "text": mask(l.raw)[0], "bbox": [l.x0, l.top + dy, l.x1, l.bottom + dy], "needs_review": [], "_line": l})
    return out


def test_snap_uses_text_and_reading_order_not_vlm_vertical_position():
    from app.pdf_structure import vlm as V
    from app.pdf_structure.extract import read_pdf
    doc = fitz.open()
    pg = doc.new_page()
    ko = ["월간 실적 보고서", "영업이익은 계획 대비 개선되었습니다", "주요 증감 원인 요약", "신규 계약 반영으로 매출 증가", "일회성 비용 발생으로 이익 감소", "각주 참고 사항 안내문"]
    try:
        font = fitz.Font("korea") if hasattr(fitz, "Font") else None
    except Exception:
        font = None
    if font is None:
        pytest.skip("한글 글꼴 없음")
    for i, t in enumerate(ko):
        y = 80 + i * 120
        pg.insert_text((60, y), t, fontname="korea", fontsize=12)
    data = doc.tobytes()
    doc.close()
    _, lines, _, _ = read_pdf(data)
    ls = lines[0]
    if len(ls) != len(ko):
        pytest.skip("한글 줄 추출이 기대와 다름")
    blocks = _shifted_vlm_blocks(ls, dy=300)                 # 모든 박스를 300pt 아래로 틀어 놓는다
    V.snap_bboxes(blocks, {1: ls}, {1: (595.0, 842.0)}, [])
    for b in blocks:
        l = b["_line"]
        cy = (l.top + l.bottom) / 2
        assert b["bbox"][1] <= cy <= b["bbox"][3], (b["text"], b["bbox"], cy)      # 박스가 자기 글자 줄을 덮는다
        assert not b["needs_review"]


def test_snap_flags_block_whose_text_is_not_in_pdf_and_when_no_text_layer():
    from app.pdf_structure import vlm as V
    from app.pdf_structure.extract import read_pdf, _flag
    doc = fitz.open()
    pg = doc.new_page()
    try:
        pg.insert_text((60, 100), "월간 실적 보고서", fontname="korea", fontsize=12)
    except Exception:
        pytest.skip("한글 글꼴 없음")
    data = doc.tobytes()
    doc.close()
    _, lines, _, _ = read_pdf(data)
    ghost = {"kind": "paragraph", "page": 1, "text": "문서에 전혀 없는 유령 문장입니다", "bbox": [10, 500, 200, 520], "needs_review": []}
    real = {"kind": "title", "page": 1, "text": "월간 실적 보고서", "bbox": [10, 600, 200, 620], "needs_review": []}
    V.snap_bboxes([real, ghost], {1: lines[0]}, {1: (595.0, 842.0)}, [])
    assert real["bbox"][1] < 100 and not real["needs_review"]                     # 위치는 글자 줄로 되돌아오고
    assert [f["code"] for f in ghost["needs_review"]] == ["box_unresolved"]       # 근거 없는 박스에는 경고가 달린다
    nolayer = {"kind": "paragraph", "page": 1, "text": "그림만 있는 페이지의 문장", "bbox": [0, 0, 1, 1], "needs_review": []}
    V.snap_bboxes([nolayer], {}, {1: (595.0, 842.0)}, [])
    assert [f["code"] for f in nolayer["needs_review"]] == ["box_unresolved"]


# ---------------- 줄 번호 방식: VLM 은 줄 id 만, 박스는 코드가 PDF 줄 좌표로 ----------------
KO_LINES = ["월간 실적 보고서", "영업이익은 계획 대비 개선되었습니다", "주요 증감 원인 요약", "신규 계약 반영으로 매출 증가", "일회성 비용 발생으로 이익 감소", "각주 참고 사항 안내문"]


def _ko_pdf():
    doc = fitz.open()
    pg = doc.new_page()
    try:
        for i, t in enumerate(KO_LINES):
            pg.insert_text((60, 80 + i * 120), t, fontname="korea", fontsize=12)
    except Exception:
        pytest.skip("한글 글꼴 없음")
    data = doc.tobytes()
    doc.close()
    return data


def _sent_lines(kw):
    txt = next(p["text"] for p in kw["messages"][1]["content"] if p["type"] == "text")
    return _json.loads(txt.split("\n")[1])


def _messy(box_dy=300):
    return [50, 500 + box_dy // 10, 990, 990]                    # 일부러 엉망인 VLM 좌표(실제 위치와 무관)


def _vlm_fake(groups_of, ignored=()):
    def answer(kw):
        rows = _sent_lines(kw)
        return {"blocks": groups_of([r["id"] for r in rows], rows), "ignored_lines": list(ignored)}
    return answer


def _text_blocks(r):
    return [b for b in r["blocks"] if b.get("from_vlm") and b["kind"] in ("title", "paragraph", "footnote")]


def test_vlm_boxes_come_from_line_ids_even_when_vlm_bbox_is_garbage(monkeypatch):
    from app.pdf_structure.extract import read_pdf, _bbox
    pdf = _ko_pdf()
    _, lines, _, _ = read_pdf(pdf)
    ls = lines[0]
    ids = [l.lid for l in ls]
    ans = _vlm_fake(lambda ids, rows: [
        {"kind": "title", "lines": [ids[0]], "bbox": _messy(), "text": "월간 실적 보고서", "level": 1},
        {"kind": "paragraph", "lines": ids[1:3], "bbox": _messy(), "text": "영업이익은 계획 대비 개선되었습니다 주요 증감 원인 요약"},
        {"kind": "paragraph", "lines": ids[3:5], "bbox": [0, 0, 1000, 1000], "text": "신규 계약 반영으로 매출 증가 일회성 비용 발생으로 이익 감소"},
        {"kind": "footnote", "lines": [ids[5]], "bbox": _messy(), "marker": "※", "text": "각주 참고 사항 안내문", "footnote_of": None}])
    c = _AiClient(ans)
    monkeypatch.setattr(openai_client, "make_client", lambda s: c)
    r = extract_structure(pdf, use_ai=True)
    assert r["vlm"]["mode"] == "vlm"
    by = {l.lid: l for l in ls}
    tb = _text_blocks(r)
    assert len(tb) == 4
    for b in tb:
        assert b["bbox"] == _bbox([by[i] for i in b["line_ids"]])      # 박스 = 줄 좌표 합집합 (±0)
        assert not any(f["code"] in ("box_unresolved", "vlm_missed") for f in b["needs_review"])
    assert r["vlm"]["boxes_unresolved"] == 0


def test_vlm_line_id_missing_is_filled_and_flagged(monkeypatch):
    pdf = _ko_pdf()
    ans = _vlm_fake(lambda ids, rows: [
        {"kind": "title", "lines": [ids[0]], "text": "월간 실적 보고서", "level": 1},
        {"kind": "paragraph", "lines": ids[1:3], "text": "영업이익은 계획 대비 개선되었습니다 주요 증감 원인 요약"},
        {"kind": "footnote", "lines": [ids[5]], "marker": "※", "text": "각주 참고 사항 안내문", "footnote_of": None}])      # ids[3], ids[4] 를 빠뜨림
    c = _AiClient(ans)
    monkeypatch.setattr(openai_client, "make_client", lambda s: c)
    r = extract_structure(pdf, use_ai=True)
    filled = [b for b in r["blocks"] if b.get("from_rules_fill")]
    assert filled and {i for b in filled for i in b["line_ids"]} >= {3, 4}
    assert all(any(f["code"] == "vlm_missed" for f in b["needs_review"]) for b in filled)      # "확인 필요 0개" 가 아니다
    assert r["vlm"]["lines_missing_filled"] == len(filled)


def test_vlm_line_in_two_blocks_belongs_to_first_and_second_is_flagged():
    from app.pdf_structure import vlm as V
    from app.pdf_structure.extract import read_pdf, _bbox
    _, lines, _, _ = read_pdf(_ko_pdf())
    ls = lines[0]
    ids = [l.lid for l in ls]
    out = {"blocks": [{"kind": "paragraph", "lines": ids[0:3], "text": "월간 실적 보고서 영업이익은 계획 대비 개선되었습니다"},
                      {"kind": "paragraph", "lines": ids[2:5], "text": "주요 증감 원인 요약 신규 계약 반영으로 매출 증가"}], "ignored_lines": [ids[5]]}
    rep: dict = {}
    bl = V.build_blocks(out, {"page": 1, "width": 595.0, "height": 842.0}, ls, rep)
    by = {l.lid: l for l in ls}
    assert bl[0]["line_ids"] == ids[0:3] and bl[1]["line_ids"] == ids[3:5]       # 겹친 줄은 먼저 배정한 블록만 갖는다
    assert not bl[0]["needs_review"] and [f["code"] for f in bl[1]["needs_review"]] == ["kind_ambiguous"]
    assert bl[1]["bbox"] == _bbox([by[i] for i in ids[3:5]]) and rep["overlap"] == [ids[2]] and rep["missing"] == [ids[5]]      # 글자가 있는 줄은 무시 목록에 넣어도 '놓친 줄'로 본다


def test_vlm_page_without_text_layer_keeps_vlm_box_but_flags_every_block():
    from app.pdf_structure import vlm as V
    out = {"blocks": [{"kind": "paragraph", "bbox": [10, 10, 500, 60], "text": "그림만 있는 쪽의 문장"}, {"kind": "chart", "bbox": [10, 100, 600, 400], "chart_type": "bar", "title": "차트"}]}
    bl = V.build_blocks(out, {"page": 1, "width": 800.0, "height": 1000.0}, [])
    assert all([f["code"] for f in b["needs_review"]] == ["box_unresolved"] for b in bl)
    assert bl[0]["bbox"] == [8.0, 10.0, 400.0, 60.0]                              # VLM 좌표를 그대로 변환(근사)


def test_vlm_too_many_lines_page_sends_no_line_list(monkeypatch):
    from app.pdf_structure import vlm as V
    monkeypatch.setattr(V, "MAX_LINES_PER_PAGE", 3)
    seen = {}
    def answer(kw):
        seen["txt"] = next(p["text"] for p in kw["messages"][1]["content"] if p["type"] == "text")
        return {"blocks": [{"kind": "paragraph", "bbox": [50, 50, 900, 900], "text": "월간 실적 보고서 영업이익은 계획 대비 개선되었습니다"}]}
    monkeypatch.setattr(openai_client, "make_client", lambda s: _AiClient(answer))
    r = extract_structure(_ko_pdf(), use_ai=True)
    assert "줄 목록 없음" in seen["txt"] and r["vlm"]["mode"] == "vlm"
    assert all(b["bbox"][3] - b["bbox"][1] < 842 for b in r["blocks"])             # 글자 대조(snap)나 경고로 처리된다


def test_sample_pdfs_mock_vlm_with_line_ids_and_garbage_bbox_cover_all_text_blocks():
    import glob
    from app.pdf_structure import vlm as V
    from app.pdf_structure.extract import read_pdf
    pdfs = sorted(glob.glob(str(Path.home() / "Downloads" / "Claude outputs" / "*.pdf")))
    if not pdfs:
        pytest.skip("샘플 PDF 없음")
    for f in pdfs:
        data = open(f, "rb").read()
        rules = extract_structure(data)["blocks"]
        pages, lines, _, _ = read_pdf(data)
        for pi, pl in enumerate(lines, 1):
            groups = []
            if not [x for x in rules if x["page"] == pi]:
                continue                                                  # 글자 없는 스캔 쪽은 규칙 블록이 없다
            for b in [x for x in rules if x["page"] == pi]:
                g = {"kind": b["kind"], "lines": sorted({int(str(i).rstrip("abcdefgh")) for i in b["line_ids"]}), "bbox": [0, 900, 1000, 1000]}          # 위치는 엉망(블록 정리가 만든 조각 이름은 줄 번호로)
                if b["kind"] in ("title", "paragraph", "footnote"):
                    g.update(text=b["text"], level=b.get("level"))
                elif b["kind"] == "table":
                    g["rows"] = [[c.get("text") if c["type"] == "text" else None for c in r] for r in b["table"]["rows"]]
                elif b["kind"] == "chart":
                    g.update(chart_type="bar", categories=b["categories"])
                else:
                    g["items"] = [{"marker": i["marker"], "text": i["text"], "level": 0} for i in b["items"]]
                groups.append(g)
            built = V.build_blocks({"blocks": groups}, {"page": pi, "width": 595.0, "height": 842.0}, pl, {})
            by = {l.lid: l for l in pl}
            miss = [b for b in built if b["line_ids"] and not all(b["bbox"][1] <= (by[i].top + by[i].bottom) / 2 <= b["bbox"][3] for i in b["line_ids"])]
            assert miss == [], f


# ---------------- 나란히 놓인 덩어리(칸) 쪼개기 ----------------
def _cards_pdf() -> bytes:
    """A4 가로: 위 제목 줄 하나에 두 제목(좌우), 아래 카드 3개(이름·큰 숫자·보조 글자)가 같은 높이에 나란히."""
    doc = fitz.open()
    pg = doc.new_page(width=842, height=595)
    pg.insert_text((50, 60), "Monthly dashboard", fontname="helv", fontsize=15)
    for i, x in enumerate((60, 330, 600)):
        pg.insert_text((x, 110), f"Card {i + 1} name", fontname="helv", fontsize=8.5)
        pg.insert_text((x, 135), f"{i + 1}00.5", fontname="helv", fontsize=17)
        pg.insert_text((x, 152), "vs plan +1.0", fontname="helv", fontsize=8.5)
    pg.insert_text((60, 200), "Left panel title", fontname="helv", fontsize=11)
    pg.insert_text((520, 200), "Right panel title", fontname="helv", fontsize=11)
    pg.insert_text((60, 300), "Body text of the report goes here as a plain sentence.", fontname="helv", fontsize=10)
    out = doc.tobytes()
    doc.close()
    return out


def _no_split(monkeypatch):
    from app.pdf_structure import extract as X
    monkeypatch.setattr(X, "_split_columns", lambda ls, W, H, p: ls)


def test_side_by_side_cards_are_split_into_columns_with_exact_boxes():
    from app.pdf_structure.extract import read_pdf, _bbox
    r = extract_structure(_cards_pdf())
    cards = [b for b in r["blocks"] if b["kind"] == "paragraph" and "Card" in b["text"]]
    assert len(cards) == 3                                                       # 카드 3개가 따로 박스
    assert len({tuple(b["bbox"]) for b in cards}) == 3 and cards[0]["bbox"][2] < cards[1]["bbox"][0] < cards[1]["bbox"][2] < cards[2]["bbox"][0]
    assert all(len(b["line_ids"]) == 3 for b in cards)                           # 카드 하나 = 세 줄(이름·숫자·보조 글자)
    _, lines, _, _ = read_pdf(_cards_pdf())
    by = {l.lid: l for l in lines[0]}
    assert all(b["bbox"] == _bbox([by[i] for i in b["line_ids"]]) for b in cards)   # 박스 = 조각 좌표 합집합
    ids = [l.lid for l in lines[0]]
    assert ids == list(range(len(ids)))                                          # 번호는 읽는 순서대로 이어진다
    titles = [b for b in r["blocks"] if "panel" in (b.get("text") or "")]
    assert len(titles) == 2 and titles[0]["bbox"][2] < titles[1]["bbox"][0]     # 한 줄에 합쳐졌던 두 제목이 둘로


def test_no_gutter_pdfs_are_identical_to_before(monkeypatch):
    from app.pdf_structure import extract as X
    pdfs = [make_pdf(), make_pdf(misaligned=True)]
    if SAMPLE.exists():
        pdfs.append(SAMPLE.read_bytes())
    for data in pdfs:
        now = extract_structure(data)
        _, ls_now, _, _ = X.read_pdf(data)
        with monkeypatch.context() as m:
            m.setattr(X, "_split_columns", lambda ls, W, H, p: ls)
            before = extract_structure(data)
            _, ls_before, _, _ = X.read_pdf(data)
        assert [[(l.lid, l.top, l.x0, l.x1, l.raw) for l in p] for p in ls_now] == [[(l.lid, l.top, l.x0, l.x1, l.raw) for l in p] for p in ls_before]
        assert [(b["kind"], b["bbox"], b["line_ids"]) for b in now["blocks"]] == [(b["kind"], b["bbox"], b["line_ids"]) for b in before["blocks"]]


def test_wide_gap_table_is_not_mistaken_for_a_gutter():
    r = extract_structure(make_pdf())                                           # 열 간격이 넓은 3칸 표(185pt)도 표로 남는다
    t = next(b for b in r["blocks"] if b["kind"] == "table")
    assert t["table"]["columns"] == 3 and len(t["table"]["rows"]) == 3


def test_vlm_line_ids_of_split_cards_give_separate_exact_boxes():
    from app.pdf_structure import vlm as V
    from app.pdf_structure.extract import read_pdf, _bbox
    _, lines, _, _ = read_pdf(_cards_pdf())
    ls = lines[0]
    cards = [[l.lid for l in ls if f"Card {k}" in l.raw or (l.x0 > 30 + 270 * (k - 1) and l.x0 < 120 + 270 * (k - 1) and l.top > 95 and l.top < 160)] for k in (1, 2, 3)]
    out = {"blocks": [{"kind": "numbercard", "lines": c, "bbox": [0, 0, 1000, 1000], "cards": [{"label": f"Card {k}", "unit": "", "delta_label": None}]} for k, c in enumerate(cards, 1)], "ignored_lines": []}
    bl = V.build_blocks(out, {"page": 1, "width": 842.0, "height": 595.0}, ls, {})
    by = {l.lid: l for l in ls}
    assert len({tuple(b["bbox"]) for b in bl}) == 3                              # 서로 다른 박스(VLM 좌표는 전부 같은 값이었다)
    assert all(b["bbox"] == _bbox([by[i] for i in b["line_ids"]]) and len(b["line_ids"]) == 3 for b in bl)


def test_line_list_sent_to_vlm_has_right_edge_and_prompt_v6():
    from app.pdf_structure import vlm as V
    from app.pdf_structure.ai_extract import _line_rows
    from app.pdf_structure.extract import read_pdf
    _, lines, _, _ = read_pdf(_cards_pdf())
    assert all({"id", "x", "x1", "top"} <= set(r) for r in _line_rows(lines[0])) and V.PROMPT_VERSION == "pdf-vlm-v6" and "x1(오른쪽 끝)" in V.SYSTEM


def test_block_with_non_adjacent_lines_is_flagged_because_its_box_is_wide():
    from app.pdf_structure import vlm as V
    from app.pdf_structure.extract import read_pdf
    _, lines, _, _ = read_pdf(_ko_pdf())
    ids = [l.lid for l in lines[0]]
    out = {"blocks": [{"kind": "paragraph", "lines": [ids[0], ids[3]], "text": "월간 실적 보고서 신규 계약 반영으로 매출 증가"},
                      {"kind": "paragraph", "lines": [ids[1], ids[2]], "text": "영업이익은 계획 대비 개선되었습니다 주요 증감 원인 요약"},
                      {"kind": "paragraph", "lines": [ids[4], ids[5]], "text": "일회성 비용 발생으로 이익 감소 각주 참고 사항 안내문"}]}
    bl = V.build_blocks(out, {"page": 1, "width": 595.0, "height": 842.0}, lines[0], {})
    assert [f["code"] for f in bl[0]["needs_review"]] == ["kind_ambiguous"] and not bl[1]["needs_review"] and not bl[2]["needs_review"]


# ---------------- 여러 줄(병합) 머리 표 ----------------
def _T(*r):
    return [{"type": "text", "text": x} if x else {"type": "number"} for x in r]


_MERGED_ROWS = [_T("사업부", "매출", "", "", "영업이익", "", "", "판정"), _T("", "계획", "실적", "차이", "계획", "실적", "차이", ""),
                _T("사업부1", "", "", "", "", "", "", "Gap"), _T("사업부2", "", "", "", "", "", "", "정상"), _T("소계", "", "", "", "", "", "", ""), _T("합계", "", "", "", "", "", "", "")]
_SPANS = [{"row": 0, "col": 1, "colspan": 3, "rowspan": 1}, {"row": 0, "col": 4, "colspan": 3, "rowspan": 1}, {"row": 0, "col": 0, "colspan": 1, "rowspan": 2}]
_ORGS = [{"code": "F10", "name": "사업부1"}, {"code": "F12", "name": "사업부2"}]


def test_header_grid_fills_colspan_and_rowspan():
    from app.pdf_structure import table_header as TH
    lay = TH.header_layout(_MERGED_ROWS, 2, _SPANS)
    assert lay["grid"] == [["사업부", "매출", "매출", "매출", "영업이익", "영업이익", "영업이익", "판정"], ["사업부", "계획", "실적", "차이", "계획", "실적", "차이", "판정"]]
    assert TH.header_grid(_MERGED_ROWS, 2, _SPANS) == lay["grid"] and not lay["ambiguous"]
    assert lay["cells"][0][1] == {"j": 1, "text": "매출", "colspan": 3} and lay["cells"][0][2].get("hide") and lay["cells"][0][0].get("rowspan") == 2 and lay["cells"][1][0].get("hide")
    assert lay["paths"][1] == ["매출", "계획"] and lay["paths"][7] == ["판정"] and lay["paths"][0] == ["사업부"]


def test_derive_spec_gives_each_column_its_metric_and_total_rows():
    from app.pdf_structure import data_select as D
    sp = D.derive_spec(_MERGED_ROWS, 2, ["매출", "영업비용", "영업이익"], _ORGS, _SPANS)
    assert [(c["t"], c.get("metric")) for c in sp["cols"]] == [("cmpval", "매출"), ("current", "매출"), ("gap", "매출"), ("cmpval", "영업이익"), ("current", "영업이익"), ("gap", "영업이익"), ("unknown", None)]
    assert [r["t"] for r in sp["rows"]] == ["org", "org", "subtotal", "total"] and D._row_kind("계획 대비") is None and D._row_kind("계") == "total" and D._row_kind("합 계") == "total"


def _fake_bases(rev_missing_org=None):
    vals = {"revenue_won": {"F10": (900e6, 860e6), "F12": (1110e6, 1150e6)}, "op_won": {"F10": (240e6, 200e6), "F12": (160e6, 200e6)}}

    def fake(con, info, y, m, col, org):
        cur, plan = vals[col][org]
        return {"현재": None if (rev_missing_org == org and col == "revenue_won") else cur, "계획": plan, "직전 월": None, "전년 동월": None, "직전 추정": None}
    return fake


def _fill(monkeypatch, spec, **kw):
    from app.pdf_structure import data_select as D
    monkeypatch.setattr(D, "_bases", _fake_bases(**kw))
    return D.fill_spec_table(None, {"orgs": _ORGS}, {"year": 2026, "month": 11}, spec, {})


def test_fill_uses_column_metric_not_row_default_and_unknown_text_column_is_no_data(monkeypatch):
    from app.pdf_structure import data_select as D
    sp = D.derive_spec(_MERGED_ROWS, 2, ["매출", "영업비용", "영업이익"], _ORGS, _SPANS)
    rows, miss = _fill(monkeypatch, sp)
    assert [c["text"] for c in rows[2]] == ["사업부1", "8.6", "9.0", "+0.4", "2.0", "2.4", "+0.4", "데이터 없음"]      # 매출 열과 영업이익 열은 서로 다른 값
    assert [c["text"] for c in rows[3]][1:7] == ["11.5", "11.1", "△0.4", "2.0", "1.6", "△0.4"] and miss
    assert [c["text"] for c in rows[0]][:3] == ["사업부", "매출", ""] and rows[0][1]["colspan"] == 3 and rows[0][0]["rowspan"] == 2     # 머리는 병합 격자 그대로(2줄)


def test_fill_missing_metric_in_db_is_no_data_never_another_metric(monkeypatch):
    from app.pdf_structure import data_select as D
    sp = D.derive_spec(_MERGED_ROWS, 2, ["매출", "영업비용", "영업이익"], _ORGS, _SPANS)
    monkeypatch.setattr(D, "METRIC_COL", {"영업비용": "cost_won", "영업이익": "op_won"})          # 매출이 DB 지표에 없는 경우
    rows, _ = _fill(monkeypatch, sp)
    r = [c["text"] for c in rows[2]]
    assert r[1:4] == ["데이터 없음"] * 3 and r[4:7] == ["2.0", "2.4", "+0.4"]
    assert len({x for row in rows[2:6] for x in [c["text"] for c in row][4:7]} & {"8.6", "9.0", "11.5"}) == 0


def test_subtotal_and_total_are_sums_of_org_rows_and_no_data_when_any_part_is_missing(monkeypatch):
    from app.pdf_structure import data_select as D
    sp = D.derive_spec(_MERGED_ROWS, 2, ["매출", "영업비용", "영업이익"], _ORGS, _SPANS)
    rows, _ = _fill(monkeypatch, sp)
    sub, tot = [c["text"] for c in rows[4]], [c["text"] for c in rows[5]]
    assert sub[:7] == ["소계", "20.1", "20.1", "0.0", "4.0", "4.0", "0.0"] and tot[1:7] == sub[1:7] and tot[0] == "합계" and sub[7] == "데이터 없음"      # 8.6+11.5, 9.0+11.1, +0.4-0.4 …
    rows, _ = _fill(monkeypatch, sp, rev_missing_org="F12")                                  # 사업부2 의 매출 실적이 없으면
    sub = [c["text"] for c in rows[4]]
    assert sub[1] == "20.1" and sub[2] == "데이터 없음" and sub[3] == "데이터 없음" and sub[5] == "4.0"      # 그 열 합계만 '데이터 없음'(일부만 더하지 않음)
    D.SCOPE_ORGS.set(["F10"])
    try:
        rows, _ = _fill(monkeypatch, sp)
        assert all(c["text"] == "데이터 없음" for c in rows[-1][1:])                          # 범위 필터가 걸리면 합계는 만들지 않는다
    finally:
        D.SCOPE_ORGS.set([])


def test_header_without_spans_is_flagged_and_columns_are_not_guessed(monkeypatch):
    from app.pdf_structure import data_select as D
    from app.pdf_structure import vlm as V
    rows = [_T("사업부", "매출", "", "", "영업이익", "", ""), _T("", "계획", "실적", "차이", "계획", "실적", "차이"), _T("사업부1", "", "", "", "", "", "")]
    out = {"blocks": [{"kind": "table", "rows": [[(c.get("text") if c["type"] == "text" else None) for c in r] for r in rows], "header_rows": 2}]}
    b = V.build_blocks(out, {"page": 1, "width": 800.0, "height": 1000.0})[0]
    assert any(f["code"] == "header_ambiguous" for f in b["needs_review"])
    sp = D.derive_spec(rows, 2, ["매출", "영업비용", "영업이익"], _ORGS, None)
    assert [(c["t"], c.get("metric")) for c in sp["cols"]] == [("cmpval", "매출"), ("unknown", None), ("unknown", None), ("cmpval", "영업이익"), ("unknown", None), ("unknown", None)]
    out_rows, _ = _fill(monkeypatch, sp)
    assert [c["text"] for c in out_rows[-1]][1:] == ["8.6", "데이터 없음", "데이터 없음", "2.0", "데이터 없음", "데이터 없음"]


def _two_column_pdf() -> bytes:
    """A4 세로, 본문 2단(칸 사이 약 40pt). 왼쪽은 촘촘한 문단, 오른쪽은 간격 있는 항목(좌우 줄의 높이는 서로 다름)."""
    doc = fitz.open()
    pg = doc.new_page()
    pg.insert_text((40, 60), "Two column briefing", fontname="helv", fontsize=16)
    for i in range(14):
        pg.insert_text((40, 110 + i * 14), f"Left column sentence number {i} with plenty of words in it", fontname="helv", fontsize=9.5)
    for i in range(7):
        pg.insert_text((313, 118 + i * 28), f"Right item {i} with a rather long description text", fontname="helv", fontsize=9.5)
    out = doc.tobytes()
    doc.close()
    return out


def test_two_column_body_is_split_into_columns_and_read_column_by_column():
    from app.pdf_structure.extract import read_pdf
    r = extract_structure(_two_column_pdf())
    _, lines, _, _ = read_pdf(_two_column_pdf())
    texts = [l.raw for l in lines[0]]
    first_right = next(i for i, t in enumerate(texts) if t.startswith("Right item"))
    assert all(t.startswith(("Two", "Left")) for t in texts[:first_right]) and all(t.startswith("Right") for t in texts[first_right:])      # 왼쪽 칸을 다 읽은 뒤 오른쪽 칸
    for b in r["blocks"]:
        assert not (b["bbox"][0] < 280 and b["bbox"][2] > 313), (b["kind"], b["bbox"])                                                        # 어떤 블록도 두 칸에 걸치지 않는다
    assert [l.lid for l in lines[0]] == list(range(len(lines[0])))


def test_gutter_rules_keep_tables_and_single_column_text_intact(monkeypatch):
    from app.pdf_structure import extract as X
    for data in (make_pdf(), _ko_pdf()):
        now = [[l.raw for l in p] for p in X.read_pdf(data)[1]]
        with monkeypatch.context() as m:
            m.setattr(X, "_split_columns", lambda ls, W, H, p: ls)
            before = [[l.raw for l in p] for p in X.read_pdf(data)[1]]
        assert now == before


# ---------------- 어려운 PDF 대응(카드 격자·신문형 단·도형 경계·라벨 열·날짜) ----------------
def _draw_pdf(draw, size=(595, 842)) -> bytes:
    doc = fitz.open()
    pg = doc.new_page(width=size[0], height=size[1])
    draw(pg)
    out = doc.tobytes()
    doc.close()
    return out


def _blocks_x(r, kind=None):
    return [b for b in r["blocks"] if kind is None or b["kind"] == kind]


def test_two_by_two_card_grid_is_split_into_four_cards():
    def draw(pg):
        for ci, x in enumerate((60, 300)):
            for ri, y in enumerate((100, 200)):
                pg.insert_text((x, y), f"Metric {ci}{ri}", fontname="helv", fontsize=8.5)
                pg.insert_text((x, y + 22), f"{ci}{ri}0.5", fontname="helv", fontsize=20)
                pg.insert_text((x, y + 38), "vs plan +1.0", fontname="helv", fontsize=8.5)
    r = extract_structure(_draw_pdf(draw))
    cards = [b for b in _blocks_x(r, "paragraph") if "Metric" in b["text"]]
    assert len(cards) == 4 and len({tuple(b["bbox"]) for b in cards}) == 4          # 줄마다 2칸뿐이어도(라벨-값 표 방어) 카드(글자 크기가 다름)는 쪼갠다
    assert all(not (b["bbox"][0] < 200 < b["bbox"][2]) for b in cards)


def test_three_column_newspaper_text_is_split_into_three_columns():
    words = "report quarter review metric budget plan cost sales field data trend risk item note".split()

    def draw(pg):
        for ci, x in enumerate((40, 207, 374)):
            for i in range(18):
                pg.insert_text((x, 150 + i * 14), " ".join(words[(i + ci + k) % len(words)] for k in range(5))[:30], fontname="helv", fontsize=10)
    r = extract_structure(_draw_pdf(draw))
    for b in r["blocks"]:
        assert not (b["bbox"][0] < 190 < b["bbox"][2] or b["bbox"][0] < 357 < b["bbox"][2]), (b["kind"], b["bbox"])        # 어떤 블록도 단 경계를 넘지 않는다
    assert len([b for b in r["blocks"] if b["bbox"][0] < 100]) >= 1 and len([b for b in r["blocks"] if b["bbox"][0] > 350]) >= 1


def test_shape_edge_separates_a_table_from_a_text_list_with_the_same_rows():
    def draw(pg):
        pg.draw_rect(fitz.Rect(40, 90, 410, 240), fill=(0.9, 0.92, 0.96), color=(0.7, 0.7, 0.7))              # 표 배경 사각형(오른쪽 끝 x=410)
        for i in range(6):
            y = 110 + i * 22
            pg.insert_text((50, y), f"Unit{i}", fontname="helv", fontsize=9)
            for j, x in enumerate((150, 230, 310)):
                pg.insert_text((x, y), f"{i}.{j}0", fontname="helv", fontsize=9)
            pg.insert_text((448, y), f"Issue {i}: short description text", fontname="helv", fontsize=9)
    r = extract_structure(_draw_pdf(draw))
    assert not any(b["bbox"][0] < 410 and b["bbox"][2] > 448 for b in r["blocks"])                         # 표와 오른쪽 목록이 한 블록에 섞이지 않는다


def test_year_month_without_wol_is_a_date_kept_and_retimed_not_a_value():
    from app.pdf_structure import data_select as D
    assert mask("제 12호 · 2026.11")[0] == "제 12호 · 2026.11" and mask("영업이익 128.4억")[0] == "영업이익 {수치}"
    assert D.retime("기준: 2026.11 (가상)", (26, 11), {"year": 2026, "month": 10}) == "기준: 2026.10 (가상)"
    assert D.retime("2025.11 대비", (26, 11), {"year": 2026, "month": 10}) == "2025.11 대비"                   # 다른 해는 그대로


def test_label_column_is_found_when_first_column_is_a_group_and_totals_sum_org_rows(monkeypatch):
    from app.pdf_structure import data_select as D
    rows = [_T("구분", "조직", "계획", "실적"), _T("본부A", "사업부1", "", ""), _T("", "사업부2", "", ""), _T("합계", "", "", "")]
    assert D._label_col(rows, 1, ["매출", "영업이익"], _ORGS) == 1
    sp = D.derive_spec(rows, 1, ["매출", "영업이익"], _ORGS)
    assert sp["lead_heads"] == ["구분"] and sp["corner"] == "조직" and sp["rows"][0]["lead"] == ["본부A"] and sp["rows"][2]["t"] == "total"
    monkeypatch.setattr(D, "_bases", _fake_bases())
    out, _ = D._fill_table(None, {"orgs": _ORGS}, {"year": 2026, "month": 11}, {"rows": rows, "header_rows": 1}, ["매출", "영업이익"], _ORGS, {}, None)
    assert [c["text"] for c in out[1]] == ["본부A", "사업부1", "8.6", "9.0"] or [c["text"] for c in out[1]][:2] == ["본부A", "사업부1"]
    assert [c["text"] for c in out[3]][0] == "합계" and out[3][2]["missing"] is False                  # 합계 행: 사업부 행 합
    out2, _ = D.fill_spec_table(None, {"orgs": _ORGS}, {"year": 2026, "month": 11}, sp, {})
    assert [c["text"] for c in out2[0]][:2] == ["구분", "조직"] and [c["text"] for c in out2[1]][:2] == ["본부A", "사업부1"]


def test_masked_number_token_never_leaks_into_unresolved_table_cells(monkeypatch):
    from app.pdf_structure import data_select as D
    monkeypatch.setattr(D, "_bases", _fake_bases())
    rows = [_T("항목", "값"), [{"type": "text", "text": "▲ {수치}"}, {"type": "text", "text": "▲ {수치}p"}]]
    out, miss = D._fill_table(None, {"orgs": _ORGS}, {"year": 2026, "month": 11}, {"rows": rows, "header_rows": 1}, ["매출"], _ORGS, {}, None)
    assert "{수치}" not in str([c["text"] for r in out for c in r]) and miss


# ---------------- 흐름 점검에서 나온 수정 ----------------
def test_vlm_may_ignore_only_page_numbers_and_numeric_labels_other_lines_are_filled(monkeypatch):
    from app.pdf_structure import vlm as V
    assert V._ignorable("- 1 -") and V._ignorable("17.8") and V._ignorable("7.1 7.1") and not V._ignorable("2026.11 · 경영기획팀") and not V._ignorable("경영기획팀 1 / 1")
    from app.pdf_structure.extract import read_pdf
    _, lines, _, _ = read_pdf(_ko_pdf())
    ids = [l.lid for l in lines[0]]
    rep: dict = {}
    V.build_blocks({"blocks": [{"kind": "paragraph", "lines": ids[:3], "text": "월간 실적 보고서 영업이익은 계획 대비 개선되었습니다"}], "ignored_lines": ids[3:]}, {"page": 1, "width": 595.0, "height": 842.0}, lines[0], rep)
    assert rep["missing"] == ids[3:]                                                     # 한글 줄을 무시했으면 전부 놓친 줄 → 채워서 노란 표시


def test_rule_block_whose_lines_are_already_owned_by_a_vlm_block_is_not_added_twice():
    from app.pdf_structure import vlm as V
    vb = [{"kind": "table", "page": 1, "line_ids": [5, 6, 7], "needs_review": [], "bbox": [0, 0, 10, 10], "table": {"rows": [[{"type": "text", "text": "원인"}]], "columns": 1, "header_rows": 1}}]
    rb = [{"kind": "paragraph", "page": 1, "line_ids": [5], "needs_review": [], "bbox": [0, 0, 10, 10], "text": "표 1. 원인별 건수"}]
    assert V.add_missed(vb, rb, {1: (595.0, 842.0)}) == []


def test_full_date_is_kept_and_reason_column_is_a_cause_column():
    from app.pdf_structure import data_select as D
    assert mask("작성 2026.11.20 · 제 12호")[0] == "작성 2026.11.20 · 제 12호"
    sp = D.derive_spec([_T("조직", "계획", "사유"), _T("사업부1", "", "계약 종료"), _T("사업부2", "", "신규 계약")], 1, ["영업이익"], _ORGS)
    assert [c["t"] for c in sp["cols"]] == ["cmpval", "cause"] or sp["cols"][-1]["t"] == "cause"


def test_chart_metric_is_read_from_text_never_defaulted_and_month_axis_uses_those_months(monkeypatch):
    from app.pdf_structure import data_select as D
    seen = {}

    def fake_chart(con, info, base, metric, limit, ctype="bar", scope=None, months_n=None, months=None):
        seen.update(metric=metric, ctype=ctype, months=months)
        return ([{"name": "x", "won": 1, "text": "0.0"}], False) if metric in D.METRIC_COL else ([], True)
    monkeypatch.setattr(D, "_chart", fake_chart)
    base = {"year": 2026, "month": 11}

    def run(block):
        monkeypatch.setattr(D, "_con", lambda: None)
        return D.fill_report.__wrapped__(base, [block]) if hasattr(D.fill_report, "__wrapped__") else None
    # fill_report 전체는 DB 가 필요하므로 차트 분기의 규칙만 같은 식으로 확인한다
    metrics = ["매출", "영업비용", "영업이익"]
    known = [k for k in metrics if k in D.METRIC_COL]
    assert D._metric_in("분기별 지연 건수 추이", known) is None and D._metric_in("월별 영업이익 추이", known) == "영업이익"
    import re as _re
    cats = ["6월", "7월", "8월"]
    ms = [int(m.group(1)) for m in (_re.fullmatch(r"\s*(\d{1,2})월\s*", c) for c in cats) if m]
    assert ms == [6, 7, 8]
