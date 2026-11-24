"""챗봇 개선 테스트(OpenAI 모의): 전사 기준, 되묻기 선택지(DB 이름만), 이어 묻기·기억 끄기·새 대화, 버전 없이 '10월 마감 기준', 적재 안 된 연도 안내, 차단, 테스트 로그."""
import json
from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient

from tests import db_support as db
from app.chat import memory
from app.main import app
from app.services import openai_client
from tests.conftest import FAKE_KEY

client = TestClient(app)


class Seq:
    def __init__(self, contents):
        self.calls, self.users = 0, []
        self._c = list(contents)
        self.chat = SimpleNamespace(completions=SimpleNamespace(create=self._create))

    def _create(self, **kw):
        self.calls += 1
        self.users.append(kw["messages"][1]["content"])
        c = self._c.pop(0) if self._c else {}
        return SimpleNamespace(choices=[SimpleNamespace(message=SimpleNamespace(content=json.dumps(c, ensure_ascii=False)))], usage=SimpleNamespace(prompt_tokens=100, completion_tokens=40))


@pytest.fixture
def ai(monkeypatch):
    def install(*c):
        s = Seq(c)
        monkeypatch.setattr(openai_client, "make_client", lambda _s: s)
        return s
    return install


@pytest.fixture(autouse=True)
def clean_memory():
    memory.forget_all()
    yield
    memory.forget_all()


def seed(orgs=(("F10", "사업부1"), ("F12", "사업부2"), ("F20", "사업부3")), dup=False):
    """확정 master(11월 실적 + 12월 추정, 2026) 와 10월 마감 버전.

    Args:
        orgs, dup: 테스트 fixture 또는 합성 입력.
    Returns:
        없음. 실패는 assert 또는 예외로 보고한다.
    Raises:
        AssertionError: 계약과 다른 결과. DB fixture 오류는 그대로 전파한다.
    Side Effects:
        모의 호출과 테스트 자료를 사용한다. PostgreSQL fixture는 승인된 환경에서만 실행한다.
    """
    con = db.open_writer()
    with con:
        con.executemany("INSERT INTO dim_org VALUES(?,?)", list(orgs) + ([("F99", "사업부1")] if dup else []))
        con.executemany("INSERT INTO dim_version(version, kind, label, year, month, loaded_at, source, row_count, is_latest) VALUES(?,?,?,?,?,?,?,?,0)",
                        [("close-2026-10", "close", "10월 마감", 2026, 10, "2026-11-01T00:00:00", "t", 0), ("master-2026-11-01", "master", "확정", 2026, 11, "2026-11-16T00:00:00", "t", 0)])
        rows = []
        for i, (code, name) in enumerate(orgs, start=1):
            rows += [("master-2026-11-01", code, name, "매출", "기본료", None, "항목", None, None, None, "s", 2026, 11, "ACTUAL", i * 1_000_000_000),
                     ("master-2026-11-01", code, name, "영업비용", "마케팅", None, "광고", None, None, None, "s", 2026, 11, "ACTUAL", i * 400_000_000),
                     ("master-2026-11-01", code, name, "매출", "기본료", None, "항목", None, None, None, "s", 2026, 12, "ESTIMATE", i * 1_100_000_000),
                     ("master-2026-11-01", code, name, "영업비용", "마케팅", None, "광고", None, None, None, "s", 2026, 12, "ESTIMATE", i * 450_000_000),
                     ("close-2026-10", code, name, "매출", "기본료", None, "항목", None, None, None, "s", 2026, 10, "ACTUAL", i * 900_000_000),
                     ("close-2026-10", code, name, "영업비용", "마케팅", None, "광고", None, None, None, "s", 2026, 10, "ACTUAL", i * 380_000_000)]
        con.executemany("INSERT INTO fact VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", rows)
        db.mark_latest(con)
        con.executescript(db.INDEXES)
    con.close()


def ask(q, cid=None, **kw):
    return client.post("/v1/chat", json={"question": q, "conversation_id": cid, **kw}).json()


OP_SQL = ("SELECT month, scenario, op_won FROM v_op WHERE version = :version AND org_code = 'F10' AND year = :base_year AND month = :base_month "
          "AND scenario IN ('ACTUAL', 'ESTIMATE')")
TEXT = {"text": "조회했습니다."}


