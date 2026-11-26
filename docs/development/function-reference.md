# 프론트·이슈 수정 및 보고서 연결 함수 명세

이 문서는 develop `5366fa8` 이후 이슈 #57·#62·#63 작업과 PR #96 반영 후 PrimeVue 전환의 함수·호출 관계를 관리한다.
모든 기존 함수의 전수 목록은 아니다. 상세 입력·출력·예외·부작용은 정의 파일의 한국어
docstring/JSDoc을 기준으로 한다.

## 통합 경로 검증

`navigation.test.agentRouteBoundaries`는 실제 메모리 라우터에서 Agent·기존 보고서·월간 경로 공존과 기획자/조직/비로그인 접근 경계를 검사한다. 경로 불일치는 AssertionError이며 API·파일·DB 접근 없이 메모리 경로만 변경한다.

## 메모리 세션 로그아웃

| 정의 | 입력·출력·호출 관계·예외와 부작용 |
| --- | --- |
| `AuthController.logout` | 인증 필터가 토큰을 기록한 HTTP 요청 → CurrentUser 확인 → AuthService.logout → 본문 없는 204. 기존 Idempotency-Key는 선택이며 무시. 인증 실패는 UNAUTHENTICATED, DB 멱등 executor·업무 저장 없이 현재 메모리 세션만 폐기 |
| `AuthLogoutTest.logoutWithoutIdempotencyKeyRevokesOnlyCurrentSessionWithoutDatabaseAccess/logoutIgnoresLegacyIdempotencyHeaderWithoutDatabaseAccess` | 실제 SessionTokenStore와 메모리 HTTP → 키 없이·기존 키와 함께 204, 해당 세션 폐기와 다른 세션 보존, 사용자·메일·프로필 저장소 미호출 검증. 실제 DB·네트워크·파일 사용 없음 |
| `AuthLogoutTest.unauthenticatedRequestCannotRevokeSession` | 인증 거부 대역 → 예외와 기존 토큰 보존 검증. 세션 폐기 전에 인증을 확인하며 DB·네트워크·파일 사용 없음 |

## 임시 모델 호출 한도

| 함수 | 입출력·호출 관계·예외·부작용 |
| --- | --- |
| `config._daily_call_limit` | `_read`로 일일 한도·선택 만료일·복구 한도를 읽어 한국 시간의 현재 적용값 반환. 만료일은 해당 날짜까지 포함하며 다음 날 복구값(기본 30) 적용. 잘못된 날짜·정수는 ValueError. 파일 변경·캐시 없음 |
| `config.get_settings` | 요청별 재정의가 없으면 `_daily_call_limit`을 호출하여 매 요청에 현재 한도 반영. 프로세스 재시작 없이 만료일 경과를 반영하며 기존 사용량은 초기화하지 않음 |
| `test_config_daily_limit` | 한국 시간 날짜 경계·명시적 복구값·만료일 없는 기존 설정·잘못된 날짜의 다섯 회귀. 파일·DB·실제 모델 호출 없음 |

- [프론트 검토·제출·이력](frontend-issue-contract.md)
- [AI 저장 경계·보고서 조회](../../ai/docs/development/ai-issue-contract.md)
- [기존 파서 함수](../../parser/docs/development/function-reference.md)
- [공개 API와 화면 연결·추가 구현](api-integration.md)
- [PrimeVue 구성·화면 전환·검증](frontend-primevue.md)
- [경영 의사결정 에이전트](pnl-decision-agent.md)

## 보고서 요청·표시·편집

정의 파일: `frontend/src/prototype/report-api.js`.

| 함수 | 입력 → 반환·검증 | 호출자·부작용 |
| --- | --- | --- |
| `manualReport(name)` | 이름 → title·metric_table을 갖는 schema_version=1 정의. 수치 결과를 만들지 않음 | `startManual`; 메모리만 사용 |
| `reportDefinition(definition)` | 이름·schema_version·블록 ID/종류/중복 검사 → JSON 깊은 복사. 오류 시 Error | 틀 열기·저장·AI 적용; 원본 변경 없음 |
| `reportRequest(api, action, input={})` | 작업별 DTO → JSON/Blob Promise. 잘못된 PDF·미지원 작업·HTTP 오류 전파 | 화면의 서버 요청 창구. RPT-01~16·페이지 이미지; 조회/저장은 action에 따름 |
| `proposalBlockKind(spec)` | AI 블록의 종류별 허용 필드·타입 검사 → kind. 수치 결과·SQL·잘못된 비교/차트 범위 거부 | `applyReportProposals`; 부작용 없음 |
| `applyReportProposals(definition, proposals)` | add/remove/move/style/column → 복사된 정의. 대상·ID·허용값 검사 | 사용자 확인·수동 블록 이동. 숫자 직접 변경·서버 저장 없음 |
| `reportRows(block)` | SQL 배열·RPT-11 객체 행·PDF 셀 행 → 머리 포함 행 배열 | 데이터/질문 미리보기·PNG 텍스트; 십진 문자열 보존 |
| `reportCell(cell)` | 값 또는 text/missing 셀 → 텍스트. null/missing은 데이터 없음 | 화면·PNG 텍스트; HTML 해석/단위 재계산 없음 |

정의 파일: `frontend/src/prototype/report-export.js`.

| 함수 | 입력 → 반환·검증 | 호출자·부작용 |
| --- | --- | --- |
| `reportLines(definition, result, base)` | 채운 결과·연월 → 정의 순서의 문자열. hide 병합 자리 제외, 빈 결과 오류 | `reportPng`; 계산·저장 없음 |
| `reportPng(definition, result, base)` | 표시 문자열 → PNG Blob Promise. 캔버스 실패·28,000px 초과 오류 | `exportPng`; 브라우저 메모리 캔버스만 생성 |

## 보고서 화면

정의 파일: `frontend/src/prototype/LiveReportPanel.vue`.

| 함수 | 호출·책임 | 반환·예외·부작용 |
| --- | --- | --- |
| `api(path, options={})` | store.api에 화면 AbortSignal 연결 | HTTP Promise·오류; 인증 요청 |
| `run(work)` | 모든 비동기 사용자 작업 직렬화 | 성공 여부; 오류를 화면에 표시; busy/error/notice 변경 |
| `loadTemplates(reset=false)`, `initialize()` | 템플릿 페이지와 기간·모든 지표·분류 초기화 | Promise<void>·HTTP 오류; 목록 변경 |
| `clearImages()` | 새 작업·unmount 시 object URL 해제 | void; 브라우저 메모리 해제 |
| `openDefinition(value, record=null)` | 정의 검증 후 결과·편집·저장 상태 초기화 | void·Error; 현재 작업 교체 |
| `loadTemplate(id)`, `startManual()` | 저장된 틀 조회 또는 기본 정의 생성 → openDefinition | Promise<void>/void·HTTP/정의 오류; 화면 변경 |
| `refreshJob()`, `uploadPdf(event)` | FileUpload의 files에서 PDF 선택 → 업로드·상태 조회 → 성공 정의와 마스킹 이미지 | Promise<void>; 실패는 run 처리; 선택 파일 UI 초기화·서버 작업 생성·인증 이미지 조회 |
| `invalidate()` | 구성·기간 변경 후 데이터·AI 제안 초기화 | void; 메모리만 변경 |
| `acknowledgeBlock(block)` | 확인 체크박스 → needs_review 해제 → invalidate | void; 확인 목록·화면 결과만 변경 |
| `changeMonth(month)` | Select 모델 값 → 기준 월·데이터·소명·질문 초기화 | void; 메모리만 변경 |
| `changePeriod(block, rule)`, `changeOffset(block, offset)` | 기간 Select·InputNumber → base/base_offset 규칙. 이동 값은 -24~24 정수 검사 | void; 잘못된 값은 화면 오류, 유효한 기간 변경 시 invalidate |
| `addBlock()`, `changeBlock(id, direction)` | 기본 지표/문장 블록 추가·이동·삭제 → invalidate | void·정의 오류; 숫자를 만들지 않음 |
| `changeYear()` | 기준 연도 기간 조회·소명/질문 결과 초기화 | Promise<void>·HTTP 오류 |
| `changeYearValue(year)` | InputNumber 모델 → 2000~9999 정수 검사 → run(changeYear) | Promise<boolean>; 잘못된 값은 요청 없이 안내, 유효하면 기준 연도 변경·조회 |
| `loadExplanations()`, `classify(item, categoryId)` | 비교 기준의 승인 소명 조회·분류 저장 후 재조회 | Promise<void>·HTTP 오류; categoryId만 본문, comparison은 query |
| `fill()` | 현재 정의·기준 월을 RPT-10으로 조회 | Promise<void>·HTTP/정의 오류; 서버값 표시·추론된 표 spec 유지 |
| `ask()`, `clarifyQuestion(option)` | RPT-11 질의 또는 기존 질문에 추가 조건 반영 | Promise<void>/void·HTTP 오류; 질문 변경 시 지난 SQL 결과 무효화 |
| `blockTitle(block)` | 서버가 기간을 치환한 제목 우선 선택 | 문자열; 부작용 없음 |
| `registerMetric()`, `addMetric(id)` | 확인한 SQL 등록 → metricId 참조 블록 추가 | Promise<void>/void·HTTP/SQL 오류; 지표 사전 저장·정의 변경 |
| `requestEdit()` | 현재 정의와 기준 월을 RPT-15로 전달 | Promise<void>·HTTP 오류; 적용 전 제안 보관 |
| `acceptEdit()`, `undoEdit()` | 제안 시점 정의 확인 → 패치 적용·재조회 / 마지막 패치 복원 | Promise<void>/void·오래된 제안/HTTP 오류; 메모리 변경 |
| `saveTemplate()` | 신규 틀 또는 expectedVersion을 지정한 다음 버전 저장 | Promise<void>·HTTP/버전 충돌; 서버 버전 저장 |
| `exportExcel()` | 저장된 틀 ID·버전·기준 월로 RPT-16 호출 | Promise<void>·미저장/HTTP 오류; 사용자 브라우저 다운로드 |
| `exportPng()` | reportPng → saveFile | Promise<void>·캔버스 오류; 사용자 브라우저 다운로드 |
| `printReport()` | report-preview에 인쇄 스타일 적용 → window.print | void·브라우저 오류; 대화상자, 종료 시 스타일 해제 |
| `blockStyle(block)` | family·8~40pt·bold → 표시 스타일 | 객체; 부작용 없음 |

작업 중 PrimeVue 입력·실행 버튼을 비활성화하고 화면을 떠나면 요청을 취소한다. 저장과 사용자 다운로드는
제품 기능이며, 개발 검증에서 해당 함수를 실행해 파일을 생성하지 않는다.

## PrimeVue 설치·메뉴·전역 안내

| 정의 파일·함수 | 입력 → 반환·검증 | 호출자·부작용 |
| --- | --- | --- |
| `prototype/primevue.js::installPrimeVue(app)` | 마운트 전 Vue 앱 → 같은 앱. 플러그인 설치 오류 전파 | `prototype/main.js`; Aura 기반 blue preset·한국어 locale·ToastService 등록. CSS 레이어는 legacy → primevue → app |
| `PrototypeApp.vue::selectMenu(item)` / `itemSelected(item)` / `menuSelected(id)` | 메뉴·조직 작업공간 → 이동 또는 활성 여부 | Menu item 슬롯; selectMenu만 활성 조직·라우트 변경, 나머지는 읽기 전용 |
| `navigate(id)` / `focusMain()` | 라우트 이름 / 없음 → void | 메뉴·화면 이벤트 / 건너뛰기 링크; 경로 또는 DOM 초점 변경 |
| `localSignIn(account)` / `loadLocalCode(response)` | 로컬 계정·발급 응답 → Promise<void> | 개발 로그인; 실제 인증 HTTP 호출·표시 상태 변경. 개발 환경에서만 실행 |
| `requestCode()` / `resendCode()` / `changeEmail()` | 현재 인증 입력 → Promise<void> 또는 void | 로그인 폼; 코드 요청·대기시간 확인·단계 초기화, 오류는 화면 안내 |
| `signIn()` / `logout()` | 현재 인증 상태 → Promise<void> | 로그인 폼 / 확인 Dialog; 서버 로그인·로그아웃·권한별 이동. 로그아웃 성공 시 Dialog 닫기 |
| `FeedbackToast.vue::showFeedback(current)` | store 알림 또는 null → void | store.toast watch; feedback 그룹 교체. 오류는 닫기 전까지, 일반 안내는 7초 표시 |
| `dismissFeedback(event)` | close/life-end 이벤트 → void | Toast; 이벤트 sourceId와 일치하는 store 알림만 해제 |

위 표에서 `prototype/`는 `frontend/src/prototype/`를 뜻한다. 메뉴·인증·알림의 상세
예외 처리와 부작용은 각 SFC의 JSDoc에 기록한다.

## 홈·연간 관리·알림 표시

정의 파일은 `frontend/src/prototype/LiveHomePanel.vue`와 `LiveManagementPanels.vue`다.

| 파일·함수 | 입력 → 반환·검증 | 호출자·부작용 |
| --- | --- | --- |
| Home `buildFinancialRows()` / `financialRowClass(row)` | 손익 응답 → 이익률을 포함한 행 / 강조 클래스 | financialRows computed / DataTable rowClass. 원문 금액·원본 응답 변경 없음 |
| Home `buildWaterfallChart()` / `buildDriverChart()` | 서버 손익·원인 목록 → labels·datasets | 두 computed → Chart. Number 변환은 기하 좌표에만 적용 |
| Home `chartTick(value)` | Chart.js 축 좌표 → 억원 눈금(최대 소수 6자리) | 축 ticks callback. 업무 금액 표시·계산에 사용하지 않음 |
| Home `waterfallTooltip(context)` / `driverTooltip(context)` | dataIndex → 서버 원문 금액 표시 | tooltip callback. 부동소수점 좌표를 금액으로 다시 사용하지 않음 |
| Management `validYear(value)` | 입력 → 2000~9999 정수 여부 | load/send/loadArchive. null·문자열·소수는 유효 연도로 처리하지 않음 |
| Management `load()` / `send()` / `loadArchive()` | 현재 화면·선택·유효 연도 → Promise<void> | watch / 요청 폼 / 조직·월 선택. 기존 API 조회·요청 수행, 오류 안내와 이전 응답 차단 유지 |
| Management `changeArchiveYear(value)` | InputNumber의 숫자/null → Promise<void> | update:modelValue → loadArchive. 기준 연도 변경·읽기 조회 |
| Management `annualExpansion()` / `annualRowClass(target)` / `versionRowClass(version)` | 펼친 조직·선택 버전 → 확장 사전 / 강조 클래스 | computed·DataTable. ID를 숫자로 변환하지 않음 |
| Management `flattenArchiveChanges()` | 날짜별 변경 이력 → changedAt을 추가한 복사 행 | archiveChanges computed → DataTable. 원본 소명·항목 보존 |
| Management `bulkAccept()` | 확인한 대기 조직 → Promise<void> | Dialog 수락 버튼. Dialog 닫기·제출본 재조회·순차 승인·결과별 안내 |

