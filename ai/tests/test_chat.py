"""챗봇(AI 작업실) 테스트: SQL 검사기, 되묻기, 자기 수정, 문장 숫자 검증, 엑셀 숫자 == SQL 결과, 버전 필터, 적재기. 실제 OpenAI 호출 없음(모의 클라이언트)."""
import json
import psycopg
from types import SimpleNamespace

import openpyxl
import pytest
from fastapi.testclient import TestClient

from app.chat import db, schema, service, sqlguard
from app.main import app
from app.services import openai_client

client = TestClient(app)


# ------------------------------------------------------------------ 공통 준비
class SeqClient:
    """호출 순서대로 미리 정한 JSON 응답을 돌려주는 모의 클라이언트(호출 횟수·입력 기록)."""

    def __init__(self, contents, usage=(200, 80)):
        self.calls, self.users, self.systems = 0, [], []
        self._contents, self._usage = list(contents), usage
        self.chat = SimpleNamespace(completions=SimpleNamespace(create=self._create))

    def _create(self, **kw):
        self.calls += 1
        self.systems.append(kw["messages"][0]["content"])
        self.users.append(kw["messages"][1]["content"])
        c = self._contents.pop(0) if self._contents else "{}"
        return SimpleNamespace(choices=[SimpleNamespace(message=SimpleNamespace(content=json.dumps(c, ensure_ascii=False) if not isinstance(c, str) else c))],
                               usage=SimpleNamespace(prompt_tokens=self._usage[0], completion_tokens=self._usage[1]))


@pytest.fixture
def ai(monkeypatch):
    def install(*contents):
        c = SeqClient(contents)
        monkeypatch.setattr(openai_client, "make_client", lambda s: c)
        return c
    return install


def seed(extra_close_value=None):
    """작은 조회 DB: 조직 2개, 버전 2개(월마감 10월, 확정 master 11월). 금액은 원 단위."""
    con = db.open_writer()
    with con:
        con.executemany("INSERT INTO dim_org VALUES(?,?)", [("F10", "사업부1"), ("F901", "사업부18")])
        con.executemany("INSERT INTO dim_version(version, kind, label, year, month, loaded_at, source, row_count, is_latest) VALUES(?,?,?,?,?,?,?,?,0)",
                        [("close-2026-10", "close", "10월 마감", 2026, 10, "2026-11-01T00:00:00", "t", 4), ("master-2026-11-01", "master", "확정 master-2026-11-01", 2026, 11, "2026-11-16T00:00:00", "t", 4)])
        rows = [("master-2026-11-01", "F10", "사업부1", "매출", "기본료", "음성", "음성기본료", None, None, None, "답안지!행12", 2026, 11, "ACTUAL", 804_860_000),
                ("master-2026-11-01", "F10", "사업부1", "영업비용", "마케팅", None, "광고", None, None, None, "답안지!행21", 2026, 11, "ACTUAL", 432_580_000),
                ("master-2026-11-01", "F901", "사업부18", "매출", "기본료", "음성", "음성기본료", None, None, None, "답안지!행12", 2026, 11, "ACTUAL", 945_640_000),
                ("master-2026-11-01", "F901", "사업부18", "영업비용", "마케팅", None, "광고", None, None, None, "답안지!행21", 2026, 11, "ACTUAL", 509_970_000),
                ("close-2026-10", "F10", "사업부1", "매출", "기본료", "음성", "음성기본료", None, None, None, "답안지!행12", 2026, 10, "ACTUAL", 700_000_000),
                ("close-2026-10", "F10", "사업부1", "영업비용", "마케팅", None, "광고", None, None, None, "답안지!행21", 2026, 10, "ACTUAL", 400_000_000)]
        con.executemany("INSERT INTO fact VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", rows)
        con.execute("INSERT INTO gap VALUES('master-2026-11-01','G1','OP_GAP','F901','사업부18','영업이익',2026,11,'ACTUAL','PLAN',385670000,435670000,50000000,'12.9','ACTIVE','연결됨')")
        con.execute("INSERT INTO explanation VALUES('master-2026-11-01','EXP-18-020','F901:REV030:2026:11:ACTUAL','G1','F901','사업부18','매출','신사업/기타B2B',2026,11,'ACTUAL','CONTRIBUTES_TO_OP_GAP','수락','ACCEPTED',2,'신규 B2B 계약 근거를 보완했습니다.','매출_세부!AU34')")
        con.execute("INSERT INTO explanation VALUES('master-2026-11-01','EXP-X','F10:X','G9','F10','사업부1','매출','로밍',2026,11,'ACTUAL','NO_GAP','검토중','ACCEPTED',1,NULL,'매출_세부!AU23')")
        db.mark_latest(con)
        con.executescript(schema.INDEXES)
    con.close()


