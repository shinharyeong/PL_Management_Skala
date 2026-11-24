"""PostgreSQL 로 옮긴 조회 SQL 을 직접 실행해 본다(기존 테스트가 닿지 않던 값 채우기·챗봇 보조 조회). PostgreSQL 이 없으면 건너뛴다."""
from __future__ import annotations

from decimal import Decimal

import pytest

from tests import db_support as db
from app.chat import sqlguard
from app.pdf_structure import data_select as D

BASE = {"year": 2030, "month": 6}


def _seed():
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
    con = db.open_writer()
    with con:
        db.upsert_orgs(con, [("A1", "사업부1"), ("A2", "사업부2")])
        con.execute("INSERT INTO dim_version(version, kind, label, year, month, loaded_at, source, row_count, is_latest) VALUES('m','master','확정',2030,6,'2030-07-01T00:00:00','t',0,0)")
        rows = []
        for org, nm, k in (("A1", "사업부1", 1), ("A2", "사업부2", 2)):
            for m in range(1, 13):
                for scn in ("ACTUAL", "ESTIMATE", "PLAN"):
                    if scn == "ACTUAL" and m > 6 or scn == "ESTIMATE" and m <= 6:
                        continue
                    rows.append(("m", org, nm, "매출", "대분류", "중분류", "상품", None, None, None, "s", 2030, m, scn, 1_000_000 * k * (m + 1)))
                    rows.append(("m", org, nm, "영업비용", "마케팅", None, "광고", None, None, None, "s", 2030, m, scn, 400_000 * k * (m + 1)))
        con.executemany("INSERT INTO fact VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", rows)
        con.executemany("INSERT INTO gap VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", [
            ("m", "k1-PLAN", "ITEM_GAP", "A1", "사업부1", "영업이익", 2030, 6, "ACTUAL", "PLAN", 100, 300, 200, "5.0", "ACCEPTED", "수락"),
            ("m", "k2-PLAN", "ITEM_GAP", "A2", "사업부2", "영업이익", 2030, 6, "ACTUAL", "PLAN", 500, 100, -400, "9.0", "NEEDS_REVIEW", "검토중"),
            ("m", "k3-PREVIOUS", "ITEM_GAP", "A2", "사업부2", "매출", 2030, 6, "ACTUAL", "PREVIOUS", 10, 20, 10, "1.0", "NEEDS_REVIEW", "없음")])
        con.executemany("INSERT INTO explanation VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", [
            ("m", "E1", "k1", "k1-PLAN", "A1", "사업부1", "매출", "상품", 2030, 6, "ACTUAL", "GAP", "수락", "ACCEPTED", 1, "원문입니다", "s!1"),
            ("m", "E2", "k2", "k2-PLAN", "A2", "사업부2", "매출", "상품", 2030, 6, "ACTUAL", "GAP", "검토중", "REJECTED", 1, None, "s!2")])
        db.mark_latest(con)
    con.executescript("ANALYZE;")
    con.close()


def test_every_remaining_query_runs_on_postgres_and_numbers_come_from_the_db():
    """변경된 조회·저장 경계의 회귀 동작을 검증한다.

    Args:
        없음.
    Returns:
        없음. 실패는 assert 또는 예외로 보고한다.
    Raises:
        AssertionError: 계약과 다른 결과. DB fixture 오류는 그대로 전파한다.
    Side Effects:
        모의 호출과 테스트 자료를 사용한다. PostgreSQL fixture는 승인된 환경에서만 실행한다.
    """
    _seed()
    r = D.q_org_rank(BASE, "영업이익", "desc", 2)
    assert r["table"]["rows"][0][1] == "사업부2" and "데이터 없음" not in r["text"]        # A2 가 더 크다(매출−비용)
    assert "Gap" in D.q_gap_orgs(BASE)["text"] and "2곳" in D.q_gap_orgs(BASE)["text"]
    c = D.q_gap_count(BASE)
    assert "3" in c["text"] or c.get("table")
    og = D.q_org_gap_count(BASE, 5)
    assert og["table"]["rows"][0][1] == "사업부2" and og["table"]["rows"][0][2] == "2"        # 사업부2 의 Gap 2건
    ex = D.q_explanation_count(BASE)
    assert "2건" in ex["text"] and "수락 1건" in ex["text"] and "검토중 1건" in ex["text"]
    assert D.q_cost_top(BASE, 3)["table"]["rows"] and D.q_cost_top(BASE, 3, org="A1")["table"]["rows"]
    mo = D.q_monthly(BASE, "매출")
    assert len(mo["table"]["rows"]) == 12 and mo["table"]["rows"][0][2] == "실적" and mo["table"]["rows"][-1][2] == "추정"
    assert D.q_org_explanation(BASE, "A1")["table"]["rows"][0][2] == "원문입니다"
    un = D.q_unexplained(BASE)
    assert "2건" in un["text"] and un["table"]["rows"][0][0] == "사업부2"


