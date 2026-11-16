"""narratives 서비스 테스트(모의 클라이언트만 사용, 실제 OpenAI 호출 없음)."""
import json
from types import SimpleNamespace

from app.services import narratives as nv
from tests.conftest import FAKE_KEY

FACTS = [
    {"id": "F1", "kind": "소명", "text": "사업부10의 일반광고비 12월 추정 소명: 광고 집행 일정 변경으로 비용 60백만원을 11월에서 12월 추정으로 이연했습니다."},
    {"id": "F2", "kind": "소명", "text": "사업부5의 기본급 11월 실적 소명: 운영 인력 증원으로 11월 기본급이 증가했습니다."},
]
TPL = {"F1": {"cause": "광고 집행 일정 변경으로 비용 60백만원을 11월에서 12월 추정으로 이연했습니다.", "impact": ""},
       "F2": {"cause": "운영 인력 증원으로 11월 기본급이 증가했습니다.", "impact": ""}}


class ItemsClient:
    def __init__(self, items, exc=None):
        self.calls, self.kwargs = 0, []
        self._content, self._exc = json.dumps({"items": items}, ensure_ascii=False), exc
        self.chat = SimpleNamespace(completions=SimpleNamespace(create=self._create))

    def _create(self, **kw):
        self.calls += 1
        self.kwargs.append(kw)
        if self._exc:
            raise self._exc
        return SimpleNamespace(choices=[SimpleNamespace(message=SimpleNamespace(content=self._content))],
                               usage=SimpleNamespace(prompt_tokens=300, completion_tokens=90))


def _install(monkeypatch, items, **kw):
    c = ItemsClient(items, **kw)
    monkeypatch.setattr(nv.openai_client, "make_client", lambda s: c)
    return c


GOOD = [{"key": "F1", "cause": "광고 집행 일정 변경으로 비용 60백만원을 11월에서 12월 추정으로 이연했습니다.", "impact": "", "fact_ids": ["F1"]},
        {"key": "F2", "cause": "운영 인력 증원으로 11월 기본급이 증가했습니다.", "impact": "", "fact_ids": ["F2"]}]


def test_normal_ai_items(monkeypatch):
    c = _install(monkeypatch, GOOD)
    r = nv.run_items("causes", FACTS, TPL, allow_call=True)
    assert c.calls == 1 and r["counts"] == {"ai": 2, "template": 0}
    kw = c.kwargs[0]
    assert kw["max_tokens"] == nv.TASKS["causes"]["max_tokens"] and kw["temperature"] == 0.2 and "reasoning" not in kw
    assert set(json.loads(kw["messages"][1]["content"])["facts"][0]) == {"id", "kind", "text"}     # 사실 목록만 전송


def test_wrong_number_falls_back_to_template(monkeypatch):
    bad = [dict(GOOD[0], cause="광고 집행 일정 변경으로 비용 90백만원을 11월에서 12월 추정으로 이연했습니다."), GOOD[1]]
    _install(monkeypatch, bad)
    r = nv.run_items("causes", FACTS, TPL, allow_call=True)
    assert r["items"]["F1"]["source"] == "template" and "NUMBER_NOT_IN_FACTS" in r["items"]["F1"]["reason"]
    assert r["items"]["F1"]["cause"] == TPL["F1"]["cause"] and r["items"]["F2"]["source"] == "ai"


def test_cause_word_not_in_source_is_replaced(monkeypatch):
    bad = [GOOD[0], dict(GOOD[1], cause="운영 인력 증원은 일회성 비용으로 12월에 회복될 예정입니다.")]   # 원문에 없는 원인 키워드(일회성·회복)
    _install(monkeypatch, bad)
    r = nv.run_items("causes", FACTS, TPL, allow_call=True)
    assert r["items"]["F2"]["source"] == "template" and "CAUSE_WORD_NOT_IN_SOURCE" in r["items"]["F2"]["reason"]
    assert r["items"]["F1"]["source"] == "ai"       # 원문에 '이연' 이 있으므로 통과


