"""조직 10개 실데이터형 시연 데이터(로컬 전용). 실제 API 흐름: 업로드→검증→조직 소명→제출→기획 수락·승인.
사용: python3 seed.py [phase...]  phase = setup y2025 y2026 nov y2027  (기본: 전부). 보통은 run.sh로 실행한다.
로그인은 /dev 계정 선택(개발 도구)을 쓴다. 대상 API는 DEMO_API_BASE(기본 http://localhost:8080/api/v1).
단계마다 상태를 보고 이미 된 것은 건너뛴다."""
import json, os, sys, time, uuid, threading
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
from urllib.parse import urlencode
import urllib.request, urllib.error
import model as M, workbook as wb

BASE = os.environ.get("DEMO_API_BASE", "http://localhost:8080/api/v1")
# API로 넣은 데이터는 지울 방법이 없다. 로컬이 아닌 주소는 DEMO_ALLOW_REMOTE=1을 줄 때만 쓴다.
if not BASE.startswith(("http://localhost", "http://127.0.0.1")) and os.environ.get("DEMO_ALLOW_REMOTE") != "1":
    raise SystemExit(f"로컬이 아닌 API({BASE})에는 넣지 않습니다. 정말 넣으려면 DEMO_ALLOW_REMOTE=1을 함께 주세요.")
MARK = "시연용 합성 데이터입니다."
LOG = threading.Lock()
def log(*a):
    with LOG: print(*a, flush=True)

def call(method, path, token=None, body=None, raw=None, ctype=None, ok=(200, 201, 202, 204), tries=3):
    data = raw if raw is not None else (json.dumps(body, ensure_ascii=False).encode() if body is not None else None)
    for attempt in range(tries):
        req = urllib.request.Request(BASE + path, data=data, method=method)
        if data is not None: req.add_header("Content-Type", ctype or "application/json")
        if token: req.add_header("Authorization", "Bearer " + token)
        if method != "GET": req.add_header("Idempotency-Key", str(uuid.uuid4()))
        try:
            with urllib.request.urlopen(req, timeout=300) as r: b, st = r.read(), r.status
        except urllib.error.HTTPError as e: b, st = e.read(), e.code
        if st in ok: return json.loads(b) if b else None
        if st in (409, 500, 503) and attempt < tries - 1 and method == "GET": time.sleep(1); continue
        raise RuntimeError(f"FAIL {method} {path} -> {st} {b[:600].decode(errors='replace')}")

def login(email): return call("POST", "/dev/sessions", body={"email": email})["token"]

