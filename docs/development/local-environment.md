# 로컬 Docker 실행 환경

저장소 루트의 [compose.yaml](../../compose.yaml)은 `skala-local` 프로젝트로 Vue 프런트, Spring Boot 백엔드와 Python 파서, PostgreSQL을 실행한다. Python은 백엔드 컨테이너의 별도 자식 프로세스로 실행하며 추가 HTTP 서비스나 공유 파일 디렉터리를 만들지 않는다.

## 구성

| 서비스 | 런타임 | 로컬 주소 | 저장 위치 |
| --- | --- | --- | --- |
| `frontend` | Node 22에서 Vite 빌드, nginx에서 정적 파일 제공 | `http://localhost:5173` | 빌드 이미지; 런타임 임시는 메모리 `/tmp` |
| `backend` | Java 21, Python 3.11 표준 라이브러리 | `http://localhost:8080` | 원본·파서 결과·업무 자료는 PostgreSQL; 런타임 임시는 메모리 `/tmp` |
| `postgres` | PostgreSQL 16 | `127.0.0.1:15432` | named volume `skala-local_postgres_data` |

세 포트는 `127.0.0.1`에만 공개한다. Docker 내부에서는 `postgres:5432`, `backend:8080`을 사용한다. 프런트의 `/api`, `/actuator`, `/dev`는 nginx가 백엔드로 전달하며 Vue 경로 새로고침은 `index.html`로 처리한다. 브라우저에 서버 Bearer 토큰을 빌드 변수로 넣지 않는다.

프런트 빌드는 `package-lock.json`을 이용한 `npm ci`, 백엔드는 Gradle 8.12의 `bootJar`를 실행한다. Docker 빌드 자체는 테스트 통과를 의미하지 않는다. 원본 `Data/`, `.env`, 호스트 `node_modules`, Gradle·Python 캐시는 [.dockerignore](../../.dockerignore)로 빌드 컨텍스트에서 제외한다.

## 로컬 비밀 설정

저장소 루트 `.env`를 별도로 준비한다. `.gitignore`와 `.dockerignore`가 이 파일을 제외하며 실행 스크립트는 기존 파일을 생성·덮어쓰기하지 않는다. 비밀번호와 두 토큰은 서로 다른 무작위 값으로 두고 파일 권한을 `600`으로 제한한다. 실제 기업 계정 대신 demo 전용 이메일을 사용한다.

| 필수 변수 | 의미 |
| --- | --- |
| `POSTGRES_DB` | demo DB 이름, 예: `skala_demo` |
| `POSTGRES_USER` | 로컬 DB 사용자, 예: `skala` |
| `POSTGRES_PASSWORD` | 로컬에서 생성한 DB 비밀번호 |
| `LOCAL_PLANNER_EMAIL` | demo 경영기획 담당자 이메일 |
| `LOCAL_PLANNER_TOKEN` | 공백 없는 32자 이상 무작위 서버용 토큰 |
| `LOCAL_ORGANIZATION_EMAIL` | demo 조직 담당자 이메일 |
| `LOCAL_ORGANIZATION_TOKEN` | 기획 토큰과 다른 32자 이상 무작위 서버용 토큰 |
| `APP_DEMO_FRONTEND_ORIGIN` | demo 세션의 허용 출처 목록, `http://localhost:5173,http://127.0.0.1:5173` |

Compose가 필수 값의 누락·빈 값을 거부한다. `.env`를 셸 스크립트로 `source`하지 않으며 `docker compose config --quiet`로 값 노출 없이 구성만 검사한다. 값이 들어간 전체 `docker compose config` 출력은 공유하지 않는다.

이 Compose에서만 `local,demo` 프로필을 활성화한다. 기본 애플리케이션의 인증 거부 정책은 유지한다. demo 사용자·조직 초기화와 브라우저 세션 발급은 해당 백엔드 프로필의 구현이 담당하며, 이 환경 설정은 운영 로그인이나 운영 초기 자료를 정의하지 않는다. nginx는 `Host $http_host`로 원래 호스트와 포트까지 전달하므로 세션의 Origin·Host 검사가 같은 프런트 출처를 확인할 수 있다.

## 시작과 종료

