from __future__ import annotations

from pydantic import BaseModel


class HealthResponse(BaseModel):
    status: str
    model: str
    openai_key_present: bool
