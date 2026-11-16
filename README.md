# skala_final_project

SKALA 최종 프로젝트 모노레포입니다.

## 폴더 구조

| 폴더 | 내용 | 기술 |
| --- | --- | --- |
| [`frontend/`](frontend/) | 웹 프론트엔드 | Vue 3, Vite, Vue Router, Pinia |
| [`backend/`](backend/) | API 서버 | Spring Boot 3.4, Java 21, Gradle |
| [`ai/`](ai/) | AI 기능 | FastAPI, LangChain (구성 예정) |
| [`parser/`](parser/) | 경영기획 Excel 파싱·검증 | Python 3.11+ (표준 라이브러리) |
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

서비스 API 0.7-draft는 전체 설계 35개 중 18개를 구현했습니다. 원본 접수·파싱에서 최초 연간 PLAN의 업무 검증·금융 적재·최종 제출(PENDING), 기획자의 적재행·보고 집계·진단·원본 셀 조회까지 연결합니다. `/api/dev` 역할 선택·합성 양식 2개는 별도 개발 기능입니다. 파싱만 완료하면 DRAFT/NOT_RUN이며 담당자의 명시적 검증과 최종 제출이 필요합니다. 연간 제출 승인·반려와 검토 결과 조회를 지원합니다. 수정 소명·월간 비교/승인·연간 확정·공식 마스터는 후속 범위입니다.

- [프런트 구현과 함수 명세](docs/development/frontend-guide.md)
- [서비스 API 명세](docs/development/api-contract.md)
- [demo API와 파싱 후보 미리보기](docs/development/demo-api.md)
- [백엔드·파서 연결](docs/development/backend-integration.md)
- [백엔드 함수 명세](docs/development/backend-function-reference.md)
- [문서 목록](docs/README.md)

## 협업 규칙

브랜치 전략, 커밋·이슈·PR 규칙은 [CONTRIBUTING.md](CONTRIBUTING.md)를 따릅니다.

입력 데이터, 실행 결과, `.env` 등 비밀 정보는 커밋하지 않습니다.
