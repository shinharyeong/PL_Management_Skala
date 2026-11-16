# 로컬 역할 체험과 파싱 후보 미리보기

## 적용 범위

이 문서는 구현된 개발 체험 API와 읽기 전용 파싱 미리보기 계약을 설명한다.
업무 API의 연간 검증·최종 제출·적재 조회·승인·반려는 [API 명세](api-contract.md)에 연결되어 있다. 수정 소명·연간 확정·공식 마스터는 후속 범위다.
`SUCCEEDED`는 파서 실행 결과이며, 업무 검증·승인 성공을 뜻하지 않는다.
파싱 직후 제출은 `DRAFT`, 업무 검증은 `NOT_RUN`, `readiness.canSubmit=false`다. 이후 담당자가 업무 검증을 실행하고 조건을 충족한 초안을 명시적으로 제출하면 PENDING이 된다. 개발 양식 API가 업무 검증을 자동으로 통과시키지는 않는다.

서버 실행과 Docker 볼륨의 생성·보존은 [로컬 실행 환경](local-environment.md)을 따른다.
세션·양식·초기 데이터는 `local`과 `demo` 프로필을 **함께** 켰을 때만 등록된다.
`local`만 켜면 기존 개발 Bearer 인증만 제공하고 자동 자료를 만들지 않는다.
`local`이 없으면 기존 기본 거부 보안 경계가 적용된다.
`parsed-records` 컨트롤러는 일반 프로필에도 등록되지만 기존 인증·조직 권한을 반드시 거친다.

## 개발 초기 자료와 환경

`DemoBootstrap`은 서버 시작 시 하나의 트랜잭션에서 다음 자료를 준비한다.

| 종류 | 내용 |
| --- | --- |
| 기본 조직 | D100 네트워크인프라부문, D200 미디어사업부 |
| 추가 조직 | 사업부1~17: F10, F12, F20, F40, F39, F50, F16, F13, F25, F43, F42, F44, F73, F38, F66, F36, F37 순서 |
| 사용자 | 환경의 PLANNER 1명·D100 ORG_MANAGER 1명과 추가 사업부별 ORG_MANAGER 17명. 추가 이메일은 `demo-f10@example.test` 형식 |
| 연간 요청 | 2027 ANNUAL_PLAN, 연간 계획 COLLECTING, 요청 OPEN |
| 요청 대상 | 요청 생성 시점의 활성 조직 전체. 기본 요청 처리 후 추가 17개 조직을 준비하며 기존 대상은 바꾸지 않음 |

요청은 기존 `SubmissionService.createRequest`와 고정 멱등키를 사용해 만든다.
재시작 시 동일한 조직·사용자·요청을 재사용한다. 기존 이름·역할·조직·활성 상태가 충돌하면
시작을 실패시키며 기존 데이터를 덮어쓰지 않는다. 기존 요청은 생성자·유형·두 조직의 대상 등록을
확인하고 상태를 다시 열거나 변경하지 않는다. 제출·승인·마스터 행은 만들지 않는다.
빈 DB에서는 기본 2027 요청의 대상이 D100·D200 두 조직으로 고정된다. 추가 조직을 준비한 뒤
새 요청을 생성하면 활성 조직 19개가 대상이다. D100·D200를 제외하거나 기존 요청에 17개를
끼워 넣지 않는다. 2026 요청은 자동 생성하지 않으며 기획자 업무 API로 명시적으로 생성한다.
조직·계정 상수는 검토한 `Data/dummy/manifest_baseline.json`의 코드·이름을 반영한 값이며
서버 기동 시 Data 경로에 접근하지 않는다.

| 설정 | 의미 |
| --- | --- |
| `app.local-auth.planner-email` / `organization-email` | 서로 다른 개발 사용자 이메일 |
| `app.local-auth.planner-token` / `organization-token` | 서로 다른 공백 없는 32자 이상 개발 Bearer |
| `app.demo.frontend-origin` | 허용하는 루프백 HTTP Origin의 쉼표 구분 목록 |
| `APP_DEMO_FRONTEND_ORIGIN` | Compose 및 `application-demo.yml`의 Origin 환경변수 |

