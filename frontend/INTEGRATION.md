> 과거 로컬 검증 기록입니다. 최신 develop에는 보고서 API가 추가됐으며, 현재 보고서 UI 연결은 후속 범위입니다. 현재 실행/기능 기준은 README.md를 따릅니다.

# 피그마 기반 프론트 API 연결

현재 실행은 BusinessUnits v2 전용 DB/서버(18183)로 전환했습니다. 아래 18182/합성 데이터 검증은 이전 단계 기록입니다. 현재 계정·로그·데이터 선택 어댑터·검증 결과는 [BUSINESSUNITS-TEST.md](./BUSINESSUNITS-TEST.md)를 참고하세요.

2026-11-24 기준. 연결 대상은 사용자가 PR로 병합한 `prototype.html`과 `src/prototype/` 화면이다. 저장소의 별도 `/` API 앱으로 화면을 대체하지 않는다.

## 연결 기준

- 최신 확인한 develop: `a472e97` (PR #55, 2026-11-24 09:54 KST).
- `docs/api/openapi.yaml`, `docs/api/api-spec.md`와 실제 Java controller/DTO를 함께 확인했다.
- 기본 진입점: `/` (호환 주소 `/prototype.html`); 라우팅은 기존 hash 경로를 유지한다.
- `main.js`와 실제 화면은 `live-store.js`를 사용한다. 기존 `store.js`, `HomePanel`, `ManagementPanels`, `OrgPanel`의 예시 데이터는 연결본에서 불러오지 않는다.
- 서버 금액은 십진 문자열 그대로 보관한다. 필터·차액·합계는 BigInt 기반으로 계산하고, 그래프의 픽셀 배치에만 Number를 쓴다. 기준 누락을 0으로 치환하지 않는다.

## 연결한 경로

| 화면 | 서버 연결 |
| --- | --- |
| 로그인 | 이메일 코드 요청, 실제 코드 인증, 역할별 메뉴, 로그아웃. 고정 체험 코드 제거. 미등록 이메일은 같은 화면 아래 오류 표시 |
| 홈 | 월/연간 손익·KPI·마감 상태·할 일·사업부별 손익·변동 원인·워터폴. NONE/잠정/확정 구분 |
| 월간 조직 목록 | 당월 요청·조직별 제출 상태·행 펼침·실제 회신 메시지·원본 다운로드 |
| 계산기 | PLANNER 계층 표와 상태·토큰·비교 종류, 정확한 금액/비율 조건, 검색·정렬 |
| 사업부 검토 탭 | 명시적 SELECT 후 생성, 셀 수 표시, 제외, 명시적 소명 요청 후 탭 제거 및 목록 복귀 |
| 셀 상세 | evidence/versions와 실제 버전 열·셀값·거래 내역·소명 이력, 소명 수락/재요청 |
| 연간 | 별도 요청 페이지, 조직 체크, 기한·메시지, 진행 현황, 회신 확장, 파일, 코멘트, 수락/재요청 |
| 알림 | 서버 요약·날짜 묶음·유형·읽음 상태·추가 페이지 조회·읽음 처리 |
| 조직 현황 | 조직/연월별 승인·제출 버전, 원본, 변경 이력 |
| 조직 담당자 | 실제 요청함·업로드·파싱 상태·진단·검증·변경 소명·제출 미리보기·최종 제출·이력 |

인증은 메모리 토큰만 사용한다. 명령은 Idempotency-Key를 보내고 검토/제출은 서버 stateToken을 사용한다. 오류를 예시 성공 상태로 바꾸지 않는다.

## 검증 결과와 범위

- 프로덕션 빌드 통과.
- `node --test src/prototype/*.test.js src/lib/gap-result-filter.test.js src/lib/submission-api.test.js`: 29개 통과. 기존 예시 흐름 테스트와 새 연결 transport/정밀 금액 테스트를 구분한다.
- 실제 프론트 store + 최신 Java/Python/PostgreSQL을 연결한 `scripts/check-live-api.mjs --apply` 통과: 미등록 계정 오류 → 실제 코드 로그인 → 당월 요청/조직/계층 값 → SELECT → 사업부 탭/배지 → 소명 요청 → 서버 상태 갱신/빈 탭 제거 → 알림 → 홈 양 기간 → 로그아웃.
- 브라우저에서 같은 화면의 미등록 이메일 오류, 로그인, 역할 메뉴, 홈 월/연 조회, 월간 목록과 회신 원본 표시를 확인했다. 계산기 이후 전 과정을 브라우저로 검증했다는 의미는 아니다.
- 사용자의 기존 DB·첨부 Excel은 사용하지 않았다. 합성 XLSX와 별도 로컬 DB `pl_monthly_qa`만 사용했다.

## 서버 제약 및 후속 QA

1. **최초 소명 회신 잠금:** 이전 월 비교본이 없는 월간 제출에 첫 소명을 요청하면 서버가 round 1을 생성하지만, PENDING 제출의 답변은 round > 1만 허용한다. 실제 HTTP 409 `EXPLANATION_LOCKED` 재현. `ReviewActionService.request`와 `ExplanationAnswerService.writable`의 회차 규칙을 백엔드에서 맞춰야 한다. 프론트에서 가짜 답변 성공이나 제출본 승인을 하지 않는다.
2. **임의 버전 vs Gap:** PLANNER Gap 표는 최신 제출본/고정 기준이다. 원래 시안의 임의 두 버전과 월/시나리오를 동시에 선택해 같은 검토 상태를 만드는 API는 없다. 연결본에서 기준을 고정 표시하고 셀 버전 비교는 실제 `/versions`로 조회한다. 다운로드는 필터 결과가 아니라 서버 버전 비교 Excel임을 명시한다.
3. **보고서:** 명세의 `/report/*`는 Java entity만 있고 공개 controller가 없다. 해당 화면에서 미지원 상태와 비활성 버튼을 표시한다. 연결 전 보고서 시안은 기존 `ManagementPanels.vue`에 보존했다.
4. **월간 상태 열:** 요청 대상 DTO에 조직별 소명 집계가 없어 목록의 소명 상태를 추측해 채우지 않는다. 계산기에는 서버 상태가 표시된다. 조직 목록용 소명 상태 필드/집계 API가 필요하다.
5. **화면 마무리 QA:** 알림의 과거 요청/특정 셀로 가는 세부 링크, 원본 조직 담당자 v3와 업로드 이후 화면의 배치 일치, 대량 조직 목록 성능, 오류/동시 변경·새로고침 후 작업공간 복원은 추가 확인 대상이다. 전체 화면의 시각적 동일성과 모든 버튼의 실서버 왕복이 완료된 상태는 아니다.

## 실행

```bash
API_PROXY_TARGET=http://127.0.0.1:18182 npm run dev -- --host 127.0.0.1 --port 14175 --strictPort
```

현재 연결본: `http://127.0.0.1:14175/prototype.html`.
별도로 띄운 원본 병합본: `http://127.0.0.1:14174/prototype.html`.

백엔드는 `/private/tmp/pl-management-frontend-20261124/backend`의 최신 빌드를 local 프로필로 실행했다. local 프로필은 이메일 대신 서버 로그에 인증코드를 출력한다. 실제 메일 전송은 SMTP 설정이 필요하다. 인증코드·토큰은 저장소에 기록하지 않는다.

```bash
# 조회 검증. 격리된 local 프로필 로그 경로를 지정한다.
API_CHECK_BASE=http://127.0.0.1:14175 \
API_CHECK_AUTH_LOG=/private/tmp/pl-monthly-qa-backend-latest-20261124.log \
node scripts/check-live-api.mjs
```

`--apply`는 테스트 스크립트가 만든 문구가 정확히 일치하는 합성 요청에만 선정·소명 요청 이력을 추가한다. 회사 DB에는 실행하지 않는다. 인증코드 재발급 제한 시간 안에 재실행하면 429가 발생할 수 있다.
