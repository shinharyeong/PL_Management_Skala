"""챗봇이 테스트 데이터(2026년·11월·사업부1~20·F코드)에 굳어 있지 않은지: 조직 30개(이름 '지사N', 코드 'K0NN'), 2027년 12월 마감 합성 데이터로 같은 코드를 돌려 본다."""
import re
from pathlib import Path

import openpyxl
import pytest
from fastapi.testclient import TestClient

from app.chat import db, loader_excel, schema
from app.main import app

client = TestClient(app)
N_ORG = 30


def seed_other_shape():
    """조직 30개 · 마감 2개(2027-11, 2027-12) · 2027년. 항목 이름은 임의 값."""
    con = db.open_writer()
    with con:
        orgs = [(f"K{i:03d}", f"지사{i}") for i in range(1, N_ORG + 1)]
        con.executemany("INSERT INTO dim_org VALUES(?,?)", orgs)
        con.executemany("INSERT INTO dim_version(version, kind, label, year, month, loaded_at, source, row_count, is_latest) VALUES(?,?,?,?,?,?,?,?,0)",
                        [("close-2027-11", "close", "11월 마감", 2027, 11, "2027-12-01T00:00:00", "t", 0), ("close-2027-12", "close", "12월 마감", 2027, 12, "2028-01-02T00:00:00", "t", 0)])
        rows = []
        for ver, last_month in (("close-2027-11", 11), ("close-2027-12", 12)):
            for i, (code, name) in enumerate(orgs, start=1):
                for m in range(1, last_month + 1):
                    rows.append((ver, code, name, "매출", "대분류X", "중분류Y", "품목A", None, None, None, f"시트!{m}", 2027, m, "ACTUAL", i * 1_000_000 + m))
                    rows.append((ver, code, name, "영업비용", "대분류Z", None, "품목B", None, None, None, f"시트!{m}", 2027, m, "ACTUAL", i * 400_000 + m))
        con.executemany("INSERT INTO fact VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", rows)
        db.mark_latest(con)
        con.executescript(schema.INDEXES)
    con.close()


def ask(question, **kw):
    return client.post("/v1/chat", json={"question": question, **kw}).json()


def test_status_reads_year_month_and_orgs_from_db():
    seed_other_shape()
    st = client.get("/v1/chat/status").json()
    assert st["db_ready"] and st["orgs"] == N_ORG and st["default_version"] == "close-2027-12"
    assert (st["latest_year"], st["latest_month"]) == (2027, 12) and st["org_samples"] == ["지사1", "지사2"]


def test_default_base_month_and_year_come_from_latest_version():
    seed_other_shape()
    r = ask("조직별 영업이익", quick_id="org_op")                           # 월·연도를 말하지 않으면 최신 버전(2027-12)의 값
    assert r["status"] == "ok" and r["version"] == "close-2027-12" and r["row_count"] == N_ORG
    assert (r["params"]["base_year"], r["params"]["base_month"]) == (2027, 12)
    top = dict(zip(r["columns"], r["rows_preview"][0]))
    assert top["org_name"] == "지사30" and top["revenue_won"] == 30 * 1_000_000 + 12 and top["op_won"] == (30 * 1_000_000 + 12) - (30 * 400_000 + 12)


def test_close_version_and_year_resolution_use_db_versions():
    seed_other_shape()
    assert ask("11월 마감 기준 조직별 영업이익", quick_id="org_op")["version"] == "close-2027-11"
    r = ask("2027년 12월 마감 기준 조직별 영업이익", quick_id="org_op")
    assert r["version"] == "close-2027-12" and r["params"]["base_year"] == 2027
    u = ask("10월 마감 기준 조직별 영업이익")
    assert u["status"] == "unknown" and "close-2027-11" in u["answer_text"] and "close-2027-12" in u["answer_text"]     # 적재된 버전 목록은 DB 에서
    y = ask("2026년 12월 마감 기준 영업이익")
    assert y["status"] == "unknown" and "2027" in y["answer_text"]


def test_org_matching_uses_db_names_and_codes_with_boundaries():
    seed_other_shape()
    one = ask("지사3 소명 현황", quick_id="expl_status")
    assert one["params"]["org0"] == "K003" and "org_code = :org0" in one["sql"]                 # '지사3' 은 '지사30' 으로 오인되지 않는다
    two = ask("지사30 과 지사3 비교", quick_id="expl_status")
    assert sorted(v for k, v in two["params"].items() if k.startswith("org")) == ["K003", "K030"]
    by_code = ask("K012 소명 현황", quick_id="expl_status")
    assert by_code["params"]["org0"] == "K012"
    for q, word in (("지사31 영업이익", "지사31"), ("K031 영업이익", "K031"), ("지사99 영업이익", "지사99")):
        r = ask(q)
        assert r["status"] == "unknown" and word in r["answer_text"] and r["sql"] is None             # 이름 형식은 DB 이름에서 알아낸다