기본 Origin은 `http://localhost:5173,http://127.0.0.1:5173`이다.
Origin 설정은 명시적 포트가 있는 `localhost` 또는 `127.0.0.1`만 허용한다.
세션 API는 설정한 프런트엔드 Host와 `localhost:<server.port>`, `127.0.0.1:<server.port>`만 받는다.
프록시는 요청의 `Origin`과 원래 `Host`를 보존해야 한다. Compose 포트는 루프백에만 공개한다.
토큰 값은 소스·문서·로그에 남기지 않고 서버 환경에서 공급한다.

## 역할 선택 세션

`POST /api/dev/session`은 실제 로그인·SSO가 아닌 **로컬 역할 선택 체험**이다.
`local & demo`에서만 이 정확한 POST 경로를 익명 허용한다. 운영 로그인·갱신·폐기 시스템을 구현하지 않는다.

```http
POST /api/dev/session
Content-Type: application/json
Origin: http://localhost:5173
Host: localhost:5173

{"role":"ORG_MANAGER"}
```

`role`은 `PLANNER` 또는 `ORG_MANAGER`다. 조직 담당자는 선택적인 `organizationCode`를 보낼 수 있다.
생략하면 기존 D100이며, `{"role":"ORG_MANAGER","organizationCode":"F10"}`은 사업부1을 선택한다.
허용 코드는 D100과 위의 17개 사업부 코드뿐이다. D200는 연결된 담당자가 없어 선택할 수 없다.
기획자의 조직 지정, 빈 문자열, 허용 목록 밖 코드는 400 `INVALID_REQUEST`다.

응답은 `token` 문자열, 현재 DB의 `actor`, `organizations` 배열이다.
`actor`는 `id`, `email`, `name`, `role`, `organization`을 가지며 기획자의 `organization`은 null이다.
담당자의 `organization`은 `id`, `code`, `name`이다. 모든 ID는 문자열이다.
`organizations` 각 항목은 `id, code, name`이며 조직 담당자에게 D100 다음 사업부1~17 순서의
활성 허용 계정만 제공한다. 기획자는 빈 배열을 받는다. 목록 조회용 GET은 추가하지 않는다.
조직 전환은 새 계정 세션을 선택하는 동작이며 기존 사용자의 DB 소속이나 이전 토큰의 조직을 바꾸지 않는다.

Origin·Host 헤더는 각각 하나만 있어야 하고 허용 목록과 정확히 일치해야 한다.
누락·외부 주소는 403 `DEMO_ORIGIN_FORBIDDEN`, 잘못된 역할·쿼리는 400,
JSON 외 Content-Type은 415다. 응답은 `private, no-store`이며 쿠키를 만들지 않는다.
프런트엔드는 토큰을 메모리에만 보관하고 이후 요청의 `Authorization: Bearer …`에 전달한다.
브라우저를 새로고침하면 역할을 다시 선택한다.
기존 PLANNER·D100 토큰은 환경 설정을 그대로 사용한다. 추가 17개 토큰은 서버 시작 시
`SecureRandom`의 32바이트로 각각 발급해 메모리에만 보관한다. 다른 역할의 공개된 토큰에서
파생하지 않으며 SHA-256 다이제스트를 constant-time 비교한다. 추가 토큰은 백엔드 재시작 시
만료되므로 조직을 다시 선택해야 한다. 매 요청에서 고정한 ORG_MANAGER 역할·조직 코드와
현재 DB 활성 상태를 확인한다. 소속·역할·코드가 바뀐 이전 토큰은 401이며 새 권한으로 전이되지 않는다.
인증된 추가 조직 Actor도 업무 조회 직전에 사용자 ID·역할·조직 ID·코드를 재검증한다.

