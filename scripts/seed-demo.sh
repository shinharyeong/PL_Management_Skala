#!/usr/bin/env bash
# 캠퍼스 네트워크에서 실행: 체험 계정·조직(backend/src/main/resources/db/local/R__local_seed.sql)을 팀 RDS에 넣는다.
# 백엔드가 한 번 떠서 Flyway가 테이블을 만든 뒤에 실행한다. 여러 번 실행해도 중복되지 않는다(ON CONFLICT DO NOTHING).
# 비밀번호는 명령줄에 쓰지 않고 Secret rds-credentials를 작업 파드의 환경변수로 받는다.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SEED="$ROOT/backend/src/main/resources/db/local/R__local_seed.sql"

context="$(kubectl config current-context 2>/dev/null || true)"
if [ "$context" != "p1-2" ]; then
  echo "kubectl context가 p1-2가 아닙니다(현재: ${context:-없음}). export KUBECONFIG=~/.kube/p1-2.yaml 후 다시 실행하세요." >&2
  exit 1
fi

overrides='{"spec":{"containers":[{"name":"seed-demo","image":"postgres:18-alpine","stdin":true,"stdinOnce":true,
  "command":["psql","-v","ON_ERROR_STOP=1","-d","p1_2"],
  "env":[{"name":"PGSSLMODE","value":"require"}],
  "envFrom":[{"secretRef":{"name":"rds-credentials"}}]}]}}'

kubectl run seed-demo --rm -i --restart=Never --image=postgres:18-alpine --overrides="$overrides" < "$SEED"
echo "체험 계정: kim.plan@sk.com(경영기획팀), choi.infra@sk.com·park.media@sk.com(조직 담당자)"