def test_impact_requires_source_cue(monkeypatch):
    bad = [GOOD[0], dict(GOOD[1], impact="내년에도 증가가 이어집니다.")]
    _install(monkeypatch, bad)
    r = nv.run_items("causes", FACTS, TPL, allow_call=True)
    assert r["items"]["F2"]["source"] == "template" and "IMPACT_WITHOUT_SOURCE_CUE" in r["items"]["F2"]["reason"]


def test_missing_key_and_unknown_fact_id(monkeypatch):
    _install(monkeypatch, [dict(GOOD[0], fact_ids=["F9"])])
    r = nv.run_items("causes", FACTS, TPL, allow_call=True)
    assert r["items"]["F1"]["reason"].startswith("UNKNOWN_FACT_ID") and r["items"]["F2"]["reason"] == "MISSING_IN_AI_OUTPUT"


def test_cache_hit_zero_calls(monkeypatch):
    c = _install(monkeypatch, GOOD)
    nv.run_items("causes", FACTS, TPL, allow_call=True)
    r = nv.run_items("causes", FACTS, TPL, allow_call=True)
    assert c.calls == 1 and r["cached"] is True and r["counts"]["ai"] == 2
    r2 = nv.run_items("causes", FACTS, TPL, allow_call=False)          # 호출 금지여도 캐시는 읽는다
    assert r2["cached"] is True and c.calls == 1


def test_default_no_call_uses_templates(monkeypatch):
    r = nv.run_items("causes", FACTS, TPL)                              # allow_call 기본 False
    assert r["counts"] == {"ai": 0, "template": 2} and r["items"]["F1"]["reason"] == "AI_NOT_USED"


def test_daily_limit(monkeypatch, isolated):
    monkeypatch.setenv("AI_DAILY_CALL_LIMIT", "0")
    c = _install(monkeypatch, GOOD)
    r = nv.run_items("causes", FACTS, TPL, allow_call=True)
    assert c.calls == 0 and r["items"]["F1"]["reason"] == "DAILY_LIMIT_EXCEEDED"


def test_no_key(monkeypatch):
    monkeypatch.delenv("OPENAI_API_KEY", raising=False)
    c = _install(monkeypatch, GOOD)
    r = nv.run_items("causes", FACTS, TPL, allow_call=True)
    assert c.calls == 0 and r["items"]["F2"]["reason"] == "NO_API_KEY"


def test_failure_no_retry_and_log_has_no_key(monkeypatch, isolated):
    c = _install(monkeypatch, GOOD, exc=TimeoutError())
    r = nv.run_items("causes", FACTS, TPL, allow_call=True)
    assert c.calls == 1 and r["error"] == "TimeoutError" and r["counts"]["ai"] == 0
    raw = (isolated / "output" / "ai_call_log.json").read_text(encoding="utf-8")
    assert FAKE_KEY not in raw and "광고 집행" not in raw and '"task": "causes"' in raw


def test_split_cause_impact_never_invents():
    t = "사무 공간 일부의 임차 계약을 종료했습니다. 12월 추정에 반영했습니다. 실제 집행은 아직 미확정입니다."
    r = nv.split_cause_impact(t)
    assert r["cause"] == "사무 공간 일부의 임차 계약을 종료했습니다."
    assert r["impact"] in t and "12월 추정" in r["impact"]
    assert nv.split_cause_impact("운영 인력 증원으로 증가했습니다.")["impact"] == ""


def test_key_messages_wrapper_uses_order_prompt(monkeypatch, facts, fake):
    c = fake()
    r = nv.key_messages(facts, [], "final", allow_call=True)
    assert c.calls == 1 and r["prompt_version"] == nv.KM_VERSION
    assert "전체 결과 → 주요 원인 → 향후 영향" in c.kwargs[0]["messages"][0]["content"]
