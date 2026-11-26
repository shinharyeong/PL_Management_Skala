"""11월 작업 상태·보고서 분류(로컬). 이미 된 단계는 건너뛴다."""
import uuid, urllib.request, urllib.parse
from datetime import datetime, timedelta, timezone
from seed import call, login, BASE

P = login("kim.plan@sk.com")
T = {"F12": login("park.media@sk.com"), "F37": login("seo.mkt@sk.com")}
ORG = {o["code"]: o["id"] for o in call("GET", "/organizations?active=true&limit=100", P)["items"]}
nov = next(r for r in call("GET", "/submission-requests?limit=100", P)["items"] if r["type"] == "MONTHLY_ACTUAL" and r["year"] == 2026 and r["month"] == 11)
due = (datetime.now(timezone.utc) + timedelta(days=3)).isoformat()

def flat(tree, out=None, parents=()):
    out = [] if out is None else out
    for n in tree or []:
        if n.get("reviewItemId"): out.append({**n, "path": parents})
        flat(n.get("children"), out, parents if n.get("reviewItemId") else parents + (n.get("name"),))
    return out
def tree(code):
    t = call("GET", f"/review-items?view=PLANNER&requestId={nov['id']}&organizationId={ORG[code]}&comparison=ACTUAL_VS_PLAN&tab=ALL", P)
    return t, flat(t["tree"])
def find(code, name):
    return next(i for i in tree(code)[1] if i["path"] and i["path"][-1] == name and i["name"] == "11월 실적")
def decide(it, verdict, note):
    d = call("GET", f"/review-items/{it['reviewItemId']}", P)
    body = {"verdict": verdict, "note": note, "expectedStateToken": d["stateToken"]}
    if verdict == "REQUEST_AGAIN": body["dueAt"] = due
    call("POST", f"/review-items/{it['reviewItemId']}/decisions", P, body=body)
def answer(code, it, text, attach=None):
    rid = it["reviewItemId"]
    d = call("GET", f"/review-items/{rid}", T[code])
    call("PUT", f"/review-items/{rid}/answer", T[code], body={"expectedStateToken": d["stateToken"], "answer": text})
    if attach:
        tok = call("GET", f"/review-items/{rid}", T[code])["stateToken"]
        pdf = b"%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj 2 0 obj<</Type/Pages/Count 0/Kids[]>>endobj\ntrailer<</Root 1 0 R>>\n%%EOF\n"
        b = "x-" + uuid.uuid4().hex
        body = (f"--{b}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{attach}\"\r\nContent-Type: application/pdf\r\n\r\n").encode() + pdf + f"\r\n--{b}--\r\n".encode()
        req = urllib.request.Request(BASE + f"/review-items/{rid}/attachments?expectedStateToken={urllib.parse.quote(tok)}", data=body, method="POST")
        req.add_header("Content-Type", f"multipart/form-data; boundary={b}"); req.add_header("Authorization", "Bearer " + T[code]); req.add_header("Idempotency-Key", str(uuid.uuid4()))
        urllib.request.urlopen(req, timeout=60).read()

# F12 미디어사업부: 탭 6종
it = find("F12", "신사업/기타B2B")
if it["tab"] == "NEEDS_REVIEW": decide(it, "ACCEPT", "AI 구독 출시 일정 변경 확인했습니다.")
it = find("F12", "제휴상품")
if it["tab"] == "NEEDS_REVIEW": decide(it, "REQUEST_AGAIN", "+16%가 일회성인지 확인이 필요합니다. OTT 결합 가입 추이 자료를 첨부해 주세요.")
it = find("F12", "제휴상품")
if it["tab"] == "IN_PROGRESS":
    answer("F12", it, "11월 OTT 결합 프로모션(3개월 무료)으로 신규 가입이 4.2만 건 늘었습니다. 프로모션 종료 뒤 해지율 12%를 가정해 12월 추정에 반영했습니다. 월별 가입 추이를 첨부합니다.", "OTT_결합_가입추이.pdf")
it = find("F12", "기타외주용역비")
if it["tab"] == "NEEDS_REVIEW": decide(it, "REQUEST_AGAIN", "외주 계약 단가 인상분이 12월 이후에도 이어지는지 알려 주세요.")
for name, action in (("메시징기본료", "EXCLUDE"), ("일반복리비", "SELECT")):
    its = [i for i in tree("F12")[1] if i["path"] and i["path"][-1] == name and i["name"] == "11월 실적"]
    if its and its[0]["tab"] == "NEEDS_REVIEW":
        body = {"action": action, "items": [{"reviewItemId": its[0]["reviewItemId"], "expectedStateToken": its[0]["stateToken"]}]}
        if action == "EXCLUDE": body["note"] = "차이가 작아 검토에서 제외합니다."
        call("POST", "/review-items/actions", P, body=body)
print("F12 탭:", tree("F12")[0]["tabCounts"])

# F37 마케팅부문: 일반광고비 재요청(미회신)
it = find("F37", "일반광고비")
if it["tab"] == "NEEDS_REVIEW": decide(it, "REQUEST_AGAIN", "연말 캠페인 조기 집행분과 12월 감액 계획을 숫자로 적어 주세요.")
print("F37 탭:", tree("F37")[0]["tabCounts"])

# F16 엔터프라이즈사업부: 재제출 요청
t, its = tree("F16")
sub = call("GET", f"/submissions/{t['submission']['id']}", P)
if sub["status"] == "PENDING":
    bad = next(i for i in its if i["path"] and i["path"][-1] == "Biz Data" and i["name"] == "11월 실적")
    call("POST", f"/submissions/{sub['id']}/decisions", P, body={"decision": "REJECT", "expectedStateToken": sub["stateToken"],
         "comment": "Biz Data 11월 실적이 IoT 개통 집계 기준(개통일)과 다르게 들어갔습니다. 정산 기준으로 고쳐 다시 제출해 주세요.", "rejectedReviewItemIds": [bad["reviewItemId"]]})
print("F16:", call("GET", f"/submissions/{t['submission']['id']}", P)["status"])

# 보고서 소명 분류(실적 비교): 의미에 맞게 일부만 분류
cats = {c["name"]: c["id"] for c in call("GET", "/report/explanation-categories", P)["items"] if c.get("assignable")}
pick = {"로밍": "일시적 효과", "기타외주용역비": "일시적 효과", "솔루션/수주": "이연 효과", "일반광고비": "이연 효과", "통신시설수선비": "일시적 효과", "고객기변지원금": "월정액 추정 Gap"}
for month in (10, 11):
    ex = call("GET", f"/report/explanations?year=2026&month={month}&comparison=ACTUAL_VS_PLAN", P)
    for k, i in enumerate(ex["items"]):
        if i.get("categoryId") or i["item"] not in pick or (month == 11 and k == len(ex["items"]) - 1): continue
        call("PUT", f"/report/explanations/{i['explanationId']}/classification?comparison=ACTUAL_VS_PLAN", P, body={"categoryId": cats[pick[i["item"]]]})
    ex = call("GET", f"/report/explanations?year=2026&month={month}&comparison=ACTUAL_VS_PLAN", P)
    inv = {v: k for k, v in cats.items()}
    print(f"보고서 {month}월:", [(i["organization"]["name"], i["item"], inv.get(i.get("categoryId"))) for i in ex["items"]], "검토중", len(ex.get("reviewing") or []))
for t in [P, *T.values()]: call("DELETE", "/auth/session", t)
