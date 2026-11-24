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


def test_demo_saved_template_and_strict_model_fit_the_stored_envelope():
    """데모 화면이 저장하는 모양(추가 칸·numbercard)과 엄격한 Template 모델 결과가 모두 백엔드 봉투를 통과한다."""
    from app.report_template.model import StoredTemplate, Template
    demo = {"schema_version": 1, "name": "월 보고", "order": ["b1", "c1"], "layout": {"c1": {"w": 50}},
            "blocks": [{"id": "b1", "kind": "title", "text": "제목"}, {"id": "c1", "kind": "numbercard", "metric": "영업이익"}]}
    assert StoredTemplate.model_validate(demo).model_dump()["layout"] == {"c1": {"w": 50}}
    strict = Template(name="월 보고", blocks=[{"id": "t1", "kind": "title", "text": "제목"}]).model_dump()
    StoredTemplate.model_validate(strict)
