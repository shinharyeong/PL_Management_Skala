#!/usr/bin/env bash
# 캠퍼스 네트워크에서 개인 kubectl로 k8s/를 적용한다(이미지는 빌드하지 않는다).
# 평소 배포는 main push 때 .github/workflows/deploy.yml이 한다(ECR push는 Actions Role로만 된다).
# 이 스크립트는 runner가 없을 때, 매니페스트만 바꿨을 때, 이전 버전으로 되돌릴 때 쓴다.
#
# 사용: scripts/deploy.sh                 origin/main 커밋 해시 12자리 태그로 적용(그 이미지가 ECR에 있어야 한다)
#       scripts/deploy.sh 1af26bbcdbad    지정한 커밋 해시 태그로 적용
# 사전 준비: 포털 가이드 「kubectl 접속」으로 만든 ~/.kube/p1-2.yaml (export KUBECONFIG=~/.kube/p1-2.yaml)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SERVICES=(backend ai frontend)

fail() { echo "$*" >&2; exit 1; }

context="$(kubectl config current-context 2>/dev/null || true)"
[ "$context" = "p1-2" ] || fail "kubectl context가 p1-2가 아닙니다(현재: ${context:-없음}). export KUBECONFIG=~/.kube/p1-2.yaml 후 다시 실행하세요."

if [ $# -ge 1 ]; then
  SHA="$1"
else
  git -C "$ROOT" fetch -q origin main
  SHA="$(git -C "$ROOT" rev-parse --short=12 origin/main)"
fi
[[ "$SHA" =~ ^[0-9a-f]{12}$ ]] || fail "커밋 해시 12자리를 주세요(받은 값: $SHA)."

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
cp -R "$ROOT/k8s" "$WORK/k8s"
sed -i.bak -E "s/newTag: (backend|ai|frontend)-main/newTag: \1-$SHA/" "$WORK/k8s/kustomization.yaml"
rm -f "$WORK/k8s/kustomization.yaml.bak"
[ "$(grep -c "newTag: .*-$SHA" "$WORK/k8s/kustomization.yaml")" = 3 ] || fail "이미지 태그 치환 실패"

echo "▶ kubectl apply -k (태그 <서비스>-$SHA)"
kubectl apply -k "$WORK/k8s"
for service in "${SERVICES[@]}"; do
  kubectl rollout status "deploy/$service" --timeout=8m
done
kubectl get pods -o wide
