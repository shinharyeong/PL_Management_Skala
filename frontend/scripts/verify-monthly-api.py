"""격리된 로컬 DB에서 합성 연간 계획·월간 검토 HTTP 흐름을 검증한다.

XLSX와 토큰은 메모리에만 둔다. 기존 요청이 있으면 중단하며, 기존 자료를
덮어쓰거나 삭제하지 않는다. --apply는 합성 요청·제출·소명·승인 이력을 만든다.
서버는 별도의 빈 개발 DB를 사용해야 한다. 실제 회사 DB를 지정하지 않는다.

기본 인증은 /api/dev/session이다. 해당 개발 API가 없는 백엔드는 환경변수
MONTHLY_API_PLANNER_TOKEN 및 MONTHLY_API_ORGANIZATION_TOKENS(JSON 코드:토큰)
으로 격리 환경의 세션을 제공할 수 있다. 토큰이나 응답 본문은 출력하지 않는다.
"""

import argparse
import importlib.util
import json
import os
import re
import sys
import time
import uuid
from collections import defaultdict
from datetime import datetime, timedelta, timezone
from decimal import Decimal
from io import BytesIO
from pathlib import Path
from urllib.parse import urlencode, urlsplit
from xml.sax.saxutils import escape, quoteattr
from zipfile import ZIP_DEFLATED, ZipFile


NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
PACKAGE_REL = "http://schemas.openxmlformats.org/package/2006/relationships"


def passed(label: str) -> None:
    """개인정보·응답값 없는 고정 검증 이름만 출력한다."""
    print("PASS " + label, flush=True)


def require(condition: bool, label: str) -> None:
    if not condition:
        raise AssertionError(label)


def column(number: int) -> str:
    result = ""
    while number:
        number, digit = divmod(number - 1, 26)
        result = chr(65 + digit) + result
    return result


def sheet_xml(cells: list[tuple]) -> str:
    rows = defaultdict(list)
    for address, value, kind, *formula in cells:
        row = int("".join(char for char in address if char.isdigit()))
        if kind == "string":
            body = '<is><t>' + escape(str(value)) + '</t></is>'
            attributes = ' t="inlineStr"'
        else:
            body = ("<f>" + escape(formula[0]) + "</f>" if formula else "")
            body += "<v>" + escape(str(value)) + "</v>"
            attributes = ""
        rows[row].append(f'<c r="{address}"{attributes}>{body}</c>')
    contents = "".join(f'<row r="{row}">{"".join(rows[row])}</row>' for row in sorted(rows))
    return f'<worksheet xmlns="{NS}"><sheetData>{contents}</sheetData></worksheet>'


