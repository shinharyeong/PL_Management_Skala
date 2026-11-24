-- 소명 사유 유형 분류표(FR-RPT-007, RPT-12~14). PRD 규칙을 담을 칸을 추가하고 기본 분류표(table_version 1)를 넣는다.
--   parent_code: 상위 유형이면 하위 유형 합으로 자동 계산한다(예: 2 일시적/비경상 = 3 + 4). 상위 유형에는 카드를 직접 넣지 않는다
--   catch_all: 분류함에 넣지 않은 소명이 자동으로 가는 '기타'. 분류표마다 하나
--   suggest_keywords: 코드가 규칙으로 유형 후보를 제안할 때 쓰는 단어(쉼표 구분, 예: 이연 후보 → 3)
ALTER TABLE "explanation_category"
  ADD COLUMN "parent_code" integer,
  ADD COLUMN "catch_all" boolean NOT NULL DEFAULT false,
  ADD COLUMN "suggest_keywords" varchar(500);

CREATE UNIQUE INDEX "explanation_category_catch_all_key" ON "explanation_category" ("table_version") WHERE "catch_all";

INSERT INTO "explanation_category" ("table_version", "code", "name", "display_order", "parent_code", "catch_all", "suggest_keywords") VALUES
  (1, 1, '월정액 추정 Gap', 1, NULL, false, '월정액'),
  (1, 2, '일시적/비경상 요인', 2, NULL, false, NULL),
  (1, 3, '이연 효과', 3, 2, false, '이연,지연,익월,다음 달'),
  (1, 4, '일시적 효과', 4, 2, false, '일시,일회성,비경상'),
  (1, 5, '기타 과추정 등', 5, NULL, true, NULL)
ON CONFLICT ("table_version", "code") DO NOTHING;