`LiveNotifications.vue`는 기존 조회·읽음 처리 함수를 유지하며 필터를 Button·Checkbox,
알림을 Card·Tag로 표시한다. 도달하지 않는 Management의 옛 보고서 placeholder와
`hideNumbers` 상태는 제거했으며 보고서 메뉴는 `LiveReportPanel`을 사용한다.

## 월간 계층·버전 비교

| 정의 파일·함수 | 입력 → 반환·검증 | 호출자·부작용 |
| --- | --- | --- |
| `prototype/monthly-view-model.js::buildGapTree(items, level=0, path=[])` | 검토 항목 → 대·중·소 TreeNode와 원본 leaf | LiveGapTable computed. 재귀 분류·exact-money.sum 합계, ID/stateToken·null 보존, 원본 변경 없음 |
| `canSelectGapItem(row, editable)` | 항목·편집 허용 → boolean | LiveGapTable.selectable. 검토 필요/검토 완료만 선택 가능 |
| `groupVersionRows(rows)` | 원천 셀 행 → 시트별 연속 행 | LiveReviewDetail.sourceRows. 셀·버전 금액 배열은 보존 |
| `LiveGapTable.vue::money(value)` / `rate(row)` / `delta(row)` | 원문 금액 → 단위·차이율·부호 표시 | TreeTable 셀. formatMoney·percent·subtract 사용 |
| `selectable(row)` / `severity(status)` / `expandGroups(nodes)` | 업무 항목·상태·트리 → 선택 여부·Tag 색상·void | Checkbox·Tag / 결과 watch. expandGroups만 expandedKeys 변경 |
| `MonthlyPanel.vue::signed(value, displayUnit)` | 차액 문자열 → 부호·표시 단위 | DataTable 셀; 정확한 비교·표시, 부작용 없음 |
| `openCalculatorDetail(item)` / `toggleOrganization(org)` | 항목·조직 → void | 상세 버튼 / 조직 행. 원래 화면을 보존해 이동, 조직 행 확장·최신 제출본 조회 |
| `selectRecipients(checked)` / `downloadMonthlySubmission(org)` | Checkbox boolean / 조직 → void 또는 Promise<boolean> | 요청 대상 전체 선택 / 파일 버튼. 선택 ID 목록 변경 또는 기존 제출 파일 다운로드 |
| `excludeSelected()` | 선택 항목·제외 사유 → Promise<void> | 제외 버튼 → store.excludeItems. 성공 후 선택·사유 초기화, 실패 시 입력을 유지하고 화면 안내 |
| `LiveReviewDetail.vue::state(value)` / `verdict(value)` / `date(value)` / `signed(value)` | 서버 상태·시각·금액 → 표시 문자열 | 표·Panel·상세 요약. 서울 시각·원문 금액 보존 |
| `loadDetail(id)` | reviewItem ID → void | id watch → store.openDetail. 이전 상세·근거·버전·메모 초기화, 조회 오류 표시 |
| `downloadAttachment(file)` / `decideExplanation(decision)` | 첨부 DTO / ACCEPT·REQUEST_AGAIN → Promise<boolean> | 버튼 → store.run. 권한이 있는 파일 다운로드 또는 소명 결정·상세 재조회 |

기존 월간 계산·제출 승인·소명 요청 API는 유지한다. TreeTable의 전체 선택은 선택 가능한
leaf ID만 사용하고, 제출본이 승인됐거나 처리 중이면 선택을 막는다. 분류 합계와 차액은
십진 문자열 연산이며 PrimeVue의 숫자 정렬·합계로 바꾸지 않는다.

## 조직 파일 선택·제출·소명

| 정의 파일·함수 | 입력 → 반환·검증 | 호출자·부작용 |
| --- | --- | --- |
| `lib/org-upload.js::singleFileSelection(event, extension, maxBytes, message)` | FileUpload 이벤트·형식·한도 → file/error | 두 공개 선택 함수. originalEvent의 다중 선택도 거부; 파일 읽기·업로드 없음 |
| `submissionFileSelection(event)` / `evidenceFileSelection(event)` | 파일 선택 → file/error | LiveOrgPanel / CurrentSubmissionReviews. 제출 XLSX/XLSB 20MiB와 소명 PDF/XLSX/PNG/JPEG 16MiB 구분 |
| `LiveOrgPanel.vue::chooseFile(event)` / `clearFile()` | 선택 이벤트 / 없음 → void | FileUpload select/clear. 파일·오류 상태만 변경 |
| `statusSeverity(value)` / `date(value)` / `toggleHistory(id)` / `closePreview(visible)` | 상태·시각·ID·Dialog 모델 → 표시값 또는 void | Tag·DataTable·Dialog. 펼침/미리보기 상태만 변경; 처리 중 확인창 닫기 방지 |
| `resetHistory()` / `open(item)` / `reload()` | 현재 필터 / 요청 DTO / 없음 → void 또는 Promise<void> | 필터 초기화·요청 열기·상태 새로고침. 활성 요청·화면 변경 또는 서버 최신 토큰 조회 |
| `upload()` / `validate()` / `confirm()` / `submit()` / `template()` | 현재 선택·제출 상태 → Promise<void> | 검증 시작·검증·미리보기·확정·양식 버튼. 기존 요청/상태토큰 계약과 사용자 다운로드 유지 |
| `CurrentSubmissionReviews.vue::chooseAttachment(event)` / `clearAttachment()` / `attach()` | 파일 선택 / 없음 → void 또는 Promise<void> | FileUpload·첨부하기 버튼. 선택만으로 전송하지 않으며 첨부 시 token·Idempotency-Key 유지 |
| `load()` / `select(id)` / `command(action)` / `download(item)` / `dispose()` | 현재 제출·reviewItem·명령·첨부 → Promise<void> 또는 void | 조회·저장/검토·파일 버튼·unmount. HTTP 취소·세대 번호·권한 검사 유지 |
| `CurrentSubmissionHistory.vue::load(id)` / `download()` / `eventLabel(event)` / `dispose()` | 제출 ID·처리 이벤트 → Promise<void>·문자열·void | 확장 행. HIST-02 조회·원본 다운로드·이벤트 표시·요청 취소; 다운로드 시점 파일명·signal 보존 |

`lib/`와 컴포넌트 이름은 `frontend/src/` 아래 경로다. 제출 파일은 선택 후 **검증 시작**,
소명 근거 파일은 선택 후 **첨부하기**를 눌러 전송한다. 보고서 PDF 선택·분석은 기존처럼
선택 이벤트에서 분석 작업을 요청하는 별도 흐름이다.

## 보고서 표·차트 어댑터

| 정의 파일·함수 | 입력 → 반환·검증 | 호출자·부작용 |
| --- | --- | --- |
| `lib/report-display.js::reportTableModel(data)` | PDF/SQL 결과 → headers·rows·width | ReportDataTable computed. header_rows·hdr·hide와 colspan/rowspan 좌표·원문 값 보존 |
| `reportBodyCell(row, column)` | 원본 cells·열 좌표 → colspan/rowSpan/style | Column pass-through. hide는 display:none, 원본 좌표 보존. rowSpan 표기로 PrimeVue의 소문자 rowspan=null 덮어쓰기 방지 |
| `reportChartModel(block)` | chart_type·name/won/text → type·labels·values·rows | ReportChart computed. bar/line/donut/waterfall 좌표만 Number, 누락값은 null 유지 |
| `components/ReportDataTable.vue::columnPassThrough(column)` | 열 좌표 → bodyCell callback 설정 | Column. 현재 parent.props.rowData와 원 열 좌표를 reportBodyCell에 전달 |
| `components/ReportChart.vue::exactTooltip(context)` | Chart.js dataIndex → 원문 text | tooltip callback. 표에도 원문 text 표시; 기하 좌표를 업무 금액으로 사용하지 않음 |

두 어댑터는 서버를 호출하거나 파일을 만들지 않는다. 차트 waterfall은 서버가 전달한
부호 있는 증감 행을 누적하는 표시 모델이며 새 업무 손익을 계산하거나 저장하지 않는다.

## 회귀 검증

`frontend/src/prototype/report-api.test.js`는 인증 요청을 메모리 대역으로 검사하고
실제 서버·PDF·캔버스를 만들지 않는다. 분류 query, 저장 버전 충돌 조건, 파일 검증,
십진 문자열과 누락값, AI 패치의 안정된 ID·숫자/SQL 금지, PNG 텍스트·병합 셀을 확인한다.

## 경영 의사결정 에이전트 계산·의사결정서

공개 호출은 `LiveDecisionAgentPanel → decisionRequest → DecisionAgentController →
DecisionAgentService`로 연결된다. 수치는 Spring에서만 계산하고, 설명 요청은
`DecisionNarrativeService → /v1/decision-agent/compose → compose_analysis`로 전달한다.
아래 표와 각 정의의 한국어 Args/Returns/Raises/Side Effects를 함께 본다.

| 백엔드 정의 (`domain/decisionagent/`) | 입력·출력·호출 관계와 부작용 |
| --- | --- |
| `controller/DecisionAgentController.baselines/list/get` | 현재 PLANNER의 기준·목록·분석 조회 → service. DB 읽기, 권한/입력/미소유 오류 |
| `create/candidates/run` | 인증 사용자·Idempotency-Key·검증 DTO → IdempotentCommandExecutor → service. 변경과 성공 응답을 같은 트랜잭션으로 기록 |
| `service/DecisionAgentService.baselines/list/get` | 역할 확인 후 loader/store 호출. 타인 분석은 404 |
| `create` | 목표·보호 범주 검증 → baseline.load → store.create. 기준을 복사한 DRAFT revision 1 생성 |
| `candidates` | current → calculator.validate → changed → store.update. 후보 전체 교체, result/narrative 무효화 |
| `run` | current → calculator.calculate → narratives.compose → changed → store.update. 계산 완료 상태 저장 및 내부 AI 요청 |
| `current/changed` | 소유 분석 행 잠금·revision 비교 / 불변 기준을 보존한 다음 상태 구성. 409 또는 다음 revision |
| `service/DecisionBaselineLoader.list/load` | 연도 목록 / 실제 master_source의 제출·조직·12개월 직접 재무 행 확인 후 Snapshot. 누락을 0으로 채우지 않음. DB 읽기 |
| `service/DecisionScenarioCalculator.amount/validate` | 원 단위 문자열 및 후보 의미 검증 → BigDecimal/void. 소수 6자리·양수 목표·근거·조직 오류 거부 |
| `calculate` | snapshot·목표·보호 범주·후보 → 검토/가정 효과·잔여 Gap·제외 사유. 메모리 정밀 계산만 수행 |
| `decimal/gap/invalid` | 지수 없는 문자열 / 음수가 아닌 부족액 / INVALID_REQUEST 구성. 부작용 없음 |
| `service/DecisionAnalysisStore.list/get` | ownerId를 모든 WHERE에 적용. 목록 / JSON 복원 상태, 수정 시 행 잠금 |
| `create/update` | 기준과 상태 생성 / ownerId·expectedRevision으로 가변 상태만 갱신. DB 생성·변경 |
| `encode/decode` | Jackson 직렬화/복원. 변환 실패는 IllegalStateException |
| `service/DecisionNarrativeService.compose/validTexts/fallback` | 계산 결과 전달·설명 계약 검사·장애 시 TEMPLATE 구성. compose만 내부 HTTP 사용. 결과 수치는 변경하지 않음 |

정의 디렉터리는 `backend/src/main/java/io/skala/backend/` 기준이다.
`DecisionAgentDtos` record와 enum은 공개·내부 계약을 정의하며 생성 부작용이 없다.
Flyway `V20261126_0900__decision_agent_analysis.sql`은 분석 테이블과 불변 기준 trigger를
추가한다. 스키마가 배포되기 전에는 새 API를 사용할 수 없다.

| 통합 테스트 정의 (`backend/src/test/java/io/skala/backend/domain/decisionagent/`) | 입력·출력·호출 관계와 부작용 |
| --- | --- |
| `DecisionAgentIntegrationTest.setUp` | 요청·대상 조직 등록 → 승인 제출본·master_source → 서로 다른 원천 행의 12개월 기준 생성. `TEST_DB_URL`이 설정된 테스트 DB와 메모리 세션만 변경하며 파일은 만들지 않음 |
| `DecisionAgentIntegrationTest.values` | 계정·월별 원 금액·중복 없는 양수 원천 행 번호 → 재무 행 ID. 같은 제출본·시트의 원천 행 유일성을 지키며 테스트 DB에 12개월 실적·추정 생성; 중복 행이나 DB 오류는 실패로 전파 |

