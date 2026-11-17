"""FastAPI 앱. 실행: (ai/ 에서) uvicorn app.main:app --reload"""
from __future__ import annotations

import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from .api import chat, health, pipeline, report
from .config import get_settings, log_key_source

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")


@asynccontextmanager
async def lifespan(app: FastAPI):
    log_key_source(get_settings())  # 키 출처만 기록(값은 기록하지 않음)
    yield


def create_app() -> FastAPI:
    app = FastAPI(title="AI Report Service", version="0.1.0-draft", lifespan=lifespan,
                  description="초안 API(백엔드와 합의 전). 핵심 메시지 초안 생성, 파일 업로드 5단계 실행(확인용).")
    # CORS: 확인용 화면(ai/preview_html/flow.html)이 file:// 에서 열리므로 localhost·127.0.0.1 과 Origin "null" 만 허용한다.
    # ※ 운영 설정이 아니다. 운영에서는 백엔드가 호출하므로 브라우저 CORS 가 필요 없거나 정해진 도메인만 허용해야 한다.
    app.add_middleware(CORSMiddleware, allow_origin_regex=r"^(null|https?://(localhost|127\.0\.0\.1)(:\d+)?)$", allow_methods=["GET", "POST"], allow_headers=["*"])
    app.include_router(health.router)
    app.include_router(report.router)
    app.include_router(pipeline.router)
    app.include_router(chat.router)
    return app


app = create_app()
