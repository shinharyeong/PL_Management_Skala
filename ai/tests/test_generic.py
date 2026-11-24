"""일반 모드: 연도·월·조직 이름·코드·시트 위치·단위가 63개 데이터와 달라도 읽고, 못 읽은 것은 경고로 남긴다."""
import json

import openpyxl
import pytest
from fastapi.testclient import TestClient

from app.chat import db
from app.config import get_settings
from app.generic import build, reader
from app.main import app

client = TestClient(app)


def workbook(path, org_name, rev=100, cost=40, unit="백만원", year=27, month=6, header_rev=2, header_opx=3, sheets=("매출_상세", "비용_상세"), bad_cell=False):
    wb = openpyxl.Workbook()
    wb.remove(wb.active)
    if sheets[0]:
        ws = wb.create_sheet(sheets[0])
        ws.cell(1, 1).value = f"(단위: {unit})"
        ws.cell(header_rev, 6).value, ws.cell(header_rev, 20).value = f"'{year}년 계획", f"'{year}년 실적/추정"
        r = header_rev + 2
        ws.cell(r, 1).value, ws.cell(r, 2).value, ws.cell(r, 3).value, ws.cell(r, 4).value = org_name, "분류", "중분류", "품목"
        for m in range(1, 13):
            ws.cell(r, 6 + m).value = rev + 1          # 계획
            ws.cell(r, 20 + m).value = rev + (5 if m == month else 0)
        if bad_cell:
            ws.cell(r, 20 + 1).value = "n/a"
    if sheets[1]:
        ws = wb.create_sheet(sheets[1])
        ws.cell(1, 1).value = f"(단위: {unit})"
        ws.cell(header_opx, 6).value, ws.cell(header_opx, 20).value = f"'{year}년 계획", f"'{year}년 실적/추정"
        r = header_opx + 2
        ws.cell(r, 1).value, ws.cell(r, 2).value, ws.cell(r, 3).value, ws.cell(r, 4).value = "대분류", "중분류", "소분류", org_name
        for m in range(1, 13):
            ws.cell(r, 6 + m).value = cost
            ws.cell(r, 20 + m).value = cost
    wb.save(path)


@pytest.fixture
def folder(tmp_path):
    root = tmp_path / "up"
    d = root / "2027_06월마감"
    d.mkdir(parents=True)
    workbook(d / "K001_지사1_2027_06월마감.xlsx", "지사1", rev=100)
    workbook(d / "K002_지사2_2027_06월마감.xlsx", "지사2", rev=200, header_rev=4, header_opx=5)     # 헤더 위치가 다름
    workbook(d / "K003_지사3_2027_06월마감.xlsx", "지사3", sheets=("매출_상세", None))               # 비용 시트 없음
    workbook(d / "K001_지사1_2027_06월마감_수정본.xlsx", "지사1", rev=110)                          # 같은 조직·월 중복(수정본)
    workbook(d / "00_전체_마스터_2027_06월마감.xlsx", "전체")                                       # 조직 파일이 아님
    return root


def test_reader_finds_header_unit_and_reports_problems(tmp_path):
    workbook(tmp_path / "a.xlsx", "지사1", rev=100, unit="억원", bad_cell=True)
    r = reader.read_workbook(tmp_path / "a.xlsx", file="a.xlsx", year=2027, month=6, org_code="K001")
    assert r["stats"]["units"] == {"매출_상세": "억원", "비용_상세": "억원"}
    june = next(x for x in r["rows"] if x[3] == "매출" and x[12] == 6 and x[13] == "ACTUAL")
    assert june[14] == 105 * 100_000_000 and june[11] == 2027                      # 단위(억원)·연도를 데이터에서 읽음
    assert any(w["code"] == "NON_NUMERIC" for w in r["warnings"])
    workbook(tmp_path / "b.xlsx", "지사1", sheets=(None, "비용_상세"))
    r = reader.read_workbook(tmp_path / "b.xlsx", file="b.xlsx", year=2027, month=6, org_code="K001")
    assert [w["code"] for w in r["warnings"]] == ["NO_SHEET"] and r["rows"]            # 못 읽은 시트는 경고, 읽은 것은 유지
    workbook(tmp_path / "c.xlsx", "지사1", year=26)
    r = reader.read_workbook(tmp_path / "c.xlsx", file="c.xlsx", year=2027, month=6, org_code="K001")
    assert r["rows"] == [] and {w["code"] for w in r["warnings"]} >= {"NO_YEAR_BLOCK", "NO_VALUES"}
    (tmp_path / "d.xlsx").write_bytes(b"broken")
    assert reader.read_workbook(tmp_path / "d.xlsx", file="d.xlsx", year=2027, month=6, org_code="K001")["warnings"][0]["code"] == "UNREADABLE"


def test_plan_files_reads_period_org_picks_revision_and_skips_master(folder):
    jobs, warns = build.plan_files(folder)
    assert sorted((j[4], j[2], j[3]) for j in jobs) == [("K001", 2027, 6), ("K002", 2027, 6), ("K003", 2027, 6)]
    assert next(j for j in jobs if j[4] == "K001")[1].endswith("수정본.xlsx")                 # 수정본을 씀
    codes = sorted(w["code"] for w in warns)
    assert codes == ["DUPLICATE", "SKIPPED_MASTER"]
    assert build.parse_period("a/2027_06월마감/x.xlsx") == (2027, 6) and build.parse_period("F10_2026-11.xlsx") == (2026, 11) and build.parse_period("x.xlsx") is None


