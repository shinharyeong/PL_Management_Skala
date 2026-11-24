"""01 시나리오의 계획·1월 실적을 실제 로컬 API로 검증하는 명시 실행 도구다.

원본은 읽기 전용이며 결과·자격·임시 파일은 만들지 않는다. --apply 실행은
개발 DB에 새 제출·검증용 소명·승인·계획 확정·마스터 이력을 남긴다.
소명 문구는 합성 자료의 기술 검증용이며 실제 사업 원인을 대신하지 않는다.
"""

import argparse
from collections import defaultdict
from decimal import Decimal
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
from typing import Any
from urllib.parse import urlencode, urlsplit
import uuid
from xml.etree import ElementTree as ET
from zipfile import ZipFile


def helpers() -> Any:
    """기존 HTTP·세션·multipart 도구를 파일 생성 없이 불러온다.

    Args: 없음. Returns: verify-dummy 모듈. Raises: 모듈 읽기 오류.
    Side Effects: 메모리 모듈 등록만 수행하며 -B 실행을 전제로 한다.
    """
    spec = importlib.util.spec_from_file_location("monthly_helpers", Path(__file__).with_name("verify-dummy.py"))
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


class Api:
    """루프백 API와 메모리 세션을 연결한다. 응답·토큰은 파일에 저장하지 않는다."""

    def __init__(self, base: str) -> None:
        """기존 도구와 기획 세션을 준비한다.

        Args: base 검증한 루프백 출처. Returns: 없음. Raises: HTTP 오류.
        Side Effects: 개발 세션을 생성하고 메모리에서만 유지한다.
        """
        self.base = base
        self.helper = helpers()
        self.http = self.helper.load_http_helpers()
        self.planner = self.session("PLANNER")

    def session(self, role: str, code: str | None = None) -> str:
        """명시한 역할의 개발 세션을 얻는다.

        Args: role 역할, code 조직코드. Returns: 토큰. Raises: HTTP 검증 오류.
        Side Effects: 개발 세션 생성, 토큰 출력·저장 없음.
        """
        return self.helper.open_session(self.http, self.base, role, code)["token"]

    def request(self, path: str, token: str | None = None, *, body: dict | None = None,
                method: str = "GET", expected: int = 200) -> dict:
        """JSON 명령을 전송하고 오류에서는 코드만 노출한다.

        Args: path 경로, token 역할 토큰, body JSON, method 메서드, expected 상태.
        Returns: JSON 객체. Raises: HTTP·JSON·상태 오류.
        Side Effects: 변경 명령은 개발 DB를 갱신한다. 파일 생성 없음.
        """
        response = self.http.call(self.base, "/api/v1" + path, token=token or self.planner,
            method=method, body=None if body is None else json.dumps(body, ensure_ascii=False).encode(),
            content_type=None if body is None else "application/json",
            key=None if method == "GET" else "monthly-check-" + uuid.uuid4().hex)
        return self.helper.json_response(response, expected)

    def items(self, path: str, token: str | None = None, **filters: Any) -> list[dict]:
        """권한·필터를 유지하면서 목록의 모든 페이지를 읽는다.

        Args: path 목록 경로, token 역할 토큰, filters 필터. Returns: 전체 항목.
        Raises: HTTP·커서 오류. Side Effects: GET과 메모리 누적만 수행한다.
        """
        result, cursor = [], None
        while True:
            query = {**filters, "limit": 100}
            if cursor:
                query["cursor"] = cursor
            page = self.request(path + "?" + urlencode(query), token)
            result.extend(page["items"])
            cursor = page.get("nextCursor")
            if not cursor:
                return result

    def original(self, submission_id: str, token: str) -> bytes:
        """권한이 있는 제출본 원본을 메모리로 읽는다.

        Args: submission_id 제출, token 역할 토큰. Returns: XLSX 바이트.
        Raises: HTTP 상태 오류. Side Effects: GET만 수행하며 다운로드 파일 없음.
        """
        response = self.http.call(self.base, f"/api/v1/submissions/{submission_id}/file", token=token)
        assert response.status == 200, "원본 조회 실패"
        return response.body


