#!/usr/bin/env bash
# 목적: 로컬 Docker(compose) DB를 비우고 실데이터형 시연 데이터(조직 10개·2025~2027)를 처음부터 넣는다.
# 주의: 로컬 DB와 업로드 파일(/data/storage)을 지운다. 제출 양식(/data/templates)은 남긴다. 운영 DB에는 쓰지 않는다.
# 사용: bash scripts/demo-data/run.sh   (저장소 루트의 .env, 실행 중인 로컬 compose 필요. 약 2~3분)
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
cd "$ROOT"
[[ -f .env ]] || { echo ".env가 없습니다. docs/development/local-environment.md를 먼저 따라 주세요." >&2; exit 1; }
set -a; . ./.env >/dev/null 2>&1; set +a

read -r -p "로컬 DB와 업로드 파일을 모두 지우고 다시 넣습니다. 계속할까요? [y/N] " answer
[[ "$answer" == "y" || "$answer" == "Y" ]] || { echo "취소했습니다."; exit 1; }

backend="$(docker compose ps -q backend)"
[[ -n "$backend" ]] || { echo "로컬 compose가 실행 중이 아닙니다. bash scripts/dev-up.sh 먼저 실행하세요." >&2; exit 1; }
docker exec "$backend" sh -c 'rm -rf /data/storage/*'
docker compose stop backend postgres >/dev/null
docker compose rm -f postgres >/dev/null
docker volume rm "$(docker compose config --format json | python3 -c 'import json,sys; print(json.load(sys.stdin)["name"])')_postgres_data" >/dev/null
docker compose up -d --wait --wait-timeout 300 >/dev/null
python3 -B "$HERE/model.py" | docker compose exec -T postgres psql -q -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"

cd "$HERE"
# 빈 DB에 여러 조직 파일을 동시에 처음 올리면 답안지 항목 생성이 충돌할 수 있어 한 번 더 시도한다.
python3 -B seed.py setup y2025 || python3 -B seed.py y2025
python3 -B seed.py y2026 nov y2027
python3 -B extra.py
python3 -B readall.py
echo "완료: http://localhost:5173/dev 에서 계정을 골라 들어가세요. 2027 연간 기한 경과 알림은 약 5분 안에 생깁니다."
