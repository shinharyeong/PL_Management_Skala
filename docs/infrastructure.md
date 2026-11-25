# 인프라 구성

SKALA 플랫폼이 제공하는 EKS 공유 클러스터(`skala-pg`)에 팀 네임스페이스 `p1-2` 하나로 배포합니다. 진입은 플랫폼 공용 NGINX Ingress Controller(`public-nginx`), 이미지 저장소는 ECR, 배포는 **main에 push되면 GitHub Actions(`deploy.yml`)가 캠퍼스 PC의 self-hosted runner에서** 합니다. 보안 사후 조치로 이전 클러스터(`skala-2026`)가 삭제되고 2026-11-24에 새로 만들어졌습니다(Harbor → ECR). Namespace·쿼터·RDS·S3·도메인과 플랫폼 Secret(`rds-credentials`, `team-tls`)은 그대로 옮겨졌고, 팀이 만든 것은 다시 만들었습니다. 구성도는 [architecture.png](architecture.png)입니다.

- **1차 배포(현재)**: frontend·backend·ai 세 파드. backend가 파서를 자식 프로세스로 실행하고, 업로드 원본은 backend PVC에 둡니다. 매니페스트는 [`k8s/`](../k8s), 절차는 아래 4절입니다.
- **목표 구성(2차)**: 아래 2절 표처럼 RabbitMQ·parser-worker·db-mcp를 더하고 원본을 S3로 옮깁니다.

> 비밀번호·키·토큰과 RDS 주소는 저장소에 넣지 않습니다(Secret과 팀 내부 문서). 팀 도메인, ECR 저장소 주소, Ingress의 캠퍼스 IP 허용 목록은 매니페스트에 필요해서 `k8s/`에 있습니다.

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

팀 네임스페이스 자원 한도는 requests 기준 **4 vCPU · 16 GiB**(포털 자원 관리에서 변경, limits는 requests의 2배)입니다. 모든 파드를 1개로 두고 HPA는 쓰지 않습니다. PVC는 기본 StorageClass(EBS gp3)로 합계 20Gi · 5개까지입니다.

**1차 배포(현재, `k8s/`)**

| 파드 | requests | limits | 비고 |
| --- | --- | --- | --- |
| frontend | 50m / 64Mi | 200m / 128Mi | nginx 8080(비루트) |
| backend | 500m / 1.5Gi | 1.5 / 2Gi | 파서 포함 이미지, PVC `backend-data` 5Gi를 `/data`에. 볼륨이 한 파드에만 붙으므로 Recreate로 교체 |
| ai | 200m / 512Mi | 1 / 1Gi | 클러스터 내부 전용(`http://ai:8000`). 조회 DB는 팀 DB의 `ai` 스키마(`AI_DATABASE_URL`) |

**목표 구성(2차)**

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
- JVM 힙은 `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75`로 컨테이너 limit의 75%까지 씁니다(플랫폼 가이드 권장).
- app과 db-mcp는 같은 파드 안에서 `localhost`로 MCP 통신합니다. db-mcp는 Service를 만들지 않아 다른 파드가 호출할 수 없습니다.

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
| 메일 | 로그인 인증코드만 발송. `SPRING_MAIL_*`(Secret `app-secrets`)가 있으면 SMTP로 보낸다. 1차 배포는 SMTP 없이 코드를 로그로 확인한다(`APP_AUTH_DEV_LOG_CODE=true`, `kubectl logs deploy/backend \| grep 인증코드`). 로그를 읽을 수 있는 반 계정도 코드를 볼 수 있으므로 발표 전 SMTP로 바꾼다 |

별도 알림 서비스나 파드는 두지 않습니다.

### 코드 구조

