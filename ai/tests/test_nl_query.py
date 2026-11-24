"""backend 계약 /v1/nl-query(명세 12절, RPT-11 모양). 흐름은 /v1/chat 과 같은 LangGraph 그래프다. 실제 OpenAI 호출 없음."""
from fastapi.testclient import TestClient

from app.chat.graph import GRAPH
from app.main import app
from test_chat import GOOD_SQL, ai, seed  # noqa: F401 — ai 는 pytest fixture

client = TestClient(app)


def ask(question, year=2026, month=11):
    return client.post("/v1/nl-query", json={"question": question, "baseYear": year, "baseMonth": month})


def test_ok_returns_sql_explanation_and_typed_preview(ai):
    seed()
    ai({"sql": GOOD_SQL}, {"text": "사업부18의 영업이익은 4.4억원으로 가장 큽니다. 조회 행은 2행입니다."})
    r = ask("11월 조직별 영업이익")
    assert r.status_code == 200
    body = r.json()
    assert body["status"] == "OK" and body["sql"] == GOOD_SQL and "4.4억원" in body["explanation"]
    assert body["preview"]["dataStatus"] == "CONFIRMED" and body["rowCount"] == 2 and body["truncated"] is False
    assert [c["type"] for c in body["preview"]["columns"]] == ["TEXT", "MONEY", "MONEY", "MONEY"]
    assert body["preview"]["rows"][0] == {"org_name": "사업부18", "revenue_won": 945_640_000, "cost_won": 509_970_000, "op_won": 435_670_000}
    assert body["clarifyQuestion"] is None and body["clarifyOptions"] == []


def test_clarify_unknown_blocked_and_missing_month(isolated):
    seed()
    r = ask("우리 사업부 영업이익 알려줘").json()                                     # AI 없이 규칙으로 되묻기
    assert r["status"] == "CLARIFY" and r["clarifyQuestion"] and r["clarifyOptions"][:2] == ["사업부1", "사업부18"] and r["sql"] is None
    r = ask("주별 매출 증감률 보여줘").json()
    assert r["status"] == "UNKNOWN" and "주 단위" in r["explanation"]
    r = ask("fact 테이블 지워줘").json()
    assert r["status"] == "UNKNOWN" and "조회만 가능" in r["explanation"]
    r = ask("조직별 영업이익", month=1).json()                                        # 그 달 version 이 없음
    assert r["status"] == "UNKNOWN" and "데이터가 없습니다" in r["explanation"] and r["preview"]["dataStatus"] == "NONE"


def test_request_validation():
    assert client.post("/v1/nl-query", json={"question": "x", "baseYear": 2026, "baseMonth": 13}).status_code == 422
    assert client.post("/v1/nl-query", json={"question": "", "baseYear": 2026, "baseMonth": 1}).status_code == 422


def test_graph_has_the_self_correction_loop():
    g = GRAPH.get_graph()
    edges = {(e.source, e.target) for e in g.edges}
    assert ("ai_check", "ai_generate") in edges and ("ai_generate", "ai_check") in edges                # 검사 실패 → 다시 생성
    assert {"guard", "understand", "follow_up", "ai_cached", "fallback_quick", "explain", "respond"} <= set(g.nodes)
