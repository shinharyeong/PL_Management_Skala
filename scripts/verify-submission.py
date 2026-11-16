"""로컬 기본 더미의 최신 원본 일치본을 검증하고 선택적으로 최종 제출한다.

--validate 또는 --submit에서만 DB 명령을 실행한다. 원본과 기존 자료는 보존한다.
검증 요약은 콘솔에만 출력하며 파일·보고서·캐시를 생성하지 않는다.
"""

import argparse
from decimal import Decimal
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
from types import ModuleType
from urllib.parse import urlencode, urlsplit
import uuid
import xml.etree.ElementTree as ET
from zipfile import ZipFile


def helpers() -> ModuleType:
    """기존 로컬 HTTP 함수를 로드한다.
    Args: 없음. Returns: verify-dummy 모듈. Raises: 모듈 로딩 오류.
    Side Effects: 메모리 로딩만 수행하며 -B로 캐시를 만들지 않는다.
    """
    spec = importlib.util.spec_from_file_location("submission_dummy", Path(__file__).with_name("verify-dummy.py"))
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def original_numbers(path: Path) -> dict[tuple[str, str], Decimal]:
    """원본 숫자 셀을 파서와 독립적인 OOXML 읽기로 색인한다.
    Args: path 읽기 전용 XLSX. Returns: (시트,주소)별 원본 수치.
    Raises: ZIP/XML/숫자 읽기 오류. Side Effects: 원본 메모리 읽기만 수행한다.
    """
    ns = {"s": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}
    rel = "{http://schemas.openxmlformats.org/officeDocument/2006/relationships}id"
    result = {}
    with ZipFile(path) as archive:
        relationships = {node.get("Id"): node.get("Target") for node in ET.fromstring(archive.read("xl/_rels/workbook.xml.rels"))}
        for sheet in ET.fromstring(archive.read("xl/workbook.xml")).findall("s:sheets/s:sheet", ns):
            target = relationships[sheet.get(rel)]
            member = target.lstrip("/") if target.startswith("/") else "xl/" + target
            for cell in ET.fromstring(archive.read(member)).findall(".//s:sheetData/s:row/s:c", ns):
                raw = cell.findtext("s:v", namespaces=ns)
                if cell.get("t") in {None, "n"} and raw is not None:
                    result[(sheet.get("name"), cell.get("r"))] = Decimal(raw)
    return result


def page_items(http: ModuleType, check, base: str, token: str, endpoint: str) -> list[dict]:
    """동일 조회의 모든 페이지를 읽는다.
    Args: http HTTP모듈, check 응답검사기, base 루프백, token 세션, endpoint 쿼리 없는 경로.
    Returns: 전체 items. Raises: HTTP/커서 오류. Side Effects: GET만 수행한다.
    """
    result, cursor = [], None
    while True:
        query = {"year": 2026, "scenario": "PLAN", "limit": 100}
        if cursor:
            query["cursor"] = cursor
        page = check(http.call(base, endpoint + "?" + urlencode(query), token=token), 200)
        result.extend(page["items"])
        cursor = page["nextCursor"]
        if not cursor:
            return result


