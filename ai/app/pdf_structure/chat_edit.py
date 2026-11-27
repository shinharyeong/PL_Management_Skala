"""5단계 AI 챗봇: 자연어 요청 → (OpenAI) 의도 JSON → 코드 검증 → 코드 계산.

역할 나눔
- AI : 요청을 "무슨 일인가(조회/편집/거절/모름)"와 "어떤 블록을, 어떤 지표·기간·범위·비교 기준으로, 어디에"로 JSON 에만 담아 돌려준다. 숫자·문장을 만들지 않는다.
- 코드: JSON 을 검증한다(지표가 DB 지표 사전에 있는지, 조직·월이 DB 에 있는지, 블록이 실제로 있는지, 글꼴이 허용된 것인지).
        통과하면 코드가 조회 DB 로 값을 계산해 답하거나(조회) 편집 계획을 돌려준다(편집). 실패하면 지어내지 않고 되묻거나 "데이터 없음/모름"으로 답한다.
- 숫자를 바꾸라는 요청은 AI 에 보내지도 않고 코드가 거절한다.
- 같은 요청(+같은 보고서 상태)은 캐시해서 호출을 아낀다. 키 없음·하루 한도·네트워크 실패는 이유를 돌려주고 호출하지 않는다.
"""
from __future__ import annotations

import hashlib
import json
import re
import time

from ..config import get_settings
from ..services import llm, response_cache
from ..services.call_log import CallLog
from . import data_select as D

PROMPT_VERSION = "report-chat-v2"
CHART_TYPES = ("bar", "line", "donut", "waterfall")
COMPARES = ("계획", "직전 월", "전년 동월", "직전 추정")
FONTS = {"gothic": "고딕", "serif": "명조"}
SIZE_RANGE = (8, 40)
INTENTS = ("query", "edit", "refuse", "no_data", "clarify", "unknown")
QTYPES = ("value", "org_rank", "org_plan_gap", "gap_orgs", "org_explanation", "unexplained", "gap_count", "org_gap_count", "explanation_count", "cost_top", "monthly")
OPS = ("add_block", "remove_block", "set_style", "move_block", "add_column")

