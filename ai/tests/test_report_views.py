"""보고서용 뷰 초안(docs/report_views.sql)을 실제 백엔드 스키마(V1)에 올려 홈과 같은 규칙으로 숫자가 나오는지 확인한다. PostgreSQL 이 없으면 건너뛴다.
외래키만 뺀 V1 스키마 + 손으로 계산한 작은 시나리오. AI 쪽 코드(조회·SQL 가드·값 채우기)가 뷰를 그대로 읽는지도 같이 본다."""
from __future__ import annotations

import os
import re
from pathlib import Path

import psycopg
import pytest

from app.chat import db, sqlguard

ROOT = Path(__file__).resolve().parents[2]
V1 = ROOT / "backend/src/main/resources/db/migration/V1__init_schema.sql"
VIEWS = Path(__file__).resolve().parents[1] / "docs" / "report_views.sql"
pytestmark = pytest.mark.skipif(not V1.exists(), reason="백엔드 마이그레이션 파일 없음")

SHA = "a" * 64


def _setup(rpt: str):
    url = os.environ.get("AI_DATABASE_URL")
    if not url:
        pytest.skip("PostgreSQL 이 없어 건너뜀")
    schema = os.environ["AI_DB_SCHEMA"]
    con = psycopg.connect(url, autocommit=True, options=f"-c search_path={schema}")
    con.execute(f'CREATE SCHEMA IF NOT EXISTS "{schema}"')
    ddl = V1.read_text(encoding="utf-8")
    ddl = re.sub(r'ALTER TABLE "[a-z_]+" ADD CONSTRAINT "[a-z_0-9]+" FOREIGN KEY.*?;', "", ddl, flags=re.S)      # 시나리오를 단순하게 넣기 위해 외래키만 뺀다(열·검사 제약은 그대로)
    con.execute(ddl)
    views = VIEWS.read_text(encoding="utf-8").replace("CREATE SCHEMA IF NOT EXISTS report;", f'CREATE SCHEMA IF NOT EXISTS "{rpt}";')
    con.execute(re.sub(r"\breport\.", f'"{rpt}".', views))
    return con