| AI 정의 (`ai/app/`) | 입력·출력·호출 관계와 부작용 |
| --- | --- |
| `api/contract.read_decision_request` | 인증된 Request stream → 엄격한 DecisionComposeRequest. 실제 본문 256KiB 초과 413, 계약 오류 422. 메모리 읽기 |
| `decision_agent_compose` | 검증 DTO → threadpool의 compose_analysis → narrative. 연결 오류 422 |
| `decision_agent/models.py` | StrictStr 금액·ID, StrictBool 표시, StrictInt 월 등 DTO와 필드 상한. 입력 금액과 최대 14자리 소수 계산 결과를 구분 |
| `core.validate_context` | 기준/목표 값·후보/효과/조직 연결·시간대 검사. 불일치 ValueError, 재계산·조회 없음 |
| `core.build_catalog/validate_selection/render_narrative` | 고정 결과의 허용 요약·안건 사전 → 모델 선택 ID 검증 → 원문 수치에 연결된 설명. 임의 본문·누락 안건 거부 |
| `graph.append_step` | 기존 상태에 실제 수행 단계를 추가한 복사 목록. 메모리만 사용 |
| `graph.context_node/evidence_node` | 입력 연결 확인 / 입력된 근거 상태·제외 사유를 허용 문장 사전으로 정리. 증빙 외부 조회 없음 |
| `graph.model_selection/narrative_node` | 설정·호출 한도 확인 후 허용 ID 선택을 최대 1회 요청. 실패·미설정·계약 위반은 TEMPLATE, 사용량은 메모리 기록 |
| `graph.decision_node/response_node` | 안건 누락 재확인 / 최종 narrative. 실제 발송·승인 없음 |
| `graph.build_graph/compose_analysis` | 다섯 단계 그래프 컴파일 / 한 요청의 타입·연결 검증과 그래프 실행. 체크포인터·DB·파일 없이 요청별 메모리 사용 |

| 프론트 정의 (`frontend/src/prototype/`) | 입력·출력·호출 관계와 부작용 |
| --- | --- |
| `decision-agent-api.exclusionText` | 제외 코드 목록 → 한국어 사유. 알 수 없는 값은 사유 확인 필요로 안내 |
| `decimalText/eokToWon/wonToEok` | 십진 문자열 정규화·소수점 이동. Number 변환·반올림 없이 원/억원 변환, 범위 오류는 Error |
| `emptyCandidate/candidateDraft/candidatePayload/analysisPayload` | 빈 후보·서버 후보 → 편집 복사 / 검증된 후보·목표 요청. 실제 후보 금액·조직은 사용자가 입력 |
| `decisionRequest` | 작업명과 DTO → 6개 공개 operation의 인증 요청. 조회·생성·저장·실행에 따른 서버 부작용 |
| `LiveDecisionAgentPanel.hasChanges/currentResult` | 편집 지문·저장 응답 → dirty·표시 가능한 결과. 미저장 편집 시 과거 결과 숨김 |
| `remainingMonths/effectRows(findCandidate)/progressSteps/analysisOptions` | 고정 월·서버 효과·저장 상태 → 선택지·후보 연결 행·실제 진행 단계·분석 목록. 계산 결과 변경 없음 |
| `constraintText/money/organizationName` | 보호 범주·정밀 금액·조직 ID → 사용자 표시. 숫자는 문자열로 보존 |
| `api/run` | 화면 AbortSignal 연결 / 직렬 실행·오류 표시. HTTP 요청 및 busy 상태 변경 |
| `acceptAnalysis(matchesAnalysis)` | 서버 응답 → 현재 분석·편집 후보·저장 지문·목록 갱신 |
| `readBaselines/refreshBaselines/readInitialData/initialize` | 기준·분석 목록 읽기. 부분 실패 안내와 마스터 부재를 구분 |
| `switchAnalysis/confirmDiscard/discardChanges` | 미저장 전환 확인 / 확인한 전환 수행 / 마지막 저장 후보 복원. 서버 변경 없음 |
| `createWork/createAnalysis/requestCreate` | 목표 검증·새 분석 생성 → 서버 응답 적용. 미저장 후보가 있으면 확인 후 전환 |
| `loadWork/loadAnalysis/requestLoad` | 선택 ID → 저장 분석 조회·적용. 후보 변경 유실 전 확인 |
| `addCandidate/removeCandidate/saveWork/saveCandidates` | 후보 편집 / 최신 revision 조건으로 후보 전체 저장. 저장 시 이전 계산 무효화 |
| `calculateWork/calculate` | dirty면 먼저 저장 → 저장 응답 revision으로 run → 의사결정서 표시 |
| `guardNavigation/warnBeforeUnload/mountPanel/disposePanel` | 미저장·실행 중 이탈 안내 / router.beforeEach·beforeunload 등록·해제와 요청 취소. RouterView 밖 패널 구조에 맞춰 전역 guard를 수명 동안만 등록 |
| `PrototypeApp.loadDecisionAgentPanel` | 메뉴 진입 시 새 패널 동적 import. 기존 역할별 route guard 유지 |

신규 검증은 `backend/.../decisionagent/*Test.java`, `ai/tests/test_decision_agent*.py`,
`frontend/src/prototype/decision-agent-api.test.js`에 있다. 테스트 생성과 실제 실행 여부는
[에이전트 개발 문서의 검증 기록](pnl-decision-agent.md)에서 구분한다.

## 고정 근거 탐색과 자연어 목표 제안 — AI

목표 제안은 `POST /v1/decision-agent/interpret-goal → interpret_goal`로 호출한다.
근거 탐색은 `POST /v1/decision-agent/discover → discover_analysis → DISCOVERY_GRAPH`로
이어지며, 고정 목록에서 읽을 근거를 선택한 뒤 Spring의
`POST /internal/decision-agent/evidence/read`를 한 번 호출한다. AI는 DB를 직접 조회하지 않는다.
목표와 후보는 사용자 확인용이며 자동 저장·추진 승인·계산 반영을 하지 않는다.

| AI 정의 (`ai/app/`) | 입력·출력·호출 관계와 부작용 |
| --- | --- |
| `api/contract.read_agent_payload` | 내부 인증된 Request·요청 DTO → 메모리 stream 읽기·Pydantic 검증. 실제 본문 256KiB 초과 413, JSON·필드 오류 422. 본문·토큰 로그 없음 |
| `read_goal_request/read_discovery_request` | Request → 위 reader의 GoalInterpretRequest / DiscoveryRequest. 사용자 지정 URL·알 수 없는 필드 거부 |
| `decision_agent_interpret_goal` | 검증 요청 → threadpool의 interpret_goal → `{mode, goalProposal}`. 의미 오류 422, 모델 최대 한 번 |
| `decision_agent_discover` | 검증 요청 → threadpool의 discover_analysis → `{mode, goalProposal, hypotheses, limitations, steps}`. 연결 오류 422 |
| `decision_agent/discovery_models.py` | 목표·인용·근거 목록·읽기 응답·가설 DTO. ID·금액은 문자열, 제안은 확인 필수. 가설 효과 금액·반영 월은 null만 허용 |
| `discovery_core.money_values` | 원문 금액·통화 단위 → 정확한 원 단위 문자열. 부호·지수 표기·지원 범위 밖 값은 추출하지 않음. 업무 효과 계산 없음 |
| `fallback_goal` | 원문·선택 연도·기존 보호 범주 → 보수적 기본 목표 제안. 복수 금액·취소 표현·다른 지표는 자동 목표 확정을 보류하고 보호 조건은 키워드로 추가하지 않음 |
| `validate_goal` | 모델 목표·원문·선택 연도 → 인용과 단위가 일치하는 확인용 목표. 원문 없는 숫자·연도·인용은 ValueError. 보호 부정·예외는 자동 제안에서 제외 |
| `validate_discovery_context` | 스냅샷·허용 목록 → 조직·ID 중복·기준일 연결 검사. 불일치 ValueError, 외부 조회 없음 |
| `default_references/validate_references` | 허용 목록 → 손익·승인 소명 기본 선택 / 모델 선택 ID 검사. 최대 20개, 목록 밖 ID·중복·의미 없는 빈 조회 거부 |
| `validate_read_result` | 읽기 응답·분석·요청 ID → 분석 ID·revision·근거 집합·kind·조직·label 대조. 응답 순서와 무관하게 같은 집합인지 검사 |
| `fallback_hypotheses` | 실제 읽은 근거 → 조직별 최대 다섯 개 기본 확인 질문. 원문을 인용하며 개선액·실현 시점을 추정하지 않음 |
| `validate_discovery_proposal` | 모델 목표·가설·읽힌 자료 → 인용·참조·조직 검증. 본문 숫자·효과 확정 주장·새 금액 필드 거부. 보호 범주 가설에는 제약 영향 확인 질문 추가 |
| `discovery_tools.read_evidence` | 분석·revision·허용 ref·설정 → 고정 내부 URL의 읽기 POST → 대조된 근거. 공유 내부 토큰과 단기 capability 사용. 15초·응답512KiB 제한, 리디렉션·환경 프록시·증빙 URL 접근 없음 |
| `discovery_graph.step` | 실행 상태 → 실제 수행 내용·상태를 추가한 새 단계 목록. 메모리만 사용 |
| `call_model` | 허용된 원문 자료·설정 → 공통 llm.ask_json JSON 또는 기본 처리 사유. 키·일일 한도 확인, 호출별 최대 20초·재시도 없음, 사용량 메타데이터만 기록 |
| `discovery_context_node/reference_plan_node` | 고정 연결 검사 → 허용 ref 선택. 조회 계획 모델 최대 한 번, 실패 시 기본 선택. 일부 자료만 선택하면 범위 한계 표시 |
| `evidence_read_node` | 선택 ref·닫힌 문맥의 reader → 읽기 Tool 한 번·응답 재검증. 실패는 FAILED 단계와 빈 근거로 표시하고 오류 원문은 숨김 |
| `hypothesis_node` | 읽힌 자료·원문 → 모델 목표·가설 제안 최대 한 번 → 인용·숫자 검증. 실패 시 실제 근거의 기본 질문, 근거 없으면 빈 가설. 자료별 본문 앞 2000자만 모델에 제공하며 잘림을 설명 |
| `discovery_response_node` | 검증된 제안 → 독립 탐색 응답. 효과 금액·반영 월 null, needsConfirmation=true 유지 |
| `build_discovery_graph/discover_analysis/reader` | 다섯 단계 그래프 구성 / 요청 검증·실행 / capability를 닫힌 문맥에만 보관한 내부 읽기. 토큰은 그래프 상태·모델·응답에서 제외. DB·파일·체크포인터 없음 |
| `interpret_goal` | prompt·year → 원문 단위를 대조한 `{mode, goalProposal}`. 모델 최대 한 번, 기본 처리 사유는 uncertainties. Tool·DB 접근 없음 |

`ai/tests/test_decision_discovery.py`의 합성 fixture, 순수 검증, 실제 LangGraph,
httpx 전송 대역, 실제 FastAPI 라우터 검증이 위 경계를 확인한다. 외부 모델과 DB를
호출하지 않으며 모델 없는 경로에서도 근거 읽기를 거친 뒤에만 후보 질문을 만든다.

## 고정 근거 조사와 조직 워크플로 — backend

정의 경로는 `backend/src/main/java/io/skala/backend/` 기준이다. 각 메서드의 한국어
Args/Returns/Raises/Side Effects와 [상세 API 계약](../api/decision-workflow.yaml)을 함께 본다.
조사·요청은 기존 제출/소명 알림과 별도 테이블을 사용한다. AI는 읽기 권한만 받고,
조직 요청 발행·회신·검토·외부 결정 기록·후속 진행은 인증된 사용자의 Spring API 명령으로 처리한다.

| 조사 정의 (`domain/decisionagent/`) | 입력·출력·호출 관계와 부작용 |
| --- | --- |
| `controller/DecisionResearchController.goal/get/discover` | PLANNER·목표 또는 분석 ID/revision → service. goal은 헤더 없는 읽기 전용 해석으로 멱등·업무 DB 저장 없음, 기존 Idempotency-Key는 무시. discover만 멱등 실행. get은 미조사 204. 입력 검증과 역할 검사를 거친 뒤 service에 위임 |
| `DecisionResearchController.read` | 내부 토큰 + 단기 capability + 분석/revision/refIds → ai.acceptsToken → evidence.read. 권한 오류 403, DB·상태 변경 없음 |
| `service/DecisionDiscoveryService.interpretGoal` | PLANNER·prompt/year → ReportAiClient.post(내부 interpret-goal) → validGoal → 미확인 목표. 실패 시 null 목표와 확인 안내. AI HTTP와 메모리 검증만 수행하며 분석·연구·멱등 저장소와 DB 트랜잭션을 사용하지 않음 |
| `get/findHypothesis` | 소유 분석 확인 → research.find → 저장된 Research 또는 선택 Hypothesis. 미존재 404, DB 읽기 |
| `discover` | 분석 행 잠금·revision 대조 → research.capture → evidenceTool.grant → 내부 discover → validated 또는 fallback → research.save, finally revoke. 고정 근거와 마지막 가설 DB 저장; Analysis.revision은 유지 |
| `validated` | AI 제안 + 허용 근거 → 목표·인용·참조·조직·null 금액 검사 후 서버 가설 ID 부여. 부적합 응답은 IllegalArgumentException. UUID 생성만 수행 |
| `fallback` | 실제 고정 근거 → 조직별 확인 질문 최대 12개. 금액/시점 null, 근거 없는 개선 효과를 생성하지 않음. UUID 생성 |
| `validGoal/text/texts` | 제안의 양수 목표·연도·보호 범주·확인 플래그와 문장 배열 상한 → boolean. 부작용 없음 |
| `service/DecisionResearchStore.find` | 분석 ID → 저장된 근거·가설 복원 또는 null. DB 읽기 |
| `capture/assertSameAmounts` | 최초 조사 시 기존 baseline 재조회 → 제출 ID/조직/원 단위 금액 대조 → loadEvidence → 불변 근거 저장. 원천 변화는 409; 기존 조사는 재사용 |
| `loadEvidence` | 정확한 고정 제출본 → PNL 최대 100항목 + 수락 소명 최대 50개. PNL 계획은 동일 제출본의 12개월이 완전할 때만 비교. DB 읽기 |
| `save` | 고정 Research + 실행 분석 revision + 가설 → discovery/revision만 교체. 근거/capturedAt은 유지. DB 갱신 |
| `encode/decodeEvidence/decodeDiscovery` | JSON 직렬화/복원. 손상 시 IllegalStateException; 외부 부작용 없음 |
| `service/DecisionEvidenceTool.grant` | 분석/revision/복사 근거 → 3분 capability. 만료 항목 정리, 동시 상한 200, 프로세스 메모리 변경 |
| `read` | capability + 분석/revision/1~20개 고유 ref → 허용 근거만 반환. 만료·범위·중복 오류 403, DB 접근 없음 |
| `revoke` | 종료한 capability → 메모리 권한 삭제. null은 무시 |

