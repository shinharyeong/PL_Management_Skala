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
| `POST /v1/decision-agent/assistant` | 근거 기반 업무 대화 | LangGraph가 권한 제한 업무 Tool을 선택하고 실제 관찰에 연결한 답변 반환 |
| `GET /health` | 상태 확인 | 모델 이름·키 존재 여부 반환, 모델 호출 없음 |

계약 API는 `X-Internal-Token`과 `REPORT_AI_TOKEN`을 대조합니다. 운영의 `AI_REQUIRE_INTERNAL_TOKEN=true`에서는 토큰 미설정도 거부합니다. PDF 작업 결과는 `AI_BACKEND_URL`의 `/internal/pdf-jobs/{jobId}/result`에 보고합니다. 결과의 `definition.extraction.pages`는 쪽 번호별 크기(`{no, width, height}`, pt)이며, 블록의 `bbox`가 이 크기 기준이라 화면이 쪽 그림 위에 박스를 올릴 때 씁니다(백엔드의 `pages` 응답에는 쪽 크기가 없습니다).

`/v1/nl-query` 입력은 `{question, baseYear, baseMonth, conversationId?}`이며 응답은 `status: OK|CLARIFY|UNKNOWN`, SQL, 설명, 되묻기, `preview`, 행 수와 잘림 여부입니다. 기준 월의 뷰 버전이 없으면 `UNKNOWN`입니다. 조회 그래프는 해석 → 후속 질문 규칙 또는 SQL 생성 → 검사·읽기 전용 실행 → 제한된 자기 수정 → 숫자 검증 설명 → 근거 구성 → 메모리 문맥 갱신으로 이어집니다.

소수 및 큰 정수는 JSON 경계에서 십진 문자열로 보존합니다. XLSX는 일반 `won`과 SQL 숫자 모두 소수 또는 15자리를 넘는 정수이면 원문 텍스트 셀로 기록합니다. 15자리 이하 정수만 숫자 셀로 기록하며, SQL에서 이미 문자열로 반환한 값은 그대로 문자열로 유지합니다. 이 정책은 자릿수 손실과 소수가 정수처럼 표시되는 문제를 막지만, 텍스트 금액은 Excel 수식의 숫자 셀과 구분해야 합니다.

`/v1/data`는 `{base:{year,month}, blocks:[...]}`를 받아 기존 `dataStatus: CONFIRMED|PROVISIONAL|NONE`·`blocks`와 추가 출처 필드 `source`를 반환합니다. `source`는 실제 `fill_report` 결과의 버전과 기준에서 만든 `{version,year,month}`이며, 버전·반환 기준이 없거나 형식이 유효하지 않으면 `null`입니다. 요청 연월로 출처를 대신 채우지 않습니다. 이 필드는 최상위 보고 기준을 설명하며 개별 블록의 다른 기간까지 고정했다고 보증하지 않습니다.

화면의 반올림 글자 `text`와 원 단위 십진 문자열 `won`을 분리합니다. 백엔드 RPT-16 XLSX는 `won`을 사용합니다. 기존 보고서 UI의 SQL 블록은 같은 `sqlguard`와 버전·기간 바인딩을 사용하며 검사 실패 시 `missing`과 이유를 표시합니다. 아래 Agent 경로에는 더 제한된 자료 틀 범위와 출처 검사가 적용됩니다.

`/v1/edit`는 `{definition, instruction}`을 받아 `EDIT|CLARIFY|REFUSED|UNAVAILABLE`과 변경 제안을 반환합니다. 직접 숫자 변경 요청은 거절합니다. 제안의 적용·저장은 사용자 확인 뒤 백엔드가 처리합니다.

## Agent의 보고서·전사 소명 도구

Agent는 기존 Spring 업무 서비스에 연결된 도구 19개를 사용합니다. `plan_analysis`, `finish_answer`, `repair_answer`는 별도의 로컬 제어 함수이며 이 개수에 포함하지 않습니다. 다음 네 도구가 기존 보고서·소명 API의 서비스를 재사용합니다.

| 도구 | 입력과 업무 범위 |
| --- | --- |
| `report_templates` | 저장된 자료 틀의 실제 ID·버전·블록 목록 조회. `nextCursor`와 `blocksTruncated`로 페이지·목록 생략을 표시합니다. |
| `report_preview` | 같은 실행에서 확인한 `templateId`·`version`·선택 `blockId`를 고정 기준으로 채웁니다. 정의·SQL·기간·숫자를 모델 인자로 받지 않습니다. |
| `report_export` | 같은 실행의 성공한 `previewObservationId`를 기존 XLSX 내보내기 서비스에 연결합니다. 실제 준비된 자료의 ID·파일 정보·만료 시각·`ready`를 반환합니다. |
| `explanation_overview` | 기존 비교별 전사 소명·분류·수락 전 현황 조회. 비교 기준, 선택 조직, 페이지 위치를 받으며 분류·소명 상태를 변경하지 않습니다. |

보고서는 자료 틀 목록 → 미리보기 → XLSX 준비 순서입니다. 백엔드가 선택 마스터와 기존 보고서 뷰의 최신 마스터·원천 제출본을 대조하고, 채우기 응답의 `source`·확정 상태 및 실행 중 변경 여부를 검사합니다. 구마스터를 최신 버전으로 자동 교체하지 않습니다. 미리보기만 성공한 경우에는 파일 준비 완료로 답하지 않습니다. 내부 출처·자료 틀 fingerprint는 백엔드 재검증에 사용하며 모델용 문맥·인용에서는 제외합니다.