def workbook(organization: dict, year: int, close_month: int | None) -> bytes:
    """명시적 월·시나리오와 SUMIFS 근거가 있는 합성 OOXML을 메모리에 만든다."""
    org_name, org_code = organization["name"], organization["code"]
    # 연간은 12개월 계획, 월간은 계획 및 마감월 실적·이후월 추정이다.
    periods = [(month, "PLAN") for month in range(1, 13)]
    if close_month is not None:
        periods += [(month, "ACTUAL" if month <= close_month else "ESTIMATE") for month in range(1, 13)]
    revenue = [("A1", "단위: 천원", "string")]
    revenue += [(f'{column(index)}3', label, "string") for index, label in enumerate(
        ("조직명", "관리분류", "중분류", "소분류", "거래내역 및 산출근거"), 1)]
    revenue += [("A5", org_name, "string"), ("B5", "합성매출", "string"),
                ("C5", "2200 합성계정", "string"), ("D5", "합성매출", "string"),
                ("E5", "합성 API 검증 [ID:synthetic-revenue; 수정:1]", "string")]
    expense = [("A1", "단위: 천원", "string")]
    expense += [(f'{column(index)}5', label, "string") for index, label in enumerate(
        ("대분류", "중분류", "소분류", "조직명", "본부명", "팀Code", "팀명", "실행과제Code",
         "실행과제명", "예산Code", "예산명칭", "실적집계기준", "비용구분", "담당자", "거래내역 및 산출근거"), 1)]
    expense += [("D6", org_name, "string"), ("F6", "SYNTHETIC-TEAM", "string"),
                ("H6", "SYNTHETIC-TASK", "string"), ("J6", "62020", "number"),
                ("K6", "합성비용", "string"), ("L6", "합성비용", "string"),
                ("M6", "고정", "string"), ("N6", "합성 담당", "string"),
                ("O6", "합성 API 검증 [ID:synthetic-expense; 수정:1]", "string")]
    report = [("A5", org_name, "string"), ("C9", "매출", "string"),
              ("D10", "합성매출", "string"), ("C12", "영업비용", "string"),
              ("E13", "합성비용", "string"), ("C14", "영업이익", "string")]
    names = {"PLAN": "계획", "ACTUAL": "실적", "ESTIMATE": "추정"}
    for index, (month, scenario) in enumerate(periods):
        rev_col, cost_col, rpt_col = column(index + 7), column(index + 18), column(index + 10)
        revenue += [(f'{rev_col}3', f'{year}년 {names[scenario]}', "string"),
                    (f'{rev_col}4', f'{month}월', "string")]
        expense += [(f'{cost_col}4', f'{year}년 {names[scenario]}', "string"),
                    (f'{cost_col}5', f'{month}월', "string")]
        report += [(f'{rpt_col}5', f'{year}년', "string"), (f'{rpt_col}6', names[scenario], "string"),
                   (f'{rpt_col}8', f'{month}월', "string")]
        rev_amount = Decimal("1000.50") if scenario == "PLAN" else Decimal("1300.75")
        cost_amount = Decimal("300.25") if scenario == "PLAN" else Decimal("350.25")
        revenue.append((f'{rev_col}5', str(rev_amount), "number"))
        expense.append((f'{cost_col}6', str(cost_amount), "number"))
        formulas = {
            9: f'SUM({rpt_col}10:{rpt_col}10)',
            10: f'SUMIFS(매출!{rev_col}:{rev_col},매출!$B:$B,$D10,매출!$A:$A,$A$5)/100000',
            12: f'SUM({rpt_col}13:{rpt_col}13)',
            13: f'SUMIFS(비용!{cost_col}:{cost_col},비용!$D:$D,보고!$A$5,비용!$L:$L,보고!$E:$E)/100000',
            14: f'{rpt_col}9-{rpt_col}12',
        }
        values = {9: rev_amount, 10: rev_amount, 12: cost_amount, 13: cost_amount, 14: rev_amount - cost_amount}
        for row, amount in values.items():
            report.append((f'{rpt_col}{row}', str(amount / Decimal(100000)), "number", formulas[row]))
    sheets = {
        "매출": sheet_xml(revenue), "비용": sheet_xml(expense),
        "조직": sheet_xml([("A1", "사업부코드", "string"), ("B1", "사업부명", "string"),
                           ("A2", org_code, "string"), ("B2", org_name, "string")]),
        "계정": sheet_xml([("A1", "예산코드", "string"), ("B1", "예산명칭", "string"),
                           ("A2", "62020", "number"), ("B2", "합성비용", "string")]),
        "보고": sheet_xml(report),
    }
    output = BytesIO()
    with ZipFile(output, "w", compression=ZIP_DEFLATED) as archive:
        entries, relationships, content_types = [], [], []
        for index, (name, xml) in enumerate(sheets.items(), 1):
            entries.append(f'<sheet name={quoteattr(name)} sheetId="{index}" r:id="r{index}"/>')
            relationships.append(f'<Relationship Id="r{index}" Type="{REL}/worksheet" Target="worksheets/sheet{index}.xml"/>')
            content_types.append(f'<Override PartName="/xl/worksheets/sheet{index}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>')
            archive.writestr(f'xl/worksheets/sheet{index}.xml', xml)
        archive.writestr("xl/workbook.xml", f'<workbook xmlns="{NS}" xmlns:r="{REL}"><sheets>{"".join(entries)}</sheets></workbook>')
        archive.writestr("xl/_rels/workbook.xml.rels", f'<Relationships xmlns="{PACKAGE_REL}">{"".join(relationships)}</Relationships>')
        archive.writestr("_rels/.rels", f'<Relationships xmlns="{PACKAGE_REL}"><Relationship Id="book" Type="{REL}/officeDocument" Target="xl/workbook.xml"/></Relationships>')
        archive.writestr("[Content_Types].xml", '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>' + "".join(content_types) + '</Types>')
    return output.getvalue()


