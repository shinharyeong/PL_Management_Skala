# PRD·Figma와 현재 구현 대응표

2026-11-17 기준. 보고서 화면을 제외한 새 PRD와 현재 소스의 대응 관계를 기록한다. **연결 구현**은 화면과 실제 API 계약이 연결됐다는 뜻이며, 전체 PRD 완료나 운영 배포 검증을 뜻하지 않는다. 실행 검증은 [로컬 실행 환경](local-environment.md)의 관측 기록을 별도로 따른다.

## 확인한 원문과 적용 원칙

- PRD: [경영팀을 부탁해 PRD v0.8](https://docs.google.com/spreadsheets/d/1T4uvFGLkFoBJYqmOGJbUO_S5ROngDN7F/edit?gid=1392943028). 연결된 Office 파일의 기능 요구사항 표를 읽었다. 소개의 예시 숫자보다 해당 FR 행을 우선한다.
- Figma: [경영팀을 부탁해](https://www.figma.com/design/ZW7ZlgvtY4phycygZqir3z/?node-id=2-2). 최신 경영기획 화면과 **각 조직 담당자 v3**를 기준으로 삼았다. PRD의 과거 v2 표기보다 사용자가 지정한 v3를 시각 기준으로 적용한다.
- 사용자가 업무 규칙도 새 PRD로 변경하도록 확정했다. 새 제출 검증은 매출·비용·손익 보고 항목별 Gap, 기준 초과 항목 소명, 검토 선정·제외, 선택 조직 요청을 적용한다. 과거 제출의 고정 비교 문맥은 변경하지 않는다.
- 선택 조직 발송과 최초 전사 마스터 필수 집합은 별개다. 연간 최초 마스터는 전사 필수 조직 수락 후 발행하고 이후 승인 원천을 교체한다.
- 원본값·수식·조직명을 자동 보정하지 않는다. 확인된 문제 위치·값·사유를 담당자에게 보여 주고 원본 수정 후 전체 파일 재제출로 처리한다.
- 모든 API 축약 경로에는 `/api/v1` 접두사가 붙는다. 아래 Figma 노드는 확인한 화면의 근거이며 PRD의 모든 행에 별도 프레임이 존재한다는 뜻은 아니다.

상태는 **연결 구현 / 일부 구현 / 미구현 / 범위 제외**로 구분한다. 서로 중복되는 `FR-YEAR`·`FR-REQ`, `FR-MONTH`·`FR-GAP` 요구는 같은 기능으로 묶으므로 단순 완료 비율을 계산하지 않는다.

## 인증·공통 탐색·알림

| PRD ID | Figma 노드 | 경로·컴포넌트 | API·현재 동작 | 상태·남은 차이 |
| --- | --- | --- | --- | --- |
| FR-AUTH-001~004 | `2:491`, `2:2656`, `2:2705` | `/` · HomeView | auth/codes → auth/sessions, 오류·재전송·만료 타이머·역할별 이동 | **일부 구현**. 서버 코드 인증은 연결했으며 현재 전달 방식은 LOCAL_PREVIEW다. 실제 이메일 발송·운영 인증 전달은 없음 |
| FR-AUTH-005 | 전용 화면 없음 | — | 서버 actor의 조직·역할 사용 | **미구현**. 최초 로그인 소속/역할 확인 전용 단계 없음 |
| FR-AUTH-006~007 | `403:259` | App · session | 401 시 재로그인, DELETE auth/session, 취소/로그아웃 확인 | **연결 구현** |
| FR-MENU-001~004 | `401:259` | App · router | 역할별 5개/2개 메뉴, 활성 강조·알림 배지·복귀 경로, 서버 권한 검사 | **연결 구현**. 보고서·미지원 설정 메뉴는 이번 탐색에서 제외 |
| FR-NOTI-001~005 | `393:266` | `/planning/notifications` · PlannerNotifications | notifications 목록/요약/필터, notifications/read, 날짜 그룹·전체 읽음·업무 대상 이동 | **연결 구현**. 최초 제출에 포함된 소명은 제출 알림에 묶고, 명시한 보완 요청의 실제 답변만 회신 알림. 검토 대기는 현재 PENDING 제출·소명이며 제외 항목은 세지 않음 |

## 전사 손익 현황

| PRD ID | Figma 노드 | 경로·컴포넌트 | API·현재 동작 | 상태·남은 차이 |
| --- | --- | --- | --- | --- |
| FR-HOME-001, 004 | `168:1818` | `/planning` · PlannerDashboard | 연도·월·조직·당월/연간 범위, master-periods·마스터·forecast | **연결 구현**. 연간 전망 미준비 시 연간 PLAN으로 명확히 구별 |
| FR-HOME-002~003 | `168:1903` | PlannerDashboard | 요청·최신 제출·현재 필수 review-items로 마감 5단계, D-day, 연간/월간 할 일 | **연결 구현**. 발송 대상 수와 전사 필수 수 분리 |
| FR-HOME-005 | `167:242` | PlannerDashboard | 매출·영업비용·영업이익 KPI 3개, 실제 고정 마스터/승인 전망 | **연결 구현**. PRD 기능 행의 3개를 우선하며 디자인 예시 금액 없음 |
| FR-HOME-006 | `168:2031` | PlannerDashboard | 손익 항목/당월 실적/계획 대비/계획 차이액/전월 추정 대비/전월 차이액, 비용 펼침·수락 소명 툴팁 | **일부 구현**. 비용 하위 항목의 확정 계획 비교는 report-values의 planAmount를 사용. 비용 하위의 전월 추정 projection은 없어 —. 전월 요청·승인 전망이 없을 때도 — |
| FR-HOME-007 | `167:372` | PlannerDashboard | 월간 REVENUE/COST 보고 항목 차액을 OP 영향으로 정렬한 Top 5. 연간은 조직 OP 차액 | **연결 구현**. 항목 링크에 requestId·확인된 organizationId·reportItemId 전달. 전사 항목 합계에는 임의 조직을 지정하지 않음. 자동 원인 문장은 생성하지 않음 |
| FR-HOME-008 | `168:1818`의 사업부 표 | PlannerDashboard | 마스터/승인 전망 organizations의 매출·비용·OP·계획 차액 | **연결 구현**. 없는 계획·전망을 0으로 채우지 않음 |
| FR-HOME-009 | 홈 손익 흐름 영역 | — | — | **미구현**. PRD의 매출원가·매출총이익 세부·순이익·영업외 분류에 대한 현재 보고 모델의 확정 대응이 없어 워터폴을 만들지 않음. 총 비용을 임의로 매출원가로 바꾸지 않음 |
| FR-HOME-010 | `168:2097` | PlannerDashboard | 월별 ACTUAL/ESTIMATE 막대·PLAN 선, 지표 선택, 같은 월 masters 최신 3개 카드 | **연결 구현**. 실제 버전·발행 시각·최초/갱신 구분·이전 버전·수락 원천 링크. 최신 금액을 과거 카드에 재사용하지 않음 |
| FR-HOME-011 | 보고서 연결 | — | — | **범위 제외**. 사용자의 보고서 제외 요청에 따라 메뉴·홈 진입을 추가하지 않음 |

표시 계산은 십진 문자열과 BigInt를 사용한다. 억원 카드의 2자리 반올림은 표시 규칙이며 원 단위·원금액 툴팁에서 실제 금액을 확인한다. 비용 증가는 Top 5에서 OP 감소 방향으로 변환하고 파생 합계를 원인 행에 중복 집계하지 않는다.

## 연간·월간 요청과 검토

| PRD ID | Figma 노드 | 경로·컴포넌트 | API·현재 동작 | 상태·남은 차이 |
| --- | --- | --- | --- | --- |
| FR-YEAR-001, FR-REQ-001 | `2:2261`, `2:2300` | `/planning/annual-plan/new` · PlannerRequestCreate | submission-requests POST, 연도·기한·메시지·organizationIds | **연결 구현**. 선택 조직만 발송하고 전사 필수 조직 집합 보존 |
| FR-YEAR-002, FR-REQ-004 | `2:1252` | `/planning/annual-plan` · PlannerRequests | 연간 요청 상세, selected 대상 진행표·상태 필터·제출 수 | **연결 구현**. `/planning/requests`는 호환 경로 |
| FR-YEAR-003, FR-REQ-007 | `2:1301` | PlannerRequests · SubmissionReviews | 행 확장, 실제 파일·시각·소명 회차, explanations 결정, submissions/decisions, annual-master | **연결 구현**. 반려 사유 필수, 수락은 서버 readiness·토큰 사용. 전사 필수 조직 수락 후 최초 연간 마스터 |
| FR-MONTH-001, FR-REQ-002 | `86:19`의 요청 흐름 | `/planning/gap` · PlannerGap | 확정 연간 계획에 대한 월간 요청 생성, 선택 조직·기한·메시지 | **연결 구현** |
| FR-REQ-003 | `2:2300` | PlannerRequestCreate · PlannerRequests | organizations/요청 대상의 recipients[] 이름·이메일, 조직 ✕ 제외 | **연결 구현**. 실제 활성 ORG_MANAGER만 표시. 미등록 조직은 ‘등록된 담당자 없음’; 담당자 관리 화면은 별도 미구현 |
| FR-REQ-005 | 전용 화면 없음 | — | — | **미구현**. 예약 발송 스케줄러 없음 |
| FR-MONTH-002~003 | `86:19`, `51:168` | PlannerGap 요청·제출 현황 모달 | 선택 조직 진행표·제출/초안 링크, 실제 상세에서 소명 검토·승인/반려 | **일부 구현**. 회신·승인 동작은 연결. 연간과 같은 월간 행 안 확장 회신 배치는 없으며 상세로 이동 |
| FR-MONTH-004, FR-GAP-001 | `86:19` | PlannerGap | 제출 두 버전 선택, submissions/{id}/comparison·comparison/file | **연결 구현**. 비교 조회·Excel은 실제 버전 기준. 다른 비교를 고르는 것만으로 고정 검토 기준을 바꾸지 않음 |
| FR-MONTH-005, FR-GAP-002, FR-SET-001 | `86:19` | PlannerGap 기준 영역 | gap-thresholds current/preview/저장, 차이율·최소 금액 활성화, 실적/추정 시나리오 | **연결 구현**. 켜진 기준을 모두 초과해야 함. 영향 미리보기는 저장하지 않음 |
| FR-MONTH-006, FR-GAP-003 | `51:168` | GapReviewTable | report-items 카탈로그와 실제 비교값으로 부모/자식·접기·검색·정렬·단위 | **연결 구현**. 실제 부모관계는 유지하고 루트 항목은 kind 매출/비용 표시그룹으로 묶음. 표시그룹·상위 금액을 임의 합산하지 않음 |
| FR-MONTH-007~008, FR-GAP-004~005 | `51:168` | PlannerGap · GapReviewTable | review-items/actions SELECT/EXCLUDE, 토큰·멱등키·제외 사유 저장, 상태 탭 | **연결 구현**. 현재 요청의 PENDING 제출본에만 명령 허용 |
| FR-MONTH-009, FR-GAP-007 | `51:168`의 상세 흐름 | SubmissionReviews 모달 | review-items/{id}, evidence/source-cells, 비교금액·근거·소명/첨부 | **연결 구현**. 확인된 원본 셀만 사용 |
| FR-MONTH-010, FR-GAP-008 | `55:244` 셀 × 버전 비교 | PlannerGap · SubmissionVersionComparison | 동일 조직·연도에서 최대 3개 제출본 선택, financial-values·source-cells로 원천 금액·차액·원문 나란히 조회 | **연결 구현**. 상세 ID·보고 항목·계정·팀·과제·연월의 명시 키가 고유할 때만 대응. 좌표가 같다는 이유로 연결하지 않으며 중복·ID 누락은 독립 행. PRD의 좌표 중심 동일 셀 가정을 그대로 적용한 것은 아님. 소명·승인 동작은 기존 검토 모달 사용 |
| FR-MONTH-011, FR-GAP-009 | 상세의 회신 이력 | SubmissionReviews · ReviewAttachments | 소명 회차별 답변·수락/보완 메모·첨부, 기존 이력 보존 | **연결 구현**. 신규 답변·첨부는 현재 허용 회차에만 작성 |
| FR-MONTH-012, FR-GAP-006, FR-REQ-006 | 상세의 소명 요청 | PlannerGap | 선택 항목의 REQUEST_EXPLANATION, 기한·메시지·필수 토큰·멱등키 | **연결 구현**. 동일 요청 안의 선택 항목 최대 100개. 임의 비교 결과에 새 검토 문맥을 생성하지 않음 |

연간 계획 확정·연간/월간 마스터·승인 전망 보조 컴포넌트는 유지한다. 계획 확정은 명시 Contingency와 승인 원천을 고정하고, 진행 화면의 수락과 혼동하지 않도록 접힌 보조 영역에서 제공한다.

## 조직 담당자 제출·아카이브

| PRD ID | Figma 노드 | 경로·컴포넌트 | API·현재 동작 | 상태·남은 차이 |
| --- | --- | --- | --- | --- |
| FR-SUBM-001 | `202:208` | `/requests` · RequestList | 본인 발송 대상 요청, 상태·기한·myWorkflow 다음 동작 | **연결 구현**. 기본 접수 중이며 체험 조직 전환을 업무 화면에서 제거 |
| FR-SUBM-002 | `202:263` | `/requests/:id/upload` · UploadView | submission-requests/{id}/template·template/file | **연결 구현**. 실제 등록 양식만 다운로드. 미등록은 안내/비활성 |
| FR-SUBM-003~005 | `202:263`, `202:383` | UploadView → OrgSubmissionWorkflow | 제출 업로드·파싱 상태 폴링·validation, diagnostics/source-cells | **연결 구현**. 새 업로드만 자동 검증 연결. 원본 오류는 담당자 수정·전체 재업로드 |
| FR-SUBM-006~007 | `202:562` | OrgSubmissionWorkflow · SubmissionReviews | Gap 필수 항목별 종류·월·기준/현재/차액·근거·답변, readiness | **연결 구현**. 필수 소명 완료 전 최종 제출 차단. 원인/향후 대응은 v3 단일 본문에 저장 |
| FR-SUBM-008 | `202:1985` | OrgSubmissionWorkflow 확인 단계 | submit, 기대 토큰·멱등키·readiness.canSubmit | **연결 구현**. 파싱 성공을 제출 완료로 표시하지 않음 |
| FR-SUBM-009~010 | `202:2186` | `/history` · HistoryView, 제출 상세 | 완료 제출 목록·원본·검증·소명 회차 | **연결 구현**. 초안은 명시 상태 필터에서 별도로 조회 |
| FR-SUBM-011~012 | `202:2707` | 반려 안내 → UploadView | 기존 요청으로 전체 파일 재제출, 같은 달 반려본/새 달 전월 승인본과 비교 | **연결 구현**. 기존 파일과 과거 회차 보존 |
| FR-SUBM-013 | `202:3214` | OrgSubmissionWorkflow · SubmissionReviews | 미답변 현재 보완 회차 답변·attachments | **연결 구현**. 소명 보완만으로 제출 파일·버전을 바꾸지 않음 |
| FR-ARCH-001~002 | `134:189` | `/planning/organizations` · PlannerOrganizations | 조직·연도, 월별 최종 제출 회차, 버전 목록·원본 파일 | **연결 구현**. 최종 제출과 승인 완료를 구별하고 초안 포함은 선택 |
| FR-ARCH-003 | `134:189` | PlannerOrganizations 변경 이력 | 선택 월/버전 review-items, 날짜·번호·코드·항목·소명 5열, 날짜 병합·최신순·소명/재소명 | **일부 구현**. 표는 목록 응답의 현재 소명 회차이며 전체 회차는 제출 상세 링크로 확인. 과거 회차를 한 표에 모두 펼치지는 않음 |
| FR-ARCH-004 | `134:189` | 왼쪽 월별 마스터 카드 | master-periods 진행/발행 상태, masters/{id}·masters/{id}/file | **연결 구현**. 실제 요청이 있는 월의 수락 진행과 발행 버전·Excel을 표시 |

## 설정과 범위 제외

| PRD ID | 현재 처리 | 상태·남은 차이 |
| --- | --- | --- |
| FR-SET-002 | RequestEditor에서 요청별 기한·메시지·개폐·추가 조직 PATCH, 실제 XLSX 양식 등록/교체·다운로드 | **일부 구현**. 요청별 설정은 연결. 전역 메시지 템플릿 목록·선택·관리 화면은 없음 |
| FR-SET-003 | 등록된 조직·활성 담당자 조회와 서버 역할 권한 사용 | **미구현**. 조직·수신자·권한을 편집하는 설정 UI/API 없음 |
| FR-SET-004 | 보고서 사유 매핑과 연결되는 소명 유형 목록 관리 | **범위 제외**. 보고서 제외 범위이며 자유 본문 소명은 사용 |
| FR-RPT 전체 | 기존 소스·직접 경로 보존 | **범위 제외**. 이번 화면 재설계·검증 대상 아님. 월간 비교와 아카이브 Excel은 보고서 작성 화면과 별도 기능 |

## 검증 범위와 변경 경계

프론트 담당 중간 점검에서 기획 화면 script 구문, 정확한 십진 계산 8개 메모리 검증, 변경 파일 공백 검사를 통과했다. 조직 담당 검증은 단위 변환·7개 script와 첨부 H2 7개 통합 테스트를 보고했다. 최종 Docker 재빌드에서 frontend/backend/postgres가 모두 healthy이며 Java 전체 176건 중 174건 통과·선택 검사 2건 제외·실패 0건이다. Chrome의 홈·월간 비교/3버전 원본·알림·연간/조직 현황·담당자 동선 확인 결과는 local-environment.md에 기록했다. 이 검증을 모든 PRD 기능의 완료나 운영 인증 검증으로 확대하지 않는다.

3버전 원천 비교는 script 구문과 9개 메모리 검사로 상세 ID 누락·중복 격리, 좌표 이동/다른 ID 구분, 소수 6자리·안전 정수 초과 차액, 실패/부분 적재, 0과 누락, 과제 변경을 확인했다. 기존 로컬 request 6의 한 조직에서 ACTUAL·PLAN 각 59개 원천 행과 각 대표 셀을 읽어 위치·환산금액 일치를 확인했다. 같은 조직의 3번째 승인본도 59행을 읽었고 PLAN↔현재 ACTUAL은 명시 키 59개가 전부 일치했다. 3번째 과거본은 다른 명시 키여서 3본 공통 키는 0개였으며 강제 연결하지 않는다. API 읽기 확인과 전체 브라우저 시나리오 검증은 구분한다. API 점검은 조회만 수행했다.

Figma와 PRD 원본, Data/ 원본 데이터, 과거 확정 비교·제출 기록을 수정하지 않았다. 요청·제출·승인·양식 등록·첨부·다운로드는 명시 사용자 동작으로 실행하며, 화면 점검만으로 최종 제출/승인을 자동 실행하지 않는다. 소스 변경의 자세한 함수·호출 관계는 [프런트엔드 구현](frontend-guide.md), 서버 계약은 [API 명세](api-contract.md)를 따른다.
