-- 분석 기준의 원천에서 최초 조회한 근거를 보존한다. 운영 DB에 자동 적용하지 않고 검토 후 배포한다.
CREATE TABLE decision_agent_research (
    analysis_id BIGINT PRIMARY KEY REFERENCES decision_agent_analysis(id),
    captured_at TIMESTAMPTZ NOT NULL,
    evidence JSONB NOT NULL,
    revision INTEGER NOT NULL CHECK (revision > 0),
    discovery JSONB
);

-- 목적: 조사 최초 캡처의 분석·시각·근거를 불변으로 유지한다.
-- Args: NEW/OLD 조사 행. Returns: 허용된 NEW. Raises: 원천 변경 예외. Side Effects: 금지된 DB 갱신 중단.
CREATE FUNCTION guard_decision_agent_evidence() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.analysis_id IS DISTINCT FROM OLD.analysis_id OR NEW.captured_at IS DISTINCT FROM OLD.captured_at
       OR NEW.evidence IS DISTINCT FROM OLD.evidence THEN
        RAISE EXCEPTION 'decision agent evidence is immutable';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER decision_agent_evidence_immutable BEFORE UPDATE ON decision_agent_research
FOR EACH ROW EXECUTE FUNCTION guard_decision_agent_evidence();
