"""backend → report-ai 계약 API(명세 12절). 클러스터 내부에서 backend 만 부른다.

POST /v1/nl-query   text2SQL(RPT-11). 흐름은 LangGraph(app/chat/graph.py)
POST /v1/data       블록 데이터 채우기(RPT-10). AI 문장 없음, 숫자는 보고서용 뷰에서 코드가 계산
POST /v1/edit       대화 편집 변경안(RPT-15). 숫자는 바꾸지 않음
POST /v1/sql-check  지표 사전 등록 전 SQL 검사(RPT-09). 실행하지 않음
POST /v1/pdf-jobs   PDF 틀 추출 요청(RPT-01, 1차 전송). 202 를 먼저 돌려주고 끝나면 backend 에 결과를 보고한다
POST /v1/decision-agent/compose 고정 분석 결과로 의사결정서 설명을 구성한다. DB·금액 계산·승인 없음
POST /v1/decision-agent/interpret-goal 자연어 요청을 사용자 확인용 목표로 제안한다
POST /v1/decision-agent/discover 한정된 고정 근거 Tool로 개선 가설을 제안한다
POST /v1/decision-agent/assistant 관찰 결과에 따라 다음 도구를 선택하는 조회 대화를 실행한다
REPORT_AI_TOKEN 이 있으면 X-Internal-Token 헤더가 같아야 한다. 운영(AI_REQUIRE_INTERNAL_TOKEN=true)은 토큰이 설정되지 않았으면 모두 거부한다.
"""
import hmac

from fastapi import APIRouter, BackgroundTasks, Depends, File, Form, Header, HTTPException, Request, UploadFile
from pydantic import BaseModel, Field, ValidationError
from starlette.concurrency import run_in_threadpool

from .. import contract
from ..chat import service
from ..config import get_settings
from ..decision_agent.graph import compose_analysis
from ..decision_agent.models import DecisionComposeRequest
from ..decision_agent.discovery_graph import discover_analysis, interpret_goal
from ..decision_agent.discovery_models import DiscoveryRequest, GoalInterpretRequest
from ..decision_agent.assistant_graph import run_assistant
from ..decision_agent.assistant_models import AssistantRequest

MAX_PDF_MB = 30
MAX_DECISION_BODY_BYTES = 256 * 1024


def internal_only(x_internal_token: str | None = Header(default=None)) -> None:
    s = get_settings()
    expected = s.report_service_token
    if not expected:
        if s.require_internal_token:                     # 운영: 토큰 없이 열어 두지 않는다(공용 클러스터)
            raise HTTPException(status_code=401, detail="내부 토큰이 설정되지 않아 요청을 받지 않습니다.")
        return
    if not hmac.compare_digest(x_internal_token or "", expected):
        raise HTTPException(status_code=401, detail="내부 토큰이 맞지 않습니다.")


router = APIRouter(prefix="/v1", tags=["backend contract"], dependencies=[Depends(internal_only)])


class NlQueryRequest(BaseModel):
    question: str = Field(min_length=1, max_length=1000)
    baseYear: int = Field(ge=2000, le=9999)
    baseMonth: int = Field(ge=1, le=12)
    conversationId: str | None = Field(default=None, max_length=64)


class Base(BaseModel):
    year: int = Field(ge=2000, le=9999)
    month: int = Field(ge=1, le=12)


class Block(BaseModel):
    model_config = {"extra": "allow"}
    id: str = Field(min_length=1, max_length=64)
    kind: str = Field(min_length=1, max_length=40)


class DataRequest(BaseModel):
    base: Base
    blocks: list[Block] = Field(max_length=500)


class SqlCheckRequest(BaseModel):
    sql: str = Field(min_length=1, max_length=4000)


class EditRequest(BaseModel):
    definition: dict
    instruction: str = Field(min_length=1, max_length=1000)