def source_inputs(directory: Path) -> tuple[list[dict], dict, dict[str, str]]:
    """01 시나리오 1월의 17개 조직·검증 마스터와 원본 해시를 읽는다.

    Args: directory 월별검증_2026 경로. Returns: 조직 목록·전체 항목·해시 사전.
    Raises: 경로·manifest·대상 개수 오류. Side Effects: 원본 읽기만 수행한다.
    """
    root = directory.resolve(strict=True)
    manifest = json.loads((root / "검증기준" / "manifest_01.json").read_text())
    entries = [item for item in manifest["files"] if item["close_month"] == 1]
    organizations = [item for item in entries if not item["is_master"]]
    references = [item for item in entries if item["is_master"]]
    assert len(organizations) == 17 and len(references) == 1
    assert len({item["org_code"] for item in organizations}) == 17
    digests = {}
    for item in entries:
        path = (root / item["path"]).resolve(strict=True)
        assert path.is_relative_to(root) and path.suffix == ".xlsx"
        item["sourcePath"] = path
        digests[str(path)] = hashlib.sha256(path.read_bytes()).hexdigest()
    return organizations, references[0], digests


def source_cells(entry: dict) -> dict[tuple[str, str], Decimal]:
    """파서와 독립적으로 원본 OOXML 숫자 셀을 읽는다.

    Args: entry 확인한 원본·시트 layout. Returns: 시트/주소별 원문 숫자.
    Raises: ZIP/XML/Decimal 오류. Side Effects: 원본 ZIP 읽기, 수식 실행 없음.
    """
    ns = {"s": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}
    values = {}
    with ZipFile(entry["sourcePath"]) as archive:
        for layout in entry["layout"].values():
            tree = ET.fromstring(archive.read(layout["part"]))
            for cell in tree.findall(".//s:sheetData/s:row/s:c", ns):
                raw = cell.findtext("s:v", namespaces=ns)
                if raw is not None and cell.get("t") in {None, "n"}:
                    values[(layout["sheet"], cell.get("r"))] = Decimal(raw)
    return values


def column_name(number: int) -> str:
    """1부터 시작하는 열 번호를 Excel 주소 열명으로 변환한다.

    Args: number 양수 열 번호. Returns: 열 문자열. Raises: 없음.
    Side Effects: 없음.
    """
    result = ""
    while number:
        number, digit = divmod(number - 1, 26)
        result = chr(65 + digit) + result
    return result


def check_values(api: Api, submission_id: str, entry: dict, annual: bool) -> int:
    """모든 적재 셀·기간·시나리오와 독립 원본 상세 합계를 대조한다.

    Args: api HTTP, submission_id 제출, entry 원본, annual 계획 여부.
    Returns: 검증 금액 개수. Raises: 누락·중복·기간·금액 불일치.
    Side Effects: 원본/API 읽기, 메모리 계산만 수행한다.
    """
    rows = api.items(f"/submissions/{submission_id}/financial-values", year=2026)
    cells, totals, identities = source_cells(entry), defaultdict(Decimal), set()
    expected_count = sum(value["row_count"] for value in entry["expected"].values()) * 12
    assert len(rows) == expected_count, (entry["org_code"], len(rows), expected_count)
    for row in rows:
        month = row["month"]
        scenario = "PLAN" if annual else "ACTUAL" if month == 1 else "ESTIMATE"
        assert row["year"] == 2026 and row["scenario"] == scenario
        source = row["source"]
        identity = (source["sheet"], source["cell"])
        assert identity not in identities
        identities.add(identity)
        layout = entry["layout"][row["reportItem"]["kind"].lower()]
        assert source["cell"] == column_name(layout["annual_columns"][1 if annual else 2] + month) + str(source["row"])
        amount = Decimal(row["amount"])
        assert amount == cells[identity] * 1000
        totals[(row["reportItem"]["kind"].lower(), month)] += amount
    for domain in ("revenue", "cost"):
        expected = entry["expected"][domain]["periods"]["plan" if annual else "outlook"]["months"]
        for month, value in enumerate(expected, 1):
            assert totals[(domain, month)] == Decimal(str(value)) * 1000
    return len(rows)


def ensure_request(api: Api, annual: bool) -> dict:
    """기존 요청을 재사용하거나 동일 연도의 최초 월 요청을 생성한다.

    Args: api HTTP, annual 연간 여부. Returns: 요청 DTO.
    Raises: 요청 중복·HTTP 오류. Side Effects: 필요하면 개발 DB 요청 생성.
    """
    filters = {"type": "ANNUAL_PLAN" if annual else "MONTHLY_ACTUAL", "year": 2026}
    if not annual:
        filters["month"] = 1
    found = api.items("/submission-requests", **filters)
    assert len(found) <= 1
    if found:
        return api.request(f"/submission-requests/{found[0]['id']}")
    return api.request("/submission-requests", method="POST", expected=201,
        body={**filters, "message": "01 사업구조 차등형 합성 자료의 로컬 전체 흐름 검증"})