# ---------- 시나리오 표 ----------
ACTUAL = {  # (연, 조직, 마감월) → {항목: 계획 대비 배율}
    (2025, "F10", 2): {"로밍": 0.90}, (2025, "F37", 3): {"일반광고비": 1.12}, (2025, "F13", 3): {"솔루션/수주": 0.88},
    (2025, "F36", 5): {"통신시설수선비": 1.15}, (2025, "F10", 7): {"로밍": 1.12}, (2025, "F38", 8): {"고객센터 O/S용역비": 1.07},
    (2025, "F20", 9): {"제휴상품": 1.10}, (2025, "F12", 10): {"제휴상품": 0.90}, (2025, "F16", 12): {"솔루션/수주": 1.15}, (2025, "F37", 12): {"판매촉진비": 1.18},
    (2026, "F10", 2): {"위탁판매장려금": 1.08}, (2026, "F13", 3): {"솔루션/수주": 1.12}, (2026, "F25", 4): {"신사업/기타B2B": 0.85},
    (2026, "F36", 6): {"기지국임차료": 1.06}, (2026, "F38", 7): {"고객센터 O/S용역비": 0.93}, (2026, "F37", 9): {"일반광고비": 1.15},
    (2026, "F12", 9): {"제휴상품": 1.09}, (2026, "F39", 10): {"기타외주용역비": 1.20}, (2026, "F10", 10): {"로밍": 1.08},
    (2026, "F10", 11): {"Data통화료": 0.94, "고객기변지원금": 1.07}, (2026, "F12", 11): {"제휴상품": 1.16, "신사업/기타B2B": 0.92, "기타외주용역비": 1.08},
    (2026, "F13", 11): {"솔루션/수주": 0.87}, (2026, "F16", 11): {"Biz Data": 1.06}, (2026, "F37", 11): {"일반광고비": 1.10, "판매촉진비": 1.09},
    (2026, "F36", 11): {"통신시설수선비": 1.07}, (2026, "F38", 11): {"위탁수납수수료": 0.94}, (2026, "F39", 11): {"IT O/S용역비": 1.06},
    (2026, "F20", 11): {"제휴상품": 1.07}, (2026, "F25", 11): {"신사업/기타B2B": 0.9},
}
REVISE = {  # (연, 조직, 마감월) → {(항목, 대상월): 배율}
    (2026, "F25", 9): {("신사업/기타B2B", 10): 0.9, ("신사업/기타B2B", 11): 0.9, ("신사업/기타B2B", 12): 0.92},
    (2026, "F10", 10): {("로밍", 12): 1.10}, (2026, "F37", 10): {("일반광고비", 12): 1.12},
    (2026, "F12", 11): {("제휴상품", 12): 1.07}, (2026, "F13", 11): {("솔루션/수주", 12): 1.20},
}
RESUBMIT = {(2025, "F13", 6): {"솔루션/수주": 1.03}, (2026, "F10", 8): {"로밍": 1.02}, (2026, "F38", 10): {"고객센터 O/S용역비": 0.98}}
CAUSE = {
    "로밍": ("여름 성수기 해외 출국자가 늘어 데이터 로밍 이용이 증가했습니다.", "해외 출국 수요가 예상보다 줄어 로밍 매출이 감소했습니다."),
    "일반광고비": ("신규 단말 출시 캠페인을 앞당겨 집행했습니다. 연간 예산 안에서 다음 분기에 조정합니다.", "캠페인 일부를 다음 분기로 이연했습니다."),
    "판매촉진비": ("연말 매장 프로모션을 확대 집행했습니다.", "프로모션 일정을 다음 달로 미뤘습니다."),
    "솔루션/수주": ("분기 말 공공 SI 수주 검수가 앞당겨져 매출을 먼저 인식했습니다(일회성).", "공공 SI 프로젝트 검수가 다음 달로 밀려 매출 인식이 늦어졌습니다(일회성)."),
    "통신시설수선비": ("집중호우 피해 기지국 복구로 수선비가 늘었습니다(일회성).", "유지보수 계약 재협상으로 단가가 내려갔습니다."),
    "고객센터 O/S용역비": ("신규 요금제 출시로 상담 콜이 늘어 운영 인력을 증원했습니다.", "AI 상담 전환율이 올라 위탁 상담 인력을 줄였습니다."),
    "제휴상품": ("OTT 결합상품 신규 가입이 늘었습니다.", "제휴 OTT 요금 인상 뒤 결합 가입이 줄었습니다."),
    "위탁판매장려금": ("신규 플래그십 단말 출시로 대리점 판매장려금이 늘었습니다.", "단말 판매가 둔화되어 장려금이 줄었습니다."),
    "신사업/기타B2B": ("AI 구독 B2B 계약이 앞당겨졌습니다.", "AI 구독 상품 출시가 한 달 늦어져 매출이 계획보다 적습니다."),
    "기지국임차료": ("5G 기지국 증설 부지 임차가 늘었습니다.", "기지국 통폐합으로 임차 부지가 줄었습니다."),
    "기타외주용역비": ("차세대 시스템 컨설팅 착수로 외주비가 늘었습니다(일회성).", "외주 개발 범위를 줄였습니다."),
    "Data통화료": ("데이터 쿠폰 판매가 늘었습니다.", "무제한 요금제 전환이 늘어 종량 데이터 매출이 줄었습니다."),
    "고객기변지원금": ("신규 단말 출시로 기기변경 지원 대상이 늘었습니다.", "기기변경 수요가 줄었습니다."),
    "Biz Data": ("IoT 회선 대량 개통으로 기업 데이터 매출이 늘었습니다.", "기업 전용 데이터 계약 갱신이 늦어졌습니다."),
    "위탁수납수수료": ("수납 대행 건수가 늘었습니다.", "자동이체 전환이 늘어 수납 대행 수수료가 줄었습니다."),
    "IT O/S용역비": ("클라우드 전환 과도기로 운영 위탁비가 일시적으로 늘었습니다.", "운영 자동화로 위탁 범위를 줄였습니다."),
}

class State:
    def __init__(self, plan, actual=None, estimate=None, close=0):
        self.plan, self.close = plan, close
        self.actual = actual or {n: {} for n in M.ITEMS}
        self.estimate = estimate or {n: dict(plan[n]) for n in M.ITEMS}
    def copy(self):
        return State(self.plan, {n: dict(v) for n, v in self.actual.items()}, {n: dict(v) for n, v in self.estimate.items()}, self.close)

