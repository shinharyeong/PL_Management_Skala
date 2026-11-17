package io.skala.backend.review;

import static io.skala.backend.submission.ApiData.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;

/** 고정 비교본의 보고 항목과 원천 다중집합을 비교하며 검증 중에만 검토 항목을 구성한다. */
@Service
public class ReviewComparisonService {
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    /** DB·JSON 의존성을 받는다. Args: jdbc DB, mapper JSON. Returns: 없음. Raises: 없음. Side Effects: 없음. */
    public ReviewComparisonService(JdbcClient jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }

    /** 현재 초안에서 사용해야 하는 직전본·확정 계획·시나리오별 기준을 고정할 문맥으로 읽는다.
     * Args: submissionId 초안 ID. Returns: 문자열 ID 또는 null인 비교 문맥.
     * Raises: DB 오류. Side Effects: DB 읽기만 수행한다.
     */
    public Map<String, Object> context(long submissionId) {
        var row = submission(submissionId);
        var previous = rows("SELECT id FROM submission WHERE request_id=? AND organization_id=? AND id<>? AND status<>'DRAFT' ORDER BY seq DESC LIMIT 1", row.get("request_id"), row.get("organization_id"), submissionId);
        boolean monthly = "MONTHLY_ACTUAL".equals(row.get("type"));
        if (monthly && previous.isEmpty() && number(row.get("month")) > 1) previous = rows("""
                SELECT s.id FROM submission s JOIN submission_request r ON r.id=s.request_id
                WHERE s.organization_id=? AND r.annual_plan_id=? AND r.type='MONTHLY_ACTUAL' AND r.month=?
                  AND s.status='APPROVED' ORDER BY s.seq DESC LIMIT 1
                """, row.get("organization_id"), row.get("annual_plan_id"), number(row.get("month")) - 1);
        var plan = monthly ? rows("""
                SELECT t.confirmed_plan_submission_id AS id FROM request_target t JOIN submission_request r ON r.id=t.request_id
                JOIN annual_plan p ON p.id=r.annual_plan_id WHERE r.annual_plan_id=? AND r.type='ANNUAL_PLAN'
                AND t.organization_id=? AND t.excluded=FALSE AND p.status='CONFIRMED' AND t.confirmed_plan_submission_id IS NOT NULL
                """, row.get("annual_plan_id"), row.get("organization_id")) : List.<Map<String,Object>>of();
        return object("previousSubmissionId", previous.isEmpty() ? null : previous.getFirst().get("id").toString(),
                "planSubmissionId", plan.isEmpty() ? null : plan.getFirst().get("id").toString(),
                "actualThresholdId", monthly ? latestThresholdId("ACTUAL") : null,
                "estimateThresholdId", monthly && number(row.get("month")) < 12 ? latestThresholdId("ESTIMATE") : null,
                "reviewPolicyVersion", monthly ? "monthly-actual/2.0" : "annual-plan/2.0");
    }

    /** 초안의 저장된 비교 기준이 현재 직전본·확정계획·기준과 같은지 확인한다.
     * Args: submissionId 초안. Returns: 문맥 일치 여부. Raises: DB 오류. Side Effects: 읽기만 수행한다.
     */
    public boolean currentContext(long submissionId) {
        var found=rows("SELECT o.payload FROM submission_validation v JOIN stored_object o ON o.object_key=v.validation_result_object_key WHERE v.submission_id=?",submissionId);
        try { return !found.isEmpty() && context(submissionId).equals(mapper.convertValue(mapper.readTree((byte[])found.getFirst().get("payload")).path("comparisonContext"),Map.class)); }
        catch(Exception exception) { return false; }
    }

