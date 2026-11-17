"""사업부 기본 더미 17개를 로컬 API에 접수하고 원본·파싱·조직 격리를 확인한다.

--upload를 명시해야 실행된다. 원본 XLSX는 읽기만 하며 전송 파일명만 API 규칙에 맞춘다.
새 요청·초안·원본·결과는 기존 PostgreSQL 볼륨에 보존하고 별도 파일을 생성하지 않는다.
"""

import argparse
from decimal import Decimal
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import sys
from types import ModuleType
from typing import Any
from urllib.parse import urlsplit
import uuid
import xml.etree.ElementTree as ET
from zipfile import ZipFile


def load_http_helpers() -> ModuleType:
    """기존 로컬 검증 스크립트의 HTTP·메모리 multipart 처리를 재사용한다.

    Args: 없음.
    Returns: smoke-local 모듈.
    Raises: ImportError: 기존 모듈 로딩 실패.
    Side Effects: Python 모듈 메모리 등록; -B 실행으로 캐시 파일을 만들지 않는다.
    """
    spec = importlib.util.spec_from_file_location("skala_smoke_local", Path(__file__).with_name("smoke-local.py"))
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def json_response(response: Any, expected: int) -> dict:
    """비밀 응답을 노출하지 않고 HTTP 상태와 JSON을 확인한다.

    Args: response HTTP 응답, expected 기대 상태 코드.
    Returns: JSON 객체.
    Raises: AssertionError: 상태 불일치. ValueError: JSON 손상.
    Side Effects: 없음. 토큰·원본 값은 출력하지 않는다.
    """
    if response.status != expected:
        try:
            code = json.loads(response.body).get("code", "UNKNOWN")
        except ValueError:
            code = "NON_JSON_RESPONSE"
        raise AssertionError(f"HTTP {response.status}; expected {expected}; code={code}")
    return json.loads(response.body)


def open_session(http: ModuleType, base: str, role: str, code: str | None = None) -> dict:
    """로컬 개발 역할·조직 세션을 메모리에서 취득한다.

    Args: http 기존 HTTP 모듈, base 루프백 출처, role 역할, code 선택 조직.
    Returns: token·actor·organizations 응답.
    Raises: AssertionError: 역할·조직·상태 불일치. OSError: 연결 실패.
    Side Effects: 세션 HTTP 호출; 자격 저장·출력 없음.
    """
    body = {"role": role}
    if code is not None:
        body["organizationCode"] = code
    result = json_response(http.call(base, "/api/dev/session", method="POST",
        body=json.dumps(body).encode(), content_type="application/json"), 200)
    assert result["actor"]["role"] == role
    if code is not None:
        assert result["actor"]["organization"]["code"] == code
    return result


def check_source_cells(path: Path, items: list[dict]) -> int:
    """미리보기의 금액 원문·셀 위치를 원본 OOXML 숫자 캐시와 독립 대조한다.

    Args: path 읽기 전용 원본, items API 첫 페이지의 후보 목록.
    Returns: 대조한 숫자 셀 수.
    Raises: AssertionError: 셀 위치·숫자 원문 불일치. ZIP/XML/Decimal 읽기 오류.
    Side Effects: 원본 ZIP 메모리 읽기만 수행. 수식 재계산·파일 저장 없음.
    """
    ns = {"s": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}
    rel = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    checked = 0
    with ZipFile(path) as archive:
        relationships = {node.get("Id"): node.get("Target") for node in ET.fromstring(archive.read("xl/_rels/workbook.xml.rels"))}
        sheets = {}
        for sheet in ET.fromstring(archive.read("xl/workbook.xml")).findall("s:sheets/s:sheet", ns):
            target = relationships[sheet.get("{" + rel + "}id")]
            sheets[sheet.get("name")] = target.lstrip("/") if target.startswith("/") else "xl/" + target
        for name in {item["sheet"] for item in items if item["amount"] is not None}:
            cells = {cell.get("r"): cell for cell in ET.fromstring(archive.read(sheets[name])).findall(".//s:sheetData/s:row/s:c", ns)}
            for item in items:
                if item["sheet"] != name or item["amount"] is None:
                    continue
                cell = cells[item["cell"]]
                if cell.get("t") not in {None, "n"}:
                    continue
                raw = cell.findtext("s:v", namespaces=ns)
                if raw is None:
                    continue
                assert Decimal(str(item["rawValue"])) == Decimal(raw), f"원본 셀 불일치: {name}!{item['cell']}"
                checked += 1
    return checked


