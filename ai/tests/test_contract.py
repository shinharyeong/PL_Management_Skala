"""backend 계약 /v1/data·/v1/edit·/v1/pdf-jobs(명세 12절). 실제 OpenAI·backend 호출 없음."""
import base64

import pytest
from fastapi.testclient import TestClient

from app import contract
from app.main import app
from test_pdf_structure import _AiClient, _seed_report_db, make_pdf
from app.services import openai_client

client = TestClient(app)
DEF = {"schema_version": 1, "name": "월 보고", "baseRule": {"mode": "fixed", "year": 2034, "month": 7},
       "blocks": [{"id": "b1", "kind": "title", "text": "제목", "level": 1}, {"id": "b2", "kind": "paragraph", "text": "문장"},
                  {"id": "b3", "kind": "table", "label": "실적 표", "columns": ["(단위: 억원)", "'34.7월", "계획", "전월"]}]}


def test_data_fills_blocks_with_view_values_and_status():
    _seed_report_db()
    r = client.post("/v1/data", json={"base": {"year": 2034, "month": 7}, "blocks": [{"id": "t1", "kind": "title", "text": "보고"}]})
    assert r.status_code == 200
    body = r.json()
    assert body["dataStatus"] in ("CONFIRMED", "PROVISIONAL") and body["blocks"][0]["id"] == "t1" and body["blocks"][0]["text"] == "보고"
    none = client.post("/v1/data", json={"base": {"year": 2001, "month": 1}, "blocks": []}).json()
    assert none["dataStatus"] == "NONE"
    assert client.post("/v1/data", json={"base": {"year": 2034, "month": 7}, "blocks": [{"kind": "title"}]}).status_code == 422


def test_edit_returns_proposals_and_refuses_number_changes_and_queries(monkeypatch):
    _seed_report_db()
    c = _AiClient({"intent": "edit", "edits": [{"op": "remove_block", "target": {"id": "b3"}}]})
    monkeypatch.setattr(openai_client, "make_client", lambda s: c)
    r = client.post("/v1/edit", json={"definition": DEF, "instruction": "실적 표 빼줘"}).json()
    assert r["status"] == "EDIT" and r["proposals"][0]["op"] == "remove_block" and r["proposals"][0]["target"] == "b3"
    assert r["proposals"][0]["description"]
    r = client.post("/v1/edit", json={"definition": DEF, "instruction": "영업이익을 30억으로 바꿔줘"}).json()      # AI 호출 없이 거절
    assert r["status"] == "REFUSED" and r["proposals"] == []
    c2 = _AiClient({"intent": "query", "query": {"type": "value", "metric": "영업이익", "month": 7}})
    monkeypatch.setattr(openai_client, "make_client", lambda s: c2)
    r = client.post("/v1/edit", json={"definition": DEF, "instruction": "7월 전사 영업이익"}).json()                # 조회는 편집이 아님
    assert r["status"] == "REFUSED" and r["message"] == contract.NOT_AN_EDIT


def test_pdf_job_accepts_then_reports_a_draft_template(monkeypatch):
    monkeypatch.setenv("OPENAI_API_KEY", "")                                   # 규칙 추출만(AI 호출 없음)
    sent = []
    monkeypatch.setattr(contract, "report_pdf_result", lambda job_id, body, settings=None: sent.append((job_id, body)) or True)
    r = client.post("/v1/pdf-jobs", data={"jobId": "42", "maskNumbers": "true"}, files={"file": ("월보고.pdf", make_pdf(), "application/pdf")})
    assert r.status_code == 202 and r.json() == {"jobId": "42", "accepted": True}
    job_id, body = sent[0]                                                     # TestClient 는 응답 뒤 백그라운드 작업을 바로 실행
    assert job_id == "42" and body["status"] == "SUCCEEDED" and body["error"] is None
    d = body["definition"]
    assert d["schema_version"] == 1 and d["name"] == "월보고" and d["blocks"] and all(b["id"] and b["kind"] for b in d["blocks"])
    assert body["pages"][0]["no"] == 1 and base64.b64decode(body["pages"][0]["imagePng"])[:4] == b"\x89PNG"
    ep = d["extraction"]["pages"][0]                                           # 쪽 크기(pt)와 블록 bbox 가 같은 좌표계
    assert ep["no"] == 1 and ep["width"] > 0 and ep["height"] > 0 and all(0 <= b["bbox"][0] < b["bbox"][2] <= ep["width"] + 1 and 0 <= b["bbox"][1] < b["bbox"][3] <= ep["height"] + 1 for b in d["blocks"] if b.get("bbox"))
    client.post("/v1/pdf-jobs", data={"jobId": "43"}, files={"file": ("x.pdf", b"not a pdf", "application/pdf")})
    assert sent[1][1]["status"] == "FAILED" and sent[1][1]["error"]["code"] == "PDF_UNREADABLE" and sent[1][1]["definition"] is None