    /** 검증된 현재 적재값과 비교본으로 수정·Gap 항목을 구성하고 같은 문맥의 답변만 유지한다.
     * Args: submissionId 잠긴 초안, context 고정할 문맥, complete 원본 검증 완전성.
     * Returns: 누락된 필수 비교 근거 진단. Raises: DB·JSON 오류.
     * Side Effects: 현재 초안의 검토 항목·비교·소명 회차 생성/갱신; 과거 제출·원본·금액은 변경하지 않는다.
     */
    public List<Map<String,Object>> prepare(long submissionId, Map<String,Object> context, boolean complete) {
        jdbc.sql("UPDATE review_item SET active=FALSE WHERE submission_id=?").param(submissionId).update();
        var diagnostics = new ArrayList<Map<String,Object>>();
        if (!complete) return diagnostics;
        var row = submission(submissionId); var current = cells(submissionId);
        var cellCache = new HashMap<Long,Map<String,Cell>>(); cellCache.put(submissionId,current);
        var completeCache = new HashMap<Long,Boolean>();
        Long previousId = nullableId(context.get("previousSubmissionId")), planId = nullableId(context.get("planSubmissionId"));
        var previous = previousId == null ? Map.<String,Cell>of() : cellCache.computeIfAbsent(previousId,this::cells);
        boolean monthly = "MONTHLY_ACTUAL".equals(row.get("type"));
        if (previousId != null && !completeCache.computeIfAbsent(previousId,this::complete)) diagnostics.add(diagnostic("BASELINE_MISSING", "직전 제출본의 완전한 검증 근거가 없습니다."));
        if (monthly && (planId == null || !completeCache.computeIfAbsent(planId,this::complete))) diagnostics.add(diagnostic("BASELINE_MISSING", "조직별 확정 계획본이 필요합니다."));
        if (monthly && number(row.get("month")) > 1 && previousId == null) diagnostics.add(diagnostic("BASELINE_MISSING", "직전 월의 승인된 제출본이 필요합니다."));
        if (monthly && (context.get("actualThresholdId") == null || (number(row.get("month")) < 12 && context.get("estimateThresholdId") == null))) diagnostics.add(diagnostic("GAP_THRESHOLD_MISSING", "실적·추정의 유효 Gap 기준을 설정해 주세요."));
        var all = new LinkedHashMap<String,Cell>(previous); all.putAll(current);
        for (var entry : all.entrySet()) {
            Cell candidate = entry.getValue();
            if (!expected(row, candidate)) continue;
            Cell now = current.get(entry.getKey()), before = previous.get(entry.getKey());
            if (now == null && before != null && !"DERIVED".equals(candidate.kind())) {
                diagnostics.add(diagnostic("REQUIRED_REPORT_ITEM_REMOVED", "직전 제출에 있던 보고 항목의 원천이 통째로 누락되었습니다. 선택 제거 근거가 없어 제출할 수 없습니다."));
                continue;
            }
            boolean hasScenarioBaseline = previous.values().stream().anyMatch(value -> value.year() == candidate.year() && value.month() == candidate.month() && value.scenario().equals(candidate.scenario()));
            boolean change = !"DERIVED".equals(candidate.kind()) && hasScenarioBaseline && (before == null || now == null || !now.fingerprint().equals(before.fingerprint()));
            var comparisons = new ArrayList<Map<String,Object>>();
            if (monthly && now != null && diagnostics.isEmpty()) comparisons.addAll(comparisons(row, now, context, null, diagnostics, cellCache, completeCache));
            boolean gap = comparisons.stream().anyMatch(value -> Boolean.TRUE.equals(value.get("exceeded")));
            Map<String,Object> inherited = now == null || previousId == null ? null : inherited(previousId, now);
            if (!change && !gap && inherited == null) continue;
            save(submissionId, now == null ? candidate : now, now != null, change, gap, context, comparisons, inherited);
        }
        return diagnostics;
    }

    /** 정책별 고정 비교를 재구성한다. 새 정책은 실적/계획·추정/추정·추정/계획을 구분하고 과거 정책은 유지한다.
     * Args: row 제출 문맥, current 보고 항목 칸, context 고정 비교본, override 미리보기 기준 또는 null, diagnostics 누락 진단 수집.
     * Returns: 확인된 PLAN/PREVIOUS 비교. Raises: DB 오류. Side Effects: DB 읽기와 메모리 진단 추가만 수행한다.
     */
    public List<Map<String,Object>> comparisons(Map<String,Object> row, Cell current, Map<String,Object> context,
            Map<String,Object> override, List<Map<String,Object>> diagnostics) {
        return comparisons(row,current,context,override,diagnostics,new HashMap<>(),new HashMap<>());
    }

    /** 한 검증에서 같은 기준본을 반복 평가하지 않도록 읽기 캐시를 전달한다.
     * Args: row 제출, current 보고 항목, context 문맥, override 후보, diagnostics 진단, cellCache 원천 캐시, completeCache 완전성 캐시.
     * Returns: 비교 목록. Raises: DB 오류. Side Effects: 요청 범위 메모리 캐시와 진단만 변경한다.
     */
    public List<Map<String,Object>> comparisons(Map<String,Object> row, Cell current, Map<String,Object> context,
            Map<String,Object> override, List<Map<String,Object>> diagnostics, Map<Long,Map<String,Cell>> cellCache, Map<Long,Boolean> completeCache) {
        var result = new ArrayList<Map<String,Object>>();
        Long previousId = nullableId(context.get("previousSubmissionId")), planId = nullableId(context.get("planSubmissionId"));
        int closing = (int) number(row.get("month"));
        boolean currentPolicy = "monthly-actual/2.0".equals(context.get("reviewPolicyVersion"));
        if (currentPolicy) {
            addComparison(result, "PLAN", planId, "PLAN", current, context, override, diagnostics, cellCache, completeCache);
            if ("ESTIMATE".equals(current.scenario()) && previousId != null)
                addComparison(result, "PREVIOUS", previousId, "ESTIMATE", current, context, override, diagnostics, cellCache, completeCache);
        } else {
            if ("ACTUAL".equals(current.scenario()) && current.month() == closing) addComparison(result, "PLAN", planId, "PLAN", current, context, override, diagnostics, cellCache, completeCache);
            if ("ESTIMATE".equals(current.scenario())) addComparison(result, "PREVIOUS", previousId == null && closing == 1 ? planId : previousId,
                    previousId == null && closing == 1 ? "PLAN" : "ESTIMATE", current, context, override, diagnostics, cellCache, completeCache);
            else if (current.month() < closing || (previousId != null && submission(previousId).get("request_id").equals(row.get("request_id"))))
                addComparison(result, "PREVIOUS", previousId, "ACTUAL", current, context, override, diagnostics, cellCache, completeCache);
        }
        return result;
    }