```
PL_Management_Skala/
├── .github/workflows/
│   ├── ci.yaml                 # PR·develop·main: 테스트 4종 + 이미지 빌드(push 안 함)
│   └── deploy.yml              # main push: ECR push → kubectl apply -k (캠퍼스 self-hosted runner)
├── frontend/   Dockerfile      # node 빌드 → nginx
├── backend/    Dockerfile      # JDK 21 빌드 → JRE + python3 + parser/src
├── parser/                     # 엑셀 파서(표준 라이브러리만). 1차는 backend 이미지에 포함
├── ai/         Dockerfile      # 보고서 AI(FastAPI). parser/src를 함께 넣음
├── k8s/                        # kubectl apply -k 로 배포하는 매니페스트(Kustomize)
│   ├── kustomization.yaml      # 이미지 태그는 자리표시(<서비스>-main). deploy.yml이 임시 복사본에서 커밋 해시로 바꿔 적용
│   ├── backend.yaml  ai.yaml  frontend.yaml  ingress.yaml
│   ├── once/                   # 개인 kubectl로 한 번만 적용: PVC backend-data, NetworkPolicy(Actions Role 권한 밖)
│   └── jobs/seed-demo.yaml     # 체험 계정 시드 Job(deploy.yml을 seed=true로 수동 실행할 때)
├── docs/
└── scripts/                    # deploy.sh(빌드 없이 태그로 적용)·seed-demo.sh·upload-template.sh, 로컬 개발·검증 스크립트
```

목표 구성(2차)에서는 `parser/`를 parser-worker 이미지로 분리하고, RabbitMQ StatefulSet과 큐 메시지 스키마를 더합니다.

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
- backend와 parser-worker는 팀 계정을 씁니다. db-mcp는 보고서용 뷰(`report` 스키마의 `fact`·`v_op`·`gap`·`explanation`·`dim_version`·`dim_org`, 마이그레이션 `V20261124_1500__report_views.sql`)에만 SELECT 권한이 있는 읽기 전용 역할을 씁니다. SELECT 이외의 구문, 실행 시간, 행 수는 db-mcp가 실행 전에 한 번 더 검사합니다.
- 원본 파일의 메타데이터는 `stored_object`, 파일 자체는 1차 배포에서 backend PVC(`/data/storage`)에 둡니다. S3 `uploads/` 연결은 2차 과제입니다.
- 팀 계정 동시 연결은 50개까지라 backend 연결 풀을 5로 둡니다(`application-prod.yml`). 접속 값은 플랫폼 Secret `rds-credentials`(`SPRING_DATASOURCE_URL`, `DATABASE_URL`, `PGHOST`·`PGPORT`·`PGDATABASE`·`PGUSER`·`PGPASSWORD`·`PGSSLMODE`)에서 받습니다. backend는 `PGUSER`·`PGPASSWORD`를 Spring 이름으로 넘겨받습니다(`k8s/backend.yaml`).
- 공용 인스턴스라 자동 스냅샷으로 팀별 복원이 안 됩니다. 발표 전 등 중요한 시점에는 `pg_dump`로 직접 백업합니다(플랫폼 가이드 "백업과 복원").

## 4. 배포

