# AI #62 변경과 함수 명세

2026-11-24 `develop`의 PR #85·#86·#88·#92 반영 코드를 기준으로 남은 중복 저장·조회 규칙을 정리했다. 이후 사용자 요청에 따라 현재 호출 관계를 감사하여 폐기된 AI 경로도 제거했다. 실제 업무 데이터·비밀 설정은 검증에 사용하지 않았다.

## 해결한 범위

| 이전 동작 | 변경한 동작 | 담당 코드 |
|---|---|---|
| 개발 보고 카드가 `OP_GAP`을 조회 | backend `ITEM_GAP`을 조회하고 항목명을 표시 | `pdf_structure/data_select.py` |
| AI가 기본 월 우선순위를 재계산 | `dim_version.is_latest` 사용 | `data_select.months` |
| 소명을 첫 비교 `gap_id`에만 연결 | `gap_key`를 이용해 PLAN/PREVIOUS 양쪽에 연결 | `data_select.gaps`, `_unlinked` |
| 이력 회차를 소명 여러 건으로 집계 | 검토 항목별 최신 회차의 상태로 집계 | `q_explanation_count` |
| 이전 수락 이력이 현재 원인으로 재사용될 수 있음 | 최신 회차·현재 Gap 상태 모두 수락인 경우만 원문 표시 | `gaps` |
| 분류함 입력으로 자체 금액 합계·사실 생성 | `assign`은 호환 입력만 받고 backend 수치·수락 원문만 사실에 사용 | `build_facts` |
| 호출 기록·SQL·문장·편집·추론 결과를 파일 저장 | 제한된 메모리 캐시와 stdout 지표 | `services/response_cache.py`, `services/call_log.py` |
| 채팅마다 XLSX·JSON 파일 생성 | 결과·근거만 반환, backend RPT-16에서 내보내기 | `chat/graph.py`, `chat/service.py` |
| AI 앱이 DDL·쓰기 연결 제공 | 운영 모듈은 조회·종료만 제공, 테스트 도우미로 분리 | `chat/db.py`, `tests/db_support.py` |

Gap 카드의 `total_won`은 화면에 나열한 보고 항목 차이의 합계다. 영업이익 기여도나 새 Gap 판정 값이 아니다. `carried_over`는 기존 개발 화면 호환용으로 항상 false이며 이월 판단은 하지 않는다.

## 함수와 호출 관계

각 함수의 Args·Returns·Raises·Side Effects 상세는 소스 docstring을 따른다.

| 함수 | 입력 → 출력과 예외 | 호출·부작용 |
|---|---|---|
| `response_cache.get` / `put` / `clear` | 기능·모델·입력 해시 키와 JSON 형태 응답 → 깊은 사본 또는 None | 최대 256개, 1시간, LRU, RLock. 파일·DB 접근 없음 |
| `CallLog.__init__` / `load` / `calls_today` / `append` / `clear` | 집계 그룹·허용 메타데이터 → 최근 이벤트·실제 호출 수 | 최근 1,000개 이벤트와 별도 일일 횟수. 성공·실패만 계수, stdout logging. 재시작 시 초기화 |
| `key_messages.generate` | 확인된 사실·상태·호출 옵션 → 문장·근거·대체 사유·사용량 | 메모리 캐시 → 한도 → 단일 LLM → 숫자·근거 검증. 업무 DB 저장 없음 |
| `nl2sql.cache_get` / `cache_put` | 질의 해시 → SQL 응답 사본 | SQL 생성 그래프에서 공통 메모리 캐시 호출 |
| `cause_summary.summarize` | 긴 원인 원문 목록 → 검증된 짧은 문장 | 숫자·코드 원문 대조 유지, 공통 캐시·사용량 사용 |
| `chat_edit._call_ai` | 사용자 편집 요청·실제 블록 문맥 → 의도 JSON·사용량 | 키·한도·모델 오류는 `AiUnavailable`. 공통 메모리 캐시 사용 |
| `db._connect` / `open_readonly` | 설정 → 읽기 전용 Conn | `report` 기본 스키마. 접속·뷰 부재는 `DbUnavailable`. DDL 수행 없음 |
| `data_select.months` | 없음 → 월·버전·상태·기본 월·지표·조직 | 백엔드 뷰 조회. 자체 잠정 데이터 구성 없음 |
| `data_select.gaps` | 기준 연월 → 실적·다음 월 추정 비교별 카드 | ITEM_GAP과 최신 소명 조회, 양쪽 비교 기준 연결. 없는 원문을 만들지 않음 |
| `data_select.fill_report` | 기준 월·블록·원본 연월 → 채운 블록 | 뷰 값을 단위·표·차트로 표현. 블록 기준 월 ACTUAL 원문만 사용하고 PLAN/PREVIOUS 중복 제거 |
| `data_select.build_facts` | 기준 월·호환 assign·선택 그룹 → 근거 있는 사실 목록 | backend 수치와 수락 원문만 사용. 소명 분류·금액 배분 없음 |
| `q_gap_orgs` / `q_unexplained` | 기준 월 → 비교별 항목 표 | 첫 비교만 보던 조회를 전체 비교 대상으로 확장 |
| `q_explanation_count` / `_unlinked` | 기준 월 또는 버전 → 최신 상태 건수·연결 없는 Gap | 최신 회차별 소명 건수와 Gap 비교 건수를 구분 |
| `graph.collect_evidence` | 실행 결과 → 근거·file=None·안내 | `explain → evidence → respond`. Excel 후속 요청은 보고서 내보내기 안내 |
| `service.run_chat` / `_test_log` | 질문·문맥 → 조회 응답·선택적 비용 지표 | DB 읽기·모델 호출·메모리 문맥. `excel` 인자는 호환용이며 파일 생성 안 함 |
| `main.create_app` / `config.get_settings` | 없음 → 앱·설정 | 계약·호환 라우터 등록, stdout logging, report 스키마 기본값 |
| `tests.db_support`의 쓰기 함수 | 합성 자료 → 테스트 스키마 | `t_` 접두사 제한. 운영 앱에서 import하지 않음. 실제 DB에 쓰므로 승인된 검증 환경 전용 |

