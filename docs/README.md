# docs

요구사항은 PRD를 기준으로 합니다. 그 밖의 문서는 인프라 구성, 물리 ERD, 로컬 실행 방법만 둡니다.

- [PRD](경영팀을부탁해_PRD_ver1.xlsx) — 기능·비기능 요구사항, 용어, 범위 (기준 문서)
- [인프라 구성](infrastructure.md) — SKALA 플랫폼 EKS 공유 클러스터 기준 요청 경로·워크로드·RabbitMQ·S3·RDS·배포
- [인프라 구성도](architecture.png)
- [물리 ERD](erd/physical-erd.svg) — PRD·와이어프레임·인프라 구성을 반영한 DB 설계안 도식. 원본은 [DBML](erd/physical-erd.dbml)이며 [dbdiagram.io](https://dbdiagram.io) 편집창에 붙여 넣어 수정함
- [로컬 Docker 환경](development/local-environment.md) — `.env`·실행·종료·DB 볼륨·smoke 검증

입력 Excel, 원본 셀 값, 실행 결과·로그·캐시·비밀 설정은 문서 PR에 포함하지 않습니다.