def advance(prev, year, code, close):
    s = prev.copy(); s.close = close
    ev = ACTUAL.get((year, code, close), {})
    for n in M.ITEMS:
        if n in ev:   # 사유가 있는 큰 차이(계획 대비 배율)
            s.actual[n][close] = round(s.plan[n][close] * ev[n])
        else:         # 전월에 적어 둔 이번 달 추정에서 조금 벗어난다
            s.actual[n][close] = round(prev.estimate[n].get(close, s.plan[n][close]) * M.noise(year, code, close, n, spread=0.008))
        s.estimate[n].pop(close, None)
        if close < 12:  # 다음 달 추정은 매달 다시 본다(계획 대비 ±1.5%)
            s.estimate[n][close + 1] = round(s.plan[n][close + 1] * M.noise(year, code, close, n, "est", spread=0.015))
    for (n, m), f in REVISE.get((year, code, close), {}).items():
        s.estimate[n][m] = round(s.plan[n][m] * f)
    return s

def resubmitted(s, year, code, close):
    t = s.copy()
    for n, f in RESUBMIT[(year, code, close)].items():
        t.actual[n][close] = round(t.plan[n][close] * f)
    return t

def workbook(code, year, s=None, plan_only=None):
    if plan_only is not None:
        periods = [(year, "계획", m) for m in range(1, 13)]
        vals = {n: {i: plan_only[n][m] for i, (_, _, m) in enumerate(periods)} for n in M.ITEMS}
    else:
        periods = [(year, "계획", m) for m in range(1, 13)] + [(year, "실적", m) for m in range(1, s.close + 1)] + [(year, "추정", m) for m in range(s.close + 1, 13)]
        src = {"계획": s.plan, "실적": s.actual, "추정": s.estimate}
        vals = {n: {i: src[sc][n][m] for i, (_, sc, m) in enumerate(periods)} for n in M.ITEMS}
    return wb.build(code, periods, vals)

# ---------- API 흐름 ----------
def upload(token, request_id, filename, payload):
    b = "seed-" + uuid.uuid4().hex
    body = (f"--{b}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{filename}\"\r\nContent-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet\r\n\r\n").encode() + payload + f"\r\n--{b}--\r\n".encode()
    sid = call("POST", f"/submission-requests/{request_id}/submissions", token, raw=body, ctype="multipart/form-data; boundary=" + b)["id"]
    for _ in range(600):
        d = call("GET", f"/submissions/{sid}", token)
        job = d.get("parsingJob") or {}
        if job.get("status") not in ("QUEUED", "RUNNING"): break
        time.sleep(1)
    if job.get("status") != "SUCCEEDED" or job.get("errorCode"):
        diag = call("GET", f"/submissions/{sid}/diagnostics", token)
        raise RuntimeError(f"FAIL parse {filename}: {job} {json.dumps(diag, ensure_ascii=False)[:1200]}")
    return sid

def org_items(data):
    if "items" in data: return data["items"]
    return [{**i, "month": mo["month"], "scenario": mo["scenario"]} for g in data["groups"] for mo in g["months"] for i in mo["items"]]

def reason(item, plan):
    name, m, sc, cur = item["name"], item["month"], item["scenario"], item.get("current")
    if sc == "ESTIMATE" and cur is None: return f"{m}월 실적이 확정되어 {m}월 추정치를 실적으로 대체했습니다."
    if cur is None: return f"{m}월 {name} 값을 정리했습니다."
    p = plan.get(name, {}).get(m, 0) * 1000
    pct = (int(cur) - p) / p * 100 if p else 0
    cause = CAUSE.get(name, ("", ""))[0 if pct > 0 else 1]
    if sc == "ESTIMATE":
        return f"{m}월 {name} 추정을 계획 대비 {pct:+.1f}%로 조정했습니다." + (" " + cause if abs(pct) >= 5 and cause else "")
    if item.get("baseline") is not None and item.get("baseline") != cur:
        return f"정산 자료를 반영해 {m}월 {name} 실적을 수정했습니다(계획 대비 {pct:+.1f}%)."
    if abs(pct) >= 5: return f"{m}월 {name} 실적 계획 대비 {pct:+.1f}%. " + (cause or "세부 원인을 확인해 반영했습니다.")
    return f"{m}월 {name} 실적 확정. 계획 대비 {pct:+.1f}%로 계획 범위 안입니다."

