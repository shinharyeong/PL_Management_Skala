# 손익 제출·검토·마스터 API 명세

## 문서 상태와 적용 기준

| 항목 | 내용 |
| --- | --- |
| 문서 버전 | 0.9-draft · 연간 확정·수정/Gap 소명·월간 승인 마스터 연결 |
| 상태 | ERD v0.2와 V3~V5 물리 확장 기반 전체 설계 39개 중 36개 구현. 연간 확정, 수정/Gap 소명, 연간·월간 승인 및 마스터 조회 연결. COM-03·REQ-04·REP-01은 후속 구현 |
| 기준 | [논리 ERD](https://github.com/shinharyeong/skala_final_project/blob/8c84d2783c9beb2f876810387baa7f9e3f503a50/docs/development/erd.md), [ERD PR #16](https://github.com/shinharyeong/skala_final_project/pull/16), [PR 기준 스키마](https://github.com/shinharyeong/skala_final_project/blob/8c84d2783c9beb2f876810387baa7f9e3f503a50/docs/development/erd.md) |
| 확인한 백엔드 | Spring Boot 3.4.1 · Java 21 · JDBC/Flyway · PostgreSQL, 테스트만 H2 |
| 범위 | 요청, 연간 계획, 전체 파일 제출, 파싱·검증, 금액 수정·OP Gap 소명, 승인·반려, 공식 마스터, 보고 데이터 |
| 범위 밖 | 로그인 제공자·회원 관리, 조직/보고 항목 관리 화면, 알림 발송 구현체, 보고 파일 포맷, 미지원 Excel 양식 |
| 관련 문서 | [현재 DB 매핑](db-mapping.md), [로컬 파서 출력 계약](../../parser/docs/development/output-contract.md), [연결·실행 범위](backend-integration.md) |

기존 `POST /api/v1/excel/parse`와 `proposal-0.1` 예시는 이 서비스 명세로 대체한다. 파서 함수를 외부에 직접 노출하는 API는 두지 않는다. 기존 파서 출력 계약은 변경하지 않는다. 이 문서의 예시 ID·금액·이름은 모두 가상이다.

PR #16은 develop에 병합되었다. 이 문서는 해당 PR의 고정된 설계 커밋을 기준으로 하며, 합의한 업무 규칙·전체 API 설계와 현재 실행 가능한 구현 범위를 구분한다. 실제 구현과 검증 기록은 백엔드 연결 문서와 로컬 실행 환경 문서를 따른다.


현재 구현은 총 39개 설계 경로 중 36개다: COM-01/02, REQ-01/02/03, PLAN-01/02, SUB-01~13, AMAS-01~04, REV-01~05, GAP-01~03, MAS-01~04. 컨트롤러의 메서드·경로를 기준으로 센 수이며 개발 전용 `/api/dev/session`, `/api/dev/template`는 제외한다. 미구현은 COM-03 보고 항목 목록, REQ-04 요청 수정, REP-01 보고 데이터 구성 3개다. 이 세 계약은 아래 목록과 본문에서 후속 설계로 구분한다.

파싱 성공 후에는 검증 전 `DRAFT + NOT_RUN + canSubmit=false`이며, 담당자가 validation·필수 소명 답변·submit을 실행해야 `PENDING`이 된다. 연간 PLAN 1~12월과 월간 요청의 1~요청월 ACTUAL·이후 ESTIMATE 전체 범위를 검증한다. 기획자는 소명을 수용 또는 재요청하고 모든 필수 소명이 수용된 제출본을 승인한다. 연간·월간 마스터는 승인 트랜잭션에서 발행하며, 연간 계획·Contingency 확정은 별도 PLAN-02 명령이다. 구현 경로 수가 실제 원본·동시성·브라우저 검증 완료 건수를 뜻하지는 않으며 실행 결과는 [로컬 환경](local-environment.md)에 별도로 기록한다.

검증은 파서 내부 `submissionData.adapterVersion=financial-submission/1.0`의 조직·월·분류·원 금액·답안지 합계 근거를 확인한다. 구버전 파서 결과에 이 근거가 없으면 `SUBMISSION_ADAPTER_REQUIRED`를 반환한다. 유효 상세는 검증 초안에 적재할 수 있지만 오류가 하나라도 있으면 `complete=false`, `canSubmit=false`, 보고값은 `UNRESOLVED/null`이다. 승인·공식 집계와 구분한다. 인증은 기존 local Bearer와 local·demo 체험 세션 범위이며 운영 로그인 정책을 대신하지 않는다.

## 1. 설계 원칙과 권한

### 합의한 업무 규칙

- 최초 제출·재제출 모두 조직의 전체 스냅샷이다. 파일·금액을 일부 PATCH하는 API는 없다.
- 금액이 수정된 모든 보고 항목에 소명이 필요하다. 기준 이하 수정과 같은 항목 안의 증감 상쇄도 포함한다.
- 보고 집계 키는 조직·보고 항목·연월·시나리오다. 매출은 관리분류, 비용은 실적집계기준으로 연결하며 팀·과제·셀 좌표는 상세 근거다.
- 손익 Gap은 확인된 영업이익(OP)에 적용한다. 매출·비용 계정을 임의로 짝짓지 않는다.
- 소명 보완은 같은 제출본의 새 소명 회차다. 제출 회차·금융값·마스터를 바꾸지 않는다.
- 최초 월 마스터는 고정된 전 조직 승인 후 발행한다. 이후에는 새로 승인된 조직만 기존 구성에서 교체한다.
- 확정 연간 계획·Contingency, 제출 완료 이후 숫자·분류·비교 기준, 과거 마스터 구성은 불변이다.

### 이번 API 설계 선택

| 선택 | 목적 |
| --- | --- |
| 리소스별 조회와 업무 명령 | 도메인 테이블을 각각 CRUD로 노출하지 않음 |
| 한 번의 multipart 업로드 + 제출 상태 폴링 | 스토리지 사전 서명·클라이언트의 파서 직접 호출을 초기 계약에서 줄임 |
| `POST .../submit`으로 최종 제출 분리 | 업로드 성공·파싱 성공·업무 제출 완료를 구분 |
| 소명 및 제출의 `decisions` 명령 | 같은 대상의 판단을 한 서비스에서 검증 |
| 응답 `stateToken`과 명령 `expectedStateToken` | 별도 업무 버전 컬럼을 가정하지 않고 오래된 화면의 쓰기를 검출 |
| 승인 안에서 마스터 발행 | 임의 생성·재생성 명령으로 승인 조건을 우회하지 않음 |
| 보고서는 고정 master ID로 JSON 생성 | 별도 저장형 보고서·아카이브 테이블을 요구하지 않음 |

모든 경로의 공통 접두사는 `/api/v1`이다. 아래 표는 이를 생략한다. `P`는 `PLANNER`, `O`는 `ORG_MANAGER`, `B`는 두 역할이다.

| 대상 | PLANNER | ORG_MANAGER |
| --- | --- | --- |
| 조직·요청·제출·소명 조회 | 전체 조직 | 본인 조직에 속한 자료만 |
| 제출 업로드·초안 검증·소명 답변·최종 제출 | 불가 | 본인 조직만 |
| 요청·기준 설정·소명 판단·제출 승인/반려·연간 확정 | 가능 | 불가 |
| 전사 연간 계획·마스터·보고 조회 | 가능 | 불가 |

조직 담당자의 `organizationId`는 인증 컨텍스트에서 결정한다. 다른 조직 ID를 필터로 지정하면 `403 FORBIDDEN`이며, 권한 밖 개별 리소스·원본 참조 조회는 `404 RESOURCE_NOT_FOUND`로 응답한다. 모든 하위 조회도 제출본의 소속을 확인한다. P 역할이 있다고 조직을 대신해 답변하거나 파일을 제출할 수는 없다.

인증된 사용자 식별 방식은 로그인 정책 확정 후 정한다. JWT·쿠키·SSO를 이미 구현된 계약으로 가정하지 않는다. 쿠키 방식을 선택하면 변경 요청의 CSRF 방어도 함께 정한다.

## 2. 공통 요청·응답 계약

### 형식과 목록

| 항목 | 계약 |
| --- | --- |
| JSON | `application/json`, 필드명 camelCase. 미정의 쓰기 필드·쿼리 이름은 `400 INVALID_REQUEST` |
| ID | DB bigint도 JSON에서는 십진 정수 문자열. URL의 ID도 같은 형식 |
| 행위자 | 응답의 requestedBy·uploadedBy·submittedBy·answeredBy·reviewedBy·confirmedBy·appliedBy는 `ActorRef{id,name}` 또는 행위 전 null. 사용자 관리 API 없이 표시할 수 있게 제공 |
| 금액·차액·비율 | 정밀 십진 문자열. 업무 적재·보고 금액은 원 단위이며 파싱 후보는 nullable amountUnit을 따른다. `"5"`라는 percent는 5%. 지수·콤마·NaN·Infinity 불허 |
| 날짜·시각 | 연도 2000~9999 정수, 월 1~12 정수. 시각은 시간대가 있는 ISO 8601 문자열, 응답은 UTC `Z` |
| 시나리오 | `PLAN / ACTUAL / ESTIMATE` |
| 누락 | nullable 필드는 명시적 null. 금액 누락을 `"0"`으로 바꾸지 않음 |
| 성공 | 단건은 DTO 직접 반환. 일반 목록은 `{"items": [], "nextCursor": null}` |
| 페이지 | 일반 목록 `limit` 기본 50, 최대 100. 불투명 `cursor` 사용, 필터·권한 범위에 결합 |
| 추적 | `X-Request-ID`는 요청 추적값이며 `submission_request.id`와 다름 |
| 캐시 | 사용자별 재무 응답과 다운로드는 `Cache-Control: private, no-store` |

제출 목록은 `uploadedAt DESC, id DESC`, 검토 목록은 현재 `id ASC`, 마스터 이력은 `seq DESC, id DESC`로 정렬한다. 변경 가능한 목록은 스냅샷으로 보장하지 않으므로 재조회가 필요할 수 있다. 변경 근거 cursor에는 아래 `stateToken`을 결합하고 자료가 달라졌으면 `409 RESOURCE_CHANGED`를 반환한다.

### 재시도와 동시성

상태를 변경하는 POST와 소명 답변 PUT에는 `Idempotency-Key`가 필수다. 초기 허용 형식은 영문·숫자·`.`·`_`·`:`·`-` 8~128자다. `/gap-thresholds/preview`와 `/reports/preview`는 읽기 전용 계산이므로 제외한다. 같은 사용자·HTTP 메서드·정규 경로·키와 같은 요청 내용이면 최초 성공 결과를 반환한다. 업로드 내용 비교에는 파일 바이트 해시와 요청 ID·원본 파일명을 포함한다.

- 키가 같고 내용이 다르면 `409 IDEMPOTENCY_KEY_REUSED`.
- 같은 명령이 실행 중이면 `409 REQUEST_IN_PROGRESS`와 `Retry-After`.
- 성공 결과 재전달에는 `Idempotency-Replayed: true`. 화면은 이후 GET으로 최신 상태를 확인한다.
- 인증·현재 접근 권한을 먼저 확인하고, 성공한 동일 명령의 재전달은 상태 토큰 검사보다 먼저 처리한다.
- 현재 유효 기간은 24시간이다. 성공 응답은 idempotency_record에 업무 변경과 같은 DB 트랜잭션으로 보존한다. 메모리 캐시만으로 중복 승인 방지를 처리하지 않는다.
- 유효 기간이 지나도 APPROVED 제출본을 다시 승인해 새 마스터를 만들 수 없다. 새로운 키로 이미 종료한 명령을 실행하면 상태 충돌이다.

상태를 바꾸는 PUT/PATCH 및 기존 리소스에 대한 POST 명령은 표에 명시한 `expectedStateToken`을 받는다. 해당 리소스 GET의 `stateToken`을 사용한다. 토큰은 상태·관련 ID·현재 회차·변경 가능한 내용의 정규 표현으로 계산하는 불투명 값이며 DB 제출 회차나 파일 해시가 아니다. 비교는 실제 변경과 같은 잠금/트랜잭션 범위에서 수행한다.

토큰 불일치는 `409 RESOURCE_CHANGED`이며 현재 PLAN-02는 `409 STATE_TOKEN_MISMATCH`를 사용한다. 업무 변경은 없다. 토큰 누락은 `400 INVALID_REQUEST`이다. 새 객체 생성에는 토큰이 없다. GET은 검토 항목을 생성하거나 재파싱하지 않는다. 초안 비교 재계산은 명시적 validation 명령이 담당한다.

## 3. API 목록

`T`는 expectedStateToken이 필요한 변경 명령이다. 성공 코드만 표에 쓰고 공통 오류는 11절을 따른다.

| ID | 메서드·경로 | 권한 | 성공 | 내용 |
| --- | --- | --- | --- | --- |
| COM-01 | GET /me | B | 200 | 로그인 사용자·역할·조직 |
| COM-02 | GET /organizations | B | 200 | P 전체, O 본인 조직 |
| COM-03 | GET /report-items | B | 200 | 후속 설계 · 보고 계층·종류·OP 계산 코드 |
| REQ-01 | POST /submission-requests | P | 201 | 전 조직 대상 요청 생성 |
| REQ-02 | GET /submission-requests | B | 200 | 요청함·요청 관리 목록 |
| REQ-03 | GET /submission-requests/{requestId} | B | 200 | 요청·대상별 진행 현황 |
| REQ-04 | PATCH /submission-requests/{requestId} | P | 200 | 후속 설계 · T · 안내·마감 표시·개폐 |
| PLAN-01 | GET /annual-plans/{year} | P | 200 | 수집/확정 상태·선택 계획본 |
| PLAN-02 | POST /annual-plans/{year}/confirm | P | 200 | T · 계획본과 Contingency 확정 |
| SUB-01 | POST /submission-requests/{requestId}/submissions | O | 202 | 전체 파일 업로드·새 DRAFT |
| SUB-02 | GET /submissions | B | 200 | 제출 이력·검토 대기 목록 |
| SUB-03 | GET /submissions/{submissionId} | B | 200 | 상태 폴링·파싱·준비 요약 |
| SUB-04 | POST /submissions/{submissionId}/parsing-retries | O | 202 | T · 같은 파일의 허용된 재파싱 |
| SUB-05 | POST /submissions/{submissionId}/validation | O | 200 | T · 초안 비교·소명 대상 재계산 |
| SUB-06 | GET /submissions/{submissionId}/diagnostics | B | 200 | 오류·불확실성·완전성 근거 |
| SUB-07 | GET /submissions/{submissionId}/report-values | B | 200 | 전체 보고 항목의 월별 값 |
| SUB-08 | GET /submissions/{submissionId}/source-cells | B | 200 | 지정 원본 셀의 값·수식·캐시 |
| SUB-09 | GET /submissions/{submissionId}/file | B | 200 | 권한 확인 후 원본 다운로드 |
| SUB-10 | POST /submissions/{submissionId}/submit | O | 200 | T · 최종 검증·회차 부여·PENDING |
| SUB-11 | POST /submissions/{submissionId}/decisions | P | 200 | T · APPROVE / REJECT |
| SUB-12 | GET /submissions/{submissionId}/parsed-records | B | 200 | 파싱 원본 후보의 읽기 전용 미리보기 |
| SUB-13 | GET /submissions/{submissionId}/financial-values | B | 200 | 검증으로 선택·적재한 상세 월별 금액과 출처 |
| AMAS-01 | GET /submission-requests/{requestId}/annual-master | P | 200 | 연간 PLAN 승인 진행·발행 현황 |
| AMAS-02 | GET /annual-masters | P | 200 | requestId 필수 · 연간 마스터 버전 목록 |
| AMAS-03 | GET /annual-masters/{masterId} | P | 200 | 고정 승인본·연간 및 12개월 합계 |
| AMAS-04 | GET /annual-masters/{masterId}/report-values | P | 200 | 조직·월별 고정 PLAN 보고값 |
| REV-01 | GET /review-items | B | 200 | 소명 목록·필터·모음 |
| REV-02 | GET /review-items/{reviewItemId} | B | 200 | 검토 칸·비교·소명 회차 |
| REV-03 | GET /review-items/{reviewItemId}/evidence | B | 200 | 원천 변경 전후·출처 |
| REV-04 | PUT /review-items/{reviewItemId}/answer | O | 200 | T · 현재 회차 답변 저장 |
| REV-05 | POST /review-items/{reviewItemId}/decisions | P | 200 | T · ACCEPT / REQUEST_CHANGES |
| GAP-01 | GET /gap-thresholds/current | B | 200 | 현재 ACTUAL/ESTIMATE 기준 |
| GAP-02 | POST /gap-thresholds/preview | P | 200 | 후보 기준의 영향 미리보기 |
| GAP-03 | POST /gap-thresholds | P | 201 | T · 시나리오별 새 기준 적용 |
| MAS-01 | GET /master-periods | P | 200 | 연도별 월 발행 상태·홈 데이터 |
| MAS-02 | GET /masters | P | 200 | 지정 월의 버전 목록 |
| MAS-03 | GET /masters/{masterId} | P | 200 | 버전·고정 조직별 승인본 |
| MAS-04 | GET /masters/{masterId}/report-values | P | 200 | 해당 버전의 ACTUAL 보고값 |
| REP-01 | POST /reports/preview | P | 200 | 후속 설계 · 고정 master ID의 보고 데이터 |

마스터 생성·금융값 수정·Gap 제외·임의 소명 수용 상태 PATCH·알림 발송 API는 노출하지 않는다.

### 화면별 호출 순서

현재 구현된 최초 연간 제출 흐름은 `REQ-02 → SUB-01 → SUB-03 폴링 → SUB-05 → SUB-06/07/13 확인 → 최신 SUB-03 → SUB-10`이다. 원본 확인은 `SUB-08/09`를 사용한다. 기획자는 `REQ-03.targets.latestSubmittedSubmissionId → SUB-03 → SUB-06/07/08/13`으로 제출 상태·적재행·집계·진단·원본 셀을 읽는다. 초안만 있는 조직은 SUB-02의 requestId/organizationId 필터로 확인한다.

아래는 현재 구현된 연간·월간 흐름이다. 별도 보고 데이터 생성 REP-01은 후속 계약이며 마스터 조회로 대체 구현한 것으로 간주하지 않는다.

| 화면 흐름 | 호출 순서 |
| --- | --- |
| 조직 제출 | REQ-02 → SUB-01 → SUB-03 폴링 → SUCCEEDED 확인 → SUB-05 → REV-01/02 → REV-04 → SUB-03에서 최신 토큰 확인 → SUB-10 |
| 경영기획 검토 | SUB-02(status=PENDING) → REV-01/02 → REV-05 → SUB-03에서 최신 토큰 확인 → SUB-11 → MAS-01 |
| 소명 보완 | REV-05(REQUEST_CHANGES) → 조직의 REV-02 → REV-04 → 경영기획의 REV-02 → REV-05(ACCEPT) |
| 연간 승인·확정 | SUB-11 → AMAS-01/03 → PLAN-01 → PLAN-02 |
| 월간 요청·실적 홈 | PLAN-01(CONFIRMED) → REQ-01(MONTHLY_ACTUAL) → 제출·검토·승인 → MAS-01 → MAS-03/04 |
| 보고 데이터 생성(후속 설계) | MAS-02에서 버전 선택 → REP-01 |

파일 자체 오류는 개별 소명 수용을 기다리지 않고 SUB-11(REJECT)로 처리한다. 최초 계획·무변경 제출처럼 필수 소명이 없으면 REV-04를 생략하지만 SUB-05는 생략하지 않는다.

## 4. 공통 조회와 제출 요청

### COM-01~03

- `GET /me` → `{id, email, name, role, organization: {id, code, name} | null}`. P의 organization은 null.
- `GET /organizations?active=true` → `Page<Organization>`. active 생략 시 접근 가능한 전체 조직, 과거 제출의 비활성 조직도 식별 가능.
- 후속 설계: `GET /report-items?active=true&kind=COST` → `Page<ReportItem>`. kind 선택 사항. 각 항목은 `id, code, name, kind, parentId, isLeaf, calculationCode, displayOrder, active`. code는 내부 보고 코드이며 원본 계정 코드가 아니다.

### REQ-01 · 요청 생성

요청 JSON:

```json
{
  "type": "MONTHLY_ACTUAL",
  "year": 2026,
  "month": 2,
  "message": "2월 실적과 이후 추정을 제출해 주세요.",
  "dueAt": null
}
```

필수는 type·year, MONTHLY_ACTUAL이면 month다. ANNUAL_PLAN이면 month는 null 또는 생략한다. message는 선택, dueAt은 선택 nullable이다. 생성 성공은 `SubmissionRequest`와 `Location: /api/v1/submission-requests/{id}`.

- ANNUAL_PLAN 요청 생성 시 해당 연도의 COLLECTING annual_plan을 같은 트랜잭션에서 만든다. 별도 계획 생성 API는 두지 않는다.
- MONTHLY_ACTUAL 요청은 해당 연간 계획이 CONFIRMED여야 한다. 먼저 요청만 여는 예외는 이번 초안에 없다.
- 대상은 생성 시점의 제출 대상 조직 전부를 서버에서 고정한다. `organizationIds` 입력을 받지 않는다. 대상이 없으면 생성하지 않는다.
- 연도별 연간 요청 한 건, 연도·월별 월간 요청 한 건. 닫힌 요청도 재생성하지 않고 기존 요청을 사용한다.
- 오류: `REQUEST_ALREADY_EXISTS`, `ANNUAL_PLAN_NOT_CONFIRMED`, `ANNUAL_PLAN_LOCKED`, `REQUEST_TARGETS_EMPTY`.

### REQ-02~03 · 목록과 현황

목록 필터는 `year, month, type, status, cursor, limit`이며 모두 선택이다. month는 year와 함께 사용한다. `SubmissionRequest`:

| 필드 | 타입·의미 |
| --- | --- |
| id, annualPlanId | ID |
| type, year, month | ANNUAL_PLAN / MONTHLY_ACTUAL, 연도, 연간은 month null |
| status, message, dueAt | OPEN / CLOSED, 안내, nullable 표시용 마감 |
| requestedBy, requestedAt | 서버가 기록한 ActorRef·시각 |
| targetCount, submittedCount, approvedCount | 권한 범위 내 조직 수. 제출 건수를 조직 수로 세지 않음 |
| stateToken | 요청 상태·안내·대상 집합에 대한 변경 검증 토큰 |
| targets | 상세 GET에만 포함. `organization, latestSubmittedSubmissionId, latestStatus, draftCount` |

latestStatus는 `NOT_SUBMITTED / PENDING / REJECTED / APPROVED`인 조회용 상태다. DRAFT만 있으면 NOT_SUBMITTED이며 가짜 제출 행을 만들지 않는다. O에게는 targets·집계도 본인 조직 범위만 반환한다. 다른 조직의 금액·제출 이력을 요청 상세에 섞지 않는다.

### REQ-04 · 요청 변경 (후속 설계)

`{expectedStateToken, message?, dueAt?, status?}`. message·dueAt의 null은 지움을 의미하고 생략은 유지다. 수정 필드는 하나 이상 필요하다. status는 OPEN/CLOSED만 가능하다.

연도·월·유형·대상 집합은 수정할 수 없다. CLOSED는 새 업로드·최종 제출을 막지만 기존 PENDING 검토·소명 보완은 허용한다. 확정 연간 요청은 재개방하지 못한다. dueAt 경과로 자동 닫기·제출 차단을 하지 않는다. 개폐 방식과 마감 표시의 이 분리는 이번 API 제안이며 자동 마감 정책은 별도 결정이다.

## 5. 연간 계획 확정

### PLAN-01 · 연도 조회

`AnnualPlanView`는 `id, year, status, contingencyAmount, confirmedBy, confirmedAt, stateToken, targetCount, approvedCount, sources`를 반환한다. 확정 전 금액·확정자·시각은 null이다. sources 각 행은 `organizationId, candidateSubmissionId, confirmedPlanSubmissionId, latestStatus`를 가진다.

candidateSubmissionId는 현재 최종 승인 후보이고 확정본이 아니다. stateToken은 계획 상태뿐 아니라 대상 조직별 최신 제출·승인 후보 집합도 포함한다. 해당 연간 요청이 없으면 404다.

### PLAN-02 · 확정

`{expectedStateToken, contingencyAmount}`. contingencyAmount는 필수인 0 이상의 원 단위 십진 문자열이며 정수 24자리·소수 6자리 이내, null 불가다. 최초 확정만 허용한다. 현재 PLAN-02의 토큰 불일치는 `409 STATE_TOKEN_MISMATCH`이며 최신 PLAN-01 조회 후 다시 확인한다.

고정된 전 조직의 최신 제출 완료본이 APPROVED인지 확인한다. 미제출·PENDING·REJECTED가 있으면 `409 PLAN_APPROVALS_INCOMPLETE`와 해당 조직 ID 목록을 반환한다. 오래된 승인본을 대신 선택하지 않는다.

조직별 계획본 참조·Contingency·확정자/시각·CONFIRMED와 해당 연간 요청 CLOSED를 원자적으로 고정한다. 응답은 확정된 `AnnualPlanView`다. 같은 해의 월간 비교는 이 고정본만 사용한다. 조직별·월별로 Contingency를 자동 배분하지 않는다.

## 6. 제출·파싱·완전성

### SUB-01 · 전체 파일 업로드

`Content-Type: multipart/form-data`, part는 `file` 하나다. requestId는 경로에서, 조직·업로더는 인증에서 결정한다. 요청 본문에 조직·제출 회차·승인 상태·파서 결과를 받지 않는다.

초기 지원은 .xlsx 한 개다. 파일명은 `{조직코드}_YYYY-MM.xlsx` 또는 `{조직코드}_YYYY-PLAN.xlsx`이며 요청과 인증 조직이 일치해야 한다. 접수 전 이름·운영 크기·ZIP 시그니처를 확인하고, OOXML 구조·암호화·압축 해제/셀 자원 한도는 비동기 파서가 검사한다. 구조 오류는 접수 후 FAILED와 진단으로 표시한다. 원본의 셀 조직도 파싱 후 다시 검사하며 파일명이 권한 근거를 대신하지 않는다.

열린 요청·대상 소속·조직당 PENDING 최대 한 건·다른 요청의 재제출 제한을 검사한다. ANNUAL_PLAN은 해당 연간 계획이 미확정이어야 하고, MONTHLY_ACTUAL은 확정 계획과 해당 조직의 고정 계획본이 있어야 한다. 같은 파일의 재전송은 멱등키로 처리한다. 파일 다시 선택은 새 키로 새 DRAFT를 만든다. 이전 DRAFT의 파일을 교체하지 않는다.

`202 Accepted`, `Location`은 새 제출 조회 경로다. 원본 보존 참조와 파싱 작업 등록이 성공해야 접수 성공으로 응답한다.

```json
{
  "id": "1201",
  "requestId": "101",
  "organizationId": "12",
  "status": "DRAFT",
  "seq": null,
  "versionLabel": null,
  "originalFilename": "D100_2026-02.xlsx",
  "parsing": {"jobId": "501", "status": "QUEUED", "attemptNo": 1},
  "stateToken": "opaque-submission-token"
}
```

파싱 이후 실패는 상태 조회로 전달한다. 이미 반환한 202를 나중에 422로 바꾸지 않는다. 접수 전에 걸러진 잘못된 파일명은 422, 파일 크기 초과는 413, 미지원 형식은 415다.

### SUB-02~03 · 제출 조회와 폴링

목록 필터는 `requestId, organizationId, year, month, type, status, cursor, limit`. O는 본인 조직으로 강제 제한한다. 목록은 `Page<SubmissionSummary>`다. SubmissionSummary는 `id, requestId, organizationId, type, year, month, status, seq, versionLabel, originalFilename, uploadedBy, uploadedAt, submittedBy, submittedAt, reviewedBy, reviewedAt, parsingStatus, requiredCount, unansweredCount, pendingReviewCount`를 포함한다. 필드 의미·null 규칙은 아래 상세와 같고, 미검증 초안의 세 카운트는 null이다. 상세는 다음 `SubmissionView`다.

| 필드 | 타입·의미 |
| --- | --- |
| id, requestId, organizationId | ID |
| type, year, month | 요청에서 결정. 금융값 대상 월과 구분 |
| status | DRAFT / PENDING / REJECTED / APPROVED |
| seq, versionLabel | DRAFT는 null. 최종 제출 이후 월간 YYYY-MM-NN / 연간 YYYY-PLAN-NN |
| originalFilename, uploadedBy, uploadedAt | 원본 이름·업로드 행위자·시각 |
| submittedBy, submittedAt, reviewedBy, reviewedAt, rejectReason | 해당 행위 전에는 null |
| parsing | `jobId, status, attemptNo, parserVersion, contractVersion, mappingVersion, retryAllowed, errorCode`. 미확정 버전은 null |
| comparisonContext | `previousSubmissionId, planSubmissionId, actualThresholdId, estimateThresholdId, reviewPolicyVersion`. 불필요한 기준은 null |
| readiness | `state, validationState, validatedAttemptNo, canSubmit, canApprove, blockers, requiredCount, unansweredCount, pendingReviewCount` |
| stateToken | 제출 상태·활성 시도·선택 기준·검토 항목/소명 회차를 반영 |

readiness.state는 `PREPARING / BLOCKED / STALE / READY`인 조회용 표현이다. READY여도 O가 명시적으로 submit해야 한다. `canSubmit`은 DRAFT·요청 상태·파싱·완전성·비교 기준·필수 답변을 모두 검사한 결과다. PENDING에서는 canSubmit=false다. canApprove는 PENDING·현재 검증·필수 소명 수용·차단 사유 없음을 검사한 결과이며 PLANNER의 승인 권한을 대신하지 않는다.

validationState는 `NOT_RUN / CURRENT / STALE`이다. 파싱 SUCCEEDED 후 최초 validation 전에는 NOT_RUN·canSubmit=false이며 `VALIDATION_REQUIRED`를 표시한다. CURRENT는 활성 시도에 대한 검증을 실행했다는 뜻이며, 오류가 있으면 state=BLOCKED다. validatedAttemptNo는 마지막 검증 시도 번호 또는 null이다. blockers는 `{code, message?, source?, reviewItemIds?, periods?}` 목록이다. source는 `{sheet, cell}` 또는 null이며 현재 구현은 구체 안내와 원본 위치가 확인된 진단을 함께 반환한다. requiredCount는 필수 검토 칸 수, unansweredCount는 필수 칸의 미답변 수다. pendingReviewCount는 필수 칸의 현재 회차가 ACCEPTED가 아닌 건수이며 미답변·보완 요청 중인 칸도 포함한다. DRAFT에서도 수용 전 건수를 반환한다. 현재 검증 객체가 있으면 실제 활성 검토 칸의 세 카운트를 반환하고, 검증 전·무효화된 초안에서는 null이다. 검증 차단 사유가 있으면 카운트가 0이어도 완전성·제출 가능을 뜻하지 않는다.

비교 문맥 변경으로 STALE이 되는 규칙은 DRAFT에 적용한다. PENDING 이후의 기준 변경은 이미 고정한 검증·비교 상태를 바꾸지 않는다.

파싱 상태는 `QUEUED / RUNNING / NEEDS_REVIEW / SUCCEEDED / FAILED`다. SUCCEEDED는 파일 해석 완료이지 업무 승인·모든 기준 충족이 아니다. 준비 상태·canSubmit·카운트는 저장 테이블을 추가하지 않고 계산한다. P가 다른 담당자의 쓰기 권한을 대신 얻는 플래그로 사용하지 않는다.

### SUB-04 · 같은 파일의 재파싱

입력은 `{expectedStateToken, expectedAttemptNo}`다. DRAFT이며 활성 작업이 종료했고 서버가 retryAllowed=true로 판정한 경우만 가능하다. 파일 내용 오류는 새 전체 파일을 업로드한다. RUNNING/QUEUED 또는 PENDING 이후 재파싱은 거부한다.

시도 번호를 증가시키고 QUEUED로 전환한다. 응답은 갱신된 SubmissionView, 202다. 기존 validation 근거를 무효화해 validationState=STALE로 만들고, 새 활성 시도의 validation 완료 전 최종 제출을 막는다. 재파싱으로 원천 의미가 바뀌면 해당 초안 답변을 다시 확인·작성해야 한다.

재파싱 중에는 이전 시도의 report-values·개별 review-item·evidence·source-cells 조회에 `409 PARSING_NOT_READY`를 반환한다. 전체 review-items 목록에서는 해당 초안의 구시도 항목을 제외한다. 새 파싱 완료 후 report-values·review-item·evidence는 validation 전이면 `409 VALIDATION_REQUIRED`이며, 과거 결과를 현재 결과처럼 보여주지 않는다. source-cells와 diagnostics는 활성 시도의 추출 결과를 기준으로 한다. 원본 file 다운로드는 그대로 허용한다.

내부 워커 결과는 `submissionId, jobId, attemptNo, sourceSha256`을 대조하고 여전히 DRAFT일 때만 반영한다. 이전 시도의 늦은 결과가 금융값·분류·비교·소명을 덮어쓰지 못한다. 외부에서 호출하는 파서 완료 콜백 경로는 이 API에 포함하지 않는다.

### SUB-05 · 초안 검증·비교 갱신

**현재 구현:** `DRAFT + SUCCEEDED`인 연간·월간 제출의 원본 조직·요청별 전체 기간·보고 분류·금액·답안지 대조를 확인하고, 선택 상세·비교·검토 칸·검증 객체를 같은 트랜잭션에서 갱신한다. 연간은 PLAN 1~12월, 월간은 ACTUAL 1~요청월과 ESTIMATE 다음 달~12월을 검사한다. 원본·과거 결과 객체는 보존한다. 원천 금액 구성 변경에는 CHANGE 소명, 기준을 초과한 OP에는 GAP 소명을 준비하며 미답변은 canSubmit=false로 표시한다. 확정 계획·직전 비교본·필요한 Gap 기준이 없으면 진단으로 차단하고 값을 만들지 않는다.

입력은 `{expectedStateToken}`다. DRAFT이고 파싱이 SUCCEEDED일 때 요청별 완전성·보고 분류·현재 유효 기준으로 비교를 갱신한다. 파싱 엔진을 다시 실행하지 않는다.

- 같은 달은 직전 제출 완료 회차, 새 달 첫 제출은 이전 달 마지막 제출 완료본을 선택한다. 반려본 포함, DRAFT 제외.
- 확정 계획본, ACTUAL/ESTIMATE 기준 ID, 검토 정책을 초안에 고정한다.
- 항목별 수정·OP 비교를 계산하고 필요한 review_item과 최초 소명 회차를 준비한다.
- 검토 키·원천 구성·비교 문맥이 같은 초안 답변만 유지한다. 현재 `changedReviewItemIds`는 이번 검증 후 활성 검토 ID 전체를 반환하는 재확인 목록이며 이전 검증 대비 변경분만으로 제한하지 않는다.
- 원천 구성이 달라져 더 이상 같은 검토 건이 아니면 이전 초안 답변을 새로운 근거의 소명으로 자동 채택하지 않는다. 재작성 대상은 `answerRequiredReviewItemIds`로 반환한다.
- 수용 소명 이어받기는 ERD의 같은 조직·항목·기간·시나리오·원천 구성과 이전 ACCEPTED 조건을 충족할 때만 허용한다.

응답은 `{submission: SubmissionView, changedReviewItemIds: [], answerRequiredReviewItemIds: []}`다. 검증 결과는 활성 attemptNo·원본 해시·비교 문맥과 함께 연결한다. 정상적으로 검증했으나 업무 오류가 있으면 200과 validationState=CURRENT·readiness.state=BLOCKED·blockers를 반환한다. 현재 상태가 검증을 허용하지 않으면 409다. 없는 기준값을 0으로 저장하거나 미완성 비교의 가짜 gap_comparison을 만들지 않는다.

### SUB-06~09 · 진단·보고값·원본

| API | 입력 | 응답 |
| --- | --- | --- |
| diagnostics | `severity?, stage?, cursor?, limit?` | `Page<Diagnostic>` |
| report-values | `year?, month?, scenario?, reportItemId?, cursor?, limit?` | `Page<ReportValue>` |
| source-cells | `sheet, cell` 필수, 한 셀 | `SourceCell` |
| file | 없음 | 원본 xlsx 바이트. Content-Disposition: attachment |

Diagnostic은 `code, severity(ERROR/WARNING), stage, message, reportItemId?, year?, month?, scenario?, source?`다. 캐시 미세차 경고에는 `cachedAmount, calculatedAmount, difference` 십진 문자열도 포함한다. stage는 `FILE / PARSING / MAPPING / COMPLETENESS / COMPARISON`, source는 `{sheet, cell}` 또는 null. 상세 진단의 내부 경로·스택·객체 저장 키는 제거한다.

답안지의 저장된 수식 캐시와 정확한 상세 합계의 절대 차이가 0보다 크고 0.00001원 이하이면 WARNING `CONTROL_CACHE_PRECISION`으로 남긴다. `cachedAmount`, `calculatedAmount`, `difference`는 십진 문자열로 보존한다. 0.00001원 초과·미확정·중복 대조값은 ERROR `CONTROL_TOTAL_MISMATCH`다. 캐시 대조는 최대 소수 16자리까지 읽지만 금융 상세 적재는 NUMERIC(30,6) 검증을 그대로 적용한다. 이 허용 범위는 대조 경고에만 적용하며 적재 금액·보고 합계·원본 캐시를 반올림하거나 보정하지 않는다. WARNING만 있으면 complete=true가 가능하다.

ReportValue는 `organizationId, reportItemId, reportItemName, kind, year, month, scenario, valueStatus, amount`다. 현재 kind는 `REVENUE / COST / DERIVED`다. valueStatus는 `AVAILABLE / MISSING / UNRESOLVED`이며 뒤의 두 상태는 amount=null이다. amount는 상세에서 집계하고 OP는 매출−영업비용으로 계산한다. 일부 상세가 미확정이면 영향을 받는 보고 합계도 UNRESOLVED/null로 전파하며, 매출·비용이 불완전하면 OP도 동일하다. 확인된 일부 행만 합산해 AVAILABLE로 반환하지 않는다. 부모 합계·상세·OP를 함께 재합산하지 않는다. 현재 적재·보고 조회는 검증 전 `409 VALIDATION_REQUIRED`, 무효화된 검증은 `409 VALIDATION_STALE`다. 현재 구현은 검증 차단 사유가 하나라도 있으면 전체 보고값을 보수적으로 UNRESOLVED/null로 반환한다.

SourceCell은 `sheet, cell, presence, dataType, rawValue, formula, cachedValue, cacheStatus, normalizationStatus, normalizedAmount, sourceUnit, multiplier, sharedFormulaBase`를 제공한다. presence는 PRESENT/EMPTY, dataType은 NUMBER/STRING/BOOLEAN/DATE/ERROR/BLANK다. rawValue·cachedValue는 string/boolean/null이며 숫자 원문·숫자 캐시는 십진 문자열이다. formula는 수식 문자열 또는 null, normalizationStatus는 CONFIRMED/UNRESOLVED/NOT_NUMERIC/MISSING이다. normalizedAmount·sourceUnit·multiplier는 nullable이며 cacheStatus는 NOT_FORMULA/STORED_UNVERIFIED/MISSING/ERROR다. sharedFormulaBase는 기준이 없으면 null이다. 공유 수식의 종속 셀에서 보존된 기준 근거가 있으면 `sharedFormulaBase:{sheet,cell,formula,range}`를 추가한다. formula는 기준 셀의 실제 원문이며 종속 셀의 수식으로 재작성하지 않는다. 종속 셀의 원래 formula=null을 유지하고, 기준도 확인되지 않았으면 수식 없음으로 단정하지 않는다. 원본 수식 실행·상대 참조 확장·캐시 보정은 하지 않는다. sourceUnit이 null인 정규화 후보는 원 단위로 확정해 표시하지 않는다.

source-cells는 해당 제출 파일의 실제 위치만 조회하며 로컬 경로나 외부 파일 URL을 받지 않는다. 추출 범위에서 확인 가능한 빈칸은 EMPTY·rawValue=null로 반환한다. 잘못된 셀 주소 문법은 400, 없는 시트·수집 범위 밖 자료는 404, 아직 추출 중이면 409다. 빈칸과 조회 실패를 같은 상태로 표현하지 않는다. 현재 정확한 셀 조회는 파서가 보존한 submissionData.sourceCells에 한정하며, 수집 근거가 없는 위치·구버전 결과는 `404 SOURCE_CELL_NOT_FOUND`다. 셀 주소는 대문자 A1 형식의 단일 주소를 받는다.

과거 버전 다운로드도 권한을 확인하고 원본 바이트를 전달한다. 이 API로 재계산·재저장한 파일을 만들지 않는다. 미등록 객체 저장 키를 클라이언트가 지정할 수 없다.

### SUB-12 · 파싱 후보 미리보기

`GET /submissions/{submissionId}/parsed-records?limit=50&cursor=...`는 원본 후보 확인을 위한 초기 연결 API다. 공식 보고값 집계나 업무 검증을 대신하지 않는다.

응답은 `{summary: {sheetCount, recordCount, mappedCount, unresolvedCount, unusedTemplateRowCount}, items: [...], nextCursor}`다. 각 행은 `sheet, cell, organizationCode, organizationName, itemName, accountCode, year, month, scenario, amount, amountUnit, normalizationStatus, formula, rawValue`를 포함한다. amount는 십진 문자열 또는 null이다. `amountUnit`은 원본 단위가 확인되어 원으로 정규화된 경우 `KRW`, 단위가 미확정이면 null이다. null 단위의 숫자를 원으로 표시하지 않으며 기존 후보 숫자를 바꾸지 않는다. 코드표 숫자 등 `value_kind`가 금액이 아닌 후보는 amount=null로 반환한다. scenario는 PLAN/ACTUAL/ESTIMATE 또는 null, normalizationStatus는 CONFIRMED/UNRESOLVED/NOT_NUMERIC/MISSING이다.

권한은 제출 상세와 동일하다. QUEUED/RUNNING은 409 PARSING_NOT_READY다. 결과 저장 참조·로컬 경로·전체 내부 레코드를 노출하지 않는다. 커서는 사용자·제출·활성 시도에 결합한다. source-cells와 달리 임의 셀 좌표를 조회하거나 추출되지 않은 빈 셀을 복원하지 않는다.

### SUB-13 · 검증 적재 금액

`GET /submissions/{submissionId}/financial-values?year&month&scenario&reportItemId&cursor&limit`는 후보 셀이 아닌 `financial_line + financial_value`의 선택된 업무 적재값을 조회한다. 권한은 제출 상세와 동일하며 금액 수정·재검증을 수행하지 않는다.

응답은 `{summary: {lineCount, valueCount, validationState, complete}, items, nextCursor}`다. summary는 해당 검증본 전체의 행·금액 수이며 페이지·필터 결과의 건수가 아니다. 각 item은 `id, lineId, reportItem:{id,code,name,kind}, accountCode, accountName, teamCode, taskCode, detailId, description, year, month, scenario, amount, source:{sheet,cell,row}`를 포함한다. ID와 원 단위 amount는 문자열이다. 팀·과제·계정과 출처는 상세 근거이며 별도 Gap 평가 키가 아니다.

CURRENT이나 차단 사유가 있으면 유효한 부분 적재행은 200으로 조회할 수 있고 `summary.complete=false`다. UI는 이를 검증 초안으로 표시한다. 검증 전/무효화는 `409 VALIDATION_REQUIRED/VALIDATION_STALE`다. 적재·보고 조회는 read-only REPEATABLE_READ에서 요약·값·커서를 같은 DB 스냅샷으로 읽는다. 커서는 사용자·제출·검증 참조·직전 제출 문맥·조회명·필터에 결합한다. 원본 후보를 확인하려면 SUB-12, 정확한 수식·캐시·배율은 SUB-08을 사용한다.

### SUB-10 · 최종 제출

**현재 구현:** 담당자의 연간·월간 검증 완료 초안을 필수 소명 답변과 함께 `PENDING`으로 고정하고 제출 회차·제출자·시각을 기록한다. 아래 조건을 만족하지 않으면 제출 상태를 바꾸지 않는다. 최종 제출만으로 연간·월간 마스터를 발행하지 않는다. `canSubmit=false`는 최종 제출 시 `409 SUBMISSION_NOT_READY`, 오래된 검증·비교 문맥은 `409 REVIEW_CONTEXT_CHANGED`다.

입력은 `{expectedStateToken}`다. 서버는 조직 단위 잠금 아래 아래 조건을 재검사한다.

1. DRAFT, 열린 요청, 대상 조직, 다른 PENDING 없음, 재제출 요청 범위 일치. ANNUAL_PLAN이면 연간 미확정, MONTHLY_ACTUAL이면 연간 확정 및 해당 조직의 고정 계획본 존재.
2. 현재 활성 파싱 시도와 그 시도의 validation 완료, 필수 항목·월·조직 전체 스냅샷 충족, 금액·보고 분류 확정. 비교·소명 대상이 없는 최초 PLAN도 validation을 생략할 수 없음.
3. 초안의 비교본·확정 계획·임계값 ID가 현재 유효한 선택과 일치.
4. 금액 수정·OP Gap의 모든 필수 항목에 현재 회차 답변 존재. 이어받은 ACCEPTED는 작성 완료로 본다.

3번이 달라졌으면 `409 REVIEW_CONTEXT_CHANGED`, 회차·PENDING 전환 없음이다. GET으로 상태를 확인한 뒤 validation → 소명 확인/수정 → 최신 stateToken으로 submit한다. GET이나 실패한 submit이 몰래 소명 대상을 변경하지 않는다.

모든 조건이 맞으면 양수 seq·submittedBy/At을 부여하고 PENDING으로 전환한다. 응답은 SubmissionView다. 금융행·금액·선택 기준·검토 대상은 이후 잠긴다. 최초 제출 전에 쓴 답변도 이 시점부터 덮어쓰지 않는다.

### SUB-11 · 제출 승인·반려

**현재 구현:** PLANNER가 최신 PENDING 제출본을 판단한다. 승인은 제출 당시 연간 `annual-plan/1.0` 또는 월간 `monthly-actual/1.0` 검증 객체·원본/파싱 참조·완전성·적재 건수와 모든 필수 소명 수용을 확인하며, 요청 종료 후에도 검토할 수 있다. REJECT는 미완료 소명과 무관하게 가능하다. 원본·금액·제출 회차는 바꾸지 않고 검토자/시각과 반려 사유를 기록한다. APPROVE는 연간 `kind=ANNUAL_PLAN, month=null` 효과 한 건 또는 월간 `kind=MONTHLY_ACTUAL`의 영향 ACTUAL 월별 효과를 반환한다. REJECT는 `masterEffects=[]`다. 반려 후 같은 요청에 새 전체 파일을 업로드하고 수정된 모든 금액 항목의 소명을 작성한다.

입력은 `{expectedStateToken, action, note?}`다. action은 APPROVE 또는 REJECT. note는 REJECT에만 허용하며 공백이 아닌 1~2000자 문자열이 필수다. 저장 시 양끝 공백을 제거한다. APPROVE에는 승인 메모 필드가 없으므로 note를 보내면 400 INVALID_REQUEST다.

- APPROVE: PENDING이고 모든 필수 항목의 마지막 소명이 ACCEPTED여야 한다. 필수 항목이 없으면 이 조건 충족. 최종 제출 당시 기준으로 검토하며 최신 기준으로 다시 판정하지 않는다.
- REJECT: PENDING이면 소명 수용 여부와 무관하게 제출본 자체 오류로 반려할 수 있다. REJECTED와 rejectReason·검토자/시각 기록. 숫자·기존 마스터는 유지한다.
- 새 키로 종료된 상태에 다시 판단하면 `409 INVALID_SUBMISSION_STATE`. 성공한 동일 멱등키는 원래 결과 재전달.

승인·영향 월의 마스터 발행은 원자적이다. 하나라도 실패하면 승인도 성공으로 응답하지 않는다. 응답은 `{submission: SubmissionView, masterEffects: MasterEffect[]}`이다. REJECT는 masterEffects=[]이며 연간 승인은 아래 AMAS 계약의 효과 한 건을 반환한다. 다음은 월간 응답 예시다.

```json
{
  "submission": {
    "id": "1201",
    "status": "APPROVED",
    "seq": 2,
    "versionLabel": "2026-02-02"
  },
  "masterEffects": [
    {
      "kind": "MONTHLY_ACTUAL",
      "year": 2026,
      "month": 2,
      "action": "UPDATED",
      "versionLabel": "master-2026-02-02",
      "masterId": "7002",
      "previousMasterId": "7001",
      "approvedOrganizationCount": 17,
      "requiredOrganizationCount": 17
    }
  ]
}
```

위 예시는 submission의 주요 필드만 발췌했으며 실제 응답은 전체 SubmissionView를 사용한다.

MasterEffect.action은 `CREATED / UPDATED / UNCHANGED / WAITING_INITIAL`이다. WAITING_INITIAL이면 masterId·previousMasterId는 null이며 조직 승인 자체는 성공이다. 최초 전 조직 승인 대기를 승인 실패로 처리하지 않는다.

이미 발행된 월은 새 승인 조직만 참조를 교체한다. 다른 조직의 새 PENDING/REJECTED는 오류가 아니다. 금액·원천 구성이 같으면 UNCHANGED이며 기존 masterId를 반환한다. 승인본의 과거 ACTUAL 월도 전체 스냅샷이면 같은 원칙으로 처리한다. ESTIMATE는 masterEffects 대상이 아니다.

## 7. 수정·손익 Gap·소명

### REV-01~02 · 검토 목록과 상세

목록 필터는 `requestId, submissionId, organizationId, reportItemId, year, month, scenario, reason, explanationState, cursor, limit`. reason은 `CHANGE / GAP / INHERITED`이며 생략하면 전체다. 권한 필터를 모든 조건보다 먼저 적용한다.

`ReviewItemView`:

| 필드 | 타입·의미 |
| --- | --- |
| id, submissionId, organizationId, reportItem | 검토 건·소속·보고 항목 |
| year, month, scenario | 검토 칸 |
| amountPresent, currentAmount | 존재하면 금액 필수. 허용된 선택 항목 제거만 false/null |
| changeDetected, gapExceeded | 원천 변경과 OP 손익 Gap을 분리 |
| required | 두 플래그 중 하나가 참인 경우. 이어받기 전용은 false |
| explanationState | UNANSWERED / ANSWERED / IN_REVIEW / ACCEPTED. ANSWERED는 DRAFT에 저장한 답변, IN_REVIEW는 제출 후 검토 대기 |
| currentRound, stateToken | 현재 소명 회차와 검토 건 변경 토큰 |
| comparisons | 상세에 포함하는 `GapComparisonView[]`. 수정만 있으면 빈 배열 |
| explanations | 상세에 포함하는 소명 회차 배열, round ASC. 목록은 마지막 회차 요약만 반환 |

GapComparisonView는 `basis(PLAN/PREVIOUS), baselineSubmissionId, baselineScenario, baselineAmount, currentAmount, gapAmount, gapRate, threshold:{id,scenario,percent,amount}, exceeded`다. 실제 기준 0이면 baselineAmount="0", gapRate=null이며 현재 값이 0이 아니면 exceeded=true다. 0이 아닌 기준은 절대 비율과 절대 금액 모두 엄격하게 초과해야 한다.

필수 기준이 누락되면 진단에서 `BASELINE_MISSING`을 제공하고 제출을 차단한다. 비교하지 않는 대상(NOT_APPLICABLE), 실제 기준 0, 누락(MISSING)을 같은 상태로 표시하지 않는다. review_item 상세의 정상 비교 배열에는 확인된 비교만 반환하며 초과하지 않은 해당 비교도 생략하지 않는다.

Explanation은 `id, round, requestNote, requestedBy, requestedAt, answer, answeredBy, answeredAt, verdict, reviewedBy, reviewedAt, reviewNote, inheritedFromId, inheritedFrom`이다. 최초 회차의 요청 메타데이터와 아직 발생하지 않은 행위는 null이다. verdict는 PENDING/ACCEPTED/REJECTED. REJECTED 이후 새 회차가 준비되면 explanationState는 새 회차 기준 UNANSWERED다.

inheritedFrom은 `{explanationId,reviewItemId,submissionId,versionLabel}` 또는 null이며 직접 이어받은 소명의 제출 버전을 표시하는 조회용 참조다. 별도 저장 컬럼 없이 기존 FK에서 조회하며, 사용자에게 허용된 조직 범위에서만 반환한다. 행위자는 공통 ActorRef로 표시한다.

### REV-03 · 원천 변경 근거

입력은 `reason=CHANGE|GAP`, `cursor?, limit?`. GAP이면 `basis=PLAN|PREVIOUS`가 필수이며 CHANGE에서는 basis를 받지 않는다. 해당 검토 항목에 없는 reason/basis는 422 INVALID_COMPARISON_SELECTION이다. 응답은 `reviewItemId, reason, basis, baselineSubmissionId, baselineScenario, currentSubmissionId, currentScenario, comparisonMode, beforeTotal, afterTotal, netDelta, items, nextCursor`다. CHANGE의 기준은 같은 시나리오의 previous 제출본이며 basis=null이다. 기준이 없으면 기준 ID·시나리오·총액·차액은 null이다.

comparisonMode는 `IDENTITY / MULTISET / UNRESOLVED / NOT_APPLICABLE`. items는 `{side: BEFORE|AFTER, groupKey, lineId, valueId, amount, semanticAttributes, source:{submissionId,sheet,cell}}`이며 같은 groupKey의 중복 개수도 보존한다. semanticAttributes는 확인된 원본 계정·관리분류·실적집계기준·팀·과제·거래 의미다. groupKey는 해당 비교의 표시용이며 영구 업무키가 아니다.

안정적인 상세 ID가 없으면 임의의 before→after 한 쌍을 만들지 않고 양쪽 다중집합으로 보여준다. 합계 차액이 "0"이어도 원천 구성이 달라지면 수정 소명 대상이다. OP의 근거는 해당 월 매출·비용 보고 항목과 원천을 연결한다. PLAN 비교와 1월의 PREVIOUS 대체 비교는 baselineScenario=PLAN일 수 있다. source-cells는 각 source.submissionId를 사용해 실제 기준본 또는 현재 제출본을 연다.

### REV-04 · 현재 회차 답변 저장

`{expectedStateToken, round, answer}`. answer는 공백만 허용하지 않는 1~4,000자다. 현재 프런트는 양끝 공백을 제거해 전송하며 서버는 받은 문자열을 보존한다. 해당 item의 소유 조직만 가능하다.

- DRAFT: 현재 최초 답변을 수정할 수 있다. 성공 후 review item과 submission의 stateToken이 바뀔 수 있다.
- PENDING: 보완 요청으로 열린 현재 미답변 회차에 한 번만 답변한다. 기존 제출 답변·검토 중 답변을 덮어쓰지 않는다.
- 현재 회차가 아니면 `409 EXPLANATION_ROUND_CHANGED`, 답변이 잠겼으면 `409 EXPLANATION_LOCKED`.
- 동일 내용의 단순 재전송은 권한·현재 round·답변자 일치부터 확인한 뒤 200으로 현재 결과를 반환한다. 이때 답변자·답변시각을 갱신하지 않으며 토큰 재검사의 예외로 처리한다. 다른 내용은 상태·토큰 검사를 거친다.
- 응답은 ReviewItemView이며 answeredBy/At은 서버에서 기록한다.

클라이언트가 verdict·round 증가·inheritedFromId·검토자를 지정하지 못한다. 이어받은 수용 소명은 서버가 생성하며 이 경로로 바꾸지 못한다.

### REV-05 · 소명 수용·보완 요청

`{expectedStateToken, round, action, note?}`. action은 `ACCEPT / REQUEST_CHANGES`이고 후자에는 note가 필수다. 제출본이 PENDING이고 현재 답변이 있으며 verdict=PENDING이어야 한다.

ACCEPT는 현재 회차에 수용자/시각·선택 reviewNote를 기록한다. REQUEST_CHANGES는 현재 회차를 REJECTED로 확정하고 새 회차를 만들어 requestNote·요청자·시각을 기록한다. 두 변경은 원자적이며 새 회차 answer는 null이다. 응답은 ReviewItemView다.

제출본은 계속 PENDING이고 제출 회차는 그대로다. 승인과 보완 요청의 경합은 같은 제출본 잠금에서 처리한다. 승인된 제출본에 뒤늦은 재요청을 반영하지 않는다.

## 8. Gap 기준

### GAP-01 · 현재 기준

응답은 `{actual: Threshold|null, estimate: Threshold|null, stateToken}`. Threshold는 `id, scenario, percent, amount, appliedBy, appliedAt`다. 아직 설정하지 않은 시나리오는 null이다. 이미 제출된 항목의 기준은 이 현재 기준이 아니라 ReviewItemView의 고정된 threshold다.

### GAP-02 · 영향 미리보기

입력은 `{requestId, scenario, percent, amount}`. requestId는 월간 요청, scenario는 ACTUAL/ESTIMATE, percent·amount는 정수 24자리·소수 6자리 이내의 0 이상 십진 문자열이다.

해당 요청에서 조직별 최신 제출 완료본(DRAFT 제외)의 해당 시나리오 OP 비교를 대상으로 후보 기준을 적용한다. 기존 review_item/gap_comparison 존재 여부와 무관하게 해당 제출본의 금융값·고정 비교본·정책으로 적용 대상 OP 전체를 재구성한다. 이전 기준에서 미초과여서 검토 행이 없던 칸도 평가한다. 두 비교가 모두 초과해도 검토 칸·조직 수는 중복 계산하지 않는다. 실제 기준 0 특례를 그대로 적용한다.

응답은 `{requestId, scenario, evaluatedItemCount, exceededItemCount, exceededOrganizationCount, excludedItemCount, exclusions, sourceSubmissionIds, evaluatedAt}`다. exclusions는 `{code,count}` 목록이다. 계산 불가·누락을 미초과로 세지 않는다. 이 호출은 기준·비교·소명 상태를 저장하거나 기존 제출본의 승인을 바꾸지 않는다.

### GAP-03 · 새 기준 적용

입력은 `{expectedStateToken, scenario, percent, amount}`. expectedStateToken은 GAP-01 응답의 값이다. 기존 행 UPDATE 없이 해당 시나리오의 새 기준 행을 생성하고 Threshold를 반환한다. 별도 단건 조회 경로를 두지 않아 Location 헤더는 생략한다. 현재 기준은 GAP-01, 제출본의 고정 기준은 해당 검토 상세에서 조회한다.

이미 제출된 기준은 유지한다. 작성 중 DRAFT는 최종 제출 전에 새 유효 기준으로 validation을 거쳐야 한다. ACTUAL·ESTIMATE는 각각 적용하며, 두 값을 한꺼번에 적용하는 기능이 필요하면 원자적 일괄 적용 계약을 별도 검토한다.

## 9. 마스터·홈·보고

### AMAS-01~04 · 연간 PLAN 마스터 — 현재 구현

연간 계획 마스터는 승인된 1~12월 PLAN 전체 자료의 고정 조합이다. 연간 계획 확정 및 Contingency 입력과 별개이며 발행해도 `annual_plan.status=COLLECTING`을 유지한다. 마스터 조회에는 Contingency나 월간 ACTUAL/ESTIMATE를 합성하지 않는다.

- AMAS-01은 `{requestId,year,scenario:"PLAN",publicationState,approvedOrganizationCount,requiredOrganizationCount,missingOrganizations,latestMasterId,latestVersionLabel}`을 반환한다. publicationState는 `WAITING_INITIAL / PUBLISHED`다. missingOrganizations는 `{id,code,name,status}` 목록이다. 최신 제출 완료 상태를 세며 DRAFT는 승인 상태를 대체하지 않는다.
- AMAS-02는 `requestId`와 일반 `limit/cursor`를 받고 최신 버전부터 페이지로 반환한다.
- AMAS-03은 `{id,requestId,year,scenario,seq,versionLabel,previousMasterId,triggerSubmissionId,createdAt,createdBy,calculationVersion,sources,totals,months,organizations}`다. sources는 `{organizationId,organizationCode,organizationName,submissionId,versionLabel}`이며 totals와 각 month는 정확한 십진 문자열 `revenue/cost/operatingProfit`와 nullable 십진 백분율 `operatingMargin`을 갖는다. months는 1~12월이다. organizations는 각 source의 식별자·원천 참조에 해당 조직의 totals·months를 붙인 배열이다. 조직별 totals·months에는 전사 영업이익 대비 `operatingProfitShare`도 포함한다. 비율은 백분율로 소수 둘째 자리 HALF_UP이며 이익률의 매출 분모 또는 기여도의 전사 손익 분모가 0이면 null이다. 금액 자체는 반올림하지 않는다.
- AMAS-04 필터는 `organizationId,month,limit,cursor`다. 보고행은 `{reportItemId,reportItemCode,reportItemName,kind,year,month,scenario:"PLAN",organizationId,amount,valueStatus:"AVAILABLE"}`이며 조직 미선택 시 organizationId는 null이고 전사 합계다. 검증 시 고정된 항목 집합과 명시적 0만 사용한다. 조직이 해당 마스터에 없거나 월·커서 범위가 틀리면 오류이며 다른 마스터의 필터/커서를 재사용할 수 없다.

첫 발행은 요청의 `excluded=false`인 전 대상 조직의 최신 완료본이 모두 유효한 APPROVED일 때만 허용한다. 이후 승인은 이전 마스터의 조직 구성을 복사하고 승인된 조직만 전체 제출본으로 교체한다. 다른 조직의 새 PENDING/REJECTED는 이전 승인본을 유지한다. 상세 금액·의미 구성 지문이 같으면 새 버전을 만들지 않는다. 과거 마스터의 sources와 값은 현재 최신본으로 바꾸지 않는다.

연간 승인 응답의 효과는 `{kind:"ANNUAL_PLAN",year,month:null,action,masterId,previousMasterId,approvedOrganizationCount,requiredOrganizationCount,versionLabel}`이다. action은 `CREATED / UPDATED / UNCHANGED / WAITING_INITIAL`이며 대기 중에는 마스터 ID·버전이 null이다. 표시 버전은 `master-YYYY-PLAN-NN`이다. 승인·발행·멱등 성공 응답은 같은 트랜잭션이며 발행 실패는 승인까지 롤백한다.

local,demo 환경의 D100/D200은 비활성화하고 수집 중이며 아직 확정·발행하지 않은 요청에서 제외한다. 행과 제출 이력은 삭제하지 않는다. 이는 기존 체험 자료의 정정이며 일반 조직 변경 API가 아니다. 이후 확정/발행한 요청의 대상 집합은 바꾸지 않는다.

홈의 계획 보기는 위 연간 PLAN 마스터 상세 한 번으로 전사·조직의 월별/연간 손익을 표시한다. 조직별 17회 조회나 최신 제출본 재조합을 하지 않는다. 현재 제공된 매출·비용·영업이익으로만 계산하고 실적·추정·전년 대비·매출총이익·순이익을 생성하지 않는다. 미발행과 조회 실패를 구분하며 발행 마스터가 없으면 금액을 0으로 표시하지 않는다.

MAS-01~04는 구현된 월간 ACTUAL 마스터 조회다. REP-01만 보고 데이터 구성의 후속 설계다.

### MAS-01 · 월별 발행 현황

`GET /master-periods?year=2026`. year 필수, 해당 연도의 12개월을 `{year, annualContingency, items}`로 반환한다. annualContingency는 `{annualPlanId, amount, scope:"ANNUAL"}` 또는 null이다.

각 월은 `month, requestId, publicationState, requiredOrganizationCount, approvedOrganizationCount, latestMasterId, latestVersionLabel, totals, missingOrganizations`다. missingOrganizations는 `{id,code,name,status}` 배열이며 status는 최신 완료본 상태 또는 NOT_SUBMITTED다. publicationState는 `NOT_REQUESTED / WAITING_INITIAL / PUBLISHED`. 요청 없음이면 requestId=null·카운트=0, 미발행이면 최신 master와 totals는 null이다.

totals는 최신 master의 `{revenue,cost,operatingProfit,operatingMargin}` ACTUAL 값이다. 기존 마스터가 있으면 다른 조직이 새 제출본을 검토 중이어도 PUBLISHED와 기존 총액을 유지한다. 카운트는 요청의 최신 제출 상태 기준이며 마스터의 고정 구성 수와 다를 수 있다.

Contingency는 연간 계획의 전사 금액으로 별도 제공한다. 월별 배분이나 매월 전액 차감을 임의로 계산하지 않는다. 월별/누적 보고 영업이익에 적용할 표시 정책은 구현 전 결정 항목이다.

### MAS-02~04 · 버전과 값

- `GET /masters?year=2026&month=2`: year·month 필수. `Page<MasterSummary>`, 최신 seq부터. 미발행이면 빈 items.
- `GET /masters/{masterId}`: `MasterView`. 존재하지 않는 ID는 404.
- `GET /masters/{masterId}/report-values?organizationId=&reportItemId=`: 필터는 선택이며 `cursor,limit` 사용. `Page<ReportValue>`. 버전의 대상 연월·ACTUAL로 고정하고 다른 연월·ESTIMATE 필터는 받지 않는다.

MasterSummary는 `id, requestId, year, month, seq, versionLabel, previousMasterId, triggerSubmissionId, createdAt`다. MasterView는 여기에 `scenario:"ACTUAL", calculationVersion, sources, totals, planTotals, comparisons, organizations, annualContingency`를 포함한다. sources는 `{organizationId,organizationCode,organizationName,submissionId,versionLabel,planSubmissionId}`의 고정된 전체 대상 집합이다.

totals는 마스터의 실제 값, planTotals는 고정된 조직별 확정 PLAN의 같은 월 값이며 둘 다 `{revenue,cost,operatingProfit,operatingMargin}`다. comparisons는 `revenue, cost, operatingProfit`별 `{baselineAmount,currentAmount,gapAmount,gapRate}`다. 차액은 ACTUAL−PLAN, 비율은 차액÷abs(PLAN)×100을 소수 2자리 HALF_UP으로 표시하며 PLAN=0이면 null이다. 이 표시용 비교율을 검토의 임계값 판정에 재사용하지 않는다.

organizations 각 행은 source 메타데이터와 그 조직의 `totals,planTotals,comparisons`를 포함한다. 조직 totals·planTotals에는 각각 전사 ACTUAL·PLAN 영업이익을 분모로 한 operatingProfitShare가 추가되며 분모가 0이면 null이다. operatingMargin도 해당 매출이 0이면 null이다. annualContingency는 `{annualPlanId,amount,scope:"ANNUAL"}`이며 실적·계획 손익에서 차감하거나 월별로 배분하지 않는다.

조회 시 현재 최신 제출본으로 sources를 교체하지 않는다. 이후 월 제출본이 과거 ACTUAL을 고쳤다면 그 이후 월 제출본 ID가 해당 과거 master source가 될 수 있다. 실제 값 조회에서는 마스터 대상 월만 선택한다.

### REP-01 · 보고 데이터 구성 (후속 설계)

입력은 `{masterIds:["7001","7005"], includeExplanations:true}`다. masterIds는 필수이고 비어 있지 않으며 중복 ID·동일 연월의 복수 버전을 허용하지 않는다. includeExplanations는 선택이며 기본 false다. 다른 연도의 master도 전년 비교 목적으로 선택할 수 있다. 서버가 최신 버전으로 조용히 바꾸지 않는다.

응답은 `{generatedAt, masterIds, calculationVersions, periods, annualContingencies, explanations}`이며 내부 구조는 다음과 같다.

| 필드 | 구조 |
| --- | --- |
| calculationVersions | `{masterId, version}` 배열 |
| periods | `{masterId, year, month, totalRows, organizations}` 배열, 연월 ASC |
| totalRows | 전사 보고 항목별 `{reportItemId, valueStatus, amount}` 배열 |
| organizations | `{organizationId, rows}` 배열. rows는 해당 조직의 `{reportItemId, valueStatus, amount}` 배열 |
| annualContingencies | 선택된 연도별 `{annualPlanId, year, amount, scope:"ANNUAL"}` 배열 |
| explanations | `{masterId, submissionId, reviewItemId, reportItemId, organizationId, year, month, scenario, explanationId, round, answer, answeredBy, answeredAt, reviewedBy, reviewedAt, inheritedFrom}` 배열 |

모든 periods와 explanations의 시나리오는 ACTUAL이다. 금액·상태·행위자 타입은 ReportValue와 ActorRef 규약을 따른다. explanations는 선택 master source의 해당 월에 연결된 수용 소명만 사용하고 includeExplanations=false이면 빈 배열이다. 다른 제출본이나 최신 검토 중 소명을 섞지 않는다. 이어받기 참조는 Explanation.inheritedFrom 구조와 같다.

기본 표·차트의 JSON만 구성하며 저장형 report 행이나 내보내기 파일은 만들지 않는다. 생성물을 프론트엔드에서 내보낼 때 masterIds·calculationVersions를 메타데이터에 포함한다. 파일 포맷·서버 내보내기가 필요하면 별도 계약으로 확장한다.

## 10. 파서·백엔드 연결 경계

| 계층 | 책임 | API에 그대로 노출하지 않는 것 |
| --- | --- | --- |
| 백엔드 | 인증·요청·조직·업무 ID·상태·멱등 처리·트랜잭션 | 사용자 임의 조직·회차·승인 상태 |
| 파서 | 읽기·정규화·업무필드 진단·원본 근거 | 원본 파일 경로, 내부 예외 스택 |
| 적재 어댑터 | 상세 선택·report_item 매핑·전체 범위 확인 | 모든 파서 후보를 승인 금융값으로 일괄 적재 |
| 검토 서비스 | 원천 수정·OP 비교·소명 회차 | 클라이언트 계산 금액·초과 여부 |
| 승인 서비스 | 승인과 월별 source 교체·불변 마스터 | 기술 마스터 파일을 공식 승인본으로 간주 |

로컬 `src/excel_parser/`와 GitHub `parser/`는 구현 범위가 다르다. GitHub에는 상세 ID·개정번호·업무검증·기술 마스터 조립 기능이 있다. 실제 원격 필드는 [PR의 DB 매핑](https://github.com/shinharyeong/skala_final_project/blob/8c84d2783c9beb2f876810387baa7f9e3f503a50/docs/development/db-mapping.md)을 따른다.

둘 다 이 서비스의 인증·요청별 완전성·소명·승인 트랜잭션을 이미 구현한 것은 아니다. `mapped / confirmed / business_validation.valid`와 `SUCCEEDED / PENDING / APPROVED`를 동일 상태로 매핑하지 않는다. 파서 결과의 원본 단위·배율·수식·출처는 결과 참조에 보존하고 서비스 DTO로 필요한 부분만 변환한다.

초기 구현은 내부 Python 프로세스에 stdin으로 XLSX 바이트를 전달하고 stdout의 JSON 1.0을 받는다. 원본 임시 파일은 만들지 않는다. `parsing_job`의 DB 큐를 폴링하고 CAS로 작업을 확보한다. 제한시간이 지난 RUNNING은 FAILED로 복구하며 운영 오류에 한해 명시적 재파싱을 허용한다. 실제 저장 바이트의 SHA-256과 활성 시도·DRAFT 상태를 확인한 결과만 채택한다. 원본·JSON은 초기 DB 객체 저장 어댑터에 보존하며 대외 DTO에는 객체 키를 노출하지 않는다.

validation 실행 근거는 단순히 review_item 개수로 추정하지 않는다. 구현 제안은 `parsing_job.result_object_key`가 가리키는 서비스 결과 메타데이터에 `validatedAttemptNo, sourceSha256, reviewPolicyVersion, comparisonContext, validationCompletedAt, blockers`를 함께 보존하고, 해당 참조와 초안 검토 데이터를 원자적으로 채택하는 방식이다. 최초 PLAN처럼 검토 칸이 0개여도 이 근거가 있어야 CURRENT다. 재파싱은 이전 근거를 무효화한다. 현재 구현은 `submission_validation`이 활성 시도·원본 해시·파서 결과·정책·불변 검증 객체를 참조한다. 검증 객체에는 비교본·진단·집계·완전성·구성 지문을 보존한다. 기존 파서 JSONL의 필드로 가정하지 않는다.

## 11. 오류 계약

오류 본문은 `application/problem+json`으로 제안한다. 기본 필드에 프로젝트 확장 `code, requestId, errors`를 추가한다. 내부 오류 메시지를 그대로 복사하지 않는다.

```json
{
  "type": "about:blank",
  "title": "Conflict",
  "status": 409,
  "detail": "비교 기준이 변경되어 소명 대상을 다시 확인해야 합니다.",
  "instance": "/api/v1/submissions/1201/submit",
  "code": "REVIEW_CONTEXT_CHANGED",
  "requestId": "trace-example-01",
  "errors": [{"field": "comparisonContext", "code": "STALE_CONTEXT"}]
}
```

errors는 `{field,code}` 목록이며 비어 있을 수 있다. 업무 오류는 필요한 `reviewItemIds, organizationIds, periods`를 추가할 수 있으나 접근 가능한 범위만 반환한다.

| HTTP | code | 상황·대응 |
| --- | --- | --- |
| 400 | INVALID_CURSOR | 커서 형식 또는 사용자·필터 범위 불일치 |
| 400 | INVALID_REQUEST | 필수 필드·토큰·멱등키 누락, 잘못된 enum·쿼리·ID 형식 |
| 401 | UNAUTHENTICATED | 인증 필요/만료. 구체적인 인증 challenge는 로그인 방식 확정 후 정의 |
| 403 | FORBIDDEN | 역할에 없는 명령 또는 다른 조직 필터 |
| 404 | RESOURCE_NOT_FOUND | 없음 또는 접근 범위 밖 리소스 |
| 409 | RESOURCE_CHANGED | 상태 토큰 불일치, 최신 GET 후 다시 판단 |
| 409 | IDEMPOTENCY_KEY_REUSED / REQUEST_IN_PROGRESS | 같은 키의 다른 요청 / 진행 중 재시도 |
| 409 | REQUEST_ALREADY_EXISTS / REQUEST_CLOSED / REQUEST_TARGETS_EMPTY | 요청 생성·접수 조건 충돌 |
| 409 | STATE_TOKEN_MISMATCH | 연간 확정 후보의 상태 토큰 불일치 |
| 409 | ANNUAL_PLAN_NOT_CONFIRMED / ANNUAL_PLAN_LOCKED / PLAN_APPROVALS_INCOMPLETE | 계획 기준·확정 조건 미충족 |
| 409 | PLAN_SOURCE_MISSING | 해당 조직의 확정 계획본 참조 없음 |
| 409 | PENDING_ALREADY_EXISTS / RESUBMISSION_SCOPE_CONFLICT | 조직 단위 제출 제한 또는 다른 요청의 재제출 필요 |
| 409 | INVALID_SUBMISSION_STATE | 현재 상태에서 명령 불가 |
| 409 | PARSING_NOT_READY / PARSING_ATTEMPT_CONFLICT / PARSING_RETRY_NOT_ALLOWED | 파싱 준비·시도·재시도 조건 문제 |
| 409 | VALIDATION_REQUIRED / VALIDATION_STALE | 현재 파싱 시도의 검증 미실행 / 기존 검증 무효화, 검증 후 진행 |
| 409 | VALIDATION_NOT_ALLOWED / SUBMISSION_NOT_READY | 검증할 수 없는 상태 / 최종 제출 조건 미충족 |
| 409 | REPORT_CATALOG_CONFLICT | 같은 보고 코드의 저장 이름·분류가 원본과 충돌 |
| 404 | SOURCE_CELL_NOT_FOUND | 해당 제출본의 원본 셀 수집 근거 없음 |
| 409 | REVIEW_CONTEXT_CHANGED / BASELINE_MISSING / THRESHOLD_MISSING | 비교 문맥 변경 또는 필수 기준 부재 |
| 409 | REQUEST_TARGET_EXCLUDED | 기존 이력은 보존하지만 현재 제출·승인 대상에서 제외됨 |
| 409 | REQUIRED_EXPLANATION_MISSING / EXPLANATIONS_NOT_ACCEPTED | 최종 제출 답변 누락 / 승인 전 수용 미완료 |
| 409 | EXPLANATION_ROUND_CHANGED / EXPLANATION_LOCKED | 오래된 회차 또는 답변·검토 잠금 |
| 413 | FILE_TOO_LARGE | 업로드 또는 압축 해제 크기 한도 초과 |
| 415 | UNSUPPORTED_FILE_TYPE | 초기 지원 밖 형식 |
| 422 | INVALID_FILENAME / INVALID_WORKBOOK / ORGANIZATION_MISMATCH | 요청과 맞지 않는 파일·손상·조직 불일치 |
| 422 | INCOMPLETE_SNAPSHOT / UNRESOLVED_REPORT_ITEM / INVALID_AMOUNT | 전체 범위·보고 분류·금액 검증 실패 |
| 422 | INVALID_THRESHOLD / INVALID_CONTINGENCY / INVALID_REPORT_SELECTION | 수치 범위나 보고 master 선택 오류 |
| 422 | INVALID_COMPARISON_SELECTION | 검토 항목에 없는 수정/Gap 근거 또는 비교 기준 선택 |
| 429 | RATE_LIMITED | 요청 한도, Retry-After 확인 |
| 503 | PARSER_BUSY | 조직당 QUEUED/RUNNING 10건 한도 |
| 503 | PROCESSING_UNAVAILABLE | 파싱 접수 또는 업무 처리 기반 사용 불가. 성공처럼 응답하지 않음 |
| 500 | INTERNAL_ERROR | 예상하지 못한 오류. 추적 ID 제공 |

비동기 파싱·업무검증에서 발견한 내용은 GET diagnostics/readiness의 code로 반환한다. GET 자체가 성공했으면 HTTP는 200이다. submit이 그 문제 때문에 거부되면 위 HTTP 분류를 적용한다. 소명 부족은 파싱 실패로 취급하지 않는다.

## 12. 구현 인수 조건

아래는 전체 계약의 인수 조건이다. 실행 검증 범위와 실제 결과는 [로컬 실행 환경](local-environment.md)에서 따로 관리한다. 이 표 전체의 구현 완료를 뜻하지 않는다.

| 상황 | 기대 결과 |
| --- | --- |
| O가 다른 조직 request/submission/review/source 조회 | 권한 범위 밖 자료 노출 없음 |
| 같은 키·같은 파일 업로드 재시도 | 같은 DRAFT, 새 seq·파싱 작업 중복 생성 없음 |
| 같은 키에 다른 파일 | 409 IDEMPOTENCY_KEY_REUSED |
| 이전 parsing attempt 결과가 늦게 도착 | 현재 결과·답변·숫자 덮어쓰기 없음 |
| 파싱 성공, 필수 월 누락 | BLOCKED, 최종 제출 차단, 누락을 0으로 보정하지 않음 |
| 계정은 같고 관리분류가 다름 | 별도 보고 항목으로 연결 |
| 수정 금액이 기준 이하 | 수정 소명 필수 |
| 같은 항목의 증감 상쇄 | changeDetected=true, 소명 필수, 합계 동일만으로 이어받지 않음 |
| 행 재정렬·셀 이동만 발생 | 수정 소명 추가 요구 없음 |
| 기준 0 → 현재 비0 / 기준값 누락 | 전자는 OP 초과, 후자는 비교 불가·제출 차단 |
| 이전에 미초과 OP가 후보 기준을 낮추면 초과 | 기존 검토 행이 없어도 미리보기 대상·초과 건수에 포함 |
| 파싱만 성공한 최초 PLAN에서 바로 submit | 검토 항목이 0개여도 VALIDATION_REQUIRED로 차단 |
| DRAFT 중 기준·이전 제출본 변경 | submit 409, 회차 미부여, validation 후 확인 |
| 연간 계획 최초 / 확정 전 재제출 | 최초 수정 소명 없음 / 바뀐 금액 소명 필수 |
| 소명 보완 요청 | 같은 PENDING, 새 소명 회차만 생성 |
| 제출본 자체 오류와 미수용 소명 동시 존재 | 소명 수용 전에도 REJECT 가능 |
| 소명 수용과 재요청 또는 승인 동시 수행 | 잠금 아래 한 전이만 성공, 나머지는 충돌 |
| 첫 월의 한 조직 승인·나머지 미승인 | 조직 승인 200, WAITING_INITIAL, master 미발행 |
| 기존 master에 A 새 승인·B 새 PENDING | A만 교체, B의 기존 승인본 유지 |
| 과거 월 포함 전체 승인 / 부분 포함 | 전체이면 과거 월 새 버전 / 부분이면 제출 차단 |
| 동일 승인 재시도 / 소명만 보완 | master 중복 발행 없음 / 숫자 master 변경 없음 |
| 과거 master 또는 보고 재조회 | 고정 source·계산 버전의 숫자와 수용 소명 사용 |
| 연간 확정과 늦은 새 계획 제출 경합 | 계획 잠금과 고정 source가 일관되며 확정 후 제출 차단 |

## 13. 구현 전에 확정할 항목

| 항목 | 현재 제안·남은 결정 |
| --- | --- |
| 인증 | 역할·조직 범위는 확정. 로그인 방식, 세션 만료, CSRF·인증 challenge는 미정 |
| 운영 한도 | 초기 한도: 원본16MiB·출력64MiB·해제64MiB·5만셀·90초·조직당 대기/실행10건. 나머지 상세 제한은 bridge 설정. 운영 확장·보관 기간은 미정 |
| 멱등·토큰 | 접수 명령에 사용자·메서드·경로·키 기반24시간 영속 멱등과 같은 DB 트랜잭션 적용. 초기 기술 테이블 stored_object/idempotency_record를 추가. 연간 확정·소명 답변/검토·기준 적용·연간/월간 승인과 영향 월 마스터 발행도 잠금·멱등 트랜잭션 사용 |
| 수치·양식 | PostgreSQL 상세 NUMERIC(30,6), 지원 양식의 연간/월간 전체 범위와 의미·금액 다중집합 비교 구현. 새 양식·추가 의미 속성의 선택 규칙은 별도 확인 |
| 마감·조직 변경 | 자동 차단·리마인드, 기간 중 조직 신설·폐지 정책 |
| Contingency 표시 | 연간 확정값은 고정. 월별/누적 실적 보고에 적용할 표시·배분 정책 |
| 내보내기 | PDF/XLSX/이미지 등 지원 포맷과 클라이언트·서버 생성 책임 |
| 파서 실행 | 내부 프로세스·DB 작업 큐로 초기 구현. 컨테이너 자원 제한·운영 모니터링은 후속 설정 |
| 계약 구현 | 39개 중 36개 경로 구현. COM-03 보고 항목 목록, REQ-04 요청 수정, REP-01 보고 데이터 구성과 OpenAPI 산출물은 후속 작업 |

HTTP 메서드·상태 의미는 [RFC 9110](https://www.rfc-editor.org/rfc/rfc9110.html), 오류 표현은 [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457.html), 파일 전송 형식은 [RFC 7578](https://www.rfc-editor.org/rfc/rfc7578.html)을 참고한다. 경로·오류 code·멱등키·stateToken은 이 프로젝트가 제안한 계약이다.