def _seed(c):
    ex = c.execute
    ex("INSERT INTO organization(id, code, name) VALUES (1,'A1','사업부1'), (2,'A2','사업부2'), (3,'A3','쉬는조직')")
    ex("UPDATE organization SET active=false WHERE id=3")
    ex("INSERT INTO annual_plan(id, year, status) VALUES (1, 2030, 'CONFIRMED')")
    for rid, typ, mon, st in ((10, "ANNUAL_PLAN", None, "CLOSED"), (5, "MONTHLY_ACTUAL", 5, "CLOSED"), (6, "MONTHLY_ACTUAL", 6, "OPEN")):
        ex("INSERT INTO submission_request(id, annual_plan_id, type, month, status, requested_by, requested_at, period_key) VALUES (%s,1,%s,%s,%s,1,now(),%s)", (rid, typ, mon, st, f"k{rid}"))
    subs = [(100, 10, 1, "APPROVED", 1), (101, 10, 2, "APPROVED", 1), (51, 5, 1, "APPROVED", 1), (52, 5, 2, "APPROVED", 1),
            (61, 6, 1, "APPROVED", 1), (63, 6, 1, "APPROVED", 2), (62, 6, 2, "PENDING", None)]
    for sid, rid, org, st, aseq in subs:
        ex("INSERT INTO submission(id, request_id, organization_id, seq, status, original_filename, source_object_key, source_sha256, uploaded_by, uploaded_at, approved_seq, reviewed_at) "
           "VALUES (%s,%s,%s,%s,%s,%s,%s,%s,1,now(),%s,now())", (sid, rid, org, 2 if sid == 63 else 1, st, f"file{sid}.xlsx", f"obj{sid}", SHA, aseq))
    for mid, seq in ((1, 1), (2, 2)):
        ex("INSERT INTO master_version(id, request_id, seq, trigger_submission_id, content_sha256, calculation_version, created_by, created_at) VALUES (%s,5,%s,51,%s,'v1',1,now())", (mid, seq, SHA))
    ex("INSERT INTO master_source(master_id, organization_id, submission_id) VALUES (1,1,51),(1,2,52),(2,1,51),(2,2,52)")
    for rid in (5, 6):
        ex("INSERT INTO request_target(request_id, organization_id, confirmed_plan_submission_id) VALUES (%s,1,100),(%s,2,101)", (rid, rid))
    items = [(1, "매출", None, "REVENUE", False), (2, "제품매출", 1, "REVENUE", True), (3, "영업비용", None, "COST", False), (4, "마케팅비용", 3, "COST", False),
             (5, "고객모집비용", 4, "COST", False), (6, "위탁수수료", 5, "COST", True), (7, "영업이익(합계)", None, "DERIVED", True)]
    for iid, name, parent, kind, leaf in items:
        ex("INSERT INTO report_item(id, code, name, parent_id, kind, is_leaf) VALUES (%s,%s,%s,%s,%s,%s)", (iid, f"c{iid}", name, parent, kind, leaf))
    line = {}
    for sid, item in [(s[0], i) for s in subs for i in (2, 6, 7)]:
        line[(sid, item)] = sid * 10 + item
        ex("INSERT INTO financial_line(id, submission_id, report_item_id, source_sheet, source_row) VALUES (%s,%s,%s,'시트',%s)", (line[(sid, item)], sid, item, item))

    def val(sid, item, year, month, scn, amt):
        ex("INSERT INTO financial_value(line_id, year, month, scenario, amount, source_record_id, source_cell) VALUES (%s,%s,%s,%s,%s,'r','C3')", (line[(sid, item)], year, month, scn, amt))
    val(51, 2, 2030, 5, "ACTUAL", 1000); val(51, 6, 2030, 5, "ACTUAL", 400); val(51, 7, 2030, 5, "ACTUAL", 600)         # 합계(DERIVED)는 빠져야 한다
    val(51, 2, 2030, 6, "ESTIMATE", 1100)
    val(51, 2, 2029, 5, "ACTUAL", 999)                                                                                 # 전년 값: 요청 연도가 아니라 빠져야 한다
    val(51, 2, 2030, 5, "PLAN", 777)                                                                                   # 월간 시트의 PLAN 은 쓰지 않는다(확정 연간 계획에서 가져온다)
    val(100, 2, 2030, 5, "PLAN", 900); val(100, 6, 2030, 5, "PLAN", 350)
    val(52, 2, 2030, 5, "ACTUAL", 2000); val(52, 6, 2030, 5, "ACTUAL", 1500)
    val(101, 2, 2030, 5, "PLAN", 1800); val(101, 6, 2030, 5, "PLAN", 1200)
    val(61, 2, 2030, 6, "ACTUAL", 5)                                                                                   # 같은 조직의 오래된 승인본: 최신 승인본(63)이 이긴다
    val(63, 2, 2030, 6, "ACTUAL", 1200); val(63, 6, 2030, 6, "ACTUAL", 500)
    val(62, 2, 2030, 6, "ACTUAL", 9999)                                                                                # 승인 전(PENDING) 제출본: 잠정에 들어가면 안 된다
    for rid, item, cur_amt, mon, scn in ((1, 2, 1000, 5, "ACTUAL"), (2, 6, 400, 5, "ACTUAL"), (3, 2, 1100, 6, "ESTIMATE")):
        ex("INSERT INTO review_item(id, submission_id, report_item_id, year, month, scenario, amount_present, current_amount, change_detected, gap_exceeded, source_fingerprint, context_fingerprint, current_round, workflow_state) "
           "VALUES (%s,51,%s,2030,%s,%s,true,%s,true,true,%s,%s,1,%s)", (rid, item, mon, scn, cur_amt, SHA, SHA, "SELECTED" if rid == 3 else "NEEDS_REVIEW"))
    def gc(rid, basis, base, cur, exceeded):
        ex("INSERT INTO gap_comparison(review_item_id, basis, baseline_submission_id, baseline_scenario, baseline_amount, current_amount, gap_amount, gap_rate, threshold_id, exceeded) VALUES (%s,%s,100,'PLAN',%s,%s,%s,%s,1,%s)",
           (rid, basis, base, cur, cur - base, round((cur - base) * 100 / abs(base), 8), exceeded))
    gc(1, "PLAN", 900, 1000, True); gc(1, "PREVIOUS", 1000, 1000, False)
    gc(2, "PLAN", 350, 400, True); gc(3, "PLAN", 900, 1000, True)
    ex("INSERT INTO review_explanation(review_item_id, round, requested_at, answer, answered_at, verdict) VALUES (1,1,now(),'수락된 원문입니다',now(),'ACCEPTED')")
    ex("INSERT INTO review_explanation(review_item_id, round, requested_at, answer, verdict) VALUES (2,1,now(),NULL,'PENDING')")
    ex("INSERT INTO review_explanation(review_item_id, round, requested_at, answer, answered_at, verdict) VALUES (3,1,now(),'다시 쓴 답',now(),'PENDING')")      # 답이 왔고 아직 판단 전 → RE_REVIEW