    /** 보고 항목 비교 기준과 임계값을 읽고 반올림하지 않은 교차곱으로 초과 여부를 계산한다.
     * Args: result 비교 목록, basis 비교종류, baselineId 기준본, scenario 기준시나리오, current 현재 항목, context 고정문맥, override 후보기준, diagnostics 진단.
     * Returns: 없음. Raises: DB 오류. Side Effects: 읽기 및 메모리 목록 추가.
     */
    private void addComparison(List<Map<String,Object>> result, String basis, Long baselineId, String scenario, Cell current,
            Map<String,Object> context, Map<String,Object> override, List<Map<String,Object>> diagnostics, Map<Long,Map<String,Cell>> cellCache, Map<Long,Boolean> completeCache) {
        Cell baseline = baselineId == null ? null : cellCache.computeIfAbsent(baselineId,this::cells).get(key(current.reportId(), current.year(), current.month(), scenario));
        if (baseline == null || !completeCache.computeIfAbsent(baselineId,this::complete)) { diagnostics.add(diagnostic("BASELINE_MISSING", "필수 보고 항목 비교값을 확인할 수 없습니다.")); return; }
        String thresholdKey = "ACTUAL".equals(current.scenario()) ? "actualThresholdId" : "estimateThresholdId";
        var threshold = override != null ? override : context.get(thresholdKey) == null ? null : one("SELECT * FROM gap_threshold WHERE id=?", nullableId(context.get(thresholdKey)));
        if (threshold == null) { diagnostics.add(diagnostic("GAP_THRESHOLD_MISSING", "해당 시나리오의 Gap 기준이 없습니다.")); return; }
        BigDecimal delta = current.amount().subtract(baseline.amount());
        boolean enabledPolicy = "monthly-actual/2.0".equals(context.get("reviewPolicyVersion")) || override != null;
        boolean exceeded = enabledPolicy
                ? exceeds(baseline.amount(), current.amount(), decimal(threshold.get("percent")), decimal(threshold.get("amount")), !Boolean.FALSE.equals(threshold.get("percent_enabled")), !Boolean.FALSE.equals(threshold.get("amount_enabled")))
                : exceeds(baseline.amount(), current.amount(), decimal(threshold.get("percent")), decimal(threshold.get("amount")));
        result.add(object("basis", basis, "baselineSubmissionId", baselineId.toString(), "baselineScenario", scenario,
                "baselineAmount", baseline.amount().toPlainString(), "currentAmount", current.amount().toPlainString(),
                "gapAmount", delta.toPlainString(), "gapRate", baseline.amount().signum() == 0 ? null : delta.multiply(new BigDecimal("100")).divide(baseline.amount().abs(), 8, RoundingMode.HALF_UP).toPlainString(),
                "thresholdId", threshold.get("id") == null ? null : threshold.get("id").toString(), "exceeded", exceeded));
    }

    /** 과거 정책의 비율·금액 엄격 초과와 기준 0 특례를 보존한다.
     * Args: baseline 기준, current 현재, percent 비율기준, amount 금액기준. Returns: 초과 여부.
     * Raises: 없음. Side Effects: 정확한 십진 계산만 수행한다.
     */
    public boolean exceeds(BigDecimal baseline, BigDecimal current, BigDecimal percent, BigDecimal amount) {
        BigDecimal delta = current.subtract(baseline).abs();
        return baseline.signum() == 0 ? current.signum() != 0 : delta.compareTo(amount) > 0 && delta.multiply(new BigDecimal("100")).compareTo(baseline.abs().multiply(percent)) > 0;
    }

    /** 새 PRD에서 활성화한 조건만 평가하고 둘 다 켰으면 정확한 AND로 판정한다.
     * Args: baseline 기준, current 현재, percent 비율, amount 최소금액, percentEnabled/amountEnabled 적용 여부.
     * Returns: 변동이 있고 켜진 모든 조건을 초과하는지. Raises: IllegalArgumentException 두 조건 모두 꺼짐.
     * Side Effects: 메모리 계산만 수행한다. 기준 0의 비율은 미산정이지만 변동 여부를 확인하고 켜진 금액 조건도 반드시 적용한다.
     */
    public boolean exceeds(BigDecimal baseline,BigDecimal current,BigDecimal percent,BigDecimal amount,boolean percentEnabled,boolean amountEnabled) {
        if(!percentEnabled&&!amountEnabled) throw new IllegalArgumentException("Gap 조건이 하나 이상 필요합니다.");
        BigDecimal delta=current.subtract(baseline).abs();
        boolean percentPassed=!percentEnabled||(baseline.signum()==0?delta.signum()>0:delta.multiply(new BigDecimal("100")).compareTo(baseline.abs().multiply(percent))>0);
        return delta.signum()>0&&percentPassed&&(!amountEnabled||delta.compareTo(amount)>0);
    }

