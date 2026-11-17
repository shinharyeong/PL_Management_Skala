"""POST /v1/pipeline/run, GET /v1/pipeline/runs/{id} 테스트. 모의 업로드(가짜 xlsx)만 쓰고 실제 OpenAI·실제 파싱은 하지 않는다."""
import io
import json
import zipfile

import pytest
from fastapi.testclient import TestClient

from app.main import app
from app.pipeline import parse_stage
from app.services import pipeline_run
from tests.conftest import FAKE_KEY

client = TestClient(app)

ORGS = {f"F{i}": f"{i:02d}_사업부{i}" for i in range(10, 30)}          # 조직 20개(가짜 코드)
REVISION_ORGS = ["F11", "F12", "F13"]                                  # 재제출 3개 → 20*3 + 3 = 63


def fake_xlsx() -> bytes:
    b = io.BytesIO()
    with zipfile.ZipFile(b, "w") as z:
        z.writestr("[Content_Types].xml", "<Types/>")
        z.writestr("xl/workbook.xml", "<workbook/>")
    return b.getvalue()


def final_rel(org):
    folder = "revisions" if org in REVISION_ORGS else "submission_2026_11"
    return f"01_excel/{folder}/{ORGS[org]}/{org}_2026-11.xlsx"


def make_files(skip=(), extra=()):
    X = fake_xlsx()
    files = []
    for org, d in ORGS.items():
        files += [(f"01_excel/annual_plan/{d}/{org}_2026-PLAN.xlsx", X), (f"01_excel/baseline_2026_10/{d}/{org}_2026-10.xlsx", X),
                  (f"01_excel/submission_2026_11/{d}/{org}_2026-11.xlsx", X)]
    files += [(f"01_excel/revisions/{ORGS[o]}/{o}_2026-11.xlsx", X) for o in REVISION_ORGS]
    approvals = {"all_approved": {"sources": [{"organization_code": o, "file": final_rel(o), "submission_id": f"{o}-2026-11-01"} for o in ORGS]},
                 "before_last_approval": {"sources": []}, "superseded_files": [], "excluded_files": []}
    files += [("02_scenarios/explanations.json", b"[]"), ("02_scenarios/approval_snapshots.json", json.dumps(approvals).encode("utf-8"))]
    files = [f for f in files if f[0] not in skip]
    return files + list(extra)


def post(files, **data):
    mp = [("files", (n, b, "application/octet-stream")) for n, b in files]
    return client.post("/v1/pipeline/run", files=mp, data=data)


@pytest.fixture
def stub_execute(monkeypatch):
    """무거운 파싱·계산은 모의로 바꾸고 호출 인자만 확인한다."""
    calls = []

    def fake(run_id, upload_report, s, data_dir, out_dir, ai, timings=None):
        calls.append({"ai": ai, "data_dir": data_dir, "xlsx": len(list((data_dir / "01_excel").rglob("*.xlsx")))})
        ok = {"status": "OK", "message": "모의"}
        return {"parse": ok, "gaps": ok, "master": ok, "report": ok}
    monkeypatch.setattr(pipeline_run, "execute", fake)
    return calls


@pytest.fixture
def no_parse(monkeypatch):
    def boom(*a, **k):
        raise AssertionError("업로드가 실패했는데 파싱이 호출되었습니다")
    monkeypatch.setattr(parse_stage, "parse_all", boom)