def upload(api: Api, request: dict, entry: dict, token: str, annual: bool) -> dict:
    """동일 원본의 최신 유효 제출을 재사용하거나 새 전체 파일을 접수한다.

    Args: api HTTP, request 요청, entry 원본, token 조직 세션, annual 계획 여부.
    Returns: 파싱 완료 제출 DTO. Raises: 다른 검토대기·파싱·HTTP 오류.
    Side Effects: 필요하면 새 초안과 원본/파싱 객체를 개발 DB에 보존한다.
    """
    payload = entry["sourcePath"].read_bytes()
    digest = hashlib.sha256(payload).hexdigest()
    candidates = api.items("/submissions", token, requestId=request["id"])
    for candidate in candidates:
        if candidate["status"] == "REJECTED":
            continue
        matches = hashlib.sha256(api.original(candidate["id"], token)).hexdigest() == digest
        if candidate["status"] == "PENDING" and not matches:
            raise AssertionError(f"{entry['org_code']} 다른 검토대기 원본을 먼저 처리해야 합니다.")
        if matches:
            return api.http.await_parsing(api.base, token, candidate["id"])
        if candidate["status"] == "APPROVED":
            break
    period = "PLAN" if annual else "01"
    boundary = "monthly-trial-" + uuid.uuid4().hex
    rejected = max((int(item["id"]) for item in candidates if item["status"] == "REJECTED"), default=0)
    response = api.http.call(api.base, f"/api/v1/submission-requests/{request['id']}/submissions",
        token=token, method="POST", key=f"scenario01-{request['id']}-{entry['org_code']}-{rejected}-{digest}",
        body=api.http.multipart(f"{entry['org_code']}_2026-{period}.xlsx", payload, boundary),
        content_type="multipart/form-data; boundary=" + boundary)
    accepted = api.helper.json_response(response, 202)
    result = api.http.await_parsing(api.base, token, accepted["id"])
    assert result["parsing"]["status"] == "SUCCEEDED"
    assert hashlib.sha256(api.original(result["id"], token)).hexdigest() == digest
    return result


def reviews(api: Api, submission_id: str, token: str, *, accept: bool) -> int:
    """합성 자료임을 명시한 소명을 작성하거나 답변된 필수 소명을 수용한다.

    Args: api HTTP, submission_id 제출, token 조직 세션, accept 기획 수용 여부.
    Returns: 필수 검토 개수. Raises: 회차·답변·HTTP 오류.
    Side Effects: 개발 DB의 검증용 소명·판단 이력을 저장한다. 실제 원인 추정 없음.
    """
    items = api.items("/review-items", submissionId=submission_id)
    required = [item for item in items if item["required"]]
    for item in required:
        detail = api.request(f"/review-items/{item['id']}")
        if detail["explanationState"] == "ACCEPTED":
            continue
        body = {"expectedStateToken": detail["stateToken"], "round": detail["currentRound"]}
        if accept:
            api.request(f"/review-items/{item['id']}/decisions", method="POST",
                body={**body, "action": "ACCEPT", "note": "로컬 합성 자료의 원본 금액·기간 대조 및 소명 흐름 검증 완료"})
        elif detail["explanationState"] == "UNANSWERED":
            answer = ("로컬 기능 검증용 소명: 01 사업구조 차등형 2026년 1월 마감 원본을 기준으로 제출했습니다. "
                f"검토 대상은 {detail['year']}년 {detail['month']}월 {detail['scenario']}이며, "
                "원본 셀과 비교 기준을 확인했습니다. 합성 시나리오의 금액 변경·차이로 실제 사업 원인을 진술하는 내용이 아닙니다.")
            api.request(f"/review-items/{item['id']}/answer", token, method="PUT", body={**body, "answer": answer})
    return len(required)