def test_report_pdf_result_posts_to_backend_with_token(monkeypatch):
    calls = []

    class _Resp:
        def raise_for_status(self):
            return None
    monkeypatch.setattr(contract.httpx, "post", lambda url, json, headers, timeout: calls.append((url, headers, json)) or _Resp())
    monkeypatch.setenv("AI_BACKEND_URL", "http://backend:8080/")
    monkeypatch.setenv("REPORT_AI_TOKEN", "t0k")
    assert contract.report_pdf_result("7", {"status": "FAILED"}) is True
    assert calls == [("http://backend:8080/internal/pdf-jobs/7/result", {"X-Internal-Token": "t0k"}, {"status": "FAILED"})]
    monkeypatch.delenv("AI_BACKEND_URL")
    assert contract.report_pdf_result("7", {}) is False                         # 주소가 없으면 보고하지 않고 기록만


def test_internal_token_is_required_when_configured(monkeypatch):
    monkeypatch.setenv("REPORT_AI_TOKEN", "t0k")
    body = {"question": "x", "baseYear": 2034, "baseMonth": 7}
    assert client.post("/v1/nl-query", json=body).status_code == 401
    assert client.post("/v1/nl-query", json=body, headers={"X-Internal-Token": "wrong"}).status_code == 401
    assert client.post("/v1/nl-query", json=body, headers={"X-Internal-Token": "t0k"}).status_code == 200


def test_missing_token_is_rejected_when_required(monkeypatch):
    monkeypatch.delenv("REPORT_AI_TOKEN", raising=False)
    monkeypatch.setenv("AI_REQUIRE_INTERNAL_TOKEN", "true")
    assert client.post("/v1/nl-query", json={"question": "x", "baseYear": 2034, "baseMonth": 7}).status_code == 401
    monkeypatch.setenv("AI_BACKEND_URL", "http://backend:8080")
    monkeypatch.setattr(contract.httpx, "post", lambda *a, **k: (_ for _ in ()).throw(AssertionError("보내면 안 됨")))
    assert contract.report_pdf_result("1", {"status": "FAILED"}) is False                 # 토큰 없이 backend 에 보고하지 않는다
def test_sql_check_uses_the_same_guard_without_running():
    ok = client.post("/v1/sql-check", json={"sql": "SELECT org_name, op_won FROM v_op WHERE version = :version"}).json()
    assert ok["ok"] is True and ok["code"] is None
    bad = client.post("/v1/sql-check", json={"sql": "DELETE FROM fact"}).json()
    assert bad["ok"] is False and bad["code"] == "NOT_SELECT" and bad["message"]
    no_version = client.post("/v1/sql-check", json={"sql": "SELECT org_name FROM v_op"}).json()
    assert no_version["ok"] is False and no_version["code"] == "NO_VERSION_FILTER"


def test_data_runs_saved_sql_blocks_with_base_month_parameters():
    _seed_report_db()
    sql = ("SELECT org_name, op_won FROM v_op WHERE version = :version AND year = :base_year AND month = :base_month "
           "AND scenario = :scn ORDER BY op_won DESC")
    r = client.post("/v1/data", json={"base": {"year": 2034, "month": 7}, "blocks": [
        {"id": "q1", "kind": "table", "sql": sql},
        {"id": "q2", "kind": "table", "sql": "SELECT * FROM organization WHERE version = :version"}]}).json()
    q1, q2 = r["blocks"]
    assert q1["table"]["columns"] == ["org_name", "op_won"] and q1["table"]["rows"] and q1["missing"] is False
    assert q2["missing"] is True and q2["table"] is None and "SQL 을 실행할 수 없습니다" in q2["text"]          # 보고서용 뷰가 아닌 표는 막힘


def test_filled_cells_carry_won_values_for_export():
    """화면 글자(억원 반올림)와 별도로 원 단위 값(won)을 담는다. 백엔드 XLSX 내보내기(RPT-16)가 값 그대로 쓴다."""
    _seed_report_db()
    r = client.post("/v1/data", json={"base": {"year": 2034, "month": 7}, "blocks": [
        {"id": "m1", "kind": "metric_table", "metrics": ["영업이익"], "compare": ["계획"]}]}).json()
    row = r["blocks"][0]["rows"][1]
    assert row[0]["text"] == "영업이익" and row[1]["won"] is not None and float(row[1]["won"]) != 0
    assert float(row[1]["won"]) / 1e8 == pytest.approx(float(row[1]["text"].replace(",", "")), abs=0.05)   # 글자는 억원 반올림, won 은 원
