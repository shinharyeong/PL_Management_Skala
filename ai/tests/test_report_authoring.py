"""합성 메모리 PDF와 모의 Responses 클라이언트로 보고 작성 경계를 확인한다."""
from __future__ import annotations

import base64
import json
from types import SimpleNamespace

import pymupdf
import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.api import report_authoring as authoring


@pytest.fixture
def client(monkeypatch):
    """내부 인증을 설정하고 외부 호출을 기본 차단한 앱을 만든다.

    Args: monkeypatch 테스트 환경 변경 도구.
    Returns: 테스트 HTTP 클라이언트.
    Raises: 앱 구성 오류.
    Side Effects: 프로세스 환경·모의 함수만 변경하고 외부 통신하지 않는다.
    """
    monkeypatch.setenv("REPORT_AI_TOKEN", "synthetic-report-token")
    monkeypatch.setattr(authoring, "make_client", lambda settings: (_ for _ in ()).throw(AssertionError("외부 호출 금지")))
    app = FastAPI()
    app.include_router(authoring.router)
    return TestClient(app)


def _pdf(with_text: bool = True) -> bytes:
    """숫자와 비밀 메타데이터가 있는 합성 PDF를 메모리에서 만든다.

    Args: with_text 텍스트 포함 여부.
    Returns: PDF 바이트.
    Raises: PyMuPDF 오류.
    Side Effects: 메모리만 사용하며 파일을 저장하지 않는다.
    """
    with pymupdf.open() as document:
        page = document.new_page(width=400, height=300)
        if with_text:
            page.insert_text((20, 40), "Revenue 12345 Cost 6789", fontsize=14)
        else:
            page.draw_rect(pymupdf.Rect(20, 20, 100, 100), fill=(0, 0, 0))
        document.set_metadata({"title": "private 12345 original"})
        return document.tobytes()


def _mock(monkeypatch, content: dict | str):
    """Responses 호출만 기록하는 가짜 클라이언트를 연결한다.

    Args: monkeypatch 테스트 도구, content 모의 JSON 응답.
    Returns: 호출 인자 목록.
    Raises: 없음.
    Side Effects: 메모리 모의만 변경한다.
    """
    calls = []

    def create(**kwargs):
        """호출 인자를 보존한다. Args: kwargs 요청. Returns: 모의 응답. Raises: 없음. Side Effects: 메모리 목록 추가."""
        calls.append(kwargs)
        return SimpleNamespace(output_text=content if isinstance(content, str) else json.dumps(content))

    monkeypatch.setattr(authoring, "make_client", lambda settings: SimpleNamespace(responses=SimpleNamespace(create=create)))
    return calls


def test_internal_auth_and_missing_provider_keep_manual_flow(client, monkeypatch):
    """잘못된 내부 인증과 미설정 API키를 확인한다.

    Args: client 모의 HTTP, monkeypatch 환경.
    Returns: 없음.
    Raises: 단언 실패.
    Side Effects: 모의 요청만 수행한다.
    """
    assert client.post("/v1/report-authoring/sql", json={"question": "매출"}).status_code == 403
    monkeypatch.delenv("OPENAI_API_KEY", raising=False)
    response = client.post("/v1/report-authoring/sql", headers={"X-Report-Service-Token": "synthetic-report-token"}, json={"question": "매출"})
    assert response.status_code == 200
    assert response.json()["configured"] is False


def test_masked_pdf_sends_no_original_digits_or_metadata(client, monkeypatch):
    """숫자제거 후 새 PDF만 모델 입력에 들어가는지 검사한다.

    Args: client 모의 HTTP, monkeypatch 모의 주입.
    Returns: 없음.
    Raises: PDF/단언 오류.
    Side Effects: 합성 PDF와 외부호출 모의만 메모리에 만든다.
    """
    calls = _mock(monkeypatch, {"blocks": [{"id": "one", "title": "Revenue", "type": "TABLE"}], "warnings": []})
    raw = _pdf()
    response = client.post("/v1/report-authoring/pdf", headers={"X-Report-Service-Token": "synthetic-report-token"}, json={"pdfBase64": base64.b64encode(raw).decode(), "maskNumbers": True})
    result = response.json()
    assert response.status_code == 200 and result["configured"] and result["maskApplied"]
    assert len(calls) == 1 and calls[0]["store"] is False
    encoded = calls[0]["input"][0]["content"][0]["file_data"].split(",", 1)[1]
    masked = base64.b64decode(encoded)
    assert masked != raw
    with pymupdf.open(stream=masked, filetype="pdf") as document:
        text = "".join(page.get_text() for page in document)
        assert "12345" not in text and "6789" not in text
        assert "private" not in json.dumps(document.metadata)
        assert not any(page.get_images() for page in document)
    assert result["pages"][0]["page"] == 1