`DecisionResearchControllerTest` 6개는 실제 MVC 입력 검증과 목표 해석 서비스에 AI 대역을 연결한다.
멱등 헤더 없는 호출, 기존 헤더 무시·응답 재생 없음, 원문·연도 검증, 기획자 역할과 인증 요구,
AI 실패 대안을 확인하고 모든 경우에 멱등 실행·분석·연구 저장소가 호출되지 않음을 검증한다.
실제 DB·모델·외부 네트워크를 사용하지 않는다.

| 워크플로 정의 (`domain/decisionworkflow/`) | 입력·출력·호출 관계와 부작용 |
| --- | --- |
| `controller/DecisionWorkflowController.list/get/followupBaselines` | 현재 사용자·요청/선택 분석 ID → service 조회. 본인 소유 또는 ISSUED 이력이 있는 자기 조직 범위 |
| `create` | PLANNER·분석 ID·후보/가설 요청 → idempotency.execute → service.create → 201. 초안 DB 생성 |
| `issue/reply/review/decide/progress/cancel` | 명령 DTO → mutate → 대응 service. 역할별 변경 API이며 실제 외부 전송 없음 |
| `mutate` | 현재 역할 및 service.get의 조직/소유권 검사 → 멱등 실행. 이전 성공 키가 있어도 변경된 조직 권한을 우회하지 못함 |
| `service/DecisionWorkflowService.list/get/followupBaselines` | 역할 범위를 store에 적용. 후속 기준은 같은 연도·기준월 이후·해당 조직 master만 제시. DB 읽기 |
| `create` | 분석 잠금/revision 확인 → sourceCandidate 또는 discovery.findHypothesis 사본 → 조직 범위 확인 → store.create. 가설 요청의 candidateId는 서버 UUID, 금액 후보는 생성하지 않음 |
| `issue` | lockPlanner → DRAFT·원본 후보·미래 기한 확인 → REQUESTED 및 ISSUED 이벤트. 인앱 요청함 상태만 변경 |
| `reply` | ORG_MANAGER + 요청 잠금 → 상태/revision/조직 검사 → FEASIBLE 후보를 ASSUMPTION으로 정규화 → calculator.validate(localSnapshot) → REPLIED. NOT_FEASIBLE은 후보 없이 근거만 저장 |
| `review` | lockPlanner → 판정. ACCEPT는 policy.acceptedCandidate → analysisService.candidates로 추가/교체; REQUEST_CHANGES는 round 증가; REJECT는 removeLinkedCandidate. 요청 감사 이력 저장 |
| `decide` | ACCEPTED·두 revision 확인 → APPROVE는 policy.approvable 및 해당 후보 순효과 고정, DECLINE은 연결 후보 제거. 외부 결정자/증빙/기한 기록, 실제 임원 인증이나 결재 실행 없음 |
| `progress` | 자기 조직·APPROVED/IN_PROGRESS → 선택 후속 baseline.load → policy.followupOrganization → 담당 조직 연간 전망 차이 고정. 완료는 후속 master 필수이며 과제 인과 효과 확정이 아님 |
| `cancel` | 소유 분석·요청 잠금 → 허용 미완료 상태 확인 → 연결 후보 제거 및 CANCELLED 이력. 수정된 새 후보는 409로 보호 |
| `lockPlanner` | 소유 요청 참조 → 분석 행 → 요청 행 순서로 잠금, 요청/선택 분석 revision 대조. 403/404/409 |
| `save` | 기존 이력 + 이벤트 한 건 → 요청 revision+1 → store.update. 감사 이벤트 상한 500; DB 갱신 |
| `removeLinkedCandidate` | 현재 후보와 요청/수락 사본 대조 → 같은 후보만 제거. 이미 없으면 분석 유지, 새 편집이면 409. 제거 시 분석 revision 증가와 result/narrative 무효화 |
| `localSnapshot/positiveId` | 자기 조직만의 후보 검증 문맥 / 양의 후속 master ID. 외부 조회 없음, 잘못된 ID는 INVALID_REQUEST |
| `service/DecisionWorkflowPolicy.revision/state` | 버전·선행 상태 → 허용 여부. 불일치 409, 부작용 없음 |
| `unchangedSource/acceptedCandidate/approvable` | 원본 후보 일치 / 실현 가능·근거 회신의 REVIEWED 변환 / 최신 완료 계산에 동일 검토 후보가 포함됐는지 검사. 부적합 400/409 |
| `futureDeadline/followupOrganization/text` | 미래 시각 / 같은 연도 후속 월·담당 조직 / 입력 길이 검증. 부적합 400, 부작용 없음 |
| `service/DecisionWorkflowStore.list/get/scope` | 기획자는 owner_id, 조직은 organization_id와 ISSUED 이벤트 조건을 모든 조회/변경에 적용. 발행 전 취소 초안도 조직에 숨김 |
| `create/update` | 후보·가설별 진행 요청 중복 방지 / 예상 revision 조건 갱신. 상태 JSON·식별자·시각 DB 저장. 경쟁 변경 409 |
| `followupBaselines` | 권한 검증된 요청 → 같은 연도 후속월 master 목록. sourceCount는 해당 조직 원천 수 1, DB 읽기 |
| `encode/decode` | WorkItem JSON 직렬화/복원. 실패 시 IllegalStateException |

`DecisionResearchDtos`와 `DecisionWorkflowDtos`는 공개·내부 DTO이며 money/ID는 문자열이다.
`V20261126_1000__decision_agent_research.sql`은 최초 근거 고정 저장을,
`V20261126_1100__decision_workflow.sql::guard_decision_work_item`은 요청 원본 불변성과
revision 증가·이벤트 한 건 추가·기존 이벤트 보존을 강제한다. SQL 함수 입력은 NEW/OLD 행,
반환은 허용된 NEW이며 잘못된 갱신은 예외로 중단한다.

검증 소스는 `decisionagent/service/DecisionResearchTest` 및 `decisionworkflow/DecisionWorkflowServiceTest`,
`DecisionWorkflowPolicyTest`, `DecisionWorkflowAuthorizationTest`에 있다. 운영 DB는 조회만 허용되며
migration 적용·실제 DB 쓰기·실제 알림은 실행하지 않는다. 단위/mock/대역 검증은 실제 DB 통합
검증을 대신하지 않으며 실행 결과는 [개발 문서](pnl-decision-agent.md)에 구분해 기록한다.

## 목표 제안·조직 검토·추진 추적 — 프론트

정의 경로는 `frontend/src/prototype/` 기준이다. 모든 HTTP 호출은 기존 인증 클라이언트의
멱등 키·세션 검증을 거친다. 업무 금액은 원 단위 십진 문자열로 보내고, 억원 입력은
`decision-agent-api`의 문자열 변환만 사용한다. 최종 권한·상태·revision 검증과 계산은
Spring의 책임이며 프론트의 입력 검증으로 대신하지 않는다.

| 정의 | 입력·출력·호출 관계·예외와 부작용 |
| --- | --- |
| `decision-workflow-api.workStateLabel/workStateSeverity` | 서버 상태 → 한국어 업무 상태·PrimeVue 색상. 부작용 없음 |
| `workDateText` | 서버 ISO 시각 → 한국 시간 YYYY-MM-DD HH:mm KST. 누락·잘못된 값을 구분하며 브라우저 현지 시간대에 영향받지 않음 |
| `dueAtKst` | YYYY-MM-DD → 한국 시간 해당 일 23:59:59의 ISO 시각. 없는 날짜·형식 오류는 Error, 부작용 없음 |
| `replyCandidateDraft` | WorkItem → 조직 회신 편집 복사. 가설 금액·반영 월은 빈 값, 후보·조직 ID는 서버 요청의 값으로 고정. 검토 완료 표시를 해제 |
| `workReplyPayload` | 요청·조직 회신 → expectedRevision과 후보/사유/근거. FEASIBLE은 정밀 금액·조직 범위 검사, NOT_FEASIBLE은 candidate=null. 회신만으로 REVIEWED가 되지 않음 |
| `workCreatePayload` | 분석·후보 또는 가설·요청 내용·기한 → expectedAnalysisRevision 및 단일 원천 요청 DTO. 필수 입력 오류, 부작용 없음 |
| `canApproveWork` | 분석·ACCEPTED 요청 → 현재 계산에 포함된 REVIEWED 효과 여부. 안내용 검사이며 최종 판단은 서버가 수행 |
| `workflowRequest` | 인증 함수·operation·DTO → goal/research/discover/items/create/item/followup/issue/reply/review/decision/progress/cancel 공개 API. ID 인코딩과 분석/업무 revision을 보존. 미지원 작업·HTTP 오류 |
| `DecisionGoalAssistant.inputs/invalidate` | 자연어·연도 변경 → 이전 제안·응답 세대 무효화. 로컬 상태만 변경 |
| `propose/applyProposal` | 원문·연도 → 목표 제안 조회 / 사용자가 확인한 제안을 apply 이벤트로 전달. 자동 분석 생성·목표 저장 없음 |
| `money/protectionLabels` | 원 금액·보호 범주 → 정밀 억원·한국어 표시. 부작용 없음 |
| `DecisionEvidenceWorkbench.analysisId/loadResearch/discover` | 분석 ID·revision → 저장 조사 조회 / 고정 손익·승인 소명 조사와 가설 요청. 세대 검사로 다른 분석의 늦은 응답 차단 |
| `selectHypothesis/organization/dispose` | 가설 → 요청 작성 이벤트 / 고정 조직명 표시 / 조회 세대 무효화. 선택으로 발송·금액 반영하지 않음 |
| `DecisionWorkflowPanel.analysisId/requestSource/money/publishItems` | 분석·선택 가설 감시 / 정밀 표시 / 현재 업무 목록과 조회 성공 여부를 보고서에 공유 |
| `acceptItem/run/loadItems/openItem` | 서버 업무 응답 적용 / 변경 요청 직렬화 / 분석별 목록·최신 업무 revision 조회. 오류 표시, 서버 조회·상태 변경 |
| `openCreate/chooseCandidate/createWork/createItem` | 선택 가설 또는 저장 후보 → 요청 초안 작성·검증·저장. 조직 전달은 별도 issue 확인 뒤 실행 |
| `openAction/actionWork/submitAction` | 사용자 확인 → issue/review/decision/cancel 요청. 각 업무 revision, 검토·결정·취소는 분석 revision도 전달. 성공 뒤 분석 재조회로 제거·편입된 후보와 계산 무효화 반영 |
| `dispose` | 컴포넌트 해제 → 늦은 목록 응답 무효화·부모 진행 상태 정리 |
| `DecisionReplyFields.months` | 요청의 고정 baseMonth·year → 잔여 월 선택지. 후보 효과 금액을 추정하거나 자동 채우지 않음 |
| `OrgImprovementPanel.inputSnapshot/hasChanges` | 회신·진척 입력 → 저장 상태와 비교할 지문·dirty. 미저장 입력 유실 방지 |
| `api/money/run` | 화면 AbortSignal이 있는 인증 요청 / 정밀 표시 / 직렬 실행·오류 안내 |
| `acceptItem/loadWork/refresh/openItem(read)` | 자기 조직 요청 목록·최신 단건·후속 기준 조회 → 저장 회신/진척 편집 폼. 조직 범위는 서버가 결정하며 사용자가 임의 지정하지 않음 |
| `discard/confirm/submitWork/submit` | 편집 되돌리기 / 확인 창 / 회신 또는 진척 저장. 완료는 후속 마스터 필수, 후보를 직접 승인·편입하지 않음 |
| `guard/beforeUnload/mount/dispose` | 미저장·실행 중 화면 이탈 검사 / router.beforeEach와 beforeunload 등록·해제 / 요청 취소. 메모리·DOM·HTTP 수명 관리 |
| `LiveDecisionAgentPanel.applyGoalProposal` | 사용자가 확인한 목표 제안 → 금액·제약·연도 폼 반영과 필요한 마스터 목록 갱신. 기존 분석은 변경하지 않음 |
| `prepareWorkRequest/setWorkflowBusy/setWorkflowItems/setWorkflowReady` | 가설 선택 → 조직 요청 탭 / 작업 중 중복 분석 변경 차단 / 업무 목록을 진행 단계·보고서에 연결 |
| `refreshCurrentAnalysis` | 업무 변경 성공 → 기존 result/narrative를 먼저 숨기고 최신 분석 조회·적용. 조회 실패에도 이전 계산 숫자를 계속 표시하지 않음 |
| `DecisionFinalReport.money/brief/organization/rows/protectionText/relevantItems/sourceReferences` | 저장 분석·동일 분석 업무 → 정밀 금액·한 장 문장 요약·과제 효과 연결·보호 조건·업무 행·상위 여섯 원천과 전체 건수. 숫자 재계산 없음 |
| `printReport/finishPrint` | 현재 보고서 DOM → 출력 전용 복제 DOM·브라우저 A4 인쇄 대화상자 / afterprint·오류·unmount 시 정리. 파일을 자동 저장하지 않음. 후보 3건·업무 2건을 넘으면 전체 건수와 상세 화면 위치 표시 |
| `PrototypeApp.loadOrgImprovementPanel` | org-improvements 진입 → 조직 개선 업무 화면 지연 로드. 기존 org-* 역할별 라우트 가드를 사용 |

호출은 `목표 입력 → DecisionGoalAssistant → 사용자 확인 → 분석 생성 →
DecisionEvidenceWorkbench → 가설 선택 → DecisionWorkflowPanel 초안·전달 →
OrgImprovementPanel 회신 → planner 근거 검토 → 분석 재조회 → 기존 시나리오 계산 →
추진 결정 기록 → 조직 실행 기록 → DecisionFinalReport` 순서다. 직접 입력한 후보도 같은
조직 확인 요청으로 연결할 수 있다. 추진 결정 당시 순효과와 후속 조직의 연간 전망 변화는
서로 다른 값으로 표시하며 후속 변화를 특정 과제의 실현 성과로 자동 확정하지 않는다.

