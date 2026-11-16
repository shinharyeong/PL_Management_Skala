# docs

프로젝트 공통 업무 규칙과 설계를 관리합니다. 상세 논리 스키마와 파서 인계 문서는 `docs/development/`에 두며, 영역별 구현 문서는 각 영역의 README와 docs에서 관리합니다.

## 문서 목록

- [도메인 규칙](domain-rules.md) — 전체 제출·금액 수정 소명·손익 Gap·승인 마스터 업무 규칙
- [업무 흐름도](flow.md) — 연간 계획·월 실적·소명 검토·최초 및 후속 마스터 흐름
- [ERD 도식](erd.md) — 17개 테이블·141개 필드·27개 핵심 관계의 직선 SVG
- [논리 ERD 상세](development/erd.md) — 설계 v0.2의 컬럼·제약·상태 전이·검증 조건
- [파서 결과와 DB 매핑](development/db-mapping.md) — 현재 파서 필드, 기술 마스터와 승인 마스터의 차이, 미구현 적재 어댑터
- [아키텍처](architecture.png) — AWS EKS · S3 · RDS 기반 인프라 구성도
- [월간 시트 관리 연결 과제](development/monthly-sheet-workflow-gap.md) — 월간 중심 업무·사용자 선택 소명·조직별 작업공간 요구, 현재 구현과 추가 개발·QA 조건 구분
- [서비스 API 명세](development/api-contract.md) — 0.6-draft, 업무 계약 35개 중 최초 연간 제출·적재 조회 17개 경로
- [백엔드·파서 연결](development/backend-integration.md) — 설정·실행·파싱·검증 범위
- [백엔드 함수 명세](development/backend-function-reference.md) — demo·parsed-records를 포함한 소스·테스트 함수와 호출 관계
- [프런트 구현 안내](development/frontend-guide.md) — Figma 화면·라우트·API 흐름·함수 명세
- [로컬 Docker 환경](development/local-environment.md) — `.env`·실행·종료·DB 볼륨·smoke 검증
- [demo API와 파싱 후보](development/demo-api.md) — 개발 역할·17개 사업부 선택·합성 양식 2개 경로와 업무 API의 읽기 전용 후보 조회

개발 전용 `/api/dev` 2개 경로는 업무 API 35개 집계와 구분합니다. 파싱 성공은 업무 검증·승인을 의미하지 않으며 후속 업무 기능의 성공 결과를 대신 만들지 않습니다.

## 문서 적용 기준

월간 시트 관리의 사용자 정정 요구와 기존 설계의 충돌은 [월간 연결 과제](development/monthly-sheet-workflow-gap.md#3-기존-설계-문서와-정합성을-맞출-항목)에 기록합니다. 해당 문서는 모든 금액 변경의 자동 필수 소명과 OP 전용 Gap 규칙을 제품 요구로 확정하지 않습니다. 관련 도메인·API·검증 규칙의 정합성 수정은 후속 설계·개발 과제이며, 문서 추가가 실행 코드의 정책 변경을 뜻하지 않습니다.

업무 규칙·논리 스키마는 [논리 ERD 상세](development/erd.md)가 기준입니다. 현재 파서의 실제 필드·타입은 [parser 출력 계약](../parser/docs/development/output-contract.md)을 따릅니다. 설계 문서 갱신은 기존 파서의 출력 형식이나 서비스 구현 완료를 뜻하지 않습니다.

입력 Excel, 원본 셀 값, 실행 결과·로그·캐시·비밀 설정은 문서 PR에 포함하지 않습니다. 수식 조사 근거는 원본 파일명·시트·셀 주소와 집계 규칙만 기록합니다.