def test_scan_masking_failure_never_calls_model(client, monkeypatch):
    """텍스트 없는 스캔형 PDF의 마스킹 실패는 미전송으로 처리한다.

    Args: client 모의 HTTP, monkeypatch 모의 주입.
    Returns: 없음.
    Raises: 단언 실패.
    Side Effects: 합성 메모리 문서만 사용한다.
    """
    calls = _mock(monkeypatch, {"blocks": []})
    response = client.post("/v1/report-authoring/pdf", headers={"X-Report-Service-Token": "synthetic-report-token"}, json={"pdfBase64": base64.b64encode(_pdf(False)).decode(), "maskNumbers": True})
    assert response.json()["configured"] is False
    assert calls == []


def test_unmasked_pdf_requires_explicit_false(client, monkeypatch):
    """명시한 비마스킹 요청만 합성 원문을 전달한다.

    Args: client 모의 HTTP, monkeypatch 모의 주입.
    Returns: 없음.
    Raises: 단언 실패.
    Side Effects: 합성 원문을 가짜 모델에만 전달한다.
    """
    calls = _mock(monkeypatch, {"blocks": []})
    raw = _pdf()
    result = client.post("/v1/report-authoring/pdf", headers={"X-Report-Service-Token": "synthetic-report-token"}, json={"pdfBase64": base64.b64encode(raw).decode(), "maskNumbers": False}).json()
    assert result["maskApplied"] is False
    assert base64.b64decode(calls[0]["input"][0]["content"][0]["file_data"].split(",", 1)[1]) == raw


def test_ai_edit_cannot_change_numeric_text(client, monkeypatch):
    """편집 AI가 본문금액을 바꾸면 적용할 초안을 반환하지 않는다.

    Args: client 모의 HTTP, monkeypatch 모의 주입.
    Returns: 없음.
    Raises: 단언 실패.
    Side Effects: 메모리 모의 호출만 수행한다.
    """
    _mock(monkeypatch, {"blocks": [{"id": "one", "text": "매출 999"}]})
    result = client.post("/v1/report-authoring/edit", headers={"X-Report-Service-Token": "synthetic-report-token"}, json={"instruction": "문장 정리", "blocks": [{"id": "one", "text": "매출 100"}]}).json()
    assert result["configured"] is False


def test_ai_edit_protects_sign_unit_order_and_unicode_numbers(client, monkeypatch):
    """같은 숫자 토큰을 유지한 부호·단위·순서 변경도 거부한다.

    Args: client 모의 HTTP, monkeypatch 모의 주입.
    Returns: 없음.
    Raises: 단언 실패.
    Side Effects: 합성 본문과 모델 응답을 메모리에서만 사용한다.
    """
    cases = [
        ("금액 100", "금액 -100"),
        ("금액 (100)", "금액 100"),
        ("비율 10%", "비율 10"),
        ("금액 100원", "금액 100억원"),
        ("매출 100 비용 200", "매출 200 비용 100"),
        ("금액 100 증가", "금액 100 감소"),
        ("비율 ½", "비율 -½"),
        ("금액 １００원", "금액 １００천원"),
        ("매출 증가", "매출 100 증가"),
        ("매출 100 증가", "매출 증가"),
    ]
    for original, proposed in cases:
        _mock(monkeypatch, {"blocks": [{"id": "one", "text": proposed}]})
        result = client.post("/v1/report-authoring/edit", headers={"X-Report-Service-Token": "synthetic-report-token"}, json={"instruction": "문장 정리", "blocks": [{"id": "one", "text": original}]}).json()
        assert result["configured"] is False, (original, proposed)
        assert "숫자가 포함된 본문" in result["warnings"][0]


