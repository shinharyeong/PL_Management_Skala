# 프론트 API 계약과 이슈 #63·#57

## 실행 범위

`frontend/index.html`, `frontend/prototype.html`, `frontend/src/main.js`는 모두
`frontend/src/prototype/main.js`로 연결한다. 이 진입점은 `live-store.js`와 실제 API를 쓰는
`PrototypeApp.vue`를 연다. 옛 `src/App.vue`·`src/router/`·`src/views/`는 지원하는 별도 앱이
아니며 현재 진입점에서 가져오지 않는다. 옛 컴포넌트를 재사용할 때는 현재 OpenAPI 계약으로
이식한 뒤 연결해야 한다. `source-cells`, 요청별 `template`·`forecast`·`annual-master`,
`financial-values`·`report-values`, `auth/profile-confirmation`은 기존 앱의 과거 계약이다.
백엔드에 이 경로를 임의로 추가하지 않는다.

옛 보고 작성 전용 `components/ReportAuthoring.vue`, `ReportBlockResult.vue`,
`lib/report-authoring.js`는 활성 사용처와 대응 백엔드가 없어 삭제했다.
`PlannerReports.vue`는 현재 `prototype/LiveReportPanel.vue`를 참조한다.
보고서 요청·숫자 표시·편집 규칙은 [함수 명세](function-reference.md)의 현재 구현을 따른다.

현재 연결본은 다음 계약을 사용한다.

| 화면 | 경로·조건 |
| --- | --- |
| 원천 근거 | `GET /review-items/{reviewItemId}/evidence`; 제출본 ID나 셀 주소로 항목 ID를 추측하지 않음 |
| 검토 선정·제외·소명 요청 | `POST /review-items/actions`; 각 항목에 현재 `expectedStateToken`, 제외 시 `note`, 소명 요청 시 `message`·`dueAt` |
| 제출 양식 | `GET /submission-template/file`; 연간·월간 공통, 미등록 오류는 서버 응답으로 안내 |
| 제출 이력 상세 | `GET /submissions/{id}`의 `explanations`·`inheritedExplanations`·`rejectedItems`·`history` |
| 비교 기준 | 서버 `Baseline.kind` 표시. `ANNUAL_PLAN`을 전월 승인본으로 표시하지 않음 |
| 제출 Excel | XLSX/XLSB 1개, 20MiB 이하. 이름 보정 시 실제 파일 확장자를 유지 |
| 소명 첨부 | 기존 PDF/XLSX/PNG/JPEG, 16MiB 계약 유지 |

금액 원문은 십진 문자열로 보존한다. 서버가 제공하는 `display*`도 어댑터가 지우지 않으며,
현재 표는 원 금액을 정밀도 보존 표시 함수에 전달한다. 이미 단위 변환한 `display*`에 단위
변환을 다시 적용하지 않는다.

## 함수 명세와 호출 관계

모든 경로는 저장소 루트 기준이다. 상세 입력·반환·예외·부작용은 각 함수 JSDoc을 따른다.

| 함수 | 역할·호출 관계 |
| --- | --- |
| `frontend/src/lib/review-api.js::reviewEvidencePath` | 소명 항목 ID 검증 → GAP-09 경로. `CurrentSubmissionReviews.select`에서 사용 |
| `reviewActionBody` | 선택한 실제 항목 ID·토큰, 제외 사유·요청 메시지 검증 → GAP-07 본문. `live-store.reviewAction`에서 사용 |
| `baselineLabel` | `ANNUAL_PLAN`·`PREV_MONTH_APPROVED`·`REJECTED`·`NONE` 표시. 검토·제출 확인·이력에서 사용 |
| `frontend/src/lib/submission-api.js::normalizeSubmission` | 제출 DTO 표시 변환, HIST-02 배열과 금액·토큰 보존 |
| `normalizeOrgReviewPage` | 항목·월 계층을 표시 목록으로 펼침; 기준 버전·표시 금액 보존 |
| `normalizeDiagnostics` | 페이지 구분 없는 진단 목록의 위치·수정 안내 보존 |
| `normalizeUploadRequest` | 요청 상세와 자기 조직 대상으로 기본 접수 가능 표시; 최종 권한은 서버 판정 |
| `frontend/src/lib/upload-filename.js::preserveWorkbookExtension` | 파일 바이트를 변환하지 않고 선택 파일의 XLSX/XLSB 확장자를 제출 이름에 유지 |
| `inspectUploadFilename` | 이름 보정 동의 조건과 조직·기간 확인; XLSB를 XLSX로 이름만 바꾸지 않음 |
| `frontend/src/components/CurrentSubmissionReviews.vue::load/select` | 목록 → 상세·첨부·원천 근거 조회, 이전 HTTP 취소 |
| `CurrentSubmissionReviews::command/attach/download/dispose` | 명세의 소명 작성·결정·첨부·다운로드·취소 |
| `frontend/src/components/CurrentSubmissionHistory.vue::load/download/eventLabel/dispose` | HIST-02 상세·원본 파일·처리 이력 표시·조회 취소 |
| `frontend/src/prototype/live-store.js::reviewAction/excludeItems/removeCartItem` | 현재 토큰과 사용자 제외 사유를 명령에 넣고 표·작업공간 갱신 |
| `frontend/src/prototype/MonthlyPanel.vue::excludeSelected` | 제외 성공 시에만 선택과 사유 초기화 |

## 검증 범위

`node --test src/lib/submission-api.test.js src/lib/review-api.test.js
src/lib/upload-filename.test.js src/prototype/live-api.test.js`는 금액·이력 보존, 실제 항목
ID와 토큰의 명령 전송, 제외 사유, 연간 계획 기준, XLSB 이름 보존을 메모리에서 검사한다.
HTTP는 테스트 안에서 대체하고 출력 파일·캐시·DB를 만들지 않는다.

실제 서버·브라우저 검증과 빌드는 이 검사와 별개다. 의존성 설치나 산출물 생성은 사용자
허락 범위를 확인한 뒤 수행한다.
