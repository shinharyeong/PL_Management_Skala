# 인프라 구성

SKALA 플랫폼이 제공하는 EKS 공유 클러스터에 팀 네임스페이스 하나로 배포합니다. 진입은 플랫폼 공용 NGINX Ingress Controller, 이미지 저장소는 Harbor, 배포는 Argo CD(GitOps)를 사용합니다. 구성도는 [architecture.png](architecture.png)입니다.

> 플랫폼 내부 주소, 버킷 이름, DB 계정 이름은 공개 저장소에 적지 않습니다. 실제 값은 Secret과 팀 내부 문서로 관리합니다.

## 1. 요청 경로

| 경로 | 흐름 |
| --- | --- |
| 화면 `/` | 사용자 → Ingress Controller (HTTPS · 팀 도메인) → frontend |
| API `/api/*` | 사용자 → Ingress Controller → backend |
| AI 요청 | backend → report-ai app (클러스터 내부 호출) |
| 외부 호출 | report-ai app → OpenAI API, backend → SMTP 메일 서버 |

- Ingress Controller는 플랫폼이 운영하는 공용 컴포넌트이고 우리 네임스페이스 밖에 있습니다. 우리는 Ingress 리소스만 만듭니다.
- HTTPS와 팀 도메인은 플랫폼 가이드의 Ingress 예시를 따릅니다.
- 대화 편집 응답을 스트리밍(SSE)으로 보낼 경우 Ingress에 읽기 타임아웃과 버퍼링 해제 설정이 필요합니다.

## 2. EKS 워크로드

팀 네임스페이스 자원 한도는 requests 기준 **2 vCPU · 8 GiB**이고 limits는 requests의 2배입니다. 모든 파드를 1개로 두고 HPA는 쓰지 않습니다.

| 파드 | 컨테이너 | 종류 | CPU | 메모리 | 역할 |
| --- | --- | --- | --- | --- | --- |
| frontend | nginx | Deployment ×1 | 50m | 64Mi | Vue 빌드 결과 서빙 |
| backend | Spring Boot | Deployment ×1 | 500m | 1.5Gi | 인증, 요청, 제출, 소명, 승인, master, 보고서 자료 API. 화면 알림 생성, 마감 예약 작업, 인증 메일 발송. 파싱·PDF 작업을 RabbitMQ에 발행 |
| rabbitmq | RabbitMQ | StatefulSet ×1 · PVC | 200m | 768Mi | 파싱 큐, PDF 분석 큐 |
| parser-worker | Python | Deployment ×1 | 300m | 1Gi | 파싱 작업 소비. S3 원본을 읽어 파싱·검증·적재, Gap 계산 |
| report-ai | app (FastAPI) | Deployment ×1 | 300m | 768Mi | 대화 편집 에이전트, PDF 분석(PDF 작업 소비). OpenAI 호출. DB 접속 정보 없음 |
| report-ai | db-mcp (사이드카) | 같은 파드 | 100m | 128Mi | MCP 서버. 지표 사전·보고서용 뷰 조회, SQL 검사 후 실행. 읽기 전용 DB 자격 증명은 이 컨테이너에만 마운트 |
| **합계** | | | **1.45 vCPU** | **약 4.2Gi** | |

- 남는 여유(CPU 550m, 메모리 약 3.8Gi)로 backend 롤링 업데이트(maxSurge 1)가 가능합니다.
- JVM 힙은 limits가 아니라 requests 기준으로 잡습니다 (예: `-Xmx1g`). requests를 넘긴 메모리는 노드가 부족할 때 먼저 회수됩니다.
- app과 db-mcp는 같은 파드 안에서 `localhost`로 MCP 통신합니다. db-mcp는 Service를 만들지 않아 다른 파드가 호출할 수 없습니다.
- 엑셀 동시 파싱이 필요해 parser-worker를 늘려야 하면 한도를 4 vCPU · 8 GiB로 올립니다.

### RabbitMQ

| 항목 | 설정 |
| --- | --- |
| 배포 | 공식 `rabbitmq:management` 이미지로 StatefulSet ×1. Operator는 CRD·클러스터 권한이 필요해 쓰지 않음 |
| 저장소 | PVC 2Gi. 노드 이름이 `rabbit@rabbitmq-0`으로 고정되어 재시작해도 같은 데이터를 씀 |
| 메모리 | `vm_memory_high_watermark.absolute = 512MiB` (requests 768Mi보다 낮게) |
| 디스크 | `disk_free_limit.absolute = 500MB` |
| 접속 | ClusterIP Service 5672만 공개. 관리 화면 15672는 `kubectl port-forward`로만 접속 |
| 계정 | `RABBITMQ_DEFAULT_USER/PASS`를 Secret으로 주입 |
| 토폴로지 | `definitions.json`을 ConfigMap으로 넣어 시작 시 로드 |

| 큐 | 생산자 | 소비자 | 설정 |
| --- | --- | --- | --- |
| `parse.jobs` | backend | parser-worker | quorum, `x-delivery-limit` 3, 실패 시 `parse.jobs.dlq` |
| `pdf.jobs` | backend | report-ai app | quorum, `x-delivery-limit` 3, 실패 시 `pdf.jobs.dlq` |

