"""FastAPI 앱. 실행: (ai/ 에서) uvicorn app.main:app --reload"""
from __future__ import annotations

import logging
import sys
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from .api import chat, contract, health, pdf_structure, report
from .config import get_settings, log_key_source

logging.basicConfig(level=logging.INFO, stream=sys.stdout, format="%(asctime)s %(levelname)s %(name)s: %(message)s")


@asynccontextmanager
async def lifespan(app: FastAPI):
    log_key_source(get_settings())  # 키 출처만 기록(값은 기록하지 않음)
    yield


def create_app() -> FastAPI:
    """보고서 계약과 개발용 경로를 묶은 FastAPI 앱을 구성한다.

    Args:
        없음.
    Returns:
        설정된 FastAPI 앱.
    Raises:
        라우터 설정 오류.
    Side Effects:
        라우터와 CORS 미들웨어만 등록한다. 파일·DB를 만들지 않는다.
    """
    app = FastAPI(title="AI Report Service", version="0.1.0-draft", lifespan=lifespan,
                  description="백엔드 보고서 계약 API. 보고서용 뷰 읽기·PDF 분석·자연어 조회·편집 변경안을 처리합니다.")
    # CORS: 확인용 화면(ai/demo/ai_report_studio.html)이 file:// 에서 열리므로 localhost·127.0.0.1 과 Origin "null" 만 허용한다.
    # ※ 운영 설정이 아니다. 운영에서는 백엔드가 호출하므로 브라우저 CORS 가 필요 없거나 정해진 도메인만 허용해야 한다.
    app.add_middleware(CORSMiddleware, allow_origin_regex=r"^(null|https?://(localhost|127\.0\.0\.1)(:\d+)?)$", allow_methods=["GET", "POST", "PUT", "DELETE"], allow_headers=["*"])
    app.include_router(health.router)
    app.include_router(report.router)
    app.include_router(chat.router)
    app.include_router(contract.router)
    app.include_router(pdf_structure.router)
    return app


app = create_app()
