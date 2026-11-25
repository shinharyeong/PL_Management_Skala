# 프론트·이슈 수정 및 보고서 연결 함수 명세

이 문서는 develop `5366fa8` 이후 이슈 #57·#62·#63 작업과 PR #96 반영 후 PrimeVue 전환의 함수·호출 관계를 관리한다.
모든 기존 함수의 전수 목록은 아니다. 상세 입력·출력·예외·부작용은 정의 파일의 한국어
docstring/JSDoc을 기준으로 한다.

- [프론트 검토·제출·이력](frontend-issue-contract.md)
- [AI 저장 경계·보고서 조회](../../ai/docs/development/ai-issue-contract.md)
- [기존 파서 함수](../../parser/docs/development/function-reference.md)
- [공개 API와 화면 연결·추가 구현](api-integration.md)
- [PrimeVue 구성·화면 전환·검증](frontend-primevue.md)

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
