# 경영팀을 부탁해 · API 명세 (초안)

| 항목 | 내용 |
| --- | --- |
| 작성일 | 2026-11-23 |
| 기준 | PRD ver1 기능 요구사항(유효 77개, 취소선 제외) · 물리 ERD 설계안([`docs/erd/physical-erd.dbml`](../erd/physical-erd.dbml)) · 인프라 구성([`docs/infrastructure.md`](../infrastructure.md)) |
| 대상 | 브라우저(frontend) → backend 공개 API. backend ↔ report-ai 내부 호출은 마지막 절에 따로 정리 |
| 상세 명세 | [`openapi.yaml`](openapi.yaml)(OpenAPI 3.1) — API별 요청 헤더·경로/쿼리 변수·요청 바디·성공 응답·오류 응답·예시. 이 문서는 개요·FR 대응·정할 것만 다룸 |
| 보는 법 | 저장소 루트에서 `npx @redocly/cli@1.25.11 preview-docs docs/api/openapi.yaml`(브라우저 미리보기) 또는 `build-docs`로 HTML 생성. 생성한 HTML은 커밋하지 않음 |

## 0. 공통 규칙

| 항목 | 규칙 |
| --- | --- |
| Base URL | `/api/v1` (Ingress `/api/*` → backend) |
| 인증 | 이메일 인증코드 로그인 후 받은 세션 토큰을 `Authorization: Bearer <token>`으로 보냄. 인증코드는 메모리 보관(DB 테이블 없음) |
| 역할 | `PLANNER` 경영기획팀 · `ORG_MANAGER` 조직 담당자. 조직 담당자는 자기 조직 데이터만 조회·수정(FR-MENU-002). 표의 권한 열 P=PLANNER, O=ORG_MANAGER |
| 금액 | 원 단위 정밀 십진수를 **문자열**로 주고받음(예: `"1240000000"`). 화면 단위(천원·백만원·억원) 변환은 `unit` 쿼리로 요청하거나 프론트에서 처리. 빈 값·계산 불가는 `null` (0으로 바꾸지 않음) |
| 비율 | 기준값이 0이면 `rate: null`, `rateLabel: "산정 불가"` |
| 시각 | ISO-8601, UTC 저장·KST 표시 (`2026-11-12T09:00:00Z`) |
| 목록 | `?cursor=&limit=` (기본 50, 최대 100). 응답 `{ items: [...], nextCursor }` — 0-2 참고 |
| 상태 변경 명령 | `Idempotency-Key` 헤더와 `expectedStateToken` 본문 필드 — 0-1, 0-2 참고 |
| 오류 형식 | RFC 9457 Problem Details(`application/problem+json`) + `code`·`requestId`·`errors` — 0-3 참고 |
| 파일 | 업로드는 `multipart/form-data`로 backend에 보내고, 형식·크기·조직 검사 후 현재는 로컬 디스크/PVC에 저장(`stored_object`에는 위치·크기·해시). 제출 트랜잭션에서 초안 원본·첨부를 보존 위치로 복사하고, 다운로드는 backend가 권한 확인 후 전달. S3 전환·초안 7일 자동 삭제는 2차 목표이며 현재 자동 정리는 구현하지 않음 |
| 버전 표기 | 조직 제출본 `YYYY-MM-NN`(제출 회차 `seq`) · 승인 버전 `ver{월}.{NN}`(`approved_seq`) · 연간 `YYYY-PLAN-NN` · 월간 master `master_ver{월}.{NN}` · 연간 master `master_plan{연도}.{NN}` — 모두 서버가 계산해 `label`로 내려줌 |

### 0-1. 공통 요청 헤더

| 헤더 | 필수 | 값 | 설명 |
| --- | --- | --- | --- |
| `Authorization` | 로그인·공개 API 외 전부 | `Bearer <token>` | AUTH-02에서 받은 세션 토큰. 없거나 만료되면 `401 UNAUTHENTICATED` |
| `Content-Type` | 본문이 있는 요청 | `application/json`, 파일 업로드는 `multipart/form-data` | 파일은 multipart의 `file` 필드로 보냄 |
| `Accept` | 선택 | `application/json` | 파일 다운로드 API는 파일 스트림(`200`) |
| `Idempotency-Key` | POST·PUT·PATCH·DELETE 명령 전부 | 클라이언트가 만든 고유 문자열(UUID 권장, 최대 128자) | 같은 사용자·경로·키로 다시 보내면 첫 성공 응답을 그대로 돌려줌(24시간 보관). 같은 키에 다른 본문이면 `409 IDEMPOTENCY_KEY_REUSED`, 첫 요청이 처리 중이면 `409 REQUEST_IN_PROGRESS` |
| `X-Request-ID` | 선택 | 클라이언트 추적 ID | 보내면 그대로 쓰고, 없으면 서버가 만들어 응답 헤더로 돌려줌 |

### 0-2. 공통 성공 응답

**본문**: 감싸는 봉투(`{ success, data }` 같은 것) 없이 리소스 JSON을 그대로 내려준다.

| 상황 | 상태 코드 | 본문 | 헤더 |
| --- | --- | --- | --- |
| 조회 | `200 OK` | 리소스 객체 또는 목록 | — |
| 생성 | `201 Created` | 만든 리소스 | `Location: /api/v1/...` |
| 비동기 작업 접수(파싱·PDF 분석) | `202 Accepted` | 작업 상태 `{ "id", "status": "QUEUED" }` | `Location`: 상태 조회 경로 |
| 수정·명령 | `200 OK` | 바뀐 리소스 | — |
| 삭제·로그아웃 | `204 No Content` | 없음 | — |
| 파일 다운로드 | `200 OK` | 파일 바이트 | `Content-Type`(xlsx·pdf 등), `Content-Disposition: attachment; filename*=UTF-8''...` |
| 멱등 재전송 | 첫 응답과 같음 | 첫 응답과 같음 | `Idempotency-Replayed: true` |

모든 응답에 붙는 헤더: `X-Request-ID`, `Cache-Control: private, no-store`

**목록 응답**

```json
{
  "items": [ { "id": "12", "...": "..." } ],
  "nextCursor": "eyJvIjo1MH0"
}
```

- 다음 페이지는 `?cursor=<nextCursor>`로 요청. 마지막 페이지면 `nextCursor: null`
- `limit` 기본 50, 1~100. 범위 밖이면 `400 INVALID_REQUEST`, 잘못된 커서는 `400 INVALID_CURSOR`

**리소스 공통 필드**

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `id` | string | DB ID를 문자열로 내려줌(JS 숫자 정밀도 문제 방지) |
| `stateToken` | string | 현재 상태 지문. 수정 명령의 `expectedStateToken`에 그대로 넣음 |
| `label` | string | 화면 표시용 버전 이름(`2026-11-01`, `ver10.01`, `master_ver10.01`) |
| `*At` | string | ISO-8601 UTC 시각 |
| 금액 | string \| null | 원 단위 십진수 문자열, 값이 없으면 `null` |

### 0-3. 공통 오류 응답

**형식** (`Content-Type: application/problem+json`)

