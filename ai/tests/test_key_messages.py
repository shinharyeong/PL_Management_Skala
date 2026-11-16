import json

import pytest
from fastapi.testclient import TestClient

from app.config import get_settings
from app.main import app
from app.services import key_messages as svc
from tests.conftest import FAKE_KEY, FACTS, GOOD

client = TestClient(app)


def _post(facts, status="final"):
    return client.post("/v1/report/key-messages", json={"status": status, "facts": facts})


def _log(tmp_path):
    return json.loads((tmp_path / "output" / "ai_call_log.json").read_text(encoding="utf-8"))


def test_a_normal_response(fake, facts, isolated):
    c = fake()
    r = _post(facts)
    assert r.status_code == 200
    body = r.json()
    assert [s["source"] for s in body["sentences"]] == ["ai"] * 4
    assert all(s["reason"] is None for s in body["sentences"])
    assert body["usage"] == {"input_tokens": 120, "output_tokens": 60, "cached": False}
    assert c.calls == 1
    kw = c.kwargs[0]
    assert kw["model"] == "gpt-4o-mini" and kw["temperature"] == 0.2 and kw["max_tokens"] == 500
    assert "reasoning" not in kw and "reasoning_effort" not in kw          # gpt-4o-mini 는 추론 설정 미지원
    sent = json.loads(kw["messages"][1]["content"])["facts"]
    assert set(sent[0]) == {"id", "kind", "text"}                            # 사실 목록만 전송
    log = _log(isolated)["calls"][-1]                                        # 토큰 로그(키·원문 없음)
    assert log["outcome"] == "success" and log["input_tokens"] == 120 and log["output_tokens"] == 60
    raw = (isolated / "output" / "ai_call_log.json").read_text(encoding="utf-8")
    assert FAKE_KEY not in raw and "557.7" not in raw


def test_b_wrong_number_falls_back_per_sentence(fake, facts):
    bad = [dict(g) for g in GOOD]
    bad[0] = {"text": "2026년 11월 전사 매출은 559.9억원입니다.", "fact_ids": ["F1"]}
    fake(sentences=bad)
    body = _post(facts).json()
    assert body["sentences"][0]["source"] == "template"
    assert body["sentences"][0]["reason"].startswith("NUMBER_NOT_IN_FACTS:559.9")
    assert [s["source"] for s in body["sentences"][1:]] == ["ai", "ai", "ai"]    # 나머지는 그대로


def test_b2_forbidden_word_unknown_fact_and_undistributed(fake, facts):
    bad = [
        {"text": "영업이익은 178.3억원으로 전망됩니다.", "fact_ids": ["F2"]},
        {"text": "매출은 557.7억원입니다.", "fact_ids": ["F9"]},
        {"text": "사업부20의 증가는 80백만원입니다.", "fact_ids": ["F4"]},
        {"text": "", "fact_ids": ["F1"]},
        GOOD[0],
    ]
    fake(sentences=bad)
    s = _post(facts).json()["sentences"]
    reasons = [x["reason"] for x in s]
    assert any("FORBIDDEN_WORD" in (r or "") for r in reasons)
    assert any("UNKNOWN_FACT_ID" in (r or "") for r in reasons)
    assert any("UNDISTRIBUTED_NOT_STATED" in (r or "") for r in reasons)
    assert len(s) <= 5 and all(x["text"].strip() for x in s)


def test_b3_more_than_five_sentences_trimmed(fake, facts):
    fake(sentences=GOOD + [GOOD[0], GOOD[1]])
    assert len(_post(facts).json()["sentences"]) <= 5


def test_c_cache_hit_makes_no_call(fake, facts):
    c = fake()
    first = _post(facts).json()
    second = _post(facts).json()
    assert c.calls == 1
    assert second["usage"] == {"input_tokens": 0, "output_tokens": 0, "cached": True}
    assert [s["text"] for s in second["sentences"]] == [s["text"] for s in first["sentences"]]
    f2 = [dict(f) for f in facts]
    f2[0]["text"] = f2[0]["text"].replace("557.7", "558.0")                  # 사실이 바뀌면 캐시 미적중
    _post(f2)
    assert c.calls == 2