## 금액 정밀도 경계

`services/numeric.py`의 `amount_precision()`은 보고서 채우기·사실 생성·채팅 그래프·설명 숫자 검증에서 Decimal 정밀도를 최소 50자리로 높였다가 요청 종료 시 복구한다. `exact_json_value(value)`는 중첩 응답의 Decimal과 JavaScript 안전정수 범위를 넘는 정수를 십진 문자열로 바꾼다. 파일·전역 설정을 변경하지 않는다.

`db._num`은 소수 numeric을 Decimal로 유지한다. `data_select._won`은 normalize·quantize 없이 정확한 원 단위 문자열을 만들고, `_sql_block`·`_chart`·`_numbercards`·`fill_report`와 `service._jsonable`은 JSON 변환 때 float로 되돌아가지 않도록 한다. 숫자 카드에도 `won`을 추가해 backend RPT-16에서 빈 셀로 나오던 문제를 수정했다. 개발 데모는 그림 좌표에만 Number 근삿값을 쓰고 서버가 보낸 표시 문구를 유지한다.

SQL 소수 및 큰 정수는 XLSX에서 정밀도를 보존하는 텍스트 셀로 나간다. 계산 가능한 숫자 셀 의미와 Excel 유효자리 제한을 함께 정한 후속 타입 계약이 필요하다. 일반 `won` 셀은 backend가 `BigDecimal.doubleValue()`로 Excel 숫자를 기록하므로 Excel 숫자 표현 범위를 넘는 값까지 정확한 내보내기를 보장하지 않는다. 이번 변경은 backend 내보내기 타입 계약을 바꾸지 않는다.

## 삭제·호환 경계

- `chat/excel_out.py`, `/v1/chat/files/{file_id}`, `service.get_file`을 제거했다. 백엔드 XLSX 구현은 이미 존재하므로 AI가 파일을 중복 생성하지 않는다.
- `chat/schema.py`의 DDL·INDEXES를 제거하고 설명·허용 목록·SQL 프롬프트·계약 해시만 유지한다.
- `chat/db.py`의 쓰기 연결·조직 적재·최신 버전 표기·테이블 비우기·스키마 삭제·쓰기 전용 어댑터 메서드를 제거했다.
- `AI_CACHE_DIR`, `OUTPUT_DIR`, `AI_LOG_DIR`는 메모리 그룹 식별자로만 유지한다. 실제 폴더·파일을 읽거나 만들지 않는다.
- 실제 데모가 호출하는 `/v1/dev/*`, 같은 핵심 서비스의 개발 입구 `/v1/chat`, `/v1/report/key-messages`를 유지한다. 호출되지 않는 별도 구현은 아래 목록처럼 제거했다.
- db-mcp·RabbitMQ는 별도 2차 인프라 범위다. HTTP·읽기 전용 Postgres 연결을 미설정 인프라로 교체하지 않았다.

## 추가 AI 코드 정리

삭제 전에 AI·백엔드·프론트·개발 데모·테스트·도구의 import와 호출 경로를 조사했다. 현재 호출자가 있는 기능과 호출자가 없는 과거 구현을 구분했다.

