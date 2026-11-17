# 손익 제출·검토·마스터 ERD

2026-11-16. 논리 설계 v0.2에 V3 연간 PLAN 마스터·V4 월간 ACTUAL 마스터·V5 수정/Gap 소명 구현을 연결했습니다. API 0.9-draft는 39개 중 36개 경로를 구현합니다. 전체 재제출, 모든 금액 수정의 소명, 전 사업 조직 최초 승인과 이후 승인 조직별 마스터 교체 규칙을 유지합니다.

![손익 제출·검토·마스터 ERD](erd.svg)

[직선 SVG 원본](erd.svg) · [테이블·컬럼·제약과 확정 규칙](development/erd.md) · [파서 결과와 DB 매핑](development/db-mapping.md) · [V3 실제 마이그레이션](../backend/src/main/resources/db/migration/V3__annual_plan_masters.sql)

19개 테이블·154개 필드·33개 핵심 관계입니다. 연결선은 수평·수직 직선이며 행위자 FK는 컬럼에 표시합니다. 하단 점선 카드는 상단 테이블을 참조하는 별칭으로, 테이블 수에 중복 포함하지 않습니다.

하단 `annual_master` 9개 필드·`annual_master_source` 3개 필드와 `request_target.excluded`는 V3 구현입니다. 17개 사업 조직의 최초 승인이 끝나면 연간 마스터를 발행하고, 이후 일부 조직 재승인 시 해당 조직의 제출본을 교체한 새 조합을 보존합니다. 금액은 복제하지 않고 승인된 제출본의 적재값을 참조합니다. 이는 Contingency를 포함한 연간 계획 확정과 별도입니다.

도식은 업무 엔티티와 관계를 표현하는 논리 설계이며 물리 스키마를 그대로 나열한 그림은 아닙니다. `master_version`·`master_source`는 V4, 수정/Gap 검토와 소명 이력은 V5로 구현했습니다. 연간 확정은 기존 `annual_plan`·조직별 확정 계획 참조를 사용합니다. 비교 문맥의 검증 객체 저장·V5 집계 정밀도·기술 테이블 등 물리 차이는 [구현 확장](development/erd.md#11-구현-확장-월-실적소명v4v5)과 Flyway를 기준으로 합니다. 도표 수치와 직선 SVG는 유지하며 실제 실행 결과는 [로컬 환경](development/local-environment.md)에서 관리합니다. 이전 초안은 Git 이력에서 확인할 수 있습니다.