async def read_decision_request(request: Request) -> DecisionComposeRequest:
    """인증된 내부 분석 요청을 제한된 크기로 읽고 고정 계약을 검증한다.

    Args:
        request: FastAPI 요청. Content-Length 유무와 무관하게 실제 바이트 수를 제한한다.
    Returns:
        엄격한 타입·필드 상한을 통과한 의사결정서 설명 입력.
    Raises:
        HTTPException: 본문 256KiB 초과 시 413, 잘못된 길이·JSON·계약이면 422.
    Side Effects:
        요청 스트림을 메모리에서 읽는다. 본문·비밀값을 파일이나 로그에 기록하지 않는다.
    """
    declared = request.headers.get("content-length")
    if declared is not None:
        try:
            size = int(declared)
        except ValueError as cause:
            raise HTTPException(status_code=422, detail="요청 본문 길이가 올바르지 않습니다.") from cause
        if size < 0:
            raise HTTPException(status_code=422, detail="요청 본문 길이가 올바르지 않습니다.")
        if size > MAX_DECISION_BODY_BYTES:
            raise HTTPException(status_code=413, detail="분석 입력은 256KiB 이하여야 합니다.")
    body = bytearray()
    async for chunk in request.stream():
        if len(body) + len(chunk) > MAX_DECISION_BODY_BYTES:
            raise HTTPException(status_code=413, detail="분석 입력은 256KiB 이하여야 합니다.")
        body.extend(chunk)
    try:
        return DecisionComposeRequest.model_validate_json(body)
    except ValidationError as cause:
        raise HTTPException(status_code=422, detail="분석 입력의 JSON 또는 필드 계약이 올바르지 않습니다.") from cause


@router.post("/decision-agent/compose")
async def decision_agent_compose(req: DecisionComposeRequest = Depends(read_decision_request)) -> dict:
    """Spring 계산 원문에 고정된 설명 초안만 반환한다.

    Args:
        req: 내부 인증과 본문 크기·스키마 검증을 마친 분석 요청.
    Returns:
        mode, summary, decisionRequests, limitations, steps를 가진 설명 객체.
    Raises:
        HTTPException: 스냅샷과 후보·계산 결과의 연결이 잘못되면 422.
    Side Effects:
        설정된 모델을 최대 한 번 호출할 수 있다. 상태 변경·DB 조회·금액 재계산은 하지 않는다.
    """
    try:
        return await run_in_threadpool(compose_analysis, req.model_dump())
    except ValueError as cause:
        raise HTTPException(status_code=422, detail="기준 스냅샷과 분석 결과의 연결이 올바르지 않습니다.") from cause


async def read_agent_payload(request: Request, model: type[BaseModel]) -> BaseModel:
    """신규 Agent 제안 요청을 제한된 크기로 읽고 모델별 엄격한 계약을 검사한다.

    Args:
        request: 내부 인증을 거친 HTTP 요청.
        model: 목표 제안 또는 근거 탐색 요청 DTO.
    Returns:
        계약 검증을 마친 DTO 객체.
    Raises:
        HTTPException: 본문 256KiB 초과는 413, 길이·JSON·필드 오류는 422.
    Side Effects:
        요청 스트림을 메모리에 읽는다. 토큰·본문을 로그·파일에 쓰지 않는다.
    """
    declared = request.headers.get("content-length")
    if declared is not None:
        try:
            size = int(declared)
        except ValueError as cause:
            raise HTTPException(status_code=422, detail="요청 본문 길이가 올바르지 않습니다.") from cause
        if size < 0:
            raise HTTPException(status_code=422, detail="요청 본문 길이가 올바르지 않습니다.")
        if size > MAX_DECISION_BODY_BYTES:
            raise HTTPException(status_code=413, detail="분석 입력은 256KiB 이하여야 합니다.")
    body = bytearray()
    async for chunk in request.stream():
        if len(body) + len(chunk) > MAX_DECISION_BODY_BYTES:
            raise HTTPException(status_code=413, detail="분석 입력은 256KiB 이하여야 합니다.")
        body.extend(chunk)
    try:
        return model.model_validate_json(body)
    except ValidationError as cause:
        raise HTTPException(status_code=422, detail="제안 요청의 JSON 또는 필드 계약이 올바르지 않습니다.") from cause


async def read_goal_request(request: Request) -> GoalInterpretRequest:
    """자연어 목표 제안의 요청 계약을 읽는다.

    Args:
        request: 인증된 HTTP 요청.
    Returns:
        GoalInterpretRequest 객체.
    Raises:
        HTTPException: 본문 크기·계약 오류.
    Side Effects:
        요청 스트림만 메모리에 읽는다.
    """
    return await read_agent_payload(request, GoalInterpretRequest)


async def read_discovery_request(request: Request) -> DiscoveryRequest:
    """한정된 Tool 토큰을 포함한 고정 근거 탐색 요청을 읽는다.

    Args:
        request: 인증된 HTTP 요청.
    Returns:
        DiscoveryRequest 객체.
    Raises:
        HTTPException: 본문 크기·계약 오류.
    Side Effects:
        요청 스트림만 메모리에 읽는다. 토큰을 별도로 기록하지 않는다.
    """
    return await read_agent_payload(request, DiscoveryRequest)