def run(base: str, source: Path, verification_id: str = "baseline") -> None:
    """17개 기본 파일을 동일 원본·멱등키로 접수하고 파싱·권한 결과를 확인한다.

    Args: base 루프백 프런트 출처, source 기본 XLSX와 manifest_baseline.json 폴더,
        verification_id 파서 변경 검증을 구분하는 멱등키 이름. 기본값 baseline.
    Returns: 없음.
    Raises: AssertionError: 파일/조직/기간/권한/저장 불일치 또는 파싱 FAILED.
        OSError/ValueError: 읽기·HTTP·JSON 오류.
    Side Effects: 원본 읽기와 로컬 DB 요청·초안 추가, 상태 폴링, 요약 콘솔 출력.
        기존 행 삭제·원본 파일 변경·보고서/캐시 파일 생성 없음.
    """
    http = load_http_helpers()
    manifest = json.loads((source / "manifest_baseline.json").read_text())
    entries = [entry for entry in manifest["files"] if not entry["is_master"]]
    assert len(entries) == 17 and len({entry["org_code"] for entry in entries}) == 17
    inputs = []
    for entry in entries:
        name = entry["filename"]
        assert Path(name).name == name and name.endswith(".xlsx")
        path = source / name
        assert path.resolve().parent == source.resolve()
        payload = path.read_bytes()
        assert 0 < len(payload) <= 16 * 1024 * 1024
        inputs.append((entry, path, hashlib.sha256(payload).hexdigest()))
    planner = open_session(http, base, "PLANNER")["token"]
    available = json_response(http.call(base, "/api/v1/organizations?active=true&limit=100", token=planner), 200)
    orgs = {item["code"]: item for item in available["items"]}
    for entry, _, _ in inputs:
        assert orgs[entry["org_code"]]["name"] == entry["org_name"]
    requests = json_response(http.call(base, "/api/v1/submission-requests?type=ANNUAL_PLAN&year=2026", token=planner), 200)
    if requests["items"]:
        assert len(requests["items"]) == 1
        request_id = requests["items"][0]["id"]
    else:
        created = json_response(http.call(base, "/api/v1/submission-requests", method="POST", token=planner,
            key="dummy-baseline-2026-plan-v1", content_type="application/json",
            body=json.dumps({"type": "ANNUAL_PLAN", "year": 2026, "month": None,
                "message": "사업부별 기본 더미 17개 원본 업로드·파싱 검증", "dueAt": None}, ensure_ascii=False).encode()), 201)
        request_id = created["id"]
    print(f"REQUEST {request_id}: 2026 ANNUAL_PLAN; files=17; source unchanged", flush=True)
    results = []
    first_id = None
    first_token = None
    for entry, path, digest in inputs:
        code = entry["org_code"]
        session = open_session(http, base, "ORG_MANAGER", code)
        token = session["token"]
        request = json_response(http.call(base, f"/api/v1/submission-requests/{request_id}", token=token), 200)
        assert request["status"] == "OPEN" and request["year"] == 2026
        original = path.read_bytes()
        assert hashlib.sha256(original).hexdigest() == digest
        filename = f"{code}_2026-PLAN.xlsx"
        boundary = "skala-dummy-" + uuid.uuid4().hex
        body = http.multipart(filename, original, boundary)
        key = f"dummy-{verification_id}-2026-{code}-{digest}"
        endpoint = f"/api/v1/submission-requests/{request_id}/submissions"
        if first_id is None:
            rejected = http.call(base, endpoint, method="POST", token=token,
                key="dummy-filename-" + uuid.uuid4().hex,
                body=http.multipart(path.name, original, boundary),
                content_type="multipart/form-data; boundary=" + boundary)
            json_response(rejected, 422)
        response = http.call(base, endpoint, method="POST", token=token, key=key,
            body=body, content_type="multipart/form-data; boundary=" + boundary)
        accepted = json_response(response, 202)
        submission_id = accepted["id"]
        detail = http.await_parsing(base, token, submission_id)
        assert detail["organizationId"] == orgs[code]["id"]
        assert detail["status"] == "DRAFT" and not detail["readiness"]["canSubmit"]
        downloaded = http.call(base, f"/api/v1/submissions/{submission_id}/file", token=token)
        assert downloaded.status == 200 and hashlib.sha256(downloaded.body).hexdigest() == digest
        assert hashlib.sha256(path.read_bytes()).hexdigest() == digest
        preview = json_response(http.call(base, f"/api/v1/submissions/{submission_id}/parsed-records?limit=100", token=token), 200)
        diagnostics = json_response(http.call(base, f"/api/v1/submissions/{submission_id}/diagnostics?limit=100", token=token), 200)
        assert all(item["amount"] is None or isinstance(item["amount"], str) for item in preview["items"])
        source_checks = check_source_cells(path, preview["items"])
        if detail["parsing"]["status"] != "FAILED":
            assert preview["summary"]["recordCount"] > 0 and source_checks > 0
        json_response(http.call(base, f"/api/v1/submissions/{submission_id}", token=planner), 200)
        if first_id is not None:
            assert http.call(base, f"/api/v1/submissions/{first_id}", token=token).status == 404
            assert http.call(base, f"/api/v1/submissions/{submission_id}", token=first_token).status == 404
        else:
            first_id, first_token = submission_id, token
            replay = json_response(http.call(base, endpoint, method="POST", token=token, key=key,
                body=body, content_type="multipart/form-data; boundary=" + boundary), 202)
            assert replay["id"] == submission_id
        result = {"code": code, "submissionId": submission_id, "parsing": detail["parsing"]["status"],
            "parserVersion": detail["parsing"].get("parserVersion"),
            "summary": preview["summary"], "diagnosticCodes": sorted({item["code"] for item in diagnostics["items"]}),
            "originalBytes": "MATCH", "source": "UNCHANGED", "previewSourceChecks": source_checks}
        results.append(result)
        print(json.dumps(result, ensure_ascii=False), flush=True)
    assert all(result["parsing"] != "FAILED" for result in results), "업로드된 파싱 실패 항목을 확인하세요."
    print("PASS 17 uploads, original hashes, idempotency, organization isolation, planner visibility; approval not tested", flush=True)


