#!/usr/bin/env bash
# 캠퍼스 네트워크에서 실행: 이미지 빌드(linux/amd64) → Harbor push → k8s/ 태그 갱신 → kubectl apply → 배포 확인.
# 사전 준비: docker login skala-registry.skala-ai.com -u p1-2 (한 번), KUBECONFIG=~/.kube/p1-2.yaml
# 사용: scripts/deploy.sh [서비스...]   예) scripts/deploy.sh            (backend ai frontend 모두)
#                                      scripts/deploy.sh backend    (backend만 새로 빌드)
#       SKIP_BUILD=1 scripts/deploy.sh  (빌드 없이 k8s/ 설정만 반영)
set -euo pipefail

REGISTRY=skala-registry.skala-ai.com/p1-2
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KUSTOMIZATION="$ROOT/k8s/kustomization.yaml"
SERVICES=("$@")
[ ${#SERVICES[@]} -eq 0 ] && SERVICES=(backend ai frontend)

context="$(kubectl config current-context 2>/dev/null || true)"
if [ "$context" != "p1-2" ]; then
  echo "kubectl context가 p1-2가 아닙니다(현재: ${context:-없음}). export KUBECONFIG=~/.kube/p1-2.yaml 후 다시 실행하세요." >&2
  exit 1
fi

if [ "${SKIP_BUILD:-0}" != "1" ]; then
  if [ -n "$(git -C "$ROOT" status --porcelain -- backend ai frontend parser)" ]; then
    echo "커밋하지 않은 변경이 있습니다. 이미지 태그(커밋 해시)와 내용이 달라지므로 먼저 커밋하세요." >&2
    exit 1
  fi
  TAG="$(git -C "$ROOT" rev-parse --short=12 HEAD)"
  for service in "${SERVICES[@]}"; do
    echo "▶ $service:$TAG 빌드·push"
    docker buildx build --platform linux/amd64 -f "$ROOT/$service/Dockerfile" -t "$REGISTRY/$service:$TAG" --push "$ROOT"
    # kustomization.yaml의 해당 이미지 newTag만 바꾼다.
    sed -i.bak -E "s#(name: $REGISTRY/$service, newTag: )\"[^\"]*\"#\1\"$TAG\"#" "$KUSTOMIZATION"
    rm -f "$KUSTOMIZATION.bak"
  done
fi

echo "▶ kubectl apply -k k8s/"
kubectl apply -k "$ROOT/k8s"
for deploy in backend ai frontend; do
  kubectl rollout status "deploy/$deploy" --timeout=6m
done
kubectl get pods -o wide

echo
echo "배포한 태그를 Git에 남기세요: git add k8s/kustomization.yaml && git commit -m \"chore(common): 배포 이미지 태그 갱신\""
