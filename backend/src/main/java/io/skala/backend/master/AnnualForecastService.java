package io.skala.backend.master;

import static io.skala.backend.submission.ApiData.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.skala.backend.common.ApiException;
import io.skala.backend.security.Actor;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 월간 요청의 최신 승인본을 읽어 실적과 남은 추정의 연간 전망을 제공한다. */
@Service
public class AnnualForecastService {
    private static final String VERSION = "approved-forecast/1.0";
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final AnnualPlanService plans;
    private final MonthlyMasterService masters;

    /** 전망 조회의 읽기 의존성을 연결한다.
     * Args: jdbc DB, mapper JSON, plans 확정 계획, masters 승인 근거 검증기.
     * Returns: 없음. Raises: 없음. Side Effects: 없음.
     */
    public AnnualForecastService(JdbcClient jdbc, ObjectMapper mapper, AnnualPlanService plans, MonthlyMasterService masters) {
        this.jdbc = jdbc; this.mapper = mapper; this.plans = plans; this.masters = masters;
    }

    /** 같은 요청의 최신 승인본을 일관된 스냅샷으로 선택해 전사 전망과 계획 차이를 읽는다.
     * Args: actor 기획자, requestId 월간 요청, query 빈 쿼리.
     * Returns: 출처 토큰·월별 실적/추정·조직별/전사 연간 합계. 미승인 조직이 있으면 전사 금액은 null.
     * Raises: 권한·입력·404·확정 계획 및 승인본 근거 오류.
     * Side Effects: REPEATABLE_READ 조회만 수행하며 공식 월 마스터·승인 상태·원본을 변경하지 않는다.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> get(Actor actor, String requestId, Map<String, String> query) {
        actor.requireRole("PLANNER");
        if (!query.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "전망 조회는 추가 조건을 받지 않습니다.");
        var requests = rows("SELECT r.*,p.year FROM submission_request r JOIN annual_plan p ON p.id=r.annual_plan_id WHERE r.id=?", id(requestId));
        if (requests.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "요청한 자료를 찾을 수 없습니다.");
        var request = requests.getFirst();
        if (!"MONTHLY_ACTUAL".equals(request.get("type"))) throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "월간 실적 요청에서만 연간 전망을 조회할 수 있습니다.");
        int year = ((Number) request.get("year")).intValue(), month = ((Number) request.get("month")).intValue();
        var plan = plans.confirmed(year);
        var targets = rows("""
                SELECT t.organization_id,o.code,o.name,s.id AS submission_id,
                  (SELECT n.status FROM submission n WHERE n.request_id=t.request_id AND n.organization_id=t.organization_id
                    AND n.seq=(SELECT MAX(n2.seq) FROM submission n2 WHERE n2.request_id=t.request_id
                      AND n2.organization_id=t.organization_id AND n2.status<>'DRAFT')) AS latest_status
                FROM request_target t JOIN organization o ON o.id=t.organization_id
                LEFT JOIN submission s ON s.request_id=t.request_id AND s.organization_id=t.organization_id AND s.status='APPROVED'
                  AND s.seq=(SELECT MAX(a.seq) FROM submission a WHERE a.request_id=t.request_id
                    AND a.organization_id=t.organization_id AND a.status='APPROVED')
                WHERE t.request_id=? AND t.excluded=FALSE ORDER BY t.organization_id
                """, id(requestId));
        var targetIds = new TreeSet<Long>();
        for (var target : targets) targetIds.add(((Number) target.get("organization_id")).longValue());
        if (targets.isEmpty() || !targetIds.equals(plan.sources().keySet())) throw new ApiException(HttpStatus.CONFLICT, "MASTER_TARGET_MISMATCH", "월 요청과 확정 계획의 필수 조직 집합이 다릅니다.");
        var metadata = new HashMap<String, Map<String, Object>>();
        for (var report : rows("SELECT id,code,kind FROM report_item")) metadata.put(report.get("id").toString(), report);
        var sources = new ArrayList<Map<String, Object>>(); var missing = new ArrayList<Map<String, Object>>();
        var organizations = new ArrayList<Map<String, Object>>(); var tokenParts = new ArrayList<Object>();
        var forecastByOrganization = new LinkedHashMap<Long, BigDecimal[][]>();
        var planByOrganization = new LinkedHashMap<Long, BigDecimal[][]>();
        for (var target : targets) {
            long organizationId = ((Number) target.get("organization_id")).longValue();
            BigDecimal[][] baseline = amounts(plan.reports(), organizationId);
            planByOrganization.put(organizationId, baseline);
            BigDecimal[][] forecast = null;
            var organization = object("organizationId", Long.toString(organizationId), "organizationCode", target.get("code"), "organizationName", target.get("name"));
            if (target.get("submission_id") == null) {
                missing.add(object("organizationId", Long.toString(organizationId), "organizationCode", target.get("code"), "organizationName", target.get("name"),
                        "latestStatus", Objects.toString(target.get("latest_status"), "NOT_SUBMITTED")));
                tokenParts.add(object("organizationId", organizationId, "submissionId", null));
            } else {
                long submissionId = ((Number) target.get("submission_id")).longValue();
                var source = masters.forecastSource(submissionId, plan, metadata);
                if (!Long.toString(organizationId).equals(source.view().get("organizationId"))) throw invalidSource();
                forecast = amounts(source.reports(), organizationId);
                forecastByOrganization.put(organizationId, forecast);
                sources.add(source.view()); organization.putAll(source.view());
                tokenParts.add(object("organizationId", organizationId, "submissionId", submissionId, "fingerprints", source.fingerprints()));
            }
            organization.put("ready", forecast != null); organization.putAll(comparison(sum(forecast), sum(baseline)));
            organization.put("months", months(forecast, baseline, month)); organizations.add(organization);
        }
        boolean ready = missing.isEmpty();
        BigDecimal[][] companyPlan = combine(planByOrganization.values());
        BigDecimal[][] company = ready ? combine(forecastByOrganization.values()) : null;
        var result = object("requestId", requestId, "year", year, "asOfMonth", month, "sourceBasis", "LATEST_APPROVED_SUBMISSIONS",
                "calculationVersion", VERSION, "sourceToken", token(requestId, plan.sources(), tokenParts), "ready", ready,
                "requiredOrganizationCount", targets.size(), "approvedOrganizationCount", sources.size(), "sources", sources,
                "missingOrganizations", missing, "organizations", organizations, "months", months(company, companyPlan, month),
                "annualContingency", object("annualPlanId", plan.plan().get("id").toString(), "amount", ((BigDecimal) plan.plan().get("contingency_amount")).toPlainString(), "scope", "ANNUAL"));
        result.putAll(comparison(sum(company), sum(companyPlan)));
        return result;
    }

    /** 검증된 보고값에서 같은 조직의 12개월 매출·비용을 읽는다.
     * Args: reports 검증 보고값, organizationId 조직.
     * Returns: 월 순서의 매출·비용 배열. Raises: 누락·중복·잘못된 금액 근거.
     * Side Effects: 메모리 읽기만 수행하며 누락을 0으로 채우지 않는다.
     */
    private BigDecimal[][] amounts(List<Map<String, Object>> reports, long organizationId) {
        var result = new BigDecimal[12][2];
        for (var report : reports) {
            if (!Long.toString(organizationId).equals(report.get("organizationId"))) continue;
            int column = switch (Objects.toString(report.get("reportItemCode"), "")) { case "REVENUE_TOTAL" -> 0; case "COST_TOTAL" -> 1; default -> -1; };
            if (column < 0) continue;
            int month = ((Number) report.get("month")).intValue();
            if (month < 1 || month > 12 || result[month - 1][column] != null) throw invalidSource();
            try { result[month - 1][column] = new BigDecimal(report.get("amount").toString()); }
            catch (RuntimeException exception) { throw invalidSource(); }
        }
        for (var month : result) if (month[0] == null || month[1] == null) throw invalidSource();
        return result;
    }