class Api:
    def __init__(self, base: str, repository: Path, local_auth_log: Path | None = None):
        specification = importlib.util.spec_from_file_location("monthly_api_http", repository / "scripts/smoke-local.py")
        require(specification is not None and specification.loader is not None, "HTTP helper available")
        self.http = importlib.util.module_from_spec(specification)
        sys.modules[specification.name] = self.http
        specification.loader.exec_module(self.http)
        self.base = base
        self.local_auth_log = local_auth_log
        self.tokens = json.loads(os.environ.get("MONTHLY_API_ORGANIZATION_TOKENS", "{}"))
        self.planner = os.environ.get("MONTHLY_API_PLANNER_TOKEN") or self.session("PLANNER")

    def session(self, role: str, code: str | None = None) -> str:
        if code in self.tokens:
            return self.tokens[code]
        if self.local_auth_log:
            # local 프로필의 공개된 합성 seed 계정만 사용한다.
            emails = {None: "kim.plan@sk.com", "D100": "choi.infra@sk.com", "F1000": "park.media@sk.com"}
            require(code in emails, "local seed organization has authentication fixture")
            email = emails[code]
            offset = self.local_auth_log.stat().st_size
            response = self.http.call(self.base, "/api/v1/auth/codes", method="POST",
                body=json.dumps({"email": email}).encode(), content_type="application/json")
            require(response.status == 202, "local authentication code requested")
            deadline = time.monotonic() + 5
            match = None
            while time.monotonic() < deadline:
                with self.local_auth_log.open("rb") as log:
                    log.seek(offset)
                    fragment = log.read().decode("utf-8", errors="replace")
                match = re.search(r'email=' + re.escape(email) + r' code=(\d{6})', fragment)
                if match:
                    break
                time.sleep(0.1)
            require(match is not None, "new local authentication code observed in memory")
            response = self.http.call(self.base, "/api/v1/auth/sessions", method="POST",
                body=json.dumps({"email": email, "code": match.group(1)}).encode(), content_type="application/json")
            require(response.status == 201, "local authentication session created")
            data = json.loads(response.body)
            require(data["me"]["role"] == role, "authenticated role matches requested fixture")
            self.tokens[code] = data["token"]
            return data["token"]
        body = {"role": role}
        if code:
            body["organizationCode"] = code
        response = self.http.call(self.base, "/api/dev/session", method="POST", body=json.dumps(body).encode(), content_type="application/json")
        require(response.status == 200, "development authentication available")
        return json.loads(response.body)["token"]

    def request(self, path: str, token: str | None = None, *, method="GET", body=None, status=200, label="API contract"):
        response = self.http.call(self.base, "/api/v1" + path, token=token or self.planner, method=method,
            body=json.dumps(body).encode() if body is not None else None,
            content_type="application/json" if body is not None else None,
            key=None if method == "GET" else "synthetic-" + uuid.uuid4().hex)
        machine_code = ""
        if response.status != status:
            try:
                code = json.loads(response.body).get("code", "")
                if isinstance(code, str) and re.fullmatch(r'[A-Z][A-Z0-9_]{0,79}', code):
                    machine_code = " (" + code + ")"
            except (ValueError, AttributeError):
                pass
        require(response.status == status, f'{label}: expected HTTP {status}, received HTTP {response.status}' + machine_code)
        return json.loads(response.body) if response.body else None

    def submission(self, sid, token=None):
        return self.request(f'/submissions/{sid}', token)

    def upload(self, request_id, organization, year, month, token):
        payload = workbook(organization, year, month)
        filename = f'{organization["code"]}_{year}-' + ("PLAN" if month is None else f'{month:02d}') + ".xlsx"
        boundary = "synthetic-" + uuid.uuid4().hex
        response = self.http.call(self.base, f'/api/v1/submission-requests/{request_id}/submissions',
            method="POST", token=token, body=self.http.multipart(filename, payload, boundary),
            content_type="multipart/form-data; boundary=" + boundary, key=uuid.uuid4().hex)
        require(response.status == 202, "synthetic upload accepted")
        sid = json.loads(response.body)["id"]
        deadline = time.monotonic() + 100
        while time.monotonic() < deadline:
            detail = self.submission(sid, token)
            parsing = detail.get("parsingJob", detail.get("parsing", {}))
            if parsing.get("status") not in {"QUEUED", "RUNNING"}:
                require(parsing.get("status") == "SUCCEEDED" and parsing.get("errorCode") is None, "synthetic real parser passed")
                break
            time.sleep(0.5)
        else:
            raise AssertionError("synthetic parsing deadline")
        original = self.http.call(self.base, f'/api/v1/submissions/{sid}/file', token=token)
        require(original.status == 200 and original.body == payload, "original bytes preserved")
        return sid


