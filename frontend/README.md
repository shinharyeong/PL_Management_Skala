# 경영팀을 부탁해 프론트엔드

Vue 3 · Vite · Vue Router · Pinia 기반의 실제 API 연결 화면입니다. `/`와 `/prototype.html` 모두 동일한 연결본을 엽니다. 원래 데모 구성은 `src/prototype/HomePanel.vue`, `ManagementPanels.vue`, `OrgPanel.vue`, `store.js`에 남아 있습니다.

## 실행

```bash
npm ci
# 필요하면 .env.example을 .env.development.local로 복사해 API 주소를 지정합니다.
npm run dev -- --host 127.0.0.1
```

운영 빌드는 기존 이메일·인증코드 로그인을 사용합니다. localhost 개발 화면의 계정 선택은 `local` 백엔드와 `LOCAL_AUTH_LOG`, 로컬 `API_PROXY_TARGET`이 모두 필요합니다. 실제 서버 세션을 발급하며 계정이나 권한을 브라우저에서 위조하지 않습니다. 예시 계정은 로컬 seed/더미 환경에만 있습니다.

## 이번 수정의 범위

- 알림의 읽음과 검토 결과를 분리하고, 수락·재요청된 제출 알림을 읽음 처리합니다. 요약 카드와 모두 읽음 버튼은 제거했습니다. 알림에서 이동하면 해당 조직을 펼치고 자동 스크롤합니다.
- 연간 제출본 검토 상태·재제출 요청 강조, 선택 조직 일괄 수락(확인 창·조직별 실패 결과), 재요청 코멘트 전달을 연결합니다.
- 연간 master 상태·최신/이전 버전·확정 담당자/시각·포함 조직 수와 엑셀 다운로드를 제공합니다. `GET /api/v1/annual-masters?year=YYYY`, `GET /api/v1/annual-masters/{id}/file`은 경영기획팀 전용입니다. 새 DB 마이그레이션은 없습니다.
- 월간 비교 조건·계층 표·조직 작업공간·버전 비교·소명과 제출본 승인을 실제 API에 연결했습니다. 최신 업로드와 승인 master는 구분합니다.
- 모든 금액 계산은 decimal 문자열/BigInt로 처리하며 차이율·금액 조건의 경계는 `>=`입니다. 홈 올해 값은 누계가 아닌 실적+추정의 연간 전망입니다.
- 좁은 화면에서는 툴바가 줄바꿈되고 넓은 표는 개별 가로 스크롤됩니다. 결과 안내는 화면 하단에 고정 표시합니다.
- `.xlsx`/`.xlsb` 업로드와 요청별 파일명 안내를 제공합니다. 문구 편집은 [COPY-EDITING.md](COPY-EDITING.md)를 참고하세요.

백엔드/프론트 변경을 함께 반영해야 연간 master 조회가 동작합니다. 보고서 API는 최신 develop에 추가됐지만 현재 보고서 UI의 연결은 별도 작업이며, 비활성 카드 상태를 유지합니다. 이전 로컬 검증 기록의 보고서 API 부재 설명은 그 당시 기준입니다.

## 검증

```bash
npm run build
node --test src/prototype/*.test.js src/lib/gap-result-filter.test.js src/lib/submission-api.test.js scripts/local-auth-code-plugin.test.js
python3 -B scripts/test_dataset_scope.py
```

`check:prototype`는 옛 데모 15화면용 스크립트로 실제 연결본의 통합/브라우저 검증을 대체하지 않습니다. 사용자 파일·DB 백업·인증코드·토큰·실행 결과는 저장소에 포함하지 않습니다. `scripts/load-business-unit-dataset.py` 등은 별도 로컬 더미 DB 검증용이며 서비스 실행에 필요하지 않습니다. 테스트 계정과 과거 검증 환경은 [BUSINESSUNITS-TEST.md](BUSINESSUNITS-TEST.md)를 참고하세요.
