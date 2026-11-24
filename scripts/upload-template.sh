#!/usr/bin/env bash
# 캠퍼스 네트워크에서 실행: 제출 양식 파일을 backend PVC(/data/templates/)에 올린다(UP-01 다운로드용).
# 양식은 저장소에 커밋하지 않는다. 사용: scripts/upload-template.sh <양식 파일 경로(.xlsb)>
set -euo pipefail

FILE="${1:?사용: scripts/upload-template.sh <양식 파일 경로(.xlsb)>}"
[ -f "$FILE" ] || { echo "파일이 없습니다: $FILE" >&2; exit 1; }
case "$FILE" in
  *.xlsb) ;;
  *) echo "k8s/backend.yaml은 .xlsb 양식을 기준으로 설정돼 있습니다. 다른 형식이면 APP_SUBMISSION_TEMPLATE_FILENAME·SUBMISSION_TEMPLATE_LOCATION도 바꾸세요." >&2; exit 1 ;;
esac

context="$(kubectl config current-context 2>/dev/null || true)"
if [ "$context" != "p1-2" ]; then
  echo "kubectl context가 p1-2가 아닙니다(현재: ${context:-없음})." >&2
  exit 1
fi

pod="$(kubectl get pod -l app=backend -o jsonpath='{.items[0].metadata.name}')"
kubectl exec "$pod" -- mkdir -p /data/templates
# kubectl cp는 컨테이너에 tar가 있어야 해서 stdin으로 쓴다.
kubectl exec -i "$pod" -- sh -c 'cat > /data/templates/submission-template.xlsb' < "$FILE"
kubectl exec "$pod" -- ls -l /data/templates/