    /** 원천행을 의미 다중집합으로 묶고 OP의 매출·비용 근거를 함께 보존한다.
     * Args: submissionId 제출본. Returns: 보고항목·기간·시나리오별 칸.
     * Raises: DB·JSON 오류. Side Effects: DB 읽기만 수행하며 행을 짝짓거나 값을 보정하지 않는다.
     */
    public Map<String,Cell> cells(long submissionId) {
        var facts = rows("""
                SELECT v.id AS value_id,v.line_id,v.year,v.month,v.scenario,v.amount,v.source_cell,
                l.report_item_id,l.detail_id,l.team_code,l.task_code,l.management_category,l.actual_basis,l.cost_type,l.description,l.source_sheet,
                r.code,r.kind,a.source_code,a.source_name FROM financial_value v JOIN financial_line l ON l.id=v.line_id
                JOIN report_item r ON r.id=l.report_item_id LEFT JOIN account_item a ON a.id=l.account_item_id
                WHERE l.submission_id=? ORDER BY v.id
                """, submissionId);
        if (facts.isEmpty()) return Map.of();
        var groups = new LinkedHashMap<String,List<Map<String,Object>>>(); var metadata = new LinkedHashMap<String,Map<String,Object>>();
        long opId = one("SELECT id FROM report_item WHERE code='OP'").get("id") instanceof Number number ? number.longValue() : 0;
        for (var fact : facts) {
            String key = key(number(fact.get("report_item_id")), (int) number(fact.get("year")), (int) number(fact.get("month")), fact.get("scenario").toString());
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(fact); metadata.putIfAbsent(key, fact);
            String op = key(opId, (int) number(fact.get("year")), (int) number(fact.get("month")), fact.get("scenario").toString());
            groups.computeIfAbsent(op, ignored -> new ArrayList<>()).add(fact);
            metadata.putIfAbsent(op, object("report_item_id",opId,"year",fact.get("year"),"month",fact.get("month"),"scenario",fact.get("scenario"),"code","OP","kind","DERIVED"));
        }
        var result = new LinkedHashMap<String,Cell>();
        for (var entry : groups.entrySet()) {
            var meta = metadata.get(entry.getKey()); boolean op = "OP".equals(meta.get("code")); BigDecimal total = BigDecimal.ZERO;
            var signatures = new ArrayList<String>();
            for (var fact : entry.getValue()) {
                BigDecimal amount = decimal(fact.get("amount")); total = total.add(op && "COST".equals(fact.get("kind")) ? amount.negate() : amount);
                signatures.add(json(object("attributes", attributes(fact), "amount", amount.stripTrailingZeros().toPlainString())));
            }
            Collections.sort(signatures);
            result.put(entry.getKey(), new Cell(number(meta.get("report_item_id")), meta.get("code").toString(), meta.get("kind").toString(),
                    (int) number(meta.get("year")), (int) number(meta.get("month")), meta.get("scenario").toString(), total, hash(json(signatures).getBytes(StandardCharsets.UTF_8)), entry.getValue()));
        }
        return result;
    }

    /** 위치·마감 안내·수정차수를 제외하고 확인된 거래 의미를 정규화한다.
     * Args: fact 적재 원천행. Returns: 식별·분류·팀·과제·거래 의미. Raises: 없음. Side Effects: 없음.
     */
    public Map<String,Object> attributes(Map<String,Object> fact) {
        String description = normalizedDescription(Objects.toString(fact.get("description"), ""));
        return object("detailId",fact.get("detail_id"),"kind",fact.get("kind"),"accountCode",fact.get("source_code"),"accountName",fact.get("source_name"),
                "teamCode",fact.get("team_code"),"taskCode",fact.get("task_code"),"managementCategory",fact.get("management_category"),
                "actualBasis",fact.get("actual_basis"),"costType",fact.get("cost_type"),"description",description);
    }

    /** 마감 안내와 상세 수정 메타데이터를 거래 설명에서 분리한다.
     * Args: description 원본 설명. Returns: 비교할 거래 의미. Raises: 없음. Side Effects: 없음.
     */
    public static String normalizedDescription(String description) {
        return Objects.toString(description, "").replaceFirst("^\\s*\\[\\d{4}년\\s+\\d{1,2}월\\s+마감:\\s*[^\\]]*실적[^\\]]*추정\\]\\s*", "").replaceAll("\\[ID:[A-Za-z0-9_-]+;\\s*수정:\\d+\\]", "").replaceAll("\\s+", " ").strip();
    }