def process_submission(api: Api, request: dict, entry: dict, annual: bool) -> str:
    """한 조직의 전체 파일을 검증·소명·제출·수용·승인까지 연결한다.

    Args: api HTTP, request 요청, entry 원본, annual 계획 여부.
    Returns: 승인 제출 ID. Raises: 준비 상태·원본·HTTP 검증 오류.
    Side Effects: 명시 실행한 로컬 DB 업무 이력을 추가하며 기존 이력을 삭제하지 않는다.
    """
    token = api.session("ORG_MANAGER", entry["org_code"])
    detail = upload(api, request, entry, token, annual)
    sid = detail["id"]
    if detail["status"] == "DRAFT":
        result = api.request(f"/submissions/{sid}/validation", token, method="POST",
            body={"expectedStateToken": detail["stateToken"]})
        detail = result["submission"]
        review_count = reviews(api, sid, token, accept=False)
        detail = api.request(f"/submissions/{sid}", token)
        assert detail["readiness"]["canSubmit"], (entry["org_code"], detail["readiness"])
        detail = api.request(f"/submissions/{sid}/submit", token, method="POST",
            body={"expectedStateToken": detail["stateToken"]})
    else:
        review_count = len(api.items("/review-items", submissionId=sid))
    count = check_values(api, sid, entry, annual)
    if detail["status"] == "PENDING":
        reviews(api, sid, token, accept=True)
        detail = api.request(f"/submissions/{sid}")
        detail = api.request(f"/submissions/{sid}/decisions", method="POST",
            body={"expectedStateToken": detail["stateToken"], "action": "APPROVE"})["submission"]
    assert detail["status"] == "APPROVED"
    print(json.dumps({"stage": "PLAN" if annual else "MONTHLY", "code": entry["org_code"],
        "id": sid, "values": count, "reviews": review_count, "status": detail["status"]}), flush=True)
    return sid


def expected_totals(entry: dict, block: str, month: int | None) -> dict[str, Decimal]:
    """검증 manifest의 원본 상세 합계를 원 단위로 변환한다.

    Args: entry 검증 항목, block plan/outlook, month 월 또는 연간None.
    Returns: 매출·비용·영업이익 Decimal. Raises: manifest 값 오류.
    Side Effects: 없음. 별도 원본 셀 전수 대조와 함께 사용한다.
    """
    values = {}
    for domain in ("revenue", "cost"):
        series = entry["expected"][domain]["periods"][block]["months"]
        selected = series if month is None else [series[month - 1]]
        values[domain] = sum((Decimal(str(value)) for value in selected), Decimal(0)) * 1000
    values["operatingProfit"] = values["revenue"] - values["cost"]
    return values


def compare_totals(actual: dict, expected: dict[str, Decimal]) -> None:
    """서버 십진 문자열 합계를 독립 기대값과 정확히 비교한다.

    Args: actual 서버 totals, expected 기대 금액. Returns: 없음.
    Raises: 금액 불일치. Side Effects: 없음.
    """
    for field, value in expected.items():
        assert Decimal(actual[field]) == value, (field, actual[field], str(value))