def prepare(sid, token, plan):
    d = call("GET", f"/submissions/{sid}", token)
    call("POST", f"/submissions/{sid}/validation", token, body={"expectedStateToken": d["stateToken"]})
    n = 0
    for it in org_items(call("GET", f"/review-items?view=ORG&submissionId={sid}", token)):
        if it.get("answerState") != "EMPTY": continue
        cur = call("GET", f"/review-items/{it['reviewItemId']}", token)
        call("PUT", f"/review-items/{it['reviewItemId']}/answer", token, body={"expectedStateToken": cur["stateToken"], "answer": reason(it, plan)})
        n += 1
    return n

def submit(sid, token, msg="제출합니다."):
    d = call("GET", f"/submissions/{sid}", token)
    ready = d.get("validation", d.get("readiness", {}))
    if not ready.get("canSubmit"): raise RuntimeError(f"FAIL not ready {sid}: {json.dumps(ready, ensure_ascii=False)[:600]}")
    call("POST", f"/submissions/{sid}/submit", token, body={"expectedStateToken": d["stateToken"], "replyMessage": msg})

def accept_and_approve(sid, planner):
    d = call("GET", f"/submissions/{sid}", planner)
    for a in d.get("explanations", []):
        rid = a.get("reviewItemId")
        if rid is None: continue
        it = call("GET", f"/review-items/{rid}", planner)
        cur = it.get("explanations", [])[-1:]
        if cur and cur[0].get("verdict") == "PENDING":
            call("POST", f"/review-items/{rid}/decisions", planner, body={"expectedStateToken": it["stateToken"], "verdict": "ACCEPT", "note": "확인했습니다."})
    d = call("GET", f"/submissions/{sid}", planner)
    call("POST", f"/submissions/{sid}/decisions", planner, body={"expectedStateToken": d["stateToken"], "decision": "APPROVE", "comment": "승인합니다."})

def subs(planner, rid, oid):
    return sorted(call("GET", "/submissions?" + urlencode({"requestId": rid, "organizationId": oid, "limit": 100}), planner)["items"], key=lambda s: int(s["id"]))

MESSAGES = {"F10": "11월 마감 실적과 12월 추정입니다. Data통화료 감소는 무제한 요금제 전환 영향입니다.", "F36": "집중 유지보수 반영했습니다.",
            "F12": "제휴상품 가입 증가분 반영했습니다. 확인 부탁드립니다.", "F13": "솔루션 수주 일정 변경 반영했습니다."}

def ensure(ctx, req, code, filename, payload, version, plan, mode="approve"):
    """mode: approve | pending | draft"""
    planner, tokens, orgs = ctx
    found = subs(planner, req["id"], orgs[code])
    done = [s for s in found if s["status"] in ("PENDING", "APPROVED")]
    if len(done) >= version: return f"{code} {filename} v{version} 이미 {done[version-1]['status']}"
    drafts = [s for s in found if s["status"] == "DRAFT"]
    sid = drafts[-1]["id"] if drafts else upload(tokens[code], req["id"], filename, payload)
    if mode == "draft": return f"{code} {filename} 업로드만(작성 중)"
    n = prepare(sid, tokens[code], plan)
    submit(sid, tokens[code], MESSAGES.get(code, "제출합니다.") if req.get("month") == 11 else "제출합니다.")
    if mode == "approve": accept_and_approve(sid, planner)
    st = call("GET", f"/submissions/{sid}", planner)
    return f"{code} {filename} v{version}: 소명 {n}건 → {st['status']} {st.get('label', '')}"

def request_for(planner, orgs, kind, year, month=None, due=None, message=None):
    reqs = call("GET", "/submission-requests?limit=100", planner)["items"]
    r = next((r for r in reqs if r["type"] == kind and r["year"] == year and r.get("month") == month), None)
    if r: return r
    due = due or (datetime.now(timezone.utc) + timedelta(days=7)).isoformat()
    msg = message or ((f"{year}년 연간 계획을 작성해 제출해 주세요." if kind == "ANNUAL_PLAN" else f"{year}년 {month}월 실적과 추정을 월간 시트 양식에 작성해 회신해 주세요.") + f" ({MARK})")
    r = call("POST", "/submission-requests", planner, body={"type": kind, "year": year, "month": month, "dueAt": due, "message": msg, "organizationIds": list(orgs.values())})
    return r.get("request", r)

def per_org(fn):
    with ThreadPoolExecutor(max_workers=len(M.CODES)) as ex:
        list(ex.map(fn, M.CODES))

