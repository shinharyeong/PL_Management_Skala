# AI 보고서 서비스

백엔드 보고서용 뷰를 읽어 PDF 구조 분석, 자연어 SQL 조회, 보고서 값 채우기와 편집 변경안을 제공합니다. LangGraph가 질의·검사·재시도 흐름을 제어하고, LangChain 공통 LLM 인터페이스를 사용합니다. 업로드 적재·Gap 판정·소명 상태·확정 마스터·틀 저장·XLSX 내보내기는 백엔드가 담당합니다.

## 통합 경계

- Vue → 백엔드 `/api/v1/report/*` → AI 계약 `/v1/pdf-jobs`, `/v1/data`, `/v1/nl-query`, `/v1/edit`, `/v1/sql-check` 순서로 연결합니다.
- AI의 DB 연결은 `AI_DATABASE_URL`과 `AI_DB_SCHEMA=report`를 사용하며 항상 읽기 전용입니다. 스키마 생성·값 적재 함수는 운영 앱에서 제거했습니다.
- `ITEM_GAP`과 `gap_key`를 사용하고, 기준 월과 확정·잠정 상태는 백엔드 `dim_version` 값을 따릅니다. 소명 건수는 검토 항목별 최신 회차 기준입니다.
- AI는 파일 결과·호출 로그·디스크 캐시를 만들지 않습니다. XLSX는 백엔드 RPT-16으로 내보내며 AI 채팅 응답의 `file`은 `null`입니다.
- 개발 데모와 `/v1/dev/*`는 Vue의 필요한 기능이 모두 이관될 때까지 유지합니다. 개발 경로는 `AI_PDF_DEV=1`에서만 열립니다. `/v1/chat`, `/v1/report/key-messages`는 같은 핵심 서비스를 확인하는 얇은 개발 입구입니다. 호출이 없던 옛 `/v1/report-authoring/*`와 행·블록 추측 경로는 제거했습니다.

## LangChain과 LangGraph의 역할

LangGraph는 자연어 조회의 해석·검사·실행·재시도 순서를 관리합니다. LangChain은 각 단계에서 사용하는 공통 모델 호출 인터페이스입니다. 둘은 같은 일을 두 번 수행하지 않습니다. 실제 SDK 전송은 `services/llm.py` 한 곳이며, 최신 백엔드 계약과 PDF·편집·핵심 문장 기능이 이를 공유합니다. 별도 Responses 호출을 만들던 폐기된 보고 작성 경로는 삭제했습니다.

## 설정과 사용량

설정 우선순위는 `ai/.env` → 셸 환경변수 → 기본값입니다. 실제 비밀값은 저장소나 로그에 남기지 않습니다.

| 설정 | 기본값 | 의미 |
|---|---|---|
| `OPENAI_MODEL` | `gpt-4o-mini` | 공통 텍스트 모델 |
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

외부 패키지·실제 DB·모델 호출 없이 회귀 검증하려면 저장소 루트에서 `python3 -B ai/tests/test_stateless_runtime.py`를 실행합니다. 전체 pytest는 외부 패키지와 PostgreSQL이 필요하며 기존 fixture가 임시 서버·파일을 만들 수 있으므로 생성이 승인된 환경에서만 실행합니다.
