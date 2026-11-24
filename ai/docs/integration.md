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
│  └─ pipeline/                 파일 입력 배치(파싱→Gap→소명→master→07 자료→사실 목록)
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
- 파이프라인(`python -m app.pipeline.run_pipeline`)은 입력 경로(`INPUT_DIR`, `EXPLANATIONS_PATH`, `APPROVALS_PATH`, `SCENARIO_DIR`, `SCENARIO_CSV_PATH`)가 비어 있으면 **무엇을 설정하라**는 오류로 멈춥니다.

## 3. 현재 엔드포인트 (초안)
| 경로 | 설명 |
|---|---|
| `GET /health` | `{status, model, openai_key_present}` (OpenAI 호출 없음) |
| `POST /v1/report/key-messages` | 요청 `{status: provisional\|final, facts:[{id,kind,text}]}` → 응답 `{sentences:[{text,fact_ids,source:ai\|template,reason}], usage}` |

| `POST /v1/pipeline/run` (multipart) | **확인용 초안.** `files[]`(엑셀 .xlsx·소명/승인 .json·이를 담은 .zip), `ai`(기본 false), `keep_files`(기본은 설정값=보존) → 5단계 결과 |
| `GET /v1/pipeline/runs/{run_id}` | 저장된 실행 결과 재조회 |

문장별 검증(숫자는 사실에 있는 것만, fact_ids 필수, 금지어, 미분해 사실 명시)에 실패한 문장만 템플릿으로 대체하고 `reason` 에 적습니다.
카드 설명·사업부 요약·소명 원인/영향은 아직 서비스 함수(`services/narratives.py`)만 있고 엔드포인트는 없습니다.


### backend 계약 `POST /v1/nl-query` (RPT-11, 명세 12절)
- 요청 `{question, baseYear, baseMonth, conversationId?}` → 응답 `{status: OK|CLARIFY|UNKNOWN, sql, explanation, clarifyQuestion, clarifyOptions, preview{dataStatus, columns[{key,label,type: TEXT|MONEY|RATE|INTEGER}], rows[]}, rowCount, truncated}`.
- 기준 월의 version(확정 master, 없으면 잠정)으로 아래 챗봇과 **같은 LangGraph 그래프**(`app/chat/graph.py`)를 돌린다. 그 달 version 이 없으면 `UNKNOWN`. 데이터를 바꾸라는 요청·조회 불가도 `UNKNOWN`(설명에 이유).
- 그래프: 차단 확인 → DB 열기 → 해석 → 후속 질문 규칙 → (되묻기) → 준비 → 규칙 / 예시 질문 / AI(저장된 SQL → SQL 생성 ⇄ 검사·실행, 실패하면 고쳐서 다시 `CHAT_MAX_FIX` 번) / AI 없으면 기본 질의 → 설명 문장 → 엑셀 → 기억 갱신. LLM 은 `app/services/llm.py`(LangChain).

### AI 작업실(챗봇) `POST /v1/chat` (초안, 데모 화면용 — #62 에서 백엔드 경유로 바꾼 뒤 정리)
- **흐름:** 질문 → 코드가 조직·월·버전(시점)을 먼저 해석(모르면 `unknown`, 모호하면 `clarify` 로 되묻기) → AI 가 SQL 작성(같은 질문은 저장된 SQL 재사용) → SQL 검사기 → 최대 2번 자기 수정 → **읽기 전용** PostgreSQL 조회 → 설명 문장(AI, 숫자 검증 실패 시 기본문구) → 엑셀(요약/결과/근거). AI 키가 없거나 상한 초과·실패면 빠른 질문 5개의 고정 SQL 로 대신 답한다("AI 없이 기본 질의").
- **원칙:** AI 는 SQL 과 설명 문장만 쓰고 숫자는 전부 DB 조회 결과에서 나온다. 데이터 값은 SQL 생성 AI 에 보내지 않는다. 기존 하루 호출 상한(`AI_DAILY_CALL_LIMIT`)과 호출 기록(`ai_call_log.json`, task=chat_sql/chat_text)을 그대로 쓴다.
- **엔드포인트:** `GET /v1/chat/status`(DB 범위·남은 AI 호출), `POST /v1/chat/load`(최신 업로드 실행 결과를 조회 DB 에 적재), `GET /v1/chat/files/{id}`(엑셀). 업로드 실행이 끝나면 조회 DB 에 자동 적재된다.
- **조회 DB:** PostgreSQL(`AI_DATABASE_URL`, 스키마 `AI_DB_SCHEMA` 기본 `ai`). 연결의 `search_path` 가 그 스키마로 고정된다. 테이블 fact(가장 작은 항목만)·gap·explanation(수락된 원문만)·dim_version·dim_org, 뷰 v_op. **버전이 곧 시점**(확정 master / 잠정 / 월마감 close-<연도>-<월>), 기본은 최신 버전.
- **적재:** (a) 업로드 실행 결과 → `python3 -m app.chat.loader run [--run-id ID]`, (b) 조직별 월마감 엑셀 상세 시트 → `python3 -m app.chat.loader excel --dir <폴더> --version close-<연도>-<월> --year <연도> --month <월>`(여러 프로세스 읽기, 일괄 insert, 인덱스·ANALYZE).
- **SQL 검사:** SELECT/WITH 한 문장, 세미콜론·주석·변경 키워드 금지, 허용 테이블·함수만(문자열 허용 목록 + `pg_*`·`information_schema`·스키마 접두 금지)·읽기 전용 연결·`statement_timeout`, version = :version 조건 필수, 행 1000·시간 20초 상한.