```json
{
  "type": "about:blank",
  "title": "Conflict",
  "status": 409,
  "detail": "다른 사용자가 먼저 변경했습니다. 다시 조회해 주세요.",
  "instance": "/api/v1/submissions/12/submit",
  "code": "RESOURCE_CHANGED",
  "requestId": "8f3c2a1e-...",
  "errors": [ { "field": "dueAt", "code": "INVALID_VALUE" } ]
}
```

| 필드 | 설명 |
| --- | --- |
| `status` | HTTP 상태 코드 |
| `detail` | 화면에 그대로 보여 줄 수 있는 한국어 설명. 내부 경로·스택·원본 예외 문구는 넣지 않음 |
| `instance` | 오류가 난 요청 경로(쿼리 제외) |
| `code` | 프론트가 분기할 오류 코드(대문자·밑줄). **화면 분기는 `detail`이 아니라 `code`로 함** |
| `requestId` | 서버 로그 추적용. 문의할 때 이 값을 전달 |
| `errors` | 입력 필드 오류 목록(`field`, `code`). 없으면 빈 배열 |

**공통 오류 코드** (모든 API에 생길 수 있어 API별 표에는 다시 적지 않음)

| 상태 | code | 언제 | 프론트 처리 |
| --- | --- | --- | --- |
| 400 | `INVALID_REQUEST` | 필수 값 누락, 형식·범위 오류, 알 수 없는 쿼리 | `errors`의 필드에 표시 |
| 400 | `INVALID_CURSOR` | 잘못되거나 다른 조건의 커서 | 첫 페이지부터 다시 조회 |
| 401 | `UNAUTHENTICATED` | 토큰 없음·만료 | 로그인 화면으로 이동 |
| 403 | `FORBIDDEN` | 역할이 맞지 않거나 다른 조직의 데이터 | "권한이 없습니다" 안내 |
| 404 | `RESOURCE_NOT_FOUND` | 경로의 리소스가 없거나 볼 수 없음 | 목록으로 돌아가기 |
| 405 | `METHOD_NOT_ALLOWED` | 지원하지 않는 메서드 | 개발 오류 |
| 409 | `RESOURCE_CHANGED` | `expectedStateToken`이 현재 상태와 다름 | 다시 조회한 뒤 재시도 |
| 409 | `IDEMPOTENCY_KEY_REUSED` | 같은 `Idempotency-Key`를 다른 본문에 재사용 | 새 키로 요청 |
| 409 | `REQUEST_IN_PROGRESS` | 같은 키의 첫 요청이 처리 중(`Retry-After: 1`) | 1초 뒤 같은 키로 재전송 |
| 413 | `FILE_TOO_LARGE` | 업로드 한도 초과 | 파일 크기 안내 |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | 지원하지 않는 본문 형식 | 개발 오류 |
| 429 | `RATE_LIMITED` | 요청이 너무 많음 | 잠시 후 재시도 |
| 500 | `INTERNAL_ERROR` | 예상하지 못한 서버 오류 | "잠시 후 다시 시도" + `requestId` 표시 |
| 503 | `PROCESSING_UNAVAILABLE` | 파서·AI 등 처리 서비스 일시 불가 | 잠시 후 재시도 |

**상태 코드 사용 원칙**

| 상태 | 쓰는 경우 | 예 |
| --- | --- | --- |
| 400 | 요청 자체가 잘못됨(형식·필수 값) | 날짜 형식 오류 |
| 401 / 403 | 인증 없음 / 권한 없음 | 조직 담당자가 다른 조직 제출본 조회 |
| 404 | 리소스 없음 | 없는 제출본 ID |
| 409 | 지금 상태에서는 할 수 없음(다시 조회하면 해결될 수 있음) | 이미 마감된 요청, 검토 대기 중인 제출본이 이미 있음 |
| 422 | 형식은 맞지만 업무 규칙 위반(사용자가 내용을 고쳐야 함) | 파일 이름·조직 불일치, 미작성 소명 |

API별 업무 오류 코드(`REQUEST_ALREADY_EXISTS`, `PENDING_ALREADY_EXISTS`, `ANSWER_REQUIRED` 등)는 `openapi.yaml`의 각 API 오류 응답에 적는다.

표 열: **ID · 메서드 · 경로 · 설명 · 주요 요청 · 주요 응답 · 권한 · FR · 관련 테이블**

## 1. 로그인·사용자 (FR-AUTH, FR-MENU)

| ID | 메서드 | 경로 | 설명 | 주요 요청 | 주요 응답 | 권한 | FR | 관련 테이블 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| AUTH-01 | POST | `/auth/codes` | 인증 메일 요청·재전송. 등록된 사내 도메인·계정만 허용, 재전송은 1분 후 | `email` | `202` `expiresAt`(3분), `resendAvailableAt` · 오류 `UNREGISTERED_EMAIL`, `CODE_RESEND_WAIT`(429) | 공개 | AUTH-001, 002 | app_user |
| AUTH-02 | POST | `/auth/sessions` | 인증코드로 로그인 | `email`, `code` | `token`, `expiresAt`, `me{user,role,organization,homePath,menus}` · 오류 `INVALID_AUTH_CODE`, 상세 `errors[].code`=MISMATCH·EXPIRED·TOO_MANY_ATTEMPTS | 공개 | AUTH-002, 003, 004 | app_user |
| AUTH-03 | DELETE | `/auth/session` | 로그아웃(세션 종료) | — | `204` | P·O | AUTH-007 | — |
| AUTH-04 | GET | `/me` | 로그인 사용자·역할·조직. 메뉴 구성과 조직 고정에 사용 | — | `user`, `role`, `organization{id,code,name}`(O만), `menus[]` | P·O | AUTH-004, MENU-001, 002 | app_user, organization |
| AUTH-05 | GET | `/me/badges` | 메뉴 배지 건수. 0이면 프론트에서 숨김 | — | P: `unreadNotifications`, `monthlyReviewNeeded`, `annualReviewNeeded` · O: `openRequests` | P·O | MENU-004 | notification, review_item, submission, request_target |

## 2. 알림 모아보기 (FR-NOTI-A) — 경영기획팀

| ID | 메서드 | 경로 | 설명 | 주요 요청 | 주요 응답 | 권한 | FR | 관련 테이블 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| NOTI-01 | GET | `/notifications` | 알림 목록(날짜 묶음) + 요약 지표 + 유형별 건수 | `type`=ALL·SUBMISSION·REPLY·OVERDUE, `unreadOnly`, `sort`=LATEST | `summary{unreadCount, replyPendingCount, overdueOrganizationCount}`, `typeCounts`, `groups[{date, items[{id,type,title,organization,actor,occurredAt,read,link{path,query}}]}]` | P | NOTI-A-001, 002, 003, 005 | notification |
| NOTI-02 | POST | `/notifications/read` | 읽음 처리. 모두 읽음 또는 선택 | `ids[]` 또는 `all: true` | `unreadCount` | P | NOTI-A-004 | notification.read_at |

`link`는 [검토하기]·[현황 보기]로 이동할 화면 경로와 선택할 요청·조직·항목 ID를 담는다(FR-NOTI-A-005). 수락·재요청은 이동한 화면의 API로 한다.

