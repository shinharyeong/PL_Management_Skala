# 손익관리 프런트엔드 구현

2026-11-16 기준 Vue 3.5 · Vite 8 · Vue Router · Pinia의 기존 스택을 유지한다. 소스는 저장소의 `frontend/`(작업공간에서는 `skala_final_project-develop/frontend/`)에 있으며 새 UI 라이브러리는 추가하지 않았다. 조직 담당자의 요청·초안·파싱 흐름과 경영기획팀의 6개 화면을 구분한다. 요청 생성·수집 현황·원본 조회는 실제 API에 연결하며, 손익 집계·Gap·보고서의 Figma 금액은 디자인 예시다. 최초 연간 계획 초안의 업무 검증·최종 제출과 제출본별 적재 금액·보고 집계·원본 셀 조회를 연결한다. 소명 답변·승인·공식 마스터는 이번 구현 범위가 아니다.

## Figma 출처와 적용

`figma-design-to-code` 스킬을 읽고 구체 노드별 `get_design_context`의 고충실 코드와 스크린샷을 확인했다. Figma 캔버스는 변경하지 않았다. 반환된 React/Tailwind 프로토타입을 Vue 템플릿과 공통·scoped CSS로 옮겼다. 실제 연결 화면은 API 응답을 사용하고, 미구현 업무 화면은 Figma 예시 값을 유지하면서 `디자인 예시 · 실제 집계가 아닙니다`를 표시한다.