NOV_MODE = {"F10": "approve", "F36": "approve", "F38": "approve", "F39": "approve", "F12": "pending", "F13": "pending",
            "F37": "pending", "F16": "pending", "F20": "draft", "F25": None}

def main(phases):
    planner = login("kim.plan@sk.com")
    tokens = {c: login(e) for c, _, e, _ in M.ORGS}
    orgs = {o["code"]: o["id"] for o in call("GET", "/organizations?active=true&limit=100", planner)["items"]}
    ctx = (planner, tokens, orgs)
    if "setup" in phases:
        cur = call("GET", "/gap-thresholds/current", planner)
        for comp in ("ACTUAL_VS_PLAN", "ESTIMATE_VS_PREVIOUS", "ESTIMATE_VS_PLAN"):
            item = next(i for i in cur["items"] if i["comparison"] == comp)
            if item["percentEnabled"]: continue
            call("POST", "/gap-thresholds", planner, body={"comparison": comp, "percentEnabled": True, "percent": "5", "amountEnabled": True, "amount": "100000000", "expectedStateToken": cur["stateToken"]})
            cur = call("GET", "/gap-thresholds/current", planner)
        log("Gap 기준: 5% 그리고 1억원 이상")
    states = {}
    for year in (2025, 2026):
        if f"y{year}" not in phases and not (year == 2026 and "nov" in phases): continue
        req = request_for(planner, orgs, "ANNUAL_PLAN", year)
        per_org(lambda c: log("  " + ensure(ctx, req, c, f"{c}_{year}-PLAN.xlsx", workbook(c, year, plan_only=M.plan(c, year)), 1, M.plan(c, year))))
        log(f"== {year} 연간 계획 확정")
        last = 12 if year == 2025 else 10
        reqs = {m: request_for(planner, orgs, "MONTHLY_ACTUAL", year, m) for m in range(1, last + 1)}
        def run_org(c, year=year, last=last, reqs=reqs):
            s = State(M.plan(c, year))
            for m in range(1, last + 1):
                s = advance(s, year, c, m)
                log("  " + ensure(ctx, reqs[m], c, f"{c}_{year}-{m:02d}.xlsx", workbook(c, year, s), 1, s.plan))
                if (year, c, m) in RESUBMIT:
                    s = resubmitted(s, year, c, m)
                    log("  " + ensure(ctx, reqs[m], c, f"{c}_{year}-{m:02d}.xlsx", workbook(c, year, s), 2, s.plan))
            states[c] = s
        if f"y{year}" in phases or year == 2026:
            per_org(run_org)
        log(f"== {year} 1~{last}월 완료")
    if "nov" in phases:
        req = request_for(planner, orgs, "MONTHLY_ACTUAL", 2026, 11, due=(datetime.now(timezone.utc) + timedelta(days=5)).isoformat(),
                          message="11월 실적과 12월 추정을 월간 시트 양식에 작성해 12월 3일까지 회신해 주세요. 계획 대비 5%·1억원 이상 차이는 소명을 함께 적어 주세요.")
        def nov(c):
            mode = NOV_MODE[c]
            if mode is None: return log(f"  {c} 11월 미제출(회신 대기)")
            s = advance(states[c], 2026, c, 11)
            log("  " + ensure(ctx, req, c, f"{c}_2026-11.xlsx", workbook(c, 2026, s), 1, s.plan, mode))
        per_org(nov)
        log("== 2026 11월 상태 반영")
    if "y2027" in phases:
        req = request_for(planner, orgs, "ANNUAL_PLAN", 2027, due=(datetime.now(timezone.utc) + timedelta(minutes=4)).isoformat(),
                          message="2027년 연간 계획을 작성해 제출해 주세요. 사업부별 매출·OpEx 계획과 산출 근거를 함께 적어 주세요.")
        mode27 = {"F10": "approve", "F36": "approve", "F12": "pending", "F13": "pending", "F38": "pending"}
        def y27(c):
            if c not in mode27: return log(f"  {c} 2027 연간 미제출")
            p = M.plan(c, 2027)
            log("  " + ensure(ctx, req, c, f"{c}_2027-PLAN.xlsx", workbook(c, 2027, plan_only=p), 1, p, mode27[c]))
        per_org(y27)
        log("== 2027 연간 계획 요청 반영 (기한 4분 뒤)")
    for t in [planner, *tokens.values()]:
        try: call("DELETE", "/auth/session", t)
        except Exception: pass

if __name__ == "__main__":
    main(sys.argv[1:] or ["setup", "y2025", "y2026", "nov", "y2027"])
