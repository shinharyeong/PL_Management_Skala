# ai 통합 안내 (초안)

보고서(07) 문장을 만드는 AI 파트입니다. 숫자·표·차트·이연 판정은 코드가 하고, AI 는 주어진 **사실 목록**으로 문장만 씁니다.
이 문서와 엔드포인트는 **초안(백엔드와 합의 전)** 입니다. 백엔드 API 계약은 수정하지 않았습니다.

## 1. 폴더 구조
```
ai/
├─ app/
│  ├─ main.py  config.py        FastAPI 앱 · 설정(ai/.env > 셸 환경변수 > 기본값)
│  ├─ api/                      health.py, report.py (라우터)
│  ├─ schemas/                  요청·응답 스키마
│  ├─ services/                 key_messages(핵심 메시지), narratives(카드·사업부·소명 요약), openai_client, call_log
├─ tests/                       pytest(OpenAI 모의, 실제 호출 없음)
├─ docs/integration.md          이 문서
├─ output/ .cache/              실행 결과·AI 응답 캐시(저장소 제외)
└─ .env.example  requirements.txt  pytest.ini  .gitignore
```
`ai/preview_html/`(화면 시안)은 로컬 확인용이라 저장소에서 제외합니다. 서비스 코드(`app/`, `tests/`)는 시안을 참조하지 않습니다.