async def read_assistant_request(request: Request) -> AssistantRequest:
    """대화 문맥과 단기 capability를 포함한 엄격한 요청을 읽는다.

    Args: request 인증된 HTTP 요청.
    Returns: AssistantRequest 객체.
    Raises: HTTPException: 본문 상한·타입·대화 역할 계약 오류.
    Side Effects: 요청 스트림만 메모리에 읽는다. 토큰을 기록하지 않는다.
    """
    return await read_agent_payload(request, AssistantRequest)


@router.post("/decision-agent/assistant")
async def decision_agent_assistant(req: AssistantRequest = Depends(read_assistant_request)) -> dict:
    """허용된 조회 도구를 동적으로 선택하는 대화형 실행 결과를 반환한다.

    Args: req 검증된 질문·history·고정 context·단기 capability.
    Returns: mode·status·answer·실제 toolCalls·관찰·검증된 인용.
    Raises: HTTPException: 고정 context 계약이 잘못되면 422.
    Side Effects: 제한된 모델·내부 도구 호출만 수행한다. DB 직접 접속·상태 변경은 없다.
    """
    try:
        return await run_in_threadpool(run_assistant, req.model_dump())
    except ValueError as cause:
        raise HTTPException(status_code=422, detail="대화의 고정 기준과 입력 계약을 확인해 주세요.") from cause


@router.post("/decision-agent/interpret-goal")
async def decision_agent_interpret_goal(req: GoalInterpretRequest = Depends(read_goal_request)) -> dict:
    """원문 대조를 거친 사용자 확인용 목표를 반환한다.

    Args:
        req: 검증된 자연어 요청·선택 연도.
    Returns:
        mode·goalProposal 객체. 기본 처리 사유는 목표의 uncertainties에 포함한다.
    Raises:
        HTTPException: 의미 검증 오류가 발생한 경우 422.
    Side Effects:
        모델을 최대 한 번 호출할 수 있다. 분석 생성·저장·승인은 하지 않는다.
    """
    try:
        return await run_in_threadpool(interpret_goal, req.model_dump())
    except ValueError as cause:
        raise HTTPException(status_code=422, detail="목표 제안 요청을 확인해 주세요.") from cause


@router.post("/decision-agent/discover")
async def decision_agent_discover(req: DiscoveryRequest = Depends(read_discovery_request)) -> dict:
    """고정 근거에서 사용자 확인이 필요한 금액 미확정 개선 가설을 반환한다.

    Args:
        req: 분석 버전·허용 근거·단기 Tool 토큰을 포함한 내부 요청.
    Returns:
        mode·goalProposal·hypotheses·limitations·steps 객체.
    Raises:
        HTTPException: 고정 조직·근거 연결이 잘못된 경우 422.
    Side Effects:
        내부 읽기 Tool 최대 한 번과 모델 최대 두 번. DB·분석·후보 상태를 바꾸지 않는다.
    """
    try:
        return await run_in_threadpool(discover_analysis, req.model_dump())
    except ValueError as cause:
        raise HTTPException(status_code=422, detail="고정 기준과 근거 목록의 연결을 확인해 주세요.") from cause


@router.post("/nl-query")
async def nl_query(req: NlQueryRequest):
    return await run_in_threadpool(service.nl_query, req.question, req.baseYear, req.baseMonth, conversation_id=req.conversationId)


@router.post("/data")
async def data(req: DataRequest):
    return await run_in_threadpool(contract.data, req.base.model_dump(), [b.model_dump() for b in req.blocks])


@router.post("/sql-check")
def sql_check(req: SqlCheckRequest):
    return contract.sql_check(req.sql)


@router.post("/edit")
async def edit(req: EditRequest):
    return await run_in_threadpool(contract.edit, req.definition, req.instruction)


@router.post("/pdf-jobs", status_code=202)
async def pdf_jobs(background: BackgroundTasks, jobId: str = Form(..., min_length=1, max_length=64), maskNumbers: bool = Form(True),
                   file: UploadFile = File(...)):
    pdf = await file.read()
    if len(pdf) > MAX_PDF_MB * 1024 * 1024:
        raise HTTPException(status_code=413, detail=f"PDF 가 너무 큽니다(최대 {MAX_PDF_MB}MB).")
    background.add_task(contract.pdf_job, jobId, pdf, file.filename, maskNumbers)
    return {"jobId": jobId, "accepted": True}