    /** 기존 검토 칸을 같은 문맥이면 유지하고 문맥 변경 시 새 답변 회차로 연다.
     * Args: submissionId 초안, cell 현재/제거 칸, present 현재존재, change 수정, gap 초과, context 고정문맥, comparisons 비교, inherited 수용소명.
     * Returns: 없음. Raises: DB 오류. Side Effects: 검토·비교·소명 행 저장; 기존 회차는 삭제하지 않는다.
     */
    private void save(long submissionId, Cell cell, boolean present, boolean change, boolean gap, Map<String,Object> context,
            List<Map<String,Object>> comparisons, Map<String,Object> inherited) {
        String fingerprint = hash(json(object("source",present ? cell.fingerprint() : "ABSENT", "context",context,"comparisons",comparisons,"change",change,"gap",gap)).getBytes(StandardCharsets.UTF_8));
        var found = rows("SELECT * FROM review_item WHERE submission_id=? AND report_item_id=? AND year=? AND month=? AND scenario=?",submissionId,cell.reportId(),cell.year(),cell.month(),cell.scenario());
        long reviewId; int round = 1; boolean createRound = true;
        if (found.isEmpty()) reviewId = insert("""
                INSERT INTO review_item(submission_id,report_item_id,year,month,scenario,amount_present,current_amount,change_detected,gap_exceeded,source_fingerprint,context_fingerprint,current_round)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,1)
                """,submissionId,cell.reportId(),cell.year(),cell.month(),cell.scenario(),present,present ? cell.amount() : null,change,gap,cell.fingerprint(),fingerprint);
        else {
            var old = found.getFirst(); reviewId = number(old.get("id")); round = (int) number(old.get("current_round"));
            createRound = !fingerprint.equals(old.get("context_fingerprint"));
            if (createRound) {
                jdbc.sql("UPDATE review_explanation SET verdict='REJECTED',review_note='검증 문맥 변경으로 이전 답변을 다시 확인합니다.' WHERE review_item_id=? AND round=? AND verdict='PENDING'").params(reviewId,round).update();
                round++;
            }
            jdbc.sql("UPDATE review_item SET amount_present=?,current_amount=?,change_detected=?,gap_exceeded=?,source_fingerprint=?,context_fingerprint=?,current_round=?,active=TRUE WHERE id=?")
                    .params(present,present ? cell.amount() : null,change,gap,cell.fingerprint(),fingerprint,round,reviewId).update();
        }
        jdbc.sql("UPDATE review_item SET review_policy_version=? WHERE id=?").params(context.get("reviewPolicyVersion"),reviewId).update();
        if (createRound) jdbc.sql("UPDATE review_item SET workflow_state='NEEDS_REVIEW',workflow_note=NULL WHERE id=?").param(reviewId).update();
        jdbc.sql("DELETE FROM gap_comparison WHERE review_item_id=?").param(reviewId).update();
        for (var comparison : comparisons) jdbc.sql("""
                INSERT INTO gap_comparison(review_item_id,basis,baseline_submission_id,baseline_scenario,baseline_amount,current_amount,gap_amount,gap_rate,threshold_id,exceeded)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """).params(reviewId,comparison.get("basis"),nullableId(comparison.get("baselineSubmissionId")),comparison.get("baselineScenario"),decimal(comparison.get("baselineAmount")),decimal(comparison.get("currentAmount")),decimal(comparison.get("gapAmount")),comparison.get("gapRate") == null ? null : decimal(comparison.get("gapRate")),nullableId(comparison.get("thresholdId")),comparison.get("exceeded")).update();
        if (createRound) {
            if (inherited == null) jdbc.sql("INSERT INTO review_explanation(review_item_id,round,verdict) VALUES (?,?,'PENDING')").params(reviewId,round).update();
            else jdbc.sql("""
                    INSERT INTO review_explanation(review_item_id,round,answer,answered_by,answered_at,verdict,reviewed_by,reviewed_at,review_note,inherited_from_id)
                    VALUES (?,?,?,?,?,'ACCEPTED',?,?,?,?)
                    """).params(reviewId,round,inherited.get("answer"),inherited.get("answered_by"),inherited.get("answered_at"),inherited.get("reviewed_by"),inherited.get("reviewed_at"),inherited.get("review_note"),inherited.get("id")).update();
        }
    }

    /** 같은 시나리오·원천 구성의 직전 수용 소명만 찾는다.
     * Args: previousId 직전본, cell 현재 칸. Returns: 수용 회차/null. Raises: DB 오류. Side Effects: DB 읽기.
     */
    private Map<String,Object> inherited(long previousId, Cell cell) {
        var found = rows("""
                SELECT e.* FROM review_item i JOIN review_explanation e ON e.review_item_id=i.id AND e.round=i.current_round
                WHERE i.submission_id=? AND i.report_item_id=? AND i.year=? AND i.month=? AND i.scenario=?
                  AND i.active=TRUE AND i.source_fingerprint=? AND e.verdict='ACCEPTED' AND e.answer IS NOT NULL
                """,previousId,cell.reportId(),cell.year(),cell.month(),cell.scenario(),cell.fingerprint());
        return found.isEmpty() ? null : found.getFirst();
    }