def test_normal_63_files(stub_execute, isolated):
    files = make_files()
    assert len([f for f in files if f[0].endswith(".xlsx")]) == 63
    r = post(files)
    assert r.status_code == 200
    body = r.json()
    up = body["stages"]["upload"]
    assert up["status"] == "WARN" and "시나리오 JSON" in up["message"]          # 시나리오 JSON 이 없으면 경고(실행은 가능)
    assert up["violations"] == [] and up["missing"] == []
    assert up["counts"]["xlsx"] == 63 and up["counts"]["orgs"] == 20
    roles = {f["name"]: f["role"] for f in up["files"]}
    assert roles["01_excel/annual_plan/10_사업부10/F10_2026-PLAN.xlsx"] == "PLAN"
    assert roles["01_excel/revisions/11_사업부11/F11_2026-11.xlsx"] == "REVISION"
    assert body["overall"] == "OK" and stub_execute[0]["ai"] is False and stub_execute[0]["xlsx"] == 63   # ai 기본 false
    # 저장 위치: <AI_CACHE_DIR>/uploads/<run_id>/data/..., 응답에 키 없음
    saved = isolated / "cache" / "uploads" / body["run_id"] / "data" / "01_excel" / "submission_2026_11" / "10_사업부10" / "F10_2026-11.xlsx"
    assert saved.exists()
    assert FAKE_KEY not in r.text
    again = client.get(f"/v1/pipeline/runs/{body['run_id']}")                      # 저장된 결과 재조회
    assert again.status_code == 200 and again.json()["run_id"] == body["run_id"] and again.json()["stages"]["upload"]["counts"]["xlsx"] == 63


def test_ai_flag_is_passed_only_when_true(stub_execute):
    post(make_files(), ai="true")
    assert stub_execute[-1]["ai"] is True


def test_missing_file_fails_upload_and_skips_next_stages(no_parse):
    skip = ("01_excel/annual_plan/15_사업부15/F15_2026-PLAN.xlsx",)
    body = post(make_files(skip=skip)).json()
    up = body["stages"]["upload"]
    assert up["status"] == "FAIL" and any(m["code"] == "MISSING_FILE" and m["org_code"] == "F15" and m["role"] == "PLAN" for m in up["missing"])
    assert "빠진 파일" in up["message"]
    for st in ("parse", "gaps", "master", "report"):
        assert body["stages"][st]["status"] == "SKIPPED"
    assert body["overall"] == "FAIL"


def test_disallowed_files_are_rejected(no_parse, isolated):
    extra = [("tools/evil.exe", b"MZ"), ("notes.txt", b"hello"), ("01_excel/annual_plan/10_사업부10/F10_2026-PLAN.csv", b"a,b")]
    body = post(make_files(extra=extra)).json()
    codes = {v["name"]: v["code"] for v in body["stages"]["upload"]["violations"]}
    assert codes["tools/evil.exe"] == "EXT_NOT_ALLOWED" and codes["notes.txt"] == "EXT_NOT_ALLOWED"
    assert body["stages"]["upload"]["status"] == "FAIL"
    root = isolated / "cache" / "uploads" / body["run_id"] / "data"
    assert not list(root.rglob("evil.exe")) and not list(root.rglob("notes.txt"))


def test_fake_xlsx_and_bad_names_are_rejected(no_parse):
    extra = [("01_excel/annual_plan/10_사업부10/F99_2026-PLAN.xlsx", b"not a zip"), ("01_excel/baseline_2026_10/10_사업부10/hello.xlsx", fake_xlsx()),
             ("F10_2026-11.xlsx", fake_xlsx()), ("01_excel/annual_plan/10_사업부10/F10_2026-11.xlsx", fake_xlsx())]
    v = {x["name"]: x["code"] for x in post(make_files(extra=extra)).json()["stages"]["upload"]["violations"]}
    assert v["01_excel/annual_plan/10_사업부10/F99_2026-PLAN.xlsx"] == "NOT_XLSX"
    assert v["01_excel/baseline_2026_10/10_사업부10/hello.xlsx"] == "BAD_FILENAME"
    assert v["F10_2026-11.xlsx"] == "NO_FOLDER"
    assert v["01_excel/annual_plan/10_사업부10/F10_2026-11.xlsx"] == "NAME_FOLDER_MISMATCH"


def test_zip_slip_is_blocked(no_parse, isolated):
    b = io.BytesIO()
    with zipfile.ZipFile(b, "w") as z:
        z.writestr("../../evil.xlsx", fake_xlsx())
        z.writestr("/abs/evil2.xlsx", fake_xlsx())
        z.writestr("ok/readme.json", b"{}")
    extra = [("bundle.zip", b.getvalue()), ("../outside.xlsx", fake_xlsx()), ("a/../../b.xlsx", fake_xlsx())]
    body = post(make_files(extra=extra)).json()
    v = {x["name"]: x["code"] for x in body["stages"]["upload"]["violations"]}
    assert v["bundle.zip!../../evil.xlsx"] == "ZIP_SLIP" and v["bundle.zip!/abs/evil2.xlsx"] == "ZIP_SLIP"
    assert v["../outside.xlsx"] == "PATH_TRAVERSAL" and v["a/../../b.xlsx"] == "PATH_TRAVERSAL"
    assert not list(isolated.rglob("evil.xlsx")) and not list(isolated.rglob("evil2.xlsx")) and not list(isolated.rglob("outside.xlsx"))   # 어디에도 쓰이지 않음