SYSTEM = """당신은 보고서 편집·조회 도우미다. 사용자 요청을 아래 JSON 하나로만 돌려준다. 숫자나 문장을 직접 만들지 않는다(값은 코드가 DB 에서 계산한다).
입력 context: base(기준 월), metrics(DB 지표 사전), compare_bases, orgs(DB 조직 이름), blocks(보고서의 블록: id·종류·설명), fonts, size_range.
출력 JSON:
{"intent":"query|edit|refuse|no_data|clarify|unknown",
 "query":{"type":"value|org_rank|org_plan_gap|gap_orgs|org_explanation|unexplained|gap_count|org_gap_count|explanation_count|cost_top|monthly","metric":지표 이름|null,"org":조직 이름|null,"year":정수|null,"month":정수|null,"compare":"계획|직전 월|전년 동월|직전 추정"|null,"order":"desc|asc","limit":정수|null},
 "edits":[
   {"op":"add_block","kind":"breakdown|metric_table|chart|numbercard","metric":지표|null,"metrics":[지표],"compare":[비교 기준],"chart_type":"bar|line|donut|waterfall"|null,"limit":정수|null,"months":정수|null,"orgs":[조직 이름],"after":블록 id|null},
   {"op":"remove_block","target":{"id":블록 id|null,"kind":"title|paragraph|table|footnote|list"|null,"words":"블록을 가리키는 말"|null}},
   {"op":"set_style","target":{같은 모양},"size":정수|null,"family":"gothic|serif|other"|null,"bold":true|false|null},
   {"op":"move_block","target":{같은 모양},"direction":"up|down"},
   {"op":"add_column","target":{같은 모양},"compare":"계획|직전 월|전년 동월|직전 추정"}],
 "message":"거절·되묻기·모름일 때 사용자에게 보일 한국어 한 문장",
 "unknown_metric":DB 지표 사전에 없는 지표 이름|null}
규칙:
- 조회(질문)면 intent=query 와 query 를 채운다. 11월처럼 월만 말하면 month 만 채운다. 조직 이름은 orgs 에 있는 그대로. 지표를 말하지 않은 조직 순위는 metric=영업이익.
  value=한 지표의 값, org_rank=조직별 지표 순위(실적 값이 큰 순), org_plan_gap=조직별 계획 대비 차이 순위("계획 대비 차이가 큰 조직", "계획과 가장 다른 조직"), gap_orgs=Gap 이 걸린 조직 목록, org_explanation=한 조직의 소명 내용, unexplained=소명이 없는 변경(Gap) 항목, gap_count=Gap 전체 건수와 종류별 건수("몇 건", "종류별"), org_gap_count=조직별 Gap 건수 순위, explanation_count=소명 건수·상태별, cost_top=영업비용(또는 매출 metric) 상위 항목(limit 개, org 가 있으면 그 조직만), monthly=한 지표의 월별 추이("월별·추이·흐름"을 물을 때만; metric, org 선택; 월 단위만, 주별·일별은 no_data). 특정 월 하나의 값을 물으면(예: "11월 영업이익은?") 반드시 value 이고 month 를 채운다.
  "Gap 은 몇 건"처럼 건수를 묻는 질문은 gap_orgs 가 아니라 gap_count 로 한다.
- 편집이면 intent=edit 와 edits. "본문"은 paragraph, "제목"은 title, "표"는 table. 블록을 가리킬 때는 가능하면 blocks 의 id 로 쓴다. 블록 종류 전체에 적용하면 kind 만 쓴다.
  비용 상세·매출 상세 표 추가=add_block kind=breakdown(metric=영업비용|매출). 지표별 표 추가=add_block kind=metric_table. 그래프·차트 추가=add_block kind=chart(막대=bar, 선·추이=line(months=개월 수), 도넛=donut, 워터폴=waterfall, 조직별은 orgs 비움=전사 상위 limit개, 특정 조직만이면 orgs). 숫자 카드·KPI 카드 추가=add_block kind=numbercard(metrics, compare). 표에 비교 열 추가=add_column.
- 숫자 값을 바꾸라는 요청, 데이터를 지어내라는 요청은 intent=refuse 와 message.
- metrics 에 없는 지표(예: 인원수)를 요구하면 intent=no_data, unknown_metric 에 그 이름, message 에 "데이터 없음"이라고 쓴다. 절대 임의의 지표로 바꿔 답하지 않는다.
- fonts 에 없는 글꼴을 요구하면 set_style 의 family 를 other 로 둔다.
- 무슨 말인지 모르면 intent=unknown, 정보가 모자라면 intent=clarify 와 되묻는 message.
JSON 만 출력한다."""


class AiUnavailable(Exception):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code, self.message = code, message


_NUMBER_CHANGE = re.compile(r"(\d[\d,.]*)\s*(억|원|만원|백만원|%|퍼센트)")
_CHANGE_VERB = re.compile(r"(으로|로|를|을)?\s*(바꿔|변경|수정|고쳐|조정|맞춰|덮어)")


def refuse_number_change(text: str, metrics) -> str | None:
    """지표 값을 직접 바꾸라는 요청은 AI 에 보내지 않고 거절한다."""
    if _NUMBER_CHANGE.search(text) and _CHANGE_VERB.search(text) and any(m in text for m in metrics):
        return "숫자는 조회 DB 의 값을 코드가 계산하므로 직접 바꿀 수 없습니다. 값은 데이터를 다시 적재해서만 바뀝니다."
    return None


def _norm(t: str) -> str:
    return re.sub(r"\s+", " ", t.strip().lower())


