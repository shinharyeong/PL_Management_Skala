#!/usr/bin/env bash
# 목적: 로컬 Compose 컨테이너와 네트워크만 종료한다.
# Args: 없음. 저장소 루트의 기존 .env를 Docker Compose가 읽는다.
# Returns: 성공은 0, Docker·구성 오류는 0이 아닌 종료 코드.
# Raises: 셸 예외 대신 stderr와 종료 코드로 실패를 전달한다.
# Side Effects: skala-local 프로젝트의 컨테이너·네트워크를 제거한다.
# named DB 볼륨·이미지·빌드 캐시·.env는 보존하며 down -v를 사용하지 않는다.
set -euo pipefail

repo_dir="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_dir"

if [[ "$#" -ne 0 ]]; then
    printf '%s\n' '사용법: bash scripts/dev-down.sh' >&2
    exit 2
fi
if [[ ! -f .env ]]; then
    printf '%s\n' '.env가 없어 Compose 구성을 읽을 수 없습니다. 기존 로컬 설정을 복구하세요.' >&2
    exit 1
fi

docker compose down