def org_review_items(data):
    if "items" in data:
        return data["items"]
    return [item for group in data["groups"] for month in group["months"] for item in month["items"]]


def tree_leaves(nodes):
    for node in nodes:
        if node.get("reviewItemId"):
            yield node
        yield from tree_leaves(node.get("children", []))


def prepare_and_submit(api: Api, sid, token):
    detail = api.submission(sid, token)
    api.request(f'/submissions/{sid}/validation', token, method="POST", body={"expectedStateToken": detail["stateToken"]})
    reviews = api.request(f'/review-items?view=ORG&submissionId={sid}', token)
    for item in org_review_items(reviews):
        if item.get("answerState") != "EMPTY":
            continue
        review_id = item.get("reviewItemId", item.get("id"))
        current = api.request(f'/review-items/{review_id}', token)
        api.request(f'/review-items/{review_id}/answer', token, method="PUT", body={
            "expectedStateToken": current["stateToken"], "answer": "합성 API 검증 자료입니다. 실제 사업 원인이나 실제 제출 승인을 의미하지 않습니다."})
    detail = api.submission(sid, token)
    readiness = detail.get("validation", detail.get("readiness", {}))
    require(readiness.get("canSubmit") is True, "synthetic submission readiness")
    api.request(f'/submissions/{sid}/submit', token, method="POST", body={
        "expectedStateToken": detail["stateToken"], "replyMessage": "합성 API 검증"})
    require(api.submission(sid, token)["status"] == "PENDING", "submission pending approval")


def accept_and_approve(api: Api, sid):
    detail = api.submission(sid)
    for answer in detail.get("explanations", []):
        review_id = answer.get("reviewItemId")
        if review_id is None:
            continue
        item = api.request(f'/review-items/{review_id}')
        current = item.get("explanations", [])[-1:]
        if current and current[0].get("verdict") == "PENDING":
            api.request(f'/review-items/{review_id}/decisions', method="POST", body={
                "expectedStateToken": item["stateToken"], "verdict": "ACCEPT", "note": "합성 API 검증"})
    detail = api.submission(sid)
    result = api.request(f'/submissions/{sid}/decisions', method="POST", body={
        "expectedStateToken": detail["stateToken"], "decision": "APPROVE", "comment": "합성 API 검증"})
    require(result["submission"]["status"] == "APPROVED", "synthetic annual submission approved")
    return result