`decision-workflow-api.test.js`는 합성 메모리 HTTP, 날짜·정밀 금액, 가설의 미정 효과,
회신 권한 경계, 업무/분석 revision 및 역할별 라우트 분리를 검증한다. 브라우저 통합 검증도
모든 `/api/**` 호출을 합성 응답으로 대체하며 운영 DB·실제 모델을 호출하지 않는다.

## 대화형 Agent — native Tool 호출과 조건부 루프

`POST /v1/decision-agent/assistant → run_assistant → ASSISTANT_GRAPH`는
`agent → tools → agent`를 조건부로 반복한다. 첫 `plan_analysis`에서 현재 질문의 확인 항목을
고정하고, 모델이 관찰에 따라 조회·상세 읽기 도구를 선택한다. `finish_answer`가 요구 항목별
해결 여부와 근거에 연결한 사실·해석·가설을 반환하면 구조화 `brief`를 검증해 종료한다.
기존 고정 단계 `discover`와 `compose`는 별도 기능으로 유지한다.
내부·공개 도구 계약은 [대화형 Agent 도구 계약](agent-tool-contract.md), 분석 설명과 요구 항목은
[근거 기반 대화 분석 계약](agent-analysis-contract.md)에 정의한다.

| 정의 (`ai/app/`) | 입력·출력·호출 관계와 부작용 |
| --- | --- |
| `services/llm.sdk_message` | LangChain system/user/assistant/tool 메시지 → SDK role·tool_call_id·native function 인자. 기존 이미지 content 보존, 미지원 역할 ValueError, 전송 없음 |
| `sdk_tool_calls` | SDK assistant 응답 → 정상 dict 인자와 invalid_tool_calls 분리. JSON 오류를 실행 가능한 호출로 바꾸지 않음 |
| `_SdkChatModel._llm_type/bind_tools` | 모델 종류 / 허용 function schema → native tool Runnable. 도구 선택·병렬 호출 옵션만 바인딩하며 네트워크 호출 없음 |
| `_SdkChatModel._generate` | 메시지·native 도구 옵션 → 동일 OpenAI SDK make_client 경로 한 번 → AIMessage·tool_calls·사용량·허용된 provider finish_reason 메타데이터. 모르는 종료 값은 제외하며 원문을 로그에 남기지 않음. 일반 ask_json의 response_format·이미지 전달 계약 보존 |
| `services/llm.ask_tools` | settings·대화·허용 schema → native AIMessage. json_mode 없이 tool_choice=auto·parallel_tool_calls=false를 전송하며 도구 자체는 실행하지 않음 |
| `api/contract.read_assistant_request` | 내부 인증된 Request → 기존 256KiB 제한 reader·AssistantRequest. history는 user/assistant 텍스트만, 오류 413/422 |
| `decision_agent_assistant` | 검증 DTO → threadpool run_assistant → 공개 대화 응답. 고정 context 오류 422 |
| `decision_agent/assistant_models.py` | 고정 context·최대 여덟 history·열세 읽기 도구 인자·관찰·AnalysisPlan·GroundedFinding·RequirementResolution·GroundedFinish DTO. 요구 항목 최대 6개·설명 최대 8개·설명별 인용 ID 1~4개. FinishAnswer·FinishSelection은 기존 검증·호환 경로에 유지. 모델 인자에서 master·사용자·SQL·URL·새 후보 금액을 받지 않음 |
| `GoalPreviewArguments.positive_target` | 억 원 문자열 → 양수 여부 검증. 목표 연도 targetYear는 별도 필수 정수이며 명시 목표 연도를 기준 연도로 덮어쓰지 않도록 모델에 안내. 원 환산·목표 차이 계산과 기준 연도 일치 검증은 Spring에 위임. 자연어 기간의 의미 해석까지 보증하지 않으며 파일·DB 부작용 없음 |
| `assistant_core.tool_schemas` | DTO·현재 근거 ID·수락한 계획·수정 허용 문장 경로·간결 복구 여부 → 계획 전에는 로컬 plan_analysis만, 이후 읽기 도구와 finish_answer. 숫자 오류 문장 수정 중에는 repair_answer만 제공하며 path enum으로 오류 경로를 제한하고 updates의 최소·최대 개수를 허용 경로 수와 일치시킴. 출력 잘림 뒤에는 finish_answer만 제공하고 요약160자·설명140자·확인/불확실성100자와 설명 최대 max(4, 계획 수)≤6으로 축소하되 모든 계획 ID와 실제 인용 ID 유지. 계획·종료·수정은 strict_schema와 strict:true 사용. 설명·불확실성·미해결 질문은 모델이 작성하고 인용 원문 path·quote를 생성하지 않음 |
| `normalize_arguments/call_key` | 모델 이름·인자 → allowlist·엄격한 타입·상한·후보 중복 검사 / 정렬 JSON 캐시 키. 기본 limit은 drivers 5·소명 10 |
| `exact_json/reject_decimal_numbers` | 정밀 문자열·정수 JSON → 직렬화 / float·Decimal 입력 거부. 원 금액·비율은 backend decimal 문자열을 유지 |
| `validate_observation` | callback·실제 Tool·고정 context → 도구·master·연월·analysis/revision·시점·ERROR 형식·256KiB 대조. 관찰 원문을 변형하지 않음 |
| `pointer_scalar` | 관찰·RFC6901 pointer → scalar. ~0/~1만 해석하며 없는 경로·객체·배열·잘못된 배열 인덱스 거부 |
| `FinishValidationError.__init__` | 내부 오류 코드·안전한 field_issues → 허용된 고정 코드·복구 안내와 최대 8개 필드 오류. 미등록 코드는 INVALID_FINISH로 대체. schema_issues가 걸러 낸 필드 경로·오류 종류만 복구 문구에 포함하며 모델 원문·금액·ID·인자값은 저장하지 않음 |
| `validate_finish` | 기존 종료 인자·이번 성공 관찰 → 정성 summary·정확한 citations·허용 화면 동작. 숫자 문자열은 부호·전 자릿수 일치, 일반 문자열만 부분 인용, bool/null 정확 표현 검사. 현재 resolve_brief는 고정 중립 문장으로 이 함수를 호출해 원문 인용만 검증하며 모델 설명은 validate_prose로 검사. 실패는 원문 없는 FinishValidationError |
| `assistant_brief.schema_issues` | Pydantic ValidationError → 최대 8개 허용 필드 경로·오류 종류. 입력값·URL 제외, 알려진 계약 필드와 제한된 배열 인덱스만 유지하며 미등록 키·오류 종류는 고정 문구로 대체. 파일·로그 부작용 없음 |
| `assistant_core.strict_schema` | JSON Schema 노드 → 기본값 제거·모든 객체 속성 required·additionalProperties false인 복사본. 정성 설명에 숫자·퍼센트 기호 생성 제한 pattern 적용, nullable 타입과 검증 상한 유지. 원본 및 업무 도구 인자 불변. plan_analysis/finish_answer의 strict native 계약에 사용 |
| `assistant_brief.validate_plan` | 모델 계획·현재 질문 → 중복 없는 id·requestQuote·question 목록. 최대 여섯 항목·인용별 500자 계약을 유지하며 실제 부분 문자열인 각 requestQuote의 반복·겹침 출현 위치를 모두 합쳐 문자·결합 부호·숫자·기호가 빠짐없이 덮였는지 검사. 공백·문장부호·제어 문자는 범위 검사에서 제외하고 누락 시 원문 없는 PLAN_COVERAGE 반환. PLAN_SCHEMA에는 안전한 schema_issues 연결. 범위 검사는 요구의 의미 이해·분리·충족을 보증하지 않으며 계획 변경·업무 쓰기·원문 로그 없음 |
| `assistant_brief.uncovered_request_segments` | 현재 요청·실제 부분 인용 목록 → 모든 반복·겹침 인용이 덮지 못한 의미 있는 문자 구간의 원문 부분 문자열. 내부 공백·문장부호·기호를 그대로 보존하고 인용 목록·원문을 변경하지 않음. 빈 인용·원문에 없는 인용은 ValueError. validate_plan의 범위 검사와 PLAN_COVERAGE 복구 안내에서만 사용하며 계획·의미·새 지시를 생성하지 않음 |
| `assistant_brief.validate_prose` | 설명·해당 설명의 인용·실제 관찰·코드가 정한 field_name → 공백·근거 밖 금액/비율·결과 보장·저장/발송 완료 주장 검사. 정확한 이름 필드 전체를 인용한 숫자 포함 조직명만 숫자 검사에서 제외. 오류에는 값 없이 문장 위치만 연결하며 의미 진실성은 보증하지 않음 |
| `assistant_brief.resolve_brief/check_prose` | GroundedFinish 제안·관찰·수락 계획 → summary·brief·인용 합집합·nextActions·COMPLETED/NEEDS_INPUT. 실제 citationIds만 원문으로 복원하고 각 finding의 업무 근거, 해석/가설의 uncertainty, 요구 항목 전체의 상태·findingIds·부족 정보·확인 질문을 검증. 계획 id·requestQuote·question 보존, 최대 32개 인용. 숫자 설명 오류만 모든 문장에서 수집해 최대 여덟 경로를 함께 반환하고 다른 오류는 즉시 전파. 요구 항목 오류 위치는 계획 순서가 아닌 원본 제안의 id→배열 index로 결정. 형식 오류는 BRIEF_SCHEMA와 안전한 필드 복구 안내 |
| `assistant_brief.prose_field` | 종료 제안·서버가 정한 경로 → summary, finding text/uncertainty, requirement missingInformation/followUpQuestion 중 실제 문자열 필드의 부모 사전·키. 인용·식별자·상태·없는 위치는 ValueError. 조회만 하며 의미 검증은 수행하지 않음 |
| `numeric_repair_fields` | 종료 제안·FinishValidationError → BRIEF_NUMERIC이며 실제 존재하는 허용 설명 경로만 반환. 다른 오류·임의 경로는 빈 목록. 원문·금액을 오류·로그에 추가하지 않음 |
| `apply_prose_repair` | 기존 종료 제안·ProseRepair·허용 경로 → deepcopy 사본의 해당 문장만 모델 입력으로 교체. 허용 필드 전부를 각 한 번 수정해야 하며 중복·추가 필드·인용/상태 변경은 BRIEF_REPAIR. 실패 원인은 값 없는 schema_issues 또는 고정된 duplicate_path/path_set_mismatch/invalid_target으로 구분. 원본 관찰·계획·다른 필드는 불변. 호출자는 resolve_brief 전체 재검증 필수이며 문장의 의미 보존 자체는 보증하지 않음 |
| `assistant_tools.call_tool` | 허용 이름·인자·고정 context·closure의 capability → `/internal/decision-agent/tools/call` 고정 경로의 POST 한 번. 남은 예산 이하 최대 10초·256KiB, 프록시·리디렉션·파일·직접 DB 접근 없음 |
| `assistant_graph.terminal/remaining/local_error` | 실제 관찰을 보존한 종료 상태 / 단조 시계의 남은 초 / 실행하지 않은 호출의 ToolMessage. PLAN_COVERAGE만 누락 원문을 untrustedRequestSegments로 모델에 전달하고 새로운 시스템 지시·조회 근거가 아님을 명시. 원문 로그·가짜 관찰 ID·공개 실행 기록·계획 자동 추가 없음 |
| `assistant_graph.output_limit/truncated_response` | provider length → 잘린 본문·native 인자를 대화에서 제거하고 허용 이름·ID와 빈 인자·오류 ToolMessage만 남겨 프로토콜 연결. 고정된 계획·실제 관찰·인용으로 간결 종료를 요청하며 계획 전에는 plan_analysis, 문장 수정 중에는 repair_answer만 유지. 호출/시간 여유가 없으면 명시적인 출력 길이 LIMIT_REACHED와 실제 부분 자료 반환. 원문 로그·부분 JSON 정답 조립·업무 조회 원장 추가 없음 |
| `assistant_graph.SYSTEM_PROMPT/assistant_models.TOOL_DESCRIPTIONS` | 모델 지시: 계획 미달·초과와 시계열 추세 구분, 질문에 필요한 finding 종류만 선택, 우선 확인 요청은 구체적인 조직·자료·이유를 가진 해석으로 답하고 일반론은 ANSWERED로 표시하지 않음. 승인 소명은 items.organizationId·facts 기간·text 회신을 대조하며 계정명 label을 원인으로 취급하지 않음. total 대비 items 상한을 확인해 필요한 누락 조직만 실제 ID로 조회하고 상세 도구는 이미 반환된 원문만 읽도록 안내. 의미 품질에 대한 모델 지시이며 기계적 의미 판정 보증은 아님 |
| `assistant_graph.period_mismatch` | 구조화된 목표 연도와 고정 기준의 불일치를 확인한 상태 → 숫자 없는 기간 확인 안내와 모든 계획 항목을 보존한 MODEL·NEEDS_INPUT. findings·citations 없이 요청 항목의 미완료·확인 질문을 resolve_brief로 재검증. 이전 실제 관찰은 유지하고 새 모델·업무 조회·계산은 수행하지 않음. 자연어 연도 추측 규칙이 아님 |
| `agent_node` | 현재 대화·관찰·계획·예산 → llm.ask_tools 한 번 → pending 호출 또는 실패 종료. 모델 최대 여섯 번·호출별 최대40초와 남은 시간 중 작은 값·전체70초·일일 한도 확인. 남은 시간이5초 미만이면 새 모델 호출을 시작하지 않으며 length 메타데이터는 JSON 검증 전에 truncated_response로 처리. 문장 수정도 같은 예산을 쓰며 출력 상한은 min(3000, 256+512×오류 필드 수), 일반 생성은3000토큰. 마지막 두 호출은 해당 단계의 종료 도구를 안내. 모델 오류는 원문 없는 종류 코드만 기록 |
| `tools_node` | pending → plan_analysis 검증·계획 고정 → 도구 인자 검증·cache·callback·모델 입력 또는 resolve_brief. goal_preview의 검증된 targetYear가 context.year와 다르면 callback 전 period_mismatch로 즉시 종료. BRIEF_NUMERIC의 안전한 문장 경로만 메모리에 고정해 repair_answer→apply_prose_repair→전체 resolve_brief 검증. 수정 중 새 조회·계획·인용 변경 금지. 간결 종료 모드도 새 조회·계획 변경을 막고 resolve_brief 전체 검증을 유지하며 다른 종류 오류는 기존 종료 재시도로 복귀. 실제 조회 최대 여덟 회·관찰 누적200KiB 제한. 계획/종료/수정은 실제 조회 횟수에서 제외하지만 모델 호출 예산은 유지. Tool 전송·관찰 오류는 MODEL·FAILED로 분류하고 기존 관찰 보존. 거절 로그는 고정 코드·안전한 필드 위치만 기록 |
| `assistant_presentation.projected_data/walk_scalars` | 원본 성공 관찰 → 전사 상세 행·중복 조직 열을 제외한 모델용 참조 / 원본 경로·업무 표시 맥락을 보존한 핵심 scalar 순회. 수치 계산·원문 변경 없음 |
| `citation_catalog/model_observation` | 실제 성공 관찰 → 안정된 cite-obs-N-M ID·원문 path/quote 목록 / 구조를 보존한 제한 문맥·contextTruncated·availableDetails·인용 목록을 담은 ToolMessage. 공개 원문 보존, 실패 관찰·과거 이력으로 근거를 만들지 않음 |
| `assistant_presentation.context_preview` | 원문·남은 예산·깊이 → 행·필드 관계를 유지하는 제한 문맥. 생략 시 예산의 truncated 표시만 갱신하고 원본 불변. 새 숫자·요약 생성 없음 |
| `available_details/visit` | 성공 관찰·실제 표시 context → 원문에서 상세 읽기가 가능한 경로 목록. 문자열·배열·객체 위치와 읽기 인자를 제한해서 안내하고 상세 결과는 detail_continuations에 위임. detail 자체를 재귀 원천으로 삼지 않음. DB·파일 접근 없음 |
| `detail_continuations/visit` | 상세 페이지·실제 표시 context → 최초 원천의 재조회 위치·긴 하위 값 경로. 실제 표시 길이/행 수에서 작은 페이지로 이어 읽고 배열 하위 경로에 최초 offset을 반영. 숨긴 중간 구간을 읽은 것으로 처리하지 않음. 새 배열 외 부작용 없음 |
| `resolve_selection` | 이전 citationIds/answerStyle/nextActions → 기존 고정 안내·인용 검증을 유지하는 호환 함수. 현재 native finish 실행은 resolve_brief 사용. 알 수 없는 ID·중복·자유 본문·임의 경로 거부 |
| `partial_finish` | 이번 실행 관찰 → 제한된 검증 인용과 부분 결과 안내 또는 인용 없음. 실패/한도를 완료로 바꾸지 않고 실제 조회 결과를 보존 |
| `route_after_agent/route_after_tools` | 상태 → 모델 재선택·도구 실행·최종 응답 분기. 결과에 따른 동적 경로이며 고정 조회 목록을 순서대로 실행하지 않음 |
| `finalize_node` | 종료 상태 → mode/status/answer/context/toolCalls/observations/citations/nextActions/limitations/brief. 실패·한도에서 brief=null과 실제 부분 근거 보존. history·모델 메시지·토큰 제외, 응답256KiB 한도 검사 |
| `build_assistant_graph/run_assistant/callback` | 조건부 루프 컴파일 / 요청 검증·70초 예산·closure 구성·graph 실행 / token 비공개 내부 도구 호출. 체크포인터·파일·직접 DB·업무 쓰기 없음 |

