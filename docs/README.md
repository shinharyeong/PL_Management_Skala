# docs

프로젝트 공통 업무 규칙과 설계를 관리합니다. 상세 논리 스키마와 파서 인계 문서는 `docs/development/`에 두며, 영역별 구현 문서는 각 영역의 README와 docs에서 관리합니다.

## 문서 목록

- [도메인 규칙](domain-rules.md) — 전체 제출·금액 수정 소명·손익 Gap·승인 마스터 업무 규칙
- [업무 흐름도](flow.md) — 연간 계획·월 실적·소명 검토·최초 및 후속 마스터 흐름
- [ERD 도식](erd.md) — 19개 테이블·154개 필드·33개 핵심 관계의 직선 SVG
- [논리 ERD 상세](development/erd.md) — 설계 v0.2와 V3~V5 물리 구현의 컬럼·제약·상태 전이·검증 조건
- [파서 결과와 DB 매핑](development/db-mapping.md) — 현재 파서 필드, 기술 마스터와 승인 마스터의 차이, 서비스 적재·비교 연결
- [아키텍처](architecture.png) — AWS EKS · S3 · RDS 기반 인프라 구성도
- [서비스 API 명세](development/api-contract.md) — 0.9-draft, 업무 계약 39개 중 연간 확정·소명·월간 승인 마스터까지 36개 경로
- [백엔드·파서 연결](development/backend-integration.md) — 설정·실행·파싱·검증 범위
- [백엔드 함수 명세](development/backend-function-reference.md) — demo·parsed-records를 포함한 소스·테스트 함수와 호출 관계
- [프런트 구현 안내](development/frontend-guide.md) — Figma 화면·라우트·API 흐름·함수 명세
- [로컬 Docker 환경](development/local-environment.md) — `.env`·실행·종료·DB 볼륨·smoke 검증
- [demo API와 파싱 후보](development/demo-api.md) — 개발 역할·17개 사업부 선택·합성 양식 2개 경로와 업무 API의 읽기 전용 후보 조회

개발 전용 `/api/dev` 2개 경로는 업무 API 39개 설계·36개 구현 집계와 구분합니다. 파싱 성공은 업무 검증·승인을 의미하지 않으며 후속 업무 기능의 성공 결과를 대신 만들지 않습니다.

## 문서 적용 기준

업무 규칙·논리 스키마는 [논리 ERD 상세](development/erd.md)가 기준입니다. 현재 파서의 실제 필드·타입은 [parser 출력 계약](../parser/docs/development/output-contract.md)을 따릅니다. 설계 문서 갱신은 기존 파서의 출력 형식이나 서비스 구현 완료를 뜻하지 않습니다.

입력 Excel, 원본 셀 값, 실행 결과·로그·캐시·비밀 설정은 문서 PR에 포함하지 않습니다. 수식 조사 근거는 원본 파일명·시트·셀 주소와 집계 규칙만 기록합니다.