def run(base: str, source: Path, submit: bool, codes: set[str]) -> None:
    """동일 원본의 최신 초안을 검증하고 기획자 조회·독립 금액 대조를 수행한다.
    Args: base 루프백, source 원본 폴더, submit 최종 제출 여부, codes 선택 조직(비면17개).
    Returns: 없음. Raises: 검증/HTTP/원본 오류.
    Side Effects: 로컬 DB 검증·선택적 PENDING 제출·콘솔 요약; 원본·과거 제출·파일은 변경하지 않는다.
    """
    helper = helpers()
    http, check = helper.load_http_helpers(), helper.json_response
    planner = helper.open_session(http, base, "PLANNER")["token"]
    requests = check(http.call(base, "/api/v1/submission-requests?year=2026&type=ANNUAL_PLAN", token=planner), 200)["items"]
    assert len(requests) == 1
    request_id = requests[0]["id"]
    entries = [entry for entry in json.loads((source / "manifest_baseline.json").read_text())["files"] if not entry["is_master"]]
    assert codes <= {entry["org_code"] for entry in entries}
    total = 0
    for entry in entries:
        code = entry["org_code"]
        if codes and code not in codes:
            continue
        path = source / entry["filename"]
        assert path.resolve().parent == source.resolve()
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        token = helper.open_session(http, base, "ORG_MANAGER", code)["token"]
        summaries = check(http.call(base, "/api/v1/submissions?" + urlencode({"requestId": request_id, "limit": 1}), token=token), 200)["items"]
        assert len(summaries) == 1
        submission_id = summaries[0]["id"]
        endpoint = f"/api/v1/submissions/{submission_id}"
        original = http.call(base, endpoint + "/file", token=token)
        assert original.status == 200 and hashlib.sha256(original.body).hexdigest() == digest
        detail = check(http.call(base, endpoint, token=token), 200)
        assert detail["parsing"]["parserVersion"] == "stdlib-bridge/1.3"
        if detail["status"] == "DRAFT":
            response = http.call(base, endpoint + "/validation", method="POST", token=token,
                key="verify-validate-" + uuid.uuid4().hex, content_type="application/json",
                body=json.dumps({"expectedStateToken": detail["stateToken"]}).encode())
            detail = check(response, 200)["submission"]
            if not detail["readiness"]["canSubmit"]:
                print(json.dumps({"code": code, "id": submission_id, "blockers": detail["readiness"]["blockers"][:12]}, ensure_ascii=False), flush=True)
                raise AssertionError("최종 제출 준비 조건 실패")
        assert detail["status"] in {"DRAFT", "PENDING"}
        facts = page_items(http, check, base, planner, endpoint + "/financial-values")
        assert len(facts) == 708 and len({item["lineId"] for item in facts}) == 59
        raw = original_numbers(path)
        sums, kind_sums = {}, {}
        for item in facts:
            address = (item["source"]["sheet"], item["source"]["cell"])
            assert Decimal(item["amount"]) == raw[address] * 1000, f"원본 환산 불일치: {code} {address}"
            key = (item["reportItem"]["id"], item["month"])
            sums[key] = sums.get(key, Decimal(0)) + Decimal(item["amount"])
            kind_key = (item["reportItem"]["kind"], item["month"])
            kind_sums[kind_key] = kind_sums.get(kind_key, Decimal(0)) + Decimal(item["amount"])
        reports = page_items(http, check, base, planner, endpoint + "/report-values")
        assert len(reports) == 1836
        for value in reports:
            assert value["valueStatus"] == "AVAILABLE" and value["amount"] is not None
            if value["kind"] != "DERIVED":
                assert Decimal(value["amount"]) == sums.get((value["reportItemId"], value["month"]), Decimal(0))
            else:
                revenue = kind_sums.get(("REVENUE", value["month"]), Decimal(0))
                cost = kind_sums.get(("COST", value["month"]), Decimal(0))
                assert Decimal(value["amount"]) == {"매출 합계": revenue, "영업비용 합계": cost, "영업이익": revenue - cost}[value["reportItemName"]]
        for item in [facts[0], facts[-1]]:
            cell = check(http.call(base, endpoint + "/source-cells?" + urlencode({"sheet": item["source"]["sheet"], "cell": item["source"]["cell"]}), token=planner), 200)
            assert Decimal(cell["normalizedAmount"]) == Decimal(item["amount"])
        if submit and detail["status"] == "DRAFT":
            body = json.dumps({"expectedStateToken": detail["stateToken"]}).encode()
            key = "verify-submit-" + uuid.uuid4().hex
            detail = check(http.call(base, endpoint + "/submit", method="POST", token=token, key=key, content_type="application/json", body=body), 200)
            replay = http.call(base, endpoint + "/submit", method="POST", token=token, key=key, content_type="application/json", body=body)
            assert check(replay, 200)["id"] == submission_id and replay.headers.get("idempotency-replayed") == "true"
            assert detail["status"] == "PENDING" and detail["seq"] == 1
        assert hashlib.sha256(path.read_bytes()).hexdigest() == digest
        total += len(facts)
        print(json.dumps({"code": code, "id": submission_id, "status": detail["status"], "lines": 59, "values": len(facts), "reports": len(reports), "source": "MATCH"}), flush=True)
    print(f"PASS original-to-database values={total}; planner visibility; source unchanged", flush=True)


def main() -> None:
    """명시한 로컬 DB 검증 범위만 실행한다.
    Args: CLI --validate/--submit, --codes, --base-url, --source. Returns: 없음.
    Raises: SystemExit 옵션 오류, run 검증 오류. Side Effects: run과 동일; 별도 파일 없음.
    """
    parser = argparse.ArgumentParser(description=__doc__)
    actions = parser.add_mutually_exclusive_group(required=True)
    actions.add_argument("--validate", action="store_true")
    actions.add_argument("--submit", action="store_true")
    parser.add_argument("--codes", nargs="*", default=[])
    parser.add_argument("--base-url", default="http://localhost:5173")
    parser.add_argument("--source", type=Path, default=Path(__file__).resolve().parents[2] / "Data" / "dummy")
    args = parser.parse_args()
    url = urlsplit(args.base_url)
    if url.scheme != "http" or url.hostname not in {"localhost", "127.0.0.1"} or url.port is None or url.username or url.password or url.path or url.query or url.fragment:
        parser.error("포트가 있는 루프백 HTTP 출처만 허용합니다.")
    run(args.base_url, args.source, args.submit, set(args.codes))


if __name__ == "__main__":
    main()
