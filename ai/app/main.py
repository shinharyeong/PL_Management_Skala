"""FastAPI 앱(report-ai). 실행: (ai/ 에서) uvicorn app.main:app --reload

backend 만 클러스터 안에서 부른다(명세 12절). 브라우저가 직접 부르지 않으므로 CORS 를 열지 않는다.
- GET  /health
- POST /v1/nl-query · /v1/data · /v1/edit · /v1/pdf-jobs · /v1/sql-check  (app/api/contract.py)
"""
from __future__ import annotations

import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI

from .api import contract, health
from .config import get_settings, log_key_source

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")


@asynccontextmanager
async def lifespan(app: FastAPI):
    log_key_source(get_settings())  # 키 출처만 기록(값은 기록하지 않음)
    yield


def create_app() -> FastAPI:
    app = FastAPI(title="report-ai", version="1.0.0", lifespan=lifespan,
                  description="AI 보고서 계약 API(명세 12절): 자연어 질의, 블록 데이터 채우기, 대화 편집, PDF 틀 추출, SQL 검사.")
    app.include_router(health.router)
    app.include_router(contract.router)
    return app


app = create_app()
