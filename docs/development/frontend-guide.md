# 손익관리 프런트엔드 구현

2026-11-17 기준 Vue 3.5 · Vite 8 · Vue Router · Pinia를 유지한다. 소스는 `frontend/src/`, 정적 자산은 `frontend/public/assets/`에 있다. 새 UI 라이브러리와 AI 검증은 추가하지 않았다. 이번 변경은 Figma 최신 경영기획 화면과 조직 담당자 v3, PRD v0.8의 **보고서 제외** 업무 흐름을 대상으로 한다.

기능별 원문·화면·API·남은 차이는 [PRD 대응표](prd-wireframe-alignment.md)에 기록한다. 이 문서의 ‘구현’은 소스와 API 연결을 뜻한다. 모든 PRD 세부 요구의 완료나 모든 브라우저 조합의 검증을 뜻하지 않는다.

## 기준과 화면 구조

- [Figma 와이어프레임](https://www.figma.com/design/ZW7ZlgvtY4phycygZqir3z/?node-id=2-2): `figma-design-to-code`의 구체 노드 고충실 context와 스크린샷을 읽고 Vue로 적용한다. Figma 파일은 수정하지 않았다.
- [PRD v0.8](https://docs.google.com/spreadsheets/d/1T4uvFGLkFoBJYqmOGJbUO_S5ROngDN7F/edit?gid=1392943028): 소개의 예시보다 기능 요구사항 행을 우선한다. `FR-HOME-005`의 KPI는 **매출·영업비용·영업이익 3개**다.
- 사용자는 이번 변경에서 업무 규칙도 새 PRD를 따르도록 확정했다. 새 검증은 매출·비용·손익 보고 항목별 Gap, 기준 초과 항목 소명, 선택 조직 요청, 검토 선정·제외를 적용한다. 과거 제출본의 고정 비교 문맥은 다시 쓰지 않는다.
- 연간 첫 마스터는 전사 필수 조직 수락 후 발행한다. 선택 조직 발송 수와 전사 필수 수를 구분한다. 제출 수락은 마스터 구성에 영향을 주지만 소명 수락은 금액·제출 버전을 바꾸지 않는다.
- 248px 어두운 사이드바, `#14171e`, 강조색 `#a9875b`, 본문 `#f5f4f0`, 흰 카드, `#e7e4de` 테두리, 12px 모서리를 공유한다. 넓은 표는 가로 스크롤하며 작은 화면에서는 탐색·카드를 재배치한다.

기획 메뉴는 **알림 모아보기 → 전사 손익 현황 → 월간 시트 관리 → 연간 계획 관리 → 조직별 현황**이다. 조직 담당자는 **요청함 → 제출 이력 관리**를 사용한다. 보고서 메뉴와 미지원 설정 메뉴를 이번 탐색에 넣지 않는다. 기존 `/planning/reports` 소스·직접 경로는 보존했으나 이번 재설계·검증 범위가 아니다.

| 화면 | Figma 근거 | 실제 구성 |
| --- | --- | --- |
| 로그인·코드 입력 | `2:491`, `2:2656`, `2:2705` | 이메일 → 6자리 코드 → 역할별 홈. 로컬 환경은 코드 수신함을 명시하며 실제 메일 발송으로 표현하지 않음 |
| 메뉴·로그아웃 | `401:259`, `403:259` | 현재 메뉴 강조, 알림 배지, 계정 정보, 취소/로그아웃 팝업 |
| 알림 모아보기 | `393:266` | 요약 3개, 유형·미읽음 필터, 날짜별 카드, 읽음·해당 건 이동 |
| 전사 손익 | `168:1903`, `167:242`, `168:2031`, `167:372`, `168:2097` | 마감 5단계·할 일 → KPI 3개 → 6열 손익·Top 5 → 사업부 표·월별 추이·최근 마스터 버전 카드 |
| 월간 시트 | `86:19`, `51:168` | 비교 버전, Gap 기준, 검토 5단계·상태 탭, 계층형 표, 선택 항목 처리·근거 모달 |
| 연간 계획 | `2:1252`, `2:2261`, `2:2300`, `2:1301` | 요청 작성 → 선택 조직 진행표 → 행 확장 회신·원본·소명·수락/반려. 마스터·계획 확정은 접힌 보조 영역 |
| 조직별 현황 | `134:189` | 조직·연도 → 월별 최종 버전 → 버전 파일 → 날짜별 변경 소명. 월별 마스터는 왼쪽 카드 |
| 조직 담당자 v3 | `202:208`, `202:263`, `202:383`, `202:562`, `202:1985`, `202:2186`, `202:2707`, `202:3214` | 요청함 → 업로드 → 검증 → 변경 소명 → 최종 확인 → 제출 이력/반려/소명 보완 |

넓게 흩어진 Figma 홈 캔버스의 개별 카드들은 본문 안에 배치했다. 회전된 아카이브 마스터 그룹은 읽을 수 있는 세로 카드로 옮겼다. 금액·버전·조직명·건수는 API 응답을 사용하며 디자인 예시 금액은 표시하지 않는다. 미지원 회계 분류를 임의 합산하여 매출총이익·순이익으로 만들지 않는다.

## 라우트와 API

| 경로 | 컴포넌트·주요 API |
| --- | --- |
| `/` | `HomeView`: `POST /auth/codes`, `POST /auth/sessions`; 별도 로컬 체험 입장은 `/api/dev/session` |
| `/planning/notifications` | `PlannerNotifications`: `GET /notifications`, `POST /notifications/read` |
| `/planning` | `PlannerDashboard`: 요청 목록/상세, `master-periods`, 연간·월간 마스터 상세, 월간 `forecast`, `review-items`, 마스터 `report-values` |
| `/planning/gap` | `PlannerGap`, `GapReviewTable`: 요청 GET/POST/PATCH, 기준 current/preview/저장, 제출 `comparison`, `review-items/actions`, 소명 상세/첨부 |
| `/planning/annual-plan` | `PlannerRequests`: 연간 요청·발송 조직 진행·회신·제출 decisions, annual-master 상태, 계획 확정 |
| `/planning/annual-plan/new` | `PlannerRequestCreate`: 조직 선택·제외, 연도·기한·메시지 → 요청 POST |
| `/planning/requests` | 기존 연간 관리 경로를 같은 컴포넌트로 유지하는 호환 경로 |
| `/planning/organizations` | `PlannerOrganizations`: 조직/연도 제출 목록, 월·버전 선택, 검토 소명, 발행 마스터·원본/Excel 다운로드 |
| `/planning/annual-masters/:id` | `AnnualMaster`: 고정 마스터 상세·버전·보고값·승인 원천 |
| `/planning/masters/:id` | `MonthlyMaster`: 고정 ACTUAL·확정 PLAN 비교, 버전·보고값·원천 |
| `/requests` | `RequestList`: 본인 조직 요청·`myWorkflow` 다음 동작. 기본 접수 중 요청 |
| `/requests/:id/upload` | `UploadView`: 요청·실제 양식 메타/파일 → 파일 선택/드롭 → 제출 업로드 |
| `/submissions/:id` | 담당자의 `OrgSubmissionWorkflow`와 기획자의 `SubmissionDetail`: 실제 역할·제출 상태에 맞는 검증·확인·검토 |
| `/history` | `HistoryView`: 본인 조직의 완료 제출본 목록. 초안은 명시적으로 상태 필터를 선택하여 조회 |

표의 축약 API에는 `/api/v1` 접두사가 붙는다. 파일 다운로드는 사용자의 명시적 클릭에서만 `saveBlob`을 호출한다.

## 전사 손익의 값과 기준

`PlannerDashboard`는 서울 현재 연도와 최근 발행 월을 우선한다. 연도·월·조직·당월/연간·금액 단위를 한 화면에서 적용한다. 당월 공식 실적은 발행 마스터, 확정 계획은 마스터가 고정한 PLAN 출처를 사용한다. 연간 전망은 해당 월간 요청의 조직별 최신 APPROVED 제출본의 실적+추정이다. 전망 준비가 되지 않으면 연간 PLAN을 **연간 계획**으로 구별해 표시한다. 승인되지 않은 조직을 0으로 더하지 않는다.

손익표는 `손익 항목 / 당월 실적 / 계획 대비 / 계획 차이액 / 전월 추정 대비 / 전월 차이액`의 6열이다. 영업비용은 실제 `kind=COST` 보고 항목을 펼친다. `planAmount`는 서버의 **확정 계획 동일 조직·월·보고 항목** 합계다. 최신 연간 마스터의 가변 계획값으로 바꾸어 비교하지 않는다. 전월 추정은 전월 요청의 승인 전망에서 같은 대상 월을 찾는다. 전월 요청/승인 전망이 없으면 —, 비용 하위 항목의 전월 추정은 현재 projection에 없어 —로 표시한다.

월간 Top 5는 REVENUE/COST 보고 항목의 계획 차액을 OP 영향으로 환산한다. 매출 증가는 양수, 비용 증가는 음수다. 파생 매출/비용/OP 합계를 원인 행에 중복 포함하지 않는다. 연간 범위는 조직별 OP 차액 순위다. 분류별 원인 소명을 추론하거나 AI 문장을 생성하지 않는다. 월별 그래프는 실제 ACTUAL/ESTIMATE 막대와 PLAN 선을 사용하고, 없는 월은 막대를 만들지 않는다.

Top 5의 항목 링크는 `requestId`, 확인된 `organizationId`, `reportItemId`를 월간 검토에 전달한다. 전사 항목 합계에는 특정 조직을 임의 지정하지 않고 보고 항목만 전달한다. 연간 조직 OP 순위는 해당 조직과 실제 OPERATING_PROFIT 보고 항목으로 연결한다. 최근 버전 카드는 같은 월의 `GET /masters?year&month&limit=3` 응답을 사용한다. 최초/갱신 구분·발행 시각·버전·이전 버전·수락 원천 링크를 표시하고 가짜 변경 활동이나 금액 설명을 만들지 않는다. 목록 오류는 KPI·추이를 지우지 않는다.

KPI와 표의 원 단위는 십진 문자열을 보존한다. 대시보드 **억원 표시만** 소수 둘째 자리에서 반올림하며 제목 툴팁과 원 단위 선택에서 원금액을 확인한다. 계산은 `dashboard-values.js`의 BigInt 소수 6자리 정수로 수행한다. null과 분모 0 비율은 —다. `.000000`만 제거해도 실제 소수 금액은 원 단위 보기에서 보존한다. 다른 표의 `formatMoney`는 원·천원·백만원·억원으로 소수점만 이동한다.

마감 5단계는 요청 → 조직 제출 → Gap 검토 → 소명 완료 → 마스터 생성이다. 최신 제출본에 연결된 현재 필수 검토만 센다. 과거 초안 소명이나 다른 제출 회차를 합산하지 않는다. 할 일은 발송 조직 수를 사용하고 전사 첫 마스터 필수 수와 혼동하지 않는다.

## 월간 검토와 연간 요청

월간 시트는 `FR-MONTH-004~012`와 중복인 `FR-GAP-001~009`를 같은 화면에서 제공한다. 기획자는 현재/비교 제출본과 ACTUAL↔PLAN, ESTIMATE↔ESTIMATE, ESTIMATE↔PLAN 유형을 선택한다. 임의 버전 비교는 조회이며, 검토 선정·제외·일괄 요청은 현재 요청의 PENDING 제출본 및 고정 비교 문맥에만 연결한다.

`SELECT`, `EXCLUDE`, `REQUEST_EXPLANATION`은 선택 항목의 토큰과 멱등키로 서버에 저장한다. 제외 사유, 일괄 요청의 회신 기한·메시지를 보존한다. 상태 탭 숫자는 응답 목록으로 다시 계산한다. 다른 버전 비교의 화면 선택만으로 제출 승인 조건을 우회하지 않는다. 소명 수락과 재요청은 `SubmissionReviews`의 회차·토큰 계약을 사용한다. 재요청은 새 회차이며 기존 답변·첨부는 보존한다.

월간 항목 상세의 `SubmissionVersionComparison`은 Figma `55:244`의 원천 행 × 버전 배치를 따른다. `submissionId`가 필수이며 `reportItemId`, `reportItemKind`, `year`, `month`, `scenario`, `unit`은 선택 props다. 현재본을 중앙에 두고 동일 조직·연도에서 이전/추가본을 선택해 최대 3열을 조회한다. 첫 비교본은 서버 `comparisonContext.previousSubmissionId` 또는 `planSubmissionId`가 목록에 있을 때만 초기 선택한다. 각 열은 계획/실적/추정 구분을 표시하며 원천 차액의 기준은 바로 왼쪽 선택본이다. 새 backend API나 명령은 추가하지 않았다.

원천 키는 `detailId + reportItem.id/kind + accountCode/accountName + teamCode + taskCode + year/month`다. 시나리오는 명시한 열의 비교 기준이며 Excel 좌표·행 번호는 키로 쓰지 않는다. 한 버전에라도 중복 키가 있으면 그 키의 **모든 버전 원천 행**을 별도로 표시해 합산/임의 대응을 막는다. 상세 ID 없는 행도 연결하지 않는다. `financial-values`가 제공하지 않는 원천 유형을 추정하지 않는다. OP는 해당 월의 매출·비용 원천으로 풀며 개별 차액을 OP Gap 판정으로 사용하지 않는다.

각 열의 전체 페이지를 읽은 뒤 중복을 판정하고 표는 50행씩 표시한다. 실패한 부분 결과는 비교에서 제외하고 `complete=false`는 누락·차액 미확정으로 표시한다. 원본 대화상자는 각 열의 실제 제출 ID와 sheet/cell로 `source-cells`를 읽어 원문·수식/공유 기준·단위·배율·파일 계산 캐시를 나란히 보여 준다. 원본 수식 재실행·값 보정·누락 0 치환은 하지 않는다. 금액은 기존 BigInt/십진 문자열 도우미로 소수 6자리까지 유지한다.

Gap 차이율·최소 금액을 각각 활성화할 수 있다. 켜진 기준을 모두 초과해야 하며 0인 기준 분모는 비율을 산정하지 않는다. 영향 미리보기는 저장하지 않고 적용은 새 유효 기준을 저장한다. 과거 최종 제출의 고정 기준을 변경하지 않는다.

연간 요청 작성은 조직을 선택하거나 ✕로 제외하여 `organizationIds`를 보낸다. 생성 후 진행표에는 `target.selected=true` 조직만 표시한다. `selectedTargetCount`는 발송 대상, `requiredOrganizationCount/targetCount`는 전사 첫 마스터 필수 대상이다. 요청 설정에서 미발송 조직을 추가 선택하면 PATCH로 추가 발송한다. 이전 제출·전사 마스터 필수 집합을 삭제하지 않는다.

수신자는 조직/요청의 `recipients[]`에 있는 실제 활성 담당자 이름·이메일을 표시한다. 등록된 담당자가 없으면 그대로 안내하며 예시 사람이나 이메일을 채우지 않는다. 코드 로그인에서도 월간 요청의 조직 목록은 별도 조직 API로 조회한다.

연간 진행표의 행을 펼치면 실제 제출 버전·시각·원본 다운로드와 소명을 확인한다. `readiness.canApprove=true`일 때만 수락 확인을 허용하고, 반려에는 사유가 필수다. 최종 명령은 `POST /submissions/{id}/decisions`다. 연간 계획 확정은 별도의 접힌 영역에서 Contingency와 명시적 확인으로 수행한다.

`RequestEditor`는 변경한 메시지·기한·OPEN/CLOSED·추가 발송 조직만 PATCH한다. 같은 본문 재시도는 같은 멱등키다. 접수 마감 후에도 기존 제출 검토·소명 보완은 가능하다. 실제 제출 양식은 요청별 `GET /template`, `POST /template`, `GET /template/file`로 등록·교체·다운로드한다. 양식 파일 SHA-256과 파일명에 따라 재시도 키를 유지한다. 개발용 합성 양식을 제품 제출 양식으로 사용하지 않는다.

## 조직 담당자 제출과 원본 보호

요청함 → 업로드 → 검증 → 변경 소명 → 제출 확인을 서로 다른 단계로 표시한다. 거대한 기술 상세 표를 최초 업무 화면으로 노출하지 않는다. 파싱 후보·정규 적재·보고값·원본 수식은 근거 펼침 안에서 구별한다.

- 새 업로드의 `validate=1` 진입만 파싱 SUCCEEDED 후 업무 검증을 이어 실행한다. 기존 제출 이력 조회는 검증·재파싱 명령을 자동 실행하지 않는다.
- `DRAFT + SUCCEEDED`는 파싱 성공이다. 검증 전/STALE/필수 소명 미작성은 제출 완료로 표시하지 않는다. 최종 확인 진입과 submit은 서버 `readiness.canSubmit`에 따른다.
- 오류는 실제 원본 시트·셀·원본값과 사유를 표시한다. 정확한 주소를 모르면 추측한 주소를 만들지 않는다. 조직 담당자가 원본 파일을 수정하고 전체 파일을 다시 업로드한다.
- 금액 변경 소명은 실제 필수 항목과 제외 상태를 사용한다. 매출·비용/손익·월의 항목을 선택해 비교금액·원본 근거·소명 입력을 읽는다. 이유와 향후 대응은 v3의 단일 본문에 기록하며 별도 DB 열로 나누지 않는다.
- 반려 후 재제출은 전체 파일 스냅샷이다. 같은 달은 반려본, 새 달은 전월 승인본을 비교 기준으로 사용한다. 과거 검증 문맥과 이미 수락된 소명은 서버 정책대로 보존한다.
- 소명 보완은 현재 요청 회차의 답변·첨부만 추가하며 제출 파일·버전은 바꾸지 않는다. 현재 DRAFT 또는 PENDING 재요청의 미답변 회차에서만 첨부를 추가한다.
- `.xlsx` 한 개, 비어 있지 않은 파일·용량 제한·조직/파일명 검사를 수행한다. 확인된 원본 이름에 대한 전송명 동의를 받더라도 `FormData.append`의 전송 이름만 지정한다. File 바이트·`Data/` 경로·원본 이름은 수정하지 않는다.

## 조직별 현황·아카이브

실제 사업조직·연도·월을 선택하고 완료 제출 회차를 기본 표시한다. ‘작성 중 포함’으로 보존 초안을 확인할 수 있다. 월별 최종 버전은 DRAFT를 제외한 최대 seq이며 승인 완료와 구분한다.

변경 이력은 날짜·번호·코드·항목명·소명 사유의 5열이며 선택 기간 제출본의 실제 변경 소명을 날짜별로 묶고 날짜 셀을 병합한다. 버전의 ‘이력 보기’를 누르면 해당 버전만 좁힌다. 본문은 현재 소명 회차이며 전체 회차는 해당 제출 상세로 연결한다. 실제 변경이 없는 소명을 만들어 채우지 않는다. 월 마스터는 실제 요청이 있는 월의 수락 진행 수, 고정 발행 버전 링크와 Excel 다운로드를 제공하고, 파일 다운로드 실패는 제출 이력을 숨기지 않는다.

## 인증·오류·자산

이메일 인증코드의 만료·재전송·오류·역할별 이동과 로그아웃 확인을 서버에 연결한다. 현재 개발 환경은 `LOCAL_PREVIEW` 수신함이며 실제 이메일 전송은 없다. 최초 로그인 소속 확인 전용 단계와 조직·권한 관리 화면은 아직 없다. 소속과 역할은 서버 actor로 결정한다.

토큰은 Pinia 메모리에만 보관하고 localStorage/sessionStorage·URL·콘솔에 기록하지 않는다. 역할 변경·로그아웃·조직 전환은 요청을 취소하고 인증을 비운다. RouterView는 사용자·조직·경로별로 다시 마운트된다. HTTP 401은 재로그인으로 연결한다. API 오류는 안전한 detail과 requestId만 표시한다. cursor는 해석·변형하지 않는다.

정적 SVG는 `frontend/public/assets/`의 원본 파일을 사용하며 Figma 임시 URL을 런타임에서 쓰지 않는다. root 크기를 수정하지 않는다. 홈 구분선은 `planner-home-divider.svg`(1×321), 실제 PLAN 좌표의 점은 `planner-trend-point.svg`(6×6), 아카이브 배경은 `planner-archive-background.svg`(1192×1076)이다. 실제 계획선은 API 값으로 만든 동적 SVG이므로 예시 곡선 파일을 데이터 그래프에 사용하지 않는다. 조직 요청함/이력 아이콘은 기존 11×11 원본을 유지한다.

## 함수 명세와 호출 관계

세부 목적·Args·Returns·Raises·Side Effects는 각 함수의 한국어 JSDoc을 따른다. 아래 표는 화면 진입과 명령의 호출 관계를 함께 관리한다. 동일 파일의 `dispose`는 controller·세대·타이머를 폐기한다. 계산·포맷·computed는 별도 표기가 없으면 부작용·예외가 없는 읽기 함수다.

| 파일·함수 | 입력 → 반환·역할 | 호출·부작용 |
| --- | --- | --- |
| `session.js`: `sessionStore`, `clear`, `request`, `enter`, `accept`, `refreshUnread`, `logout` | 세션·HTTP 옵션·로그인 응답 → 상태/JSON/Blob | 메모리 인증, HTTP 취소/401 정리, 개발/코드 로그인, 알림 배지, 서버 로그아웃 |
| `App.vue`: `changeRole`, `openLogout`, `cancelBackdrop`, `refreshNavigation`, `plannerMenuActive`; `isOrganizationInboxActive` | 이벤트·메뉴명·조회한 제출 상태 → 모달/활성 상태 | 취소/확인 후 logout, 알림 갱신, 요청함/완료 이력의 실제 단계 강조 |
| `HomeView`: `requestCode`, `verify`, `enterDemo`, `changeEmail`, `initialize`, `dispose`; `remaining/countdown/canResend` | 이메일·코드·역할 → 로그인/남은 시간 | 코드 요청/소비, session.accept/enter, 타이머와 역할 경로 |
| `PlannerNotifications`: `load`, `open`, `readAll`, `selectType`, `dayLabel`, `typeLabel`, `timeLabel`, `initialize`, `dispose`; `groups` | 필터/알림 → 날짜별 목록·안내 | 조회, 개별 읽음 후 이동, 미읽음 전체 페이지를 수집하여 읽음 처리 |
| `PlannerDashboard`: `pages`, `requestYears`, `newestYear`, `initialize`, `loadYear`, `loadMonth`, `jobPromise`, `loadRows` | 연도·월·조직 → 마스터/전망/검토 | 전체 cursor 조회, 독립 자료 GET, 이전 요청·행 결과 무효화 |
| `entity`, `findMonth`, `selectedAmounts`, `availableOrganizations`, `selectedScope` | 집계·범위 → 선택 DTO/이름 | 동일 범위 KPI·표·차트 연결, null 보존 |
| `currentReviews`, `tasks`, `closingStages`, `deadlineLabel` | 최신 제출·기한 → 5단계/할 일 | 과거 초안 제외·발송 대상 진행 수 |
| `profitRows`, `reason`, `departmentRows`, `hasGap`, `compareGap`, `topDeviations` | 고정 보고값·소명 → 표·순위 | 비용 접기·확정 계획 비교·수락 소명·OP 영향 순위 |
| `monthlyTrend`, `chooseMonth`, `money`, `rate`, `monthlyLink`, `topFiveLink`, `dispose` | 월/금액/순위 → 좌표/표시/경로 | BigInt 차트 좌표, 월 조회·최근 고정 버전, 실제 조직·항목 검토 경로·요청 취소 |
| `dashboard-values.js`: `moneyUnits`, `moneyString`, `difference`, `gapRate`, `dashboardMoney`, `amountTone` | 십진 문자열 → 정수/정확 차액·비율·표시 | 소수 6자리 BigInt; 금액 Number 합산 없음, 0분모 null |
| `PlannerGap`: `loadRequests`, `loadDetail`, `createRequest`, `loadThresholds`, `changeScenario`, `thresholdCommand` | 요청·기준 입력 → 응답/미리보기 | GET, 토큰/멱등 POST, 표시 필터와 확정 검증 문맥 구분 |
| `scenario`, `baselineScenario`, `allPages`, `loadOrganizations`, `loadCatalog`, `loadWorkspace`, `chooseBaseline`, `loadComparison`, `changeComparisonType` | 비교유형·제출ID·월 → 조직/카탈로그/비교행 | 로그인 방식과 무관한 실제 조직 조회, 실제 제출·확정 계획/전월 승인 기준 조회, 세대 검사 |
| `toggleReview`, `openReview`, `openSource`, `closeReview`, `beginAction`, `submitAction`, `reviewed`, `openRequest`, `downloadComparison`; `nextSelectionAction` | 선택ID/행위 → 모달·명령/다운로드 | 검토 필요 항목은 SELECT 후 요청 단계, selected 토큰 일괄 처리. 소명 없는 일반 항목도 원천 3버전 모달, 소명/알림 재조회, 사용자 선택 비교 내보내기 |
| `targetLink`, `draftLink`, `statusLabel`, `versionLabel`, `initialize`, `dispose`; target/visibleTargets/selectedRows/canAct/displayRows/tabCounts/filteredRows/targetName | 요청·상태 → 선택/라벨/표 | 완료본과 새 초안 분리, 상태 탭·검색·선택 가능 범위 |
| `GapReviewTable`: `buildTree`, `decimalSort`, `compareNodes`, `flattenTree`/내부 `append`, `toggleBranch`, `selectAll`, `signClass` | 실제 카탈로그·비교행 → 계층·정렬 | 부모관계를 유지하고 루트 항목은 실제 kind 매출/비용 표시그룹으로 묶음. 접기·부모 항목도 개별 선택·상세 이벤트; 표시그룹의 합계 생성 없음 |
| `PlannerRequests`: `load`, `select`, `changeRequest`, `shortDate`, `targetStatus`, `targetLink`, `draftLink`, `refreshSelected`, `dispose` | 연간 요청·조직 → 목록/표시/경로 | 현재 연도 우선, 상세와 마스터 독립 조회 |
| `visibleRequestTargets`, `requestOrganizations`, `expand`, `downloadSource`, `decideSubmission` | 발송조직·선택행·사유 → 회신/결정 | 행 확장 상세 GET, 사용자 다운로드, canApprove/토큰/멱등 결정 |
| `PlannerRequestCreate`: `loadOrganizations`, `organizationId`, `selectedRecipients`, `isSelected`, `changeRecipients`, `createRequest`, `dispose` | 조직선택·연도·기한·메시지 → 요청 | 선택 조직만 POST, 재시도키 보존, 현황으로 이동 |
| `RequestEditor`: `localDateTime`, `save`, `refresh`, `loadTemplate`, `uploadTemplate`, `downloadTemplate`, `dispose` | 기한·변경필드·양식파일 → 상태/파일 | 부분 PATCH·추가발송·양식 메타 조회/불변 바이트 등록·멱등키·다운로드 |
| `PlannerOrganizations`: `filterOrganizations`, `findOrganization`, `periodSubmissions`, `latestVersion`, `initialize`, `loadYear`, `loadMasters` | 조직·연도·월 → 실제 제출/마스터 | 전체 목록 조회·초안 선택 분리·마스터 독립 오류 |
| `groupedChanges`, `newestChange`, `loadReviews`, `choosePeriod`, `selectOrganization`, `selectSubmission`, `selectYear`, `detailLink`, `download`, `downloadMaster`, `dispose` | 기간·버전 → 날짜별 이력/경로/파일 | 선택 버전 필터·소명 조회·원본/고정마스터 명시 다운로드 |
| `RequestList`: `load`, `statusLabel`, `dispose` | 다음 페이지/요청 → 본인 요청 카드 | OPEN 기본 조회·myWorkflow 다음 작업·구 응답 폐기 |
| `UploadView`: `allowsUpload`, `uploadBlocker`, `expectedFilename`, `requiresConsent`, `reuploadReturn`, `load`, `choose`, `selectFiles`, `onFile`, `onDrop`, `sendFile`, `upload`, `template`, `dispose` | 실제 파일·요청 → 업로드/양식 | 원본 바이트 유지·전송명 동의·멱등 업로드·신규 검증 화면으로 이동 |
| `OrgSubmissionWorkflow`: `isParsing`, `allowsExplanationStep`, `combinedValidationIssues`, `reuploadTarget`, `workflowTitle`, `load`, `publishNavigationContext`, `moreDiagnostics`, `command`, `showConfirmation`, `openSource`, `closeSource`, `download`, `scenarioLabel`, `openGuidance`, `dispose` | 제출 상태·단계 → 검증/소명/확인 | 순차 폴링, 신규 업로드만 검증 연결, 조회한 최소 상태를 App에 메모리 공유, 최종 submit, 정확한 원본 근거·오류 안내 |
| `SubmissionDetail`: `isParsing`, `allowsValidation`, `allowsSubmission`, `allowsReupload`, `needsOriginalCorrection`, `originalReuploadLink`, `allowsReview`, `submissionReturn`, `describeState`, `blockerMessage` | actor·상태 → 권한/안내/복귀 | 기획자 검토와 담당자 단계 구분 |
| `loadResults`, `load`, `more`, `command`, `chooseDecision`, `decide`, `describeMasterEffect`, `openCell`, `closeCell`, `openGuidance`, `download`, `dispose` | 제출 명령/근거 → 응답/파일 | GET/validation/submit/decisions·masterEffects·원본셀 모달·폴링 취소 |
| `SubmissionReviews`: `usesOrganizationLayout`, `groupReviewItems`, `selectedComparison`, `currentSourceCells`, `kindLabel`, `scenarioLabel`, `load`, `select`, `allowsAnswer`, `allowsDecision`, `save`, `loadEvidence`, `openSource`, `stateLabel`, `attachmentChanged`, `dispose` | 검토항목·답변·행위 → 회차/근거 | 종류·월 계층, 필수 소명, 회차 토큰 답변/수락/보완, detailOnly·initialReviewId 모달 재사용 |
| `SubmissionVersionComparison`: `displayUnitName`, `comparisonContextKey`, `defaultScenario`, `scenarioName`, `versionName`, `findVersion`, `usedElsewhere`, `selectVersion`, `initialize`, `readColumn`, `compare` | 제출 ID·항목·기간 → 최대 3개 선택본의 적재 원천 | 초기화 GET → 같은 조직/연도 목록 → 전체 페이지 병렬 조회. 모든 HTTP 읽기 전용, 세대/취소로 뒤늦은 응답 차단 |
| `SubmissionVersionComparison`: `businessKey`, `alignRows`, `independentRow`, `changedRow`, `filterRows`, `visibleRowsPage`, `countUnaligned`, `showMore`, `deltaView`, `absentText` | 명시 키·정확한 금액 → 안전한 대응/차액/표시 | 중복/누락 ID 분리, 실패·부분 적재 미확정, 숫자 0 보존. 좌표 동일성 추정·합산 없음 |
| `SubmissionVersionComparison`: `openSource`, `readSource`, `closeSource`, `formulaText`, `dispose` | 행의 실제 제출별 sheet/cell → 원문 3열 | 자기 제출 source-cells만 조회. 공유 수식의 실제 기준과 해당 셀 원문 구별, 메모리 캐시·취소/해제 |
| `ReviewAttachments`: `load`, `selectFile`, `upload`, `download`, `choose`, `clearFile`, `dispose` | 소명회차·파일 → 실제첨부 | 현재 편집가능 회차에만 추가, 과거첨부 읽기, 원본 다운로드 |
| `HistoryView`: `load`, `reset`, `detailLink`, `dispose` | 필터/cursor → 완료 제출본 | submittedOnly 기본, 초안필터 별도, 선택 상세 경로 |
| `ValidationIssue`: `openCell`; `submission-guidance.js`: `issueLocation`, `issueNextAction` | 실제진단 → 위치/조치 | 확인된 셀만 열고 주소 추측·원본 보정 없음 |
| `AnnualPlanConfirmation`: `load`, `selectedYear`, `confirm`, `organizationName`, `dispose` | 연도/명시 Contingency → 확정 | 승인 후보 GET, 확인·토큰·멱등키 confirm |
| `AnnualMaster`: `load`, `loadVersions`, `loadValues`, `changeVersion`, `sourceLink`, `kindLabel`, `dispose` | 마스터/필터 → 고정값 | 버전·보고값 cursor와 승인 원천 유지 |
| `MonthlyMaster`: `load`, `loadVersions`, `loadValues`, `dispose`; `MonthlyMasterData`: `selectOrganization`, `visibleOrganizations`, `money`, `rate`, `sourceLink`, `reset` | 고정 월/조직 → 실적·확정계획 | 실제금액·null비율·고정 출처 조회 |
| `MonthlyOverview`: `selectPeriod`, `selectedYear`, `load`, `loadMaster`, `chooseMonth`, `dispose`; `AnnualForecast`: `selectScope`, `selectSources`, `load`, `money`, `sourceLink`, `dispose` | 연도/요청/조직 → 발행월·승인전망 | 보존한 보조 컴포넌트, 명시 조회·원천 일치 |
| `display.js`: `query`, `requestTitle`, `dateTime`, `amount`, `submissionStatus`, `errorText`, `saveBlob`/내부 `releaseDownload` | API값 → 표시/파일 | 상태 분리·한국시간·십진 정밀도·클릭 다운로드 |
| `decimal-display.js`: `parts`, `formatMoney`, `chartGeometry`, `zeroPosition` | 십진값/단위 → 표시/좌표 | 문자열 소수점 이동, BigInt 비교, Number는 0~100 좌표에만 사용 |
| `upload-filename.js`: `inspectUploadFilename`; `request-workflow.js`: `draftStatus`, `requestAction`; `role-route.js`: `routeAllowed`, `roleDestination`; router `authorizeRoute` | 파일명/요청/역할 → 판정/경로 | 순수 판정·역할별 진입; 원본 수정/인증 저장 없음 |

보고서 전용 함수는 이번 변경 대상이 아니며 `PlannerReports.vue`의 기존 소스 명세를 따른다.

## 검증 기록

이번 변경의 중간 점검에서 담당 기획 화면 5개 script 구문과 `git diff --check`를 통과했다. `dashboard-values.js`는 실제 1월 숫자, 음수, 소수 6자리, 0분모, null, Number 안전정수 밖의 값에 대한 **8개 메모리 검증**을 통과했다. 파일·보고서·캐시를 생성하지 않았다.

최근 버전 카드·Top 5 문맥 링크 추가 뒤 Dashboard script와 요청/선택 조직/전사 항목/연간 OP 링크의 **4개 메모리 검증**을 통과했다. 실제 수신 화면의 최종 필터 동작은 Docker 브라우저 점검으로 구분한다.

조직 화면 담당 검증은 단위 변환/7개 script 구문과 첨부 H2 통합 테스트 7개 통과를 보고했다. 이 기록은 해당 검증 범위만 뜻한다. 이후 완료한 Docker 통합 빌드·최종 브라우저 확인의 범위와 결과는 [최종 재빌드와 Chrome 확인](local-environment.md#최종-재빌드와-chrome-확인)을 따른다. 앞선 중간 점검의 테스트 숫자를 최종 전체 검증으로 확대하지 않는다.