def test_sqlguard_rejects_postgres_catalog_schema_function_and_set_tricks_but_allows_normal_sql():
    """변경된 조회·저장 경계의 회귀 동작을 검증한다.

    Args:
        없음.
    Returns:
        없음. 실패는 assert 또는 예외로 보고한다.
    Raises:
        AssertionError: 계약과 다른 결과. DB fixture 오류는 그대로 전파한다.
    Side Effects:
        모의 호출과 테스트 자료를 사용한다. PostgreSQL fixture는 승인된 환경에서만 실행한다.
    """
    _seed()
    con = db.open_readonly()
    p = {"version": "m", "version_prev": "m", "base_year": 2030, "base_month": 6, "scn": "ACTUAL"}
    bad = {
        "SELECT * FROM pg_catalog.pg_class": "TABLE_NOT_ALLOWED",
        "SELECT * FROM information_schema.tables": "TABLE_NOT_ALLOWED",
        "SELECT pg_sleep(5) FROM fact WHERE version = :version": "TABLE_NOT_ALLOWED",
        "SELECT * FROM public.fact WHERE version = :version": "TABLE_NOT_ALLOWED",
        "SELECT * FROM generate_series(1,10)": "TABLE_NOT_ALLOWED",
        "SELECT current_setting('server_version'), COUNT(*) FROM fact WHERE version = :version": "FUNCTION_NOT_ALLOWED",
        "SELECT lo_import('/etc/passwd') FROM fact WHERE version = :version": "FUNCTION_NOT_ALLOWED",
        "SELECT * INTO x FROM fact WHERE version = :version": "FORBIDDEN_KEYWORD",
        "SELECT 1 FROM fact WHERE version = :version; SELECT 2": "MULTI_OR_SEMICOLON",
        "SELECT $$x$$ FROM fact WHERE version = :version": "FORBIDDEN_KEYWORD",
        "SELECT COUNT(*) FROM fact": "NO_VERSION_FILTER",
        "COPY fact TO '/tmp/x'": "NOT_SELECT",
        "SELECT * FROM fact WHERE version = :version FOR UPDATE": "FORBIDDEN_KEYWORD",
    }
    for sql, code in bad.items():
        with pytest.raises(sqlguard.SqlRejected) as e:
            sqlguard.execute(con, sql, p)
        assert e.value.code == code, (sql, e.value.code)
    ok = sqlguard.execute(con, "SELECT org_name, ROUND(SUM(amount_won) * 1.0 / NULLIF(COUNT(*), 0), 1) AS avg_won, STRING_AGG(DISTINCT kind, ',') AS kinds FROM fact "
                               "WHERE version = :version AND scenario = :scn AND item LIKE '%상%' GROUP BY org_name ORDER BY org_name", p)
    assert ok.row_count == 2 and ok.rows[0][0] == "사업부1" and isinstance(ok.rows[0][1], (int, Decimal))       # LIKE 의 % 와 numeric 은 그대로
    sub = sqlguard.execute(con, "SELECT x.org_code FROM (SELECT org_code FROM fact WHERE version = :version GROUP BY org_code) x ORDER BY 1", p)
    assert [r[0] for r in sub.rows] == ["A1", "A2"]                                                      # 서브쿼리 FROM (SELECT ...) 은 막지 않는다
    con.close()




def test_missing_database_is_reported_as_file_not_found_for_callers():
    from dataclasses import replace
    from app.config import get_settings
    s = replace(get_settings(), chat_database_url=None)
    with pytest.raises(db.DbUnavailable) as e:
        db.open_readonly(s)
    assert isinstance(e.value, FileNotFoundError) and "AI_DATABASE_URL" in str(e.value)
    assert D.months()["db_ready"] in (True, False)
