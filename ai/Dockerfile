# syntax=docker/dockerfile:1
# 빌드 컨텍스트는 저장소 루트(docker build -f ai/Dockerfile .).

FROM python:3.11-slim
RUN useradd --system --uid 10001 --home-dir /srv --shell /usr/sbin/nologin app
WORKDIR /srv/ai
COPY ai/requirements.txt ./requirements.txt
RUN --mount=type=cache,target=/root/.cache/pip pip install -r requirements.txt
COPY ai/app ./app
# app/pipeline 이 저장소 루트 기준 parser/src(엑셀 파서)를 불러온다.
COPY parser/src /srv/parser/src
# 캐시·결과는 컨테이너 임시 폴더에만 둔다(보고서 결과는 저장하지 않는다).
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    TZ=Asia/Seoul \
    AI_CACHE_DIR=/tmp/ai/cache \
    OUTPUT_DIR=/tmp/ai/output
USER 10001
EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