| 항목 | 구성 |
| --- | --- |
| CI | GitHub Actions(`ci.yaml`, GitHub-hosted runner)가 backend·parser·ai·frontend 테스트와 linux/amd64 이미지 빌드를 확인한다. push는 하지 않는다 |
| 이미지 저장소 | ECR `881490135253.dkr.ecr.ap-northeast-2.amazonaws.com/teams/p1-2/app` 하나. 태그는 `<서비스>-<main 커밋 해시 12자리>`(예: `backend-1af26bbcdbad`)이고 덮어쓸 수 없다(IMMUTABLE). 노드가 IAM으로 받으므로 `imagePullSecrets`를 쓰지 않는다. push는 캠퍼스 네트워크에서만 된다 |
| CD | **main 기준.** develop → main PR이 머지되면 `deploy.yml`이 캠퍼스 PC의 self-hosted runner에서 돈다: GitHub OIDC로 Role `gha-p1-2`를 받아 → 서비스 3개를 빌드해 ECR에 push(같은 태그가 이미 있으면 생략) → `k8s/` 임시 복사본에 태그 적용 → `kubectl apply -k` → rollout 확인. 인증은 main 브랜치와 캠퍼스 IP에서만 허용된다. 지금 배포된 버전은 이미지 태그로 안다. Actions 탭에서 다시 실행(Re-run)하면 같은 커밋을 다시 배포한다 |
| Runner | 캠퍼스 안 PC에 설치(repo → Settings → Actions → Runners). aws CLI v2·kubectl·docker가 있어야 하고, 배포할 때 켜져 있어야 한다. 터미널 창을 닫아도 돌도록 `~/actions-runner`에서 `./svc.sh install && ./svc.sh start`로 서비스 등록한다. 잠자기에 들어가면 배포가 대기 상태로 멈추므로(화면만 꺼지는 것은 괜찮음) 전원 어댑터를 연결하고 "디스플레이가 꺼져 있을 때 자동으로 잠자기 방지"를 켠다. 캠퍼스 밖 네트워크에서는 AWS 인증이 거부된다. 저장소가 공개라 Settings → Actions → General에서 외부 기여자의 fork PR 워크플로는 승인 후에만 돌게 한다 |
| Actions 로그 | 공개 저장소라 누구나 본다. 워크플로에서 Pod 로그·Secret·인증코드를 출력하지 않는다 |
| Actions Role 권한 | Deployment·StatefulSet·Service·ConfigMap·Ingress·HPA·Job·CronJob 생성·수정. Secret·PVC·NetworkPolicy와 삭제는 안 되므로 그런 것은 `k8s/`의 kustomization 밖(`k8s/once/`)에 두고 개인 kubectl로 적용한다 |
| 개인 kubectl | 포털 가이드 「kubectl 접속」 명령으로 `~/.kube/p1-2.yaml`을 만든다. 토큰은 매일 06:00 재발급(26시간 유효), 캠퍼스 네트워크에서만. p1-2 안의 Secret·PVC 생성·삭제, logs·exec·port-forward가 된다. AWS 자격 증명은 없어 ECR push는 Actions로만 한다 |
| 처음 한 번(개인 kubectl) | `kubectl create secret generic app-secrets --from-literal=REPORT_AI_TOKEN="$(openssl rand -hex 32)"`(`OPENAI_API_KEY`·`SPRING_MAIL_*`은 선택) → `kubectl apply -f k8s/once/` → 배포 → `scripts/upload-template.sh <양식.xlsb>`. 빈 DB일 때만 `scripts/seed-demo.sh` |
| 수동 배포 | runner가 없거나 매니페스트만 바꿨을 때, 되돌릴 때: `scripts/deploy.sh [커밋 해시 12자리]`. ECR에 있는 `<서비스>-<해시>` 이미지로 `kubectl apply -k`만 한다(빌드 없음) |
| NetworkPolicy | `k8s/once/networkpolicy.yaml`(ai는 backend에서만). 2026-11-25 확인 결과 플랫폼이 NetworkPolicy를 강제하지 않아(frontend → ai 접속이 열려 있음) 지금은 효과가 없다. 대신 ai 계약 API는 `REPORT_AI_TOKEN`으로 막는다 |
| 비용 | 포털 자원 관리에서 끄고 켠다. 끄지 않으면 밤·주말에도 비용이 쌓인다 |
| 모니터링 | 플랫폼 팀 Grafana 없음. Pod 상태·로그로 확인 |

## 5. 확인 필요

| 항목 | 내용 |
| --- | --- |
| DB 읽기 전용 역할 | 팀 계정으로 역할을 만들 수 있는지. 안 되면 운영 측에 요청하고, 그래도 안 되면 NFR-SEC-004를 db-mcp 검사 기준으로 수정 |
| SMTP 계정 | 인증 메일을 보낼 메일 계정과 발송 한도. 정해지면 `app-secrets`에 `SPRING_MAIL_*`를 넣고 `APP_AUTH_DEV_LOG_CODE`를 `false`로 |
| 파서 분리(2차) | 1차는 backend가 파서를 자식 프로세스로 실행하고 DB 작업 큐를 씀. parser-worker 분리와 RabbitMQ 전환 작업 필요 |
| 원본 S3 전환(2차) | `LocalFileStorage`(PVC)를 S3로 바꾸는 작업. 버킷 `skala-2026-finalproj-p1-2`와 `s3-access`·`s3-config`는 새 클러스터에도 있다. 옮기면 클러스터를 다시 만들어도 파일이 남는다 |
| SSE | 대화 편집을 스트리밍으로 보내면 Ingress에 `proxy-buffering: "off"` 주석 추가(`proxy-read-timeout`은 120초로 이미 있음) |
| 자료 렌더링 | 차트·표 이미지 생성을 브라우저에서 할지 서버에서 할지 결정 |
