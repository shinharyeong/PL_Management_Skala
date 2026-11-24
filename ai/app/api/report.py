"""POST /v1/report/key-messages — 핵심 메시지 초안(초안 명세, 백엔드와 합의 전)."""
from fastapi import APIRouter

from ..schemas.key_messages import KeyMessagesRequest, KeyMessagesResponse, Sentence, Usage
from ..services import key_messages

router = APIRouter(prefix="/v1/report", tags=["report"])


@router.post("/key-messages", response_model=KeyMessagesResponse)
def create_key_messages(req: KeyMessagesRequest) -> KeyMessagesResponse:
    facts = [f.model_dump() for f in req.facts]
    r = key_messages.generate(facts, req.status)
    return KeyMessagesResponse(
        sentences=[Sentence(text=s["text"], fact_ids=s["fact_ids"], source=s["source"], reason=s["reason"]) for s in r["sentences"]],
        usage=Usage(**r["usage"]),
    )
