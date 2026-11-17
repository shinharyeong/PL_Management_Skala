"""GET /v1/report/latest — 프론트 보고서 화면용 요약. 숫자는 보고서 값 그대로, 서식(콤마·부호)만 입힌다."""
import json

from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)


def metric(name, ly, lm, tm, d1, p1, d2, p2):
    mk = lambda v: {"value_eok": v}
    dd = lambda d, p: {"diff_eok": d, "percent": p, "direction": "▼" if d.startswith("-") else "▲"}
    return {"metric": name, "last_year_same_month": mk(ly), "last_month": mk(lm), "this_month": mk(tm), "vs_last_month": dd(d1, p1), "vs_last_year": dd(d2, p2)}


def data():
    rep = {"data_status": "확정", "master_version": "m-1", "base_month": "2027-03", "unit": "억원", "scope": {"orgs": 3, "excluded_orgs": []},
           "summary_table": {"subtitle": "2027년 3월 · 전사(3개 조직) · 억원", "rows": [metric("매출", "1200.5", "1300.0", "1400.2", "100.2", "7.7", "-199.7", "-14.3")]},
           "op_bridge": {"unit": "억원", "total_delta_eok": "-1.0", "total_delta_percent": "-0.1",
                         "steps": [{"label": "연간 계획", "type": "total", "value_eok": "1728.7"}, {"label": "조직A", "type": "delta", "delta_eok": "-0.8"}, {"label": "최신 추정", "type": "total", "value_eok": "1727.7"}]},
           "explanation_inline": {"pending_count": 0, "items": [{"status": "수락", "text": "작은 건", "included_in_scope": True, "observed_diff_won": "10", "item": "항목1", "org_name": "조직A"},
                                                                {"status": "수락", "text": "큰 건", "included_in_scope": True, "observed_diff_won": "-500", "item": "항목2", "org_name": "조직B"}]}}
    return {"report": {"final": rep}, "keyMessages": {"final": {"sentences": [{"text": "문장1"}, {"text": "문장2"}]}}, "master": {"final": {"version": "m-1", "approval_label": "승인 3/3 조직"}},
            "gapCounts": {"gaps_total": 2, "item_revision_gaps": 1, "op_gaps": 1, "gaps_without_explanation": 0, "explanation_keys": 2},
            "records": [{"org_name": "조직A", "item": "항목1", "ym": "2027-03", "gclass": "항목 수정", "diff_eok": "0.1", "diff_basis": "계획 대비", "st_final": ["수락"], "dfr": ["이연"], "latest_answer": "답", "resub": False}]}


def write_run(root, run_id, overall="OK", with_data=True):
    d = root / "runs" / run_id
    d.mkdir(parents=True)
    (d / "result.json").write_text(json.dumps({"run_id": run_id, "created_at": "2027-03-02T00:00:00", "overall": overall,
                                               "stages": {"report": {"status": "OK", "data": data() if with_data else None}}}, ensure_ascii=False), encoding="utf-8")


def test_404_when_no_completed_run():
    assert client.get("/v1/report/latest").status_code == 404


def test_returns_latest_completed_run_with_formatted_values(isolated):
    root = isolated / "cache"
    write_run(root, "20270301-000000-aaaaaa")
    write_run(root, "20270302-000000-bbbbbb")
    write_run(root, "20270303-000000-cccccc", overall="FAIL")                       # 실패한 실행은 건너뛴다
    r = client.get("/v1/report/latest").json()
    assert r["run_id"] == "20270302-000000-bbbbbb" and r["master_version"] == "m-1" and r["data_status"] == "확정" and r["base_month"] == "2027-03"
    assert r["summary"]["rows"] == [["매출", "1,200.5", "1,300.0", "1,400.2", "▲100.2", "+7.7%", "▼199.7", "−14.3%"]]
    assert [s["value"] for s in r["bridge"]["steps"]] == ["1,728.7", "−0.8", "1,727.7"]
    assert r["bridge"]["caption"] == "연간 계획 1,728.7 → 최신 추정 1,727.7 (−1.0, −0.1%) · 억원"
    assert r["gap_counts"]["gaps_total"] == 2 and r["explanations"][0]["dfr"] == "이연" and r["explanations"][0]["answer"] == "답"
    assert r["message"] == ["문장1", "문장2"] and r["weekly"] is None and r["banner"] is None
    assert r["reason"] == {"title": "▼ 항목2 소명 · [조직B]", "cause": "큰 건"}      # 변동이 가장 큰 수락 소명 하나


def test_ui_pages_render_run_data_inside_the_team_app(isolated):
    """어제 만든 보고서·Gap 화면을 서버가 실행 결과로 그려 준다(팀 앱 iframe 용). 상세 데이터가 없으면 안내 페이지."""
    assert client.get("/ui/report").status_code == 404
    root = isolated / "cache"
    write_run(root, "20270301-000000-aaaaaa", with_data=False)
    d = root / "runs" / "20270301-000000-aaaaaa"
    res = json.loads((d / "result.json").read_text(encoding="utf-8"))
    res["stages"]["report"]["screen"] = {"base_month": "2027-03", "scope": "전사(3개 조직)", "warning_total": 2, "summary": {"caption": "", "rows": []}, "state": "final", "data_status": "일반 모드",
                                         "master_version": "close-2027-03", "message": [], "bridge": None, "gap_counts": {}, "explanations": [], "reason": None, "weekly": None, "banner": None}
    (d / "result.json").write_text(json.dumps(res, ensure_ascii=False), encoding="utf-8")
    page = client.get("/ui/report?run_id=20270301-000000-aaaaaa")
    assert page.status_code == 200 and "일반 모드 실행" in page.text and "window.DATA" not in page.text
    from app.services import pipeline_run
    full = {"report": {"final": {"x": 1}, "provisional": {"x": 2}}, "keyMessages": {}, "narr": {}, "records": [], "gaps": [], "gapCounts": {}, "orgNames": {}, "master": {}, "submissions": {}, "basis": {}, "changes": {}, "ai": {}}
    write_run(root, "20270302-000000-bbbbbb")
    res = json.loads((root / "runs" / "20270302-000000-bbbbbb" / "result.json").read_text(encoding="utf-8"))
    res["stages"]["report"]["data"] = full
    (root / "runs" / "20270302-000000-bbbbbb" / "result.json").write_text(json.dumps(res, ensure_ascii=False), encoding="utf-8")
    for path, marker in (("/ui/report", "보고서 자료 생성"), ("/ui/gap", "Gap")):
        r = client.get(path + "?run_id=20270302-000000-bbbbbb")
        assert r.status_code == 200 and "window.DATA=" in r.text and marker in r.text and "임시 시안" not in r.text and "저장소 제외" not in r.text
    assert 'data-nav="gap"' in client.get("/ui/report").text and 'class="side"' not in client.get("/ui/report").text