### 업로드 실행 `POST /v1/pipeline/run` (초안)
- **입력:** 엑셀 63개(계획 `annual_plan`·baseline `baseline_2026_10`·제출 `submission_2026_11`·재제출 `revisions`), `explanations.json`, `approval_snapshots.json`. gap_key(REV030 등)를 셀로 풀기 위한 시나리오 JSON(`02_scenarios/NN_*.json`)은 선택이며, 없으면 소명 연결이 제한된다는 경고가 나온다.
- **파일 이름에 폴더 경로가 필요:** 예) `01_excel/submission_2026_11/01_사업부1/F10_2026-11.xlsx`(브라우저 폴더 선택은 자동, curl 은 `-F "files=@경로;filename=01_excel/..."`). 이름만 있으면 역할을 알 수 없어 위반으로 처리한다.
- **응답:** `stages.upload|parse|gaps|master|report` 각각 `status`(OK/WARN/FAIL/SKIPPED)와 쉬운 말 `message`. upload=파일·조직·역할·위반·누락, parse=파일별 성공/실패·셀 수·조직별 요약, gaps=Gap 목록·분류·소명 연결·재소명 이력, master=승인 수·잠정/확정·master 이름·합계, report=07 화면 데이터·핵심 메시지. 앞 단계가 FAIL 이면 뒤 단계는 SKIPPED.
- **저장:** 업로드 `AI_CACHE_DIR/uploads/<run_id>/`(기본 보존, `keep_files=false` 또는 `AI_UPLOAD_KEEP=false` 로 삭제), 결과 `AI_CACHE_DIR/runs/<run_id>/`. 파일 수·크기 상한은 `AI_UPLOAD_MAX_FILES / _FILE_MB / _TOTAL_MB`. 확장자는 xlsx·json(+zip 묶음)만, 경로 이탈(zip slip)·절대경로·역슬래시는 거부.
- **OpenAI:** `ai=true` 일 때만(확정 버전 최대 4회). 기본은 템플릿 문장. 키는 응답·로그에 나오지 않는다.
- **CORS:** 확인용 화면(`ai/preview_html/flow.html`, 저장소 제외)이 `file://` 에서 열리므로 `localhost`·`127.0.0.1` 과 Origin `null` 만 허용한다. **운영 설정이 아니다**(코드 주석에도 명시).
- **마스터 답안지:** master 단계 응답에 부서별 `답안지_IFRS15後` 표(월 1~12 × 항목 190행 계층)와 합계 검증이 들어 있다(`answer_sheet`, 값은 원 단위 정수). 검증: 답안지 매출·영업비용 = 상세 시트 합, 영업이익 = 매출 − 영업비용, 부모 = 자식 합.
- **속도:** 엑셀 파싱은 여러 프로세스로 동시에 돌고(`AI_PARSE_WORKERS`, 0=자동), 파일 내용(sha256)이 같으면 `AI_CACHE_DIR/parse_cache` 의 이전 결과를 다시 쓴다. 응답에 `timings`(단계별 초)가 있다.
- **교체 예정:** 업로드→SUB-01, 파싱→SUB-03/06/07, Gap·소명→REV-01, 마스터→MAS-04, 보고서→REP-01. 시나리오 JSON 은 REV-01 의 검토 항목이 소명을 직접 가리키면 필요 없어진다.

## 4. 입력 데이터: 지금은 파일 → 나중에 REP-01 응답
지금은 업로드 파일(엑셀·소명 JSON·승인 JSON)을 읽는 배치 파이프라인이 `ai/output/*.json` 을 만듭니다. 연결 후에는 백엔드의 `POST /reports/preview`(REP-01) 응답과 검토 API 값으로 같은 JSON 을 채웁니다.

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

## 프론트 보고서 화면 연결 (GET /v1/report/latest)

