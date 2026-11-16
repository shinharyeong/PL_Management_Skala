"""로컬 Compose의 실제 PostgreSQL·Python 연결을 HTTP로 검증한다.

합성 양식은 메모리에서만 전송한다. 사용자 원본·자격 증명·응답 본문은 출력하지 않는다.
실행하면 개발 DB에 초안 두 개(정상/손상 예시)가 추가되며 기존 자료를 삭제하지 않는다.
"""

import argparse
import json
import time
import uuid
from dataclasses import dataclass
from urllib.error import HTTPError
from urllib.parse import urlsplit
from urllib.request import ProxyHandler, Request, build_opener


@dataclass(frozen=True)
class Response:
    """HTTP 응답 상태·헤더·메모리 본문. 자동 생성 메서드는 외부 부작용이 없다."""

    status: int
    headers: dict[str, str]
    body: bytes


def call(
    base: str,
    path: str,
    *,
    method: str = "GET",
    token: str | None = None,
    body: bytes | None = None,
    content_type: str | None = None,
    key: str | None = None,
) -> Response:
    """동일 로컬 출처로 요청하고 오류 응답도 검증 가능한 값으로 반환한다.

    Args:
        base: loopback HTTP 출처.
        path: 고정 API 상대 경로.
        method: HTTP 메서드.
        token: 개발 세션 토큰. 메모리에만 사용한다.
        body: 직렬화한 요청 바이트 또는 없음.
        content_type: 본문 유형 또는 없음.
        key: 멱등키 또는 없음.
    Returns:
        상태·소문자 헤더·바이트 본문의 Response.
    Raises:
        OSError: 서버 연결 또는 네트워크 시간 초과.
    Side Effects:
        로컬 HTTP를 호출하며 POST는 개발 DB에 자료를 저장할 수 있다.
    """
    headers = {"Origin": base}
    if token is not None:
        headers["Authorization"] = "Bearer " + token
    if content_type is not None:
        headers["Content-Type"] = content_type
    if key is not None:
        headers["Idempotency-Key"] = key
    request = Request(base + path, data=body, headers=headers, method=method)
    try:
        # 로컬 개발 요청이 시스템 프록시로 전달되지 않게 직접 연결한다.
        response = build_opener(ProxyHandler({})).open(request, timeout=20)
    except HTTPError as error:
        response = error
    with response:
        return Response(
            response.status,
            {key.lower(): value for key, value in response.headers.items()},
            response.read(),
        )


def expect(response: Response, status: int, label: str) -> None:
    """응답 본문·비밀값을 출력하지 않고 기대 HTTP 상태를 검사한다.

    Args:
        response: HTTP 결과.
        status: 기대 HTTP 코드.
        label: 원본 값이 없는 고정 검증 이름.
    Returns:
        없음.
    Raises:
        AssertionError: 실제 상태가 기대와 다른 경우.
    Side Effects:
        성공 시 고정된 검증 이름을 콘솔에 출력한다.
    """
    assert response.status == status, f"{label}: expected {status}, got {response.status}"
    print(f"PASS {label} ({status})")


def session(base: str, role: str) -> str:
    """개발 전용 역할 선택 API에서 메모리 토큰을 받는다.

    Args:
        base: 로컬 출처.
        role: PLANNER 또는 ORG_MANAGER.
    Returns:
        화면과 동일한 개발 세션 토큰.
    Raises:
        AssertionError: 세션 상태·역할이 예상과 다른 경우.
        OSError: HTTP 연결 실패.
    Side Effects:
        세션 API 호출. 토큰을 저장하거나 출력하지 않는다.
    """
    response = call(
        base, "/api/dev/session", method="POST",
        body=json.dumps({"role": role}).encode(), content_type="application/json",
    )
    expect(response, 200, "session " + role)
    data = json.loads(response.body)
    assert data["actor"]["role"] == role
    return data["token"]


def multipart(filename: str, workbook: bytes, boundary: str) -> bytes:
    """한 개의 XLSX 파일 part를 메모리에서 조립한다.

    Args:
        filename: 서버에서 제공한 예시 파일명.
        workbook: 원본 바이트.
        boundary: 생성한 안전 ASCII 경계값.
    Returns:
        multipart 본문 바이트.
    Raises:
        없음.
    Side Effects:
        없음. 임시 파일을 만들지 않는다.
    """
    prefix = (
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; "
        f"filename=\"{filename}\"\r\nContent-Type: "
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet\r\n\r\n"
    ).encode()
    return prefix + workbook + f"\r\n--{boundary}--\r\n".encode()


def await_parsing(base: str, token: str, submission_id: str) -> dict:
    """초안을 폴링해 현재 파싱 시도의 종료를 기다린다.

    Args:
        base: 로컬 출처.
        token: 조직 담당자 토큰.
        submission_id: 접수한 초안 ID.
    Returns:
        종료된 파싱 시도를 포함하는 제출 DTO.
    Raises:
        AssertionError: 조회 실패 또는 100초 안에 종료되지 않은 경우.
    Side Effects:
        읽기 HTTP 호출과 0.5초 간격 대기. 로그·파일 쓰기 없음.
    """
    deadline = time.monotonic() + 100
    while time.monotonic() < deadline:
        response = call(base, "/api/v1/submissions/" + submission_id, token=token)
        assert response.status == 200, "polling failed"
        data = json.loads(response.body)
        if data["parsing"]["status"] not in {"QUEUED", "RUNNING"}:
            return data
        time.sleep(0.5)
    raise AssertionError("parser did not finish within 100 seconds")


