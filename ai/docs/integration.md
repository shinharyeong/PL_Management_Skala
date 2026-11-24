# AI 서비스 통합

현재 1차 연결은 백엔드의 내부 HTTP 호출과 PostgreSQL `report` 스키마 읽기 전용 조회입니다. AI는 업로드 파싱·Gap 임계값 판정·소명 상태 변경·마스터 확정·틀 영속 저장을 수행하지 않습니다. db-mcp와 RabbitMQ는 API 명세 12절의 2차 인프라 범위입니다.

## 지원하는 계약

| AI 내부 경로 | 백엔드 용도 | 동작 |
|---|---|---|
| `POST /v1/pdf-jobs` | RPT-01 PDF 분석 | 업로드 바이트를 메모리에서 분석하고 작업 결과를 백엔드에 보고 |
| `POST /v1/data` | RPT-10 보고서 채우기 | 기준 월·조직·기간에 해당하는 뷰 값으로 블록 채우기 |
| `POST /v1/nl-query` | RPT-11 자연어 조회 | LangGraph 질의 생성·검사·실행·설명, 결과 미리보기 반환 |
| `POST /v1/edit` | RPT-15 편집 제안 | 실제 블록·지표에 대해 검증된 변경안만 반환 |
| `POST /v1/sql-check` | RPT-09 SQL 검사 | 허용된 읽기 전용 SQL인지 검사, 실행하지 않음 |
| `GET /health` | 상태 확인 | 모델 이름·키 존재 여부 반환, 모델 호출 없음 |

계약 API는 `X-Internal-Token`과 `REPORT_AI_TOKEN`을 대조합니다. 운영의 `AI_REQUIRE_INTERNAL_TOKEN=true`에서는 토큰 미설정도 거부합니다. PDF 작업 결과는 `AI_BACKEND_URL`의 `/internal/pdf-jobs/{jobId}/result`에 보고합니다.

`/v1/nl-query` 입력은 `{question, baseYear, baseMonth, conversationId?}`이며 응답은 `status: OK|CLARIFY|UNKNOWN`, SQL, 설명, 되묻기, `preview`, 행 수와 잘림 여부입니다. 기준 월의 뷰 버전이 없으면 `UNKNOWN`입니다. 조회 그래프는 해석 → 후속 질문 규칙 또는 SQL 생성 → 검사·읽기 전용 실행 → 제한된 자기 수정 → 숫자 검증 설명 → 근거 구성 → 메모리 문맥 갱신으로 이어집니다.

소수 및 큰 정수는 JSON 경계에서 십진 문자열로 보존합니다. SQL 소수·큰 정수의 XLSX는 텍스트 셀이며, 일반 `won` 숫자 셀은 백엔드의 double 변환과 Excel 유효자리 한계가 남아 있습니다. 후속 내보내기 타입 계약에서 다룰 범위입니다.

`/v1/data`는 `{base:{year,month}, blocks:[...]}`를 받아 `dataStatus: CONFIRMED|PROVISIONAL|NONE`과 채운 블록을 반환합니다. 화면의 반올림 글자 `text`와 원 단위 십진 문자열 `won`을 분리합니다. 백엔드 RPT-16 XLSX는 `won`을 사용합니다. SQL 블록은 같은 `sqlguard`와 버전·기간 바인딩을 사용하며 검사 실패 시 `missing`과 이유를 표시합니다.

`/v1/edit`는 `{definition, instruction}`을 받아 `EDIT|CLARIFY|REFUSED|UNAVAILABLE`과 변경 제안을 반환합니다. 직접 숫자 변경 요청은 거절합니다. 제안의 적용·저장은 사용자 확인 뒤 백엔드가 처리합니다.

## 조회 데이터와 소명 규칙

