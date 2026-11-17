"""답안지 마스터 표·AI 사용 요약·파싱 결과 재사용 테스트(가짜 데이터, 실제 파싱·OpenAI 없음)."""
from decimal import Decimal as D

from app.config import get_settings
from app.pipeline import master_stage, parse_stage
from app.services import report_view


def _sheet_all():
    """매출(1단계) = 기본료 + 기타(2단계), 영업비용, 영업이익. 1~11월 실적, 12월 추정, 계획 12개월."""
    rows = [(("매출",), 10, 1), (("매출", "기본료"), 11, 2), (("매출", "기타"), 12, 2), (("영업비용",), 20, 1), (("영업이익",), 30, 1)]
    val = {("매출",): 100, ("매출", "기본료"): 60, ("매출", "기타"): 40, ("영업비용",): 70, ("영업이익",): 30}
    out = []
    for path, row, lv in rows:
        for mo in range(1, 13):
            out.append((path, "actual" if mo <= 11 else "estimate", mo, D(val[path]) * 1_0000_0000, row, f"I{row}", lv))
            out.append((path, "plan", mo, D(val[path]) * 1_0000_0000, row, f"I{row}", lv))
    return out


def _amounts(rev=100, cost=70):
    a = []
    for mo in range(1, 13):
        sc = "actual" if mo <= 11 else "estimate"
        a.append({"kind": "REVENUE", "y": 2026, "mo": mo, "sc": sc, "amount": D(rev) * 1_0000_0000})
        a.append({"kind": "COST", "y": 2026, "mo": mo, "sc": sc, "amount": D(cost) * 1_0000_0000})
    return a


def test_answer_sheet_fills_12_months_and_checks_totals():
    r = master_stage.answer_sheet({"sheet_all": _sheet_all(), "amounts": _amounts()})
    assert [x["path"] for x in r["rows"]][0] == ["매출"] and len(r["rows"]) == 5
    top = r["rows"][0]
    assert sorted(map(int, top["current"])) == list(range(1, 13)) and sorted(map(int, top["plan"])) == list(range(1, 13))   # 월 1~12 모두
    assert top["current"]["1"] == "10000000000"                                                                         # 원 단위 정수 문자열
    assert r["scenario_by_month"]["11"] == "actual" and r["scenario_by_month"]["12"] == "estimate"
    assert r["check"]["all_ok"] and r["check"]["hierarchy_mismatch_count"] == 0 and r["check"]["parents_checked"] >= 1


def test_answer_sheet_detects_total_and_hierarchy_mismatch():
    r = master_stage.answer_sheet({"sheet_all": _sheet_all(), "amounts": _amounts(rev=99)})          # 상세 합이 답안지와 다름
    assert not r["check"]["all_ok"] and r["check"]["months"][0]["revenue"]["diff"] == "100000000" and r["check"]["months"][0]["ok"] is False
    sa = [x for x in _sheet_all() if not (x[0] == ("매출", "기타") and x[2] == 5 and x[1] == "actual")]
    sa.append((("매출", "기타"), "actual", 5, D(39) * 1_0000_0000, 12, "I12", 2))                           # 자식 합 ≠ 부모
    r2 = master_stage.answer_sheet({"sheet_all": sa, "amounts": _amounts()})
    assert r2["check"]["hierarchy_mismatch_count"] == 1 and r2["check"]["hierarchy_mismatches"][0]["month"] == 5


def test_ai_summary_counts_calls_and_reasons():
    tasks = [{"state": "final", "task": "key_messages", "attempted": True, "cached": False, "ai": 5, "template": 0, "input_tokens": 300, "output_tokens": 90, "reason": None},
             {"state": "final", "task": "cards", "attempted": True, "cached": False, "ai": 0, "template": 5, "input_tokens": 0, "output_tokens": 0, "reason": "AI_CALL_FAILED:APIConnectionError"},
             {"state": "final", "task": "divisions", "attempted": False, "cached": False, "ai": 0, "template": 20, "input_tokens": 0, "output_tokens": 0, "reason": "DAILY_LIMIT_EXCEEDED"}]
    s = report_view.ai_summary(True, "gpt-4o-mini", tasks)
    assert (s["api_calls"], s["succeeded"], s["failed"], s["ai_sentences"], s["template_sentences"]) == (2, 1, 1, 5, 25)
    assert "호출 실패(연결 실패)" in s["reasons"] and "하루 호출 상한을 넘음" in s["reasons"]
    assert report_view.reason_ko("AI_CALL_FAILED:APITimeoutError") == "호출 실패(시간 초과)"
    assert report_view.reason_ko("APITimeoutError") == "호출 실패(시간 초과)"            # 접두어가 빠진 예외 이름도 실제 사유로 보인다(예전에는 '검증 실패로 교체'로 잘못 표시)
    assert report_view.reason_ko("NUMBER_NOT_IN_FACTS:12") == "문장 검증 실패로 기본문구로 교체(NUMBER_NOT_IN_FACTS)"
    off = report_view.ai_summary(False, "gpt-4o-mini", [{**tasks[2], "reason": "AI_NOT_USED"}])
    assert off["api_calls"] == 0 and off["reasons"] == ["AI 사용을 켜지 않음"]


def test_parse_reuses_results_for_same_file_content(tmp_path, monkeypatch):
    f1, f2 = tmp_path / "a" / "01_사업부1" / "F10_2026-11.xlsx", tmp_path / "b" / "01_사업부1" / "F10_2026-11.xlsx"
    for f in (f1, f2):
        f.parent.mkdir(parents=True)
        f.write_bytes(b"same-bytes")                                                   # 내용이 같으면 같은 결과를 재사용
    calls = []
    monkeypatch.setattr(parse_stage, "inventory", lambda: [{"path": f1, "folder": "submission_2026_11", "role_folder": "SUBMISSION", "rel": "01_excel/x/F10.xlsx"}])
    monkeypatch.setattr(parse_stage, "parse_core", lambda p: calls.append(p) or {"org": "F10", "org_name": "사업부1", "amounts": [], "ans": [], "sheet_all": [], "quality": {}, "timing": {"total": 1.0}})
    a = parse_stage.parse_all(use_cache=True, workers=1)
    assert len(calls) == 1 and a[0]["cached"] is False
    monkeypatch.setattr(parse_stage, "inventory", lambda: [{"path": f2, "folder": "submission_2026_11", "role_folder": "SUBMISSION", "rel": "01_excel/y/F10.xlsx"}])
    b = parse_stage.parse_all(use_cache=True, workers=1)
    assert len(calls) == 1 and b[0]["cached"] is True and b[0]["rel"] == "01_excel/y/F10.xlsx"   # 파싱 안 함, 경로 정보는 새로 붙음
    assert (get_settings().ai_cache_dir / "parse_cache").exists()
    c = parse_stage.parse_all(use_cache=False, workers=1)                               # 재사용 끄기
    assert len(calls) == 2 and c[0]["cached"] is False
