# 실행 프론트 PrimeVue 전환

2026-11-24, PR #96이 반영된 develop `6a4c8a0` 이후의 UI 전환이다. 실행 진입점
`frontend/src/main.js` → `prototype/main.js` → `PrototypeApp.vue`와 그 하위 업무 화면을
대상으로 한다. 저장소에 남은 비활성 데모·과거 API 앱을 다시 연결하지 않는다.

## 패키지와 공통 설정

| 패키지 | 고정 버전 | 역할 | 라이선스 |
| --- | --- | --- | --- |
| `primevue` | `4.5.5` | Vue 입력·목록·표·대화상자·알림 | MIT |
| `@primeuix/themes` | `2.0.3` | Aura preset | MIT |
| `primeicons` | `7.0.0` | 버튼·파일 등 아이콘 | MIT |
| `chart.js` | `4.6.2` | PrimeVue Chart의 그래프 렌더러 | MIT |

버전과 라이선스는 설치된 각 `package.json`에서 확인했다. `frontend/package.json`과
lockfile에 버전을 고정한다. 이번 선택은 PrimeVue v4이며 v5로의 전환은 포함하지 않는다.
컴포넌트 API는 설치된 v4 소스를 기준으로 검증한다.

`prototype/primevue.js::installPrimeVue(app)`가 Aura의 primary를 blue로 설정하고
한국어 locale, ripple, ToastService를 설치한다. darkModeSelector는 false다.
`prototype/main.js`는 이 설치를 마친 앱에 Pinia·Router를 연결하고 마운트한다.
업무 화면 5개는 defineAsyncComponent로 나누어 로드하며 운영 빌드의 500kB 초과 chunk 경고를 없앴다.
각 SFC는 실제 사용 컴포넌트를 `PButton`, `PDataTable`처럼 명시적으로 import한다.

CSS 순서는 `legacy, primevue, app`이다. 기존 prototype/home/monthly/management/org
및 반응형 스타일은 `@layer legacy`로 감싸고, 테마는 `primevue`, 공통 레이아웃과 전환된 월간·조직 화면 보완은
`primevue-layout.css`, `monthly-live.css`, `org-live.css`의 `app`에 둔다. SFC의 scoped 스타일은 wrapper 변경·표 셀·차트 크기
등 해당 화면 보완에 사용한다. 선택 상태는 Button severity/variant·Tag severity로 연결해
기존 `.active` 색상 규칙만으로 상태가 표현되지 않도록 한다.

## 화면별 구성

| 화면 | 사용 컴포넌트와 유지한 동작 |
| --- | --- |
| 로그인·메뉴·전역 안내 | InputText·Menu·Badge·Dialog·Message·ProgressBar·Toast. 기존 인증 요청과 권한별 라우트 유지 |
| 전사 홈 | Card·DataTable·ColumnGroup·Chart. 기간별 손익·마감·할 일·사업부 표와 손익 흐름/원인 차트 |
| 월간 관리·계산기 | Select·Checkbox·DataTable·TreeTable. 대·중·소 계층과 leaf 선택, 검토 항목 ID/stateToken·정밀 합계 보존 |
| 원천·버전·소명 검토 | 시트별 DataTable 그룹·동적 버전 열·Panel. 누락 버전 값·소명 회차·첨부와 별도 소명 결정 유지 |
| 연간 관리·조직 이력 | InputNumber·DataTable 확장 행·Dialog. 연도 선택·대상 조직 선택·일괄 수락·마스터 다운로드 |
| 조직 제출·소명 | FileUpload·SelectButton·DataTable·Dialog·Textarea. 파일 선택과 실제 업로드를 분리하고 제출 미리보기 후 확정 |
| 알림 | Button·Checkbox·Card·Tag. 유형/미읽음 필터와 읽음·검토 결과 구분 유지 |
| 보고서 | SelectButton·FileUpload·InputNumber·Select·Card·Panel·ReportDataTable·ReportChart. 기존 5단계 API 흐름 연결 |

폼·섹션·제목·목록은 의미 있는 HTML 구조를 유지한다. 넓은 표는 표 컨테이너에서
스크롤하며 전체 화면의 폭을 늘리지 않는다. Toast의 긴 상세 안내는 펼쳐 읽을 수 있다.
원본 PDF와 같은 임의 배치·시각 디자인까지 재현하는 기능은 이번 범위가 아니다.

## 입력·파일·금액 계약

- PrimeVue의 `update:modelValue`는 DOM 이벤트가 아닌 값이다. Select·Checkbox·InputNumber
 에서 `$event.target.value`로 읽지 않는다. Checkbox는 boolean 또는 ID 배열 모델을 사용한다.
- InputNumber는 연도·이동 개월 수처럼 범위가 정해진 정수에만 사용한다. 서버 금액·ID를
  InputNumber로 넘기지 않는다. 연도는 2000~9999, 보고서 이동 월은 -24~24 정수다.