## 3. 홈 · 전사 손익 현황 (FR-HOME) — 경영기획팀

공통 쿼리: `period`=MONTH·YEAR(FR-HOME-004), `year`, `month`, `masterId`(이전 master 선택 시, 없으면 최신), `scope`=COMPANY, `unit`.

| ID | 메서드 | 경로 | 설명 | 주요 요청 | 주요 응답 | 권한 | FR | 관련 테이블 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| HOME-01 | GET | `/home/status` | 데이터 상태와 마감 진행 막대 | `year`, `month` | `dataStatus`=CONFIRMED·PROVISIONAL·NONE, `master{id,label}`, `masters[]`(이전 master 선택용), `dueAt`, `dDay`, `steps[{key: REQUESTED·SUBMITTED·GAP_REVIEW·EXPLAINED·MASTER, done, total, date}]` | P | HOME-001, 002 | master_version, submission_request, request_target, submission, review_item |
| HOME-02 | GET | `/home/todos` | 오늘 할 일(연간/월간 탭) | `tab`=ANNUAL·MONTHLY, `year`, `month` | `items[{key, label, count, link}]` (요청 필요·미제출·검토 필요 조직·소명 검토 필요 조직) | P | HOME-003 | submission_request, request_target, submission, review_item |
| HOME-03 | GET | `/home/pnl` | 핵심 KPI 3개 + 전사 손익 요약표(답안지 행 순서) | 공통 쿼리 | `dataStatus`, `kpis[{key: REVENUE·COST·OP, actual, vsPlanRate, vsPlanAmount}]`, `rows[{reportItemId, kind: ITEM·OP, name, level, actual, vsPlanRate, vsPlanAmount, vsPrevEstimateRate, vsPrevEstimateAmount, gapExceeded, explanation?}]`, `opMargin{actualRate, planRate, vsPlanPoint, prevEstimateRate, vsPrevEstimatePoint}` | P | HOME-001, 004, 005, 006 | master_source → financial_line/value, report_item, gap_comparison, review_explanation |
| HOME-04 | GET | `/home/op-drivers` | 조직별 영업이익 변동 원인 Top 5 (계획 대비) | 공통 쿼리, `limit`=5 | `dataStatus`, `items[{organization, opDiff, opDiffRate}]`(차이 절댓값 큰 순), `total` | P | HOME-007 | master_source, financial_value, annual_master_source |
| HOME-05 | GET | `/home/divisions` | 사업부별 손익 표(사업부 = 제출 조직 17개) | 공통 쿼리 | `dataStatus`, `columns[{organization, revenue, op, opContributionRate, opMarginRate, vsPlanAmount}]`(조직 코드 순), `total{revenue, op, opMarginRate, vsPlanAmount}` | P | HOME-008 | master_source, financial_value |
| HOME-06 | GET | `/home/waterfall` | 손익 흐름(워터폴) 단계 금액 | 공통 쿼리 | `dataStatus`, `steps[{key: REVENUE·COST·OP, reportItemId, name, amount, kind: TOTAL·DEDUCTION, breakdown[{reportItemId, name, amount}]}]` | P | HOME-009 | report_item, financial_value |

`dataStatus`: 그 달 master가 있으면 CONFIRMED, 아직 모든 조직이 승인되지 않아 master가 없으면 승인된 제출본만으로 계산한 PROVISIONAL, 승인본이 하나도 없으면 NONE. 잠정 값은 저장하지 않는다.

HOME-03~06 계산 기준:

- 실적: 그 달 master(`masterId`가 없으면 최신 master)의 조직별 승인본. master가 없으면 조직별 최신 승인본(잠정)
- 계획: 월간 요청을 보낼 때 고정한 확정 연간 계획(`request_target.confirmed_plan_submission_id`)의 계획 값. 월간 시트 안의 계획 열은 쓰지 않는다
- 전월 추정: 전월 요청의 최신 master(없으면 최신 승인본)에 적힌 이번 달 추정 값. 1월은 없음(null)
- `period=YEAR`: 연간 전체. 실적 = 고른 달 시트의 1월~그 달 실적 + 다음 달~12월 추정, 계획 = 1~12월 계획, 전월 대비 = 전월 시트의 같은 연간 전망. `month`가 없으면 승인본이 있는 가장 최근 달. `period=MONTH`에서 `month`가 없으면 이번 달(KST)
- 매출 = REVENUE 항목 합, 영업비용 = COST 항목 합, 영업이익 = 매출 − 영업비용. 영업이익률 = 영업이익 / 매출 × 100은 금액이 아니라서 `opMargin`으로 따로 내려준다
- `gapExceeded`·`explanation`: 승인본 항목 중 Gap 기준을 넘은 실적 항목, 그 항목의 마지막 수락 소명("조직명: 소명", 여러 조직이면 줄바꿈)
- HOME-06: 답안지에 매출원가·매출총이익·영업외손익이 없어 매출(TOTAL) → 영업비용 중계정(DEDUCTION) → 영업이익(TOTAL) 순으로 내려준다. `breakdown`은 그 단계의 하위 항목

## 4. 연간 계획 관리 · 월간 시트 요청 (FR-YEAR, FR-MONTH-001~003) — 경영기획팀

| ID | 메서드 | 경로 | 설명 | 주요 요청 | 주요 응답 | 권한 | FR | 관련 테이블 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| REQ-01 | GET | `/organizations` | 요청 대상 조직 목록(체크박스 표) | `active=true`, `q` | `items[{id, code, name, managers[{name, email}]}]` | P | YEAR-001, MONTH-001 | organization, app_user |
| REQ-02 | POST | `/submission-requests` | 연간 계획·월간 시트 요청 일괄 발송 | `type`=ANNUAL_PLAN·MONTHLY_ACTUAL, `year`, `month`(월간만), `dueAt`, `message`, `organizationIds[]`(기본 전체) | `201` `request{id, type, year, month, status: OPEN}` · 오류 `REQUEST_ALREADY_EXISTS` | P | YEAR-001, MONTH-001 | submission_request, request_target, notification |
| REQ-03 | GET | `/submission-requests` | 요청 목록(진행 중인 요청) | `type`, `year`, `status` | `items[{id, type, year, month, requestedAt, dueAt, status, submittedCount, targetCount}]` | P | YEAR-002, MONTH-002 | submission_request, request_target, submission |
| REQ-03A | POST | `/submission-requests/{requestId}/targets` | 진행 중인 월간 요청의 미요청 조직에 추가 발송 | `organizationIds[]`, `dueAt`, `message` | 요청 요약 · 기존 제출·기한 유지, 추가 조직별 발송 시각·기한·메시지 저장. 이미 발송한 조직이 섞이면 전체 취소(`REQUEST_TARGET_ALREADY_SELECTED`) | P | MONTH-001 | request_target.dispatch_* |
| REQ-04 | GET | `/submission-requests/{requestId}` | 조직별 진행 현황 표 | — | `request`, `targets[{organization, selected, requestedAt(=조직별 요청 발송일), dueAt, displayStatus: NOT_REQUESTED·SUBMITTED·OVERDUE·WAITING, latestSubmission{id, status, label}}]` · 미요청 조직의 발송일·기한은 null | P | YEAR-002, MONTH-002 | request_target, submission |
| REQ-05 | GET | `/submissions` | 조직 행을 눌렀을 때 회신 이력 카드 | `requestId`, `organizationId` | `items[{id, label, status, submittedBy, submittedAt, replyMessage, file{name, url}, reviewComment, reviewedAt}]` | P | YEAR-003, MONTH-003 | submission |
| REQ-06 | POST | `/submissions/{submissionId}/decisions` | 회신 수락 또는 재제출 요청(재요청 시 코멘트 필수). 전 조직 수락 시 연간·월간 master 자동 생성 | `decision`=APPROVE·REJECT, `comment`(REJECT 필수), `rejectedReviewItemIds[]`(반려 항목), `expectedStateToken` | `submission{status, approvedLabel}`, `masterPublished{id, label}?` · 오류 `EXPLANATIONS_NOT_ACCEPTED`, `COMMENT_REQUIRED` | P | YEAR-003, MONTH-003, HIST-004 | submission.reject_reason·approved_seq, review_item.rejected, master_version, annual_master |
| REQ-07 | GET | `/submissions/{submissionId}/file` | 회신 첨부(제출 엑셀) 다운로드 | — | 파일 스트림 | P·O | YEAR-003, MONTH-003, ARCH-002 | stored_object |

