package io.skala.backend.domain.decisionagent.service;

import static io.skala.backend.domain.decisionagent.dto.DecisionAgentDtos.*;
import static io.skala.backend.domain.decisionagent.dto.DecisionResearchDtos.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.skala.backend.global.exception.BusinessException;
import io.skala.backend.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** 고정 분석의 제출 ID로만 조회하고 최초 조사 근거를 불변 JSON으로 보존한다. */
@Repository
@RequiredArgsConstructor
public class DecisionResearchStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final DecisionBaselineLoader baselines;
    private final Clock clock;

    /** 목적: 이미 고정한 조사와 가설을 읽는다. Args: analysisId 소유권 확인을 마친 분석. Returns: 연구 또는 null. Raises: 저장 JSON 오류. Side Effects: DB 읽기. */
    public Research find(String analysisId) {
        return jdbc.query("SELECT captured_at,evidence,revision,discovery FROM decision_agent_research WHERE analysis_id=?",
                (rs, row) -> new Research(analysisId, rs.getInt("revision"), rs.getObject("captured_at", OffsetDateTime.class),
                        decodeEvidence(rs.getString("evidence")), decodeDiscovery(rs.getString("discovery"))), Long.parseLong(analysisId))
                .stream().findFirst().orElse(null);
    }

    /** 목적: 최초 조사에서 승인 원천·소명을 복사한다. Args: analysis 잠근 분석. Returns: 불변 근거. Raises: 원천 변경/결측 또는 DB 오류. Side Effects: 최초 조사 JSON 저장. */
    public Research capture(Analysis analysis) {
        Research existing = find(analysis.id());
        if (existing != null) return existing;
        Snapshot current = baselines.load(Long.parseLong(analysis.snapshot().masterId()));
        assertSameAmounts(analysis.snapshot(), current);
        List<Evidence> evidence = loadEvidence(analysis.snapshot());
        OffsetDateTime capturedAt = OffsetDateTime.now(clock);
        jdbc.update("INSERT INTO decision_agent_research(analysis_id,captured_at,evidence,revision) VALUES(?,?,?::jsonb,?)",
                Long.parseLong(analysis.id()), capturedAt, encode(evidence), analysis.revision());
        return new Research(analysis.id(), analysis.revision(), capturedAt, evidence, null);
    }

    /** 목적: 같은 근거에 대해 생성한 가설만 교체한다. Args: research 기존 근거, revision 실행 당시 분석 버전, discovery 검증된 가설. Returns: 저장된 조사. Raises: DB 오류. Side Effects: discovery·revision만 DB 갱신. */
    public Research save(Research research, int revision, Discovery discovery) {
        jdbc.update("UPDATE decision_agent_research SET revision=?,discovery=?::jsonb WHERE analysis_id=?", revision,
                encode(discovery), Long.parseLong(research.analysisId()));
        return new Research(research.analysisId(), revision, research.capturedAt(), research.evidence(), discovery);
    }

    /** 목적: 원천 변경 후 다른 숫자의 근거를 섞지 않는다. Args: fixed 분석 기준, current 현재 원천 계산. Returns: 없음. Raises: RESOURCE_CHANGED 불일치. Side Effects: 없음. */
    void assertSameAmounts(Snapshot fixed, Snapshot current) {
        Map<String, Organization> amounts = new LinkedHashMap<>();
        current.organizations().forEach(org -> amounts.put(org.id(), org));
        boolean same = new HashSet<>(fixed.sourceSubmissionIds()).equals(new HashSet<>(current.sourceSubmissionIds())) && fixed.organizations().size() == amounts.size();
        for (Organization org : fixed.organizations()) {
            Organization now = amounts.get(org.id());
            same &= now != null && new BigDecimal(org.revenueWon()).compareTo(new BigDecimal(now.revenueWon())) == 0
                    && new BigDecimal(org.costWon()).compareTo(new BigDecimal(now.costWon())) == 0;
        }
        if (!same) throw new BusinessException(ErrorCode.RESOURCE_CHANGED, "분석 기준의 원천 금액이 변경되었습니다. 새 분석을 생성해 주세요.");
    }

    /** 목적: 최신 월별 뷰 대신 정확한 제출본에서 손익과 수락된 소명을 읽는다. Args: snapshot 불변 분석 범위. Returns: 최대 손익100·소명50개 근거. Raises: DB 조회 오류. Side Effects: DB 읽기. */
    public List<Evidence> loadEvidence(Snapshot snapshot) {
        var query = new NamedParameterJdbcTemplate(jdbc);
        Map<String, Object> params = Map.of("sources", snapshot.sourceSubmissionIds().stream().map(Long::parseLong).toList(),
                "year", snapshot.year(), "month", snapshot.baseMonth());
        Map<String, String> organizations = new LinkedHashMap<>();
        snapshot.organizations().forEach(org -> organizations.put(org.id(), org.name()));
        List<Evidence> evidence = new ArrayList<>(query.query("""
                SELECT s.organization_id,ri.id AS item_id,ri.name AS item_name,ri.kind,
                  sum(v.amount) FILTER (WHERE (v.scenario='ACTUAL' AND v.month<=:month)
                    OR (v.scenario='ESTIMATE' AND v.month>:month)) AS forecast,
                  CASE WHEN count(v.id) FILTER (WHERE v.scenario='PLAN')=12*count(DISTINCT l.id)
                    THEN sum(v.amount) FILTER (WHERE v.scenario='PLAN') ELSE NULL END AS plan,
                  string_agg(DISTINCT s.id::text, ',') AS source_ids
                FROM submission s JOIN financial_line l ON l.submission_id=s.id
                JOIN report_item ri ON ri.id=l.report_item_id AND ri.kind IN ('REVENUE','COST')
                JOIN financial_value v ON v.line_id=l.id AND v.year=:year
                WHERE s.id IN (:sources) GROUP BY s.organization_id,ri.id
                ORDER BY abs(sum(v.amount) FILTER (WHERE (v.scenario='ACTUAL' AND v.month<=:month)
                  OR (v.scenario='ESTIMATE' AND v.month>:month))) DESC,s.organization_id,ri.id LIMIT 100
                """, params, (rs, row) -> {
                    String org = rs.getString("organization_id");
                    String kind = rs.getString("kind");
                    String label = organizations.get(org) + " · " + rs.getString("item_name");
                    BigDecimal forecast = rs.getBigDecimal("forecast"), plan = rs.getBigDecimal("plan");
                    Map<String, Object> facts = new LinkedHashMap<>();
                    facts.put("reportItemId", rs.getString("item_id")); facts.put("kind", kind);
                    facts.put("forecastWon", DecisionScenarioCalculator.decimal(forecast));
                    facts.put("planWon", plan == null ? null : DecisionScenarioCalculator.decimal(plan));
                    facts.put("varianceWon", plan == null ? null : DecisionScenarioCalculator.decimal(forecast.subtract(plan)));
                    facts.put("profitImpactWon", plan == null ? null : DecisionScenarioCalculator.decimal(
                            "REVENUE".equals(kind) ? forecast.subtract(plan) : plan.subtract(forecast)));
                    facts.put("sourceSubmissionIds", List.of(rs.getString("source_ids").split(",")));
                    facts.put("comparison", "SAME_SUBMISSION_PLAN_VS_ANNUAL_OUTLOOK");
                    String text = label + ": 연간 전망 " + facts.get("forecastWon") + "원. "
                            + (plan == null ? "동일 제출본의 12개월 계획이 불완전해 비교하지 않습니다."
                            : "동일 제출본 계획 " + facts.get("planWon") + "원, 전망-계획 " + facts.get("varianceWon") + "원.")
                            + " 이는 과거·전망 자료의 비교이며 개선 가능액이나 인과 원인의 확정이 아닙니다.";
                    return new Evidence("pnl-" + org + "-" + rs.getString("item_id"), "PNL", org, label, text, facts);
                }));
        evidence.addAll(query.query("""
                SELECT s.organization_id,ri.id AS review_id,i.name AS item_name,ri.month,ri.scenario,
                    acc.id AS explanation_id,acc.answer,acc.reviewed_at,ri.submission_id
                FROM review_item ri JOIN submission s ON s.id=ri.submission_id
                JOIN report_item i ON i.id=ri.report_item_id
                JOIN LATERAL (SELECT re.id,re.answer,re.reviewed_at FROM review_explanation re
                    WHERE re.review_item_id=ri.id AND re.verdict='ACCEPTED' AND re.answer IS NOT NULL
                    ORDER BY re.round DESC LIMIT 1) acc ON true
                WHERE ri.submission_id IN (:sources) AND ri.year=:year
                  AND ((ri.scenario='ACTUAL' AND ri.month<=:month) OR (ri.scenario='ESTIMATE' AND ri.month>:month))
                ORDER BY acc.reviewed_at DESC NULLS LAST,acc.id DESC LIMIT 50
                """, params, (rs, row) -> {
                    String org = rs.getString("organization_id");
                    String label = organizations.get(org) + " · " + rs.getString("item_name") + " · " + rs.getInt("month") + "월 수락 소명";
                    Map<String, Object> facts = new LinkedHashMap<>();
                    facts.put("submissionId", rs.getString("submission_id")); facts.put("reviewItemId", rs.getString("review_id"));
                    facts.put("explanationId", rs.getString("explanation_id")); facts.put("month", rs.getInt("month"));
                    facts.put("scenario", rs.getString("scenario")); facts.put("reviewedAt", rs.getString("reviewed_at"));
                    return new Evidence("explanation-" + rs.getString("explanation_id"), "APPROVED_EXPLANATION", org,
                            label, rs.getString("answer"), facts);
                }));
        return List.copyOf(evidence);
    }

    /** 목적: 근거·가설을 JSON으로 변환한다. Args: value 저장 값. Returns: JSON 문자열. Raises: IllegalStateException 직렬화 오류. Side Effects: 없음. */
    private String encode(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException cause) { throw new IllegalStateException("조사 상태를 저장할 수 없습니다.", cause); }
    }

    /** 목적: 고정 근거 목록을 복원한다. Args: value JSON. Returns: 근거 목록. Raises: IllegalStateException 저장 계약 오류. Side Effects: 없음. */
    private List<Evidence> decodeEvidence(String value) {
        try { return mapper.readValue(value, new TypeReference<List<Evidence>>() { }); }
        catch (JsonProcessingException cause) { throw new IllegalStateException("근거 상태를 읽을 수 없습니다.", cause); }
    }

    /** 목적: 마지막 생성한 가설을 복원한다. Args: value nullable JSON. Returns: 가설 또는 null. Raises: IllegalStateException 저장 계약 오류. Side Effects: 없음. */
    private Discovery decodeDiscovery(String value) {
        try { return value == null ? null : mapper.readValue(value, Discovery.class); }
        catch (JsonProcessingException cause) { throw new IllegalStateException("가설 상태를 읽을 수 없습니다.", cause); }
    }
}
