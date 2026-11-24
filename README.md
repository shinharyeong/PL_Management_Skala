# skala_final_project

SKALA 최종 프로젝트 모노레포입니다.

## 폴더 구조

| 폴더 | 내용 | 기술 |
| --- | --- | --- |
| [`frontend/`](frontend/) | 웹 프론트엔드 | Vue 3, Vite, Vue Router, Pinia |
| [`backend/`](backend/) | API 서버 | Spring Boot 3.4, Java 21, Gradle |
| [`ai/`](ai/) | AI 기능 | FastAPI, LangChain (구성 예정) |
| [`parser/`](parser/) | 경영기획 Excel 파싱·검증 (.xlsx·.xlsm·.xlsb) | Python 3.11+ (표준 라이브러리) |
| [`docs/`](docs/) | 공통 문서 | |

## 로컬 실행

Docker Desktop과 저장소 루트의 로컬 `.env`를 준비한 뒤 실행합니다. 필수 변수·포트·비밀값·DB 볼륨은 [로컬 실행 환경](docs/development/local-environment.md)을 따릅니다.

```bash
bash scripts/dev-up.sh
# http://localhost:5173
python3 -B scripts/smoke-local.py
bash scripts/dev-down.sh
```

Compose는 Vue 정적 프런트, Java 21·Python 3.11 백엔드, PostgreSQL을 연결합니다. `/api`는 같은 출처로 프록시하며 개발 세션 토큰은 브라우저 메모리에서만 사용합니다. smoke는 실제 개발 DB에 합성 정상·손상 초안을 추가하고, 종료는 DB 볼륨을 보존합니다. `Data/`는 이 흐름에서 사용하지 않습니다. 영역별 개발 서버·테스트 명령은 각 폴더 README를 참고하세요.

## 구현 범위와 문서

서비스 API 0.9-draft는 전체 설계 39개 중 36개를 구현했습니다. 전체 파일 접수·파싱·금융 적재에서 연간 계획 확정, 금액 수정·OP Gap 소명, 기획자의 수용·보완 요청, 연간/월간 승인과 마스터 발행까지 연결합니다. 파싱만 완료하면 DRAFT/NOT_RUN이며 담당자의 검증·필수 소명 답변·최종 제출이 필요합니다. 최초 마스터는 전 사업조직 승인 후 발행하고 이후에는 새로 승인된 조직의 전체 스냅샷만 교체합니다.

홈은 연간 PLAN과 월간 ACTUAL을 구분하고 실적은 고정된 확정 계획과 비교합니다. Contingency는 명시적으로 입력한 전사 연간 값으로 별도 표시하며 월별 배분·차감은 하지 않습니다. 보고 항목 목록·요청 수정·별도 보고 데이터 구성 3개 API는 후속 범위이고 `/api/dev` 역할 선택·합성 양식 2개는 별도 개발 기능입니다. 구현 범위와 실제 실행 검증 결과는 구분하며 [로컬 실행 환경](docs/development/local-environment.md)의 기록을 따릅니다.

- [문서 목록](docs/README.md) — PRD·인프라 구성·로컬 실행 환경

## 협업 규칙

브랜치 전략, 커밋·이슈·PR 규칙은 [CONTRIBUTING.md](CONTRIBUTING.md)를 따릅니다.

입력 데이터, 실행 결과, `.env` 등 비밀 정보는 커밋하지 않습니다.