## 5. 월간 시트 관리 · Gap 검토 (FR-MONTH-004~012) — 경영기획팀

| ID | 메서드 | 경로 | 설명 | 주요 요청 | 주요 응답 | 권한 | FR | 관련 테이블 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| GAP-01 | GET | `/submissions/{submissionId}/comparison` | 기준 대상·비교 대상 버전으로 Gap 계산 결과 | `baseSubmissionId`(기본: 현재 기준 버전; 금액은 원 단위, 조직은 제출본으로 결정) | `base{label}`, `target{label}`, `rows[...]` | P | MONTH-004 | submission, financial_value |
| GAP-02 | GET | `/submissions/{submissionId}/comparison/file` | Gap 계산 결과 엑셀 다운로드 | GAP-01과 같음 | xlsx 스트림 | P | MONTH-004 | — |
| GAP-03 | GET | `/gap-thresholds/current` | 현재 Gap 기준(비교 3종) | — | `items[{comparison: ACTUAL_VS_PLAN·ESTIMATE_VS_PREVIOUS·ESTIMATE_VS_PLAN, percentEnabled, percent, amountEnabled, amount}]` | P | MONTH-005 | gap_threshold |
| GAP-04 | POST | `/gap-thresholds/preview` | 기준 적용 전 영향 건수 미리보기 | `comparison`, `percentEnabled`, `percent`, `amountEnabled`, `amount` | `affectedCount` | P | MONTH-005 | gap_threshold, review_item |
| GAP-05 | POST | `/gap-thresholds` | 기준 적용(새 행 생성, 이후 제출부터 적용) | GAP-04와 같음 + `expectedStateToken` | `threshold{id, appliedAt}` | P | MONTH-005 | gap_threshold, gap_threshold_guard |
| GAP-06 | GET | `/review-items` | Gap 계층형 표(대/중/소/상세)와 상태 탭 건수 | `requestId`, `organizationId`, `comparison`, `tab`=NEEDS_REVIEW·SELECTED·IN_PROGRESS·RE_REVIEW·ACCEPTED·EXCLUDED·ALL, `sort`=DIFF·RATE, `order`, `q`, `unit` | `tabCounts`, `tree[{reportItem, level, plan, actual, diff, rate, stage(1~5), reviewItemId?, children[]}]` | P | MONTH-006 | review_item, report_item, gap_comparison, review_explanation |
| GAP-07 | POST | `/review-items/actions` | 선택 항목 처리: 제외 / 선택 항목 검토하기 / 일괄 소명 요청 | `action`=EXCLUDE·SELECT·REQUEST_EXPLANATION, `items[{reviewItemId, expectedStateToken}]`, `note`(제외 사유), `dueAt`·`message`(소명 요청) | `items[{reviewItemId, workflowState}]`, `tabCounts` | P | MONTH-007, 009, 010, 012 | review_item.workflow_state, review_workflow_event, review_explanation, notification |
| GAP-08 | GET | `/review-items/{reviewItemId}` | Gap 상세: 선택 항목 요약 + 버전별 소명 이력·담당자 메모 | — | `summary{path, plan, actual, diff, rate, threshold}`, `explanations[{round, requestNote, requestedBy, dueAt, answer, answeredBy, verdict, reviewNote, attachments[], inheritedFrom?, approvedLabel?}]` | P·O | MONTH-009, 011 | review_item, review_explanation, review_attachment |
| GAP-09 | GET | `/review-items/{reviewItemId}/evidence` | 답안지 셀에 걸린 원천 셀 목록(셀 오름차순) | — | `items[{sourceSheet, sourceCell, account, description(거래내역·산출근거), amount}]` | P·O | MONTH-009 | financial_line, financial_value, account_item |
| GAP-10 | GET | `/review-items/{reviewItemId}/versions` | 같은 셀을 버전별 한 줄 비교(확정 v1.01 · 현재 · 신규 업로드) | — | `columns[{submissionId, label, state: CONFIRMED·CURRENT·NEW, approvedAt}]`, `rows[{cell, values[], change, sourceAmount, basis, uploadExplanation}]` | P | MONTH-010 | submission, financial_line, financial_value, review_explanation |
| GAP-11 | POST | `/review-items/{reviewItemId}/decisions` | 소명 수락 / 재요청 | `verdict`=ACCEPT·REQUEST_AGAIN, `note`(재요청 시 필수), `dueAt`, `expectedStateToken` | `explanation{round, verdict}`, `workflowState` | P | MONTH-011, YEAR-003 | review_explanation |

## 6. 조직별 현황 · 아카이브 (FR-ARCH) — 경영기획팀

| ID | 메서드 | 경로 | 설명 | 주요 요청 | 주요 응답 | 권한 | FR | 관련 테이블 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| ARCH-01 | GET | `/organizations` | 조직 검색·선택 (REQ-01과 같음) | `q` | 조직 목록 | P | ARCH-001 | organization |
| ARCH-02 | GET | `/organizations/{organizationId}/versions` | 월별 최종 버전, 월을 고르면 그 달 버전 이력 | `year`, `month`(선택) | `months[{month, finalLabel}]`, `history[{submissionId, label, status, lastModifiedAt, file{name, url}}]` | P | ARCH-002 | submission |
| ARCH-03 | GET | `/submissions/{submissionId}/change-history` | 버전의 변경 이력(변경 일자별 항목·소명 사유, 소명/재소명) | — | `dates[{changedAt, state: EXPLAINED·RE_EXPLAINED, items[{no, code, name, month, scenario, explanation}]}]` | P | ARCH-003 | review_item, review_explanation |
| ARCH-04 | GET | `/masters` | 월별 master 목록과 상태 | `year` | `items[{id, month, label, state: WAITING·CONFIRMED, createdAt}]` | P | ARCH-004 | master_version |
| ARCH-05 | GET | `/masters/{masterId}/file` | master 엑셀 다운로드 | — | xlsx 스트림 | P | ARCH-004 | master_source |

