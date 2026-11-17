# docs

프로젝트 공통 업무 규칙과 설계를 관리합니다. 상세 논리 스키마와 파서 인계 문서는 `docs/development/`에 두며, 영역별 구현 문서는 각 영역의 README와 docs에서 관리합니다.

## 문서 목록

- [도메인 규칙](domain-rules.md) — 전체 제출·보고 항목 Gap·명시 소명 요청·승인 마스터 업무 규칙
- [논리 ERD 상세](development/erd.md) — 현재 논리 설계와 V1~V8 물리 구현의 컬럼·제약·상태 전이·검증 조건
- [파서 결과와 DB 매핑](development/db-mapping.md) — 현재 파서 필드, 기술 마스터와 승인 마스터의 차이, 서비스 적재·비교 연결
- [인프라 구성](infrastructure.md) — SKALA 플랫폼 EKS 공유 클러스터 기준 요청 경로·워크로드·RabbitMQ·S3·RDS·배포
- [인프라 구성도](architecture.png) — 위 문서의 구성도
- [월간 시트 관리 연결 과제](development/monthly-sheet-workflow-gap.md) — 작성 당시의 월간 업무·선택 소명·조직별 작업공간 요구와 설계 차이 기록. 최신 구현은 API 명세 기준
- [서비스 API 명세](development/api-contract.md) — 0.11-draft, V1~V8 기반 서비스 56개 메서드·경로와 PRD 검토·첨부·비교·양식·인앱 알림 계약
- [백엔드·파서 연결](development/backend-integration.md) — 설정·실행·파싱·검증 범위
- [백엔드 함수 명세](development/backend-function-reference.md) — demo·parsed-records를 포함한 소스·테스트 함수와 호출 관계
- [프런트 구현 안내](development/frontend-guide.md) — Figma 화면·라우트·API 흐름·함수 명세
- [로컬 Docker 환경](development/local-environment.md) — `.env`·실행·종료·DB 볼륨·smoke 검증
- [demo API와 파싱 후보](development/demo-api.md) — 개발 역할·17개 사업부 선택·합성 양식 2개 경로와 업무 API의 읽기 전용 후보 조회

개발 전용 `/api/dev` 2개 경로는 서비스 API 56개 집계와 구분합니다. 경로 구현 수가 운영 인증이나 모든 PRD 기능의 완료를 뜻하지는 않습니다. 파싱 성공은 업무 검증·승인을 의미하지 않으며 후속 업무 기능의 성공 결과를 대신 만들지 않습니다.

## 문서 적용 기준

[월간 연결 과제](development/monthly-sheet-workflow-gap.md#3-기존-설계-문서와-정합성을-맞출-항목)는 문서에 명시한 고정 커밋 시점의 요구·설계 차이를 기록한 역사 자료입니다. 해당 문서의 미구현 판정을 최신 서비스 상태로 적용하지 않습니다. 현재 업무 계약은 [API 명세](development/api-contract.md), 실제 실행 검증은 [로컬 환경](development/local-environment.md)을 따릅니다.

현재 API 0.11의 신규 검토 정책 2.0은 매출·비용 보고 항목과 OP를 각각 비교하고, Gap 기준 초과 또는 기획자의 명시 요청을 필수 소명 조건으로 사용합니다. 기준 이하 변경도 원천 근거로 보존하지만 자동 필수는 아닙니다. 기존 1.0 제출본의 모든 금액 변경 필수·OP 전용 비교는 과거 스냅샷과 함께 유지합니다. 선정·제외·일괄 소명 요청과 소명 회차·첨부·버전 비교는 연결되어 있습니다.

요청 수신 조직은 선택할 수 있으나 최초 공식 마스터는 생성 시 고정한 전사 필수 조직의 승인이 필요합니다. 이후에는 새 승인 조직만 기존 마스터 구성에서 교체합니다. 연간 계획·Contingency 확정은 마스터 발행과 별도입니다. 인증은 local+demo 코드·Bearer 세션 범위이며 실제 이메일·SSO·외부 알림·조직/권한 관리와 PPT/PNG 출력은 구현 범위 밖입니다. 기존 보고 API는 호환을 위해 유지하며 이번 PRD 프런트 범위에서는 보고서 메뉴를 제외합니다.

업무 규칙·논리 스키마는 [논리 ERD 상세](development/erd.md)가 기준입니다. 현재 파서의 실제 필드·타입은 [parser 출력 계약](../parser/docs/development/output-contract.md)을 따릅니다. 설계 문서 갱신은 기존 파서의 출력 형식이나 서비스 구현 완료를 뜻하지 않습니다.

입력 Excel, 원본 셀 값, 실행 결과·로그·캐시·비밀 설정은 문서 PR에 포함하지 않습니다. 수식 조사 근거는 원본 파일명·시트·셀 주소와 집계 규칙만 기록합니다.