def main() -> None:
    """명시적 업로드 옵션과 루프백 출처를 확인해 검증을 실행한다.

    Args: 없음. --source, --base-url, --verification-id, --upload 명령행 사용.
    Returns: 없음.
    Raises: SystemExit: 옵션 오류. run의 파일·HTTP·검증 오류.
    Side Effects: --upload에서만 run의 로컬 DB 변경·콘솔 출력; 파일 생성 없음.
    """
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=Path(__file__).resolve().parents[2] / "Data" / "dummy")
    parser.add_argument("--base-url", default="http://localhost:5173")
    parser.add_argument("--verification-id", default="baseline",
                        help="변경한 파서를 새 초안으로 검증할 때 사용하는 식별자")
    parser.add_argument("--upload", action="store_true", help="로컬 DB에 요청·초안을 저장하는 검증 실행")
    args = parser.parse_args()
    parsed = urlsplit(args.base_url)
    if (parsed.scheme != "http" or parsed.hostname not in {"localhost", "127.0.0.1"}
            or parsed.username or parsed.password or parsed.path or parsed.query or parsed.fragment):
        parser.error("포트가 있는 루프백 HTTP 출처를 사용하세요.")
    if parsed.port is None or not args.upload:
        parser.error("로컬 포트와 --upload를 명시해야 합니다.")
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,31}", args.verification_id):
        parser.error("검증 식별자는 영문·숫자로 시작하는 1~32자의 영문·숫자·점·밑줄·하이픈이어야 합니다.")
    run(args.base_url, args.source, args.verification_id)


if __name__ == "__main__":
    main()
