#!/usr/bin/env bash
# 캠퍼스 네트워크에서 실행: main 기준으로 이미지 빌드(linux/amd64) → Harbor push → kubectl apply → 배포 확인.
# 배포 기준은 main이다. 로컬 main이 origin/main과 같고 커밋 안 한 변경이 없어야 한다.
# 이미지 태그는 main 커밋 해시이며, k8s/ 임시 복사본에만 넣어 적용한다(저장소 파일은 바꾸지 않는다 → 커밋 불필요).
#
# 사전 준비: docker login skala-registry.skala-ai.com -u p1-2 (한 번), KUBECONFIG=~/.kube/p1-2.yaml
# 사용: scripts/deploy.sh                  backend·ai·frontend 모두 새로 빌드
#       scripts/deploy.sh backend          backend만 새로 빌드(나머지는 지금 배포된 이미지 유지)
#       SKIP_BUILD=1 scripts/deploy.sh     빌드 없이 k8s/ 설정만 반영(지금 배포된 이미지 유지)
set -euo pipefail

REGISTRY=skala-registry.skala-ai.com/p1-2
ALL=(backend ai frontend)
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILD=("$@")
[ ${#BUILD[@]} -eq 0 ] && BUILD=("${ALL[@]}")
[ "${SKIP_BUILD:-0}" = "1" ] && BUILD=()

fail() { echo "$*" >&2; exit 1; }

context="$(kubectl config current-context 2>/dev/null || true)"
[ "$context" = "p1-2" ] || fail "kubectl context가 p1-2가 아닙니다(현재: ${context:-없음}). export KUBECONFIG=~/.kube/p1-2.yaml 후 다시 실행하세요."

# 배포 기준: 최신 main
branch="$(git -C "$ROOT" rev-parse --abbrev-ref HEAD)"
[ "$branch" = "main" ] || fail "main 브랜치에서만 배포합니다(현재: $branch). git switch main && git pull 후 다시 실행하세요."
[ -z "$(git -C "$ROOT" status --porcelain)" ] || fail "커밋하지 않은 변경이 있습니다. main을 깨끗한 상태로 맞춘 뒤 실행하세요."
git -C "$ROOT" fetch -q origin main
[ "$(git -C "$ROOT" rev-parse HEAD)" = "$(git -C "$ROOT" rev-parse origin/main)" ] \
  || fail "로컬 main이 origin/main과 다릅니다. git pull 후 다시 실행하세요."
TAG="$(git -C "$ROOT" rev-parse --short=12 HEAD)"

for service in ${BUILD[@]+"${BUILD[@]}"}; do
  case " ${ALL[*]} " in *" $service "*) ;; *) fail "알 수 없는 서비스: $service (backend·ai·frontend)";; esac
  echo "▶ $service:$TAG 빌드·push"
  docker buildx build --platform linux/amd64 -f "$ROOT/$service/Dockerfile" -t "$REGISTRY/$service:$TAG" --push "$ROOT"
done

# 서비스별 배포 태그: 이번에 빌드한 것은 TAG, 나머지는 지금 클러스터에 떠 있는 태그
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
cp -R "$ROOT/k8s" "$WORK/k8s"
for service in "${ALL[@]}"; do
  if [[ " ${BUILD[*]-} " == *" $service "* ]]; then
    tag="$TAG"
  else
    image="$(kubectl get deploy "$service" -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null || true)"
    tag="${image##*:}"
    [ -n "$image" ] && [ "$tag" != "$image" ] || fail "$service 이 아직 배포된 적이 없습니다. 처음에는 빌드를 포함해 실행하세요."
  fi
  echo "  $service → $tag"
  sed -i.bak -E "s#(name: $REGISTRY/$service, newTag: )\"[^\"]*\"#\1\"$tag\"#" "$WORK/k8s/kustomization.yaml"
done
rm -f "$WORK/k8s/kustomization.yaml.bak"

echo "▶ kubectl apply -k (main $TAG)"
kubectl apply -k "$WORK/k8s"
for deploy in "${ALL[@]}"; do
  kubectl rollout status "deploy/$deploy" --timeout=6m
done
kubectl get pods -o wide
