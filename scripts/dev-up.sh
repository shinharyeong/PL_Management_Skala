#!/usr/bin/env bash
# 목적: 로컬 Compose 서비스를 빌드하고 상태 확인이 끝날 때까지 기다린다.
# Args: 없음. 저장소 루트의 기존 .env를 Docker Compose가 읽는다.
# Returns: 성공은 0, 구성·Docker·빌드·상태 확인 실패는 0이 아닌 종료 코드.
# Raises: 셸 예외 대신 stderr 안내와 종료 코드로 실패를 전달한다.
# Side Effects: 이미지·빌드 캐시·컨테이너·네트워크·DB 볼륨을 만들거나 갱신한다.
# 기존 .env와 DB 볼륨의 자료를 삭제하거나 비밀값을 출력하지 않는다.
set -euo pipefail

repo_dir="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_dir"

if [[ "$#" -ne 0 ]]; then
    printf '%s\n' '사용법: bash scripts/dev-up.sh' >&2
    exit 2
fi
if [[ ! -f .env ]]; then
    printf '%s\n' '.env가 없습니다. docs/development/local-environment.md의 필수 설정을 먼저 준비하세요.' >&2
    exit 1
fi
if ! command -v docker >/dev/null 2>&1 || ! docker compose version >/dev/null 2>&1; then
    printf '%s\n' 'Docker Desktop과 Docker Compose v2가 필요합니다.' >&2
    exit 1
fi
if ! docker info --format '{{.ServerVersion}}' >/dev/null 2>&1; then
    printf '%s\n' 'Docker 엔진이 실행되지 않았습니다. Docker Desktop을 시작하세요.' >&2
    exit 1
fi

docker compose config --quiet
docker compose up --build --detach --wait --wait-timeout 240
docker compose ps
frontend_address="$(docker compose port frontend 8080)"
printf '프런트 주소: http://%s\n' "$frontend_address"