- 회신 기한은 `InputText type="datetime-local"`의 문자열을 유지하고 기존 seoulDeadline으로
  서울 시간 기준을 적용한다. DatePicker 변환으로 시간대를 바꾸지 않는다.
- 제출 파일은 XLSX/XLSB 1개·20MiB, 소명 근거는 PDF/XLSX/PNG/JPEG 1개·16MiB다.
  FileUpload 선택 검증은 메모리만 변경한다. 제출은 **검증 시작**, 소명은 **첨부하기**에서
  기존 token·Idempotency-Key 계약으로 전송한다. 제출 이름을 보정할 때 XLSB 확장자를 유지한다.
- 보고서 PDF는 30MiB 이하며 **PDF 선택·분석**에서 기존 분석 요청을 시작한다. 이 흐름은
  제출 Excel의 선택 후 검증 흐름과 구별한다.
- 표·본문·차트 툴팁은 서버 십진 문자열을 사용한다. 계층 합계·차액·비율은 exact-money
  연산을 유지한다. Number는 Chart.js 좌표에만 쓰며 좌표를 업무 값으로 저장하지 않는다.
- ReportDataTable은 header_rows·hdr·hide·colspan·rowspan을 보존한다. 병합된 자리도 원본
  열 좌표를 유지한 채 숨기므로 다음 셀의 값이 앞 열로 이동하지 않는다.
  본문 pass-through는 DOM 속성 `rowSpan` 표기로 전달해 PrimeVue의 `rowspan=null`
  속성이 원본 세로 병합을 덮지 않도록 한다. 실제 PrimeVue SSR 회귀로 확인한다.
- ReportChart는 bar·line·donut·waterfall을 표시한다. 누락 값은 0으로 만들지 않고,
  정확한 원문은 툴팁과 펼칠 수 있는 데이터 표에 제공한다.

공개 API 경로·승인 규칙·저장 계약을 UI 라이브러리 전환에 맞춰 바꾸지 않았다.
소명 수락과 제출본 승인·마스터 반영은 계속 별도 단계다.

## 호출 관계

```text
main.js → prototype/main.js → installPrimeVue → PrototypeApp
  ├─ Menu → selectMenu → 기존 Router / 활성 조직
  ├─ store.toast → FeedbackToast → ToastService
  ├─ LiveHomePanel → DataTable / Chart → 서버 원문 표시
  ├─ MonthlyPanel → LiveGapTable → buildGapTree → TreeTable
  │                 └─ LiveReviewDetail → groupVersionRows → DataTable / Panel
  ├─ LiveManagementPanels → DataTable / Dialog
  │                        └─ LiveNotifications → Card / Tag
  ├─ LiveOrgPanel → submissionFileSelection → 선택 상태 → upload
  │   ├─ CurrentSubmissionHistory → 기존 HIST-02 조회
  │   └─ CurrentSubmissionReviews → evidenceFileSelection → 선택 상태 → attach
  └─ LiveReportPanel → reportRequest → 기존 보고서 API
      ├─ ReportDataTable → reportTableModel / reportBodyCell
      └─ ReportChart → reportChartModel / exactTooltip
```

각 함수의 입력·반환·예외·부작용은 [함수 명세](function-reference.md)에 정리했다.

## 검증과 남은 확인

현재 확인한 범위는 다음과 같다.

- `npm run test:prototype`: 59개 통과. 기존 API 계약과 신규 파일 선택, 계층 합계·선택 조건,
  시트 그룹, PDF 병합 셀·헤더, 큰 금액·누락값·차트 원문 보존을 포함한다.
- `npm run build`: Vite 빌드 성공. 설치된 PrimeVue v4와 Vue 컴파일러를 사용했다.
- 별도 메모리 검사: 변경 SFC 구문·템플릿·스타일 컴파일, 30자리 금액과 큰 ID 보존,
  실제 PrimeVue SSR의 TreeTable 선택 가능/불가 및 버전 표·그룹 열 배치 확인.
- 실제 Chrome과 테스트 API 응답으로 기획 메뉴 6개 이동, 홈 표 2개·차트 2개, 보고서 생성·데이터 채우기·병합 헤더/본문 rowspan·정밀 문자열, 로그아웃 취소/확정, 조직 XLSB 선택/제거와 자동 전송 없음 확인. 브라우저 오류 0건.
- 390px 모바일에서 보고서 문서 폭도 390px이며 넓은 표만 내부 스크롤한다. 스크린샷은 승인된 임시 브라우저 경로에서만 보관한다.
- 실제 백엔드가 실행되지 않아 서버 데이터로 인증·업로드·검증·승인·PDF 작업·AI 응답·파일 다운로드까지의 통합 검증은 수행하지 않았다.

설치·빌드·브라우저 검증은 사용자에게 별도로 허용받은 범위에서 진행했다. 사용자 원본
Data·비밀 설정을 바꾸지 않으며 브라우저 대역 응답 검증을 실제 서버 동작으로 기록하지 않는다.
기존 `check:prototype`은 과거 데모용이므로 현재 업무 화면의 검증을 대신하지 않는다.