def run(args: argparse.Namespace) -> None:
    """원본 대조·계획 교체·확정·1월 승인 마스터의 전체 로컬 흐름을 실행한다.

    Args: args 명시 허용·원본·Contingency·Gap 입력. Returns: 없음.
    Raises: 원본/HTTP/업무/금액 오류. Side Effects: 개발 DB 이력 추가와 콘솔 요약만 출력.
    """
    entries, reference, digests = source_inputs(args.source)
    api = Api(args.base_url)
    try:
        annual_request = ensure_request(api, True)
        old_status = api.request(f"/submission-requests/{annual_request['id']}/annual-master")
        old_master = None if not old_status.get("latestMasterId") else api.request(f"/annual-masters/{old_status['latestMasterId']}")
        plan = api.request("/annual-plans/2026")
        if plan["status"] != "CONFIRMED":
            plan_ids = {entry["org_code"]: process_submission(api, annual_request, entry, True) for entry in entries}
            status = api.request(f"/submission-requests/{annual_request['id']}/annual-master")
            annual = api.request(f"/annual-masters/{status['latestMasterId']}")
            compare_totals(annual["totals"], expected_totals(reference, "plan", None))
            assert {item["organizationCode"]: item["submissionId"] for item in annual["sources"]} == plan_ids
            plan = api.request("/annual-plans/2026")
            plan = api.request("/annual-plans/2026/confirm", method="POST",
                body={"expectedStateToken": plan["stateToken"], "contingencyAmount": args.contingency})
        assert plan["status"] == "CONFIRMED" and Decimal(plan["contingencyAmount"]) == Decimal(args.contingency)
        for scenario, percent, amount in (("ACTUAL", args.actual_percent, args.actual_amount),
                ("ESTIMATE", args.estimate_percent, args.estimate_amount)):
            current = api.request("/gap-thresholds/current")
            threshold = current[scenario.lower()]
            if threshold and Decimal(threshold["percent"]) == Decimal(percent) and Decimal(threshold["amount"]) == Decimal(amount):
                continue
            api.request("/gap-thresholds", method="POST", expected=201,
                body={"expectedStateToken": current["stateToken"], "scenario": scenario, "percent": percent, "amount": amount})
        monthly_request = ensure_request(api, False)
        initial_periods = api.request("/master-periods?year=2026")
        initial_january = next(item for item in initial_periods["items"] if item["month"] == 1)
        monthly_ids = {}
        for entry in entries:
            monthly_ids[entry["org_code"]] = process_submission(api, monthly_request, entry, False)
            if initial_january["latestMasterId"] is None:
                current = api.request("/master-periods?year=2026")
                january_status = next(item for item in current["items"] if item["month"] == 1)
                if january_status["approvedOrganizationCount"] < 17:
                    assert january_status["publicationState"] == "WAITING_INITIAL"
                    assert january_status["latestMasterId"] is None and january_status["totals"] is None
                else:
                    assert january_status["publicationState"] == "PUBLISHED"
        periods = api.request("/master-periods?year=2026")
        january = next(item for item in periods["items"] if item["month"] == 1)
        assert january["publicationState"] == "PUBLISHED" and january["approvedOrganizationCount"] == 17
        master = api.request(f"/masters/{january['latestMasterId']}")
        actual_expected, plan_expected = expected_totals(reference, "outlook", 1), expected_totals(reference, "plan", 1)
        compare_totals(master["totals"], actual_expected)
        compare_totals(master["planTotals"], plan_expected)
        assert {item["organizationCode"]: item["submissionId"] for item in master["sources"]} == monthly_ids
        for field in actual_expected:
            assert Decimal(master["comparisons"][field]["gapAmount"]) == actual_expected[field] - plan_expected[field]
        for organization in master["organizations"]:
            source = next(entry for entry in entries if entry["org_code"] == organization["organizationCode"])
            compare_totals(organization["totals"], expected_totals(source, "outlook", 1))
            compare_totals(organization["planTotals"], expected_totals(source, "plan", 1))
        assert all(item["publicationState"] == "NOT_REQUESTED" for item in periods["items"] if item["month"] > 1)
        if old_master:
            assert api.request(f"/annual-masters/{old_master['id']}") == old_master, "기존 계획 마스터 불변"
        print(json.dumps({"result": "PASS", "masterId": master["id"], "versionLabel": master["versionLabel"],
            "actual": master["totals"], "plan": master["planTotals"], "contingency": plan["contingencyAmount"]}), flush=True)
    finally:
        for path, digest in digests.items():
            assert hashlib.sha256(Path(path).read_bytes()).hexdigest() == digest, "원본 변경 감지"
        print("SOURCE HASHES UNCHANGED: 18", flush=True)


def main() -> None:
    """명시한 실행 범위·업무 입력을 검증한 뒤 로컬 흐름을 시작한다.

    Args: CLI --apply/source/contingency/actual/estimate 기준. Returns: 없음.
    Raises: 옵션·금액·실행 오류. Side Effects: run의 승인된 개발 DB 변경.
    """
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true", required=True)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--base-url", default="http://localhost:5173")
    for name in ("contingency", "actual-percent", "actual-amount", "estimate-percent", "estimate-amount"):
        parser.add_argument("--" + name, required=True)
    args = parser.parse_args()
    url = urlsplit(args.base_url)
    if url.scheme != "http" or url.hostname not in {"localhost", "127.0.0.1"} or url.port is None or url.username or url.password or url.path or url.query or url.fragment:
        parser.error("포트가 있는 루프백 HTTP 출처만 허용합니다.")
    for name in ("contingency", "actual_percent", "actual_amount", "estimate_percent", "estimate_amount"):
        value = Decimal(getattr(args, name))
        if not value.is_finite() or value < 0:
            parser.error("유한한 0 이상의 십진 금액·비율을 입력하세요.")
    run(args)


if __name__ == "__main__":
    main()