Docker Desktop을 실행한 뒤 저장소 루트에서 호출한다.

```bash
bash scripts/dev-up.sh
```

상위 `Final/` 작업공간에서 시작한다면 먼저 `cd skala_final_project-develop`를 실행한다. 이 문서의 `scripts/...`와 `docker compose ...` 명령은 모두 그 저장소 루트를 기준으로 한다.

[dev-up.sh](../../scripts/dev-up.sh)는 `.env`와 Docker 엔진을 확인하고, 비밀값을 출력하지 않는 구성 검사 후 이미지를 빌드한다. DB → 백엔드 → 프런트 순서로 healthcheck를 기다린 뒤 서비스 상태와 프런트 주소를 출력한다. 최초 실행은 이미지·의존성 다운로드가 필요하다. 이후 실행도 소스 변경을 반영하되 빌드 캐시와 DB 볼륨을 재사용한다.

```bash
bash scripts/dev-down.sh
```

[dev-down.sh](../../scripts/dev-down.sh)는 컨테이너와 네트워크만 내리고 DB 볼륨·이미지·캐시·`.env`를 보존한다. 이 프로젝트의 종료 절차에는 `down -v`, volume 삭제, 기존 DB 초기화를 포함하지 않는다. `.env`의 DB 비밀번호를 바꾸는 것만으로 이미 초기화된 PostgreSQL 사용자의 비밀번호가 바뀌지는 않으므로 기존 설정을 유지한다.

## 상태 확인과 통합 검증

```bash
docker compose config --quiet
docker compose ps
curl --fail http://localhost:5173/healthz
curl --fail http://localhost:5173/actuator/health
```