기본 더미의 원래 파일명은 업로드 계약명과 다르다. 업무 접수 API는 계속
`F10_2026-PLAN.xlsx`처럼 선택 조직·요청 기간과 정확히 일치하는 multipart 파일명을 요구한다.
프런트에서 허용된 더미 파일의 전송명만 명시적으로 맞추더라도 원본 바이트는 바뀌지 않는다.
파일명 보정은 워크북 내부 조직·기간의 업무 검증을 대체하지 않는다.

## 메모리 합성 양식

`GET /api/dev/template?requestId={id}`는 인증된 ORG_MANAGER만 사용할 수 있다.
요청 조회 서비스를 통해 본인 조직이 대상인지 확인하며 ANNUAL_PLAN만 지원한다.
다른 유형은 422 `DEMO_TEMPLATE_UNSUPPORTED_REQUEST`, 기획자는 403,
없는 요청·대상 외 요청은 기존 조회 계약에 따라 처리한다.

응답은 XLSX attachment이며 파일명은 조직과 요청 연도에서 결정한다. 예: `D100_2027-PLAN.xlsx`.
양식은 `매출`, `비용`, `조직코드`, `예산코드`, `보고` 5개 시트다.
매출·비용 상세에는 1~12월 PLAN, 단위 원, 합성 상세 ID·개정번호와 코드 근거를 포함한다.
금액은 0과 소수 정밀도를 확인하는 합성 값이다. 보고 시트는 요청 조직·연도의 12개월 헤더와
관리분류/실적집계기준별 SUMIFS, 매출·비용 총계 SUM, 영업이익 차감 수식과 정확한 합성 캐시값을
제공한다. 원 단위 상세와 보고 금액의 대조가 가능하며 서버 파서는 수식을 재계산하지 않는다.
상세 금융값 24개와 보고 대조값 60개는 역할이 다르며, 보고 총계를 금융 상세에 중복 적재하지 않는다.

양식은 `ByteArrayOutputStream`·`ZipOutputStream`으로 요청마다 메모리에서 만든다.
서버 파일·임시 파일·Data 원본을 읽거나 만들지 않는다. 사용자가 다운로드한 양식을 업로드하면
기존 접수 경로가 원본 바이트를 DB에 저장하고 실제 Python 파서로 처리한다.
이 양식은 동작 확인을 위한 최소 예시이며 전체 기업 양식이나 확정 계획을 대신하지 않는다.
같은 요청에 비교할 이전 제출이 없는 경우 일반 업무 검증을 거쳐 최종 제출과 기획자 승인·반려까지
체험할 수 있다. 기존 제출본과 금액이 다르면 합성 양식도 수정 소명 조건을 우회하지 않는다.

## 파싱 후보 페이지

`GET /api/v1/submissions/{submissionId}/parsed-records?limit=50&cursor=…`

PLANNER는 기존 제출 조회 권한 범위, ORG_MANAGER는 본인 조직 제출만 조회한다.
일반 업무 API와 동일한 인증을 요구하며 demo 프로필 전용이 아니다.
`limit`은 1~100, 기본 50이고 `cursor`는 서버가 반환한 불투명 문자열이다.
그 외 쿼리나 잘못된 페이지 인자는 400이다. 커서는 사용자·역할·제출·파싱 시도·결과에 묶이므로
다른 제출·사용자 또는 재파싱 후의 커서는 400 `INVALID_CURSOR`다.

```json
{
  "summary": {
    "sheetCount": 5,
    "recordCount": 0,
    "mappedCount": 0,
    "unresolvedCount": 0,
    "unusedTemplateRowCount": 0
  },
  "items": [],
  "nextCursor": null
}
```

위 숫자는 구조 예시이며 실제 합성 양식의 레코드 개수를 뜻하지 않는다.
summary는 내부 파서가 저장한 카운트를 그대로 사용한다. items는 원래 후보 순서를 유지하며
코드·참조 후보도 포함하므로 `recordCount`를 재무 상세 개수로 해석하면 안 된다.
`unusedTemplateRowCount`는 입력 근거와 월별 입력이 없고 확인된 연간 합계 수식만 남은
미사용 양식 행의 개수다. 동일 시트·행의 여러 연간 후보를 한 행으로 센다.
원본 후보·수식·캐시 값은 items와 내부 저장 결과에 남고, 실제 0·누락·오류 입력 행은 이 숫자에 포함하지 않는다.
이 필드가 없는 구버전 저장 결과와 결과 없는 실패 작업은 0을 반환하며 구버전 결과를 다시 분류하지 않는다.
Python 경계에서는 선택 필드로 받되 존재하면 0 이상의 Java int 범위 정수여야 한다.

