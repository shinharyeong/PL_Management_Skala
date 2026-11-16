# 백엔드·Excel 파서·최초 연간 제출 연결

## 기준과 구현 범위

[API 0.7-draft](api-contract.md)와
[ERD PR #16](https://github.com/shinharyeong/skala_final_project/blob/8c84d2783c9beb2f876810387baa7f9e3f503a50/docs/development/erd.md)을 기준으로 한다.
전체 스냅샷 재제출, 모든 금액 변경 소명, 조직·보고 항목·연월·시나리오별 비교, 확인된 OP의 Gap,
전 조직 최초 승인 후 마스터 발행·이후 승인 조직만 교체하는 업무 규칙을 유지한다.
현재 구현은 원본 접수·파싱에서 최초 연간 PLAN 검증·적재·최종 제출·기획자 조회·연간 승인·반려까지 연결한다. 연간 계획 확정·공식 마스터 발행은 후속 범위다.

공통 접두사는 /api/v1이다. P는 PLANNER, O는 ORG_MANAGER, B는 둘 다다.

| 메서드·경로 | 권한 | 구현 |
| --- | --- | --- |
| GET /me | B | DB의 사용자·역할·조직 |
| GET /organizations | B | 권한 범위 조직·필터·페이지 |
| POST /submission-requests | P | 계획·요청·전 조직 대상 동시 등록 |
| GET /submission-requests | B | 요청함·기간/유형/상태 필터 |
| GET /submission-requests/{requestId} | B | 대상별 최신 완료 회차·초안 수 |
| POST /submission-requests/{requestId}/submissions | O | 원본·DRAFT·QUEUED 접수, 202 |
| GET /submissions | B | 제출 이력 |
| GET /submissions/{submissionId} | B | 활성 시도·stateToken·readiness |
| POST /submissions/{submissionId}/parsing-retries | O | 운영 실패의 동일 원본 재파싱 |
| GET /submissions/{submissionId}/diagnostics | B | 현재 진단·필터·페이지 |
| GET /submissions/{submissionId}/file | B | 최초 원본 바이트 |
| GET /submissions/{submissionId}/parsed-records | B | 추출 후보·nullable 단위·원본 위치 미리보기 |
| POST /submissions/{submissionId}/validation | O | 현재 초안의 연간 12개월·분류·금액·합계 근거 검증과 선택 상세 적재 |
| POST /submissions/{submissionId}/submit | O | 준비된 초안에 회차·제출자·시각 부여, PENDING |
| POST /submissions/{submissionId}/decisions | P | 최신 PENDING의 연간 승인·반려, 검토자·시각·반려 사유 기록 |
| GET /submissions/{submissionId}/financial-values | B | 실제 적재한 상세 월별 금액과 원본 위치 |
| GET /submissions/{submissionId}/report-values | B | 검증된 보고 집계 또는 UNRESOLVED/null |
| GET /submissions/{submissionId}/source-cells | B | 지정한 보존 원본 셀의 값·수식·캐시·단위 |

전체 설계 35개 중 위 18개를 구현했다. 나머지 17개 경로와 소명·연간 확정·Gap·월간 승인·공식 마스터·보고 생성은 후속 구현이다. 아직 없는 기능이 성공한 것처럼 응답하지 않는다.
월간 접수의 확정 계획 조건은 검사하지만 확정 API는 아직 없으므로 초기 연결은 ANNUAL_PLAN 흐름으로 확인한다.

## 연결 구조

```mermaid
flowchart LR
    A["업로드"] --> B["권한·파일명·요청 검사"]
    B --> C["원본·초안·작업·멱등 응답 커밋"]
    C --> D["DB 워커의 작업 확보"]
    D --> E["stdin → Python 추출/매핑 → stdout"]
    E --> F["활성 시도·원본 해시 확인"]
    F --> G["결과 저장·상태/진단 조회"]
    G --> H["담당자의 업무 검증 명령"]
    H --> I["원본 근거·전체 월·금액 대조 / 유효 상세 적재"]
    I --> J["준비 완료 후 최종 제출 → PENDING"]
    J --> K["기획자 적재행·보고 집계·진단·원본 셀 조회"]
```

Java는 업로드 바이트를 별도 셸 없이 Python stdin으로 전달한다.
Python은 BytesIO/ZipFile로 기존 추출·매핑을 재사용한다. 원본 임시 파일·수식 재계산·외부 파일 조회가 없다.
루트의 구형 src/excel_parser가 아니라 저장소 parser/src/excel_parser/service_bridge.py를 사용한다.
Decimal·원본 단위·배율·수식·출처를 내부 JSON에 보존하며, 빈칸을 0으로 바꾸지 않는다.
원본 객체 키·서버 경로·내부 스택을 공개 DTO에 넣지 않는다.

접수 전에 파일명·크기·ZIP 시그니처를 확인하고, OOXML 구조·암호화·자원 제한은 비동기 파서가 검사한다.
따라서 잘못된 구조는 202 접수 후 FAILED로 나타날 수 있다.
파싱 SUCCEEDED는 업무 검증 성공이 아니다. 검증 전 초안은 NOT_RUN·canSubmit=false다.
명시적 validation은 인증 조직과 원본 소유/각 행 조직, 요청 연도의 PLAN 1~12월, 관리분류·실적집계기준, 원 금액 정밀도, 원본 답안지의 말단·총계·OP 대조를 확인한다. 오류가 있으면 유효한 일부 행만 검증 초안으로 적재하고 complete=false·canSubmit=false·보고값 UNRESOLVED/null을 반환한다. 최초 연간 범위를 넘는 월간 비교와 수정 소명은 차단 사유로 남긴다.

답안지의 저장 캐시와 상세 합계의 절대 차이가 0보다 크고 0.00001원 이하이면 CONTROL_CACHE_PRECISION 경고와 캐시·계산값·차이를 보존한다. 그보다 큰 차이·누락·미확정은 오류다. 적재 원 금액과 보고 집계는 상세의 정확한 Decimal 값을 사용하며 캐시 오차를 숫자 보정이나 빈칸 0 처리에 사용하지 않는다.

## 초기 DB와 동시성

V1은 organization, app_user, annual_plan, submission_request, request_target, submission, parsing_job의
접수 범위를 만든다. V2는 account_item, report_item, financial_line, financial_value, submission_validation 및 submission.previous_submission_id를 추가한다. 소명·마스터 테이블은 후속 대상이다.
추가한 물리 요소는 app_user.active(인증 해제), submission_request.period_key(연간 null 월 포함 중복 방지),
stored_object(원본/결과 BYTEA), idempotency_record(성공 응답 보존)다.
뒤의 두 테이블은 초기 운영 저장 장치이며 도메인 17개 전체 구현을 뜻하지 않는다.
대용량 운영에서는 객체 저장소로 교체하고 접수 원자성을 유지해야 한다. S3는 아직 연결하지 않았다.

Flyway만 스키마 초기화에 사용한다.
[Spring Boot 데이터 초기화 문서](https://docs.spring.io/spring-boot/3.4/how-to/data-initialization.html).
원본·초안·작업·멱등 성공 응답은 같은 DB 트랜잭션에서 저장한다.
멱등키는 영문·숫자·점·밑줄·콜론·하이픈 8~128자이며 사용자·메서드·정규 경로와 결합한다.
24시간 내 같은 내용은 최초 성공 응답을 재전달한다. 다른 내용은 409다.
사용자 행 NOWAIT 잠금 충돌은 409와 Retry-After: 1을 반환한다.
재전달 전 사용자 역할·조직 활성 상태를 재확인한다.

검증·최종 제출은 조직·제출·연간 계획 잠금을 사용하며, 최종 제출은 요청 상태·대상·다른 PENDING·반려 요청 범위·최신 검증을 재검사한다. 유효 상세 교체·검증 참조·멱등 응답은 같은 트랜잭션이다. 원본과 과거 검증 결과 객체는 삭제하지 않는다. 적재·보고 GET은 REPEATABLE_READ로 같은 검증 스냅샷의 요약·행·커서를 읽는다.

업로드는 조직도 잠그고 PENDING 또는 다른 요청의 최신 완료 회차가 REJECTED인 경우 차단한다.
과거 반려 후 최신 승인과 동일 요청 재제출은 허용한다. 업로드 순서 대신 완료 seq를 판단에 사용한다.
인스턴스별 워커는 한 건씩 처리하고 QUEUED→RUNNING 조건부 갱신 성공자만 실행한다.
제한시간+30초가 지난 RUNNING은 PARSER_INTERRUPTED로 복구한다.
결과 채택은 submissionId/jobId/attemptNo/sourceSha256/DRAFT/RUNNING을 확인한 트랜잭션 안에서만 한다.
실제 저장 바이트의 SHA-256도 실행 전에 검사한다. 지연된 구시도 결과는 버린다.
재파싱은 TIMEOUT/INTERRUPTED/UNAVAILABLE/PROTOCOL_INVALID만 허용하며 파일 오류·무결성 오류는 제외한다.

## 프로토콜·한도

```text
python3 -B -m excel_parser.service_bridge --filename D100_2031-PLAN.xlsx
stdin: XLSX bytes
stdout: {protocolVersion, status, errorCode, summary, diagnostics, records, submissionData}
exit: SUCCEEDED/NEEDS_REVIEW=0, FAILED=1
```

| 경계 | 초기 한도 |
| --- | --- |
| 원본 / 전체 multipart | 16MiB / 17MiB, 메모리 임계값 17MiB |
| 해제 전체 / ZIP 엔트리 | 64MiB / 16MiB |
| ZIP 항목 / 압축배율 | 1,024개 / 200배 |
| 내용 셀 / 물리 XML 셀 / stdout | 50,000개 / 250,000개 / 64MiB |
| 실행시간 / 조직 대기·실행 합계 | 90초 / 10건 |

XML DTD/엔티티·깊이·노드·병합/열 반복량·속성/문자열 길이와 텍스트 증폭도 사전 제한한다.
세부 값은 BridgeLimits 및 [파서 함수 명세](../../parser/docs/development/function-reference.md)에 있다.
로컬 Compose 백엔드는 CPU 2개·메모리 1536MiB·PID 256개로 제한한다.
운영 상한과 전체 인스턴스 동시성은 운영 배포 단계에서 따로 결정해야 한다.
Java는 출력 타입·필수 카운트·진단 enum·종료코드를 검증한다.
자식 프로세스에는 PATH·PYTHONPATH·인코딩만 전달해 DB 비밀번호·개발 토큰 상속을 막는다.
stderr는 폐기하며 고정 오류 코드만 노출한다. 엔진이 주지 않은 버전 필드는 null이다.
브리지 envelope의 `protocolVersion=1.0`은 유지한다. `stdlib-bridge/1.1`에서 도입한 투영은 전체 후보를 삭제하지 않고
`preview-records/1.0`에 원본 셀·조직·항목·기간·역할·값·수식·매핑·오류 근거를 보존한다.
표준 파서 결과는 변경하지 않으며 HTTP 연결용 저장 결과에서 반복된 설명 문맥만 생략한다.
1.1에서 사업부 기본 파일의 응답은 약 76.4MB에서 22.2MB로 줄었고 당시 미확정 판정도 유지했다.
1.2는 엄격히 확인한 미사용 비용 양식 행에 `inactive_template` 역할을 부여하고
원본 후보·금액·수식은 보존한다. `summary.unusedTemplateRowCount`에 고유 행 수를 알리며
해당 행만 업무 검토 수에서 분리한다. 실제 0원·입력된 행의 오류를 미사용으로 제외하지 않는다.
조직 조회 실패와 실제 이름 충돌을 구분하고 원인별 안내 및 확인된 셀 위치를 전달한다.
1.3은 submissionData.adapterVersion=financial-submission/1.0으로 소유 조직·시트/기간·보고 분류·상세/월 슬롯·답안지 대조값·정확한 원본 셀을 추가한다. 공개 source-cells는 보존된 공유 수식의 기준 셀·수식·범위가 있으면 sharedFormulaBase로 구분해 전달하며 종속 셀 수식을 만들어 실행하지 않는다. 검증은 이 내부 근거를 사용하며 후보 records를 모두 금융값으로 적재하지 않는다. 공개 parsed-records는 amountUnit=KRW일 때만 원 단위를 표시하고, 단위가 미확정인 후보 숫자는 null 단위로 보존한다.
기존 저장 결과는 변경하지 않으므로 새 파서 결과를 보려면 새 접수가 필요하다.

## 실행과 초기 등록

설정·명령은 [backend README](../../backend/README.md)를 따른다.
기본 프로필은 health 외 거부하고 local만 환경변수 Bearer를 받는다. local+demo의 역할 선택·예시 양식 API는 개발 체험에만 제공한다.
local 토큰도 DB에 등록한 활성 사용자·역할·조직과 일치해야 한다. local만으로는 자동 계정·조직을 만들지 않으며, Docker에서 local+demo를 함께 켜면 개발 자료를 명시적으로 준비한다.
서버 시작은 지정 PostgreSQL에 Flyway를 적용하므로 실행 대상 DB를 먼저 확인한다.
`local`만 사용하는 별도 개발 DB의 수동 등록 예시는 다음과 같다.
Docker 체험은 [demo 초기화](demo-api.md)를 사용하므로 아래 SQL을 중복 실행하지 않는다.

```sql
INSERT INTO organization(code,name) VALUES ('D100','개발 조직');
INSERT INTO app_user(email,name,role)
VALUES ('planner@example.test','개발 기획','PLANNER');
INSERT INTO app_user(email,name,role,organization_id)
SELECT 'manager@example.test','개발 담당','ORG_MANAGER',id FROM organization WHERE code='D100';
```

실제 제출 대상 조직을 모두 등록한 후 요청을 생성해야 한다. 활성 조직 전체가 대상에 고정된다.
인증 이메일·별개의 32자 이상 개발 토큰은 환경변수로 공급한다.
프런트 연결은 /me → 요청 생성(P) → 요청함(O) → 파일 접수(O) → 상태 폴링 → validation(O) → 적재/진단 확인 → submit(O) → 요청별 최신 제출본·적재/집계/셀 조회(P) 순서다.

## 검증과 남은 작업

메모리 H2와 합성 XLSX로 실제 Python 프로세스까지 연결한다.
원본 동일성·십진 정밀도/0·검증 전 차단, 권한 격리, 멱등 재전달/충돌/롤백, 반려 제한,
무결성·재시도·구시도 차단, 공통 오류·기본 접근 거부를 검증한다.
H2는 PostgreSQL 모드와 NON_KEYWORDS=YEAR,MONTH를 사용한다.
실제 PostgreSQL의 잠금 동작·배포 환경·대용량 성능을 검증한 것으로 보지 않는다.
기존 단위·통합 테스트는 Data/와 실제 PostgreSQL을 사용하지 않는다. Docker 체험에서는 별도 PostgreSQL 개발 DB를 생성해 추가로 검증한다. 실행·종료와 볼륨 보존은 [로컬 실행 환경](local-environment.md)을 따른다.

이전 접수·파싱 단계의 2026-11-16 실행 기록: demo·파싱 미리보기 테스트를 포함한 Java 57개 모두 통과.
앞선 파서 연결 검증에서는 Python 관련 49개 중 47개 통과·외부 원본을 요구하는 2개를 건너뛰었다.
실제 PostgreSQL·nginx·브라우저 업로드 및 DB 재시작 후 결과 조회도 통과했으며,
구체적인 범위는 [로컬 통합 검증 기록](local-environment.md#2026-11-16-통합-검증-기록)에 남겼다.
실제 Java→Python 프로세스 연결은 Java 통합 테스트에도 포함된다.
Python 검증 명령은 parser 디렉터리에서 다음과 같다.

```bash
env -u EXCEL_PARSER_TEST_DATA PYTHONPATH=src:tests python3 -B -m unittest test_extraction test_mapping test_mapping_monthly test_business_validation test_service_bridge
```

현재 단계의 검증 함수·HTTP/H2 테스트는 [함수 명세](backend-function-reference.md)에 기록하며 실제 17개 원본 대조는 [verify-submission.py](../../scripts/verify-submission.py)를 사용한다. 실행 방법·범위는 [로컬 환경](local-environment.md#최초-연간-검증최종-제출-대조)에 있다.
남은 업무 순서는 모든 수정 항목·OP Gap 비교/소명 → 연간 확정·월간 승인·불변 마스터다.
로그인 제공자·OpenAPI 산출물·운영 모니터링도 남아 있다.
구현 함수·테스트와 부작용은 [백엔드 함수 명세](backend-function-reference.md)에 관리한다.

## 연간 제출 승인·반려

`decisions`는 조직·제출 잠금 아래 최신 회차와 상태 토큰을 다시 확인한다. 연간 승인은 제출 때 고정된 검증 객체와 실제 적재 건수를 확인하고 `APPROVED`로 변경한다. 요청이 닫혀도 이미 접수한 제출본은 검토할 수 있다. `REJECTED`에는 1~2000자 사유와 검토자·시각을 기록하며 원본·금액·제출 회차는 보존한다. 새 DB 마이그레이션 없이 기존 `submission`의 검토 컬럼을 사용한다.

종료한 제출본을 새 멱등키로 다시 판단하면 409이며, 성공한 같은 키는 처음 응답을 재전달한다. 승인과 반려의 경합은 한 전이만 성공한다. 승인 응답과 저장을 같은 트랜잭션에서 처리하므로 실패하면 검토 기록도 남지 않는다.

반려본을 초안으로 되돌리지 않는다. 같은 요청의 새 전체 파일 업로드 → 검증 → 최종 제출에서만 다음 회차를 부여하며, 비교 대상은 직전 반려본이다. 금액 변경은 현재 `CHANGE_EXPLANATION_REQUIRED`로 차단한다. 연간 승인과 연간 계획 확정은 별도이고, 월간 승인/마스터 발행은 아직 지원하지 않는다.