def test_clarify_lists_db_orgs_and_phrase_to_replace():
    seed_other_shape()
    r = ask("우리 지사 영업이익")
    assert r["status"] == "clarify" and len(r["clarify"]["options"]) == N_ORG and r["clarify"]["options"][0] == "지사1" and r["clarify"]["replace"] == "우리 지사"
    assert ask("우리 사업부 영업이익")["status"] == "clarify"                                           # 일반 낱말도 동작


def test_excel_loader_is_year_and_code_agnostic(tmp_path):
    def wb_for(code, year_yy, rev):
        wb = openpyxl.Workbook()
        ws = wb.active
        ws.title = loader_excel.REV_SHEET
        ws["V3"], ws["AJ3"] = f"'{year_yy - 1}년 계획", f"'{year_yy - 1}년 실적/추정"         # 이전 연도 머리(적재되면 안 됨)
        ws["AX3"], ws["BL3"] = f"'{year_yy}년 계획", f"'{year_yy}년 실적/추정"
        ws["A5"], ws["B5"], ws["C5"], ws["D5"], ws["E5"] = "지사1", "분류가", "중분류나", "소분류다", "담당자"
        for m in range(1, 13):
            ws.cell(5, 23 + m).value = 999999                       # 이전 연도 블록(무시돼야 함)
            ws.cell(5, 50 + m).value = rev + m                      # 계획 블록
            ws.cell(5, 64 + m).value = rev + 10 * m                 # 실적/추정 블록
        ws2 = wb.create_sheet(loader_excel.OPX_SHEET)
        ws2["BX4"] = f"'{year_yy}년 실적/추정"
        ws2.cell(6, 1).value, ws2.cell(6, 4).value = "비용대", "지사1"
        for m in range(1, 13):
            ws2.cell(6, 76 + m).value = 5 + m
        path = tmp_path / f"{code}_2027-12.xlsx"
        wb.save(path)
        return path
    files = [wb_for("K001", 27, 100), wb_for("K002", 27, 200)]
    assert all(loader_excel.is_org_file(f) for f in files) and loader_excel.org_code_of(files[0]) == "K001"
    t = loader_excel.load_excel(files, "close-2027-12", 2027, 12, workers=1)
    con = db.open_readonly()
    assert {r[0] for r in con.execute("SELECT DISTINCT year FROM fact")} == {2027}
    assert con.execute("SELECT COUNT(*) FROM fact WHERE amount_won=999999000").fetchone()[0] == 0           # 이전 연도 블록은 안 읽음
    assert {r[0] for r in con.execute("SELECT DISTINCT scenario FROM fact WHERE kind='매출'")} == {"ACTUAL", "PLAN"}   # 12월 마감이라 추정 없음
    assert con.execute("SELECT amount_won FROM fact WHERE org_code='K002' AND kind='매출' AND month=12 AND scenario='ACTUAL'").fetchone()[0] == (200 + 120) * 1000
    assert t["files"] == 2
    con.close()


SRC = Path(__file__).resolve().parents[1] / "app" / "chat"


def test_no_test_data_values_in_chat_code_or_prompts():
    """코드·프롬프트 예시에 테스트 데이터의 연도·월·조직 이름·코드·항목 이름이 없어야 한다(자리표시 형식만)."""
    bad = re.compile(r"(?<![0-9])(2025|2026|2027)(?![0-9])|(?<![A-Za-z0-9])F\d{2,3}(?![A-Za-z0-9])|사업부\s?\d|신사업|기타B2B|master-\d|close-\d|provisional-\d")
    hits = []
    for f in sorted(SRC.glob("*.py")):
        for n, line in enumerate(f.read_text(encoding="utf-8").splitlines(), 1):
            if bad.search(line):
                hits.append(f"{f.name}:{n}: {line.strip()[:90]}")
    assert not hits, "\n".join(hits)
    doc = schema.SCHEMA_DOC + schema.RULES + schema.EXAMPLES
    assert not bad.search(doc) and "<조직코드>" in doc
    ui = (Path(__file__).resolve().parents[1] / "preview_html" / "tpl" / "chat.js")
    if ui.exists():
        assert not re.search(r"사업부\s?\d|(?<![0-9])20\d\d(?![0-9])|\d{1,2}월 마감", ui.read_text(encoding="utf-8"))