## 2. 실행 방법
```bash
cd ai
cp .env.example .env                   # OPENAI_API_KEY 만 채우면 됨. 상대 경로는 저장소 루트 기준
python3 -m venv .venv && source .venv/bin/activate && pip install -r requirements.txt
uvicorn app.main:app --reload          # http://127.0.0.1:8000/docs
python -m pytest -q                    # 모의 호출만 사용(실제 네트워크 없음)
```
- 키는 로그·파일에 남기지 않고 출처(`ai/.env` 또는 env)만 기록합니다. 하루 호출 상한(`AI_DAILY_CALL_LIMIT`)과 캐시를 씁니다.
- 업로드·파싱·Gap·소명·master 는 백엔드가 맡습니다(#62 에서 AI 쪽 파이프라인 삭제). AI 는 보고서용 뷰를 읽기만 합니다.

## 3. 현재 엔드포인트 (초안)
| 경로 | 설명 |
|---|---|
| `GET /health` | `{status, model, openai_key_present}` (OpenAI 호출 없음) |
| `POST /v1/report/key-messages` | 요청 `{status: provisional\|final, facts:[{id,kind,text}]}` → 응답 `{sentences:[{text,fact_ids,source:ai\|template,reason}], usage}` |


문장별 검증(숫자는 사실에 있는 것만, fact_ids 필수, 금지어, 미분해 사실 명시)에 실패한 문장만 템플릿으로 대체하고 `reason` 에 적습니다.
카드 설명·사업부 요약·소명 원인/영향은 아직 서비스 함수(`services/narratives.py`)만 있고 엔드포인트는 없습니다.


### backend 계약 `POST /v1/nl-query` (RPT-11, 명세 12절)
- 요청 `{question, baseYear, baseMonth, conversationId?}` → 응답 `{status: OK|CLARIFY|UNKNOWN, sql, explanation, clarifyQuestion, clarifyOptions, preview{dataStatus, columns[{key,label,type: TEXT|MONEY|RATE|INTEGER}], rows[]}, rowCount, truncated}`.
- 기준 월의 version(확정 master, 없으면 잠정)으로 아래 챗봇과 **같은 LangGraph 그래프**(`app/chat/graph.py`)를 돌린다. 그 달 version 이 없으면 `UNKNOWN`. 데이터를 바꾸라는 요청·조회 불가도 `UNKNOWN`(설명에 이유).
- 그래프: 차단 확인 → DB 열기 → 해석 → 후속 질문 규칙 → (되묻기) → 준비 → 규칙 / 예시 질문 / AI(저장된 SQL → SQL 생성 ⇄ 검사·실행, 실패하면 고쳐서 다시 `CHAT_MAX_FIX` 번) / AI 없으면 기본 질의 → 설명 문장 → 엑셀 → 기억 갱신. LLM 은 `app/services/llm.py`(LangChain).

### backend 계약 `POST /v1/data` · `/v1/edit` · `/v1/pdf-jobs` (RPT-10·15·01, 명세 12절)
- 공통: `REPORT_AI_TOKEN` 이 있으면 요청 헤더 `X-Internal-Token` 이 같아야 한다(다르면 401). 운영(`AI_REQUIRE_INTERNAL_TOKEN=true`, k8s 설정)은 토큰이 설정되지 않았으면 계약 API 를 모두 거부하고 결과 보고도 하지 않는다. 비어 있어도 통과하는 것은 로컬 개발(기본값 false)뿐이다.
- `POST /v1/data` `{base{year,month}, blocks[{id,kind,…}]}` → `{dataStatus: CONFIRMED|PROVISIONAL|NONE, blocks[{id,…}]}`. `data_select.fill_report` 그대로, AI 문장 없음.
- 표 셀은 화면 글자 `text`(억원·백만원 반올림)와 별도로 원 단위 값 `won`(십진 문자열)을 담는다. 백엔드 XLSX 내보내기(RPT-16)가 `won` 을 숫자 그대로 쓴다.
- 블록에 `sql` 이 있으면(지표 사전·자연어 질의로 확정한 SQL) 블록 기준 월의 version 으로 `:version :version_prev :base_year :base_month :scn` 을 넣어 챗봇과 같은 `sqlguard`(보고서용 뷰만·읽기 전용·행/시간 상한)로 실행하고 `table{columns, rows, truncated}` 를 채운다. 막히면 `missing` 과 사유 코드.
- `POST /v1/sql-check` `{sql}` → `{ok, sql, code, message}`. 지표 사전 등록(RPT-09) 전 검사만 한다(실행 안 함). 백엔드는 `ok=false` 를 `SQL_NOT_ALLOWED` 로 돌려준다.
- `POST /v1/edit` `{definition, instruction}` → `{status: EDIT|CLARIFY|REFUSED|UNAVAILABLE, message, proposals[{op, target, description, patch}]}`. `chat_edit.interpret`(LLM 의도 → 코드 검증). 숫자 변경 요청은 AI 호출 없이 REFUSED, 조회 요청은 편집이 아니라 REFUSED(자연어 질의 안내).
- `POST /v1/pdf-jobs` (multipart `jobId`, `maskNumbers`(기본 true), `file`) → `202 {jobId, accepted}`. 백그라운드에서 추출해 `AI_BACKEND_URL` 의 `POST /internal/pdf-jobs/{jobId}/result` 로 `{status: SUCCEEDED|FAILED, definition(틀 초안, 저장 봉투 모양), pages[{no,width,height,imagePng(base64, maskNumbers 면 숫자 가림)}], error{code,message}}` 를 보낸다. 1차 전송이며, 2차는 `pdf.jobs` 큐·S3 에서 PDF 를 읽는 것만 다르다(보고 본문은 같음).

### AI 작업실(챗봇) `POST /v1/chat` (초안, 데모 화면용 — #62 에서 백엔드 경유로 바꾼 뒤 정리)
- **흐름:** 질문 → 코드가 조직·월·버전(시점)을 먼저 해석(모르면 `unknown`, 모호하면 `clarify` 로 되묻기) → AI 가 SQL 작성(같은 질문은 저장된 SQL 재사용) → SQL 검사기 → 최대 2번 자기 수정 → **읽기 전용** PostgreSQL 조회 → 설명 문장(AI, 숫자 검증 실패 시 기본문구) → 엑셀(요약/결과/근거). AI 키가 없거나 상한 초과·실패면 빠른 질문 5개의 고정 SQL 로 대신 답한다("AI 없이 기본 질의").
- **원칙:** AI 는 SQL 과 설명 문장만 쓰고 숫자는 전부 DB 조회 결과에서 나온다. 데이터 값은 SQL 생성 AI 에 보내지 않는다. 기존 하루 호출 상한(`AI_DAILY_CALL_LIMIT`)과 호출 기록(`ai_call_log.json`, task=chat_sql/chat_text)을 그대로 쓴다.
- **엔드포인트:** `GET /v1/chat/status`(DB 범위·남은 AI 호출), `GET /v1/chat/files/{id}`(엑셀). 백엔드 계약은 `/v1/nl-query`(엑셀 없음)이고 `/v1/chat` 은 그래프를 단계 기록과 함께 확인하는 개발 입구다.
- **조회 DB:** PostgreSQL(`AI_DATABASE_URL`, 스키마 `AI_DB_SCHEMA` 기본 `ai`). 연결의 `search_path` 가 그 스키마로 고정된다. 테이블 fact(가장 작은 항목만)·gap·explanation(수락된 원문만)·dim_version·dim_org, 뷰 v_op. **버전이 곧 시점**(확정 master / 잠정 / 월마감 close-<연도>-<월>), 기본은 최신 버전.
- **데이터:** AI 가 엑셀·JSON 을 직접 적재하지 않는다(#62 에서 적재기 삭제). 운영·통합은 `AI_DB_SCHEMA=report`(백엔드 보고서용 뷰), 테스트는 `db.open_writer` 로 작은 시나리오를 넣는다.
- **SQL 검사:** SELECT/WITH 한 문장, 세미콜론·주석·변경 키워드 금지, 허용 테이블·함수만(문자열 허용 목록 + `pg_*`·`information_schema`·스키마 접두 금지)·읽기 전용 연결·`statement_timeout`, version = :version 조건 필수, 행 1000·시간 20초 상한.

## 4. 입력 데이터
숫자는 백엔드 RDS 의 보고서용 뷰(`report` 스키마: fact·v_op·gap·explanation·dim_version·dim_org)에서만 읽습니다. AI 앱은 파일을 적재하지 않습니다.

| 지금(파일 → JSON) | 나중(백엔드 API) | 비고 |
|---|---|---|
| master_*.json 의 이번달·지난달·작년 동월 합계 | REP-01 `periods[].totalRows[{reportItemId,valueStatus,amount}]` (masterIds 3개: 당월·전월·전년 동월) | 조직별은 `periods[].organizations[].rows` |
| master 버전·승인 조직 수 | REP-01 `calculationVersions[{masterId,version}]`, MAS-03 | 잠정은 공식 master 없음 |
| 소명(수락) 문장·판정 이력 | REP-01 `explanations[]`(ACTUAL·수락만), REV-01/02 `explanations[]` | 반려·검토중 문장은 AI 입력에서 제외 |
| Gap 목록·기준 대비 값 | REV-01/02 `ReviewItemView`(`changeDetected`, `gapExceeded`, `comparisons`) | 금액·초과 여부는 서버 값 사용 |
| Contingency(현재 "데이터 없음") | REP-01 `annualContingencies[].amount`(연간 단위) | 월에 나누거나 반복 차감하지 않음 |
| 계획 값 | **REP-01 에 없음**(ACTUAL 만) | 출처 결정 필요(§6) |
| 연간 최신 추정(1~11월 실적+12월 추정) | **REP-01 에 없음**(ESTIMATE 는 master 대상 아님) | 출처 결정 필요(§6) |

## 5. 소명 상태 이름 대응
| 화면·JSON(현재) | api-contract `explanationState` | `verdict` |
|---|---|---|
| 소명 없음 | UNANSWERED(필수 아님 포함) | - |
| (제출 전 작성, 현재 데이터에 없음) | ANSWERED | - |
| 검토중 | IN_REVIEW | PENDING |
| 수락 | ACCEPTED | ACCEPTED |
| 반려(회차 이력의 1차) | 새 회차는 UNANSWERED | REJECTED |

재소명 이력 "1차 반려 → 2차 수락" 은 회차별 `verdict`(REJECTED → ACCEPTED)로 표현합니다.

## 6. 팀이 정해야 할 것
1. **백엔드가 AI 서비스를 부를지**, 부른다면 주소(`AI_BASE_URL` 등)와 인증·타임아웃. 지금은 AI 서비스가 독립 실행입니다.
2. **계획·추정 값의 출처**: REP-01 은 ACTUAL 만 주므로 계획(PLAN)과 연간 최신 추정(ESTIMATE)을 어느 API·테이블에서 받을지.
3. **Gap 규칙 확정**: 임계값(예: 5%, 10백만원은 예시), 문서 규칙(영업이익 중심)과 화면(항목 단위) 차이, 이연/실제 총액 변화 판정 기준(직전 추정=계획 전제, 현재는 코드 판정 + 팀 확인).
4. 월별 시계열·누적 보기에 필요한 **월별 master 목록** 제공 여부.

## 기동·종료
```
# ai 서버 (포트 8000)
cd ai && python3 -B -m uvicorn app.main:app --port 8000
# 팀 앱(DB·백엔드·프론트) — 루트 .env 필요(docs/development/local-environment.md)
bash scripts/dev-up.sh        # 프론트 http://127.0.0.1:5173 (nginx 가 /ai/ 를 호스트의 8000 으로 전달)
bash scripts/dev-down.sh
# 프론트만 개발 서버: cd frontend && npx vite   (/api → 8080, /ai → 8000 프록시)
```
환경변수(`ai/.env.example`): `AI_DAILY_CALL_LIMIT`, `CHAT_MEMORY`, `CHAT_TEST_LOG`, `AI_DATABASE_URL`, `AI_DB_SCHEMA`, `REPORT_AI_TOKEN`, `AI_BACKEND_URL`, `AI_REQUIRE_INTERNAL_TOKEN`.

## 보고 작성 연결

`/v1/report-authoring/sql`, `/edit`, `/pdf`는 백엔드 `PLANNER` 인증을 통과한 요청만 받는 별도 내부 경계다. `X-Report-Service-Token`은 AI의 `REPORT_AI_TOKEN`과 일치해야 한다. 백엔드에는 같은 토큰과 `REPORT_AI_URL`을 설정한다. 기존 배치·SQLite 챗봇의 파일 경로나 캐시를 이 경로에 연결하지 않는다.

PDF는 사용자가 분석 요청한 바이트만 받고, `maskNumbers=true`이면 원문을 재사용하지 않는 메모리 재구성을 수행한다. 외부 모델 결과는 초안이며 사용자 확인 전 저장·적용하지 않는다. API키 미설정·모델실패·스캔 마스킹실패는 수동 작업 안내를 반환한다.

검증은 `tests/test_report_authoring.py`의 합성 메모리 PDF와 모의 Responses 클라이언트로 수행한다. 실제 API키·사용자 원본·Data 폴더를 테스트에 사용하지 않는다.