GOOD_SQL = ("SELECT org_name, revenue_won, cost_won, op_won FROM v_op WHERE version = :version AND year = :base_year AND month = :base_month "
            "AND scenario = :scn ORDER BY op_won DESC")


# ------------------------------------------------------------------ SQL 검사기
@pytest.mark.parametrize("sql,code", [
    ("DROP TABLE fact", "NOT_SELECT"),
    ("SELECT * FROM fact WHERE version = :version; DROP TABLE fact", "MULTI_OR_SEMICOLON"),
    ("SELECT * FROM fact WHERE version = :version -- x", "COMMENT"),
    ("SELECT * FROM sqlite_master", "TABLE_NOT_ALLOWED"),
    ("SELECT * FROM users WHERE version = :version", "TABLE_NOT_ALLOWED"),
    ("SELECT org_name FROM v_op WHERE year = 2026", "NO_VERSION_FILTER"),
    ("SELECT org_name FROM fact WHERE version = 'master-2026-11-01'", "NO_VERSION_FILTER"),
    ("SELECT org_name FROM fact WHERE version = :version AND version = 'x'", "VERSION_LITERAL"),
    ("SELECT 1 FROM fact WHERE version = :version AND (SELECT 1) ATTACH", "FORBIDDEN_KEYWORD"),
    ("WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM c) SELECT x FROM c", "RECURSIVE"),
    ("PRAGMA table_info(fact)", "NOT_SELECT"),
    ("", "EMPTY"),
])
def test_sqlguard_blocks(sql, code):
    with pytest.raises(sqlguard.SqlRejected) as e:
        sqlguard.validate(sql)
    assert e.value.code == code


def test_sqlguard_allows_select_with_version_and_cte():
    assert sqlguard.validate(GOOD_SQL).startswith("SELECT")
    assert sqlguard.validate("WITH t AS (SELECT org_code, SUM(amount_won) AS s FROM fact WHERE version = :version GROUP BY org_code) SELECT * FROM t")
    assert sqlguard.validate("SELECT a.org_code FROM fact a JOIN fact b ON a.org_code=b.org_code WHERE a.version = :version AND b.version = :version_prev")


def test_authorizer_blocks_functions_and_readonly_connection():
    seed()
    con = db.open_readonly()
    p = {"version": "master-2026-11-01", "version_prev": "close-2026-10", "base_year": 2026, "base_month": 11, "scn": "ACTUAL"}
    with pytest.raises(sqlguard.SqlRejected) as e:                       # 허용되지 않은 함수
        sqlguard.execute(con, "SELECT load_extension('x') FROM fact WHERE version = :version", p)
    assert e.value.code == "FUNCTION_NOT_ALLOWED"
    with pytest.raises(psycopg.errors.ReadOnlySqlTransaction):           # 연결 자체가 읽기 전용
        con.execute("DELETE FROM fact")
    res = sqlguard.execute(con, GOOD_SQL, p)
    assert res.columns == ["org_name", "revenue_won", "cost_won", "op_won"] and res.row_count == 2
    assert res.rows[0][3] == 945_640_000 - 509_970_000                    # 영업이익 = 매출 − 비용(뷰가 계산)
    con.close()


def test_row_limit_and_timeout():
    seed()
    con = db.open_readonly()
    p = {"version": "master-2026-11-01", "version_prev": "close-2026-10", "base_year": 2026, "base_month": 11, "scn": "ACTUAL"}
    r = sqlguard.execute(con, "SELECT org_code FROM fact WHERE version = :version", p, row_limit=2)
    assert r.row_count == 2 and r.truncated is True
    heavy = ("SELECT COUNT(*) FROM fact a, fact b, fact c, fact d, fact e, fact f, fact g, fact h, fact i, fact j, fact k, fact l, fact m "
             "WHERE a.version = :version AND a.amount_won + b.amount_won + c.amount_won + d.amount_won > 0")
    with pytest.raises(sqlguard.SqlRejected) as e:
        sqlguard.execute(con, heavy, p, timeout_s=0.05)
    assert e.value.code == "TIMEOUT"
    con.close()


# ------------------------------------------------------------------ 되묻기·모른다
def test_clarify_when_org_missing_without_ai(isolated):
    seed()
    r = client.post("/v1/chat", json={"question": "우리 사업부 영업이익 알려줘"}).json()      # make_client 금지 상태에서 AI 를 부르면 실패
    assert r["status"] == "clarify" and r["clarify"]["options"][:2] == ["사업부1", "사업부18"] and r["sql"] is None and r["ai"]["calls"] == 0
    assert [s["step"] for s in r["steps"]][:2] == ["해석", "되묻기"]