    /** 모든 필수 조직의 확인된 금액만 월별로 합산한다.
     * Args: organizations 완전한 조직별 12개월 배열. Returns: 월별 전사 배열.
     * Raises: 없음. Side Effects: 새 메모리 배열만 구성한다.
     */
    private BigDecimal[][] combine(Collection<BigDecimal[][]> organizations) {
        var result = new BigDecimal[12][2];
        for (var month : result) Arrays.fill(month, BigDecimal.ZERO);
        for (var organization : organizations) for (int month = 0; month < 12; month++) for (int column = 0; column < 2; column++) result[month][column] = result[month][column].add(organization[month][column]);
        return result;
    }

    /** 완전한 12개월 금액을 연간 합산한다.
     * Args: months 월 배열 또는 미완전 null. Returns: 연간 매출·비용 또는 null.
     * Raises: 없음. Side Effects: 메모리 계산만 수행한다.
     */
    private BigDecimal[] sum(BigDecimal[][] months) {
        if (months == null) return null;
        BigDecimal[] result = {BigDecimal.ZERO, BigDecimal.ZERO};
        for (var month : months) for (int column = 0; column < 2; column++) result[column] = result[column].add(month[column]);
        return result;
    }

    /** 요청 마감월을 경계로 실제·추정의 월별 비교를 구성한다.
     * Args: forecast 완전한 전망/null, baseline 확정 계획, asOfMonth 마감월.
     * Returns: 12개월 시나리오·완전성·비교 목록. Raises: 없음. Side Effects: 메모리 계산.
     */
    private List<Map<String, Object>> months(BigDecimal[][] forecast, BigDecimal[][] baseline, int asOfMonth) {
        var result = new ArrayList<Map<String, Object>>();
        for (int month = 1; month <= 12; month++) {
            var item = object("month", month, "scenario", month <= asOfMonth ? "ACTUAL" : "ESTIMATE", "ready", forecast != null);
            item.putAll(comparison(forecast == null ? null : forecast[month - 1], baseline[month - 1])); result.add(item);
        }
        return result;
    }