def test_c2_cache_key_includes_model_and_prompt_version(monkeypatch):
    h = svc.facts_hash(FACTS)
    k1 = svc.cache_key("gpt-4o-mini", h)
    assert k1 == svc.cache_key("gpt-4o-mini", h)                             # 같은 입력은 같은 키
    assert k1 != svc.cache_key("other-model", h)                             # 모델이 다르면 다른 키
    monkeypatch.setattr(svc, "PROMPT_VERSION", "key-messages-v2")
    assert k1 != svc.cache_key("gpt-4o-mini", h)                             # 프롬프트 버전이 다르면 다른 키


def test_d_daily_limit_exceeded(fake, facts, monkeypatch):
    monkeypatch.setenv("AI_DAILY_CALL_LIMIT", "1")
    c = fake()
    assert _post(facts).json()["sentences"][0]["source"] == "ai"
    other = [dict(f) for f in facts]
    other[0]["text"] += " 추가"
    body = _post(other).json()
    assert c.calls == 1
    assert all(s["source"] == "template" for s in body["sentences"])
    assert all(s["reason"] == "DAILY_LIMIT_EXCEEDED" for s in body["sentences"])


def test_d2_limit_zero_never_calls(fake, facts, monkeypatch):
    monkeypatch.setenv("AI_DAILY_CALL_LIMIT", "0")
    c = fake()
    assert _post(facts).status_code == 200 and c.calls == 0


def test_e_no_key_uses_template(fake, facts, monkeypatch):
    monkeypatch.delenv("OPENAI_API_KEY")
    c = fake()
    body = _post(facts).json()
    assert c.calls == 0
    assert body["sentences"] and all(s["source"] == "template" and s["reason"] == "NO_API_KEY" for s in body["sentences"])
    assert body["usage"] == {"input_tokens": 0, "output_tokens": 0, "cached": False}


def test_e2_call_failure_uses_template_without_retry(fake, facts):
    c = fake(exc=TimeoutError("boom"))
    body = _post(facts).json()
    assert c.calls == 1                                                       # 재시도 없음
    assert all(s["source"] == "template" and s["reason"] == "AI_CALL_FAILED:TimeoutError" for s in body["sentences"])


def test_f_health(isolated):
    r = client.get("/health")                                                 # make_client 가 호출되면 테스트가 실패함
    assert r.status_code == 200
    assert r.json() == {"status": "ok", "model": "gpt-4o-mini", "openai_key_present": True}
    assert FAKE_KEY not in r.text


def test_f2_health_without_key(monkeypatch):
    monkeypatch.delenv("OPENAI_API_KEY")
    assert client.get("/health").json()["openai_key_present"] is False


def test_request_validation():
    assert client.post("/v1/report/key-messages", json={"status": "x", "facts": FACTS}).status_code == 422
    assert client.post("/v1/report/key-messages", json={"status": "final", "facts": []}).status_code == 422
    dup = [FACTS[0], dict(FACTS[0])]
    assert client.post("/v1/report/key-messages", json={"status": "final", "facts": dup}).status_code == 422
    bad = [{**FACTS[0], "kind": "엑셀"}]
    assert client.post("/v1/report/key-messages", json={"status": "final", "facts": bad}).status_code == 422


def test_env_file_has_priority_and_logs_source_only(tmp_path, monkeypatch, caplog):
    envf = tmp_path / "a.env"
    envf.write_text("OPENAI_API_KEY=key-from-dotenv-file\n", encoding="utf-8")
    monkeypatch.setenv("AI_ENV_FILE", str(envf))
    monkeypatch.setenv("OPENAI_API_KEY", "key-from-shell-env")
    s = get_settings()
    assert s.openai_api_key == "key-from-dotenv-file" and s.key_source == "ai/.env"      # .env 가 우선
    from app.config import log_key_source
    with caplog.at_level("INFO", logger="ai.config"):
        log_key_source(s)
    assert "source=ai/.env" in caplog.text
    assert "key-from-dotenv-file" not in caplog.text and "key-from-shell-env" not in caplog.text
    assert "key-from-dotenv-file" not in repr(s)


def test_pipeline_inputs_missing_gives_helpful_error():
    from app.config import ConfigError
    with pytest.raises(ConfigError) as e:
        get_settings().require_pipeline_inputs()
    assert "INPUT_DIR" in str(e.value) and "EXPLANATIONS_PATH" in str(e.value) and "APPROVALS_PATH" in str(e.value)