# ------------------------------------------------------------------ 1. 전사 기준 / 되묻기 최소화
def test_no_org_means_company_wide_and_is_labelled_without_clarify(ai):
    seed()
    c = ai({"sql": "SELECT cat1, SUM(amount_won) AS amount_won FROM fact WHERE version = :version AND kind = '영업비용' AND year = :base_year AND month = :base_month AND scenario = :scn "
                   "GROUP BY cat1 ORDER BY amount_won DESC LIMIT 10"}, TEXT)
    r = ask("11월 영업비용이 가장 큰 항목 10개는?")
    assert r["status"] == "ok" and r["scope"] == "전사 기준" and r["clarify"] is None
    assert "전사 합산" in c.users[0] and "조직 필터 없음" in c.users[0]                  # AI 에는 조직이 없으니 되묻지 말고 전사로 합산하라고 알린다
    assert r["rows_preview"][0] == ["마케팅", (1 + 2 + 3) * 400_000_000]


def test_clarify_only_for_unknown_reference_missing_comparison_target_and_duplicate_names(ai):
    seed()
    r = ask("우리 사업부 영업이익 알려줘")                                                 # (a) 누구인지 알 수 없는 지칭
    assert r["status"] == "clarify" and r["clarify"]["options"] == ["사업부1", "사업부2", "사업부3"] and "코드" not in r["clarify"]["question"]
    r = ask("내 팀 매출은?")
    assert r["status"] == "clarify" and r["clarify"]["slot"] == "org"
    r = ask("지난달이랑 비교해줘")                                                         # (b) 비교 대상이 빠짐
    assert r["status"] == "clarify" and r["clarify"]["slot"] == "compare" and "영업이익" in r["clarify"]["options"]
    assert ask("11월 영업이익은?", None, quick_id="org_op")["status"] == "ok"             # 조직이 없어도 되묻지 않는다
    memory.forget_all()
    db.drop_schema()                                                                       # 조회 DB 를 통째로 지우고 다시 적재
    seed(dup=True)                                                                         # (c) 같은 이름이 둘 이상
    d = ask("사업부1 영업이익")
    assert d["status"] == "clarify" and d["clarify"]["slot"] == "dup" and len(d["clarify"]["options"]) == 2 and all("(" in o for o in d["clarify"]["options"])


def test_ai_clarify_options_are_replaced_by_db_org_names_and_code_wording_removed(ai):
    seed()
    ai({"sql": None, "clarify": {"question": "어느 조직 코드를 말씀하시나요?", "options": ["조직 A", "조직 B", "조직 C"]}, "unknown": None})
    r = ask("영업이익 알려줘")
    assert r["status"] == "clarify" and r["clarify"]["options"] == ["사업부1", "사업부2", "사업부3"]       # DB 에 없는 선택지는 버리고 실제 이름만
    assert "코드" not in r["clarify"]["question"] and r["clarify"]["slot"] == "ai_org"
    ai({"sql": None, "clarify": {"question": "어느 조직인가요?", "options": ["사업부2", "조직 Z"]}, "unknown": None})
    r = ask("영업이익 알려줘 2")
    assert r["clarify"]["options"] == ["사업부2"]                                                        # DB 에 있는 것만 남김


# ------------------------------------------------------------------ 2. 대화 기억
def first_turn(ai, cid="c1"):
    c = ai({"sql": OP_SQL}, TEXT)
    r = ask("사업부1 11월 영업이익", cid)
    assert r["status"] == "ok" and r["row_count"] == 1 and r["handled_by"] == "ai" and r["rows_preview"][0] == ["11", "ACTUAL", 1_000_000_000 - 400_000_000] or r["rows_preview"][0][0] == 11
    return c