| items 필드 | 타입·해석 |
| --- | --- |
| `sheet`, `cell` | 문자열/null, 원본 시트·A1 셀 위치 |
| `organizationCode`, `organizationName` | 문자열/null, 파서가 읽은 조직 근거이며 인증 조직 대조 전 |
| `itemName`, `accountCode` | 문자열/null, 원본 항목·계정 후보이며 확정 report_item이 아님 |
| `year`, `month` | 정수/null, 확인된 원본 기간 |
| `scenario` | PLAN/ACTUAL/ESTIMATE/null |
| `amount` | 십진 문자열/null, `value_kind=amount`인 정규화 후보 값만 포함 |
| `amountUnit` | KRW/null. 원본 단위가 확인된 원 정규화 값만 KRW, 미확정 단위는 null |
| `normalizationStatus` | 대문자 파서 정규화 상태/null |
| `formula` | 원본 수식 문자열/null, 재계산하지 않음 |
| `rawValue` | 문자열/boolean/null, 숫자 원문도 문자열로 반환 |

0은 문자열 `"0"`이며 미확정·비금액 후보의 null과 구분한다. amountUnit=null인 숫자는 단위 미확인 금액 후보로 표시하며 원으로 단정하지 않는다.
수치형 JSON으로 금액을 변환하거나 반올림하지 않는다. `formula`·`rawValue`·조직·항목은
파일에서 온 표시 데이터이므로 프런트엔드는 HTML로 해석하지 않고 텍스트로 렌더링해야 한다.
내부 경로·저장 객체 키·원본 레코드 ID는 응답에 포함하지 않는다.

현재 시도가 QUEUED/RUNNING이면 409 `PARSING_NOT_READY`를 반환하여 이전 결과를 표시하지 않는다.
완료된 FAILED에 저장 결과가 없으면 0 요약·빈 items를 반환하고, 실패 이유는 기존 diagnostics에서 확인한다.
결과가 있으면 SUCCEEDED/NEEDS_REVIEW/FAILED의 현재 저장 결과를 조회한다.
전체 저장 JSON 바이트를 DB에서 읽되 summary와 요청 페이지의 후보만 객체화하고 나머지는 스트림으로 건너뛴다.
따라서 응답 크기는 페이지로 제한하지만 DB payload 전체 읽기는 남아 있다.

이 API는 내부 파서 결과를 제한적으로 투영하는 추가 조회 계약이다.
`report-values`, 전체 `source-cells` 조회, 변경 소명 근거, 승인 재무 집계의 대체물이 아니다.

## 함수 명세와 호출 관계

구체적 Args·Returns·Raises·Side Effects는 아래 정의 파일의 모든 메서드 Javadoc에서 관리한다.
기존 기반 함수는 [백엔드 함수 명세](backend-function-reference.md)를 따른다.