def test_unknown_weekly_and_missing_org_and_missing_close(isolated):
    seed()
    for q, word in (("주별 매출 증감률 보여줘", "주 단위"), ("사업부99 영업이익", "사업부99"), ("9월 마감 기준 영업이익", "9월 마감")):
        r = client.post("/v1/chat", json={"question": q}).json()
        assert r["status"] == "unknown" and word in r["answer_text"] and r["sql"] is None and r["ai"]["calls"] == 0


# ------------------------------------------------------------------ AI 경로: 자기 수정, 문장 검증, 캐시
def test_self_correction_then_success_and_text_validation(ai):
    seed()
    bad = {"sql": "SELECT org_name, op_won FROM v_op WHERE year = :base_year"}                       # version 조건 없음 → 검사에서 막힘
    good = {"sql": GOOD_SQL}
    text_ok = {"text": "사업부18의 영업이익은 4.4억원으로 가장 큽니다. 조회 행은 2행입니다."}
    c = ai(bad, good, text_ok)
    r = client.post("/v1/chat", json={"question": "11월 조직별 영업이익"}).json()
    assert r["status"] == "ok" and r["sql_source"] == "ai" and r["answer_source"] == "ai" and r["ai"]["calls"] == 3 and c.calls == 3
    names = [s["step"] for s in r["steps"]]
    assert "자기 수정 1회차" in names and any(s["status"] == "fail" and "NO_VERSION_FILTER" in s["detail"] for s in r["steps"])
    assert r["row_count"] == 2 and r["rows_preview"][0] == ["사업부18", 945_640_000, 509_970_000, 435_670_000]
    sent_to_ai = " ".join(c.users)
    assert "945640000" not in sent_to_ai or "rows" in c.users[-1]                                       # SQL 생성 호출에는 데이터 값 없음
    assert "945640000" not in c.users[0] and "945640000" not in c.users[1]


def test_text_with_wrong_number_is_replaced_by_template(ai):
    seed()
    c = ai({"sql": GOOD_SQL}, {"text": "사업부18의 영업이익은 99.9억원입니다."})
    r = client.post("/v1/chat", json={"question": "11월 조직별 영업이익"}).json()
    assert r["status"] == "ok" and r["answer_source"] == "template" and "99.9" not in r["answer_text"]
    assert any(s["step"] == "문장 검증" and s["status"] == "fail" for s in r["steps"]) and "문장 검증 실패" in " ".join(r["ai"]["reasons"])
    assert "4.4억원" in r["answer_text"] or "합계" in r["answer_text"]                                    # 템플릿 숫자는 조회 결과에서 나옴


def test_zero_rows_say_no_data_and_no_file(ai):
    seed()
    ai({"sql": "SELECT org_name, op_won FROM v_op WHERE version = :version AND month = 1"})
    r = client.post("/v1/chat", json={"question": "11월 영업이익"}).json()
    assert r["status"] == "ok" and r["row_count"] == 0 and r["answer_text"] == "해당 데이터가 없습니다." and r["file"] is None


def test_sql_cache_second_question_makes_no_ai_sql_call(ai):
    seed()
    c = ai({"sql": GOOD_SQL}, {"text": "사업부18의 영업이익은 4.4억원입니다."}, {"text": "사업부18의 영업이익은 4.4억원입니다."})
    q = {"question": "11월 조직별 영업이익"}
    client.post("/v1/chat", json=q)
    assert c.calls == 2                                           # SQL 1회 + 문장 1회
    r2 = client.post("/v1/chat", json=q).json()
    assert c.calls == 3 and r2["ai"]["cache_hits"] == 1 and r2["sql_source"] == "cache"      # 두 번째는 SQL 호출 없이 문장 1회만


def test_ai_clarify_and_unknown_from_model(ai):
    seed()
    ai({"sql": None, "clarify": {"question": "어느 시나리오인가요?", "options": ["실적", "계획"]}, "unknown": None})
    r = client.post("/v1/chat", json={"question": "영업이익 보여줘"}).json()
    assert r["status"] == "clarify" and r["clarify"]["options"] == ["실적", "계획"] and r["sql"] is None
    ai({"sql": None, "clarify": None, "unknown": "그 지표는 데이터에 없습니다."})
    r = client.post("/v1/chat", json={"question": "시장점유율 알려줘"}).json()
    assert r["status"] == "unknown" and "데이터에 없습니다" in r["answer_text"]


# ------------------------------------------------------------------ AI 없이 기본 질의
def test_no_key_falls_back_to_quick_query(monkeypatch):
    seed()
    monkeypatch.delenv("OPENAI_API_KEY", raising=False)
    r = client.post("/v1/chat", json={"question": "조직별 영업이익 보여줘"}).json()                 # make_client 금지 → AI 를 부르면 실패
    assert r["status"] == "ok" and r["sql_source"] == "quick" and r["notice"] == "AI 없이 기본 질의" and r["ai"]["calls"] == 0
    assert "API 키가 없음" in r["ai"]["reasons"] and r["answer_source"] == "template" and r["row_count"] == 2
    r2 = client.post("/v1/chat", json={"question": "시장 분위기가 어때"}).json()
    assert r2["status"] == "clarify" and r2["clarify"]["slot"] == "quick" and len(r2["clarify"]["options"]) == 5


