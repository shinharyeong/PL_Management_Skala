"""POST /v1/report/key-messages — 핵심 메시지 초안(초안 명세, 백엔드와 합의 전)."""
from fastapi import APIRouter, HTTPException, Query
from starlette.concurrency import run_in_threadpool

from ..schemas.key_messages import KeyMessagesRequest, KeyMessagesResponse, Sentence, Usage
from ..services import key_messages, pipeline_run

router = APIRouter(prefix="/v1/report", tags=["report"])


@router.post("/key-messages", response_model=KeyMessagesResponse)
def create_key_messages(req: KeyMessagesRequest) -> KeyMessagesResponse:
    facts = [f.model_dump() for f in req.facts]
    r = key_messages.generate(facts, req.status)
    return KeyMessagesResponse(
        sentences=[Sentence(text=s["text"], fact_ids=s["fact_ids"], source=s["source"], reason=s["reason"]) for s in r["sentences"]],
        usage=Usage(**r["usage"]),
    )


@router.get("/latest")
def latest_report(state: str = Query("final", pattern="^(final|provisional)$"), run_id: str | None = Query(None, max_length=64)):
    """가장 최근에 끝난 실행의 보고서 화면 요약(프론트 보고서 화면용). 실행이 없으면 404."""
    r = pipeline_run.latest_report(state, run_id=run_id)
    if r is None:
        raise HTTPException(status_code=404, detail="완료된 실행의 보고서가 없습니다. 먼저 POST /v1/pipeline/run 을 실행하세요.")
    return r
