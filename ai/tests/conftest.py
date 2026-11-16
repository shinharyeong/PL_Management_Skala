"""테스트 공통 설정. 실제 OpenAI 호출·실제 ai/.env·실제 출력 폴더를 쓰지 않는다."""
import json
from types import SimpleNamespace

import pytest

from app.services import openai_client

FAKE_KEY = "test-key-not-a-real-key"

FACTS = [
    {"id": "F1", "kind": "매출", "text": "2026년 11월 전사 매출 실적은 557.7억원이며, 계획 대비 +2.3억원(+0.4%)입니다."},
    {"id": "F2", "kind": "영업이익", "text": "2026년 11월 전사 영업이익 실적은 178.3억원이며, 계획 177.0억원 대비 +1.3억원(+0.7%)입니다."},
    {"id": "F3", "kind": "소명", "text": "사업부12의 기타외주용역비 12월 추정 변동은 +0.8억원이며, 수락된 소명 원문은 \"추정을 75백만원 높였습니다.\"입니다."},
    {"id": "F4", "kind": "주의", "text": "사업부20의 11월 변동에서 증가분 80백만원의 원인별 금액은 미분해 상태이므로 원인 금액을 나누어 쓰지 않는다."},
]
GOOD = [
    {"text": "2026년 11월 전사 매출은 557.7억원으로 계획 대비 +2.3억원(+0.4%)입니다.", "fact_ids": ["F1"]},
    {"text": "영업이익은 178.3억원으로 계획 177.0억원 대비 +1.3억원(+0.7%)입니다.", "fact_ids": ["F2"]},
    {"text": "사업부12의 기타외주용역비 12월 추정은 소명에 따라 75백만원 높아졌습니다.", "fact_ids": ["F3"]},
    {"text": "사업부20의 80백만원 증가분은 원인별 금액이 미분해 상태입니다.", "fact_ids": ["F4"]},
]


class FakeClient:
    """OpenAI 클라이언트 모의. 호출 횟수를 센다."""

    def __init__(self, sentences=None, usage=(120, 60), exc=None):
        self.calls, self.kwargs = 0, []
        self._content = json.dumps({"sentences": sentences if sentences is not None else GOOD}, ensure_ascii=False)
        self._usage, self._exc = usage, exc
        self.chat = SimpleNamespace(completions=SimpleNamespace(create=self._create))

    def _create(self, **kw):
        self.calls += 1
        self.kwargs.append(kw)
        if self._exc:
            raise self._exc
        return SimpleNamespace(choices=[SimpleNamespace(message=SimpleNamespace(content=self._content))],
                               usage=SimpleNamespace(prompt_tokens=self._usage[0], completion_tokens=self._usage[1]))


@pytest.fixture(autouse=True)
def isolated(tmp_path, monkeypatch):
    for k in ("OPENAI_API_KEY", "OPENAI_MODEL", "AI_TEMPERATURE", "AI_MAX_OUTPUT_TOKENS", "AI_TIMEOUT_SECONDS", "AI_MAX_RETRIES", "AI_DAILY_CALL_LIMIT"):
        monkeypatch.delenv(k, raising=False)
    monkeypatch.setenv("AI_ENV_FILE", str(tmp_path / "no.env"))          # 실제 ai/.env 를 읽지 않음
    monkeypatch.setenv("OUTPUT_DIR", str(tmp_path / "output"))
    monkeypatch.setenv("AI_CACHE_DIR", str(tmp_path / "cache"))
    monkeypatch.setenv("OPENAI_API_KEY", FAKE_KEY)

    def forbidden(settings):
        raise AssertionError("실제 OpenAI 클라이언트 생성 금지 — 테스트에서 모의 클라이언트를 지정하세요")
    monkeypatch.setattr(openai_client, "make_client", forbidden)
    return tmp_path


@pytest.fixture
def fake(monkeypatch):
    def install(**kw):
        c = FakeClient(**kw)
        monkeypatch.setattr(openai_client, "make_client", lambda settings: c)
        return c
    return install


@pytest.fixture
def facts():
    return [dict(f) for f in FACTS]
