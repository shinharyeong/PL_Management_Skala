-- 상세 행의 본부명·팀명·실행과제명·담당자(#58). 파서 submissionData.lines가 주는 값을 그대로 저장한다.
-- AI 보고서 조회(fact.team, fact.manager)와 "○○팀 비용", "담당자별" 질문에 쓴다. 모두 비어 있을 수 있다.
ALTER TABLE "financial_line"
  ADD COLUMN "headquarters_name" varchar(500),
  ADD COLUMN "team_name" varchar(500),
  ADD COLUMN "task_name" varchar(500),
  ADD COLUMN "person_in_charge" varchar(500);