성공 관찰 없이도 미해결 요구 항목과 확인 질문이 있으면 `NEEDS_INPUT`으로 종료할 수 있다.
모델 없음·제공자 실패는 `UNAVAILABLE`와 실패 상태, 예산 소진은 `LIMIT_REACHED`다.
`COMPLETED`는 모든 계획 항목이 실제 finding에 연결되어 ANSWERED이고 종료 계약을 통과한 경우만 반환한다.
이는 요구 항목·근거 연결 검증이며 해석의 진위나 가설의 효과 실현 보증이 아니다.
내부 예외·SQL·비밀·추론 원문은 공개하지 않는다.

| 무네트워크 테스트 정의 (`ai/tests/`) | 검증과 호출 관계 |
| --- | --- |
| `test_assistant_guards.request_fixture/observation_fixture/native_call/finish_arguments` | 합성 입력·관찰·native 메시지·종료 인자를 메모리에서 구성. 실제 자료·비밀 없음 |
| `AssistantCoreGuardTests` | allowlist·scope·history 역할·전체 숫자 인용·JSON pointer·bool/null·거짓 출처·정성 본문·관찰 정밀도 검증 |
| `test_finish_errors_classify_failures_without_source_text` | 합성 오류별 고정 코드 분류와 schema 오류 원문 비노출 검증. 외부 호출·산출물 없음 |
| `test_summary_does_not_mistake_organization_words_for_money` | 일반 조직·조건 문구는 허용하고 실제 한글 금액·Unicode 숫자·비율은 거부하는 경계 검증 |
| `AssistantLoopGuardTests.setUp/test_*/delayed_model` | 실제 LangGraph와 대역 모델·Tool·시계 → 재선택·중복 cache·거짓 인용 수정·오류 보존·model6/tool8/deadline·NEEDS_INPUT 검증. Tool 시간 초과·잘못된 관찰은 MODEL·FAILED와 기존 관찰을 유지하며 모델 미설정·일일 한도를 서로 구분. sleep·외부 호출 없음 |
| `test_rejected_summary_gets_precise_recovery_and_safe_field_logging` | 잘못된 합성 종료 → 고정 코드·안전한 필드 오류 안내 → 재조회 없는 정상 종료. 원문·값을 로그에 남기지 않는지 검증 |
| `DiscoveryCoreTests.test_organization_words_are_allowed_in_goal_and_hypothesis` | 공통 금액 식 변경 후 목표 설명·가설의 일반 조직 문구 허용과 실제 금액 거부 회귀 검증 |
| `NativeAdapterGuardTests.test_invalid_native_arguments_stay_unexecutable` | SDK 잘못된 인자 JSON → invalid_tool_calls·메시지 연결 검증 |
| `AssistantHttpGuardTests.setUp/test_*` | 실제 FastAPI router·무모델 설정 → 인증 선행·256KiB·history/context 오류·UNAVAILABLE 검증 |
| `test_assistant_native_contract.request_fixture/sdk_response` | 합성 고정 요청과 실제 SDK native function 응답 모양 구성 |
| `AdaptiveProvider.__init__/create` | SDK 요청 기록 / 실제 ToolMessage의 관찰 수치에 따라 company_pnl 후 조기 종료 또는 profit_drivers 추가 선택. token 비노출·native schema·tool_call_id 검사 |
| `AssistantNativeContractTests.test_observation_changes_the_next_native_tool_call/backend/client_factory` | 실제 SDK 메시지 어댑터→LangGraph→httpx Client(MockTransport)→Tool 관찰→인용의 두 분기 왕복. callback 경로·헤더·고정 범위·정밀 숫자 검증 |
| `test_provider_failure_never_fabricates_successful_tool_results` | 제공자 실패 → 실제 도구 호출·허위 관찰·성공 답변 없음 확인 |
| `selection_arguments/plan_arguments/missing_arguments` | 합성 관찰·현재 질문 → 구조화 finish·plan_analysis·미해결 확인 질문 인자. 실제 자료·네트워크·파일 부작용 없음 |
| `test_assistant_brief.GroundedBriefTests` | 실제 질문 부분 인용·전체 요청 문자 범위(문장 누락 거부, 모든 문장·공백·문장부호·반복·겹침·숫자·기호)·계획 고정·누락 요구 항목 거부·가설의 근거와 uncertainty·숫자 포함 조직명·최초 계획·상세 원문과 다음 페이지를 무네트워크 검증. 범위 일치가 요구의 의미 충족을 보증하지 않음 |
| `test_uncovered_request_segments_preserve_literal_compound_instructions/test_plan_repair_receives_missing_request_only_as_untrusted_context` | 복합 요청의 실제 누락 구간·내부 공백/기호·반복/겹침 인용 보존과 PLAN_COVERAGE 모델 재계획을 검사. 요청 원문은 수정 문맥에만 전달하고 로그에는 고정 code/fields만 남는지, 코드가 계획 항목을 자동 추가하지 않는지 확인. 조회·파일·실제 모델 없음 |
| `test_assistant_truncation.truncated_call/AssistantTruncationTests.setUp/test_*/model` | 합성 SDK length·깨진 JSON·시계 → 다음 요청의 원문 제거와 native 연결·간결 strict schema·여섯 요구/인용 enum·기존 관찰/계획 불변·추가 조회/계획 변경 거부·반복 잘림6회 상한·마감 직전 새 호출 차단·계획/수정 단계별 재시도·종료 메타데이터 allowlist를 검증. 실제 모델·DB·파일·네트워크 없음 |
| `test_assistant_repair.repair_arguments/AssistantProseRepairTests.setUp/test_*/model` | 합성 문장 수정·기간 질문·시계·SDK 대역 → 좁은 필드 교체와 원본/인용/계획 불변, 불법 경로·추가 조회·계획 변경 거부, 숫자 오류 재검증, 반복 수정6회·전체70초 한도, SDK only-repair strict/path enum·허용 경로와 updates 개수 일치·원문 없는 수정 오류 구분·여러 숫자 문장 한 번에 복구·역순 요구 항목의 정확한 원본 경로·다른 계약 오류 즉시 거부·여덟 수정 경로 상한 검증. 목표 연도 필수·구조화된 기간 불일치 시 반복 모델에도2회/조회0으로NEEDS_INPUT·이전 관찰과 계획 보존·동일 연도 정상 흐름 및 조회 없는 계획→NEEDS_INPUT 검증. 실제 모델·DB·파일·네트워크 없음 |
| `test_schema_repair_names_fields_without_exposing_values` | 잘못된 구조 → schema_issues/FinishValidationError → 허용 필드와 오류 종류만 포함한 복구 안내. 입력값·임의 필드명·원문 비노출 검증 |
| `test_uncited_suggestion_recovers_as_unresolved_requirement` | 근거 없는 제안을 finding에서 제외하고 미해결 requirement의 부족 자료·확인 질문으로 옮겨 재종료하는 복구 검증 |
| `test_plan_and_finish_use_strict_native_schema` | LangChain→SDK 계획·종료 schema의 strict 플래그·객체 필수 필드·nullable 유지·인용 하한·정성 문장 pattern을 네트워크 없이 검사 |
| `test_assistant_presentation.test_catalog_ids_stay_stable_and_restore_exact_scalars` | 관찰 추가 전후 ID 안정성과 기존 원문 인용 복원 검증 |
| `test_model_view_omits_large_company_rows_without_changing_public_data` | 큰 전사 상세 행 → 경량 모델 입력, 공개 원장 원문 불변 검증 |
| `test_finish_selection_rejects_old_fields_unknown_ids_and_duplicates` | 자유 summary·미등록 근거·중복 선택 거부 검증 |
| `test_partial_response_uses_successful_current_sources_only` | 실패·과거 자료를 제외한 이번 성공 관찰만 부분 결과 인용으로 구성하는지 검증 |
| `test_empty_success_can_finish_with_context_and_needs_input` | 빈 조회 자료 → 실제 기준 문구를 인용하고 NEEDS_INPUT으로 정직하게 종료하는지 검증 |
| `test_goal_preview_accepts_won_precision_without_doing_calculation` | 양수 억 원·소수8자리 입력 검증, AI의 직접 금액 계산 없음 확인 |
| `test_timeout_and_limit_keep_valid_partial_evidence` | 모델 시간 초과·전체 한도 → 실패 상태와 실제 인용 보존 확인 |

기존 `test_llm.py`의 ask_json 요청·이미지·토큰 사용량·오류 호환성 검증은 함께 유지한다.
전체 AI 회귀는 DB 생성 프로그램 탐색과 psycopg 연결을 차단하고 모델 제공자를 대역으로
교체해 실행한다. 승인된 임시 검증 경로 밖에 테스트 산출물을 만들지 않는다.

## 대화형 Agent — Spring 조회 도구와 관찰 검증

정의 경로는 `backend/src/main/java/io/skala/backend/domain/decisionagent/` 기준이다.
공개·내부 DTO와 13 Tool의 인자·결과는 [decision-assistant.yaml](../api/decision-assistant.yaml),
실행 경계는 [대화형 도구 계약](agent-tool-contract.md)을 따른다. 신규 집계 SQL을 만들지 않고
Home·Archive·버전 비교·정밀 계산·기존 Research 읽기·Workflow 조회 서비스를 재사용한다.