def _call_ai(s, request: str, context: dict) -> tuple[dict, dict]:
    """보고 편집 의도를 메모리 캐시 또는 단일 LLM 호출로 구한다.

    Args:
        s: 모델·한도 설정.
        request: 사용자 편집 요청.
        context: 실제 보고서 블록·지표·조직 문맥.
    Returns:
        의도 JSON과 호출 사용량.
    Raises:
        AiUnavailable: 키·한도·모델 호출 문제.
    Side Effects:
        선택적 LLM 호출·메모리 캐시·stdout 사용량 지표를 갱신한다. 파일은 쓰지 않는다.
    """
    user = json.dumps({"request": request, "context": context}, ensure_ascii=False)
    key = hashlib.sha256(f"{s.openai_model}|{PROMPT_VERSION}|{_norm(request)}|{json.dumps(context, ensure_ascii=False, sort_keys=True)}".encode("utf-8")).hexdigest()
    cache_key = (str(s.ai_cache_dir), "report_edit", key)
    log = CallLog(s.log_dir or s.output_dir)
    meta = {"task": "report_edit", "status": "-", "model": s.openai_model, "prompt_version": PROMPT_VERSION, "facts_hash": key[:12]}
    cached = response_cache.get(cache_key)
    if cached is not None:
        log.append({**meta, "outcome": "cache_hit", "input_tokens": 0, "output_tokens": 0})
        return cached, {"calls": 0, "input_tokens": 0, "output_tokens": 0, "seconds": 0.0, "cached": True}
    if not s.key_present:
        log.append({**meta, "outcome": "skipped_no_key", "input_tokens": 0, "output_tokens": 0})
        raise AiUnavailable("NO_API_KEY", "키 없음 — ai/.env 에 OPENAI_API_KEY 를 넣고 서버를 재시작하세요.")
    if log.calls_today() >= s.ai_daily_call_limit:
        log.append({**meta, "outcome": "skipped_daily_limit", "input_tokens": 0, "output_tokens": 0})
        raise AiUnavailable("DAILY_LIMIT", f"오늘 AI 호출 한도({s.ai_daily_call_limit}회)에 도달했습니다.")
    t0 = time.perf_counter()
    try:
        reply = llm.ask_json(s, SYSTEM, user, max_tokens=700, temperature=0)
        out, tin, tout = reply.data, reply.input_tokens, reply.output_tokens
        if not isinstance(out, dict):
            raise ValueError("JSON object expected")
    except Exception as e:  # noqa: BLE001 — 오류 종류만 기록(키·응답 원문 없음)
        kind = type(e).__name__ + (f" HTTP {e.status_code}" if getattr(e, "status_code", None) else "")
        log.append({**meta, "outcome": "error", "input_tokens": 0, "output_tokens": 0, "error": kind})
        if "Connection" in kind or "Timeout" in kind:
            raise AiUnavailable("NETWORK", f"네트워크 연결 실패({kind}).") from e
        if "Authentication" in kind or "401" in kind:
            raise AiUnavailable("AUTH", f"키가 거절되었습니다({kind}).") from e
        raise AiUnavailable("CALL_FAILED", f"AI 호출 실패({kind}).") from e
    dt = time.perf_counter() - t0
    log.append({**meta, "outcome": "success", "input_tokens": tin, "output_tokens": tout})
    response_cache.put(cache_key, out)
    return out, {"calls": 1, "input_tokens": tin, "output_tokens": tout, "seconds": round(dt, 2), "cached": False}


def _resolve_target(t, blocks: list[dict]) -> list[str]:
    """AI 가 가리킨 블록(id·종류·말)을 실제 보고서의 블록 id 로. 못 찾으면 빈 목록."""
    if not isinstance(t, dict):
        return []
    ids = {b["id"] for b in blocks}
    if t.get("id") in ids:
        return [t["id"]]
    k = t.get("kind")
    if k in ("title", "paragraph", "table", "footnote", "list"):
        hit = [b["id"] for b in blocks if b["kind"] == k and (k != "title" or t.get("level") in (None, b.get("level")))]
        if hit:
            return hit
    w = (t.get("words") or "").strip()
    if w:
        toks = [x for x in re.split(r"\s+", w) if x and x not in ("표", "블록", "문장")]
        hit = [b["id"] for b in blocks if w in b["label"] or (toks and all(x in b["label"] for x in toks))]
        if hit:
            return hit[:1]
    return []