    /** 이전 ESTIMATE가 새 ACTUAL로 전환된 칸을 삭제 수정으로 만들지 않는다.
     * Args: row 요청 문맥, cell 비교 칸. Returns: 현재 전체 스냅샷에 속하는지. Raises: 없음. Side Effects: 없음.
     */
    private boolean expected(Map<String,Object> row, Cell cell) {
        String scenario = "ANNUAL_PLAN".equals(row.get("type")) ? "PLAN" : cell.month() <= number(row.get("month")) ? "ACTUAL" : "ESTIMATE";
        return cell.year() == number(row.get("year")) && scenario.equals(cell.scenario());
    }

    /** 제출·요청·연도 문맥을 읽는다. Args: id 제출. Returns: DB행. Raises: DB 오류. Side Effects: DB 읽기. */
    public Map<String,Object> submission(long id) { return one("SELECT s.*,r.type,r.month,r.annual_plan_id,p.year FROM submission s JOIN submission_request r ON r.id=s.request_id JOIN annual_plan p ON p.id=r.annual_plan_id WHERE s.id=?",id); }
    /** 고정 파서·검증 근거와 실제 적재값이 완전하고 동일한지 확인한다.
     * Args: id 비교 기준 제출. Returns: 현재 파싱 참조·정책·원천 다중집합·보고값까지 일치할 때 true.
     * Raises: DB 조회 오류. Side Effects: DB 읽기와 보존 JSON의 메모리 대조만 수행하며 원본 파일·금액·소명을 변경하지 않는다.
     */
    public boolean complete(long id) {
        var found = rows("""
                SELECT s.*,r.type,r.month,p.year,o.code AS organization_code,j.status AS parsing_status,j.attempt_no,j.result_object_key,
                    v.attempt_no AS validated_attempt,v.source_sha256 AS validated_hash,v.parser_result_object_key,v.policy_version,
                    b.payload AS validation_payload,parsed.payload AS parser_payload
                FROM submission s JOIN submission_request r ON r.id=s.request_id JOIN annual_plan p ON p.id=r.annual_plan_id
                JOIN organization o ON o.id=s.organization_id JOIN parsing_job j ON j.submission_id=s.id
                JOIN submission_validation v ON v.submission_id=s.id JOIN stored_object b ON b.object_key=v.validation_result_object_key
                JOIN stored_object parsed ON parsed.object_key=v.parser_result_object_key WHERE s.id=?
                """, id);
        if (found.isEmpty()) return false;
        var row = found.getFirst();
        try {
            String policy = "ANNUAL_PLAN".equals(row.get("type")) ? "annual-plan/1.0" : "monthly-actual/1.0";
            JsonNode snapshot = mapper.readTree((byte[]) row.get("validation_payload")), parsed = mapper.readTree((byte[]) row.get("parser_payload"));
            if (snapshot == null || !snapshot.isObject() || parsed == null || !"SUCCEEDED".equals(row.get("parsing_status"))
                    || !Objects.equals(row.get("attempt_no"), row.get("validated_attempt")) || !Objects.equals(row.get("source_sha256"), row.get("validated_hash"))
                    || !Objects.equals(row.get("result_object_key"), row.get("parser_result_object_key")) || !policy.equals(row.get("policy_version"))
                    || !policy.equals(snapshot.path("policyVersion").asText()) || !snapshot.path("complete").asBoolean() || !snapshot.path("summary").path("complete").asBoolean()
                    || !snapshot.path("diagnostics").isArray() || !snapshot.path("reportValues").isArray() || snapshot.path("reportValues").isEmpty()
                    || !Objects.equals(row.get("previous_submission_id") == null ? null : row.get("previous_submission_id").toString(), snapshot.path("previousSubmissionId").isNull() || snapshot.path("previousSubmissionId").isMissingNode() ? null : snapshot.path("previousSubmissionId").asText())) return false;
            for (JsonNode diagnostic : snapshot.path("diagnostics")) if ("ERROR".equals(diagnostic.path("severity").asText())) return false;
            var evaluated = new io.skala.backend.submission.FinancialValidationRules(mapper).evaluate(parsed.path("submissionData"), row.get("source_sha256").toString(), row.get("organization_code").toString(),
                    (int) number(row.get("year")), row.get("type").toString(), row.get("month") == null ? null : (int) number(row.get("month")));
            if (!Objects.equals(evaluated.get("fingerprint"), snapshot.path("fingerprint").asText())) return false;
            JsonNode expected = mapper.valueToTree(evaluated);
            for (JsonNode diagnostic : expected.path("diagnostics")) if ("ERROR".equals(diagnostic.path("severity").asText())) return false;
            return intactFacts(id, expected, snapshot) && intactReports(row, expected, snapshot);
        } catch (Exception exception) { return false; }
    }