`AI_DB_SCHEMA` 기본값은 `report`입니다. 백엔드가 제공하는 `fact`, `v_op`, `gap`, `explanation`, `dim_version`, `dim_org`만 사용합니다. 연결마다 `search_path`를 지정하고 `default_transaction_read_only=on`을 적용합니다. 운영 계정에는 해당 뷰의 읽기 권한만 부여합니다.

- `v_op`의 영업이익을 읽으며 AI가 별도 OP 뷰·마스터를 만들지 않습니다.
- `dim_version.kind`, `is_latest`가 출처 상태와 기본 기준 월을 결정합니다.
- `gap`의 `ITEM_GAP`은 백엔드가 판정한 보고 항목×비교 기준입니다. 자체 OP Gap·이월·임계값 판정을 하지 않습니다.
- `explanation.gap_id`는 PLAN/PREVIOUS 중 첫 비교만 가리킬 수 있습니다. 양쪽 비교는 `gap.gap_id = explanation.gap_key || '-' || gap.basis`로 연결합니다.
- 수락 원문은 최신 회차와 현재 Gap 소명 상태가 수락인 경우만 보고 원인 카드에 넣습니다. 소명 건수는 검토 항목별 최신 회차로 세고 이력을 중복 집계하지 않습니다.
- 개발 화면의 예전 분류함 `assign`은 호환 입력만 받고 AI 사실 생성의 금액 배분에 사용하지 않습니다.

## 저장과 비용 지표

운영 AI 앱은 데이터 DDL·쓰기 연결·내보내기 파일을 제공하지 않습니다. 테스트 자료용 DDL과 쓰기 도우미는 `tests/db_support.py`로 분리했으며 `t_` 접두사 테스트 스키마로 제한합니다.

응답 캐시는 프로세스 메모리에만 최대 256개·1시간 유지합니다. 사용량 이벤트는 원문·응답·키를 제외한 허용 메타데이터만 stdout에 기록하며 최근 1,000개를 메모리에서 볼 수 있습니다. 성공·실패 호출만 일일 계수에 포함하고 캐시 적중·생략은 제외합니다. 메모리 캐시·계수는 재시작 시 초기화되고 인스턴스 간 공유되지 않으므로 전역 비용 한도는 외부 운영 정책이 필요합니다.

AI 채팅은 결과 미리보기와 근거를 반환합니다. 예전 `GET /v1/chat/files/{id}`는 제거했고, `/v1/chat`의 `file`은 항상 `null`입니다. XLSX는 백엔드 RPT-16에서 내보냅니다.

## 남아 있는 개발용 경로

`demo/ai_report_studio.html`과 `AI_PDF_DEV=1`에서 열리는 `/v1/dev/pdf-structure`, `/v1/dev/report-data/*`는 기존 고급 편집·문장 생성 기능을 확인하는 개발 화면입니다. `/v1/chat`, `/v1/report/key-messages`는 동일한 핵심 서비스를 확인하는 개발 입구입니다. 실제 기능이 남은 경로는 유지하고, backend·demo에서 호출이 없던 `/v1/report-authoring/*`, `/v1/dev/report-data/guess`, `/v1/dev/report-data/guess-block`는 제거했습니다. 별도 인증·구형 블록 스키마·직접 Responses 호출을 유지하지 않습니다.

미연결 `services/narratives.py`와 전용 테스트도 제거했습니다. 실제 화면이 사용하는 핵심 메시지 `key_messages`와 원인 한 줄 요약 `cause_summary`는 유지합니다. 개발 화면의 모든 기능이 새 경로로 이관됐다는 뜻은 아닙니다.

## 검증

소스 함수와 호출 관계는 [AI 이슈 변경 명세](development/ai-issue-contract.md)에 정리했습니다. `python3 -B ai/tests/test_stateless_runtime.py`는 외부 의존성·실제 DB·파일 생성 없이 실행합니다. FastAPI·LangGraph·PostgreSQL·LLM·브라우저 통합 검증은 별도 승인된 실행 환경에서 수행합니다.