- 작업 상태의 기준은 DB 작업 테이블입니다. 메시지에는 작업 ID만 담고, 소비자는 `QUEUED → RUNNING` 조건부 갱신에 성공한 경우에만 처리합니다. 같은 메시지가 두 번 와도 한 번만 처리됩니다.
- 소비자는 수동 ack, prefetch 1로 받고 DB 커밋 후 ack 합니다.
- backend는 publisher confirm을 켭니다. 발행에 실패해 `QUEUED`로 남은 작업은 backend 예약 작업이 다시 발행합니다.

### 알림

| 종류 | 처리 |
| --- | --- |
| 화면 알림 (조직 제출, 소명 회신) | 이벤트를 처리하는 backend API가 같은 트랜잭션에서 `notification` 테이블에 저장 |
| 화면 알림 (기한 경과) | backend 예약 작업(`@Scheduled`)이 마감 시각을 확인해 저장. backend를 2개 이상으로 늘리면 ShedLock 등으로 중복 실행 방지 |
| 화면 반영 | N1 알림 모아보기와 메뉴 배지는 주기적 조회(폴링)로 시작 |
| 메일 | 로그인 인증코드만 실제 발송. backend가 외부 SMTP로 전송하고 계정 정보는 Secret으로 관리 |

별도 알림 서비스나 파드는 두지 않습니다.

### 코드 구조

```
skala_final_project/
├── .github/workflows/   # 서비스별 빌드 워크플로 (paths 필터)
├── frontend/            # Dockerfile: node 빌드 → nginx
├── backend/
├── parser/              # parser-worker
├── report-ai/
│   ├── app/             # FastAPI 에이전트 · PDF 분석
│   └── db-mcp/          # MCP 서버
├── deploy/              # Argo CD가 감시하는 매니페스트 (Kustomize)
│   ├── base/
│   │   ├── frontend/  backend/  parser-worker/  report-ai/  rabbitmq/
│   │   └── ingress.yaml
│   └── kustomization.yaml   # 이미지 태그는 여기서만 바뀜
├── contracts/           # 큐 메시지 JSON 스키마 (backend · Python 공용)
├── experiments/parser_ai/
├── docs/
└── scripts/
```

## 3. 데이터

### S3 (팀 버킷 1개, prefix로 분리)

| prefix | 내용 | 접근 주체 |
| --- | --- | --- |
| `uploads/excel/` | 원본 엑셀 | 브라우저 presigned 업로드, parser-worker 읽기 |
| `uploads/pdf/` | 틀 분석용 PDF | 브라우저 presigned 업로드, report-ai app 읽기 |
| `materials/` | 자료 틀 분석 결과, 만든 자료 | report-ai app·backend 쓰기, backend 읽기 |

- 파드는 플랫폼이 제공한 ServiceAccount(IRSA)로 키 없이 접근합니다. 플랫폼 ServiceAccount가 하나라서 파드별로 prefix 권한을 나누기 어렵습니다. 대신 각 서비스 코드가 자기 prefix만 쓰도록 제한합니다.
- 프론트는 frontend 파드가 서빙하므로 버킷에 `frontend/`를 두지 않습니다.

### RDS PostgreSQL

- 여러 팀이 같이 쓰는 인스턴스이고 팀 DB 계정이 하나 주어집니다.
- 제출본, 버전, 소명, master, 자료 틀, 알림을 저장합니다.
- backend와 parser-worker는 팀 계정을 씁니다. db-mcp는 보고서용 뷰에만 SELECT 권한이 있는 읽기 전용 역할을 씁니다. SELECT 이외의 구문, 실행 시간, 행 수는 db-mcp가 실행 전에 한 번 더 검사합니다.
- 현재 원본은 DB `stored_object`에 저장합니다. S3 `uploads/` 연결은 운영 전환 과제입니다.

## 4. 배포

| 항목 | 구성 |
| --- | --- |
| CI | GitHub Actions에서 빌드·테스트 후 Harbor에 이미지 push, `deploy/`의 이미지 태그 갱신 |
| 이미지 저장소 | Harbor 팀 비공개 프로젝트 (용량 한도 있음). Trivy 스캔. 오래된 태그는 정리 |
| CD | Argo CD가 `deploy/` 변경을 감지해 팀 네임스페이스에 동기화 |
| 모니터링 | 플랫폼 팀 Grafana 없음. `kubectl logs`, `kubectl top`으로 확인 |

## 5. 확인 필요

| 항목 | 내용 |
| --- | --- |
| DB 읽기 전용 역할 | 팀 계정으로 역할을 만들 수 있는지. 안 되면 운영 측에 요청하고, 그래도 안 되면 NFR-SEC-004를 db-mcp 검사 기준으로 수정 |
| StorageClass | RabbitMQ PVC에 쓸 수 있는 StorageClass (`kubectl get storageclass`) |
| ingressClassName | 가이드 Ingress 예시의 클래스. SSE 타임아웃 설정 방법이 달라짐 |
| SMTP 계정 | 인증 메일을 보낼 메일 계정과 발송 한도 |
| 현재 파서 실행 방식 | 지금 코드는 backend가 파서를 자식 프로세스로 실행하고 DB 작업 큐를 씀. parser-worker 분리와 RabbitMQ 전환 작업 필요 |
| 자료 렌더링 | 차트·표 이미지 생성을 브라우저에서 할지 서버에서 할지 결정 |