def interpret(request: str, base: dict, blocks: list[dict]) -> dict:
    """요청 한 건을 처리한다. 반환 status: answer | edit | refuse | no_data | not_found | clarify | unknown | ai_unavailable"""
    s = get_settings()
    info = D.months()
    t0 = time.perf_counter()
    usage = {"calls": 0, "input_tokens": 0, "output_tokens": 0, "seconds": 0.0, "cached": False}
    res = lambda status, message, **kw: {"status": status, "message": message, "usage": {**usage, "total_seconds": round(time.perf_counter() - t0, 2)}, **kw}
    if not info["db_ready"]:
        return res("no_data", "조회 DB 에 데이터가 없습니다.")
    metrics = [m for m in info["metrics"] if m in D.METRIC_COL]
    why = refuse_number_change(request, metrics)
    if why:
        return res("refuse", why)
    gran = re.search(r"주별|주간|일별|일간|분기별|시간별|요일별", request)
    if gran:                                                      # DB 는 월 단위만 있다: 더 잘게 쪼갠 요청은 AI 에 보내기 전에 '데이터 없음'
        return res("no_data", f"데이터 없음 — “{gran.group(0)}” 단위 값은 없습니다. 조회 DB 는 월 단위로만 적재돼 있습니다.")
    own = re.search(r"(우리|내|저희)\s*(사업부|조직|팀|부서)", request)
    if own and not D.match_org(request, info["orgs"]):             # '우리 사업부'가 어느 조직인지 DB 에 없다: 추측해서 답하지 않고 되묻는다
        return res("clarify", "어느 조직(사업부)인지 알려 주세요. 예: " + ", ".join(o["name"] for o in info["orgs"][:3]) + " …")
    if re.search(r"(지난달|전월|지난 달|이전)\S*\s*(이랑|와|과|하고)?\s*비교", request) and not any(m in request for m in metrics):
        return res("clarify", f"무엇을 비교할까요? 지표를 알려 주세요(예: {', '.join(metrics)}).")
    have = {(m["year"], m["month"]) for m in info["months"]}
    context = {"base": base, "metrics": metrics, "compare_bases": list(COMPARES), "orgs": [o["name"] for o in info["orgs"]][:60],
               "blocks": [{"id": b["id"], "kind": b["kind"], "label": b["label"]} for b in blocks],
               "fonts": list(FONTS), "size_range": list(SIZE_RANGE)}
    try:
        out, usage = _call_ai(s, request, context)
    except AiUnavailable as e:
        return res("ai_unavailable", e.message, code=e.code)
    intent = out.get("intent")
    msg = str(out.get("message") or "")
    if intent not in INTENTS:
        return res("unknown", "요청을 이해하지 못했습니다. 다시 말씀해 주세요.")
    if intent == "refuse":
        return res("refuse", msg or "이 요청은 처리하지 않습니다.")
    if intent == "no_data":
        um = out.get("unknown_metric")
        return res("no_data", f"데이터 없음 — {'“' + str(um) + '”는 ' if um else ''}조회 DB 지표 사전({', '.join(metrics)})에 없습니다.")
    if intent == "clarify":
        return res("clarify", msg or "조금 더 구체적으로 말씀해 주세요.")
    if intent == "unknown":
        return res("unknown", msg or "무슨 요청인지 알 수 없습니다(모름). 예: 영업이익 조회, 표 추가, 글꼴·크기 변경.")
    if intent == "query":
        q = dict(out.get("query") or {})
        if q.get("type") == "monthly" and not re.search(r"월별|추이|추세|매월|월간|흐름|변화|트렌드", request):     # '11월 전사 영업이익은?' 같은 한 달 질문을 월별 추이로 오해하지 않는다
            q["type"] = "value"
        return _run_query(q, base, info, metrics, have, res)
    return _plan_edits(out.get("edits"), blocks, metrics, res, request, info["orgs"])