## 7. 요청함 (FR-NOTI-U) — 조직 담당자

| ID | 메서드 | 경로 | 설명 | 주요 요청 | 주요 응답 | 권한 | FR | 관련 테이블 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| INBOX-01 | GET | `/inbox` | 내 조직의 열린 요청 카드(제출 요청·소명 요청) | — | `organization`, `openCount`, `items[{kind: SUBMISSION·EXPLANATION, title, requestId, submissionId, draftSubmissionId, dueAt, action: START_SUBMISSION·START_EXPLANATION, link}]` — `draftSubmissionId`는 마지막 제출본 이후 업로드한 최신 초안(없으면 null), `submissionId`는 재제출 요청·소명 대상 제출본 | O | NOTI-U-001, 002 | submission_request, request_target, submission, review_explanation |

## 8. 엑셀 업로드·검증 (FR-UPLOAD) — 조직 담당자

| ID | 메서드 | 경로 | 설명 | 주요 요청 | 주요 응답 | 권한 | FR | 관련 테이블 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| UP-01 | GET | `/submission-template/file` | 제출 양식 다운로드(연간·월간 공통 파일 하나) | — | 파일 스트림 · 파일이 없으면 404 `TEMPLATE_NOT_FOUND` | P·O | UPLOAD-002 | — |
| UP-03 | POST | `/submission-requests/{requestId}/submissions` | 엑셀 업로드 → 파일 규칙 확인 → 로컬/PVC 초안 저장 → 제출 초안 생성 → 프로세스 내 파싱 작업 등록 | multipart `file`(.xlsx·.xlsb, 요청당 1개) | `202` `submission{id, status: DRAFT, kind: ANNUAL_PLAN·MONTHLY_ACTUAL, title}`, `parsingJob{status: QUEUED}` · 오류 `INVALID_FILENAME`(422), `ORGANIZATION_MISMATCH`, `UNSUPPORTED_FILE_TYPE`(415), `FILE_TOO_LARGE`(413) | O | UPLOAD-001, 003, 004, 005 | submission, stored_object, parsing_job |
| UP-04 | GET | `/submissions/{submissionId}` | 제출본 상태 조회. 파싱 진행 중에는 폴링 | — | `submission{status, label, ...}`, `parsingJob{status: QUEUED·RUNNING·SUCCEEDED·FAILED}`, `validation{canSubmit}` | P·O | UPLOAD-005 | submission, parsing_job |
| UP-05 | GET | `/submissions/{submissionId}/diagnostics` | 검증 결과 · 수정 필요 표 | — | `errorCount`, `items[{sourceCell, item, rule, guide}]` (예: 손익제출!N40 · 설비 임차료 · 금액 누락) | O | UPLOAD-006 | parsing_job, submission_validation |
| UP-06 | POST | `/submissions/{submissionId}/parsing-retries` | 같은 파일로 파싱 재시도 | `expectedStateToken` | `parsingJob{status: QUEUED, attemptNo}` | O | UPLOAD-005 | parsing_job |

'파일 다시 선택'은 UP-03으로 새 DRAFT를 만든다. 이전 DRAFT의 파싱·소명은 섞지 않는다.

## 9. 변경 확인·소명·제출 (FR-PROOF, FR-RESUB) — 조직 담당자

| ID | 메서드 | 경로 | 설명 | 주요 요청 | 주요 응답 | 권한 | FR | 관련 테이블 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| PROOF-01 | POST | `/submissions/{submissionId}/validation` | 비교 기준 대비 변경 감지·소명 대상 생성 | `expectedStateToken` | `baseline{submissionId, label, kind: PREV_MONTH_APPROVED·REJECTED·NONE}`, `progress{answered, required}`, `canSubmit` · 연간 계획 첫 제출은 `kind: NONE` | O | PROOF-001, 007, RESUB-005 | review_item, gap_comparison, submission_validation |
| PROOF-02 | GET | `/review-items` | 소명 대상 목록(매출/비용 → 대상 월 실적/추정 → 항목). Gap 기준은 내려주지 않음 | `submissionId`, `view=ORG` | `tree[{group, month, scenario, items[{reviewItemId, name, sourceCell, baseline, current, previous?, rate, rateLabel, answerState: EMPTY·WRITTEN}]}]` | O | PROOF-002, RESUB-005 | review_item, financial_value |
| PROOF-03 | GET | `/review-items/{reviewItemId}` | 변경 내역 상세 + 지난 소명 사유(승인 배지) (GAP-08과 같은 경로, 조직 보기) | — | `rows[{sourceCell, baseline, previous?, current, diff, rate}]`, `previousExplanation{answer, verdict: ACCEPTED, label}?`, `requestNote?`(재제출 요청 메시지) | O | PROOF-003, 004, RESUB-005 | review_item, review_explanation |
| PROOF-04 | PUT | `/review-items/{reviewItemId}/answer` | 이번 소명 작성·수정(제출 전까지) | `answer`, `expectedStateToken` | `answerState: WRITTEN`, `progress` | O | PROOF-005 | review_explanation |
| PROOF-05 | POST | `/review-items/{reviewItemId}/attachments` | 소명 파일 첨부(검사 후 로컬/PVC 초안 저장, 제출 시 보존) | multipart `file`(1~16MiB), `expectedStateToken` | `attachment{id, name, size}` | O | PROOF-005 | review_attachment, stored_object |
| PROOF-06 | GET | `/review-items/{reviewItemId}/attachments` · `/attachments/{attachmentId}/file` | 첨부 목록·다운로드 | — | 목록 · 파일 스트림 | P·O | PROOF-005, MONTH-011 | review_attachment |
| PROOF-07 | GET | `/submissions/{submissionId}/submit-preview` | 제출 내용 확인 화면 | — | `file{name, savedAs}`, `expectedLabel`, `baseline{label}`, `items[{name, month, scenario, amount}]` | O | PROOF-008, 009, RESUB-007 | submission, review_item |
| PROOF-08 | POST | `/submissions/{submissionId}/submit` | 제출하기 → 새 제출본 '검토 대기' | `replyMessage`, `expectedStateToken` | `submission{status: PENDING, seq, label}` · 오류 `ANSWER_REQUIRED`(미작성 항목 목록), `VALIDATION_FAILED` | O | PROOF-006, 010, RESUB-007, YEAR-003 | submission.reply_message, notification |

## 10. 제출 이력·재제출 (FR-HIST, FR-RESUB-001) — 조직 담당자