def test_daily_limit_uses_quick_query(monkeypatch, ai):
    seed()
    monkeypatch.setenv("AI_DAILY_CALL_LIMIT", "0")
    c = ai({"sql": GOOD_SQL})
    r = client.post("/v1/chat", json={"question": "Gap 목록 보여줘"}).json()
    assert c.calls == 0 and r["sql_source"] == "quick" and "하루 호출 상한을 넘음" in r["ai"]["reasons"] and r["row_count"] == 1


def test_quick_id_runs_fixed_sql_for_org_without_ai():
    seed()
    r = client.post("/v1/chat", json={"question": "사업부18 소명 현황", "quick_id": "expl_status"}).json()
    assert r["status"] == "ok" and r["row_count"] == 1 and "org_code = :org0" in r["sql"] and r["params"]["org0"] == "F901" and r["notice"] is None
    cols = r["columns"]
    row = dict(zip(cols, r["rows_preview"][0]))
    assert row["explanation_id"] == "EXP-18-020" and row["answer"] == "신규 B2B 계약 근거를 보완했습니다."
    assert any(e["type"] == "explanation" and e["ref"] == "EXP-18-020" for e in r["evidence"])


def test_unaccepted_explanation_answer_is_null_in_db():
    seed()
    con = db.open_readonly()
    row = con.execute("SELECT answer FROM explanation WHERE explanation_id='EXP-X'").fetchone()
    assert row["answer"] is None
    con.close()


# ------------------------------------------------------------------ 버전(시점) 필터
def test_version_filter_and_default_latest(ai):
    seed()
    st = client.get("/v1/chat/status").json()
    assert st["db_ready"] and st["default_version"] == "master-2026-11-01" and st["default_basis"] == "master-2026-11-01 (확정)" and [v["version"] for v in st["versions"]] == ["close-2026-10", "master-2026-11-01"]
    r = client.post("/v1/chat", json={"question": "조직별 영업이익", "quick_id": "org_op"}).json()
    assert r["version"] == "master-2026-11-01" and r["row_count"] == 2                  # 기본은 최신 버전
    r = client.post("/v1/chat", json={"question": "10월 마감 기준 조직별 영업이익", "quick_id": "org_op"}).json()
    assert r["version"] == "close-2026-10" and r["row_count"] == 1 and r["rows_preview"][0] == ["사업부1", 700_000_000, 400_000_000, 300_000_000]
    assert r["params"]["version_prev"] == "close-2026-10" or r["params"]["version"] == "close-2026-10"


# ------------------------------------------------------------------ 엑셀 숫자 == SQL 결과
def test_excel_numbers_equal_sql_result_and_has_formula_chart(ai):
    seed()
    r = client.post("/v1/chat", json={"question": "조직별 영업이익", "quick_id": "org_op"}).json()
    f = client.get(r["file"]["url"])
    assert f.status_code == 200 and "attachment" in f.headers["content-disposition"]
    path = db.files_dir() / f"{r['file']['id']}.xlsx"
    wb = openpyxl.load_workbook(path)
    assert wb.sheetnames == ["요약", "결과", "근거"]
    ws = wb["결과"]
    header = [c.value for c in ws[1]]
    assert header == ["org_name", "revenue_won", "revenue_억원", "cost_won", "cost_억원", "op_won", "op_억원"]
    con = db.open_readonly()
    rows = con.execute("SELECT org_name, revenue_won, cost_won, op_won FROM v_op WHERE version='master-2026-11-01' AND year=2026 AND month=11 AND scenario='ACTUAL' ORDER BY op_won DESC").fetchall()
    for i, row in enumerate(rows, start=2):
        assert [ws.cell(i, c).value for c in (1, 2, 4, 6)] == list(tuple(row))                   # 정수 원 단위가 그대로
        assert ws.cell(i, 3).value == f"=B{i}/100000000" and ws.cell(i, 7).value == f"=F{i}/100000000"
    assert ws.freeze_panes == "A2" and ws.auto_filter.ref and len(ws._charts) == 1
    assert "SELECT" in wb["근거"]["B1"].value and wb["요약"]["B2"].value == "master-2026-11-01 (확정)"
    con.close()


def test_file_endpoint_rejects_bad_id():
    assert client.get("/v1/chat/files/../../etc").status_code in (404, 422)
    assert client.get("/v1/chat/files/0123456789abcdef").status_code == 404


# ------------------------------------------------------------------ 적재기