    /** 보존 파서에서 검증된 원천 각 셀과 DB의 정확 다중집합을 대조한다.
     * Args: submissionId 기준 제출, expected 동일 정책 평가 결과, snapshot 고정 검증 JSON. Returns: 건수·식별·계정·분류·원본좌표·금액이 모두 같은지.
     * Raises: DB·JSON·숫자 오류. Side Effects: DB 읽기·메모리 정렬만 수행하며 같은 합계의 상쇄 변경도 허용하지 않는다.
     */
    private boolean intactFacts(long submissionId, JsonNode expected, JsonNode snapshot) {
        var reportCodes = new HashMap<String, String>(); for (JsonNode report : expected.path("reports")) reportCodes.put(report.path("key").asText(), report.path("code").asText());
        var wanted = new ArrayList<String>();
        for (JsonNode line : expected.path("lines")) {
            boolean account = line.path("account").hasNonNull("code") && !line.path("account").path("code").asText().isBlank();
            var fields = object("report_code", reportCodes.get(line.path("reportItemKey").asText()), "kind", line.path("domain").asText(),
                    "source_type", account ? "REVENUE".equals(line.path("domain").asText()) ? "REVENUE_CLASS" : "EXPENSE_BUDGET" : null,
                    "source_code", account ? line.path("account").path("code").asText() : null,
                    "source_name", account ? line.path("account").path("name").asText("") : null,
                    "source_sheet", line.path("source").path("sheet").asText(), "source_row", line.path("source").path("row").asInt());
            String[][] attributes = {{"detail_id","detailId"},{"detail_revision","detailRevision"},{"headquarters_code","headquartersCode"},{"team_code","teamCode"},{"task_code","taskCode"},
                    {"management_category","managementCategory"},{"actual_basis","actualBasis"},{"cost_type","costType"},{"description","description"}};
            for (var attribute : attributes) { JsonNode value = line.path(attribute[1]); fields.put(attribute[0], value.isMissingNode() || value.isNull() ? null : value.asText()); }
            for (JsonNode value : line.path("values")) {
                var fact = new LinkedHashMap<>(fields); fact.put("year", value.path("year").asInt()); fact.put("month", value.path("month").asInt()); fact.put("scenario", value.path("scenario").asText());
                fact.put("amount", value.path("amount").asText()); fact.put("source_record_id", value.path("sourceRecordId").asText()); fact.put("source_cell", value.path("sourceCell").asText()); wanted.add(integritySignature(fact));
            }
        }
        var actual = rows("""
                SELECT l.*,r.code AS report_code,r.kind,a.source_type,a.source_code,a.source_name,
                    v.year,v.month,v.scenario,v.amount,v.source_record_id,v.source_cell
                FROM financial_line l JOIN financial_value v ON v.line_id=l.id JOIN report_item r ON r.id=l.report_item_id
                LEFT JOIN account_item a ON a.id=l.account_item_id WHERE l.submission_id=?
                """, submissionId);
        long lineCount = jdbc.sql("SELECT COUNT(*) FROM financial_line WHERE submission_id=?").param(submissionId).query(Long.class).single();
        if (wanted.isEmpty() || lineCount != expected.path("lines").size() || lineCount != snapshot.path("summary").path("lineCount").asLong(-1)
                || actual.size() != wanted.size() || actual.size() != snapshot.path("summary").path("valueCount").asInt(-1)) return false;
        var saved = new ArrayList<String>(); for (var fact : actual) saved.add(integritySignature(fact));
        Collections.sort(wanted); Collections.sort(saved); return wanted.equals(saved);
    }

    /** 원천 적재 필드와 정확 금액을 위치 포함 고정 형식으로 직렬화한다.
     * Args: fact 원천 셀의 비교 필드. Returns: 정밀도 표현만 정규화한 JSON. Raises: 숫자·JSON 오류. Side Effects: 없음.
     */
    private String integritySignature(Map<String, Object> fact) {
        var fields = new LinkedHashMap<String, Object>();
        for (String field : List.of("report_code","kind","source_type","source_code","source_name","detail_id","detail_revision","headquarters_code","team_code","task_code",
                "management_category","actual_basis","cost_type","description","source_sheet","source_row","year","month","scenario","source_record_id","source_cell")) fields.put(field, fact.get(field));
        fields.put("amount", decimal(fact.get("amount")).stripTrailingZeros().toPlainString()); return json(fields);
    }