| ID | 메서드 | 경로 | 설명 | 주요 요청 | 주요 응답 | 권한 | FR | 관련 테이블 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| HIST-01 | GET | `/submissions` | 제출본 목록·필터(월간/연간 탭) | `type`=MONTHLY_ACTUAL·ANNUAL_PLAN, `year`, `month`, `displayStatus`=PENDING·APPROVED·RESUBMIT_REQUESTED·SUPERSEDED | `total`, `items[{id, requestTitle, period, submittedAt, displayStatus, label, actions}]` | O | HIST-001, 002 | submission |
| HIST-02 | GET | `/submissions/{submissionId}` | 제출본 상세(검토 대기·재제출 요청·반려된 제출본) | — | `status`, `submittedAt`, `file`, `baseline`, `previousSubmission?`, `explanations[{item, month, answer}]`, `inheritedExplanations[]`, `rejectComment?`, `rejectedItems[{reviewItemId, sourceCell, reason, previousExplanation}]`, `nextSubmission?`, `history[{event: SUBMITTED·RESUBMIT_REQUESTED·RESUBMITTED, actor, at}]` | O | HIST-003, 004, RESUB-001 | submission, review_item.rejected, review_explanation |

재제출 흐름(FR-RESUB-001, 005, 007)은 새 API 없이 UP-01~06 → PROOF-01~08을 그대로 쓴다. 비교 기준이 반려된 제출본이면 PROOF-02·03 응답에 `previous`(직전 제출값)가 함께 온다.

## 11. AI 보고서 만들기 (FR-RPT) — 경영기획팀

보고서 결과물은 DB에 저장하지 않는다. 저장 대상은 자료 틀(버전), 지표 사전, 소명 분류뿐이다.

| ID | 메서드 | 경로 | 설명 | 주요 요청 | 주요 응답 | 권한 | FR | 관련 테이블 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| RPT-01 | POST | `/report/pdf-jobs` | PDF 업로드 → 저장 → report-ai에 분석 요청(비전 LLM, 12절. 1차 HTTP, 2차 RabbitMQ `pdf.jobs`) | multipart `file`(.pdf), `maskNumbers`(기본 true) | `202` `job{id, status: RUNNING}` · report-ai 전달 실패 시 `503 REPORT_AI_UNAVAILABLE`(작업은 FAILED) | P | RPT-001 | pdf_job, stored_object |
| RPT-02 | GET | `/report/pdf-jobs/{jobId}` | 분석 상태·추출 블록(구성 확인용). 폴링 | — | `status`, `definition`(AI가 추출한 틀 초안, 확인 필요 블록은 `needs_review`), `pages[{no, maskedImageUrl}]`(`GET /report/pdf-jobs/{jobId}/pages/{no}` PNG, 숫자 가림), `errorCode`, `errorMessage` · 실패 시 직접 만들기 안내 | P | RPT-001, 010, 011 | pdf_job |
| RPT-03 | GET | `/report/templates` | 틀 목록(경영기획팀 공용, 최신 순) | `cursor`, `limit` | `items[{id, name, latestVersion, createdAt, updatedAt}]`, `nextCursor` | P | RPT-003 | report_template |
| RPT-04 | POST | `/report/templates` | 틀 저장(새 틀, v1). AI 추출 확정본 또는 직접 만든 틀 | `name`, `definition`(AI 틀 형식, 백엔드는 봉투 `schema_version·name·blocks[].id·kind`만 검사), `sourcePdfJobId?` | `201` `template{id, name, version: 1, latestVersion, definition}` · 오류 `INVALID_TEMPLATE`(422) | P | RPT-002, 010, 011 | report_template, report_template_version |
| RPT-05 | POST | `/report/templates/{templateId}/versions` | 틀 새 버전 저장(이전 버전 유지) | `definition`, `expectedVersion` | `201` `template{version, latestVersion, definition}` · 오류 `REPORT_REVISION_CHANGED`(409), `INVALID_TEMPLATE`(422) | P | RPT-002, 008 | report_template_version |
| RPT-06 | GET | `/report/templates/{templateId}` | 틀 불러오기(구성 확인 건너뜀) | `version`(기본 최신) | `template`, `definition` | P | RPT-003 | report_template_version |
| RPT-07 | GET | `/report/periods` | 기준 월 선택지와 월별 상태 | `year`(기본 올해) | `default{year, month}`(최근 확정 월, 없으면 최근 잠정 월), `year`, `months[{month, state: CONFIRMED·PROVISIONAL·NONE, masterId?}]` (보고서용 뷰 `dim_version` 기준) | P | RPT-004 | master_version, submission |
| RPT-08 | GET | `/report/metrics` | 지표 사전 목록 | `q` | `items[{id, code, name, description}]` | P | RPT-005 | metric |
| RPT-09 | POST | `/report/metrics` | 자주 쓰는 SQL을 지표 사전으로 등록(검사는 report-ai `/v1/sql-check`) | `code`, `name`, `description`, `sqlText` | `201` `metric{id, code, name}` · 오류 `SQL_NOT_ALLOWED`(422), `METRIC_CODE_EXISTS`(409), `REPORT_AI_UNAVAILABLE`(503) | P | RPT-006 | metric |
| RPT-10 | POST | `/report/data` | 블록 데이터 채우기(지표 또는 확정 SQL, AI 문장 없음). report-ai로 위임 | `base{year, month}`, `blocks[]`(틀의 블록 그대로, `metricId`는 backend가 지표 SQL로 바꿔 넘김) | `dataStatus`, `blocks[{id, …}]`(AI 형식, 계산할 수 없는 칸은 `missing`) | P | RPT-004, 005 | db-mcp 보고서용 뷰 |
| RPT-11 | POST | `/report/nl-query` | 자연어 질의 → SQL 생성·미리보기(text2SQL, 읽기 전용) | `question`, `baseYear`, `baseMonth` | `status: OK·CLARIFY·UNKNOWN`, `sql`, `explanation`, `preview{columns, rows}`, `clarifyQuestion`, `clarifyOptions[]` | P | RPT-006 | db-mcp 보고서용 뷰 |
| RPT-12 | GET | `/report/explanation-categories` | 소명 사유 유형 분류표 | `tableVersion`(기본 최신) | `items[{id, code, name, parentCode, catchAll, assignable}]` (상위 유형 = 하위 합, catchAll = '기타') | P | RPT-007 | explanation_category |
| RPT-13 | GET | `/report/explanations` | Gap 설명 블록용 승인 소명 카드 + 제안 분류 | `year`, `month`, `comparison` (실적 비교는 기준 월, 추정 비교는 기준 월+1 항목) | `items[{explanationId, organization, item, gapAmount, answer, categoryId, suggestedCategoryId, suggestionSource: RULE·LAST_MONTH}]`, `reviewing[]`, `gapTotal`, `categoryTotals`, `classifiedTotal`, `matchesGapTotal` | P | RPT-007 | review_explanation, explanation_classification, gap_comparison |
| RPT-14 | PUT | `/report/explanations/{explanationId}/classification` | 소명 카드를 분류함에 배치 | `categoryId`(null이면 빼기), `comparison?` | `explanationId`, `categoryId`, `categoryTotals`, `classifiedTotal`, `gapTotal`, `matchesGapTotal` · 오류 `EXPLANATION_NOT_ACCEPTED`(409), `CATEGORY_NOT_ASSIGNABLE`(400) | P | RPT-007 | explanation_classification |
| RPT-15 | POST | `/report/ai/edit` | 대화로 자료 편집 → 변경안(블록 추가·삭제·이동·글꼴). 숫자는 바꾸지 않음 | `definition`, `instruction` | `status: EDIT·CLARIFY·REFUSED·UNAVAILABLE`, `message`, `proposals[{op: add_block·remove_block·move_block·set_style·add_column, target, description, patch}]` | P | RPT-012 | — (저장 안 함) |
| RPT-16 | POST | `/report/export` | 자료 XLSX 내보내기(1개씩, 저장 안 함). 틀·기준 월로 다시 채워 숫자는 원 단위 값 그대로. PNG·PDF는 화면(브라우저)에서 만든다 | `templateId`, `version?`(기본 최신), `period{year, month}`, `blockId?`, `format`=XLSX | xlsx 스트림 · 오류 `REPORT_AI_UNAVAILABLE`(503) | P | RPT-008, 009 | — |

