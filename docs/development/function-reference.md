# 이슈 수정 및 보고서 연결 함수 명세

이 문서는 develop `5366fa8` 이후 이슈 #57·#62·#63 작업의 함수·호출 관계를 관리한다.
모든 기존 함수의 전수 목록은 아니다. 상세 입력·출력·예외·부작용은 정의 파일의 한국어
docstring/JSDoc을 기준으로 한다.

- [프론트 검토·제출·이력](frontend-issue-contract.md)
- [AI 저장 경계·보고서 조회](../../ai/docs/development/ai-issue-contract.md)
- [기존 파서 함수](../../parser/docs/development/function-reference.md)
- [공개 API와 화면 연결·추가 구현](api-integration.md)

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
| `refreshJob()`, `uploadPdf(event)` | PDF 업로드·상태 조회 → 성공 정의와 마스킹 이미지 | Promise<void>; 실패는 run 처리; 서버 작업 생성·인증 이미지 조회 |
| `invalidate()` | 구성·기간 변경 후 데이터·AI 제안 초기화 | void; 메모리만 변경 |
| `addBlock()`, `changeBlock(id, direction)` | 기본 지표/문장 블록 추가·이동·삭제 → invalidate | void·정의 오류; 숫자를 만들지 않음 |
| `changeYear()` | 기준 연도 기간 조회·소명/질문 결과 초기화 | Promise<void>·HTTP 오류 |
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

작업 중 fieldset을 비활성화하고 화면을 떠나면 요청을 취소한다. 저장과 사용자 다운로드는
제품 기능이며, 개발 검증에서 해당 함수를 실행해 파일을 생성하지 않는다.

## 회귀 검증

`frontend/src/prototype/report-api.test.js`는 인증 요청을 메모리 대역으로 검사하고
실제 서버·PDF·캔버스를 만들지 않는다. 분류 query, 저장 버전 충돌 조건, 파일 검증,
십진 문자열과 누락값, AI 패치의 안정된 ID·숫자/SQL 금지, PNG 텍스트·병합 셀을 확인한다.