| 정의 파일·메서드 | 호출·입출력과 부작용 |
| --- | --- |
| `demo/DemoAccounts` 생성자, `required`, `email`, `organizationEmail`, `token` 오버로드, `actor` 오버로드 | 기존 자격·허용 코드 검증 → 고정 역할·조직의 DB Actor. DB·파일 쓰기 없음 |
| `DemoAccounts.selectedOrganization`, `organizations` | 선택 역할·코드 검증 및 활성 허용 계정 목록. 기획자는 빈 목록; 기존 소속을 수정하지 않음 |
| `DemoAccounts.randomToken`, `digest`, `actorForOrganizationToken` | 시작 시 17개 난수 자격 발급 → 다이제스트 비교 → 고정 역할·조직 재확인. 메모리·DB 읽기만 사용 |
| `demo/DemoBootstrap` 생성자, `run`, `organization`, `user` | ApplicationRunner → 합성 자료 검사/INSERT → `SubmissionService.createRequest`. 하나의 트랜잭션, 충돌 시 실패 |
| `demo/DemoController` 생성자, `session`, `template` | 환경 Origin 검증 → 세션 헤더·역할 검사 또는 인증 양식 서비스 호출. 세션은 읽기만, 양식은 메모리 생성 |
| `demo/DemoTemplateService` 생성자, `generate`, `workbook` | 인증 조직·연간 요청 → 파일명·OOXML 바이트. 요청 DB 읽기; 디스크 접근 없음 |
| `DemoTemplateService.worksheetTypes`, `financialSheet`, `reportSheet`, `worksheet`, `text`, `column`, `xml` | 고정 합성 템플릿·12개월 보고 수식/캐시 XML 구성·문자 이스케이프. 메모리만 사용 |
| `parser/ParsedRecordsController` 생성자, `records` | ActorResolver → ParsedRecordsService. 인증·권한 오류를 기존 공통 처리기로 전달 |
| `parser/ParsedRecordsService` 생성자, `records`, `page` | 제출 권한 → 활성 작업 → DB 결과 바이트 → 스트림 페이지·커서. 미사용 행 수를 전달하며 구버전 누락은 0. 파일·DB 쓰기·재파싱 없음 |
| `ParsedRecordsService.project`, `emptySummary`, `text`, `integer`, `limit` | 내부 후보 → 제한 DTO, nullable 값·정밀 문자열·페이지 범위 유지. 외부 부작용 없음 |
| `parser/PythonParserClient.validResult`, `failure` | 선택 미사용 행 수의 타입·범위 검증, 안전한 실패 요약에 0 명시. 파일·DB 쓰기 없음 |
| `security/SecurityConfiguration.localSecurityFilterChain` | local 인증 유지, 선택적 DemoAccounts 공급, demo 추가 활성 시 POST `/api/dev/session`만 익명 허용 |
| `security/LocalBearerAuthenticationFilter` 생성자·`doFilterInternal`, `ActorResolver.current` | 추가 자격의 고정 Actor를 principal로 전달하고 업무 조회 시 소속·역할 변경을 다시 차단 |

코드 경로의 기준은 `backend/src/main/java/io/skala/backend/`다.
`SessionInput(role, organizationCode)`는 자동 접근자를 사용하는 입력 record다. 역할·최대 코드 길이는
Jakarta Validation, 역할과 허용 코드 조합은 `DemoAccounts.selectedOrganization`에서 검사한다.

## 검증 범위

`DemoIntegrationTests`는 별도 H2 `demo_bridge`에서 초기 자료 멱등성·충돌 보존,
Origin/Host/Content-Type, 조직 선택·토큰 격리·잘못된 선택·발급 후 권한 변경 차단,
새 요청의 19개 대상·기존 두 대상 보존, 양식 권한, 실제 Python 연결, 금액 문자열·0, 페이지·조직 격리를 확인한다.
합성 양식의 보고 항목·대조 근거를 일반 검증 API로 확인하고 상세 2행·월별 24값을 적재한 뒤
최종 제출(PENDING) → 기획자 승인(APPROVED) → 요청 승인 조직 수 갱신까지 검사한다.
미사용 행 수 전달·구버전 필드 누락·결과 없는 실패의 0 호환과 음수·소수·문자열·null·범위 초과 거부도 검사한다.
`ActorResolverTest`는 고정 Actor 인증과 업무 조회 사이의 소속·역할·코드 변경도 검사한다.
`DemoDisabledTests`는 별도 H2 `demo_disabled`에서 demo 미활성 시 Bean·초기 자료·익명 세션이 없음을 확인한다.
실제 파서 테스트는 메모리 양식과 `-B` Python 프로세스를 사용하며 Data를 읽지 않는다.
실행 결과와 Docker의 실제 HTTP 연결 결과는 상위 통합 검증에서 함께 기록한다.