def test_generic_run_builds_report_from_data_only(folder):
    s = get_settings()
    out = build.run(folder, None, s, workers=1)
    assert out["parse"]["status"] == "WARN"
    sc = out["report"]["screen"]
    assert sc["base_month"] == "2027-06" and sc["master_version"] == "close-2027-06" and sc["scope"] == "전사(3개 조직)"
    rows = {r[0]: r for r in sc["summary"]["rows"]}
    assert rows["매출"][3] == "4.3" and rows["영업비용"][3] == "0.8"          # 백만원 단위: 매출 (115+205+105)=425백만원=4.25억, 비용 40+40=80백만원(수정본·헤더 위치·단위 모두 데이터에서)
    assert {w["code"] for w in sc["warnings"]} >= {"NO_SHEET", "DUPLICATE", "SKIPPED_MASTER"} and "일반 모드" in sc["banner"]
    con = db.open_readonly(s)
    assert {r[0] for r in con.execute("SELECT DISTINCT version FROM fact")} == {"close-2027-06"}
    assert {r[0] for r in con.execute("SELECT org_name FROM dim_org")} == {"지사1", "지사2", "지사3"}
    con.close()


def test_run_api_accepts_any_structure_and_exposes_runs_and_status(folder, isolated):
    files = [("자료/" + p.relative_to(folder).as_posix(), p.read_bytes()) for p in sorted(folder.rglob("*.xlsx"))]
    r = client.post("/v1/pipeline/run", files=[("files", (n, b)) for n, b in files]).json()
    assert r["mode"] == "generic" and r["overall"] == "OK" and r["stages"]["upload"]["status"] == "WARN"
    rep = client.get("/v1/report/latest").json()
    assert rep["run_id"] == r["run_id"] and rep["mode"] == "generic" and rep["weekly"] is None
    runs = client.get("/v1/pipeline/runs").json()["runs"]
    assert runs[0]["run_id"] == r["run_id"] and runs[0]["base_month"] == "2027-06" and runs[0]["mode"] == "generic"
    assert client.get(f"/v1/report/latest?run_id={r['run_id']}").json()["run_id"] == r["run_id"]
    assert client.get("/v1/report/latest?run_id=nope-nope-nope").status_code == 404


def test_explanations_json_is_optional_and_matched_by_org_and_month(folder):
    s = get_settings()
    exp = folder / "explanations.json"
    exp.write_text(json.dumps([{"gap_key": "K001:ITEM:2027:6:ACTUAL", "round": 1, "answer": "일회성 매출", "verdict": "ACCEPTED"}]), encoding="utf-8")
    items = build.load_explanations(exp)
    out = build.build_screen({(2027, 6): [("v", "K001", "지사1", "매출", "a", None, "i", None, None, None, "s", 2027, 6, "ACTUAL", 200_000_000),
                                          ("v", "K001", "지사1", "매출", "a", None, "i", None, None, None, "s", 2027, 6, "PLAN", 100_000_000)]},
                             (2027, 6), [], items, 1, [])
    g = out["explanations"][0]
    assert g["org_name"] == "지사1" and g["status"] == "수락" and g["answer"] == "일회성 매출" and out["gap_counts"]["gaps_without_explanation"] == 0
    none = build.build_screen({(2027, 6): [("v", "K001", "지사1", "매출", "a", None, "i", None, None, None, "s", 2027, 6, "ACTUAL", 200_000_000),
                                          ("v", "K001", "지사1", "매출", "a", None, "i", None, None, None, "s", 2027, 6, "PLAN", 100_000_000)]},
                              (2027, 6), [], [], 1, [])
    assert none["explanations"][0]["status"] == "소명 없음" and none["gap_counts"]["gaps_without_explanation"] == 1


def test_chat_db_follows_the_last_loaded_run_and_reload_restores_it(folder, tmp_path):
    s = get_settings()
    build.run(folder, None, s, workers=1)
    other = tmp_path / "other" / "2028_03월마감"
    other.mkdir(parents=True)
    workbook(other / "Z100_본부A_2028_03월마감.xlsx", "본부A", year=28, month=3)
    build.run(other.parent, None, s, workers=1)
    con = db.open_readonly(s)
    assert {r[0] for r in con.execute("SELECT version FROM dim_version")} == {"close-2028-03"}          # 이전 실행의 버전·조직이 남지 않는다
    assert {r[0] for r in con.execute("SELECT org_name FROM dim_org")} == {"본부A"}
    con.close()
    out = build.reload(folder, s, workers=1)                                                         # 실행 선택: 보관된 원본으로 다시 적재
    assert [v["version"] for v in out["versions"]] == ["close-2027-06"]
    con = db.open_readonly(s)
    assert {r[0] for r in con.execute("SELECT org_name FROM dim_org")} == {"지사1", "지사2", "지사3"}
    con.close()


def test_error_values_and_missing_cells_become_warnings_without_losing_amounts(tmp_path):
    f = tmp_path / "K009_지사9_2027_06월마감.xlsx"
    workbook(f, "#N/A", rev=100)
    wb = openpyxl.load_workbook(f)
    ws = wb["매출_상세"]
    ws.cell(4, 20 + 2).value = None             # 실적 2월만 비어 있음(같은 행의 다른 실적 월에는 값이 있음)
    ws.cell(4, 20 + 3).value = 7
    wb.save(f)
    r = reader.read_workbook(f, file=f.name, year=2027, month=6, org_code="K009")
    codes = {w["code"] for w in r["warnings"]}
    assert {"ERROR_VALUE", "MISSING_VALUE", "NO_ORG_NAME"} <= codes
    assert any(x[3] == "매출" and x[13] == "ACTUAL" and x[14] > 0 for x in r["rows"])          # 이름이 오류값이어도 금액은 읽음
    sums = build.Sums([("v", "K009", "", "매출", "a", None, "i", None, None, None, "s", 2027, 6, "ACTUAL", 5)])
    assert sums.names == {"K009": "K009"}                                                      # 이름이 없으면 코드로
