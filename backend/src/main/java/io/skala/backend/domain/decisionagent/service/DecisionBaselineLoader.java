package io.skala.backend.domain.decisionagent.service;

import static io.skala.backend.domain.decisionagent.dto.DecisionAgentDtos.*;

import io.skala.backend.domain.master.service.MasterLabels;
import io.skala.backend.global.exception.BusinessException;
import io.skala.backend.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 최신 승인본 대체 없이 선택한 확정 master의 연간 전망만 읽어 불변 분석 기준을 만든다. */
@Component
@RequiredArgsConstructor
public class DecisionBaselineLoader {
    private final JdbcTemplate jdbc;
    private final Clock clock;

    /** 목적: 확정된 월별 master 버전 선택지를 조회한다. Args: year 기준 연도, null이면 올해. Returns: 모든 확정 버전. Raises: INVALID_REQUEST 연도 오류. Side Effects: DB 읽기. */
    public List<Baseline> list(Integer year) {
        int selectedYear = year == null ? LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul"))).getYear() : year;
        if (selectedYear < 2000 || selectedYear > 9999) throw new BusinessException(ErrorCode.INVALID_REQUEST, "year는 2000~9999입니다.");
        return jdbc.query("""
                SELECT m.id, p.year, r.month, m.seq, m.created_at, count(ms.submission_id) AS source_count
                FROM master_version m JOIN submission_request r ON r.id=m.request_id
                JOIN annual_plan p ON p.id=r.annual_plan_id
                JOIN master_source ms ON ms.master_id=m.id
                WHERE r.type='MONTHLY_ACTUAL' AND p.year=?
                GROUP BY m.id,p.year,r.month ORDER BY r.month DESC,m.seq DESC
                """, (rs, i) -> new Baseline(rs.getString("id"), MasterLabels.monthly(rs.getInt("month"), rs.getInt("seq")),
                rs.getInt("year"), rs.getInt("month"), rs.getObject("created_at", OffsetDateTime.class), rs.getInt("source_count")), selectedYear);
    }

    /** 목적: 생성 시 선택한 master의 수치·조직·출처를 복사한다. Args: masterId 확정 버전 식별자. Returns: 저장할 기준 snapshot. Raises: 404 버전 없음, INVALID_REQUEST 결측/불완전 원천. Side Effects: DB 읽기. */
    public Snapshot load(long masterId) {
        Baseline baseline = jdbc.query("""
                SELECT m.id,p.year,r.month,m.seq,m.created_at,
                       (SELECT count(*) FROM master_source WHERE master_id=m.id) AS source_count
                FROM master_version m JOIN submission_request r ON r.id=m.request_id
                JOIN annual_plan p ON p.id=r.annual_plan_id WHERE m.id=? AND r.type='MONTHLY_ACTUAL'
                """, (rs, i) -> new Baseline(rs.getString("id"), MasterLabels.monthly(rs.getInt("month"), rs.getInt("seq")),
                rs.getInt("year"), rs.getInt("month"), rs.getObject("created_at", OffsetDateTime.class), rs.getInt("source_count")), masterId)
                .stream().findFirst().orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "확정 월간 master를 선택해 주세요."));
        if (baseline.sourceCount() == 0 || baseline.sourceCount() > 1000) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "기준 master의 원천 조직 수는 1~1000개여야 합니다.");
        }
        List<SourceAmounts> sources = jdbc.query("""
                SELECT ms.submission_id,o.id,o.code,o.name,s.status,s.organization_id=ms.organization_id AS same_org,
                       s.request_id=m.request_id AS same_request,
                       sum(v.amount) FILTER (WHERE ri.kind='REVENUE') AS revenue,
                       sum(v.amount) FILTER (WHERE ri.kind='COST') AS cost,
                       count(DISTINCT v.month) FILTER (WHERE ri.kind='REVENUE') AS revenue_months,
                       count(DISTINCT v.month) FILTER (WHERE ri.kind='COST') AS cost_months,
                       count(DISTINCT l.id) FILTER (WHERE ri.kind IN ('REVENUE','COST')) AS line_count,
                       count(v.id) FILTER (WHERE ri.kind IN ('REVENUE','COST')) AS value_count
                FROM master_source ms JOIN master_version m ON m.id=ms.master_id
                JOIN organization o ON o.id=ms.organization_id JOIN submission s ON s.id=ms.submission_id
                LEFT JOIN financial_line l ON l.submission_id=ms.submission_id
                LEFT JOIN report_item ri ON ri.id=l.report_item_id
                LEFT JOIN financial_value v ON v.line_id=l.id AND v.year=?
                     AND ((v.scenario='ACTUAL' AND v.month BETWEEN 1 AND ?)
                       OR (v.scenario='ESTIMATE' AND v.month BETWEEN ? AND 12))
                WHERE ms.master_id=? GROUP BY ms.submission_id,o.id,s.status,s.organization_id,ms.organization_id,s.request_id,m.request_id
                ORDER BY o.code,o.id
                """, (rs, i) -> {
                    BigDecimal revenue = rs.getBigDecimal("revenue");
                    BigDecimal cost = rs.getBigDecimal("cost");
                    if (!"APPROVED".equals(rs.getString("status")) || !rs.getBoolean("same_org") || !rs.getBoolean("same_request")
                            || revenue == null || cost == null || rs.getInt("revenue_months") != 12 || rs.getInt("cost_months") != 12
                            || rs.getLong("value_count") != 12L * rs.getLong("line_count")) {
                        throw new BusinessException(ErrorCode.INVALID_REQUEST, "기준 master 원천의 승인 상태 또는 12개월 매출·비용 범위가 불완전합니다. 누락을 0으로 간주하지 않습니다.");
                    }
                    return new SourceAmounts(rs.getString("submission_id"), new Organization(rs.getString("id"),
                            rs.getString("code"), rs.getString("name"), DecisionScenarioCalculator.decimal(revenue),
                            DecisionScenarioCalculator.decimal(cost), DecisionScenarioCalculator.decimal(revenue.subtract(cost))));
                }, baseline.year(), baseline.month(), baseline.month() + 1, masterId);
        if (sources.size() != baseline.sourceCount()) throw new BusinessException(ErrorCode.INVALID_REQUEST, "master 원천 조직을 모두 확인할 수 없습니다.");
        BigDecimal profit = sources.stream().map(source -> new BigDecimal(source.organization().profitWon())).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Snapshot(baseline.masterId(), baseline.label(), baseline.year(), baseline.month(), baseline.createdAt(),
                sources.stream().map(SourceAmounts::submissionId).toList(), sources.stream().map(SourceAmounts::organization).toList(),
                DecisionScenarioCalculator.decimal(profit));
    }

    private record SourceAmounts(String submissionId, Organization organization) { }
}