def run(args):
    api = Api(args.base_url, args.repository, args.local_auth_log)
    anonymous = api.http.call(api.base, "/api/v1/me")
    require(anonymous.status == 401, "anonymous denied")
    passed("anonymous API denied")
    organizations = api.request("/organizations?limit=100")["items"]
    require(bool(organizations), "active organizations available")
    existing_requests = {}
    for kind in ("ANNUAL_PLAN", "MONTHLY_ACTUAL"):
        existing = api.request("/submission-requests?" + urlencode({"type": kind, "year": args.year, "limit": 100}))["items"]
        require(not existing or args.resume, "isolated year has no existing requests; use a fresh test database")
        require(len(existing) <= 1 and all(item.get("message") == "합성 API 검증 전용 요청" for item in existing), "resume only verifier synthetic requests")
        if kind == "MONTHLY_ACTUAL":
            require(all(item.get("month") == args.month for item in existing), "resume only same synthetic monthly period")
        existing_requests[kind] = existing[0] if existing else None
    due = (datetime.now(timezone.utc) + timedelta(days=7)).isoformat()
    request_body = {"type": "ANNUAL_PLAN", "year": args.year, "dueAt": due,
        "message": "합성 API 검증 전용 요청", "organizationIds": [org["id"] for org in organizations]}
    annual = existing_requests["ANNUAL_PLAN"] or api.request("/submission-requests", method="POST", status=201, body=request_body)
    last_result = None
    manager_tokens = {}
    for organization in organizations:
        token = api.session("ORG_MANAGER", organization["code"])
        manager_tokens[organization["code"]] = token
        found = api.request("/submissions?" + urlencode({"requestId": annual["id"], "organizationId": organization["id"]}))["items"]
        require(len(found) <= 1, "one synthetic annual fixture per organization")
        sid = found[0]["id"] if found else api.upload(annual["id"], organization, args.year, None, token)
        state = api.submission(sid, token)["status"]
        if state == "DRAFT":
            prepare_and_submit(api, sid, token)
        if state != "APPROVED":
            last_result = accept_and_approve(api, sid)
    require(existing_requests["ANNUAL_PLAN"] is not None or last_result.get("masterPublished") is not None, "annual master published after all synthetic approvals")
    passed("annual precondition and master publication")
    monthly = existing_requests["MONTHLY_ACTUAL"] or api.request("/submission-requests", method="POST", status=201, body={
        **request_body, "type": "MONTHLY_ACTUAL", "month": args.month})
    passed("monthly request after confirmed annual plan")
    organization = organizations[0]
    manager = manager_tokens[organization["code"]]
    api.request("/organizations", manager, status=403, label="organization role denied planner scope")
    passed("organization role denied planner scope")
    threshold = api.request("/gap-thresholds/current")
    api.request("/gap-thresholds", method="POST", status=201, body={"comparison": "ACTUAL_VS_PLAN",
        "percentEnabled": True, "percent": "5", "amountEnabled": False, "amount": "0",
        "expectedStateToken": threshold["stateToken"]})
    found = api.request("/submissions?" + urlencode({"requestId": monthly["id"], "organizationId": organization["id"]}))["items"]
    require(len(found) <= 1, "one synthetic monthly fixture for selected organization")
    sid = found[0]["id"] if found else api.upload(monthly["id"], organization, args.year, args.month, manager)
    if api.submission(sid, manager)["status"] == "DRAFT":
        prepare_and_submit(api, sid, manager)
    passed("monthly upload parse validate and submit")
    query = urlencode({"view": "PLANNER", "requestId": monthly["id"], "organizationId": organization["id"], "comparison": "ACTUAL_VS_PLAN", "tab": "ALL"})
    tree = api.request("/review-items?" + query)
    leaves = list(tree_leaves(tree["tree"]))
    require(bool(leaves), "hierarchical Gap result has leaf items")
    leaf = next((item for item in leaves if item.get("gapExceeded")), leaves[0])
    review_id = leaf["reviewItemId"]
    if leaf.get("tab") == "NEEDS_REVIEW":
        selected = api.request("/review-items/actions", method="POST", label="select Gap basket item", body={"action": "SELECT", "items": [
            {"reviewItemId": review_id, "expectedStateToken": leaf["stateToken"]}]})
        require(selected["items"][0]["tab"] == "SELECTED", "selected item enters review basket")
        token = selected["items"][0]["stateToken"]
    else:
        token = leaf["stateToken"]
    api.request("/review-items/actions", method="POST", status=409, label="duplicate selection conflict", body={
        "action": "SELECT", "items": [{"reviewItemId": review_id, "expectedStateToken": token}]})
    passed("hierarchical Gap basket and duplicate conflict")
    if leaf.get("tab") in {"NEEDS_REVIEW", "SELECTED"}:
        api.request("/review-items/actions", method="POST", label="request selected explanation", body={"action": "REQUEST_EXPLANATION",
            "items": [{"reviewItemId": review_id, "expectedStateToken": token}], "dueAt": due,
            "message": "합성 검증 항목의 수치 차이를 확인해 주세요."})
    detail = api.request(f'/review-items/{review_id}', manager)
    api.request(f'/review-items/{review_id}/answer', manager, method="PUT", label="organization replies to planner request", body={
        "expectedStateToken": detail["stateToken"], "answer": "합성 API 검증에 의도적으로 설정한 차이입니다. 실제 업무 소명이 아닙니다."})
    detail = api.request(f'/review-items/{review_id}')
    api.request(f'/review-items/{review_id}/decisions', method="POST", label="planner accepts organization reply", body={
        "expectedStateToken": detail["stateToken"], "verdict": "ACCEPT", "note": "합성 검증 회신 확인"})
    current = api.request(f'/review-items/{review_id}')
    require(current["explanations"][-1]["verdict"] == "ACCEPTED", "requested explanation accepted")
    passed("selected explanation request organization reply and planner acceptance")
    # 소명 수락이 제출본 승인으로 바뀌지 않아야 한다.
    require(api.submission(sid)["status"] == "PENDING", "explanation acceptance leaves submission pending")
    passed("explanation acceptance separated from submission approval")
    evidence = api.request(f'/review-items/{review_id}/evidence')
    require(bool(evidence["items"]), "original cell evidence available")
    api.request(f'/review-items/{review_id}/versions')
    passed("source cell evidence and version comparison")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--repository", type=Path, required=True, help="scripts/smoke-local.py와 parser/src가 있는 백엔드 저장소")
    parser.add_argument("--local-auth-log", type=Path, help="격리 local 프로필의 인증 로그. 새 코드만 메모리에서 읽고 출력하지 않음")
    parser.add_argument("--year", type=int, default=2026)
    parser.add_argument("--month", type=int, default=11)
    parser.add_argument("--apply", action="store_true", help="빈 격리 DB에 합성 업무 이력을 만드는 명시적 허용")
    parser.add_argument("--resume", action="store_true", help="이 검증기의 같은 합성 요청·파일 단계만 이어서 검증")
    args = parser.parse_args()
    origin = urlsplit(args.base_url)
    if origin.scheme != "http" or origin.hostname not in {"127.0.0.1", "localhost"} or origin.username or origin.password or origin.path or origin.query or origin.fragment:
        parser.error("base-url must be an exact loopback HTTP origin")
    if not 2000 <= args.year <= 9999 or not 1 <= args.month <= 12:
        parser.error("invalid synthetic request period")
    if not args.apply:
        parser.error("--apply is required; use only a fresh isolated development database")
    try:
        run(args)
    except (AssertionError, OSError, ValueError, KeyError, IndexError) as error:
        # 서버 본문·토큰·개인정보가 포함될 수 있는 exception repr/traceback은 출력하지 않는다.
        label = str(error) if isinstance(error, AssertionError) else "integration transport or response contract"
        print("FAIL " + label, file=sys.stderr)
        raise SystemExit(1) from None


if __name__ == "__main__":
    main()