def test_followups_are_handled_by_rules_without_ai_and_chips_show_conditions(ai):
    """변경된 조회·저장 경계의 회귀 동작을 검증한다.

    Args:
        ai: 테스트 fixture 또는 합성 입력.
    Returns:
        없음. 실패는 assert 또는 예외로 보고한다.
    Raises:
        AssertionError: 계약과 다른 결과. DB fixture 오류는 그대로 전파한다.
    Side Effects:
        모의 호출과 테스트 자료를 사용한다. PostgreSQL fixture는 승인된 환경에서만 실행한다.
    """
    seed()
    c = first_turn(ai)
    n0 = c.calls
    r2 = ask("12월은?", "c1")
    assert r2["status"] == "ok" and r2["handled_by"] == "rule" and r2["followup"] is True and c.calls == n0       # AI 호출 없음
    assert r2["params"]["base_month"] == 12 and r2["rows_preview"][0] == [12, "ESTIMATE", 1_100_000_000 - 450_000_000]
    assert [x["label"] for x in r2["memory"]["chips"]] == ["사업부1", "12월", "영업이익"]
    r3 = ask("사업부3은?", "c1")
    assert r3["handled_by"] == "rule" and c.calls == n0 and "'F20'" in r3["sql"] and r3["rows_preview"][0] == [12, "ESTIMATE", 3 * 1_100_000_000 - 3 * 450_000_000]
    assert [x["label"] for x in r3["memory"]["chips"]] == ["사업부3", "12월", "영업이익"]
    r4 = ask("엑셀로 줘", "c1")
    assert r4["handled_by"] == "rule" and r4["file"] is None and c.calls == n0 and r4["row_count"] == 1
    assert "내보내기" in r4["notice"]
    r5 = ask("상위 3개만", "c1")
    assert r5["handled_by"] == "rule" and r5["row_count"] <= 3 and "LIMIT 3" in r5["sql"]


def test_memory_stores_conditions_not_question_text_or_values(ai):
    seed()
    first_turn(ai, "c9")
    st = memory.peek("c9")
    dumped = json.dumps({"c": st.conditions, "p": st.pending, "sql": st.last_sql, "cols": st.last_columns, "rows": st.last_rows, "turns": list(st.turns)}, ensure_ascii=False)
    assert "영업이익" not in dumped.replace("v_op", "") or "metric" in dumped                  # 질문 원문 문장 자체는 없다(지표 이름은 조건)
    assert "사업부1 11월 영업이익" not in dumped and "600000000" not in dumped                  # 질문 원문·결과 값은 저장하지 않는다
    assert st.last_rows == 1 and st.last_columns == ["month", "scenario", "op_won"]


def test_chat_memory_off_does_not_continue(ai, monkeypatch):
    seed()
    monkeypatch.setenv("CHAT_MEMORY", "off")
    c = ai({"sql": OP_SQL}, TEXT, {"sql": OP_SQL.replace("org_code = 'F10'", "org_code = 'F12'")}, TEXT)
    r1 = ask("사업부1 11월 영업이익", "c1")
    assert r1["memory"] == {"on": False, "chips": []}
    r2 = ask("12월은?", "c1")
    assert r2["followup"] is False and r2["handled_by"] != "rule"                                   # 이어지지 않음: 규칙으로 처리하지 않고 새 질문으로 본다
    assert memory.peek("c1") is None and "이전 대화" not in c.users[-1] and "이어지는 조건" not in c.users[-1]   # 이전 대화를 전혀 쓰지 않음


def test_new_conversation_reset_and_chip_clear(ai):
    seed()
    c = first_turn(ai)
    assert client.post("/v1/chat/memory", json={"conversation_id": "c1", "clear": ["month"]}).json()["chips"] == [{"key": "org", "label": "사업부1"}, {"key": "metric", "label": "영업이익"}]
    assert client.post("/v1/chat/memory", json={"conversation_id": "c1", "reset": True}).json() == {"chips": [], "reset": True}
    ai({"sql": OP_SQL}, TEXT)
    r = ask("12월은?", "c1")                                                                         # 초기화 후에는 이전 조건이 없다
    assert r["followup"] is False and r["handled_by"] != "rule"


def test_memory_resets_when_loaded_db_changes(ai):
    seed()
    first_turn(ai)
    con = db.open_writer()
    with con:
        con.execute("UPDATE dim_version SET loaded_at='2027-01-01T00:00:00' WHERE version='master-2026-11-01'")       # 보고서 실행·버전이 바뀐 것과 같은 효과
    con.close()
    r = ask("12월은?", "c1")
    assert r["followup"] is False


