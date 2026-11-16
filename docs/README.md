# docs

프로젝트 공통 업무 규칙과 설계를 관리합니다. 상세 논리 스키마와 파서 인계 문서는 `docs/development/`에 두며, 영역별 구현 문서는 각 영역의 README와 docs에서 관리합니다.

## 문서 목록

- [도메인 규칙](domain-rules.md) — 전체 제출·금액 수정 소명·손익 Gap·승인 마스터 업무 규칙
- [업무 흐름도](flow.md) — 연간 계획·월 실적·소명 검토·최초 및 후속 마스터 흐름
- [ERD 도식](erd.md) — 17개 테이블·141개 필드·27개 핵심 관계의 직선 SVG
- [논리 ERD 상세](development/erd.md) — 설계 v0.2의 컬럼·제약·상태 전이·검증 조건
- [파서 결과와 DB 매핑](development/db-mapping.md) — 현재 파서 필드, 기술 마스터와 승인 마스터의 차이, 미구현 적재 어댑터
- [아키텍처](architecture.png) — AWS EKS · S3 · RDS 기반 인프라 구성도

## 문서 적용 기준

업무 규칙·논리 스키마는 [논리 ERD 상세](development/erd.md)가 기준입니다. 현재 파서의 실제 필드·타입은 [parser 출력 계약](../parser/docs/development/output-contract.md)을 따릅니다. 설계 문서 갱신은 기존 파서의 출력 형식이나 서비스 구현 완료를 뜻하지 않습니다.

입력 Excel, 원본 셀 값, 실행 결과·로그·캐시·비밀 설정은 문서 PR에 포함하지 않습니다. 수식 조사 근거는 원본 파일명·시트·셀 주소와 집계 규칙만 기록합니다.
