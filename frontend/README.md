# 경영팀을 부탁해 프론트엔드

Vue 3 · Vite · Vue Router · Pinia · PrimeVue 기반의 실제 API 연결 화면입니다. `/`와 `/prototype.html` 모두 동일한 연결본을 엽니다. 원래 데모 구성은 `src/prototype/HomePanel.vue`, `ManagementPanels.vue`, `OrgPanel.vue`, `store.js`에 남아 있습니다.

`src/main.js`도 같은 연결본을 사용합니다. 과거 `src/App.vue`·`src/router/`·`src/views/`는
별도 지원 앱이 아니며 현재 진입점에서 실행하지 않습니다. 과거 컴포넌트를 연결하기 전에는
[현재 API 계약과 함수 명세](../docs/development/frontend-issue-contract.md)를 확인하세요.

UI 의존성은 PrimeVue `4.5.5`, Aura를 제공하는 `@primeuix/themes` `2.0.3`, PrimeIcons
`7.0.0`, Chart.js `4.6.2`로 고정했습니다. 네 패키지의 라이선스는 MIT입니다.
테마·입력 이벤트·표·차트의 유지보수 기준은 [PrimeVue 전환 문서](../docs/development/frontend-primevue.md)를 따릅니다.

## 실행

```bash
npm ci
# 필요하면 .env.example을 .env.development.local로 복사해 API 주소를 지정합니다.
npm run dev -- --host 127.0.0.1
```

운영 빌드는 기존 이메일·인증코드 로그인을 사용합니다. localhost 개발 화면의 계정 선택은 `local` 백엔드와 `LOCAL_AUTH_LOG`, 로컬 `API_PROXY_TARGET`이 모두 필요합니다. 실제 서버 세션을 발급하며 계정이나 권한을 브라우저에서 위조하지 않습니다. 예시 계정은 로컬 seed/더미 환경에만 있습니다.

## 제공하는 화면

- 알림의 읽음과 검토 결과를 분리하고, 수락·재요청된 제출 알림을 읽음 처리합니다. 요약 카드와 모두 읽음 버튼은 제거했습니다. 알림에서 이동하면 해당 조직을 펼치고 자동 스크롤합니다.
- 연간 제출본 검토 상태·재제출 요청 강조, 선택 조직 일괄 수락(확인 창·조직별 실패 결과), 재요청 코멘트 전달을 연결합니다.
- 연간 master 상태·최신/이전 버전·확정 담당자/시각·포함 조직 수와 엑셀 다운로드를 제공합니다. `GET /api/v1/annual-masters?year=YYYY`, `GET /api/v1/annual-masters/{id}/file`은 경영기획팀 전용입니다. 새 DB 마이그레이션은 없습니다.
- 월간 비교 조건·계층 표·조직 작업공간·버전 비교·소명과 제출본 승인을 실제 API에 연결했습니다. 최신 업로드와 승인 master는 구분합니다.
- 업무 금액 계산은 decimal 문자열/BigInt로 처리하며 차이율·금액 조건의 경계는 `>=`입니다. 차트 좌표만 근삿값을 쓰고 표·툴팁은 서버 금액 문자열을 보존합니다. 홈 올해 값은 누계가 아닌 실적+추정의 연간 전망입니다.
- 평면 목록은 DataTable, 월간 대·중·소 분류는 TreeTable, 확인은 Dialog, 처리 안내는 Toast로 제공합니다. 좁은 화면에서는 툴바가 줄바꿈되고 넓은 표는 개별 가로 스크롤됩니다.
- `.xlsx`/`.xlsb` 업로드와 요청별 파일명 안내를 제공합니다. 문구 편집은 [COPY-EDITING.md](COPY-EDITING.md)를 참고하세요.
- 보고서 메뉴는 `LiveReportPanel.vue`와 실제 보고서 API를 사용합니다. PDF/직접 작성/저장 틀 선택 → 구성 확인 → 기간·지표 → 승인 소명 분류 → 데이터 채우기·AI 편집 확인·버전 저장·내보내기를 연결했습니다. 병합 표는 ColumnGroup과 셀 속성, 차트는 Chart.js로 표시합니다.

백엔드/프론트의 같은 계약을 함께 반영해야 합니다. PDF 분석·AI 조회에는 백엔드와 AI 서비스가
필요합니다. XLSX는 서버 내보내기, PNG는 결과 텍스트를 그린 이미지, PDF는 브라우저 인쇄
저장입니다. 원본 PDF의 임의 레이아웃 재현과 미연결 업무 API는
[API 연결·남은 범위](../docs/development/api-integration.md)에서 구분합니다.

## 검증

```bash
npm run build
npm run test:prototype
```

2026-11-24 PrimeVue 전환 검증에서 프론트 테스트 58개와 Vite 빌드가 통과했습니다.
브라우저 확인은 진행 중이며, 실제 백엔드가 실행되지 않은 상태이므로 로그인부터
업로드·승인·AI·다운로드까지의 서버 연동 완료를 뜻하지 않습니다. 검사 범위와 한계는
[전환 검증 기록](../docs/development/frontend-primevue.md#검증과-남은-확인)을 참고하세요.

`check:prototype`는 옛 데모 15화면용 스크립트로 실제 연결본의 통합/브라우저 검증을 대체하지 않습니다. 사용자 파일·DB 백업·인증코드·토큰·실행 결과는 저장소에 포함하지 않습니다. `scripts/load-business-unit-dataset.py` 등은 별도 로컬 더미 DB 검증용이며 서비스 실행에 필요하지 않습니다. 테스트 계정과 과거 검증 환경은 [BUSINESSUNITS-TEST.md](BUSINESSUNITS-TEST.md)를 참고하세요.

## 월간 검토 화면 통합

기존 서비스 화면 디자인을 유지하면서 최신 보고서·업로드 API 계약을 사용합니다. 계산기는 전체/검토중/소명 중/제외됨/승인됨 필터와 사용자 입력 Gap 조건을 제공합니다. 대 분류 접기, 항목 셀 전체 선택, 오른쪽 경계를 고정한 열 너비 조절을 지원합니다. 버전별 소명 이력과 담당자 메모를 나란히 표시합니다.

새로고침 로그인 유지는 [SESSION-PERSISTENCE.md](./SESSION-PERSISTENCE.md), 로컬 문구 편집은 [COPY-EDITING.md](./COPY-EDITING.md)를 참고하세요. 월간 추가 발송은 `POST /api/v1/submission-requests/{requestId}/targets` 백엔드가 필요하며 현재 develop 백엔드에는 없습니다.