    /** 고정 보고값의 조직·항목·기간·합계를 보존 원천의 평가 결과와 대조한다.
     * Args: row 기준 제출 문맥, expected 원천 평가, snapshot 고정 검증. Returns: 모든 보고값이 일치하는지.
     * Raises: DB·숫자 오류. Side Effects: 보고 메타 읽기 및 메모리 비교만 수행한다.
     */
    private boolean intactReports(Map<String, Object> row, JsonNode expected, JsonNode snapshot) {
        var reportCodes = new HashMap<String, String>(); for (JsonNode report : expected.path("reports")) reportCodes.put(report.path("key").asText(), report.path("code").asText());
        var metadata = new HashMap<String, Map<String, Object>>(); for (var report : rows("SELECT id,code,kind FROM report_item")) metadata.put(report.get("id").toString(), report);
        var wanted = new TreeMap<String, BigDecimal>();
        for (JsonNode value : expected.path("reportValues")) wanted.put(reportCodes.get(value.path("reportKey").asText()) + ':' + value.path("year").asText() + ':' + value.path("month").asText() + ':' + value.path("scenario").asText(), new BigDecimal(value.path("amount").asText()));
        var seen = new HashSet<String>();
        for (JsonNode value : snapshot.path("reportValues")) {
            var report = metadata.get(value.path("reportItemId").asText());
            if (report == null || !"AVAILABLE".equals(value.path("valueStatus").asText()) || !row.get("organization_id").toString().equals(value.path("organizationId").asText()) || !report.get("kind").equals(value.path("kind").asText())) return false;
            String key = report.get("code") + ":" + value.path("year").asText() + ':' + value.path("month").asText() + ':' + value.path("scenario").asText();
            if (!seen.add(key) || !wanted.containsKey(key) || wanted.get(key).compareTo(new BigDecimal(value.path("amount").asText())) != 0) return false;
        }
        return !wanted.isEmpty() && seen.equals(wanted.keySet());
    }
    /** 최신 기준 ID를 읽는다. Args: scenario 시나리오. Returns: 문자열/null. Raises: DB 오류. Side Effects: 읽기. */
    private String latestThresholdId(String scenario) { var found = rows("SELECT id FROM gap_threshold WHERE scenario=? ORDER BY id DESC LIMIT 1",scenario); return found.isEmpty() ? null : found.getFirst().get("id").toString(); }
    /** 칸의 불변 조회키를 만든다. Args: report 보고항목, year 연도, month 월, scenario 구분. Returns: 키. Raises: 없음. Side Effects: 없음. */
    public String key(long report,int year,int month,String scenario) { return report+":"+year+":"+month+":"+scenario; }
    /** 안전한 차단 진단을 만든다. Args: code 코드, message 설명. Returns: 진단. Raises: 없음. Side Effects: 없음. */
    private Map<String,Object> diagnostic(String code,String message) { return object("code",code,"severity","ERROR","stage","COMPARISON","message",message,"source",null); }
    /** JSON을 정규화한다. Args: value 메모리값. Returns: JSON. Raises: IllegalStateException 직렬화실패. Side Effects: 없음. */
    public String json(Object value) { try { return mapper.writeValueAsString(value); } catch(Exception e) { throw new IllegalStateException("검토 근거를 직렬화하지 못했습니다."); } }
    /** 정수를 읽는다. Args: value JDBC 정수. Returns: long. Raises: 타입오류. Side Effects: 없음. */
    private long number(Object value) { return ((Number)value).longValue(); }
    /** nullable ID를 읽는다. Args: value ID. Returns: Long/null. Raises: 입력오류. Side Effects: 없음. */
    public Long nullableId(Object value) { return value == null ? null : id(value.toString()); }
    /** 십진 값을 손실 없이 읽는다. Args: value 문자열/JDBC십진. Returns: Decimal. Raises: 숫자오류. Side Effects: 없음. */
    public BigDecimal decimal(Object value) { return value instanceof BigDecimal decimal ? decimal : new BigDecimal(value.toString()); }
    /** 조회한다. Args: sql 고정SQL, params 바인딩. Returns: 행목록. Raises: DB오류. Side Effects: 읽기. */
    public List<Map<String,Object>> rows(String sql,Object...params) { return jdbc.sql(sql).params(params).query().listOfRows(); }
    /** 필수행을 조회한다. Args: sql 고정SQL, params 바인딩. Returns: 행. Raises: DB오류. Side Effects: 읽기. */
    private Map<String,Object> one(String sql,Object...params) { return jdbc.sql(sql).params(params).query().singleRow(); }
    /** 행을 생성한다. Args: sql 고정SQL, params 바인딩. Returns: 생성ID. Raises: DB오류. Side Effects: 현재트랜잭션 INSERT. */
    private long insert(String sql,Object...params) { var holder=new GeneratedKeyHolder(); jdbc.sql(sql).params(params).update(holder,"id"); return holder.getKey().longValue(); }
    /** 원천 금액 칸이며 자동 접근자는 외부 부작용이 없다. */
    public record Cell(long reportId,String code,String kind,int year,int month,String scenario,BigDecimal amount,String fingerprint,List<Map<String,Object>> facts) {}
}