def test_zip_with_valid_members_is_accepted(stub_execute):
    files = make_files()
    z = io.BytesIO()
    with zipfile.ZipFile(z, "w") as zf:
        for n, b in files:
            zf.writestr(n, b)
    body = post([("everything.zip", z.getvalue())]).json()
    assert body["stages"]["upload"]["status"] in ("OK", "WARN") and body["stages"]["upload"]["counts"]["xlsx"] == 63


def test_explanations_missing(no_parse):
    body = post(make_files(skip=("02_scenarios/explanations.json",))).json()
    up = body["stages"]["upload"]
    assert up["status"] == "FAIL" and any(m["code"] == "NO_EXPLANATIONS" for m in up["missing"])
    assert "소명 파일" in up["message"] or any("소명 파일" in m["message"] for m in up["missing"])


def test_limits_come_from_settings(monkeypatch, no_parse):
    monkeypatch.setenv("AI_UPLOAD_MAX_FILES", "5")
    body = post(make_files()).json()
    assert any(v["code"] == "TOO_MANY_FILES" for v in body["stages"]["upload"]["violations"]) and body["stages"]["upload"]["counts"]["received"] == 5
    monkeypatch.setenv("AI_UPLOAD_MAX_FILES", "150")
    monkeypatch.setenv("AI_UPLOAD_MAX_FILE_MB", "0")
    body = post(make_files()).json()
    assert any(v["code"] == "TOO_LARGE" for v in body["stages"]["upload"]["violations"])


def test_keep_files_option(stub_execute, isolated):
    kept = post(make_files()).json()["run_id"]
    assert (isolated / "cache" / "uploads" / kept).exists()                       # 기본은 보존
    gone = post(make_files(), keep_files="false").json()["run_id"]
    assert not (isolated / "cache" / "uploads" / gone).exists()                   # 정리 옵션
    assert client.get(f"/v1/pipeline/runs/{gone}").status_code == 200              # 결과는 남음


def test_unknown_or_unsafe_run_id():
    assert client.get("/v1/pipeline/runs/20260101-000000-abcdef").status_code == 404
    assert client.get("/v1/pipeline/runs/..%2F..%2Fetc").status_code == 404


def test_cors_allows_only_localhost_and_null():
    ok = client.get("/health", headers={"Origin": "null"})
    assert ok.headers.get("access-control-allow-origin") == "null"
    assert client.get("/health", headers={"Origin": "http://localhost:5500"}).headers.get("access-control-allow-origin") == "http://localhost:5500"
    assert "access-control-allow-origin" not in client.get("/health", headers={"Origin": "https://evil.example"}).headers


def test_pipeline_failure_is_reported_per_stage(monkeypatch):
    """파싱이 실패하면 parse 단계 FAIL + 쉬운 말 메시지, 이후 단계는 SKIPPED."""
    def boom(*a, **k):
        raise ValueError("손상된 파일")
    monkeypatch.setattr(parse_stage, "parse_all", boom)
    body = post(make_files()).json()
    assert body["stages"]["upload"]["status"] in ("OK", "WARN") and body["stages"]["parse"]["status"] == "FAIL"
    assert "엑셀을 읽는 중 오류" in body["stages"]["parse"]["message"]
    assert [body["stages"][n]["status"] for n in ("gaps", "master", "report")] == ["SKIPPED"] * 3


def test_excel_lock_file_is_ignored_not_rejected(stub_execute):
    body = post(make_files(extra=[("01_excel/annual_plan/10_사업부10/~$F10_2026-PLAN.xlsx", b"x")])).json()
    assert body["stages"]["upload"]["violations"] == [] and body["stages"]["upload"]["status"] in ("OK", "WARN")