| 정의 | 입력·출력·호출 관계·예외와 부작용 |
| --- | --- |
| `DecisionAssistantController.ask` | 인증 사용자·Request → Service.ask → 공개 Response. PLANNER·입력·고정 기준 검사. 멱등 executor나 업무 저장 없음 |
| `DecisionAssistantController.call` | 내부 공유 토큰+단기 capability·ToolRequest → Gateway.call. 내부 토큰 실패는 403이며 capability 조회 전에 종료 |
| `DecisionAssistantService.ask/id` | masterId·선택 analysisId/expectedRevision·질문/이력 → 소유권·원천 대조 → capability 발급 → ReportAiClient.post → 최종 권한·원장 검사 → 폐기. HTTP 대기 동안 트랜잭션 없음. ID 범위·권한·버전 오류는 호출 전 거부 |
| `DecisionAssistantGateway.grant/require/revoke` | 실제 사용자·snapshot·선택 analysis·현재 질문 → 메모리 capability / 유효 실행 / 폐기. 현재 질문은 brief.requestQuote 대조에만 사용. TTL 180초, 동시 200개, 만료 회수, 403·429. DB 저장 없음 |
| `DecisionAssistantGateway.call` | token·tool/arguments → 현재 역할·revision 확인 → 정규화·캐시 → 기존 조회 실행 → 안전한 Observation. 고유 최대 8회·단일 256KiB·누적 192KiB 예산. 실패도 안전한 원장에 기록. 성공한 버전 목록만 비교 allowlist에 추가 |
| `DecisionAssistantGateway.Run/Run.calls` | 고정 사용자·기준·선택 분석·만료 → 메모리 실행 상태 / 실제 관찰 순서의 call-N 목록. 토큰·SQL·모델 추론은 공개하지 않음 |
| `DecisionAssistantTools.label/normalize/identifier` | allowlist 이름·인자 → 고정 표시명·정규 JSON·검증된 ID. 미허용 필드/null/SQL/URL/scope, 중복 후보, 잘못된 숫자 범위 거부. 기본 limit 5/10, 후보 ID 정렬. goal_preview는 양수 억 원 문자열(정수16자리·소수8자리)과 2000~9999 정수 targetYear를 모두 요구하고 연도를 자동 보충하지 않음 |
| `DecisionAssistantTools.assertCurrent` | Run → 현재 AppUser 활성·PLANNER, 선택 분석 소유권·revision·master 재확인. readOnly DB 조회, 403/404/409 |
| `DecisionAssistantTools.execute` | 고정 Run·도구·정규 인자 → 기존 서비스 결과. readOnly 트랜잭션 안에서 원천 금액·고정 조직을 검증하며 새 SQL·쓰기 API 없음. 비교 방향·순위는 기존 차이 원값으로 계산하고 목표 비교는 기존 계산기를 재사용 |
| `DecisionAssistantTools.observationDetail/boundedInteger` | 이번 실행의 SUCCESS 관찰 ID·/data 아래 pointer·offset/limit → sourceObservationId/path/offset/nextOffset/total/value. offset 0~10000·limit 1~10 검사; 배열 행·객체 속성·문자열 일부만 반환하고 scalar는 offset 0만 허용. 다른 실행·ERROR·detail 재귀·금지 경로 거부. 새 DB·URL·파일 조회 없음 |
| `contextData` | Run → snapshot, 선택 분석의 id/title/revision/목표/보호 조건/후보/result만 공개. 분석 미선택이면 analysis=null |
| `company/comparisonMetric` | 기존 전사 손익 원문·KPI → 원문 보존과 summary.metrics/detailRowCount. 원값 부호로 증가/감소/동일, COST의 증가=불리·감소=유리를 판정하며 결측은 영으로 대체하지 않음 |
| `divisions/rankMetrics` | Run·선택 organizationId → 고정 조직 columns와 OP 계획 차이 요약. 부호 있는 차이 내림차순과 절댓값 내림차순을 별도 순위로 반환하고 누락은 후순위. 조직별 매출·비용 계획 비교 미제공을 명시. total은 전사 합계, 계획 비교는 조회 시점 기준 |
| `goalPreview` | execute에서 targetYear와 고정 Snapshot 연도가 일치한 양수 억 원 목표 → 정확한 원 환산 후 기존 계산기에서 현재 전망·목표·부족액·초과 전망액·부호 차이 반환. 연도 불일치는 원천 재조회·계산 전에 ERROR 관찰로 차단하고 기준 선택·목표 연도 확인을 요청. 자연어의 연도 해석 정확성까지 보장하지 않음. forecastMeetsTarget는 전망 비교이며 실적 달성 아님. saved=false, DB 저장·신규 후보 없음 |
| `explanations` | Run·선택 조직·limit → 기존 저장 Research 근거 또는 ResearchStore.loadEvidence의 읽기 결과. 수락 소명만 필터하고 포착 시점·추출 상한을 표시. capture/save 호출 없음 |
| `versions` | Run·조직 → ArchiveQueryService.versions의 기준월 APPROVED·snapshot.asOf 이전 history. 파일/URL·현재 최신 finalLabel 제외 |
| `compare` | Run·조직·baseSubmissionId → 앞선 성공 버전 관찰의 ID 검사 → 고정 sourceSubmissionIds의 조직 제출본 → VersionComparisonService.compare. 기준 ID 생략·자동 최신 대체 없음 |
| `scenario` | Run·선택 candidateIds → 저장 분석 후보만 DecisionScenarioCalculator.calculate로 임시 계산. 새 금액·후보·저장·승인 없음 |
| `workItems` | Run → DecisionWorkflowService.list로 소유 분석의 요청/회신/검토/외부 결정/진척 상태만 투영. source 사본·events·ownerId·파일 제외 |
| `draft` | Run·candidate/hypothesis sourceId → 저장 후보 또는 DiscoveryService.findHypothesis → analysisId/expectedAnalysisRevision/sourceKind/sourceId/organizationId/title/message/needsConfirmation. 기한은 사용자가 입력. 생성·발송하지 않음 |
| `requireAnalysis/value/ToolFailure` | 선택 분석 검증 / 조회 값·비교 기준·한계 봉투 / 노출 가능한 고정 안내 예외. 미선택 분석은 안전한 ERROR 관찰 |
| `DecisionAssistantJson` 생성자·`safe/tree/size/canonical/project/clean` | 공용 mapper 사본 → BigDecimal 문자열 직렬화·결정적 캐시 키·허용 필드 투영·UTF-8 크기. 파일/URL/로컬 경로/stateToken/capability 제거. 공용 ObjectMapper 설정이나 파일 변경 없음 |
| `DecisionAssistantResponseValidator.validate` | AI JSON·실제 Run → 필드/기준/관찰/호출 전체 일치 및 최대 32개 scalar 인용 검증. 숫자 문자열의 부호·전 자릿수, bool/null 정확 표현·RFC6901 escape 대조. brief가 있으면 별도 BriefValidator로 원문 인용·현재 질문·최상위 answer·상태를 함께 검사하고 없으면 기존 정성 답변 계약 유지 |
| `DecisionAssistantBriefValidator` 생성자·`validate` | 정밀 mapper / brief·검증된 인용·성공 원장·현재 질문·answer·status → 타입이 있는 Brief. finding/requirement ID·현재 질문 부분 인용·설명 인용과 최상위 인용의 동일 합집합·불확실성·미해결 정보·완료 상태 검사. 형식·근거 연결 오류 IllegalArgumentException, 쓰기 없음 |
| `DecisionAssistantBriefValidator.prose` | 설명·검증 인용·성공 원장 → 근거 밖 금액/비율·보장·업무 실행 주장 검사. 정확히 인용한 이름의 숫자만 예외. 해석의 진위를 자동 보증하지 않음 |
| `DecisionAssistantBriefValidator.businessCitation` | 인용·성공 관찰 → 업무 내용 근거 여부. 상세 조회는 /data/value 또는 그 하위 값만 인정하며 원천 ID·offset·total 등 메타데이터만 인용한 finding을 거부. 상태·DB 변경 없음 |
| `DecisionAssistantBriefValidator.text/optionalText/identifier/array/fields` | JSON 값·길이/건수 상한·필수 여부 → 계약에 맞는 문자열·배열 또는 예외. 임의 필드·빈 문자열·초과 길이 거부, nullable 명세 유지. 원문 변경·DB 부작용 없음 |
| `unavailable/exactFields/text/array` | 검증 실패 → 실제 원장만 보존한 UNAVAILABLE/FAILED / 엄격한 객체·문자열·배열 검사. 서버 한계 문구만 공개하고 모델이 추가한 원장·한계 주장을 재사용하지 않음 |
| `DecisionResearchStore.loadEvidence` | Snapshot → 기존 손익 최대 100·수락 소명 최대 50개를 읽기만 수행하는 공개 facade. 기존 capture도 같은 함수를 재사용. 호출자가 기준 수치·권한을 검증하며 research INSERT/UPDATE 없음 |
| `SecurityConfig.securityFilterChain` 내부 경로 | `/internal/decision-agent/tools/call`은 bearer 대신 컨트롤러의 내부 공유 토큰+capability 이중 검사를 적용. Ingress에 공개하지 않음 |

호출 관계는 `공개 ask → 기준·권한 확인 → capability → 내부 assistant → plan_analysis → 모델 도구 선택 →
내부 tools/call → 기존 조회 서비스·원문 상세 읽기 → 관찰 원장 → 구조화 finish_answer → 서버 원장·brief 재검사 →
공개 응답 → capability 폐기`다. 사용자 이력은 문맥이며 현재 사실은 이번 실행의 성공 관찰에서만 인용한다.
동일 요청의 캐시 결과도 역할·분석 revision을 다시 확인한다. 비동기 실행 뒤 권한 또는 분석 버전이
바뀌면 최종 응답도 차단한다. 자연어 질문은 기존 저장·승인·발행 API를 자동 호출하지 않는다.

`DecisionAssistantTest`는 실제 DB 없는 회귀로 기존 서비스·고정 master 재사용, 금지 인자,
조직·소유권·revision·역할 철회, 저장 후보 계산, 수동 요청 초안, 만료·동시 capability·실행/바이트 상한,
선행 승인 버전 목록, 가짜 관찰·호출·숫자 인용, 일반 조직 표현 오탐과 실제 금액 차단,
URL 제거·정밀 문자열, HTTP JSON 시간대 왕복을 검증한다.
`rejectsMissingOrInvalidTargetYearBeforeCalculation`과
`rejectsMismatchedTargetYearBeforeBaselineReadOrCalculation`은 연도 누락·형식·범위·기준 불일치에서
ERROR 관찰을 남기되 원천 조회·계산기를 호출하지 않는지 확인한다.
`DecisionAssistantControllerTest` 2개는 내부 공유 토큰과 capability 검사를 분리해 확인한다.
실제 DB 연결·migration·업무 상태 변경이나 외부 알림은 실행하지 않는다.

`ReportAiClient` 생성자는 Spring Boot의 `ObjectMapper`를 JSON converter에 주입한다.
내부 assistant·compose·discover 요청의 `OffsetDateTime`이 timestamp 숫자로 바뀌지 않고
공개 API와 같은 ISO 문자열로 전달된다. 공용 mapper 자체는 변경하지 않는다.
`ReportAiClientTest.client`는 토큰 검사용 mapper·클라이언트를 구성하며,
`sendsIsoDatesWithBootMapperThroughActualHttp`는 Jackson 전용 Spring context와 루프백 HTTP
서버를 사용해 실제 전송 JSON의 날짜·내부 헤더·응답 역직렬화를 검증한다.
서버는 finally에서 종료하며 DB·외부 모델·파일을 사용하지 않는다.

## 대화형 Agent — 프론트 질문·출처·업무 연결

정의 경로는 `frontend/src/prototype/` 기준이다. 질문 화면은 확정 마스터만으로도 조회할 수 있고,
저장 분석이 있으면 그 분석의 ID·revision을 같이 전송한다. 도구 선택은 서버 Agent가 수행하며
프론트는 실제 응답의 실행 내역만 표시한다. 질문으로 저장·승인·발송을 자동 실행하지 않는다.