## 12. backend ↔ report-ai 내부 API (클러스터 내부)

report-ai app은 상태를 저장하지 않는다. 저장·권한·감사는 backend가 맡고, 프론트는 report-ai를 직접 부르지 않는다. report-ai가 백엔드를 거치지 않는 것은 보고서용 뷰 읽기(읽기 전용) 하나뿐이다.

**전송과 내용을 나눈다.** 아래 요청·응답 본문이 계약이고, 1차·2차는 전송 방식만 다르다. 2차로 바뀌어도 본문은 그대로다.

| 구분 | 1차 배포(현재: 파드 3개, PVC 저장) | 2차(목표: RabbitMQ·S3·db-mcp) |
| --- | --- | --- |
| PDF 분석 요청 | backend → report-ai `POST /v1/pdf-jobs` (multipart: `jobId`, `maskNumbers`, `file`) → `202` | RabbitMQ `pdf.jobs` 메시지 `{pdfJobId, objectKey, maskNumbers}`, report-ai가 S3 `uploads/pdf/`에서 읽음 |
| 보고서용 뷰 조회 | report-ai 앱이 읽기 전용 계정으로 직접(`AI_DATABASE_URL`, `AI_DB_SCHEMA=report`) | db-mcp 사이드카(같은 읽기 전용 역할) |
| 결과 보고·동기 호출 | 아래 HTTP 그대로 | 그대로 |

| 방향 | 경로 | 요청 | 응답 | 공개 API |
| --- | --- | --- | --- | --- |
| report-ai → backend | `POST /internal/pdf-jobs/{jobId}/result` | `status: SUCCEEDED·FAILED`, `definition`(틀 초안, `TemplateDefinition`), `pages[{no, width, height, imagePng(base64, 숫자 가림)}]`, `error{code, message}` | `204` | RPT-02 |
| backend → report-ai | `POST /v1/data` | `base{year, month}`, `blocks[]` | `dataStatus`, `blocks[{id, …}]` | RPT-10 |
| backend → report-ai | `POST /v1/nl-query` | `question`, `baseYear`, `baseMonth`, `conversationId?` | RPT-11 응답과 같은 모양(`status`, `sql`, `explanation`, `preview`, `clarifyQuestion`, `clarifyOptions`) | RPT-11 |
| backend → report-ai | `POST /v1/sql-check` | `sql` | `ok`, `sql`, `code`, `message`(실행하지 않음) | RPT-09 |
| backend → report-ai | `POST /v1/edit` | `definition`, `instruction` | RPT-15 응답과 같은 모양(`status`, `message`, `proposals[]`) | RPT-15 |
| backend → parser-worker | RabbitMQ `parse.jobs` (메시지: `parsingJobId`) | — | — | UP-03 (2차) |

- 틀 형식(`definition`)의 기준은 AI다. backend는 저장 봉투만 검사한다(`backend/src/main/resources/report/template-definition.schema.json`, AI `model.py StoredTemplate`에서 `python ai/tools/export_template_schema.py`로 생성, AI 테스트가 어긋나면 실패).
- 공통 헤더: 양방향 모두 `X-Internal-Token`(Secret `REPORT_AI_TOKEN`). 운영(backend `prod` 프로필 `app.report-ai.require-token=true`, report-ai `AI_REQUIRE_INTERNAL_TOKEN=true`)은 토큰이 설정되지 않으면 내부 보고·계약 API를 거부하고 상대를 부르지 않는다. 비어 있어도 통과하는 것은 로컬 개발(기본값 false)뿐이다. backend → report-ai는 `X-Request-Id`, `X-User-Id`(감사·호출 한도용)도 보낸다. backend `/internal/**`는 Ingress에 열지 않는다(Ingress는 `/api`만). 주소: backend `REPORT_AI_URL`(`http://ai:8000`), report-ai `AI_BACKEND_URL`(`http://backend:8080`).
- report-ai 연결 실패·시간 초과·5xx는 공개 API에서 `503 REPORT_AI_UNAVAILABLE`, report-ai의 4xx는 `400 REPORT_AI_REJECTED`. 결과 보고가 이미 끝난 작업에 다시 오면 바꾸지 않는다. 틀 초안이 저장 봉투에 맞지 않으면 작업을 `FAILED`(`INVALID_TEMPLATE`)로 둔다.
- report-ai가 키 없음·한도 초과·실패로 AI를 못 쓰면 규칙 기반으로 답하고 응답에 그 사실을 표시한다(`UNAVAILABLE` 등). 숫자는 항상 코드가 보고서용 뷰에서 계산한다.

## 13. FR 대응 확인

유효 기능 요구사항 77개 모두 위 API 중 하나 이상에 대응한다. 화면 표시만 하는 요구사항(FR-AUTH-007 팝업, FR-UPLOAD-001 제목 구분, FR-MENU-001 메뉴 구성)은 각각 AUTH-03, UP-03 응답의 `kind`, AUTH-04 응답으로 처리한다.

## 14. 정할 것

1. `/home/*` 6개를 화면 위젯별로 나눌지, 한 번에 받는 `/home/dashboard`로 합칠지
2. S3 수명 주기 규칙(`drafts/` 7일 삭제) 설정 권한을 SKALA 플랫폼이 주는지 — 안 되면 backend 예약 작업으로 정리


## 개발용 진입점과 공통 문구 (2026-11-25)

`/dev`와 `/dev/`는 프론트 SPA 진입점이며 로컬·배포 빌드에서 동일하게 사용한다. 내부 화면은 기존 hash 라우팅(`/dev#/monthly`)으로 이동한다. URL은 접근 제한 수단이 아니다.