def run(base: str) -> None:
    """브라우저와 같은 출처로 정상·중복·권한·오류 흐름을 실제 검증한다.

    Args:
        base: localhost 또는 127.0.0.1의 프런트 출처.
    Returns:
        없음.
    Raises:
        AssertionError: API·파서·저장 계약이 기대와 다른 경우.
        OSError: HTTP 연결 실패.
    Side Effects:
        개발 DB에 정상/손상 예시 초안을 추가하고 검증 결과만 콘솔로 출력한다.
        Data 및 임시 XLSX 파일은 읽거나 쓰지 않는다.
    """
    planner = session(base, "PLANNER")
    manager = session(base, "ORG_MANAGER")
    expect(call(base, "/api/v1/me"), 401, "anonymous API denied")
    me_response = call(base, "/api/v1/me", token=manager)
    expect(me_response, 200, "organization identity")
    organization_code = json.loads(me_response.body)["organization"]["code"]
    requests_response = call(base, "/api/v1/submission-requests?type=ANNUAL_PLAN&year=2027", token=manager)
    expect(requests_response, 200, "seeded annual request")
    requests = json.loads(requests_response.body)["items"]
    assert len(requests) == 1, "expected one 2027 demo annual request"
    request_id = requests[0]["id"]
    template = call(base, "/api/dev/template?requestId=" + request_id, token=manager)
    expect(template, 200, "generated XLSX template")
    assert template.body.startswith(b"PK"), "template is not OOXML ZIP"
    filename = f"{organization_code}_2027-PLAN.xlsx"
    boundary = "skala-" + uuid.uuid4().hex
    body = multipart(filename, template.body, boundary)
    content_type = "multipart/form-data; boundary=" + boundary
    path = "/api/v1/submission-requests/" + request_id + "/submissions"
    key = "smoke-" + uuid.uuid4().hex
    accepted = call(base, path, method="POST", token=manager, body=body, content_type=content_type, key=key)
    expect(accepted, 202, "original upload")
    submission_id = json.loads(accepted.body)["id"]
    replay = call(base, path, method="POST", token=manager, body=body, content_type=content_type, key=key)
    expect(replay, 202, "idempotent replay")
    assert json.loads(replay.body)["id"] == submission_id
    assert replay.headers.get("idempotency-replayed") == "true"
    changed = call(base, path, method="POST", token=manager,
                   body=multipart(filename, template.body + b"\n", boundary), content_type=content_type, key=key)
    expect(changed, 409, "changed payload cannot reuse key")
    expect(call(base, path, method="POST", token=planner, body=body, content_type=content_type,
                key="planner-" + uuid.uuid4().hex), 403, "planner cannot upload")
    result = await_parsing(base, manager, submission_id)
    assert result["parsing"]["status"] == "SUCCEEDED", "valid demo template must parse successfully"
    assert result["status"] == "DRAFT" and not result["readiness"]["canSubmit"]
    print("PASS real Python parsing; draft remains unapproved")
    preview = call(base, "/api/v1/submissions/" + submission_id + "/parsed-records?limit=100", token=manager)
    expect(preview, 200, "parsed amount preview")
    data = json.loads(preview.body)
    amounts = [item["amount"] for item in data["items"] if item["amount"] is not None]
    assert amounts and all(isinstance(amount, str) for amount in amounts)
    assert {"1000000.50", "300000.25", "0"}.issubset(amounts), "fixture decimals or zero were changed"
    original = call(base, "/api/v1/submissions/" + submission_id + "/file", token=manager)
    expect(original, 200, "original download")
    assert original.body == template.body, "downloaded bytes differ from upload"
    expect(call(base, "/api/v1/submissions/" + submission_id, token=planner), 200, "planner sees received draft")
    expect(call(base, "/api/v1/submissions?organizationId=999999", token=manager), 403, "cross-organization scope denied")
    invalid_name = multipart("OTHER_2027-PLAN.xlsx", template.body, boundary)
    expect(call(base, path, method="POST", token=manager, body=invalid_name, content_type=content_type,
                key="filename-" + uuid.uuid4().hex), 422, "wrong organization filename denied")
    corrupt = call(base, path, method="POST", token=manager,
                   body=multipart(filename, b"PK\x03\x04invalid-workbook", boundary),
                   content_type=content_type, key="corrupt-" + uuid.uuid4().hex)
    expect(corrupt, 202, "asynchronous invalid workbook reception")
    failed = await_parsing(base, manager, json.loads(corrupt.body)["id"])
    assert failed["parsing"]["status"] == "FAILED" and not failed["parsing"]["retryAllowed"]
    print("PASS invalid workbook fails without approval or retry")
    print("PASS local PostgreSQL / backend / Python / reverse proxy integration")


def main() -> None:
    """명령행의 로컬 출처를 확인하고 통합 검증을 시작한다.

    Args:
        없음. --base-url 명령행을 읽으며 기본값은 http://localhost:5173이다.
    Returns:
        없음.
    Raises:
        SystemExit: 비로컬 출처 또는 잘못된 CLI.
        AssertionError: 검증 실패.
    Side Effects:
        run에 명시한 개발 DB 변경과 콘솔 출력.
    """
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://localhost:5173")
    arguments = parser.parse_args()
    base = arguments.base_url.rstrip("/")
    parsed = urlsplit(base)
    if (
        parsed.scheme != "http"
        or parsed.hostname not in {"localhost", "127.0.0.1"}
        or parsed.path or parsed.query or parsed.fragment
        or parsed.username is not None or parsed.password is not None
    ):
        parser.error("base-url must be a loopback HTTP origin")
    run(base)


if __name__ == "__main__":
    main()