| 정의 | 입력·출력·호출 관계·예외와 부작용 |
| --- | --- |
| `decision-conversation-api.conversationKey` | master·analysis ID·revision → 기준별 화면 이력 키. 부작용 없음 |
| `conversationHistory/historyExecutionContext` | 완료·실패·부분 조회 문답 → 최근 최대 8개 user/assistant 메시지, 각 2000자. 이전 질문·허용 도구/상태와 검증된 요구 질문/상태·종류별 번호가 붙은 짧은 finding 문구를 문맥으로 전달. 인용·관찰 원문·원문 수치는 제외하고 이전 분석이 현재 사실 근거가 아님을 명시 |
| `successfulObservations` | 응답 → SUCCESS이며 master/analysis/revision/year/baseMonth가 같은 중복 없는 관찰. 완료 실패와 관계없이 실제 조회 결과 표시 |
| `observationForExecution` | 현재 기준 문답·실행 ID·관찰 ID → 해당 문답의 성공 관찰 또는 null. 반복되는 obs-1 등을 다른 실행 원문으로 대체하지 않음 |
| `observationExplanations` | 승인 소명 관찰 → 최대 20개 원문 본문·근거 ID·검토 시각 카드. 요약·문장 추가·저장 없음 |
| `assistantPayload` | 고정 기준·선택 분석·질문·이력 → 공개 assistant 요청 DTO. 필수 기준·질문 길이·분석 master 불일치 Error |
| `validateAssistantResponse` | 응답·원 요청 → mode/status 및 master·analysis·revision 일치 검사. 기준 불일치는 Error. 같은 기준에서 brief만 잘못되면 원문 관찰 보존·brief=null·FAILED와 고정 분석 실패 안내/한계를 담은 사본 반환. 잘못된 모델 answer는 숨기며 성공 관찰 없는 경우 자료 확보를 주장하지 않음 |
| `assistantStatusLabel` | 실제 mode/status/brief → 한도 우선 상태 문구. UNAVAILABLE·LIMIT_REACHED는 실행 한도, UNAVAILABLE·FAILED는 답변 생성 중단으로 표시하며 모델 미설정·시간 초과·검증 실패를 임의로 추정하지 않음. 구체 원인은 서버의 안전한 answer를 유지하고 원문 관찰·응답은 변경하지 않음 |
| `citationValue/answerCitations` | 관찰·RFC6901 경로·quote → 자체 스칼라 및 검증 인용. prototype·객체 경로 거부, 숫자는 부호 포함 전체 문자열 일치, 일반 텍스트만 부분 인용, null 보존. 금액 표시만 억원으로 변환 |
| `detailCitationOrigin` | 상세 읽기 관찰·인용 경로·현재 성공 관찰 → 원천 도구·원래 필드의 표시 의미. /data/value 인용도 원천 원 단위 금액 서식을 유지하고 출처 자체는 실제 상세 관찰에 연결. 조회·원문 변경 없음 |
| `analysisBrief` | 응답·선택 현재 질문 → 근거·요구 항목·상태를 검증하고 표시 라벨/정밀 인용/미해결 목록/answeredCount를 추가한 사본 또는 null. 요구/설명 한도·ID·현재 부분 인용·실제 finding 참조·해석의 uncertainty·미해결 질문 검사. 형식 오류 Error, 재계산·저장 없음 |
| `briefText/citationIdentity/unansweredRequirement` | brief 문자열/인용/요구 항목 → 문자열 유효 여부·인용 조합 키·미해결 여부. analysisBrief의 형식·중복·상태 검사를 보조하며 원문 응답 변경이나 부작용 없음 |
| `findingKindLabel/requirementStatusLabel` | 계약 kind/status → 확인된 사실·근거 해석·검토 가설 / 답변됨·추가 확인·현재 범위 밖 라벨. 새로운 판단·쓰기 없음 |
| `answerActions` | 서버 nextActions → 세 개 허용 업무 탭 탐색. 알 수 없는 동작 제거, 상태 변경 없음 |
| `reviewDraftSource` | 요청 초안 관찰·현재 분석 → 기존 요청 작성 원천. basis와 data revision·조직·단일 원천을 확인하며 효과 금액·기한을 만들지 않음 |
| `observationCell` | 필드·원문·도구 → 정밀 억원·한국어 상태 표시. 원 단위는 Won 접미사 및 기존 HOME 도구 필드 계약으로 식별. OP 차이 부호와 서버의 방향·유불리를 보존. 원본 변경 없음 |
| `observationTables/displayRow/isScalar/scalarRow` | 관찰·정렬 방식 → 기존 ReportDataTable 표시용 복사. 서버 비교 요약 우선, 중첩 조직 이름·코드, 최대 4표·20행·8열. 기본 부호 차이 내림차순, 생략 한도와 접힌 원문 위치 표시 |
| `rowsTable/byDifference` | 행·필드·정렬 기준 → 정확한 십진 문자열의 부호/절댓값 내림차순과 누락 후순위 표. 원본 수치 재계산 없음 |
| `goalPreviewTables` | 저장 없는 목표 관찰 → 목표·현재 전망·부족액·초과액 표. saved=false와 전망 비교의 의미 표시, 목표 저장·계산 없음 |
| `askAssistant` | 인증 api·payload·AbortSignal → 공개 POST assistant·응답 기준 검증. HTTP/계약 오류 전달, 읽기·초안 Tool만 실행 |
| `DecisionConversationPanel.currentMaster/scopeKey/basisLabel` | 부모 분석·기준 → 고정 master·분리 키·기준 이름. 계산·저장 없음 |
| `DecisionConversationPanel.hasOrganizationResults` | 같은 실행의 성공한 조직 관찰 여부 → 부호 차이/절댓값 정렬 선택기 표시 |
| `DecisionConversationPanel.briefByTurn/findingSeverity` | 현재 기준의 문답·질문 → 검증된 brief Map / 설명 종류별 PrimeVue 표시. 요약·설명·불확실성·미해결 질문을 연결하고 원문은 접힌 조회 자료로 유지. 출처는 해당 turn ID·observation ID 쌍 사용 |
| `decision-agent-api.isReadOnlyPreviewError/startAnalysisAction` | 정확한 `READ_ONLY_PREVIEW` 오류만 조회 전용으로 인식하고 목표 비교 또는 기존 저장 동작 하나만 호출. 일반 403·503이나 문구만으로 권한·환경을 추정하지 않음 |
| `goalPreviewQuestion/canStartGoalPreview` | 폼의 마스터·연도·정밀 억원 목표 → 목표 대비 부족·초과액과 조직별 계획 차이의 두 조회 요청 및 실행 가능 여부. 목표 형식·상한·현재 기준을 검증하고 기존 질문 초안·실행 중 요청을 보존. 고정 마스터·저장 없음·보호 제약 및 개선 효과 미적용은 API와 조회 전용 폼 안내에서 유지하며 모델 질문에 정책 설명을 중복하지 않음 |
| `LiveDecisionAgentPanel.previewCurrentGoal/requestSavedAnalysis/requestCreate` | 조회 전용이면 대화 공개 메서드로 비교하고 정상 환경이면 기존 생성 흐름 실행. 조회 전용 경로에서 분석 생성 API를 호출하지 않음 |
| `DecisionConversationPanel.requestGoalPreview/sendQuestion/revealConversation` | 확인한 목표 질문 또는 일반 질문 → 동일한 조회 Agent 요청·메모리 문답. 폼 비교는 보고서 실행 이벤트로 연결하며 대화로 자동 이동하지 않음. 초안 충돌·입력 오류 때만 대화 위치를 안내하며 기준 변경 시 요청 중단 |
| `DecisionConversationPanel.publishExecution/openObservation` | 현재 문답 → 실행 ID를 포함한 PENDING/RESULT/ERROR 또는 기준 전환 IDLE 이벤트. 보고서 출처는 현재 기준의 정확한 실행 ID·관찰 ID 쌍에 해당하는 성공 관찰만 원문 창으로 연결. 서버 재조회·저장 없음 |
| `changeScope` | 새 기준 키 → 이전 요청 중단·세대 증가·화면 이력 교체. 최근 4개 기준·각 8문답 메모리만 유지하며 오래된 내용 제거 안내. 서버 저장 없음 |
| `submitQuestion/cancelQuestion/dispose` | 질문 → payload·읽기 요청·실제 답변 / 중단 / 화면 해제. AbortSignal과 세대로 다른 기준의 늦은 응답 차단 |
| `selectMaster/openSource/navigate` | 명시적 사용자 선택 → 기준 선택 이벤트·출처 창·기존 업무 탭. 자동 업무 변경 없음 |
| `draftRequests/prepareDraft` | 현재 분석과 일치하는 초안 관찰 → 요청 원천 목록·부모 prepareWorkRequest. 사용자 확인·기한 입력·저장·전달은 기존 업무 화면에서 각각 수행 |
| `statusLabel/statusSeverity/toolStatus` | 실제 서버 mode/status → assistantStatusLabel 기반 한국어 상태·색상. 한도와 답변 중단을 구분하고 고정 모델 불가 배너로 서버 원인 안내를 덮지 않음. UNAVAILABLE를 완료로 표시하지 않으며 수행하지 않은 진행 단계 생성 없음 |
| `rawDetails` | 관찰 → 접힌 JSON 원문 최대 24000자. 표시 한도 초과는 명시하며 원본은 메모리에 보존 |
| `LiveDecisionAgentPanel.api` | 인증 요청에 화면 수명과 자식 요청 AbortSignal을 결합. 화면 해제·기준 변경 중 취소 지원 |
| `selectConversationMaster/openConversationTab` | 분석 전 master 공유 / 허용 탭 이동. 계산 없는 시나리오는 후보 탭과 실행 안내로 연결하며 자동 계산 없음 |
| `LiveDecisionAgentPanel.previewScope/previewReport/previewBaselineLabel` | 현재 폼·선택 분석·실제 대화 실행 → 보고서 범위와 투영 결과·기준명. 업무 계산·저장 없음 |
| `LiveDecisionAgentPanel.acceptPreviewExecution/openPreviewSource` | 실행 이벤트 → 보고서 메모리 교체·폼 실행 시 보고서 위치 이동. 원문은 실행 ID·관찰 ID 쌍으로 대화의 기존 출처 창에서만 표시 |
| `DecisionPreviewReport.currentGoal/organizationRows/sourceRows` | 검증한 투영 결과 → 목표·최대 5개 조직·출처. 결측을 임의 수치로 대체하지 않음 |
| `DecisionPreviewReport.currentBrief/findingSeverity` | 현재 READY/PARTIAL 투영 → 분석 요약·설명·불확실성·요구 항목 / 종류별 PrimeVue 표시. EMPTY·LOADING·STALE에서는 이전 brief 숨김. baseline 목표 판단과 모델 요약을 구분하며 각 설명의 실제 출처 연결 |
| `DecisionPreviewReport.money/longMoney/signedMoney` | 원 단위 문자열 → 정밀 억원·긴 금액 서식·부호 표시. 반올림·금융 계산 없음 |
| `DecisionPreviewReport.judgment/judgmentDetail/differenceAmount/differenceLabel` | 실제 상태·백엔드 목표 판단 → 핵심 문구·기존 차이 값·지표명. 오류·기준 변경·미조회 구분 |
| `DecisionPreviewReport.directionClass/planDirection/openSource` | 서버의 손익 영향·증감 방향 → 색상·한국어 판단 / 출처 클릭 이벤트. 승인·저장·새 조회 없음 |
| `DecisionPreviewReport.organizationEmptyText` | 실제 조회 조직 수·표시 상태 → 미조회와 조회 결과 0건을 구분한 안내 |
| `DecisionPreviewReport.organizationCaption` | 조직 조회 출처·현재 표시 행 → 전체 조직 손익과 차이 상위 조회의 범위·정렬 안내. profit_drivers만 있으면 연간 전망 미조회·조회된 조직 안의 차이 순서임을 표시하며 전체 순위나 조직 수를 추정하지 않음 |
| `decision-preview-result.buildPreviewResult/emptyResult` | 현재 범위·실행 → 같은 기준의 SUCCESS 관찰·검증 brief·실행 status를 담은 단일 보고서 DTO 또는 금액/brief 없는 상태. EMPTY·LOADING·READY·PARTIAL·STALE·ERROR 구분. 성공 관찰 없이도 유효한 NEEDS_INPUT brief는 질문이 있는 PARTIAL. API·DB 호출 없음 |
| `matchesScope/matchesForm/normalizedGoal` | 마스터·분석 revision·연도·정규화한 폼 목표 → 현재 입력과 실행의 일치 여부. 대화에서 실제 조회한 목표를 폼 값으로 덮지 않음 |
| `observedGoal` | saved=false·KRW·같은 연월·정밀 금액의 성공 관찰 → 원문 목표 비교. 금액 누락은 null, 부족액·초과액 재계산 없음 |
| `observedOrganizations/byPlanDifference` | 성공한 조직 요약 → 정밀 십진 비교로 정렬한 상위 5개·전체 기준 조직 수. 원본 불변, 누락은 후순위 |
| `observedPlanDifferences` | 같은 기준의 SUCCESS profit_drivers.items → 정밀 opDiff와 조직 ID/이름을 보존한 최대 5개 행. organization_pnl 자료가 없을 때만 대체하며 현재 전망·전체 조직 수·방향/유불리는 추정하지 않음. total은 금액이므로 개수로 사용하지 않고 원문 출처 ID 유지 |
| `observedExplanationCount` | 같은 기준의 SUCCESS approved_explanations 관찰 전체 → 실제 items의 refId 합집합 개수. refId는 DecisionResearchStore가 review_explanation 전역 기본키로 만들므로 조직·조회별 복합키를 추가하지 않음. total이나 마지막 조회 건수로 대체하지 않고 미조회·식별 불가=null, 성공 빈 조회=0. 원본 변경 없음 |
| `moneyText/textOrNull/dateText/countOrNull` | 원문 값 → 형식 검증 값 또는 null. 조회 시각을 현재 시각·마스터 기준일로 대체하지 않음 |
| `warningTexts/appendWarnings` | 실제 제한·관찰 경고 → 중복 없는 표시 목록. 새 목록만 갱신하며 원본 응답 불변 |

호출은 `질문 → assistantPayload → askAssistant → 서버 동적 도구 루프 → 기준 검증 →
실제 호출 내역·analysisBrief/answerCitations → 분석 설명·요구 항목·observationTables/ReportDataTable`이다.
조회 전용 보고서는 같은 실행의 `publishExecution → acceptPreviewExecution →
buildPreviewResult → DecisionPreviewReport`로 연결한다. 출처는
`openPreviewSource → openObservation → observationForExecution`에서 실행 ID와 관찰 ID를 함께 검사한다.
검증된 요청 초안은 `reviewDraftSource → prepareDraft → prepareWorkRequest →
DecisionWorkflowPanel`로 이어지고 사용자가 내용·기한을 확인해 직접 저장한다.

`decision-conversation-api.test.js`는 이력 상한, 기준 분리, 숫자·null 인용, 원 금액 정밀 표시,
조직 이름, 초안 revision, HTTP 중단 신호를 검증한다. `report-display.test.js`는 실제 PrimeVue
HelperSet에서 반복 부모 key=0과 조건부 그룹의 충돌로 본문 열이 두 번 수집되는 경우를 재현하고,
ReportDataTable의 명시적 헤더 그룹 키가 중복을 없애는지 확인한다. 기존 병합 셀 SSR 검증도 유지한다.
`decision-preview-result.test.js`는 기준·목표 변경, 정밀 정렬, 미조회와 0건, 부분 실패·조회 시각,
빈 근거의 확인 질문, 잘못된 분석 설명을 숨겨도 성공 수치·출처가 유지되는지 검증한다.
`DecisionPreviewReport.test.js`는 SSR에서 실제 요약·사실/해석/가설·불확실성·미해결 질문·각 출처,
HTML 이스케이프와 기준 변경·조회 중 이전 숫자/출처/brief 숨김을 확인한다. 대화 회귀는 존재하지 않는
근거·finding 참조·불확실성 누락·미해결 COMPLETED 거부, 번호로 가설을 참조하는 후속 문맥,
반복되는 관찰 ID의 실행별 분리도 검사한다. 현재 전체 프론트 검증은 157건과 빌드 통과다.
합성 브라우저 확인 및 이전 실제 DB·모델 왕복 기록은 현재 brief 계약의 실제 모델 검증과 구분한다.

## 로컬 화면 확인용 임시 실행 도구

아래 파일은 사용자 승인 경로 `/private/tmp/pl-agent-verification/`의 임시 도구이며
제품 코드·배포 구성에 포함하지 않는다. 각 함수의 목적·Args·Returns·Raises·Side Effects는
해당 임시 파일의 한국어 명세에도 기록한다.

| 정의 | 입력·출력·호출 관계·예외와 부작용 |
| --- | --- |
| `readonly-agent-proxy.py: ReadOnlyPreview.respond_problem/proxy/log_message` | 루프백 요청 → 조회·메모리 로그인·저장 없는 대화만 전달, 나머지 변경 요청 403. 고정 오류 JSON·로그 생략. HTTP 통신만 수행. 실제 RDS 읽기 검증 시 18081에서 재실행하며, 미적용 저장 분석 목록은 503 안내 반환 |
| `preview-server.cjs: readFixture/readBody` | 기존 검증 스크립트·HTTP 요청 → 메모리 fixture·크기 제한 JSON. 파일 읽기·본문 오류 가능. 브라우저 실행 코드·실제 DB 연결 없음 |
| `previewResponse/previewHtml/previewLabels` | 합성 응답·기존 dist 문자열 → 고정 예시 안내·전역 합성 배너·합성 로그인·예시 문구. 원본 dist 파일은 변경하지 않음 |
| `serve/main/failure` | 루프백 정적 파일·합성 API 요청 처리 / 5180 서버 시작 / 오류 콘솔 표시. CSP로 외부 연결 차단. 제품·DB·모델 호출 없이 메모리 예시만 사용 |