def _run_query(q, base, info, metrics, have, res):
    typ = q.get("type")
    if typ not in QTYPES:
        return res("clarify", "무엇을 조회할지 모르겠습니다. 예: 11월 전사 영업이익, 조직별 영업이익 순위, Gap 걸린 조직, 사업부3 소명 내용.")
    b = dict(base)
    if isinstance(q.get("year"), int):
        b["year"] = q["year"]
    if isinstance(q.get("month"), int) and 1 <= q["month"] <= 12:
        b["month"] = q["month"]
    if (b["year"], b["month"]) not in have:
        avail = ", ".join("%d.%02d" % (m["year"], m["month"]) for m in info["months"])
        return res("no_data", f"데이터 없음 — {b['year']}년 {b['month']}월은 조회 DB에 없습니다(있는 월: {avail}).")
    metric = q.get("metric") or "영업이익"
    if typ in ("value", "org_rank", "org_plan_gap", "monthly") and metric not in D.METRIC_COL:
        return res("no_data", f"데이터 없음 — “{metric}”는 조회 DB 지표 사전({', '.join(metrics)})에 없습니다.")
    org = None
    if q.get("org"):
        org = D.match_org(str(q["org"]), info["orgs"])
        if org is None:
            return res("no_data", f"모름 — “{q['org']}”는 조회 DB 의 조직에 없습니다.")
    cmp_ = q.get("compare") if q.get("compare") in COMPARES else None
    if typ == "value":
        r = D.q_value(b, metric, org, cmp_)
    elif typ == "org_rank":
        r = D.q_org_rank(b, metric, "asc" if q.get("order") == "asc" else "desc", q.get("limit") if isinstance(q.get("limit"), int) and 0 < q["limit"] <= 50 else None)
    elif typ == "org_plan_gap":
        r = D.q_org_plan_gap(b, metric, q.get("limit") if isinstance(q.get("limit"), int) and 0 < q["limit"] <= 50 else None)
    elif typ == "gap_orgs":
        r = D.q_gap_orgs(b)
    elif typ == "gap_count":
        r = D.q_gap_count(b)
    elif typ == "org_gap_count":
        r = D.q_org_gap_count(b, q.get("limit") if isinstance(q.get("limit"), int) and 0 < q["limit"] <= 50 else None)
    elif typ == "explanation_count":
        r = D.q_explanation_count(b)
    elif typ == "cost_top":
        r = D.q_cost_top(b, q.get("limit") if isinstance(q.get("limit"), int) and 0 < q["limit"] <= 50 else None, org, metric if metric in ("매출", "영업비용") else "영업비용")
    elif typ == "monthly":
        r = D.q_monthly(b, metric, org)
    elif typ == "org_explanation":
        if org is None:
            return res("clarify", "어느 조직의 소명을 볼까요? 조직 이름을 알려 주세요.")
        r = D.q_org_explanation(b, org)
    else:
        r = D.q_unexplained(b)
    qspec = {"type": typ, "metric": metric if typ in ("value", "org_rank", "org_plan_gap", "monthly", "cost_top") else None, "org": org, "year": b["year"], "month": b["month"], "compare": cmp_,
             "order": "asc" if q.get("order") == "asc" else "desc", "limit": q.get("limit") if isinstance(q.get("limit"), int) and 0 < q["limit"] <= 50 else None}
    return res("answer", r["text"], table=r.get("table"), qspec=qspec)


_STOP = {"표", "블록", "문장", "제목", "본문", "각주", "목록", "빼줘", "빼", "삭제", "삭제해줘", "삭제해", "지워줘", "지워", "제거", "제거해줘", "없애줘", "없애", "해줘", "줘", "그", "이", "저", "모든", "전부", "전체", "좀", "한번", "다시"}


def _subjects(request: str) -> list[str]:
    """요청에서 '무엇을'에 해당하는 말(조사·동사 제외)을 거칠게 뽑는다. 블록 이름에 없는 말이면 AI 가 엉뚱한 블록을 골랐을 수 있다."""
    out = []
    for w in re.findall(r"[가-힣A-Za-z0-9]{2,}", request):
        if len(w) > 2 and w[-1] in "을를은는이가도만과와의에":
            w = w[:-1]
        if w not in _STOP and not re.search(r"(해줘|해주세요|줘|주세요)$", w):
            out.append(w)
    return out


