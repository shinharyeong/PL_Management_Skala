# docs

요구사항은 PRD를 기준으로 합니다. 인프라 구성, 물리 ERD, API 명세와 개발 변경·검증 범위는 아래 문서에서 관리합니다.

- [PRD](경영팀을부탁해_PRD_ver1.xlsx) — 기능·비기능 요구사항, 용어, 범위 (기준 문서)
- [인프라 구성](infrastructure.md) — SKALA 플랫폼 EKS 공유 클러스터(`skala-pg`) 기준 요청 경로·워크로드·RDS·ECR·GitHub Actions 배포·runner·모니터링. RabbitMQ·S3 원본 저장은 2차 목표 구성
- [인프라 구성도](architecture.png)
- [물리 ERD](erd/physical-erd.svg) — PRD·와이어프레임·인프라 구성을 반영한 DB 설계안 도식. 원본은 [DBML](erd/physical-erd.dbml)이며 [dbdiagram.io](https://dbdiagram.io) 편집창에 붙여 넣어 수정함
- [API 명세](api/api-spec.md) — 화면별 API 개요·공통 규칙·FR 대응. 상세(요청·응답·오류)는 [OpenAPI](api/openapi.yaml)
- [로컬 Docker 환경](development/local-environment.md) — `.env`·실행·종료·DB 볼륨·smoke 검증
- [공개 API와 화면 연결](development/api-integration.md) — 현재 미연결 경로·보고서 연결·추가 구현 범위
- [함수 명세](development/function-reference.md) — 이슈 수정·보고서 화면의 입출력·호출 관계
- [프론트 이슈 계약](development/frontend-issue-contract.md) — 활성 진입점·검토·제출·XLSB 입력 계약
- [경영 의사결정 에이전트](development/pnl-decision-agent.md) — 고정 근거 조사·조직 검토·시나리오·결정 기록·후속 전망·A4 요약의 범위와 검증 기록
- [대화형 Agent 도구 계약](development/agent-tool-contract.md) — 기존 업무 서비스 재사용, native 도구 호출 루프, 조회 권한·출처·예산 경계

입력 Excel, 원본 셀 값, 실행 결과·로그·캐시·비밀 설정은 문서 PR에 포함하지 않습니다.