    /** 실제·추정 전망과 확정 계획의 정확한 금액 차이를 제공한다.
     * Args: forecast 매출·비용/null, baseline 계획 매출·비용.
     * Returns: totals·planTotals·comparisons. 미완전 전망은 합계와 차이 모두 null.
     * Raises: 없음. Side Effects: 메모리 계산만 수행하며 Contingency를 차감하지 않는다.
     */
    private Map<String, Object> comparison(BigDecimal[] forecast, BigDecimal[] baseline) {
        var planTotals = totals(baseline);
        if (forecast == null) return object("totals", null, "planTotals", planTotals, "comparisons", null);
        var totals = totals(forecast); var gaps = new LinkedHashMap<String, Object>();
        for (String field : List.of("revenue", "cost", "operatingProfit")) {
            BigDecimal current = new BigDecimal(totals.get(field).toString()), previous = new BigDecimal(planTotals.get(field).toString()), gap = current.subtract(previous);
            gaps.put(field, object("baselineAmount", previous.toPlainString(), "currentAmount", current.toPlainString(), "gapAmount", gap.toPlainString(), "gapRate", percent(gap, previous.abs())));
        }
        return object("totals", totals, "planTotals", planTotals, "comparisons", gaps);
    }

    /** 정확한 손익과 표시용 영업이익률을 구성한다.
     * Args: values 매출·비용. Returns: 십진 문자열 금액·비율 DTO.
     * Raises: 없음. Side Effects: 메모리 계산만 수행한다.
     */
    private Map<String, Object> totals(BigDecimal[] values) {
        BigDecimal profit = values[0].subtract(values[1]);
        return object("revenue", values[0].toPlainString(), "cost", values[1].toPlainString(), "operatingProfit", profit.toPlainString(), "operatingMargin", percent(profit, values[0]));
    }

    /** 표시용 비율을 계산하며 분모 0은 미정으로 유지한다.
     * Args: numerator 분자, denominator 분모. Returns: 2자리 HALF_UP 문자열 또는 null.
     * Raises: 없음. Side Effects: 없음; 소명 임계값 판정에 사용하지 않는다.
     */
    private String percent(BigDecimal numerator, BigDecimal denominator) { return denominator.signum() == 0 ? null : numerator.multiply(BigDecimal.valueOf(100)).divide(denominator, 2, RoundingMode.HALF_UP).toPlainString(); }

    /** 선택 승인본과 검증된 월별 지문을 확정 계획 참조와 함께 결합한다.
     * Args: requestId 요청, planSources 계획 참조, sources 선택 승인본 지문.
     * Returns: 내용 변경을 식별하는 SHA-256 토큰. Raises: JSON 직렬화 오류.
     * Side Effects: 메모리 계산만 수행하며 조회 결과를 저장하지 않는다.
     */
    private String token(String requestId, Map<Long, Long> planSources, List<Object> sources) {
        try { return hash(mapper.writeValueAsBytes(object("version", VERSION, "requestId", requestId, "planSources", planSources, "sources", sources))); }
        catch (Exception exception) { throw new IllegalStateException("전망 출처 토큰을 만들 수 없습니다.", exception); }
    }

    /** 매개변수를 바인딩한 읽기 쿼리를 실행한다.
     * Args: sql 조회문, parameters 값. Returns: 조회 행 목록. Raises: DB 오류. Side Effects: DB 읽기만 수행한다.
     */
    private List<Map<String, Object>> rows(String sql, Object... parameters) { return jdbc.sql(sql).params(parameters).query().listOfRows(); }

    /** 잘못되거나 누락된 전망 근거를 차단 오류로 만든다.
     * Args: 없음. Returns: 409 오류. Raises: 없음. Side Effects: 없음.
     */
    private ApiException invalidSource() { return new ApiException(HttpStatus.CONFLICT, "MASTER_SOURCE_INVALID", "승인본의 연간 전망 근거가 완전하지 않거나 적재값과 일치하지 않습니다."); }
}
