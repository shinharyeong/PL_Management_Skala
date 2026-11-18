"""보고서·Gap 화면(어제 만든 시안을 팀 앱 안에서 보여 주는 서버 렌더링 페이지).

GET /ui/report?run_id=  보고서 자료 생성(+ AI 작업실 패널)
GET /ui/gap?run_id=     Gap · 소명 검토
팀 앱(프론트)은 이 페이지를 iframe 으로 보여 준다. 실행 결과(result.json)의 보고서 데이터를 그대로 박아 넣고, 챗봇은 같은 서버의 /v1/chat 을 부른다.
표준 구조 실행만 상세 데이터(소명·이연 판정)가 있다. 일반 모드 실행은 요약만 있어 안내 페이지를 보여 준다.
"""
import html
import json
from pathlib import Path

from fastapi import APIRouter, Query
from fastapi.responses import HTMLResponse

from ..services import pipeline_run, report_view

router = APIRouter(prefix="/ui", tags=["ui"], include_in_schema=False)
WEB = Path(__file__).resolve().parents[1] / "web"


def _read(name: str) -> str:
    return (WEB / name).read_text(encoding="utf-8")


def _run_data(run_id: str | None) -> tuple[dict | None, dict | None]:
    """(상세 보고서 데이터, 실행 결과). run_id 가 없으면 가장 최근에 끝난 실행."""
    for r in pipeline_run._runs():
        if run_id and r.get("run_id") != run_id:
            continue
        rep = (r.get("stages") or {}).get("report") or {}
        if r.get("overall") == "OK" and rep.get("status") == "OK":
            return rep.get("data"), r
        if run_id:
            return None, r
    return None, None


def _page(tpl: str, js: str, data: dict, safe: bool) -> str:
    payload = json.dumps(report_view.report_safe(data) if safe else data, ensure_ascii=False, separators=(",", ":")).replace("</", "<\\/")
    page = _read(tpl)
    for key, val in {"__CSS__": _read("common.css"), "__SIDEBAR__": "", "__DATA__": payload, "__COMMON_JS__": _read("common_base.js"), "__FILTERS_JS__": _read("filters.js"),
                     "__PAGE_JS__": _read(js), "__CHAT_JS__": _read("chat.js")}.items():
        page = page.replace(key, val)
    return page


def _notice(title: str, lines: list[str]) -> str:
    body = "".join(f"<p>{html.escape(x)}</p>" for x in lines)
    return (f"<!doctype html><html lang='ko'><head><meta charset='utf-8'><title>{html.escape(title)}</title><style>{_read('common.css')}</style></head>"
            f"<body><div class='main' style='padding:40px'><h1>{html.escape(title)}</h1>{body}</div></body></html>")


def _respond(tpl: str, js: str, run_id: str | None, safe: bool) -> HTMLResponse:
    data, run = _run_data(run_id)
    if run is None:
        return HTMLResponse(_notice("보고서가 없습니다", ["완료된 실행이 없습니다. ai 서버(POST /v1/pipeline/start 또는 /run)로 먼저 실행하세요."]), status_code=404)
    if not data:
        sc = pipeline_run.latest_report(run_id=run["run_id"]) or {}
        return HTMLResponse(_notice("일반 모드 실행입니다", [
            "표준 구조(역할 폴더·파일 이름 규칙)가 아닌 엑셀은 일반 모드로 읽어 소명·이연 판정이 있는 상세 보고서가 없습니다.",
            f"기준월 {sc.get('base_month', '-')} · {sc.get('scope', '')} · 읽지 못한 항목 {sc.get('warning_total', 0)}건. 요약은 GET /v1/report/latest 로 볼 수 있습니다."]))
    return HTMLResponse(_page(tpl, js, data, safe), headers={"Cache-Control": "no-store"})


@router.get("/report")
def report_page(run_id: str | None = Query(None, max_length=64)):
    return _respond("report.html", "report.js", run_id, True)


@router.get("/gap")
def gap_page(run_id: str | None = Query(None, max_length=64)):
    return _respond("gap.html", "gap.js", run_id, False)