def _rpt():
    from dataclasses import replace
    from app.config import get_settings
    s = get_settings()
    return replace(s, chat_schema=os.environ["AI_DB_SCHEMA"] + "_rpt")


def test_views_follow_the_home_rules_on_the_real_backend_schema():
    c = _setup(os.environ.get("AI_DB_SCHEMA", "x") + "_rpt")
    _seed(c)
    s = _rpt()
    con = db.open_readonly(s)
    q = lambda sql, *a: [tuple(r) for r in con.execute(sql, a).fetchall()]
    # dim_version: 한 달에 하나, master 가 있으면 최신 master(seq 2 도 같은 내용), 없으면 잠정. 가장 최근 달이 is_latest
    assert q("SELECT version, kind, year, month, is_latest FROM dim_version ORDER BY month") == [("master-2030-05", "master", 2030, 5, 0), ("provisional-2030-06", "provisional", 2030, 6, 1)]
    assert q("SELECT org_code, org_name FROM dim_org ORDER BY 1") == [("A1", "사업부1"), ("A2", "사업부2")]                 # 쉬는 조직(active=false) 제외
    # 확정: 전년 값(999)·월간 시트의 PLAN(777)·합계(DERIVED 600)는 빠지고, 계획은 확정 연간 계획 제출본에서 온다
    assert q("SELECT org_code, scenario, revenue_won, cost_won, op_won FROM v_op WHERE version='master-2030-05' AND month=5 ORDER BY org_code, scenario") == [
        ("A1", "ACTUAL", 1000, 400, 600), ("A1", "PLAN", 900, 350, 550), ("A2", "ACTUAL", 2000, 1500, 500), ("A2", "PLAN", 1800, 1200, 600)]
    assert q("SELECT SUM(amount_won) FROM fact WHERE version='master-2030-05' AND org_code='A1' AND scenario='ESTIMATE'") == [(1100,)]
    assert q("SELECT COUNT(*) FROM fact WHERE year <> 2030") == [(0,)] and q("SELECT COUNT(*) FROM fact WHERE kind NOT IN ('매출','영업비용')") == [(0,)]
    # 잠정: 조직별 최신 승인본(63)만, 승인 전(62)·오래된 승인본(61)은 제외. A2 는 승인본이 없어 아예 없다
    assert q("SELECT org_code, revenue_won, cost_won FROM v_op WHERE version='provisional-2030-06' AND scenario='ACTUAL'") == [("A1", 1200, 500)]
    # 항목 계층: 루트(영업비용) 아래 cat1=마케팅비용, cat2=고객모집비용, item=위탁수수료. 매출은 깊이가 얕아 cat2 가 없다
    assert q("SELECT DISTINCT cat1, cat2, item FROM fact WHERE kind='영업비용' AND org_code='A1' AND version='master-2030-05'") == [("마케팅비용", "고객모집비용", "위탁수수료")]
    assert q("SELECT DISTINCT cat1, cat2, item FROM fact WHERE kind='매출' AND org_code='A1' AND version='master-2030-05'") == [("제품매출", None, "제품매출")]
    assert q("SELECT team, manager, src FROM fact WHERE org_code='A1' AND version='master-2030-05' AND item='제품매출' AND scenario='ACTUAL'") == [(None, None, "file51.xlsx / 시트!C3")]
    # gap: 임계값을 넘은 비교만, 상태는 ReviewTabs 규칙. explanation: 원문은 수락된 것만
    assert q("SELECT gap_id, basis, previous_won, current_won, diff_won, rate_percent, status, explanation_status FROM gap WHERE version='master-2030-05' ORDER BY gap_id") == [
        ("RI-1-PLAN", "PLAN", 900, 1000, 100, "11.11", "ACCEPTED", "수락"), ("RI-2-PLAN", "PLAN", 350, 400, 50, "14.29", "IN_PROGRESS", "검토중"), ("RI-3-PLAN", "PLAN", 900, 1000, 100, "11.11", "RE_REVIEW", "검토중")]
    assert q("SELECT gap_key, status, answer FROM explanation WHERE version='master-2030-05' ORDER BY gap_key") == [("RI-1", "수락", "수락된 원문입니다"), ("RI-2", "검토중", None), ("RI-3", "검토중", None)]
    ai_schema = os.environ["AI_DB_SCHEMA"] + "_ai"
    db.open_writer(schema_override=ai_schema).close()                           # AI 가 지금 쓰는 표(chat/schema.py)를 따로 만들어 컬럼 이름·순서를 그대로 비교한다
    cols = lambda sc, t: [tuple(r) for r in con.execute("SELECT column_name FROM information_schema.columns WHERE table_schema = ? AND table_name = ? ORDER BY ordinal_position", (sc, t)).fetchall()]
    for t in ("fact", "v_op", "gap", "explanation", "dim_version", "dim_org"):
        assert cols(s.chat_schema, t) == cols(ai_schema, t) and cols(ai_schema, t), t
    con.close()


