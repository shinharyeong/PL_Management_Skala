"""POST /v1/pipeline/run — 파일 업로드로 5단계(업로드→파싱→Gap→마스터→보고서)를 실행하는 확인용 API(초안, 백엔드와 합의 전).

나중에 백엔드 API 로 단계별 교체 예정: SUB-01(업로드) · SUB-03/06/07(파싱) · REV-01(Gap·소명) · MAS-04(마스터) · REP-01(보고).
업로드 파일 이름에는 폴더 경로가 들어 있어야 합니다(예: 01_excel/submission_2026_11/01_사업부1/F10_2026-11.xlsx).
"""
from fastapi import APIRouter, File, Form, HTTPException, UploadFile
from starlette.concurrency import run_in_threadpool

from ..services import pipeline_run

router = APIRouter(prefix="/v1/pipeline", tags=["pipeline (draft)"])


@router.post("/run")
async def run_pipeline(files: list[UploadFile] = File(..., description="엑셀(.xlsx)·JSON(.json) 또는 이를 담은 .zip. 파일 이름에 폴더 경로 포함"),
                       ai: bool = Form(False, description="true 일 때만 OpenAI 호출(확정 버전 최대 4회). 기본 false = 템플릿 문장"),
                       keep_files: bool | None = Form(None, description="false 면 실행 후 업로드 파일 삭제(기본은 설정 AI_UPLOAD_KEEP, 보존)")):
    blobs = [(f.filename or "", await f.read()) for f in files]
    # 파싱은 CPU 작업이라 스레드에서 실행한다.
    return await run_in_threadpool(pipeline_run.run, blobs, ai=ai, keep_files=keep_files)


@router.post("/start")
async def start_pipeline(files: list[UploadFile] = File(..., description="엑셀(.xlsx)·JSON(.json) 또는 이를 담은 .zip. 표준 이름·폴더가 아니어도 일반 모드로 읽음"),
                         ai: bool = Form(False), keep_files: bool | None = Form(None)):
    """업로드 직후 run_id 를 돌려주고 백그라운드에서 실행한다. 진행은 GET /v1/pipeline/runs/{run_id}/status."""
    blobs = [(f.filename or "", await f.read()) for f in files]
    return await run_in_threadpool(pipeline_run.start, blobs, ai=ai, keep_files=keep_files)


@router.get("/runs")
def list_runs(limit: int = 30):
    """실행 목록(최근 순). 보고서·챗봇의 실행 선택에 쓴다."""
    return {"runs": pipeline_run.list_runs(limit=min(max(limit, 1), 100))}


@router.get("/runs/{run_id}/status")
def get_status(run_id: str):
    r = pipeline_run.read_status(run_id)
    if r is None:
        raise HTTPException(status_code=404, detail="해당 실행이 없습니다.")
    return r


@router.get("/runs/{run_id}")
def get_run(run_id: str):
    r = pipeline_run.load_run(run_id)
    if r is None:
        raise HTTPException(status_code=404, detail="해당 실행 결과가 없습니다(run_id 를 확인하세요).")
    return r
