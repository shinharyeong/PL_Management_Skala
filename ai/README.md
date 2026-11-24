# ai — 보고서 AI 파트 (FastAPI)

경영기획 보고서 자료의 **핵심 메시지 초안**을 만드는 AI 서비스입니다. 숫자는 코드가 계산하고, AI는 주어진 사실만으로 문장을 다듬습니다.

> **API 명세는 초안입니다(백엔드와 합의 전).** 엔드포인트·요청/응답 필드는 합의 후 바뀔 수 있습니다.

## 구조
```
ai/
├─ app/
│  ├─ main.py            FastAPI 앱
│  ├─ config.py          설정(ai/.env > 셸 환경변수 > 기본값)
│  ├─ api/               라우터 (health.py, report.py)
│  ├─ schemas/           요청·응답 스키마
│  ├─ services/          핵심 메시지 서비스(검증·캐시·일일 상한·토큰 로그), LLM 호출(llm.py, LangChain)
├─ tests/                pytest (OpenAI 는 모의, 실제 호출 없음)
├─ output/               파이프라인 결과 JSON(OUTPUT_DIR), ai_call_log.json
├─ .cache/               AI 응답 캐시(AI_CACHE_DIR)
├─ docs/integration.md   통합 안내(초안)
├─ requirements.txt  .env.example  pytest.ini  .gitignore
```

## 설정 (`ai/.env.example` 복사 → `ai/.env`)
| 변수 | 기본값 | 설명 |
|---|---|---|
| OPENAI_API_KEY | (없음) | 키. 값은 어디에도 기록하지 않고 출처(ai/.env 또는 env)만 로그에 남깁니다 |
| OPENAI_MODEL | gpt-4o-mini | 추론(reasoning) 설정은 보내지 않습니다 |
| AI_TEMPERATURE / AI_MAX_OUTPUT_TOKENS | 0.2 / 500 | |
| AI_TIMEOUT_SECONDS / AI_MAX_RETRIES | 30 / 0 | 재시도 없음 |
| AI_DAILY_CALL_LIMIT | 30 | 하루 실제 호출 상한. 넘으면 호출 없이 템플릿 문장 |
| AI_CACHE_DIR | ai/.cache | (모델+프롬프트 버전+사실 목록 해시) 캐시 |
| OUTPUT_DIR | ai/output | 결과·호출 로그 폴더 |

우선순위는 **ai/.env → 셸 환경변수 → 기본값**입니다. 상대 경로는 저장소 루트 기준입니다.

## 엔드포인트 (초안)
- **LLM 호출은 `app/services/llm.py` 한 곳**입니다. `chat_model()` 은 LangChain `BaseChatModel`(LangGraph 노드가 그대로 씀), `ask_json()` 은 JSON 응답 한 번 호출입니다. 전송은 OpenAI 공식 SDK(`openai_client.make_client`)라 재시도·제한 시간 설정과 테스트의 가짜 클라이언트가 그대로 동작합니다. `ChatOpenAI` 로 바꾸려면 `llm.py` 의 `_SdkChatModel` 만 바꿉니다.
- `GET /health` → `{"status","model","openai_key_present"}` (OpenAI 호출 없음, 키는 true/false만)
- `POST /v1/report/key-messages`
  - 요청: `{"status":"provisional|final","facts":[{"id":"F1","kind":"매출|비용|영업이익|연간|소명|주의","text":"..."}]}`
  - 응답: `{"sentences":[{"text","fact_ids","source":"ai|template","reason"}],"usage":{"input_tokens","output_tokens","cached"}}`
  - 문장별 검증(숫자 일치, fact_ids 존재, 빈 문장·5문장 초과·금지어 금지, 미분해 사실은 밝힐 것). 실패한 문장만 템플릿으로 대체하고 `reason` 에 사유를 적습니다.

## 토큰 절약 장치
- 사실 목록(id·kind·text)만 보냅니다. 엑셀 원본·소명 전체·반려된 소명은 보내지 않습니다.
- JSON 응답만 받고 `max_tokens` 로 제한합니다.
- 같은 (모델+`PROMPT_VERSION`+사실 목록 해시)는 `AI_CACHE_DIR` 에 저장된 결과를 쓰고 OpenAI 를 부르지 않습니다.
- 일일 호출 상한 초과·키 없음·호출 실패(재시도 없음)는 템플릿 문장으로 대체하고 이유를 표시합니다.
- 호출마다 토큰 사용량(입력/출력)을 `ai/output/ai_call_log.json` 에 누적합니다(키·입력 원문 없음).

## 실행·통합
실행 방법(.env, uvicorn, pytest), 현재 엔드포인트(초안), 입력 데이터가 REP-01 응답으로 바뀌는 대응표, 소명 상태 이름 대응, 팀이 정할 것은 **[docs/integration.md](docs/integration.md)** 를 보세요.

```bash
cd ai && cp .env.example .env && python3 -m venv .venv && source .venv/bin/activate && pip install -r requirements.txt
uvicorn app.main:app --reload && python -m pytest -q
```
자연어 질문 챗봇(AI 작업실, `POST /v1/chat`)은 `report.html` 오른쪽 패널에서 씁니다(조회 DB 는 업로드 실행 후 자동 적재). 자세한 내용은 integration.md.
업로드·파싱·Gap·소명·master 는 백엔드가 맡고, AI 는 백엔드 보고서용 뷰를 읽기만 합니다(백엔드와의 계약은 docs/integration.md).
`ai/.gitignore` 가 `.env`, `.venv/`, `.cache/`, `_backup_before_rename/`, `preview_html/` 을 저장소에서 제외합니다.
