"""데이터 묶음 적재: 제목 문구의 '2026년' 을 연도 머리로 오인하지 않는다. 실제 묶음 폴더가 있으면 정답 JSON 과 대조한다(검증 전용)."""
import os
from pathlib import Path

import pytest

from app.generic import reader


def test_title_sentence_is_not_a_year_block_header():
    title = ["▣ 2026년 실적·추정 및 2027년 계획_매출 (천원, IFRS후) - 테스트용 합성 데이터"] + [None] * 5
    assert reader._blocks(title, 26) == {}
    head = [None] * 7 + ["'25년 실적", None, "'26년 계획", "'26년 실적/추정", "'27년 계획"]
    assert reader._blocks(head, 26) == {"PLAN": 10, "CUR": 11}
    assert reader._blocks(["2026년 계획", "2026년 실적/추정"], 26) == {"PLAN": 1, "CUR": 2}     # 짧은 머리 칸은 4자리 연도도 인정


ROOT = Path(os.environ.get("AI_DATASET_DIR", "")) if os.environ.get("AI_DATASET_DIR") else None


@pytest.mark.skipif(not ROOT or not ROOT.exists(), reason="AI_DATASET_DIR 로 데이터 묶음 폴더를 지정하면 실행")
def test_real_dataset_matches_expected_values(tmp_path, monkeypatch):
    import dataclasses

    from app.chat import loader_set
    from app.config import get_settings
    s = dataclasses.replace(get_settings(), chat_db_path=tmp_path / "t.db")
    r = loader_set.load_dataset(ROOT, s, workers=1)
    v = loader_set.verify_against(ROOT, s)
    assert r["orgs"] >= 1 and v["financial"]["wrong"] == 0 and v["financial"]["missing"] == 0
    assert v["review"]["wrong"] == 0 and v["review"]["missing"] == 0
