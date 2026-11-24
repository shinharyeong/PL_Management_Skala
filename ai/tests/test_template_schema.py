"""백엔드가 틀 저장 때 쓰는 JSON 스키마가 AI 모델(app/report_template/model.py)과 같은지 확인한다(계약이 한쪽만 바뀌지 않게)."""
from __future__ import annotations

import importlib.util
from pathlib import Path

TOOL = Path(__file__).resolve().parents[1] / "tools" / "export_template_schema.py"


def test_backend_schema_matches_ai_model():
    spec = importlib.util.spec_from_file_location("export_template_schema", TOOL)
    tool = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(tool)
    assert tool.TARGET.exists(), "python ai/tools/export_template_schema.py 를 실행하세요"
    assert tool.TARGET.read_text(encoding="utf-8") == tool.render(), \
        "틀 모델이 바뀌었습니다. python ai/tools/export_template_schema.py 로 백엔드 스키마를 다시 만드세요"


def test_demo_saved_template_fits_the_stored_envelope():
    """현재 데모의 추가 필드와 숫자 카드가 백엔드 저장 봉투를 통과함을 검증한다.

    Args:
        없음.
    Returns:
        없음.
    Raises:
        AssertionError: 저장 봉투가 실제 데모 블록을 허용하지 않는 경우.
        pydantic.ValidationError: 저장 봉투 필수 필드가 잘못된 경우.
    Side Effects:
        합성 딕셔너리만 검증하며 파일·DB를 만들지 않는다.
    """
    from app.report_template.model import StoredTemplate
    demo = {"schema_version": 1, "name": "월 보고", "order": ["b1", "c1"], "layout": {"c1": {"w": 50}},
            "blocks": [{"id": "b1", "kind": "title", "text": "제목"}, {"id": "c1", "kind": "numbercard", "metric": "영업이익"}]}
    assert StoredTemplate.model_validate(demo).model_dump()["layout"] == {"c1": {"w": 50}}