- `GET /api/v1/dev/accounts`: 개발 도구가 켜져 있을 때 DB의 활성 사용자 전체를 `[{email,name,hint}]`로 반환한다(경영기획팀 먼저, 조직 코드·이메일 순). 헤더 `X-Dev-Access-Key`가 서버의 접근 키와 같아야 한다(다르거나 없으면 403). 로그인 세션은 필요 없다.
- `POST /api/v1/dev/sessions`: `{email}`과 헤더 `X-Dev-Access-Key`를 받아 DB의 활성 계정에 기존 AUTH 로그인 응답 `{token,expiresAt,me}`를 반환한다(201). 이메일 인증은 생략하지만 서버의 역할·소속·API 권한 검사는 유지한다. 접근 키 불일치·없는 계정·비활성 계정은 403.
- `GET /api/v1/ui-copy`: `[{key,value,revision,updatedAt}]`. 모든 사용자 화면이 읽는 공통 문구 수정값이며 비로그인 조회 가능. 원본 JSON을 기본값으로 쓰고 알려진 문자열 키만 병합한다. no-store.
- `PUT /api/v1/dev/ui-copy`: `{key,value,expectedRevision}`. 개발 도구가 켜져 있고 활성 계정으로 인증해야 한다. 최초 수정 revision=0, 이후 현재 revision을 보낸다. 성공은 수정값과 증가한 revision을 반환하고, 동시 수정은 409 RESOURCE_CHANGED, 잘못된 키/빈 값/1만 자 초과는 400이다. 서버 DB에 저장하므로 일반 화면·다른 사용자·재배포 후에도 유지된다.

`app.dev-tools.enabled` 기본 false. 꺼지면 계정 조회·개발 로그인·문구 쓰기는 404이며 공통 문구 읽기는 유지한다. 켜져 있으면 `app.dev-tools.access-key`(`APP_DEV_TOOLS_ACCESS_KEY`, `app-secrets`)와 같은 `X-Dev-Access-Key`가 있어야 계정 조회·개발 로그인이 된다. 키를 설정하지 않으면 켜도 모두 403이다. 로컬 프로필과 배포 ConfigMap(개발·시연 클러스터)은 `require-access-key: false`로 키 없이 쓴다(`docs/infrastructure.md` 4절 「개발 도구」). 사용자·조직을 자동 생성하지 않는다.

Flyway `V20261125_1800__shared_ui_copy.sql`: `ui_copy_override` 테이블. 기존 업무 테이블과 금액 데이터는 변경하지 않는다.

### 관리 목록·알림의 공통 업무 상태

`workflowStatus`는 요청·조직의 최신 제출본과 현재 소명 회차로 계산하며, 요청 상세의 조직 행과 알림에 동일하게 제공한다.

| 코드 | 표시 | 색 | 판단 기준 |
| --- | --- | --- | --- |
| REQUEST_NEEDED | 요청 필요 | 회색 | 해당 조직에 요청 미발송 |
| WAITING | 회신 대기 | 파랑 | 시트 미제출·파일 재제출 대기 또는 소명 회신 대기 |
| REVIEW_NEEDED | 검토 필요 | 주황 | 제출본 검토 또는 요청한 소명의 회신 검토 필요 |
| APPROVED | 승인 완료 | 초록 | 최신 제출본 승인 완료 |

회신을 받은 항목과 회신 대기 항목이 섞이면 경영기획팀이 처리할 수 있는 회신 검토를 우선 표시한다. 기한 경과는 회신 기한의 경고 정보로 유지한다. 읽음/안 읽음은 업무 처리 상태와 별개다. 소명 수락만으로 제출본 승인 완료가 되지 않는다.

승인 완료된 요청·조직의 제출·소명·기한 경과 알림은 목록과 배지에서 함께 제외한다. 수락한 개별 소명 회신 알림도 제외한다. 제출·소명 원본과 버전 이력은 삭제하지 않는다. 알림 화면은 30초 간격으로 현재 상태를 갱신한다.

### 월간 비교 정책 — monthly-comparison/2.0

- 원본 엑셀은 모든 월을 저장한다. 월간 review_item은 요청 연도의 당월 ACTUAL과 다음 달~12월 ESTIMATE만 생성한다. PLAN·과거 ACTUAL·당월 이전 ESTIMATE는 월간 검토 대상이 아니다. 연간 계획 검증은 기존 정책을 유지한다.
- 첫 제출: 당월 실적은 확정 연간 계획의 같은 월 PLAN 및 전월 마지막 승인본의 같은 월 ESTIMATE와 비교한다. 미래 월 추정은 전월 마지막 승인본의 같은 월 ESTIMATE와 비교한다.
- 재제출: 같은 요청·조직의 직전 제출본(반려본 포함)의 같은 월·구분과 비교한다. 당월 계획 차이나 전월 추정 차이가 재제출의 새 변경으로 잡히지 않는다. 검토 대기 중 중복 제출 제한은 유지한다.
- 검증 시 submission.review_baseline_submission_id와 review_policy_version을 저장한다. 제출 전 기준이 바뀌면 재검증해야 하며, 제출 후 조회는 저장된 기준을 사용한다. 전월 자료가 없는 첫 운영 월은 비교 가능한 계획만 사용한다. 기존 1월 운영 규칙대로 확정 연간 계획을 사용하며 미래 추정의 전월 비교는 없다.
- gap_comparison.basis=PREVIOUS인 ACTUAL도 지원한다. ACTUAL_VS_PREVIOUS 조건을 별도로 설정할 수 있으며, 아직 없으면 ACTUAL_VS_PLAN 조건을 사용한다. 미래 ESTIMATE에는 PLAN 비교를 저장하지 않는다.
- 차이율·금액 조건은 각각 >=, 켠 조건은 AND. 첫 실적의 두 비교 중 하나라도 넘으면 해당 검토 항목의 gap_exceeded=true다. 비율 판정은 표시용 반올림값을 사용하지 않는다. 누락값과 0은 구분한다.
- 계산기 조회는 호환성을 위해 ACTUAL_VS_PLAN 및 ESTIMATE_VS_PLAN으로 실적/추정 행을 함께 받는다. 네 금액 열은 참고 표시이며, 실제 조건 필터와 호버 비교는 각 상세 행의 comparisons 배열을 사용한다. 이 배열에는 label·basis·baselineSubmissionId·baselineScenario·baselineAmount·currentAmount·diff·rate를 제공한다.
- previousEstimate는 첫 제출에서 고정한 전월 파일의 같은 월 추정으로 표시한다. 재제출의 실제 기준은 comparisons의 직전 실적/추정이다.
- V20261127_1200 마이그레이션은 원본 financial 값과 소명 이력을 유지한다. 기존 파생 검토/GAP 결과를 monthly_review_policy_archive에 보존한 뒤 범위 밖 항목을 비활성화하고 기존 제출의 Gap을 새 정책에 맞춰 계산한다. 보존된 당시 조건을 우선 적용한다. 기존 초안은 재검증이 필요하다.

### 계산기 항목 승인과 4개 탭

계산기 탭은 전체 / 검토 필요(NEEDS_REVIEW·SELECTED·RE_REVIEW) / 회신 대기(IN_PROGRESS) / 승인됨(ACCEPTED)이다. 검토 필요의 상세 행은 검토 전·회신 도착으로 구분한다.
`POST /review-items/actions`의 `APPROVE`는 소명 요청 전 항목을 직접 승인한다. workflow_state=APPROVED로 저장하고 APPROVE 이력을 남긴다. 소명 수락과 직접 승인 모두 API tab=ACCEPTED로 집계한다. 기존 EXCLUDE 명령은 직접 승인의 호환 별칭이며 EXCLUDED 상태 항목도 승인으로 이관한다. 제출본 승인·master 생성은 별도 절차다. 기존 EXCLUDE 이벤트 기록은 보존한다.