| 제거한 코드 | 근거와 대체 경로 |
|---|---|
| `api/report_authoring.py`, 전용 `test_report_authoring.py` | 현 백엔드·데모 호출 없음. 폐기된 `report_view`·대문자 블록 계약을 사용하는 별도 SDK Responses 구현. 최신 `/v1/nl-query`, `/v1/edit`, `/v1/pdf-jobs`가 각 역할 담당 |
| `pdf_structure/row_guess.py`, `block_guess.py`, 전용 `test_row_guess.py` | 개발 화면에서도 사용하지 않는다고 명시된 미래용 추측 기능. `/report-data/guess`, `/report-data/guess-block`와 입력 모델도 함께 제거 |
| `services/narratives.py`, 전용 `test_narratives.py` | 런타임 import·호출이 없고 별도 카드/사업부/원인 요약 기능으로 연결되지 않은 코드. 현재 사용 중인 `key_messages`·`cause_summary` 유지 |
| 구 `Template`·`Block`·`Scope`·`Compare`·글꼴·설정 계층 및 `Mapping`·분류 상수 | 삭제된 AI 저장소의 미사용 모델. 테스트가 구 DTO를 생성하는 용도만 남아 있었음. 현 기간 규칙 `Period`, 저장 봉투 `StoredBlock`·`StoredTemplate` 유지 |
| `now_iso`, `ai_reasons.call_failed`, 중복/미사용 import | AI 소스·테스트·도구에서 호출·참조 없음 |

LangGraph의 `StateGraph`는 `service.run_chat → graph.GRAPH.invoke`에서 해석·SQL 검사·제한된 자기 수정·조회·설명을 순서대로 실행한다. LangChain `BaseChatModel`은 `nl2sql`·PDF VLM·편집·문장 서비스가 사용하는 공통 모델 인터페이스다. `llm.ask_json → chat_model.invoke → _SdkChatModel._generate`에서 SDK 전송은 한 번 발생한다. 두 라이브러리는 역할이 달라 모두 유지하며 별도 패키지 교체는 하지 않았다.

PDF의 기본 VLM 경로와 개발 `engine=lines` 경로는 모두 호출자가 있어 유지했다. `Period`가 검사하는 날짜 규칙은 그대로이며 `StoredBlock`·`StoredTemplate`의 AST는 기존 HEAD와 동일하다. 따라서 이번 정리로 backend 저장 봉투 schema를 다시 생성하지 않았다.

현재 호출 관계의 상대 import 검증은 AI app·tests·tools 63개 Python 파일에서 미해결 로컬 모듈·심볼 0건이다. 전체 pytest를 대신하는 결과는 아니다.

## 검증 결과와 한계

`python3 -B ai/tests/test_stateless_runtime.py`의 13개 테스트를 통과했다.

1. 캐시 깊은 사본·TTL 만료·파일 접근 금지
2. LRU 최대 개수·목록 형태 요약 응답
3. 허용 로그 필드·실제 호출만 계수·최근 이력 1,000개 초과 시 누적 유지
4. 운영 schema 독립 import·해시·DDL 및 쓰기 API 제거
5. 백엔드 is_latest에 따른 기본 월 선택
6. ITEM_GAP 필터·현재 실적/다음 추정·양쪽 비교의 동일 소명 연결
7. 최신 검토중 회차의 이전 수락 배제·소명 중복 집계 방지
8. 현재 실적 원인과 다음 월 추정 원인 분리·비교 기준 중복 원문 제거
9. DB numeric(30,6) 소수·최대 자릿수의 원 금액 문자열 보존
10. SQL·자연어 응답의 소수·큰 정수 JSON 문자열과 Decimal 계산 문맥 복원
11. 숫자 카드의 정확한 won과 미등록 지표의 won=None
12. 최신 backend 5개 계약과 실제 demo 8개 경로 유지·폐기 라우터 제거
13. direct Gap 조회에서 numeric(30,6) 큰 소수 합계 보존

AI Python 소스·테스트 60개를 AST 파싱했다. 위 메모리 DB 검증은 실제 `data_select.py` 함수 본문을 추출해 실행하며 FastAPI·LangGraph·PostgreSQL 통합 검증을 대신하지 않는다. 현재 Python에 필요한 외부 패키지가 설치되어 있지 않고, 기존 전체 pytest fixture는 임시 PostgreSQL·로그 파일을 생성할 수 있어 실행하지 않았다. 패키지 설치·빌드·실DB·실LLM 호출·사용자 원본 데이터 접근은 수행하지 않았다.

기존 pytest의 파일 로그·채팅 XLSX 검증과 OP_GAP 자료는 새 계약에 맞게 갱신했으나 해당 통합 테스트 결과는 아직 확인하지 않았다. 모델 호출 한도는 프로세스 단위여서 여러 인스턴스 전체의 비용 제한은 별도 운영 정책이 필요하다.