def test_ai_edit_keeps_numeric_text_while_changing_style_and_order(client, monkeypatch):
    """숫자 본문을 유지한 서식·기간·순서 편집은 허용한다.

    Args: client 모의 HTTP, monkeypatch 모의 주입.
    Returns: 없음.
    Raises: 단언 실패.
    Side Effects: 합성 블록과 모의 응답만 메모리에서 사용한다.
    """
    originals = [
        {"id": "one", "text": "매출 100원", "style": {"fontSize": 12}, "periodRule": "CURRENT_MONTH"},
        {"id": "two", "text": "비용 (50)원"},
    ]
    proposals = [
        {"id": "two", "text": "비용 (50)원"},
        {"id": "one", "text": "매출 100원", "style": {"fontSize": 16}, "periodRule": "YTD"},
    ]
    _mock(monkeypatch, {"blocks": proposals})
    result = client.post("/v1/report-authoring/edit", headers={"X-Report-Service-Token": "synthetic-report-token"}, json={"instruction": "순서와 서식 변경", "blocks": originals}).json()
    assert result["configured"] is True and result["requiresConfirmation"] is True
    assert result["blocks"] == proposals


def test_ai_edit_allows_text_without_numeric_characters(client, monkeypatch):
    """숫자 문자가 없는 본문의 문장 편집은 허용한다.

    Args: client 모의 HTTP, monkeypatch 모의 주입.
    Returns: 없음.
    Raises: 단언 실패.
    Side Effects: 모델 응답을 메모리에서만 대체한다.
    """
    _mock(monkeypatch, {"blocks": [{"id": "one", "text": "추가 검토가 필요합니다."}]})
    result = client.post("/v1/report-authoring/edit", headers={"X-Report-Service-Token": "synthetic-report-token"}, json={"instruction": "문장 정리", "blocks": [{"id": "one", "text": "추가검토 필요"}]}).json()
    assert result["configured"] is True
    assert result["blocks"][0]["text"] == "추가 검토가 필요합니다."


def test_ai_edit_rejects_missing_duplicate_or_malformed_blocks(client, monkeypatch):
    """본문 블록을 제거하거나 식별자를 바꿔 보호 검사를 우회하지 못하게 한다.

    Args: client 모의 HTTP, monkeypatch 모의 주입.
    Returns: 없음.
    Raises: 단언 실패.
    Side Effects: 잘못된 모델 응답만 메모리에서 사용한다.
    """
    originals = [{"id": "one", "text": "금액 100"}, {"id": "two", "text": "설명"}]
    proposals = [
        None,
        {"id": "one", "text": "금액 100"},
        [],
        [{"id": "two", "text": "설명"}],
        [{"id": "new", "text": "금액 100"}, {"id": "two", "text": "설명"}],
        [{"id": "one", "text": "금액 100"}, {"id": "one", "text": "금액 100"}],
        ["one", "two"],
        [{"id": "one", "text": 100}, {"id": "two", "text": "설명"}],
    ]
    for blocks in proposals:
        _mock(monkeypatch, {"blocks": blocks})
        response = client.post("/v1/report-authoring/edit", headers={"X-Report-Service-Token": "synthetic-report-token"}, json={"instruction": "문장 정리", "blocks": originals})
        assert response.status_code == 200
        assert response.json()["configured"] is False


def test_sql_is_a_draft_and_invalid_json_is_explicit_failure(client, monkeypatch):
    """SQL은 미실행 초안이며 잘못된 모델 JSON은 수동경로로 보낸다.

    Args: client 모의 HTTP, monkeypatch 모의 주입.
    Returns: 없음.
    Raises: 단언 실패.
    Side Effects: 모델모의만 호출하고 DB에는 접근하지 않는다.
    """
    calls = _mock(monkeypatch, {"sql": "SELECT SUM(amount) AS amount FROM report_view WHERE metric_code = 'REVENUE'"})
    result = client.post("/v1/report-authoring/sql", headers={"X-Report-Service-Token": "synthetic-report-token"}, json={"question": "매출 합계"}).json()
    assert result["configured"] and result["requiresConfirmation"] and len(calls) == 1
    _mock(monkeypatch, "invalid JSON")
    result = client.post("/v1/report-authoring/sql", headers={"X-Report-Service-Token": "synthetic-report-token"}, json={"question": "매출 합계"}).json()
    assert result["configured"] is False
