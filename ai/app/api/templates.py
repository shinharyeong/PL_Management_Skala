"""AI 보고서 틀 저장·불러오기 API — 개발 전용(AI_TEMPLATE_DEV=1 일 때만 열린다, 아니면 404).

GET    /v1/templates              저장된 틀 목록
POST   /v1/templates              저장(id 없으면 새 틀, 있으면 덮어쓰기)
GET    /v1/templates/{id}         불러오기
DELETE /v1/templates/{id}         삭제
POST   /v1/templates/{id}/start   저장한 틀로 시작: 구성 추출을 건너뛰고 '데이터 선택' 단계로
POST   /v1/templates/resolve-period   기준 월 + 날짜 규칙 → 실제 월 목록(확인용)
GET/POST /v1/templates/mappings   분류표 목록·새 버전 저장, GET /v1/templates/mappings/{version}
"""
from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field, ValidationError

from ..config import get_settings
from ..report_template import periods, store
from ..report_template.model import Mapping, Period, Template


def dev_only():
    if not get_settings().template_dev:
        raise HTTPException(status_code=404, detail="Not Found")


router = APIRouter(prefix="/v1/templates", tags=["report template (dev)"], dependencies=[Depends(dev_only)])


@router.get("")
def list_templates():
    return {"templates": store.list_templates()}


@router.post("")
def save_template(t: Template):
    return store.save_template(t).model_dump(mode="json")


class ResolveReq(BaseModel):
    base_year: int
    base_month: int = Field(ge=1, le=12)
    period: Period


@router.post("/resolve-period")
def resolve_period(req: ResolveReq):
    return {"months": [{"year": y, "month": m} for y, m in periods.resolve(req.period, (req.base_year, req.base_month))]}


@router.get("/mappings")
def list_mappings():
    return {"mappings": store.list_mappings()}


@router.post("/mappings")
def save_mapping(m: Mapping):
    return store.save_mapping(m).model_dump(mode="json")


@router.get("/mappings/{version}")
def get_mapping(version: int):
    try:
        return store.load_mapping(version).model_dump(mode="json")
    except store.NotFound:
        raise HTTPException(status_code=404, detail="분류표 버전이 없습니다.") from None


def _get(tid: str) -> Template:
    try:
        return store.load_template(tid)
    except store.NotFound:
        raise HTTPException(status_code=404, detail="틀이 없습니다.") from None
    except ValidationError as e:
        raise HTTPException(status_code=422, detail=f"저장된 틀이 현재 스키마와 맞지 않습니다: {e.errors()[0]['msg']}") from None


@router.get("/{tid}")
def get_template(tid: str):
    return _get(tid).model_dump(mode="json")


@router.delete("/{tid}")
def delete_template(tid: str):
    try:
        store.delete_template(tid)
    except store.NotFound:
        raise HTTPException(status_code=404, detail="틀이 없습니다.") from None
    return {"deleted": tid}


@router.post("/{tid}/start")
def start_from_template(tid: str):
    """저장한 틀로 시작. 구성 추출(PDF)은 건너뛰고 데이터 선택 단계로 간다. 데이터 선택은 C 단계에서 구현."""
    t = _get(tid)
    mapping = None
    if t.settings.mapping_version:
        try:
            mapping = store.load_mapping(t.settings.mapping_version).model_dump(mode="json")
        except store.NotFound:
            mapping = None
    return {"template": t.model_dump(mode="json"), "next_step": "select_data", "skipped_steps": ["extract_pdf", "confirm_structure"],
            "mapping": mapping, "mapping_missing": bool(t.settings.mapping_version) and mapping is None}