def _plan_edits(edits, blocks, metrics, res, request="", info_orgs=None):
    if not isinstance(edits, list) or not edits:
        return res("clarify", "어떻게 편집할지 알려 주세요. 예: 비용 상세 표 추가, 제목 24pt, 본문 명조로.")
    plan, notes = [], []
    for e in edits:
        op = e.get("op") if isinstance(e, dict) else None
        if op not in OPS:
            return res("clarify", "편집 내용을 이해하지 못했습니다. 다시 말씀해 주세요.")
        if op == "add_block":
            kind = e.get("kind")
            if kind == "breakdown":
                mt = e.get("metric") or "영업비용"
                if mt not in ("영업비용", "매출"):
                    return res("no_data", f"데이터 없음 — “{mt}” 상세는 만들 수 없습니다(상세 표는 영업비용·매출만).")
                spec = {"type": "breakdown", "metric": mt}
                notes.append(f"블록 추가: {mt} 상세 표")
            elif kind == "metric_table":
                ms = [m for m in (e.get("metrics") or ([e["metric"]] if e.get("metric") else []))]
                bad = [m for m in ms if m not in D.METRIC_COL]
                if bad or not ms:
                    return res("no_data", f"데이터 없음 — {'“' + '”, “'.join(map(str, bad)) + '”는 ' if bad else ''}조회 DB 지표 사전({', '.join(metrics)})에 없습니다.")
                cp = [c for c in (e.get("compare") or []) if c in COMPARES]
                spec = {"type": "metric_table", "metrics": ms, "compare": cp}
                notes.append(f"블록 추가: 지표 표({', '.join(ms)}{' · ' + ', '.join(cp) + ' 대비' if cp else ''})")
            elif kind == "chart":
                mt = e.get("metric") or "영업이익"
                if mt not in D.METRIC_COL:
                    return res("no_data", f"데이터 없음 — “{mt}” 은(는) 조회 DB 지표 사전({', '.join(metrics)})에 없습니다.")
                ct = e.get("chart_type") if e.get("chart_type") in CHART_TYPES else "bar"
                org_names = [o for o in (e.get("orgs") or []) if isinstance(o, str)]
                scope, badorg = [], []
                for nm in org_names:
                    c = D.match_org(nm, info_orgs) if info_orgs else None
                    (scope if c else badorg).append(c or nm)
                if badorg:
                    return res("no_data", f"데이터 없음 — 조직 “{'”, “'.join(badorg)}” 은(는) 조회 DB 에 없습니다.")
                lim = e.get("limit") if isinstance(e.get("limit"), int) and 3 <= e["limit"] <= 30 else 10
                mn = e.get("months") if isinstance(e.get("months"), int) and 2 <= e["months"] <= 36 else None
                spec = {"type": "chart", "metric": mt, "chart_type": ct, "limit": lim, "scope": scope or None, "months_n": mn}
                kor = {"bar": "막대", "line": "선", "donut": "도넛", "waterfall": "워터폴"}[ct]
                notes.append(f"블록 추가: {mt} {kor} 차트" + (f"({', '.join(org_names)})" if org_names else ""))
            elif kind == "numbercard":
                ms = [m for m in (e.get("metrics") or ([e["metric"]] if e.get("metric") else []))]
                bad = [m for m in ms if m not in D.METRIC_COL]
                if bad or not ms:
                    return res("no_data", f"데이터 없음 — {'“' + '”, “'.join(map(str, bad)) + '”는 ' if bad else ''}조회 DB 지표 사전({', '.join(metrics)})에 없습니다.")
                cp = [c for c in (e.get("compare") or []) if c in COMPARES]
                word = {"계획": "계획比", "직전 월": "전월比", "전년 동월": "전년比", "직전 추정": "추정比"}
                spec = {"type": "numbercard", "cards": [{"label": m, "unit": "억원", "delta_label": word[cp[0]] if cp else "계획比"} for m in ms]}
                notes.append(f"블록 추가: 숫자 카드({', '.join(ms)})")
            else:
                return res("clarify", "어떤 블록을 추가할까요? 예: 비용 상세 표, 지표 표, 막대그래프, 숫자 카드.")
            after = e.get("after") if e.get("after") in {b["id"] for b in blocks} else None
            plan.append({"op": "add_block", "spec": spec, "after": after})
            continue
        ids = _resolve_target(e.get("target"), blocks)
        if not ids:
            names = ", ".join(f"{b['id']}={b['label'][:14]}" for b in blocks[:12])
            return res("not_found", f"해당 블록을 찾을 수 없습니다 — 현재 보고서에 없는 블록입니다. 있는 블록: {names}")
        if op == "remove_block":
            alltext = " ".join(b["label"] for b in blocks)
            missing = [w for w in _subjects(request) if w not in alltext]
            if missing:                                              # 보고서 어디에도 없는 말을 가리키며 지우라는 요청: AI 가 다른 블록을 골랐더라도 지우지 않는다
                return res("not_found", f"“{missing[0]}”에 해당하는 블록을 찾을 수 없습니다 — 현재 보고서에 없는 블록이라 아무것도 빼지 않았습니다.")
            if len(ids) > 1 and not re.search(r"모든|전부|전체", request):
                return res("clarify", f"해당하는 블록이 {len(ids)}개입니다. 어느 블록인지 알려 주세요(ID: {', '.join(ids)}).")
            plan.append({"op": "remove_block", "ids": ids})
            notes.append(f"블록 빼기: {', '.join(ids)}")
        elif op == "move_block":
            if e.get("direction") not in ("up", "down"):
                return res("clarify", "위로 옮길까요, 아래로 옮길까요?")
            plan.append({"op": "move_block", "ids": ids[:1], "direction": e["direction"]})
            notes.append(f"순서 {'위' if e['direction'] == 'up' else '아래'}로: {ids[0]}")
        elif op == "set_style":
            fam, size, bold = e.get("family"), e.get("size"), e.get("bold")
            if fam == "other" or (fam and fam not in FONTS):
                return res("refuse", f"지원하지 않는 글꼴입니다. 쓸 수 있는 글꼴은 {', '.join(FONTS.values())} 2종입니다.")
            if size is not None and not (isinstance(size, int) and SIZE_RANGE[0] <= size <= SIZE_RANGE[1]):
                return res("clarify", f"글자 크기는 {SIZE_RANGE[0]}~{SIZE_RANGE[1]} 사이로 알려 주세요.")
            if fam is None and size is None and bold is None:
                return res("clarify", "글꼴·크기·굵기 중 무엇을 바꿀까요?")
            patch = {k: v for k, v in (("family", fam), ("size", size), ("bold", bold)) if v is not None}
            plan.append({"op": "set_style", "ids": ids, "patch": patch})
            notes.append(f"서식 {', '.join(f'{k}={v}' for k, v in patch.items())} → {', '.join(ids)}")
        else:  # add_column
            cmp_ = e.get("compare")
            if cmp_ not in COMPARES:
                return res("clarify", f"어떤 비교 기준 열을 넣을까요? ({', '.join(COMPARES)})")
            tb = next((b for b in blocks if b["id"] == ids[0]), None)
            if not tb or tb["kind"] != "table":
                return res("clarify", "열은 표에만 추가할 수 있습니다. 어느 표인지 알려 주세요.")
            if any(cmp_ in c for c in tb.get("columns", [])):
                return res("edit", f"이미 “{cmp_}” 열이 있는 표입니다. 바꿀 것이 없습니다.", plan=[], notes=[])
            plan.append({"op": "add_column", "ids": ids[:1], "compare": cmp_})
            notes.append(f"열 추가: {ids[0]} 에 “{cmp_} 대비”")
    return res("edit", "편집안을 만들었습니다. 왼쪽에서 미리보고 적용하세요.", plan=plan, notes=notes)
