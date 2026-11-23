-- Gap 기준 적용(GAP-05)과 제출(PROOF-08)이 같은 기준을 보도록 잠그는 행. 표 자체는 V1에 있고 행만 넣는다.
INSERT INTO gap_threshold_guard (id) VALUES (1) ON CONFLICT (id) DO NOTHING;