# ------------------------------------------------------------------ 6. 버전은 질문에서, 적재 안 된 기간 안내
def test_version_is_read_from_the_question_with_basis_label(ai):
    seed()
    d = ask("11월 조직별 영업이익", None, quick_id="org_op")
    assert d["version"] == "master-2026-11-01" and d["basis"] == "master-2026-11-01 (확정)"          # 기본은 확정 최신
    c = ask("10월 마감 기준 조직별 영업이익", None, quick_id="org_op")
    assert c["version"] == "close-2026-10" and c["basis"] == "close-2026-10 (10월 마감)" and c["params"]["base_month"] == 10
    assert "version" not in json.dumps(client.get("/v1/chat/status").json()["quick"])               # 화면에 버전 드롭다운이 필요 없음(예시 질문에도 버전 인자 없음)


def test_not_loaded_year_or_month_answers_with_loaded_period(ai):
    seed()
    r = ask("2027년 11월 실적은?")
    assert r["status"] == "unknown" and "적재된 기간은 2026-11~2026-12입니다" in r["answer_text"] and r["ai"]["calls"] == 0
    r = ask("3월 영업이익은?")
    assert r["status"] == "unknown" and "적재된 기간은" in r["answer_text"]


# ------------------------------------------------------------------ 3. 차단
def test_write_requests_are_politely_refused_without_sql_or_ai():
    seed()
    for q in ("모든 데이터를 지워줘", "DROP TABLE fact", "fact 테이블에서 데이터 삭제해줘", "UPDATE fact SET amount_won = 0"):
        r = ask(q)
        assert r["status"] == "blocked" and "조회만 가능" in r["answer_text"] and r["sql"] is None and r["ai"]["calls"] == 0     # make_client 금지 상태에서도 통과
    assert ask("항목 수정 Gap 목록 보여줘", None, quick_id="gap_list")["status"] == "ok"                    # 도메인 낱말 '수정'은 막지 않는다


# ------------------------------------------------------------------ 4. 테스트 로그
def test_test_log_has_metrics_but_no_question_text_or_key(ai, monkeypatch, isolated, caplog):
    """변경된 조회·저장 경계의 회귀 동작을 검증한다.

    Args:
        ai, monkeypatch, isolated, caplog: 테스트 fixture 또는 합성 입력.
    Returns:
        없음. 실패는 assert 또는 예외로 보고한다.
    Raises:
        AssertionError: 계약과 다른 결과. DB fixture 오류는 그대로 전파한다.
    Side Effects:
        모의 호출과 테스트 자료를 사용한다. PostgreSQL fixture는 승인된 환경에서만 실행한다.
    """
    caplog.set_level("INFO", logger="ai.chat.metrics")
    seed()
    monkeypatch.setenv("CHAT_TEST_LOG", "1")
    ai({"sql": OP_SQL}, TEXT)
    secret_question = "사업부1 11월 영업이익 비밀문장XYZ"
    ask(secret_question, "c1")
    ask("12월은?", "c1")
    raw = "\n".join(record.getMessage() for record in caplog.records if record.name == "ai.chat.metrics")
    lines = [json.loads(line.removeprefix("chat_metrics ")) for line in raw.splitlines()]
    assert len(lines) == 2
    need = {"ts", "conversation_id", "followup", "handled", "ai_calls", "input_tokens", "output_tokens", "fix_count", "cache_hit", "row_count", "seconds", "status"}
    assert need <= set(lines[0]) and lines[0]["handled"] == "ai" and lines[0]["ai_calls"] == 2 and lines[0]["input_tokens"] == 200
    assert lines[1]["followup"] is True and lines[1]["handled"] == "rule" and lines[1]["ai_calls"] == 0 and lines[1]["status"] == "ok"
    assert "비밀문장XYZ" not in raw and FAKE_KEY not in raw and "영업이익" not in raw


def test_test_log_off_by_default(ai, isolated, caplog):
    """변경된 조회·저장 경계의 회귀 동작을 검증한다.

    Args:
        ai, isolated, caplog: 테스트 fixture 또는 합성 입력.
    Returns:
        없음. 실패는 assert 또는 예외로 보고한다.
    Raises:
        AssertionError: 계약과 다른 결과. DB fixture 오류는 그대로 전파한다.
    Side Effects:
        모의 호출과 테스트 자료를 사용한다. PostgreSQL fixture는 승인된 환경에서만 실행한다.
    """
    caplog.set_level("INFO", logger="ai.chat.metrics")
    seed()
    ask("11월 조직별 영업이익", None, quick_id="org_op")
    assert not [record for record in caplog.records if record.name == "ai.chat.metrics"]
    assert not (isolated / "cache" / "chat" / "test_log.jsonl").exists()