현재 Agent 보고서 경로는 선택 기준의 구조형 블록과 계획·전월 비교를 지원합니다. 저장 SQL·`metricId`, 선택 기준과 다른 기간·다기간 블록은 이 경로에서 거부합니다. 기존 보고서 UI의 더 넓은 지원 범위와 구분하며, 지원하지 않는 자료 틀은 오류 이유와 필요한 블록 선택을 안내합니다. 보고서 틀 편집·새 버전 저장·외부 발송·승인은 Agent에 연결하지 않았습니다.

XLSX는 백엔드 메모리에만 제한된 시간 동안 보관하며 업무 DB·서버 파일에 저장하지 않습니다. 모델은 다운로드 URL을 작성하지 않습니다. 화면은 성공한 실제 자료의 ID로 인증된 `GET /api/v1/decision-agent/report-artifacts/{artifactId}`를 호출합니다. 다운로드마다 현재 기획자 권한·소유자·만료를 확인합니다. 이 결과는 보고서 영속 보관·외부 전달·추진 승인을 뜻하지 않습니다.

소명 `summary`·분류별 합계는 전사 범위입니다. 최상위 상세 건수는 선택 조직의 전체 건수이고 `items/reviewing`은 그중 현재 페이지입니다. 소명 대상 `itemYear/itemMonth/itemScenario`를 읽으며 추정 비교는 기준월 다음 월일 수 있습니다. `hasMore`인 상세는 다음 페이지를 읽거나 미확인 범위를 표시합니다. 분류별 `subtotal=true`는 상위 소계이므로 하위 금액과 다시 합산하지 않습니다. `matchesGapTotal=true`만으로 분류 완료를 판정하지 않고 미분류·수락 전 건수와 `fullyClassified`를 함께 확인합니다. Gap 단순 합계는 영업이익 영향·개선 가능액이 아니며, `reviewing`은 미회신을 확정하는 값이 아닙니다. 이 도구는 현재 비교 기준의 소명 현황이며 다기간 반복 원인·위험 추세 전용 분석 API가 아닙니다.

전체 입력·출력·권한 계약은 [Agent 도구 계약](../../docs/development/agent-tool-contract.md)을 따릅니다.

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

일반 AI 채팅은 결과 미리보기와 근거를 반환합니다. 예전 `GET /v1/chat/files/{id}`는 제거했고, `/v1/chat`의 `file`은 항상 `null`입니다. Agent의 임시 보고서 준비는 위의 별도 Tool 경로이며 XLSX 생성은 기존 백엔드 내보내기 서비스를 사용합니다.

## 남아 있는 개발용 경로

`demo/ai_report_studio.html`과 `AI_PDF_DEV=1`에서 열리는 `/v1/dev/pdf-structure`, `/v1/dev/report-data/*`는 기존 고급 편집·문장 생성 기능을 확인하는 개발 화면입니다. `/v1/chat`, `/v1/report/key-messages`는 동일한 핵심 서비스를 확인하는 개발 입구입니다. 실제 기능이 남은 경로는 유지하고, backend·demo에서 호출이 없던 `/v1/report-authoring/*`, `/v1/dev/report-data/guess`, `/v1/dev/report-data/guess-block`는 제거했습니다. 별도 인증·구형 블록 스키마·직접 Responses 호출을 유지하지 않습니다.

미연결 `services/narratives.py`와 전용 테스트도 제거했습니다. 실제 화면이 사용하는 핵심 메시지 `key_messages`와 원인 한 줄 요약 `cause_summary`는 유지합니다. 개발 화면의 모든 기능이 새 경로로 이관됐다는 뜻은 아닙니다.

## 검증

소스 함수와 호출 관계는 [AI 이슈 변경 명세](development/ai-issue-contract.md)에 정리했습니다. `python3 -B ai/tests/test_stateless_runtime.py`는 외부 의존성·실제 DB·파일 생성 없이 실행합니다. FastAPI·LangGraph·PostgreSQL·LLM·브라우저 통합 검증은 별도 승인된 실행 환경에서 수행합니다.

2026-11-27 Tool 연결의 AI 검증은 DB·네트워크를 차단한 Agent·설정·Responses·보고서 source 회귀 182개와 기존 보고서·LLM 회귀 114개가 통과했습니다. 보고서 회귀는 `--noconftest`와 메모리 fixture·`pg_url=None`으로 실행했으며 DB 필요 24개·외부 PDF 샘플 필요 6개를 생략했습니다. 일반 pytest의 자동 PostgreSQL 생성 fixture를 운영 RDS에 연결하지 않습니다. `/v1/data`는 합성 채우기 결과를 이용한 인증된 ASGI 요청으로 기존 입력과 `source`/`null` 응답 호환을 검증했습니다.

현재 실제 RDS에서 확인한 저장 자료 틀은 0개이므로, 저장 틀 선택부터 실제 RDS 데이터가 채워진 XLSX 다운로드까지의 전체 경로는 검증하지 못했습니다. 이를 확인하기 위해 운영 DB에 예시 자료 틀을 삽입하지 않았습니다. 합성 도구 체인·내보내기 대역 검증과 실제 RDS 기반 전체 보고서 생성 검증을 구분합니다.

별도로 저장하지 않은 메모리 블록을 기존 채우기 함수에 제공해 실제 RDS의 기준월 금액·계획 차이 6칸을 원천 집계와 대조했습니다. 실제 Luna의 전사 소명 조회는 COMPLETED, 빈 자료 틀의 XLSX 요청은 NEEDS_INPUT으로 응답했고 프론트 계약과 실제 브라우저 소명 카드·근거 표시를 확인했습니다. 이 검증은 DB 저장이나 전체 파일 다운로드 검증을 대신하지 않습니다.
