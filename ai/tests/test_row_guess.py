"""[현재 화면에서는 쓰지 않는 기능의 테스트] 행 이름 → 지표 AI 추측: AI 출력이 지표 사전 밖이면 버리고, 키·한도가 없으면 추측하지 않는다(실제 호출 없이 가짜 클라이언트로)."""
import json
from types import SimpleNamespace

from app.pdf_structure import row_guess as R


class _Settings(SimpleNamespace):
    pass


def _patch(monkeypatch, tmp_path, answer, key=True, calls_today=0):
    s = _Settings(openai_model="m", key_present=key, ai_daily_call_limit=5, output_dir=tmp_path, log_dir=tmp_path)
    monkeypatch.setattr(R, "get_settings", lambda: s)
    monkeypatch.setattr(R.D, "months", lambda: {"db_ready": True, "months": [], "metrics": ["매출", "영업비용", "영업이익"], "orgs": [{"code": "F10", "name": "사업부1"}]})
    monkeypatch.setattr(R, "CallLog", lambda d: SimpleNamespace(calls_today=lambda: calls_today, append=lambda e: None))

    class _C:
        class chat:
            class completions:
                @staticmethod
                def create(**kw):
                    return SimpleNamespace(choices=[SimpleNamespace(message=SimpleNamespace(content=json.dumps(answer)))], usage=SimpleNamespace(prompt_tokens=10, completion_tokens=5))
    monkeypatch.setattr(R.openai_client, "make_client", lambda s: _C())


def test_only_dictionary_metrics_kept(monkeypatch, tmp_path):
    _patch(monkeypatch, tmp_path, {"guess": {"OP": "영업이익", "인원수": "인원", "수익": None}})
    r = R.guess(["OP", "인원수", "수익", "매출", "사업부1"])
    assert r["guess"] == {"OP": "영업이익"}                        # 사전에 없는 지표·null 은 버림, 이미 맞는 행(매출·조직)은 묻지 않음
    assert r["usage"]["calls"] == 1


def test_no_key_no_guess(monkeypatch, tmp_path):
    _patch(monkeypatch, tmp_path, {"guess": {"OP": "영업이익"}}, key=False)
    r = R.guess(["OP"])
    assert r["guess"] == {} and not r["status"]["ok"]


def test_daily_limit_no_guess(monkeypatch, tmp_path):
    _patch(monkeypatch, tmp_path, {"guess": {"OP": "영업이익"}}, calls_today=5)
    assert R.guess(["OP"])["guess"] == {}


def test_block_guess_link_and_kind_validated(monkeypatch, tmp_path):
    from app.pdf_structure import block_guess as G
    s = _Settings(openai_model="m", key_present=True, ai_daily_call_limit=5, output_dir=tmp_path, log_dir=tmp_path)
    monkeypatch.setattr(G, "get_settings", lambda: s)
    monkeypatch.setattr(G, "CallLog", lambda d: SimpleNamespace(calls_today=lambda: 0, append=lambda e: None))
    answers = iter([{"target": "b3"}, {"target": "zzz"}, {"kind": "list"}, {"kind": "poem"}])

    class _C:
        class chat:
            class completions:
                @staticmethod
                def create(**kw):
                    return SimpleNamespace(choices=[SimpleNamespace(message=SimpleNamespace(content=json.dumps(next(answers))))], usage=SimpleNamespace(prompt_tokens=1, completion_tokens=1))
    monkeypatch.setattr(G.openai_client, "make_client", lambda s: _C())
    cands = [{"id": "b3", "label": "표"}, {"id": "b4", "label": "문장"}]
    assert G.guess_block("link", {"text": "※ 주"}, cands)["result"] == "b3"
    assert G.guess_block("link", {"text": "※ 다른"}, cands)["result"] is None          # 후보에 없는 id 는 버림
    assert G.guess_block("kind", {"text": "1. 가 2. 나", "kind": "paragraph"})["result"] == "list"
    assert G.guess_block("kind", {"text": "x", "kind": "paragraph"})["result"] is None   # 허용 종류 밖은 버림
    assert G.guess_block("text_mismatch", {})["result"] is None