- 가장 최근에 끝난(전체 OK) 실행의 보고서를 화면용으로 추려 돌려줍니다. 완료된 실행이 없으면 404 이고, 프론트는 기존 예시 값을 그대로 보여 줍니다.
- 응답: `run_id`, `data_status`(확정/잠정), `master_version`, `base_month`, `summary`(caption, rows), `bridge`(caption, steps), `message`(문장 목록), `reason`(대표 소명 1건), `banner`(잠정일 때만), `weekly`(항상 null — 주별 매출 증감률은 만들지 않음). `?state=provisional` 로 잠정 버전을 받을 수 있습니다.
- 브라우저는 `/ai/v1/...` 로 부르고, 개발은 vite 프록시(`/ai` → localhost:8000), 배포는 nginx(`/ai/` → `ai:8000`)가 `/ai` 를 떼어 전달합니다. compose 에 ai 서비스(Dockerfile)는 아직 없습니다.

## 업로드 → 보고서 → 챗봇 흐름 (화면 /planning/ai)

1. 화면의 "엑셀 업로드 · 실행"에서 폴더나 파일(엑셀·소명 JSON·zip)을 골라 올립니다 → `POST /v1/pipeline/start`(run_id 즉시 반환).
2. 화면이 `GET /v1/pipeline/runs/{run_id}/status` 를 1초마다 불러 단계(업로드·파싱·Gap·master·보고서)를 보여 줍니다.
3. 끝나면 새 실행이 선택되어 보고서·소명 목록·챗봇이 그 실행을 씁니다. 실행 선택 드롭다운은 `GET /v1/pipeline/runs` 에서 채웁니다.

**처리 방식 두 가지**
- 표준 구조(`annual_plan`·`baseline_2026_10`·`submission_2026_11`·`revisions` 폴더 + `{조직코드}_YYYY-MM.xlsx` + 소명·승인 JSON): 문서 기준 전체 파이프라인(master·이연 판정 포함).
- 그 밖의 모든 엑셀: **일반 모드**. 마감 연월·조직코드는 폴더·파일 이름에서, 시트·헤더 위치·단위(원/천원/만원/백만원/억원)는 파일에서 읽습니다. 못 읽은 파일·시트·칸(시트 없음, 연도 머리 없음, 숫자 아님, 빈 칸, 오류값, 중복, 마스터 파일)은 멈추지 않고 경고로 남기며 보고서와 화면에 표시합니다. 소명 JSON 이 없으면 Gap 은 "소명 없음"입니다. 공식 master 는 없고 마감월 조직 합계로 보고합니다.

**챗봇 조회 DB** 는 마지막으로 적재·선택한 실행만 담습니다(다른 실행의 조직·버전이 섞이지 않음). 실행을 바꾸면 `POST /v1/chat/load {run_id}` 로 다시 적재합니다(일반 모드는 보관된 업로드 원본을 다시 읽음 — `AI_UPLOAD_KEEP=false` 면 불가).

## 기동·종료
```
# ai 서버 (포트 8000)
cd ai && python3 -B -m uvicorn app.main:app --port 8000
# 팀 앱(DB·백엔드·프론트) — 루트 .env 필요(docs/development/local-environment.md)
bash scripts/dev-up.sh        # 프론트 http://127.0.0.1:5173 (nginx 가 /ai/ 를 호스트의 8000 으로 전달)
bash scripts/dev-down.sh
# 프론트만 개발 서버: cd frontend && npx vite   (/api → 8080, /ai → 8000 프록시)
```
환경변수(`ai/.env.example`): `AI_DAILY_CALL_LIMIT`, `AI_UPLOAD_MAX_FILES`(기본 150), `AI_UPLOAD_MAX_TOTAL_MB`(기본 400), `AI_PARSE_WORKERS`, `CHAT_MEMORY`, `CHAT_TEST_LOG`. nginx 의 `/ai/` 업로드 상한은 300MB 입니다.

## 보고 작성 연결

`/v1/report-authoring/sql`, `/edit`, `/pdf`는 백엔드 `PLANNER` 인증을 통과한 요청만 받는 별도 내부 경계다. `X-Report-Service-Token`은 AI의 `REPORT_AI_TOKEN`과 일치해야 한다. 백엔드에는 같은 토큰과 `REPORT_AI_URL`을 설정한다. 기존 배치·SQLite 챗봇의 파일 경로나 캐시를 이 경로에 연결하지 않는다.

PDF는 사용자가 분석 요청한 바이트만 받고, `maskNumbers=true`이면 원문을 재사용하지 않는 메모리 재구성을 수행한다. 외부 모델 결과는 초안이며 사용자 확인 전 저장·적용하지 않는다. API키 미설정·모델실패·스캔 마스킹실패는 수동 작업 안내를 반환한다.

검증은 `tests/test_report_authoring.py`의 합성 메모리 PDF와 모의 Responses 클라이언트로 수행한다. 실제 API키·사용자 원본·Data 폴더를 테스트에 사용하지 않는다.
