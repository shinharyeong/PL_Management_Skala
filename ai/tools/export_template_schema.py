"""보고서 틀(definition) 저장 봉투의 JSON 스키마를 AI 모델(app/report_template/model.py StoredTemplate)에서 뽑아 백엔드 리소스로 쓴다.

틀 형식의 기준은 AI 쪽 하나다. 백엔드(RPT-04·05 저장 검사)는 봉투(schema_version·name·blocks[].id·kind)만 보고,
블록 세부 형식은 AI·화면이 정한다(열려 있음).
모델을 바꾸면 이 스크립트를 다시 실행한다: python tools/export_template_schema.py
tests/test_template_schema.py 가 파일과 모델이 어긋나면 실패한다.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
TARGET = ROOT.parent / "backend/src/main/resources/report/template-definition.schema.json"


def build() -> dict:
    from app.report_template.model import StoredTemplate
    schema = StoredTemplate.model_json_schema()
    return {"$schema": "https://json-schema.org/draft/2020-12/schema",
            "$comment": "ai/app/report_template/model.py StoredTemplate 에서 생성. 직접 고치지 말 것: python ai/tools/export_template_schema.py",
            **schema}


def render() -> str:
    return json.dumps(build(), ensure_ascii=False, indent=2, sort_keys=True) + "\n"


if __name__ == "__main__":
    TARGET.write_text(render(), encoding="utf-8")
    print(f"썼습니다: {TARGET.relative_to(ROOT.parent)}")