| 원본 화면 | 노드 | 구현 |
| --- | --- | --- |
| 조직 담당자 v3 요청함 | [202:208](https://www.figma.com/design/ZW7ZlgvtY4phycygZqir3z/?node-id=202-208) | `RequestList.vue`, 공통 사이드바 |
| 연간 계획 엑셀 업로드 | [202:442](https://www.figma.com/design/ZW7ZlgvtY4phycygZqir3z/?node-id=202-442) | `UploadView.vue` |
| 제출 이력 관리 | [202:2186](https://www.figma.com/design/ZW7ZlgvtY4phycygZqir3z/?node-id=202-2186) | `HistoryView.vue` |
| 홈 · 전사 손익 | [2:590](https://www.figma.com/design/ZW7ZlgvtY4phycygZqir3z/?node-id=2-590), 복제 화면 [168:1818](https://www.figma.com/design/ZW7ZlgvtY4phycygZqir3z/?node-id=168-1818) | `PlannerDashboard.vue`: 월 선택, 마감·할 일, KPI·표·차트 예시 |
| 월간 시트 관리 | [86:19](https://www.figma.com/design/ZW7ZlgvtY4phycygZqir3z/?node-id=86-19) | `PlannerGap.vue`: Gap 기준, 5단계 흐름, 상태 탭, 계층형 영업비용 표, 선택 작업 바 |
| 연간 요청·검토 현황 | [2:1108](https://www.figma.com/design/ZW7ZlgvtY4phycygZqir3z/?node-id=2-1108), 요청 카드 [2:1160](https://www.figma.com/design/ZW7ZlgvtY4phycygZqir3z/?node-id=2-1160), 진행 표 [2:1252](https://www.figma.com/design/ZW7ZlgvtY4phycygZqir3z/?node-id=2-1252) | `PlannerRequests.vue`: 연간 요청 바로가기와 실제 조직별 수집 현황 |
| 연간 계획 요청 작성 | [2:2261](https://www.figma.com/design/ZW7ZlgvtY4phycygZqir3z/?node-id=2-2261), 작성 카드 [2:2300](https://www.figma.com/design/ZW7ZlgvtY4phycygZqir3z/?node-id=2-2300) | `PlannerRequestCreate.vue`: 본문 작성 페이지, 전 조직·기한·메시지·실제 생성 |
| 조직별 현황·아카이브 | [134:189](https://www.figma.com/design/ZW7ZlgvtY4phycygZqir3z/?node-id=134-189) | `PlannerOrganizations.vue`: 조직 목록, 월별 버전, 변경 이력, 마스터 카드와 실제 업로드 보기 |
| 보고서 자료 생성 | [2:1924](https://www.figma.com/design/ZW7ZlgvtY4phycygZqir3z/?node-id=2-1924) | `PlannerReports.vue`: 표·차트 미리보기, 자료 구성, 메시지 편집 |

대시보드의 세부 출처는 마감·할 일 `2:685`, KPI `167:242`(복제 `168:2159`), 사업부별 표 `167:439`(`168:2214`), 손익표 `168:2031`, 월별 추이 `168:2097`, 워터폴 `167:318`(`168:2319`), Top 5와 그 외 `167:372`(`168:2373`)다. 다른 연월·연간·매출 선택의 수치를 임의로 생성하지 않는다.

최초 구현의 `PlannerRequests.vue` 요청 작성 dialog는 제거했다. 작성은 별도 본문 화면으로 이관하고 `/planning`의 기본 화면도 요청 목록에서 전사 손익 대시보드로 복원했다. Figma의 캔버스 밖으로 배치된 차트는 웹의 본문 안에서 읽을 수 있도록 정돈했다. 조직 아카이브의 회전된 마스터 영역 [134:388](https://www.figma.com/design/ZW7ZlgvtY4phycygZqir3z/?node-id=134-388)은 수평 카드 목록으로 표시한다. 이는 원본 데이터·색·차트 의미를 변경하는 것이 아니라 웹 탐색을 위한 배치 차이다. 먼 캔버스 아래에 분리된 소명 작성 영역을 연간 요청 표 안으로 합치지 않는다.

공통 기준은 248px 사이드바, `#14171e` 배경, `#a9875b` 강조색, `#f5f4f0` 본문, 흰색 카드, `#e7e4de` 테두리, 12px 카드 모서리, 기본 36px 본문 여백이다. Noto Sans KR을 사용하고 외부 글꼴이 연결되지 않으면 시스템 글꼴로 표시한다. 모바일에서는 사이드바를 상단 탐색으로 전환하고 넓은 데이터 표는 가로 스크롤한다.

원본 SVG는 Figma가 제공한 curl 다운로드 지침으로 `frontend/public/assets/`에 저장했다. 임시 Figma URL은 런타임에서 사용하지 않는다. SVG 내용을 변형하지 않고 각각의 root width·height를 유지한다. 기존 조직 담당자 아이콘 4개는 11×11이며 주위 16×16 테두리 래퍼만 CSS로 설정한다.

| 로컬 자산 | 원본 자산 이름 | 표시 위치 |
| --- | --- | --- |
| `inbox-active.svg` | `8cdbb.svg` | 요청함/업로드 메뉴 활성 |
| `inbox.svg` | `276a0.svg` | 요청함 메뉴 비활성 |
| `history-active.svg` | `3a12c.svg` | 이력/상세 메뉴 활성 |
| `history.svg` | `50632.svg` | 이력 메뉴 비활성 |
| `gap-step-1.svg` ~ `gap-step-5.svg` | `ab59c.svg`, `ee37c.svg`, `900cb.svg`, `0065f.svg`, `da4da.svg` | 5단계 원형 번호 배경, 각각 23.6528×23.6528 |
| `gap-tab-line.svg` | `bb31c.svg` | 재검토 탭 원본의 높이 0 선 슬롯, root 32×32 유지 |
| `planner-archive-background.svg` | `9934c.svg` | 조직별 현황 배경, 1192×1076 |
| `planner-home-divider.svg` | `f1f68.svg` | 홈 마감·할 일 구분선, 1×321 |
| `planner-trend-line.svg` | `beaa0.svg` | 월별 추이 계획선, 699.006×72.7219 |
| `planner-trend-point.svg` | `9568c.svg` | 월별 추이 계획점 12개, 각각 6×6 |
| `planner-waterfall-connector.svg` | `34ed7.svg` | 손익 워터폴 차감 연결선 8개, 각각 27×1 |

연간 요청 현황·작성 및 보고서 본문에는 반환된 별도 정적 SVG가 없다. 보고서의 원본 사각형 차트는 CSS로 구현한다. 경영기획 사이드바의 원본 빈 테두리 아이콘은 조직 담당자용 SVG로 대체하지 않는다.

요청 생성 시 대상은 활성 조직 전체이며 조직별 선택/제외는 제공하지 않는다. 이는 현재 확정된 서버 계약을 따른다. 양식 버튼은 예시임을 명확히 알리기 위해 `테스트 양식 다운로드`로 표시한다. `테스트 양식으로 실행`은 같은 서버 양식을 Blob으로 받은 뒤 계약 파일명으로 File을 구성하여 일반 업로드 API에 보낸다. 일반 파일 선택과 드롭도 동일하게 지원한다.

## 라우트와 API 흐름

| 경로 | 동작과 호출 |
| --- | --- |
| `/` | `POST /api/dev/session {role, organizationCode?}` → 메모리 토큰·actor·선택 가능 조직 → 역할별 시작 화면 |
| `/requests` | 열린 요청 GET·cursor, 세션 응답의 체험 조직 선택 시 새 조직 세션으로 전환 |
| `/requests/:id/upload` | 요청 상세 → 양식 다운로드 또는 파일 입력 → multipart `POST .../submissions` |
| `/submissions/:id` | 상태 GET·순차 폴링 → 진단·파싱 후보 조회, financial-values/report-values 독립 조회. 담당자만 validation → 준비 상태 확인 → submit. 출처 클릭은 source-cells GET |
| `/history` | 제출 목록 필터·cursor, query의 requestId/organizationId로 조직 현황에서 진입 |
| `/planning` | `PlannerDashboard.vue`, 경영기획 기본 진입점. 전사 손익 디자인 예시 |
| `/planning/gap` | `PlannerGap.vue`, 예시 선택·검색·정렬·탭·행 펼침·상세 dialog. HTTP·업무 상태 변경 없음 |
| `/planning/requests` | `PlannerRequests.vue`, ANNUAL_PLAN 요청 목록·상세 GET, 조직별 초안·최종 제출 수, 최신 제출본 직접 연결(없으면 조직별 이력) |
| `/planning/annual-plan` | `PlannerRequestCreate.vue`, 활성 조직 전체 GET → 멱등 연간 요청 POST → `/planning/requests?requestId=…` |
| `/planning/organizations` | `PlannerOrganizations.vue`, 기본 디자인 예시. `실제 업로드`의 기본 연도는 2026. 경로의 실제 보기·조직·연월 복원, 제출 GET·원본 다운로드 |
| `/planning/reports` | `PlannerReports.vue`, Figma 표·차트 예시 구성과 메시지 메모리 편집. 공식 보고서 생성 없음 |

`App.vue`의 경영기획 메뉴는 위 6개 경로를 각각 제공한다. 조직 담당자 메뉴와 업로드 경로는 유지한다. 로그인 역할이 PLANNER면 첫 진입과 브랜드 링크는 `/planning`, ORG_MANAGER면 `/requests`다. 역할 전환은 진행 요청과 메모리 인증을 제거하고 `/`로 돌아간다. 기획 화면은 `plannerCanvas` 메타데이터로 중복된 공통 breadcrumb를 숨긴다. `Gap 기준 · 템플릿`, `조직 · 권한 관리` 설정 저장은 아직 비활성이다.

파싱 종료 후 `GET .../parsed-records`의 summary와 items를 표시한다. 파싱 후보 표는 `금액 후보`로 표시하며 `amountUnit=KRW`일 때만 `원`, null이면 `단위 미확인`을 붙인다. 후보 영역 전체에 원 단위를 일괄 선언하지 않는다. 기존 금액 문자열은 바꾸지 않고 Number 변환 없이 천 단위 구분자만 추가한다. null은 `—`이며 0으로 바꾸지 않는다. 표에 원본 시트·셀을 표시하고 펼침 영역에서 수식·원본 값·정규화 상태를 확인한다. 페이지 cursor는 해석하거나 재구성하지 않는다.

summary의 `unusedTemplateRowCount`가 0보다 크면 기존 네 개 요약 건수 아래에 `미사용 양식 N행 · 입력 없는 양식 행은 업무 검증에서 제외하고 원본을 보존했습니다.`를 중립 안내로 표시한다. N은 서버가 집계한 행 수이며 화면에서 셀 수나 진단 수로 환산하지 않는다. 과거 저장본은 API가 0을 반환하고 안내를 표시하지 않는다. 미사용 양식 행을 오류·확인 필요 건수에 더하지 않으며 파싱 성공을 최종 제출 완료로 바꾸지 않는다.

파싱 진단은 API의 `message`, `source.sheet`, `source.cell`을 그대로 표시한다. 정확한 검증 사유와 원본 셀을 프런트에서 일반 문구로 바꾸거나 추측하지 않는다. 이번 추가는 안내 한 줄의 조건부 표시이며 진단 조회·페이징·원본 다운로드 흐름과 함수 서명은 유지한다.

`DRAFT + SUCCEEDED`는 파싱 성공만 뜻한다. 검증 전에는 `파싱 완료 · 제출 전`, `CURRENT + canSubmit=true`는 `검증 완료 · 제출 가능`, `STALE`은 `재검증 필요 · 제출 전`으로 구분한다. 상태 API의 `canSubmit=false`를 우회하는 버튼이나 가짜 성공 응답은 없다. 초안 업로드는 제출/승인 건수에 포함하지 않는다. 디자인 예시 화면의 검토·제외·기준 적용·다운로드·복사는 미지원 안내만 표시한다. 예시 금액을 승인 집계로 합산하거나 예시 상태를 서버에 저장하지 않는다.

조직별 현황의 `실제 업로드` 보기는 예시 조직·버전·소명과 분리한다. 기본 연간 조회는 2026이며 상세에서 돌아오면 `mode=actual`, `organizationId`, `year`, `month`로 같은 선택을 복원한다. 서버가 준 `versionLabel`이 없으면 `초안`으로 표시하며 승인 버전을 만들어내지 않는다. 변경·소명·공식 마스터는 조회 미연결로 안내한다. API를 호출하지 않고 자료가 없다고 단정하지 않는다. 원본 다운로드는 실제 제출의 권한 확인 경로만 사용한다.

월간 시트의 예시 차이율·금액 입력은 기준 저장 기능이 아니다. 기존 합의인 모든 금액 수정 소명, 확인된 OP 비교, 전체 스냅샷 재제출, 승인 조직별 마스터 교체 규칙은 변경하지 않는다.


`SubmissionDetail.vue`는 기존 카드·색·표를 유지하고 `SubmissionData.vue`로 적재 조회만 분리한다. `financial-values`의 선택 완료 상세행·월 금액과 `report-values`의 보고 합계를 원본 `parsed-records` 후보와 별도 표로 표시한다. 팀·과제·계정·상세 ID·설명은 원천 근거이며 별도 Gap 평가 축으로 취급하지 않는다. 적재·보고의 확정 금액은 원 단위 십진 문자열을 유지한다. 파싱 후보의 nullable amountUnit과 혼동하지 않는다. `valueStatus=MISSING/UNRESOLVED`는 `—`와 해당 상태로 표시하고 일부 행만 합산한 값을 만들지 않는다. `summary.complete=false`이면 유효 일부 행을 담은 검증 초안이라고 안내한다. 검증 전·무효화 조회의 `VALIDATION_REQUIRED/VALIDATION_STALE`는 빈 결과로 단정하지 않고 검증 필요 안내를 표시한다.

업무 검증과 최종 제출은 ORG_MANAGER의 DRAFT에만 제공한다. 검증은 파싱 SUCCEEDED에서 `POST .../validation {expectedStateToken}`으로 실행하고 응답의 submission을 채택한 뒤 최신 상태·진단을 조회한다. `readiness.blockers`의 message/code/source를 그대로 안내하며 `canSubmit=true`일 때만 `POST .../submit {expectedStateToken}`을 허용한다. 같은 명령·본문의 통신 재시도에는 같은 멱등키를 사용한다. 상태 충돌은 최신 GET으로 갱신하고 오류를 표시하며 자동 재제출하지 않는다. 제출 후 서버의 회차·행위자·시각과 PENDING을 표시하며 승인으로 바꾸지 않는다. 기획자에게는 담당자 명령 버튼을 표시하지 않는다.

진단·적재 금액·파싱 후보의 원본 위치를 누르면 `source-cells?sheet&cell`로 정확한 셀을 조회한다. 패널은 값·수식·저장된 계산값·단위·배율·정규화 상태를 표시하며 원본을 재계산하지 않는다. source-cells의 sharedFormulaBase가 있으면 `공유 수식 · 기준 셀`과 실제 기준 수식·공유 범위를 별도로 표시한다. 이를 현재 셀의 수식으로 복제하거나 실행하지 않는다. formula=null이면서 기준 근거도 없으면 cacheStatus에 따라 `수식 원문 미확인`과 `없음`을 구분한다. 파싱 후보 DTO만으로 수식 존재를 알 수 없으면 원본 셀 확인을 안내한다. 정규화 값은 sourceUnit이 확인될 때만 원으로 표시한다. CONTROL_CACHE_PRECISION 진단은 저장 캐시·정확한 상세 합계·차이를 함께 표시하며 숫자를 수정하지 않는다. 없는 셀은 API 오류로 표시하고 빈 셀과 혼동하지 않는다. 선택 변경은 이전 조회를 취소하고 세대값으로 늦은 응답을 무시한다. 파싱 시도 변경·재파싱·화면 이탈에는 패널을 비운다.

요청 현황의 `보기`는 `latestSubmittedSubmissionId`가 있으면 해당 최종 제출본으로 바로 연결하고 없으면 요청·조직의 이력으로 연결한다. `from=requests/organizations/history`와 식별자만 전달해 돌아갈 화면과 메뉴 활성을 유지한다. 임의 반환 URL은 받지 않는다. 대시보드의 Figma 예시 손익은 이 제출 적재값으로 덮어쓰지 않는다.

## 인증·오류·취소

- 개발 역할 세션을 브라우저 메모리에만 저장한다. localStorage/sessionStorage, 쿠키, URL, 콘솔에 토큰을 남기지 않는다.
- 로컬 체험의 조직 담당자는 세션 응답 `organizations`의 조직만 선택할 수 있다. `organizationCode`를 생략한 최초 입장은 D100이며, 응답 목록은 D100과 17개 사업부다. PLANNER 입장에는 조직 코드를 보내지 않는다. 이 선택 기능은 `/api/dev/session`의 local·demo 개발 범위이며 운영 인증 정책을 대신하지 않는다.
- API 요청은 같은 출처 `/api`만 사용하고 `Authorization: Bearer`를 붙인다. 역할·조직은 서버의 actor 응답으로 표시하고 요청 본문으로 임의 주입하지 않는다.
- 세션 종료·역할 변경·조직 전환은 진행 중인 모든 요청을 AbortController로 취소하고 actor·token·조직 목록을 먼저 비운다. 화면별 controller와 합친 신호를 사용하며, 늦게 끝난 이전 입장은 세션을 덮어쓸 수 없다. RouterView key에 actor·조직·경로를 포함해 이전 조직의 화면 상태를 재사용하지 않는다. 상태 폴링은 앞선 요청 완료 후에만 다음 타이머를 만들며 화면 이탈 시 해제한다. 목록의 다음 페이지는 마지막으로 조회에 적용한 필터를 유지한다.
- HTTP 401은 현재 세션을 비운다. API 오류는 안전한 detail과 requestId를 화면에 표시하고 응답 원문·토큰을 로그에 출력하지 않는다.
- 파일 선택은 한 개, `.xlsx`, 비어 있지 않은 16MiB 이하를 확인한다. 계약 파일명은 그대로 허용한다. 17개 사업부 원본은 순번·사업부번호·조직코드 매핑과 현재 조직이 모두 일치할 때만 이름 보정 대상으로 허용한다. 원본명과 전송 예정 이름을 함께 표시하며 `제출 파일명으로 전송`에 체크하기 전에는 업로드하지 않는다. 새 선택이 잘못되면 이전 파일과 동의를 해제한다.
- 이름 보정은 `FormData.append('file', file, expectedFilename)`의 전송 이름만 지정한다. 원본 File의 이름·바이트, Data/의 파일·경로를 수정하지 않는다. 이름 근거가 없는 파일, 전체 마스터, 다른 조직 파일, 잘못 조합된 순번·코드는 보정하지 않는다. 이 검사는 파일명 실수를 막는 보조 검사이며 Excel 내부 조직의 업무 교차 검증을 완료했다는 뜻은 아니다.
- 생성·업로드·재파싱에 `Idempotency-Key`를 사용한다. 같은 입력의 통신 실패 재시도는 키를 재사용한다. 파일 재선택/새 파싱 시도/변경된 요청 본문은 새 키다.
- 다운로드는 사용자 명시적 클릭으로 시작하며 Blob URL을 해제한다. 사용자 데이터를 HTML 문자열로 주입하지 않는다.

## 함수 명세와 호출 관계

아래는 직접 작성 함수의 목록이다. 세부 Args·Returns·Raises·Side Effects는 각 함수의 한국어 JSDoc에 있으며, 같은 파일 안의 짧은 callback도 소유 함수의 명세에 포함한다. API 오류를 화면에서 처리하는 함수는 Promise<void>를 반환하고 AbortError는 표시하지 않는다. HTTP 요청 외 로컬 파일 쓰기는 명시적 다운로드만 수행한다.

| 파일·함수 | 입력 → 반환 | 예외·부작용·호출 |
| --- | --- | --- |
| `session.js` · `sessionStore` | 없음 → Pinia 상태·동작 | 스토어 구성; `clear`, `request`, `enter` 제공 |
| `clear` | 없음 → void | 진행 요청 취소·메모리 actor/token/조직 목록/입장 상태 초기화 |
| `request` | API 경로·fetch 옵션·blob → JSON/Blob Promise | HTTP·안전한 Error/AbortError; 현재 401에서 clear |
| `enter` | 역할·선택적 signal·organizationCode → Promise<void> | clear → 개발 세션 POST → 인증·조직 목록 저장; 이전 시도 응답/오류 무효화 |
| `display.js` · `query` | 필터 객체 → 쿼리 문자열 | 빈 값 제외; 부작용 없음 |
| `requestTitle` | 요청/제출 → 제목 문자열 | 연간/월간 표시; 부작용 없음 |
| `dateTime` | ISO 시각/null → 표시 문자열 | 한국 시간대; 부작용 없음 |
| `amount` | 십진 문자열/null → 표시 문자열 | 정밀도 유지, null은 —; 부작용 없음 |
| `submissionStatus` | 제출 DTO → 표시 문자열 | 업무 제출·파싱 구분; 부작용 없음 |
| `errorText` | Error → 안전한 메시지·추적값 | 부작용 없음 |
| `saveBlob`, 내부 `releaseDownload` | Blob·파일명 → void | 사용자 다운로드, 임시 URL 생성 후 해제 |
| `App.vue` · `changeRole` | 없음 → void | session.clear → 시작 경로 이동 |
| `plannerMenuActive` | 메뉴 이름 → boolean | 공용 이력·상세의 진입 문맥으로 메뉴 활성 판정 |
| `HomeView.vue` · `enter` | 역할 → Promise<void> | session.enter → 역할 경로; 오류 표시 |
| `restoreRoute`, `dispose` | 없음 → void | 이미 인증되면 이동, 이탈 시 입장 요청 취소 |
| `RequestList.vue` · `load` | 다음 페이지 여부 → Promise<void> | 열린 요청 조회·목록 갱신 |
| `changeOrganization`, 내부 `isAvailable` | select 이벤트/조직 → Promise<void>/boolean | 실제 선택 가능 조직 확인 → 구목록·요청 취소 → session.enter |
| `dispose` | 없음 → void | 목록 HTTP 취소 |
| `UploadView.vue` · `expectedFilename` | 없음 → 파일명 | computed 조직·요청 기간 계산; 부작용 없음 |
| `requiresConsent` | 없음 → boolean | computed 원본명과 제출명 비교; 부작용 없음 |
| `load` | 없음 → Promise<void> | 요청 상세 조회 |
| `choose`, `onFile`, `onDrop` | 없음/브라우저 이벤트 → void | 파일 선택기 및 selectFiles 연결 |
| `selectFiles` | FileList → void | 파일·조직 이름 근거 검사, 원본 File 유지, 동의 초기화·멱등키 갱신 |
| `sendFile` | 없음 → Promise<void> | 조직·동의 재검사 → 같은 바이트에 제출 이름만 지정한 multipart → 상세; 오류 전파 |
| `upload`, `template` | 없음/즉시 실행 여부 → Promise<void> | sendFile 또는 양식 다운로드; 오류 표시 |
| `dispose` | 없음 → void | 파일/요청 HTTP 취소 |
| `upload-filename.js` · `inspectUploadFilename` | 원본명·제출명·현재 조직 코드 → 허용/동의 필요/거절 사유 | 계약 이름 또는 확인된 17개 원본 이름만 허용; 파일 읽기·쓰기 없음 |
| `SubmissionDetail.vue` · `isParsing`, `allowsValidation`, `allowsSubmission` | 없음 → boolean | 파싱·역할·상태·서버 준비 상태 판정 |
| `submissionReturn`, `describeState`, `blockerMessage` | 없음/없음/blocker → 라우트·안내 | 허용된 복귀 문맥과 실제 상태 표시; 부작용 없음 |
| `load`, `loadResults`, 내부 `setDiagnostics`/`setRecords` | 없음/세대/응답 → Promise<void>/void | 상태 GET·순차 폴링·진단/후보 병렬 조회, 구 응답 차단 |
| `more` | 자료 종류 → Promise<void> | 현재 cursor의 다음 페이지 추가 |
| `command` | validation/submit/parsing-retries → Promise<void> | 권한·상태 확인 → 토큰/멱등키 POST → 최신 상태·진단 조회 |
| `openCell`, `closeCell` | 서버 출처/없음 → Promise<void>/void | 정확한 원본 셀 GET·초점 이동 / 취소·패널 초기화 |
| `download`, `dispose` | 없음 → Promise<void>/void | 원본 GET·saveBlob / 폴링·명령·셀 조회 취소 |
| `SubmissionData.vue` · `resultMessage` | API 오류 → 안내 | NOT_RUN/STALE/파싱 중을 조회 오류와 구분 |
| `readPage`, `load`, `more` | 종류·다음 페이지·세대/없음/종류 → Promise<void> | 적재/보고 두 API 독립 조회·필터·cursor·응답 취소 |
| `scenarioName`, `valueStatus` | API 상태 → 표시 문자열 | 부작용 없음; 누락·미확정을 0으로 바꾸지 않음 |
| `openCell`, `dispose` | 출처/없음 → void | 상위 셀 조회 이벤트 / 조회 취소 |
| `HistoryView.vue` · `load` | 다음 페이지 여부 → Promise<void> | 필터 목록, 이전 조회 취소·세대값으로 경쟁 응답 차단 |
| `reset`, `dispose` | 없음 → void | 필터 초기화→load / HTTP 취소 |
| `detailLink` | 제출 요약 → 라우트 | 요청·조직·진입 문맥 전달 |
| `PlannerDashboard.vue` · `deltaTone` | 예시 차이 문자열 → 색상 클래스 | 표시 부호 해석; 부작용 없음 |
| `matchesDepartment`, `filteredDepartments` | 사업부/없음 → boolean/배열 | 선택 조직의 예시 표 필터, 실제 집계 조회 없음 |
| `selectionDescription` | 없음 → 안내 문자열 | 선택 기간·지표의 예시 범위 설명; 부작용 없음 |
| `PlannerRequests.vue` · `load` | 다음 페이지 여부 → Promise<void> | 연간 요청 GET, query 선택 ID 또는 최초 상세 조회 |
| `select`, `changeRequest` | 요청 ID/선택 이벤트 → Promise<void>/void | 이전 상세 취소·조회 세대 확인·ANNUAL_PLAN 확인 |
| `shortDate` | ISO 시각/null·시간 포함 여부 → 문자열 | 한국 시간대 월일·시분, 잘못된 값은 —; 부작용 없음 |
| `targetStatus` | 대상 조직 DTO → label/tone | 실제 최종 상태·기한 해석, 초안을 완료로 세지 않음 |
| `targetLink` | 대상 조직 DTO → 라우트 | 최신 제출본 직접 연결 또는 요청·조직 이력 |
| `dispose` | 없음 → void | 목록·상세 취소 및 세대 무효화 |
| `PlannerRequestCreate.vue` · `loadOrganizations` | 없음 → Promise<void> | 활성 조직 전체 cursor 조회, 전 조직 고정 표시 |
| `createRequest` | 없음 → Promise<void> | 실제 연간 요청 POST, 본문별 멱등키, 생성 요청의 현황 화면 이동 |
| `dispose` | 없음 → void | 대상 조회·생성 요청 취소 |
| `PlannerGap.vue` · `filteredGroups`, `compareRows` | 없음/두 예시 행 → 그룹 배열/비교 정수 | 조직·검색·탭 필터와 소분류 내 정렬, 원본 예시 합계 불변 |
| `countDetails`, `showResale` | 없음 → 상세 수/boolean | 현재 예시 표시 계산; 부작용 없음 |
| `toggleGroup`, `clearSelection` | 그룹 ID/없음 → void | 로컬 펼침·선택 상태만 변경 |
| `showDialog`, `explainUnavailable`, `showDetail` | 제목·안내·선택 상세/기능명/상세 → void | 안내 dialog·초점, 저장·발송·승인 없음 |
| `selectStage`, `closeDialog` | 단계/없음 → void | 예시 탭 또는 미지원 안내, dialog 닫기 |
| `PlannerOrganizations.vue` · `filterOrganizations`, `findOrganization` | 없음 → 조직 배열/조직 또는 undefined | 예시/실제 모드별 검색·선택 computed |
| `changeMode` | 없음 → Promise<void> | 이전 요청 취소·선택 초기화, 실제 모드 조직 GET → loadSubmissions |
| `loadSubmissions` | 다음 페이지 여부 → Promise<void> | 실제 조직·연월·유형 GET, 필터 변경 시 cursor 초기화·구 응답 무시 |
| `select` | 필드·값 → void | 조직·연월 선택, 실제 모드에서만 조회 |
| `detailLink`, `initialize` | 제출 요약/없음 → 라우트/void | 실제 조직·기간 전달 / 경로의 선택 복원·조회 |
| `download`, `explainPreview`, `dispose` | 제출/없음/없음 → Promise<void>/void | 실제 원본 GET·saveBlob / 예시 안내 / 취소·세대 무효화 |
| `PlannerReports.vue` · `countSelected` | 없음 → 정수 | 선택 구성 수 computed; 부작용 없음 |
| `explainPreview` | 없음 → void | 공식 마스터·보고서 미연결 안내, 생성·다운로드 없음 |

이전 요청 작성 modal의 `openCreate`, `closeCreate`, `cancelCreate`, `initialize`는 더 이상 현재 함수가 아니다. 실제 생성 함수는 `PlannerRequestCreate.vue`로 이관했다. 보고 메시지 편집·구성 선택은 Vue의 로컬 ref만 갱신한다.

## 검증 범위

**이전 검증 범위 정정:** 이전 기록의 “기획 전체 레이아웃 확인”은 과도한 표현이었다. 당시 확인한 것은 조직 담당자 입장 → 요청함 → 합성 양식 업로드 → SUCCEEDED 폴링 → 24개 금액 셀·0 표시 → 이력 조회와, 기획 역할의 2028년 연간 요청 생성·2개 조직 현황이라는 API 흐름이다. 경영기획 Figma 전체 화면과 내비게이션의 재현을 확인한 것은 아니다. 사용자 지적 후 6개 기획 화면과 메뉴를 복원했다.

이전 단계의 Node 문법 검사, 금액 정밀도·null/0·cursor·파싱/승인 상태 구분 검사, 조직 담당자 SVG 4개와 Docker 빌드 통과 기록은 해당 이전 소스 범위에 한정한다. 새 기획 화면의 완료 근거로 사용하지 않는다.

현재 수정 단계에서는 구체 Figma 노드의 고충실 context·스크린샷, Vue 소스 읽기, API 계약·취소 경계·예시/실제 분리와 자산 root 크기를 점검했다. 신규 기획 함수 34개의 Args·Returns·Raises·Side Effects, 6개 컴포넌트의 scoped style, 신규 SVG 11개의 파일·호출 위치·root 치수를 확인했다. 기존 조직 담당자 SVG를 포함한 전체 자산은 15개다. Gap의 로컬 메모리 SFC 컴파일은 `@vue/compiler-sfc`가 설치되지 않아 실행되지 않았고 추가 설치하지 않았다.

새 기획 화면의 실제 브라우저 검증에서 현재 확인한 범위는 다음과 같다.

| 화면 | 확인 결과 |
| --- | --- |
| 보고서 | 화면 배치 확인. 주별 자료 체크 해제 시 선택 수가 4개→3개로 변경되고, 전체 다운로드는 미지원 안내만 표시 |
| 월간 시트 관리 | 상세 예시 금액 표시와 상세 dialog 닫기 확인 |
| 연간 계획 작성 → 요청·검토 | 2029년 요청을 실제 생성해 ID 3을 받았으며, 생성 후 2개 조직의 수집 표를 확인 |
| 조직별 현황 | 디자인 예시 배치 확인. 실제 업로드 모드에서 2027년 D100 초안 6개(파싱 성공 5개·실패 1개) 조회 확인 |
| 실제 초안 상세 | 아카이브에서 초안 ID 6으로 진입해 `2027년 연간 계획`, `파싱 완료 · 제출 전`, 읽은 시트 5·추출 셀 25·정규화 24·확인 필요 0을 확인 |
| HTTP·자산 | 기획 SPA 6개 경로 응답, SVG 15개가 비어 있지 않고 로컬 파일과 응답 바이트가 동일함을 확인. 백엔드 health UP |
| 홈 · 전사 손익 | 상·중·하단 시각 확인. 마감·To Do → KPI → 요약·Top 5 → 사업부별 → 손익 흐름 → 월별 추이 순서와 SVG 계획선·점·분리선·연결선 표시 확인 |
| 최종 프로덕션 빌드 | Docker 프런트 빌드 49개 모듈 처리 성공, frontend healthy 확인 |

위 결과는 확인한 동작만 기록한 것이며 모든 선택·검색·반응형 조합의 완전 검증을 뜻하지 않는다. 후속 정적 점검에서 Gap 상세 항목과 보고서 옵션 label에 남아 있던 공통 CSS 글자 굵기를 보정했다. 마지막 label 스타일 보정은 소스 점검과 최종 프로덕션 빌드로 확인했으며 별도의 시각 재검증을 했다고 기록하지 않는다. 미구현 최종 제출·소명 처리·승인·마스터·보고서 내보내기는 검증 완료로 표시하지 않는다.

### 사업부 원본 업로드 연결의 추가 검증

조직 선택·파일명 동의 변경은 메모리에서 검사했다. Data/dummy의 17개 원본 파일명만 읽어 각 올바른 조직을 허용하고, 다른 조직 조합 289개를 거절하는지 확인했다. 순번/사업부번호/코드 불일치·전체 마스터·임의 파일명도 거절했다. 메모리 File을 다른 multipart 이름으로 지정해도 원본 이름과 바이트가 동일함을 확인했다. 이 검사는 실제 Excel 내용을 읽거나 변환하지 않는다.

세션 스토어는 HTTP 대역을 사용해 전환 중 이전 인증·조직 목록 제거, 진행 요청 취소, 늦은 입장 응답 무효화, 입장 실패 후 이전 actor가 남지 않는 것을 확인했다. 변경된 JavaScript/Vue script 문법 검사를 통과했다. 실제 17개 Excel 파싱·업로드 및 새 화면 브라우저 검증은 통합 작업에서 별도로 확인하며, 앞선 기획 화면 검증 결과를 새 기능의 완료 근거로 재사용하지 않는다.

### 최초 연간 제출·적재 조회 연결 검증

이번 프런트 변경은 파일을 만들지 않는 메모리 검사로 검증했다. 변경한 7개 JavaScript/script setup의 `node --check`와 공백 오류 검사가 통과했다. 모의 API에서는 검증 실패 후 같은 본문·멱등키 재시도, 검증 후 새 토큰으로 submit, PENDING 전환, 기획자의 담당자 명령 차단, 연속 원본 셀 선택의 늦은 응답 무시, 요청 표의 최신 제출본/이력 분기가 통과했다. 이는 실제 Vue 템플릿 렌더·서버 적재·원본 금액 검증을 대신하지 않는다. 실제 Docker 빌드와 브라우저의 최초 연간 최종 제출·기획 조회 검증은 통합 단계에서 확인한다.