PostgreSQL은 `pg_isready`, 백엔드는 `/actuator/health`, 프런트는 `/healthz`로 상태를 확인한다. `service_healthy` 의존성을 사용해 단순 컨테이너 시작보다 실제 준비 상태를 기다린다. [Docker Compose 시작 순서 문서](https://docs.docker.com/compose/how-tos/startup-order/)

브라우저 통합 검증은 프런트의 demo 역할 선택 → 사용자 조회 → 요청함 → 전체 파일 접수 → 파싱 상태·진단 → 업무 검증·적재 확인 → 최종 제출(PENDING) → 기획자의 제출 내역 조회 순서로 수행한다. 최초 연간 검증 범위와 후속 승인/마스터를 구분한다. 검증에 사용한 접수·원본·결과는 로컬 PostgreSQL 볼륨에 남는다. 실행 결과는 아래 날짜별 검증 기록과 작업 보고에서 구분한다.

같은 경로를 HTTP로 확인하는 [smoke-local.py](../../scripts/smoke-local.py)는 저장소 루트에서 다음과 같이 실행한다.

```bash
python3 -B scripts/smoke-local.py --base-url http://localhost:5173
```

스크립트는 개발 역할 세션과 메모리 합성 양식으로 접수·멱등 재전달/충돌·권한·실제 Python 파싱·금액 문자열·원본 동일성·손상 원본 실패를 확인한다. 정상 완료 시 개발 DB에 정상·손상 초안 두 개가 추가되며, 중간 실패나 재실행 때 기존 자료를 삭제하지 않는다. 토큰·응답 본문·원본 값은 출력하지 않고 결과 이름만 콘솔에 표시한다. `Data/`와 임시 XLSX 파일은 사용하지 않는다.

화면 흐름과 함수는 [프런트 안내](frontend-guide.md), 개발 전용 `/api/dev` 2개와 파싱 후보 조회는 [demo API](demo-api.md), Java 소스·테스트 함수는 [백엔드 함수 명세](backend-function-reference.md)를 따른다. 업무 API 계약 0.7-draft의 전체 설계 35개 중 18개가 현재 구현이며 개발 전용 2개 경로는 그 집계에 포함하지 않는다.

컨테이너의 stdout/stderr는 Docker 로그로 조회할 수 있지만 애플리케이션 안에 파일 로그를 추가하지 않는다. 파서에는 원본 바이트를 표준 입력으로 전달하고 `python3 -B`와 `PYTHONDONTWRITEBYTECODE=1`을 사용한다. nginx 프록시는 원래 요청 경로를 유지하며 업로드를 백엔드로 스트리밍한다. [nginx proxy 모듈 문서](https://nginx.org/en/docs/http/ngx_http_proxy_module.html)

## 파일과 변경 경계

| 파일 | 역할 |
| --- | --- |
| [compose.yaml](../../compose.yaml) | 로컬 포트·필수 환경값·의존성·상태 확인·DB 볼륨 |
| [.dockerignore](../../.dockerignore) | 비밀값·원본·호스트 산출물의 컨텍스트 제외 |
| [backend/Dockerfile](../../backend/Dockerfile) | Gradle 빌드와 Java 21·Python 3.11 런타임 |
| [frontend/Dockerfile](../../frontend/Dockerfile) | 잠금 파일 기반 Vue 빌드와 nginx 런타임 |
| [frontend/nginx.conf](../../frontend/nginx.conf) | 같은 출처 API 프록시·정적 자산·SPA fallback |
| [scripts/dev-up.sh](../../scripts/dev-up.sh) | 기존 설정 확인·빌드·시작·준비 대기 |
| [scripts/dev-down.sh](../../scripts/dev-down.sh) | DB 볼륨을 보존하는 종료 |
| [scripts/smoke-local.py](../../scripts/smoke-local.py) | 개발 DB에 합성 초안을 추가하는 실제 HTTP·파서 연결 검증 |

두 실행 스크립트는 별도 함수를 선언하지 않는다. 목적·입력·종료 코드·실패·부작용은 각 파일 상단 명세가 기준이다. Gradle/npm 의존성 캐시는 Docker 빌드 캐시 안에만 추가하고, 원본 데이터 경로나 호스트 소스를 컨테이너의 쓰기 볼륨으로 연결하지 않는다.

## 2026-11-16 통합 검증 기록

| 확인 경계 | 결과 |
| --- | --- |
| Java 단위·통합 테스트 | 57개 모두 통과 |
| Docker 서비스 | frontend·backend·postgres 3개 healthcheck 통과 |
| 실제 PostgreSQL·Python smoke | 원본 바이트 동일성, 정밀 소수·0의 문자열 보존, 같은 멱등키의 다른 요청 409, 기획자 업로드 403, 다른 조직 파일명 422, 손상 XLSX의 비동기 FAILED 모두 통과 |
| 브라우저 조직 담당자 | 2027 요청 → 합성 양식 업로드 → 파싱·정규화 금액 24개 → 제출 이력 조회 통과 |
| 브라우저 금액 표시 | 매출 `1,000,000.50`, 비용 `300,000.25`, `0` 표시 확인 |
| 브라우저 경영기획팀 | 2028 연간 요청 생성과 대상 2개 조직 등록 확인 |
| PostgreSQL·백엔드 재시작 | 재시작 후 3개 서비스 healthy. 전후 조직 2·사용자 2·요청 2·초안 6·저장 객체 12개로 동일 |
| 저장 원본·스키마 | 업로드 원본 6개의 SHA-256이 DB의 source hash와 모두 일치. Flyway V1 성공 확인 |
| Figma SVG 4개 | 실제 HTTP 200, 원본 바이트 동일, 각각 11×11 확인 |
| 비밀값·원본 경계 | 로컬 `.env`는 Git 추적 제외, `Data/` 미접근 |

검증·체험으로 생성한 초안과 2028 연간 요청은 개발 DB에 남겨 두었다. 기존 자료를 정리하거나 삭제하지 않았다. 위 DB 개수는 해당 검증 시점의 값이며 이후 화면 사용으로 늘어날 수 있다. 이 기록은 접수·파싱·후보 조회와 개발 화면 연결에 관한 것이며, 미구현 업무 검증·최종 제출·승인·공식 마스터가 통과했다는 뜻은 아니다.

## 사업부별 기본 더미 검증

[verify-dummy.py](../../scripts/verify-dummy.py)는 사용자가 소유한 기본 사업부 XLSX 17개를 현재 API로 검증한다. 파일 위치와 사업부 코드는 `Data/dummy/manifest_baseline.json`에서 읽고 전체 마스터 파일은 제출 대상에서 제외한다. 파일 내용과 디스크 이름을 변경하지 않고 multipart 전송명만 `F10_2026-PLAN.xlsx`처럼 구성한다.

```bash
python3 -B scripts/verify-dummy.py --upload
```

다른 입력 폴더는 `--source`, 로컬 포트는 `--base-url`로 지정한다. 이 명령은 2026 연간 요청이 없으면 만들고 생성 시점의 활성 조직 모두를 대상으로 고정한다. 기존 2027~2029 요청의 대상은 바꾸지 않는다. F 계열 사업부의 개발 계정이 준비된 `local,demo` 서버가 필요하다. 요청·초안·원본·파싱 결과는 기존 PostgreSQL 볼륨에 남으며 별도 XLSX 사본·보고서·캐시를 만들지 않는다.

파일 SHA-256을 멱등키에 포함하므로 같은 검증 식별자·요청·조직·원본의 24시간 내 재실행은 초안을 재사용한다. 멱등 기록이 만료된 뒤에는 같은 키도 새 초안을 만들 수 있으며 기존 초안은 보존된다. 파서 변경 뒤 실제 재파싱이 필요하면 `--verification-id parser-1.3`처럼 새 식별자를 지정한다. 기존 결과는 덮어쓰지 않고 사업부별 새 초안 17개를 추가하므로 실행에는 기존 업로드 승인 범위가 필요하다. 저장 원본의 SHA-256, 로컬 원본 불변, 초안 유지, 기획자 조회, 조직 간 접근 차단, 첫 페이지 숫자 셀의 OOXML 원문 대조를 확인한다. 이 스크립트의 숫자 셀 검사는 표본 대조이며 전체 업무 손익 집계 검증을 대신하지 않는다. 파싱 오류·미확정 항목은 그대로 표시한다.

| 함수 | 입력·반환 | 예외·부작용·호출 |
| --- | --- | --- |
| `load_http_helpers` | 없음 → ModuleType | 기존 `smoke-local.py` 로딩, 메모리 모듈 등록 |
| `json_response` | 응답·상태 → dict | 잘못된 HTTP/JSON 오류, 비밀 응답 출력 없음 |
| `open_session` | HTTP 모듈·출처·역할·코드 → dict | 개발 세션 HTTP, 토큰은 메모리만 사용 |
| `check_source_cells` | 경로·후보 → int | ZIP/XML 숫자 원문 대조, 원본 읽기만 수행 |
| `run` | 출처·폴더·verification_id(기본 baseline) → None | manifest → 세션 → 요청 → 17개 접수·폴링·읽기 검증, 로컬 DB 저장 |
| `main` | CLI → None | 루프백·명시적 `--upload` 확인 → run |

각 함수의 Args·Returns·Raises·Side Effects는 소스 docstring에 함께 관리한다.

### 2026-11-16 사업부 더미 실행 결과

2026 연간 요청 ID 4를 생성했고 대상은 기존 체험 조직 2개를 포함한 활성 조직 19개다.
17개 F 계열 사업부의 원본을 각각 초안 ID 8~24로 접수했다. 기존 초안과 요청은 보존했다.
같은 원본의 멱등 재실행은 기존 ID를 유지했다. 초안의 다른 조직 조회는 존재를 노출하지 않는
기존 계약대로 404이며, 기획자 조회는 200이다. 최초 검증 스크립트의 403 기대값을 이 계약에 맞춰 정정했다.

| 확인 항목 | 결과 |
| --- | --- |
| 원본 업로드·Python 파싱 | 17/17 접수, 각 9시트·19,376후보 해석, 파일 거부 FAILED 없음 |
| 결과 상태 | 전부 NEEDS_REVIEW, DRAFT 유지·업무 검증 NOT_RUN·최종 제출 불가 |
| 저장 원본·로컬 원본 | 17개 모두 다운로드 SHA-256 일치, Data 원본 읽기 전후 불변 |
| API 표본 원문 | 파일당 첫 100개, 총 1,700개 숫자 셀의 위치·원문이 ZIP 캐시와 일치 |
| 전체 2026 계획 값 | 파서 직접 검증에서 12,036셀 원문×1,000=정규화 원 단위 일치, 사업부별 월 합계·건수 일치 |
| 조회 권한 | F10과 나머지 조직 사이 양방향 404, 기획자 전체 조회 200 |
| 최종 Java 테스트 | 62개 통과, 실패·오류·건너뜀 0개 |
| 파서 회귀 테스트 | 54개 중 52개 통과, 외부 데이터 의존 2개 건너뜀 |
| 브라우저 실제 파일 | 사업부1 선택 → 원본 파일 선택 → 전송명 확인 → 파싱 결과까지 확인. 추가 초안 ID 25, 원본 다운로드 바이트 일치 |
| 최종 배포 | 프런트 production 빌드 통과, Docker 3개 서비스 healthy, 브라우저 초안에 `stdlib-bridge/1.1` 적용 확인 |

macOS 파일 선택기가 전달하는 분해형 한글(NFD) 때문에 정상 원본 이름이 거부되는 문제를
실제 브라우저에서 확인해 수정했다. 이름 비교에만 NFC 정규화를 적용하며 디스크 이름과
파일 바이트는 변경하지 않는다. 17개 파일의 NFC/NFD 34개 사례를 확인했다.
원본·전송 예정 이름을 표시하고 사용자가 확인하기 전에는 파싱 버튼을 비활성화한다.
브라우저 검증 초안 25는 API 일괄 검증 초안 8과 별도로 남겨 두었다.

`mappedCount=3,392`, `unresolvedCount=1,725`는 파일별 값이며 승인된 업무 항목 수가 아니다.
미사용 비용 양식 345행에 남은 조직 `#N/A`·누락 코드의 연간 수식과 코드표의 조직명 충돌 진단을
자동 삭제하거나 승인으로 바꾸지 않았다. 상세 원인·성능·기간 근거는
[파서 재검증 기록](../../parser/docs/development/function-reference.md#2026-11-16-사업부-원본-17개-메모리-재검증)에 있다.
기본 파일의 9월 실적/추정 헤더는 manifest 설명과 다르므로, 이번 금액 일치 판정은 2026 PLAN에 한정한다.
월별 오류 시나리오 1,458개, 승인·소명·마스터 집계는 이번 실행 대상이 아니다.

### 미사용 비용 양식 처리 보완 후 재검증

같은 날 `stdlib-bridge/1.2`로 보완했다. 앞선 `ORGANIZATION_NAME_CONFLICT`의 직접 원인은
실제 입력 조직의 이름 충돌이 아니라 빈 비용 양식 행의 조직 조회 오류를 이름으로 비교한 것이다.
미사용 행의 확인 조건과 활성 행 보호 경계는 [매핑 규칙](../../parser/docs/development/mapping-rules.md)을 따른다.
원본 후보를 삭제하지 않고 `inactive_template`로 분류하며, 기존 초안·결과는 그대로 보존한다.

```bash
python3 -B scripts/verify-dummy.py --upload --verification-id parser-1.2
```

| 검증 경계 | 실제 결과 |
| --- | --- |
| 새 HTTP 접수 | 요청 4의 초안 28~44, 사업부 17개 모두 SUCCEEDED·미확정 0·진단 0 |
| 파일별 보존 | 후보 19,376·정규화 3,392 유지. 미사용 양식은 345행으로 별도 안내 |
| 변경 전 매핑과 독립 대조 | 전체 후보 329,392개의 source·raw_value·formula·value·period 완전 동일 |
| 실제 업무 금액 | 57,460개 기존 조직 필드 동일. 2026 PLAN 12,036개가 원문×1,000과 일치, 조직·매출/비용·월의 합계 408개 동일 |
| 원본·보안 | 17개 로컬/저장 원본 해시 동일, 조직 간 404·기획자 조회·동일 멱등키 재전달 확인 |
| 브라우저 | 사업부1 원본으로 추가 초안 45 생성. 파싱 완료·진단 0·미사용 양식 345행 안내 확인 |
| 회귀 검증 | Python 관련 8개 모듈 103개 중 102개 통과·외부 자료 의존 1개 건너뜀. Java 64개 전부 통과 |
| 실행 환경 | frontend production 빌드 통과. Docker frontend·backend·postgres 모두 healthy |

2026 PLAN 원문에는 숫자 0이 없어 실제 0 보존과 입력 후 재검토 전환은 독립 합성 회귀로 검증했다.
미사용 판정은 알려진 양식의 엄격한 조건을 만족할 때만 적용한다. 미확인 양식·수식·입력·주석은
활성 검증 대상으로 남긴다. 이번 SUCCEEDED는 파싱 단계의 결과이며 전체 제출 완전성·소명·승인·
공식 마스터 검증 완료를 의미하지 않는다. 이전 초안을 열면 이전 파서의 진단이 그대로 보인다.


## 최초 연간 검증·최종 제출 대조

[verify-submission.py](../../scripts/verify-submission.py)는 이미 접수된 2026 연간 요청의 조직별 최신 제출본을 읽고 저장 원본과 로컬 기본 XLSX의 SHA-256이 같은지 확인한다. 최신 파일이 다르면 중단하며 과거 파일을 자동 선택하거나 새 원본을 업로드하지 않는다. stdlib-bridge/1.3 결과가 필요하다.

```bash
python3 -B scripts/verify-submission.py --validate
python3 -B scripts/verify-submission.py --submit --codes F10
```

`--validate`는 선택한 DRAFT의 업무 검증·금융 적재를 실행한다. `--submit`은 같은 검증과 금액 대조 후 준비된 초안을 최종 제출하고 동일 명령의 멱등 재전달을 확인한다. 둘은 서로 배타적이며 `--codes`를 생략하면 17개 사업부가 대상이다. 기존 PENDING은 읽기 대조만 수행한다. `--source`와 `--base-url`로 원본 폴더·루프백 출처를 지정한다. 토큰은 메모리에서만 사용한다.

이 스크립트는 기본 더미의 확인된 규모(조직별 59행·708개 월 값·1,836개 보고값)와 천원 단위를 대상으로 한다. 전체 17개에서는 적재 금액 12,036개를 독립 OOXML 숫자 원문×1,000과 정확히 대조하고, 보고값 31,212개를 상세 합계 및 매출 합계·비용 합계·영업이익의 관계와 대조한다. 각 파일의 첫/마지막 적재 셀에 대한 source-cells 조회도 확인한다. 이는 모든 자유 양식에 대한 일반 검증기가 아니며 실제 실행 결과는 별도 콘솔 결과로 확인한다.

원본 파일·기존 제출·과거 파싱/검증 객체를 삭제하거나 다시 저장하지 않는다. 검증은 해당 DRAFT의 선택 적재행과 현재 검증 참조를 갱신하며, 최종 제출은 PENDING·회차·제출 시각을 기록한다. 결과는 콘솔에만 출력하고 보고서·캐시·XLSX 사본은 만들지 않는다. 이 명령은 승인·공식 마스터를 생성하지 않는다.

| 함수 | 입력 → 반환 | 예외·부작용·호출 |
| --- | --- | --- |
| `helpers` | 없음 → ModuleType | verify-dummy 모듈 메모리 로딩; -B로 캐시 없음 |
| `original_numbers` | 원본 Path → (시트, 셀)별 Decimal | ZIP/XML/숫자 오류; 원본 OOXML 읽기만 |
| `page_items` | HTTP·검사기·출처·토큰·endpoint → 전체 items | HTTP/cursor 오류; 연도2026·PLAN·100건 페이지 GET |
| `run` | 출처·폴더·submit 여부·조직 집합 → None | 원본/업무/HTTP 대조 오류; 로컬 검증·선택적 제출·콘솔 출력 |
| `main` | CLI → None | 옵션 오류; 명시 action과 루프백 검사 후 run |

각 함수는 목적·Args·Returns·Raises·Side Effects를 소스 docstring에 명시한다. 스크립트 명령 예시는 실행 권한을 추가하는 설정이 아니며 현재 작업에서 허용된 로컬 검증·최종 제출 범위로 사용한다.


### 최초 연간 제출 연결의 통합 확인

통합 실행에서 Java 테스트 89개가 모두 통과했고 최신 Docker 빌드의 frontend·backend·postgres가 healthy임을 확인했다. 아래 내용은 이전 파싱 단계의 57개/64개 테스트 기록과 구분한 검증 범위다.

| 검증 경계 | 확인 결과 |
| --- | --- |
| 17개 실제 기본 원본 | 업무 검증 완료, 전체 1,003행·12,036개 월 금액 적재 |
| 독립 원본 대조 | 12,036개가 OOXML 숫자 원문×1,000과 정확히 일치 |
| 보고 집계 | 31,212개 전체를 상세 합계·매출/비용 총계·OP와 독립 대조해 일치 |
| 답안지 캐시 | 미세차 26건, 최대 0.000001원. CONTROL_CACHE_PRECISION 경고로 근거 보존, 적재/집계값 보정 없음 |
| 최종 제출 | 17개 사업부 모두 HTTP 최종 제출·PENDING·seq=1 확인. F10은 브라우저 재검증→최종 제출도 확인 |
| 기획자 조회 | 브라우저 요청 현황→F40 제출본 49→적재 59행/708값→캐시 차이 0.000001원 진단→원본 AC205 확인 |
| 보존 | 모든 원본의 로컬/저장 SHA-256 일치, 기존 원본·초안·과거 결과 삭제 없음 |

17개 최종 제출본은 PENDING이며 승인·공식 마스터·월간 비교를 검증했다는 뜻은 아니다. 파싱 후보의 amountUnit은 원 단위가 확인된 경우만 KRW이며, 단위가 확인되지 않은 후보를 원으로 표시하지 않는다.


## 2026-11-16 연간 제출 승인·반려 검증

경영기획팀 → 요청·검토 → 연도 선택 → 조직의 `검토`에서 승인 또는 사유를 입력한 반려를 실행한다. 역할 전환 후 조직 담당자의 제출 이력에서 검토자·시각·반려 사유를 확인한다. 반려 후에는 `전체 파일 다시 제출`로 새 초안을 올린다. 이전 반려본은 유지하고 최종 제출에서만 회차가 증가한다. 금액이 달라지면 수정 소명이 필요하므로 아직 최종 제출을 제한한다.

- 전체 Java 테스트 98개 통과, 실패·오류·건너뜀 0개. 권한, 입력 형식, 토큰 충돌, 동일 키 재전달, 종료본 재판단 차단, 승인/반려 경합, 실패 롤백, 검증 근거/적재 누락 차단, 반려 후 재제출을 포함한다.
- Docker frontend 프로덕션 빌드 55개 모듈과 backend bootJar 성공. 기존 PostgreSQL 볼륨을 유지한 채 앱 이미지만 갱신했다.
- 실제 nginx→Java→PostgreSQL API에서 별도 합성 요청의 반려→동일 원본 업로드→검증→2회차 제출→승인을 확인했다. 검토자·시각·사유의 담당자 조회, 멱등 재전달, 원본 바이트와 기존 적재값 보존, 승인 조직 수 갱신을 대조했다.
- 기존 2026년 17개 사업부의 최신 제출본은 모두 PENDING으로 보존했다. 별도 2032년 합성 요청에는 반려/승인 이력을 남겼다. 2027년 기본 요청에는 사용자가 직접 검토할 D100 합성 PENDING을 추가했다. Data/는 읽거나 수정하지 않았다.
- 기존 간단 체험 양식은 보고 매핑·합계 근거가 부족해 업무 검증에서 차단됐다. 12개월 보고 SUMIFS·매출/비용 합계·영업이익 수식을 보강한 새 양식은 실제 파싱→검증→최종 제출을 통과했다. 과거에 업로드한 양식은 덮어쓰지 않는다.
- 브라우저는 로컬 진입·경영기획 역할 선택까지 확인했다. 사용 중인 Chrome 창 전환과 충돌해 승인·반려 버튼의 실제 클릭 검증은 완료하지 못했다. 프런트 명령은 메모리 API 대역으로 사유 필수·권한·멱등 재시도·409 갱신·이중 클릭 차단을 검사했다.

이 결과는 연간 제출 검토 범위다. 수정 소명 작성·연간 계획 확정·월간 승인/마스터 발행·운영 인증의 구현 또는 검증 완료를 뜻하지 않는다.
