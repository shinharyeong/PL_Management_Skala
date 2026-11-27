# AI 보고서 서비스

백엔드 보고서용 뷰를 읽어 PDF 구조 분석, 자연어 SQL 조회, 보고서 값 채우기와 편집 변경안을 제공합니다. LangGraph가 질의·검사·재시도 흐름을 제어하고, LangChain 공통 LLM 인터페이스를 사용합니다. 업로드 적재·Gap 판정·소명 상태·확정 마스터·틀 저장·XLSX 내보내기는 백엔드가 담당합니다.

## 통합 경계

- Vue → 백엔드 `/api/v1/report/*` → AI 계약 `/v1/pdf-jobs`, `/v1/data`, `/v1/nl-query`, `/v1/edit`, `/v1/sql-check` 순서로 연결합니다.
- 대화형 Agent는 기존 Spring 서비스를 재사용하는 업무 도구 19개를 제공합니다. 보고서 틀 조회·값 채우기·임시 XLSX 준비와 전사 소명 현황 Tool의 범위·제약은 [통합 계약](docs/integration.md#agent의-보고서전사-소명-도구)에 정리했습니다.
- AI의 DB 연결은 `AI_DATABASE_URL`과 `AI_DB_SCHEMA=report`를 사용하며 항상 읽기 전용입니다. 스키마 생성·값 적재 함수는 운영 앱에서 제거했습니다.
- `ITEM_GAP`과 `gap_key`를 사용하고, 기준 월과 확정·잠정 상태는 백엔드 `dim_version` 값을 따릅니다. 소명 건수는 검토 항목별 최신 회차 기준입니다.
- AI는 파일 결과·호출 로그·디스크 캐시를 만들지 않습니다. 일반 AI 채팅 `/v1/chat`의 `file`은 `null`입니다. Agent의 `report_export`는 백엔드가 기존 XLSX 내보내기 서비스로 준비한 임시 메모리 자료의 메타데이터만 받으며 업무 DB 저장·외부 발송은 수행하지 않습니다.
- 개발 데모와 `/v1/dev/*`는 Vue의 필요한 기능이 모두 이관될 때까지 유지합니다. 개발 경로는 `AI_PDF_DEV=1`에서만 열립니다. `/v1/chat`, `/v1/report/key-messages`는 같은 핵심 서비스를 확인하는 얇은 개발 입구입니다. 호출이 없던 옛 `/v1/report-authoring/*`와 행·블록 추측 경로는 제거했습니다.

## LangChain과 LangGraph의 역할

LangGraph는 자연어 조회의 해석·검사·실행·재시도 순서를 관리합니다. LangChain은 각 단계에서 사용하는 공통 모델 호출 인터페이스입니다. 둘은 같은 일을 두 번 수행하지 않습니다. 실제 SDK 전송은 `services/llm.py` 한 곳이며, 최신 백엔드 계약과 PDF·편집·핵심 문장 기능이 이를 공유합니다. 별도 Responses 호출을 만들던 폐기된 보고 작성 경로는 삭제했습니다.

GPT-6 모델은 이 공통 진입점에서 `services/responses_adapter.py`로 Responses 전송을 사용합니다.
함수 호출 ID·도구 응답·암호화된 추론 상태를 실행 메모리에서 연결하며 `store=false`입니다.
추론 원문은 답변·로그에 넣지 않습니다. 이전 모델의 Chat Completions 경로는 유지합니다.
GPT-5·o 계열은 기존 `max_completion_tokens`·`AI_REASONING_EFFORT`를 전송하고
`temperature`를 보내지 않습니다. 일반 모델은 기존 `max_tokens`·`temperature`를 사용합니다.

## 설정과 사용량

설정 우선순위는 `ai/.env` → 셸 환경변수 → 기본값입니다. 실제 비밀값은 저장소나 로그에 남기지 않습니다.

2026-11-27 검증 실행과 `.env.example`은 `OPENAI_MODEL=gpt-6-luna`,
`OPENAI_REASONING_EFFORT=low`를 사용합니다. 배포 환경에도 두 값을 명시해야 하며,
설정이 없는 기존 실행의 기본 모델은 호환성을 위해 아래와 같이 유지합니다.
Luna의 출력 상한·사용량에는 추론 토큰이 포함됩니다. JSON이 문법상 유효해도 제공자가 출력 한도로
미완료 처리한 결과는 `ModelOutputLimitError`로 거부합니다.
추론 설정은 모델 계열별로 분리되어 있습니다. `OPENAI_REASONING_EFFORT`는 GPT-6 Responses에,
`AI_REASONING_EFFORT`는 GPT-5·o 계열 Chat Completions에만 적용됩니다. 서로의 별칭이나
대체값이 아니며, 텍스트·VLM 호출마다 실제 선택한 모델을 기준으로 적용합니다.
기존 `AI_REASONING_EFFORT=none` 설정을 유지해도 Luna의 기본 추론 `low`를 덮어쓰지 않습니다.
추론 수준의 지원 여부는 선택한 제공자 모델에 따릅니다.

| 설정 | 기본값 | 의미 |
|---|---|---|
| `OPENAI_MODEL` | `gpt-4o-mini` | 공통 텍스트 모델 |
| `OPENAI_REASONING_EFFORT` | `low` | GPT-6 Responses 추론 수준 |
| `AI_REASONING_EFFORT` | `none` | GPT-5·o 계열 Chat Completions의 기존 추론 수준 |
| `AI_TIMEOUT_SECONDS` / `AI_MAX_RETRIES` | `30` / `0` | 호출 제한 시간·SDK 재시도 |
| `AI_DAILY_CALL_LIMIT` | `30` | 한국 날짜 기준 프로세스 내 성공·실패 호출 수 상한 |
| `AI_DB_SCHEMA` | `report` | 백엔드 보고서용 뷰 스키마 |
| `REPORT_AI_TOKEN` | 없음 | 백엔드와 AI의 내부 인증 토큰 |
| `AI_REQUIRE_INTERNAL_TOKEN` | `false` | 운영에서는 `true`로 설정 |
| `CHAT_MEMORY` | `true` | 프로세스 메모리의 최근 대화 조건 |
| `CHAT_TEST_LOG` | `false` | 활성화 시 질의 사용량을 stdout으로 출력 |

응답 캐시는 프로세스당 최대 256개·1시간이며 재시작하면 사라집니다. 호출 이력은 최근 1,000개만 메모리에 유지하고 일일 실제 호출 수는 별도 집계합니다. 여러 프로세스의 전역 비용 한도는 외부에서 관리해야 합니다. `AI_CACHE_DIR`, `OUTPUT_DIR`, `AI_LOG_DIR`는 기존 호출부와의 호환을 위한 메모리 그룹 식별자이며 폴더를 만들지 않습니다.

## 개발 문서와 검증

- [통합 API·운영 경계](docs/integration.md)
- [#62 변경 함수·호출 관계·검증 범위](docs/development/ai-issue-contract.md)
- [기존 데모 기록](README_AI_REPORT.md)
- [기획팀 업무 범위와 남은 Agent 도구](../docs/development/agent-capability-gaps.md)

외부 패키지·실제 DB·모델 호출 없이 회귀 검증하려면 저장소 루트에서 `python3 -B ai/tests/test_stateless_runtime.py`를 실행합니다. 전체 pytest는 외부 패키지와 PostgreSQL이 필요하며 기존 fixture가 임시 서버·파일을 만들 수 있으므로 생성이 승인된 환경에서만 실행합니다.