def test_ai_reads_the_views_with_the_same_queries_and_a_read_only_connection():
    from app.pdf_structure import data_select as D
    c = _setup(os.environ["AI_DB_SCHEMA"] + "_rpt")
    _seed(c)
    s = _rpt()
    from app.config import use_settings
    with use_settings(chat_schema=s.chat_schema):
        info = D.months()
        assert info["db_ready"] and [m["version"] for m in info["months"]] == ["master-2030-05", "provisional-2030-06"] or {m["version"] for m in info["months"]} == {"master-2030-05", "provisional-2030-06"}
        assert {o["code"] for o in info["orgs"]} == {"A1", "A2"} and {"매출", "영업비용", "영업이익"} <= set(info["metrics"])
        r = D.q_org_rank({"year": 2030, "month": 5}, "영업이익", "desc", 5)
        assert r["table"]["rows"][0][1] == "사업부1" and "데이터 없음" not in r["text"]                        # 600 > 500
        con = db.open_readonly()
        p = {"version": "master-2030-05", "version_prev": "master-2030-05", "base_year": 2030, "base_month": 5, "scn": "ACTUAL"}
        res = sqlguard.execute(con, "SELECT org_name, revenue_won, cost_won, op_won FROM v_op WHERE version = :version AND year = :base_year AND month = :base_month AND scenario = :scn ORDER BY op_won DESC", p)
        assert [tuple(x) for x in res.rows] == [("사업부1", 1000, 400, 600), ("사업부2", 2000, 1500, 500)]            # numeric(30,6) 도 정수로 돌아온다
        assert isinstance(res.rows[0][1], int)
        with pytest.raises(psycopg.Error):
            con.execute("DELETE FROM dim_org")                                                                   # 읽기 전용 연결(뷰는 쓸 수도 없다)
        with pytest.raises(psycopg.Error):
            con.execute("SELECT * FROM organization")                                                            # 원본 표는 이 연결(search_path)에 보이지 않는다
        con.close()
