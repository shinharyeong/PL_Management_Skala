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
│  ├─ services/          핵심 메시지 서비스(검증·캐시·일일 상한·토큰 로그), OpenAI 클라이언트
│  └─ pipeline/          배치 파이프라인(파싱→Gap→소명→master→07 자료→사실 목록) — README 참고
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
| INPUT_DIR, EXPLANATIONS_PATH, APPROVALS_PATH | (비움) | 파이프라인 입력(업로드된 파일 경로). 없으면 설정 안내 오류 |
| SCENARIO_DIR, SCENARIO_CSV_PATH | (비움) | 파이프라인의 gap_key 해석·시나리오 대조용(검증 데이터) |

우선순위는 **ai/.env → 셸 환경변수 → 기본값**입니다. 상대 경로는 저장소 루트 기준입니다.

## 엔드포인트 (초안)
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
확인용 흐름 화면(`ai/preview_html/flow.html`, 저장소 제외)은 `POST /v1/pipeline/run` 을 부릅니다. 서버 실행 후 파일을 더블클릭해 여세요(자세한 내용은 integration.md).
`ai/.gitignore` 가 `.env`, `.venv/`, `.cache/`, `_backup_before_rename/`, `preview_html/` 을 저장소에서 제외합니다.
