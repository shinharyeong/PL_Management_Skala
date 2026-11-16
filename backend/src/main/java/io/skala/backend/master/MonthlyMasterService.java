package io.skala.backend.master;

import static io.skala.backend.submission.ApiData.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.skala.backend.common.ApiException;
import io.skala.backend.security.Actor;
import io.skala.backend.review.ReviewComparisonService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 승인된 월간 전체 제출의 ACTUAL만 고정하여 월 마스터와 확정 계획 비교를 제공한다. */
@Service
public class MonthlyMasterService {
    private static final String VERSION = "monthly-master/1.0";
    private static final String POLICY = "monthly-actual/1.0";
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final AnnualPlanService plans;

    /** 의존성을 연결한다. Args: jdbc DB, mapper JSON, plans 확정 계획. Returns: 없음. Raises: 없음. Side Effects: 없음. */
    public MonthlyMasterService(JdbcClient jdbc, ObjectMapper mapper, AnnualPlanService plans) { this.jdbc = jdbc; this.mapper = mapper; this.plans = plans; }

    /** 승인과 동일 트랜잭션에서 해당 승인본의 과거·현재 ACTUAL 월을 순서대로 발행한다.
     * Args: actor 기획자, submissionId 방금 승인한 최신 완료 제출. Returns: 요청이 있는 영향 월의 효과 목록.
     * Raises: 권한·상태·고정 근거·적재 무결성 오류. Side Effects: 연간 계획 잠금·새 월 버전/출처 INSERT, 전체 실패 시 승인까지 롤백.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Map<String, Object>> publishAfterApproval(Actor actor, String submissionId) {
        actor.requireRole("PLANNER"); if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("승인 트랜잭션이 필요합니다.");
        var trigger = submission(id(submissionId));
        String status = jdbc.sql("SELECT status FROM annual_plan WHERE id=? FOR UPDATE").param(trigger.get("annual_plan_id")).query(String.class).single();
        if (!"CONFIRMED".equals(status)) throw conflict("ANNUAL_PLAN_NOT_CONFIRMED", "연간 계획 확정 후 월 실적을 승인할 수 있습니다.");
        var plan = plans.confirmed(integer(trigger.get("year"))); var metadata = metadata();
        var ownTargets = targets(trigger.get("request_id"));
        if (ownTargets.stream().noneMatch(row -> Objects.equals(row.get("organization_id"), trigger.get("organization_id"))
                && Objects.equals(row.get("latest_id"), trigger.get("id")) && "APPROVED".equals(row.get("latest_status")))) {
            throw conflict("MASTER_SOURCE_NOT_APPROVED", "필수 조직의 최신 완료 승인본만 월 마스터에 반영할 수 있습니다.");
        }
        var cache = new HashMap<Long, Source>(); var approved = source(id(submissionId), plan, metadata); cache.put(id(submissionId), approved);
        var effects = new ArrayList<Map<String, Object>>();
        for (var request : rows("SELECT r.*,p.year FROM submission_request r JOIN annual_plan p ON p.id=r.annual_plan_id WHERE r.annual_plan_id=? AND r.type='MONTHLY_ACTUAL' AND r.month<=? ORDER BY r.month",
                trigger.get("annual_plan_id"), trigger.get("month"))) {
            effects.add(publish(actor, approved, request, plan, metadata, cache));
        }
        return effects;
    }

    /** 연도의 12개월 발행 상태와 이미 발행된 최신 실제 금액을 읽는다.
     * Args: actor 기획자, query year 필수. Returns: year·annualContingency·items.
     * Raises: 권한·입력·근거 오류. Side Effects: REPEATABLE_READ 조회만 수행한다.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> periods(Actor actor, Map<String, String> query) {
        actor.requireRole("PLANNER"); query(query, Set.of("year")); int year = AnnualPlanService.year(query.get("year"));
        var requests = rows("SELECT r.*,p.year FROM submission_request r JOIN annual_plan p ON p.id=r.annual_plan_id WHERE p.year=? AND r.type='MONTHLY_ACTUAL'", year);
        var byMonth = new HashMap<Integer, Map<String, Object>>(); for (var request : requests) byMonth.put(integer(request.get("month")), request);
        var planRows = rows("SELECT * FROM annual_plan WHERE year=?", year); Object contingency = null;
        if (!planRows.isEmpty() && "CONFIRMED".equals(planRows.getFirst().get("status"))) contingency = object("annualPlanId", planRows.getFirst().get("id").toString(), "amount", ((BigDecimal) planRows.getFirst().get("contingency_amount")).toPlainString(), "scope", "ANNUAL");
        var items = new ArrayList<Map<String, Object>>(); AnnualPlanService.ConfirmedPlan plan = null; Map<String, Map<String, Object>> metadata = null;
        for (int month = 1; month <= 12; month++) {
            var request = byMonth.get(month);
            if (request == null) { items.add(object("month", month, "requestId", null, "publicationState", "NOT_REQUESTED", "requiredOrganizationCount", 0, "approvedOrganizationCount", 0,
                    "latestMasterId", null, "latestVersionLabel", null, "totals", null, "missingOrganizations", List.of())); continue; }
            var targets = targets(request.get("id")); var master = latest(request.get("id")); Object totals = null;
            if (master != null) {
                if (plan == null) { plan = plans.confirmed(year); metadata = metadata(); }
                totals = detail(master, request, plan, metadata).get("totals");
            }
            items.add(object("month", month, "requestId", request.get("id").toString(), "publicationState", master == null ? "WAITING_INITIAL" : "PUBLISHED",
                    "requiredOrganizationCount", targets.size(), "approvedOrganizationCount", approvedCount(targets), "latestMasterId", master == null ? null : master.get("id").toString(),
                    "latestVersionLabel", master == null ? null : label(request, master), "totals", totals, "missingOrganizations", missing(targets)));
        }
        return object("year", year, "annualContingency", contingency, "items", items);
    }

    /** 지정 월의 버전 이력을 최신순으로 읽는다.
     * Args: actor 기획자, query year·month 필수 및 cursor·limit. Returns: 마스터 페이지.
     * Raises: 권한·입력·커서 오류. Side Effects: DB 읽기, 미발행은 빈 페이지.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> list(Actor actor, Map<String, String> query) {
        actor.requireRole("PLANNER"); query(query, Set.of("year", "month", "limit", "cursor")); int year = AnnualPlanService.year(query.get("year")), month = range(query.get("month"), 1, 12);
        String scope = "masters:" + actor.id() + ':' + year + ':' + month; int start = offset(query.get("cursor"), scope), limit = limit(query);
        var found = rows("SELECT m.*,p.year,r.month FROM master_version m JOIN submission_request r ON r.id=m.request_id JOIN annual_plan p ON p.id=r.annual_plan_id WHERE p.year=? AND r.month=? ORDER BY m.seq DESC,m.id DESC LIMIT ? OFFSET ?", year, month, limit + 1, start);
        return object("items", found.stream().limit(limit).map(row -> summary(row, row)).toList(), "nextCursor", found.size() > limit ? cursor(start + limit, scope) : null);
    }

    /** 고정 월 마스터의 전사·조직별 실적과 확정 계획을 비교한다.
     * Args: actor 기획자, masterId 버전, query 빈 쿼리. Returns: 고정 출처·금액·계획·Gap.
     * Raises: 권한·입력·404·근거 오류. Side Effects: 반복 가능한 읽기만 수행한다.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> detail(Actor actor, String masterId, Map<String, String> query) {
        actor.requireRole("PLANNER"); query(query, Set.of()); var master = one("SELECT * FROM master_version WHERE id=?", id(masterId)); var request = request(master.get("request_id"));
        return detail(master, request, plans.confirmed(integer(request.get("year"))), metadata());
    }

    /** 고정 월의 실제 보고값만 전사 또는 지정 조직으로 집계한다.
     * Args: actor 기획자, masterId 버전, query organizationId·reportItemId·limit·cursor. Returns: ACTUAL 보고값 페이지.
     * Raises: 권한·입력·404·근거 오류. Side Effects: DB 읽기. ESTIMATE·다른 연월은 받지 않는다.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> reportValues(Actor actor, String masterId, Map<String, String> query) {
        actor.requireRole("PLANNER"); query(query, Set.of("organizationId", "reportItemId", "limit", "cursor"));
        Long org = query.containsKey("organizationId") ? id(query.get("organizationId")) : null; Long report = query.containsKey("reportItemId") ? id(query.get("reportItemId")) : null;
        var master = one("SELECT * FROM master_version WHERE id=?", id(masterId)); var request = request(master.get("request_id")); var plan = plans.confirmed(integer(request.get("year")));
        var sources = sources(master, request, plan, metadata(), new HashMap<>());
        if (org != null && sources.stream().noneMatch(source -> source.organizationId() == org)) throw invalid("마스터에 포함된 조직만 조회할 수 있습니다.");
        var values = aggregate(sources, integer(request.get("month")), org).stream().filter(value -> report == null || report.toString().equals(value.get("reportItemId"))).toList();
        String scope = "master-values:" + actor.id() + ':' + masterId + ':' + org + ':' + report; int start = offset(query.get("cursor"), scope), end = Math.min(values.size(), start + limit(query));
        return object("items", values.subList(Math.min(start, values.size()), end), "nextCursor", end < values.size() ? cursor(end, scope) : null);
    }

    /** 한 영향 월의 최초 전원 승인 또는 기존 조합의 단일 조직 교체를 반영한다.
     * Args: actor 기획자, trigger 승인 출처, request 영향 월, plan 확정 계획, metadata 보고 메타, cache 호출 범위 출처 캐시.
     * Returns: 월 효과. Raises: 대상·근거 불일치. Side Effects: 변경이 있을 때 새 버전과 전체 출처 INSERT.
     */
    private Map<String, Object> publish(Actor actor, Source trigger, Map<String, Object> request, AnnualPlanService.ConfirmedPlan plan,
            Map<String, Map<String, Object>> metadata, Map<Long, Source> cache) {
        var targets = targets(request.get("id")); var previous = latest(request.get("id")); var selected = new TreeMap<Long, Source>();
        var required = new TreeSet<Long>(); for (var row : targets) required.add(number(row.get("organization_id")));
        if (!required.equals(plan.sources().keySet())) throw conflict("MASTER_TARGET_MISMATCH", "월 요청과 확정 연간 계획의 필수 조직 집합이 다릅니다.");
        if (previous == null) {
            if (targets.isEmpty() || approvedCount(targets) != targets.size()) return effect(request, targets, null, "WAITING_INITIAL");
            for (var target : targets) selected.put(number(target.get("organization_id")), cached(number(target.get("latest_id")), plan, metadata, cache));
        } else {
            for (var source : sources(previous, request, plan, metadata, cache)) selected.put(source.organizationId(), source);
            if (!selected.keySet().equals(required)) throw conflict("MASTER_TARGET_MISMATCH", "마스터와 고정 월 제출 대상이 다릅니다.");
            if (!selected.containsKey(trigger.organizationId())) throw conflict("MASTER_TARGET_MISMATCH", "새 승인 조직이 기존 월 마스터에 없습니다.");
            selected.put(trigger.organizationId(), trigger);
        }
        String hash = contentHash(request, new ArrayList<>(selected.values()));
        if (previous != null && hash.equals(previous.get("content_sha256"))) return effect(request, targets, previous, "UNCHANGED");
        var holder = new GeneratedKeyHolder(); jdbc.sql("INSERT INTO master_version(request_id,seq,previous_master_id,trigger_submission_id,content_sha256,calculation_version,created_by,created_at) VALUES (?,?,?,?,?,?,?,?)")
                .params(request.get("id"), previous == null ? 1 : number(previous.get("seq")) + 1, previous == null ? null : previous.get("id"), trigger.submissionId(), hash, VERSION, id(actor.id()), OffsetDateTime.now(ZoneOffset.UTC)).update(holder, "id");
        long masterId = holder.getKey().longValue();
        for (var source : selected.values()) jdbc.sql("INSERT INTO master_source(master_id,organization_id,submission_id) VALUES (?,?,?)").params(masterId, source.organizationId(), source.submissionId()).update();
        return effect(request, targets, one("SELECT * FROM master_version WHERE id=?", masterId), previous == null ? "CREATED" : "UPDATED");
    }

    /** 고정 출처 조합과 저장된 월 의미 지문의 동일성을 확인한다.
     * Args: master 버전, request 월 요청, plan 확정 계획, metadata 보고 메타, cache 출처 캐시. Returns: 검증된 출처.
     * Raises: 고정 출처 누락·변경·근거 오류. Side Effects: DB 읽기 및 호출 범위 캐시만 갱신.
     */
    private List<Source> sources(Map<String, Object> master, Map<String, Object> request, AnnualPlanService.ConfirmedPlan plan,
            Map<String, Map<String, Object>> metadata, Map<Long, Source> cache) {
        var result = new ArrayList<Source>();
        for (var row : rows("SELECT * FROM master_source WHERE master_id=? ORDER BY organization_id", master.get("id"))) {
            var source = cached(number(row.get("submission_id")), plan, metadata, cache);
            if (source.organizationId() != number(row.get("organization_id"))) throw invalidSource(); result.add(source);
        }
        if (result.isEmpty() || !VERSION.equals(master.get("calculation_version")) || !contentHash(request, result).equals(master.get("content_sha256"))) throw invalidSource(); return result;
    }

    /** 같은 요청 내에서 한 제출의 검증을 재사용한다.
     * Args: id 제출, plan 확정 계획, metadata 보고 메타, cache 호출 캐시. Returns: 검증된 출처.
     * Raises: 근거 오류. Side Effects: 최초에 DB 읽기·메모리 캐시 생성.
     */
    private Source cached(long id, AnnualPlanService.ConfirmedPlan plan, Map<String, Map<String, Object>> metadata, Map<Long, Source> cache) {
        return cache.computeIfAbsent(id, key -> source(key, plan, metadata));
    }

    /** 월간 제출의 고정 검증 근거와 모든 ACTUAL 상세·보고 그리드의 완전성을 대조한다.
     * Args: submissionId 승인 제출, plan 확정 계획, metadata 보고 항목. Returns: 월별 의미 지문·확인된 실제 보고값.
     * Raises: 근거·시도·원본·계획·이전본·월·금액·건수 불일치. Side Effects: DB 읽기만 수행하며 0으로 누락을 보완하지 않는다.
     */
    private Source source(long submissionId, AnnualPlanService.ConfirmedPlan plan, Map<String, Map<String, Object>> metadata) {
        var row = submission(submissionId); int requestMonth = integer(row.get("month")); long org = number(row.get("organization_id"));
        var saved = rows("SELECT v.*,o.payload FROM submission_validation v JOIN stored_object o ON o.object_key=v.validation_result_object_key WHERE v.submission_id=?", submissionId);
        if (saved.isEmpty()) throw invalidSource(); var validation = saved.getFirst(); JsonNode snapshot;
        try { snapshot = mapper.readTree((byte[]) validation.get("payload")); } catch (Exception exception) { throw invalidSource(); }
        if (snapshot == null || !snapshot.isObject() || !"APPROVED".equals(row.get("status")) || !"SUCCEEDED".equals(row.get("parsing_status"))
                || !row.get("annual_plan_id").equals(plan.plan().get("id")) || !plan.sources().containsKey(org) || !POLICY.equals(validation.get("policy_version")) || !POLICY.equals(snapshot.path("policyVersion").asText())
                || !Objects.equals(row.get("attempt_no"), validation.get("attempt_no")) || !Objects.equals(row.get("source_sha256"), validation.get("source_sha256"))
                || !Objects.equals(row.get("result_object_key"), validation.get("parser_result_object_key"))
                || !Objects.equals(textId(row.get("previous_submission_id")), nullable(snapshot.path("previousSubmissionId")))
                || !Objects.equals(textId(plan.sources().get(org)), nullable(snapshot.path("comparisonContext").path("planSubmissionId")))
                || !snapshot.path("complete").asBoolean() || !snapshot.path("summary").path("complete").asBoolean()
                || !snapshot.path("diagnostics").isArray() || !snapshot.path("reportValues").isArray() || snapshot.path("reportValues").isEmpty()
                || !snapshot.path("fingerprint").asText().matches("[a-f0-9]{64}")) throw invalidSource();
        for (JsonNode diagnostic : snapshot.path("diagnostics")) if ("ERROR".equals(diagnostic.path("severity").asText())) throw invalidSource();
        var facts = rows("""
                SELECT l.*,a.source_type,a.source_code,a.source_name,r.code AS report_code,r.kind,v.year,v.month,v.scenario,v.amount
                FROM financial_line l JOIN financial_value v ON v.line_id=l.id JOIN report_item r ON r.id=l.report_item_id
                LEFT JOIN account_item a ON a.id=l.account_item_id WHERE l.submission_id=?
                """, submissionId);
        long lineCount = jdbc.sql("SELECT COUNT(*) FROM financial_line WHERE submission_id=?").param(submissionId).query(Long.class).single();
        if (lineCount == 0 || facts.isEmpty() || lineCount != snapshot.path("summary").path("lineCount").asLong(-1) || facts.size() != snapshot.path("summary").path("valueCount").asInt(-1)) throw invalidSource();
        var sums = new HashMap<String, BigDecimal>(); var lineMonths = new HashMap<Long, Set<Integer>>(); var signatures = new TreeMap<Integer, List<String>>(); var factReports = new HashSet<String>();
        for (var fact : facts) {
            int month = integer(fact.get("month")); String scenario = fact.get("scenario").toString();
            if (!fact.get("year").equals(row.get("year")) || !("REVENUE".equals(fact.get("kind")) || "COST".equals(fact.get("kind")))
                    || !("ACTUAL".equals(scenario) && month <= requestMonth || "ESTIMATE".equals(scenario) && month > requestMonth)) throw invalidSource();
            if (!"ACTUAL".equals(scenario)) continue;
            if (!lineMonths.computeIfAbsent(number(fact.get("id")), key -> new HashSet<>()).add(month)) throw invalidSource();
            BigDecimal amount = (BigDecimal) fact.get("amount"); String reportId = fact.get("report_item_id").toString(); factReports.add(reportId);
            sums.merge(reportId + ':' + month, amount, BigDecimal::add); sums.merge(fact.get("kind") + ":" + month, amount, BigDecimal::add);
            signatures.computeIfAbsent(month, key -> new ArrayList<>()).add(factFingerprint(fact));
        }
        if (lineMonths.size() != lineCount || lineMonths.values().stream().anyMatch(months -> months.size() != requestMonth)) throw invalidSource();
        var reports = new ArrayList<Map<String, Object>>(); var keys = new HashSet<String>(); var reportIds = new HashSet<String>(); var totals = new HashSet<String>();
        for (JsonNode value : snapshot.path("reportValues")) {
            if (!"ACTUAL".equals(value.path("scenario").asText())) continue;
            String reportId = value.path("reportItemId").asText(); int month = value.path("month").asInt(); var report = metadata.get(reportId);
            if (report == null || !report.get("kind").equals(value.path("kind").asText()) || value.path("year").asInt() != integer(row.get("year"))
                    || month < 1 || month > requestMonth || !"AVAILABLE".equals(value.path("valueStatus").asText()) || !Long.toString(org).equals(value.path("organizationId").asText()) || !keys.add(reportId + ':' + month)) throw invalidSource();
            String code = report.get("code").toString(); BigDecimal expected = sums.getOrDefault(reportId + ':' + month, BigDecimal.ZERO);
            if ("DERIVED".equals(report.get("kind"))) {
                BigDecimal revenue = sums.getOrDefault("REVENUE:" + month, BigDecimal.ZERO), cost = sums.getOrDefault("COST:" + month, BigDecimal.ZERO);
                expected = switch (code) { case "REVENUE_TOTAL" -> revenue; case "COST_TOTAL" -> cost; case "OP" -> revenue.subtract(cost); default -> throw invalidSource(); }; totals.add(code);
            }
            try { if (new BigDecimal(value.path("amount").asText()).compareTo(expected) != 0) throw invalidSource(); } catch (NumberFormatException exception) { throw invalidSource(); }
            reportIds.add(reportId); reports.add(object("organizationId", Long.toString(org), "reportItemId", reportId, "reportItemCode", code, "reportItemName", value.path("reportItemName").asText(),
                    "kind", report.get("kind"), "year", row.get("year"), "month", month, "scenario", "ACTUAL", "valueStatus", "AVAILABLE", "amount", expected.toPlainString()));
        }
        if (keys.size() != reportIds.size() * requestMonth || !reportIds.containsAll(factReports) || !totals.equals(Set.of("REVENUE_TOTAL", "COST_TOTAL", "OP"))) throw invalidSource();
        var fingerprints = new TreeMap<Integer, String>();
        for (var entry : signatures.entrySet()) { Collections.sort(entry.getValue()); fingerprints.put(entry.getKey(), hash(json(entry.getValue()).getBytes(StandardCharsets.UTF_8))); }
        return new Source(org, submissionId, fingerprints, reports, object("organizationId", Long.toString(org), "organizationCode", row.get("organization_code"), "organizationName", row.get("organization_name"),
                "submissionId", Long.toString(submissionId), "versionLabel", String.format(Locale.ROOT, "%04d-%02d-%02d", integer(row.get("year")), requestMonth, integer(row.get("seq"))), "planSubmissionId", textId(plan.sources().get(org))));
    }

    /** 원본 위치·회차와 무관한 한 실제 금액의 의미 구성을 직렬화한다.
     * Args: fact 적재 행. Returns: 정규 의미 JSON. Raises: 직렬화 오류. Side Effects: 없음.
     */
    private String factFingerprint(Map<String, Object> fact) {
        String description = ReviewComparisonService.normalizedDescription(Objects.toString(fact.get("description"), ""));
        return json(object("report", fact.get("report_code"), "amount", ((BigDecimal) fact.get("amount")).stripTrailingZeros().toPlainString(), "detailId", fact.get("detail_id"),
                "accountType", fact.get("source_type"), "accountCode", fact.get("source_code"), "accountName", fact.get("source_name"), "team", fact.get("team_code"), "task", fact.get("task_code"),
                "description", description, "managementCategory", fact.get("management_category"), "actualBasis", fact.get("actual_basis"), "costType", fact.get("cost_type")));
    }

    /** 해당 월 조직별 의미 지문으로 마스터 조합 지문을 만든다.
     * Args: request 월 요청, sources 출처. Returns: SHA-256. Raises: 월 전체 스냅샷 부재. Side Effects: 없음.
     */
    private String contentHash(Map<String, Object> request, List<Source> sources) {
        int month = integer(request.get("month")); var result = new StringBuilder(VERSION).append(':').append(request.get("id"));
        sources.stream().sorted(Comparator.comparingLong(Source::organizationId)).forEach(source -> {
            String fingerprint = source.fingerprints().get(month); if (fingerprint == null) throw invalidSource(); result.append('|').append(source.organizationId()).append(':').append(fingerprint);
        }); return hash(result.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** 상세의 전사·조직 실적과 같은 조직의 확정 계획을 비교한다.
     * Args: master 버전, request 월 요청, plan 확정 계획, metadata 보고 항목. Returns: 상세 DTO.
     * Raises: 근거·확정 계획 대상 불일치. Side Effects: 고정 출처 DB 읽기와 메모리 합산.
     */
    private Map<String, Object> detail(Map<String, Object> master, Map<String, Object> request, AnnualPlanService.ConfirmedPlan plan, Map<String, Map<String, Object>> metadata) {
        var sources = sources(master, request, plan, metadata, new HashMap<>()); int month = integer(request.get("month"));
        var actual = new LinkedHashMap<Long, BigDecimal[]>(); var planned = new LinkedHashMap<Long, BigDecimal[]>(); BigDecimal[] company = {BigDecimal.ZERO, BigDecimal.ZERO}, companyPlan = {BigDecimal.ZERO, BigDecimal.ZERO};
        for (var source : sources) {
            var values = money(source.reports(), month, source.organizationId()); var baseline = money(plan.reports(), month, source.organizationId());
            actual.put(source.organizationId(), values); planned.put(source.organizationId(), baseline);
            for (int column = 0; column < 2; column++) { company[column] = company[column].add(values[column]); companyPlan[column] = companyPlan[column].add(baseline[column]); }
        }
        var result = summary(request, master); result.put("calculationVersion", master.get("calculation_version")); result.put("scenario", "ACTUAL");
        result.put("sources", sources.stream().map(Source::view).toList()); result.putAll(comparison(company, companyPlan, null, null));
        var organizations = new ArrayList<Map<String, Object>>();
        for (var source : sources) { var item = new LinkedHashMap<>(source.view()); item.putAll(comparison(actual.get(source.organizationId()), planned.get(source.organizationId()), company, companyPlan)); organizations.add(item); }
        result.put("organizations", organizations); result.put("annualContingency", object("annualPlanId", plan.plan().get("id").toString(), "amount", ((BigDecimal) plan.plan().get("contingency_amount")).toPlainString(), "scope", "ANNUAL")); return result;
    }

    /** 검증된 파생 매출·비용을 고정 조직·월에서 읽는다.
     * Args: reports 검증 보고값, month 월, org 조직. Returns: 매출·비용. Raises: 누락·중복 근거. Side Effects: 메모리 읽기.
     */
    private BigDecimal[] money(List<Map<String, Object>> reports, int month, long org) {
        BigDecimal[] result = new BigDecimal[2];
        for (var report : reports) if (integer(report.get("month")) == month && Long.toString(org).equals(report.get("organizationId"))) {
            int column = switch (report.get("reportItemCode").toString()) { case "REVENUE_TOTAL" -> 0; case "COST_TOTAL" -> 1; default -> -1; };
            if (column >= 0) { if (result[column] != null) throw invalidSource(); result[column] = new BigDecimal(report.get("amount").toString()); }
        }
        if (result[0] == null || result[1] == null) throw conflict("PLAN_SOURCE_MISSING", "동일 조직·월의 완전한 계획 또는 실적이 없습니다."); return result;
    }

    /** 실적·계획·부호 있는 Gap을 만든다.
     * Args: actual 실적, plan 계획, company 실제 전사 또는 null, companyPlan 계획 전사 또는 null. Returns: totals·planTotals·comparisons.
     * Raises: 없음. Side Effects: 없음. Contingency는 차감하지 않는다.
     */
    private Map<String, Object> comparison(BigDecimal[] actual, BigDecimal[] plan, BigDecimal[] company, BigDecimal[] companyPlan) {
        var totals = totals(actual, company); var baseline = totals(plan, companyPlan); var comparisons = new LinkedHashMap<String, Object>();
        for (String field : List.of("revenue", "cost", "operatingProfit")) {
            BigDecimal current = new BigDecimal(totals.get(field).toString()), previous = new BigDecimal(baseline.get(field).toString()), gap = current.subtract(previous);
            comparisons.put(field, object("baselineAmount", previous.toPlainString(), "currentAmount", current.toPlainString(), "gapAmount", gap.toPlainString(), "gapRate", percent(gap, previous.abs())));
        }
        return object("totals", totals, "planTotals", baseline, "comparisons", comparisons);
    }

    /** 정확한 손익과 이익률·선택적 기여율을 만든다.
     * Args: amounts 매출·비용, company 전사 비교 금액 또는 null. Returns: 금액·비율 DTO. Raises: 없음. Side Effects: 없음.
     */
    private Map<String, Object> totals(BigDecimal[] amounts, BigDecimal[] company) {
        BigDecimal profit = amounts[0].subtract(amounts[1]); var result = object("revenue", amounts[0].toPlainString(), "cost", amounts[1].toPlainString(), "operatingProfit", profit.toPlainString(), "operatingMargin", percent(profit, amounts[0]));
        if (company != null) result.put("operatingProfitShare", percent(profit, company[0].subtract(company[1]))); return result;
    }

    /** 부호 있는 백분율을 계산한다. Args: numerator 분자, denominator 분모. Returns: 2자리 HALF_UP 또는 분모0이면null. Raises: 없음. Side Effects: 없음. */
    private String percent(BigDecimal numerator, BigDecimal denominator) { return denominator.signum() == 0 ? null : numerator.multiply(BigDecimal.valueOf(100)).divide(denominator, 2, RoundingMode.HALF_UP).toPlainString(); }

    /** 고정 ACTUAL 보고값을 같은 그리드로 집계한다.
     * Args: sources 출처, month 대상 월, org null이면전사. Returns: 보고 코드 순 값. Raises: 근거 중복 오류. Side Effects: 메모리 계산.
     */
    private List<Map<String, Object>> aggregate(List<Source> sources, int month, Long org) {
        var grid = new TreeMap<String, Map<String, Object>>(); var sums = new HashMap<String, BigDecimal>();
        for (var source : sources) for (var report : source.reports()) if (integer(report.get("month")) == month) {
            String key = report.get("reportItemCode").toString(); var old = grid.putIfAbsent(key, report);
            if (old != null && !old.get("reportItemId").equals(report.get("reportItemId"))) throw invalidSource();
            if (org == null || source.organizationId() == org) sums.merge(key, new BigDecimal(report.get("amount").toString()), BigDecimal::add);
        }
        var result = new ArrayList<Map<String, Object>>(); for (var entry : grid.entrySet()) { var value = new LinkedHashMap<>(entry.getValue()); value.put("organizationId", textId(org)); value.put("amount", sums.getOrDefault(entry.getKey(), BigDecimal.ZERO).toPlainString()); result.add(value); } return result;
    }

    /** 요청 고정 대상의 최신 완료 회차를 읽는다. Args: requestId 요청. Returns: 대상목록. Raises: DB 오류. Side Effects: 읽기. */
    private List<Map<String, Object>> targets(Object requestId) {
        return rows("SELECT t.organization_id,o.code,o.name,s.id AS latest_id,s.status AS latest_status FROM request_target t JOIN organization o ON o.id=t.organization_id LEFT JOIN submission s ON s.request_id=t.request_id AND s.organization_id=t.organization_id AND s.seq=(SELECT MAX(n.seq) FROM submission n WHERE n.request_id=t.request_id AND n.organization_id=t.organization_id AND n.status<>'DRAFT') WHERE t.request_id=? AND t.excluded=FALSE ORDER BY t.organization_id", requestId);
    }
    /** 미승인 대상을 만든다. Args: targets 대상. Returns: 공개 조직목록. Raises: 없음. Side Effects: 없음. */
    private List<Map<String, Object>> missing(List<Map<String, Object>> targets) { return targets.stream().filter(row -> !"APPROVED".equals(row.get("latest_status"))).map(row -> object("id", textId(row.get("organization_id")), "code", row.get("code"), "name", row.get("name"), "status", row.get("latest_status") == null ? "NOT_SUBMITTED" : row.get("latest_status"))).toList(); }
    /** 승인수를 센다. Args: targets 대상. Returns: 승인수. Raises: 없음. Side Effects: 없음. */
    private long approvedCount(List<Map<String, Object>> targets) { return targets.stream().filter(row -> "APPROVED".equals(row.get("latest_status"))).count(); }
    /** 월 효과를 만든다. Args: request 요청, targets 대상, master nullable버전, action 결과. Returns: 효과. Raises: 없음. Side Effects: 없음. */
    private Map<String, Object> effect(Map<String, Object> request, List<Map<String, Object>> targets, Map<String, Object> master, String action) { return object("kind", "MONTHLY_ACTUAL", "year", request.get("year"), "month", request.get("month"), "action", action, "masterId", master == null ? null : textId(master.get("id")), "previousMasterId", master == null ? null : textId(master.get("previous_master_id")), "versionLabel", master == null ? null : label(request, master), "approvedOrganizationCount", approvedCount(targets), "requiredOrganizationCount", targets.size()); }
    /** 마스터 헤더를 만든다. Args: request 연월 포함 행, master 버전. Returns: 요약. Raises: 없음. Side Effects: 없음. */
    private Map<String, Object> summary(Map<String, Object> request, Map<String, Object> master) { return object("id", textId(master.get("id")), "requestId", textId(master.get("request_id")), "year", request.get("year"), "month", request.get("month"), "seq", master.get("seq"), "versionLabel", label(request, master), "previousMasterId", textId(master.get("previous_master_id")), "triggerSubmissionId", textId(master.get("trigger_submission_id")), "createdAt", instant(master.get("created_at"))); }
    /** 표시 라벨을 만든다. Args: request 연월, master 회차. Returns: 라벨. Raises: 없음. Side Effects: 없음. */
    private String label(Map<String, Object> request, Map<String, Object> master) { return String.format(Locale.ROOT, "master-%04d-%02d-%02d", integer(request.get("year")), integer(request.get("month")), integer(master.get("seq"))); }
    /** 최신버전을 읽는다. Args: requestId 요청. Returns: 버전/null. Raises: DB 오류. Side Effects: 읽기. */
    private Map<String, Object> latest(Object requestId) { var result = rows("SELECT * FROM master_version WHERE request_id=? ORDER BY seq DESC LIMIT 1", requestId); return result.isEmpty() ? null : result.getFirst(); }
    /** 월간 요청을 읽는다. Args: requestId ID. Returns: 요청·연도. Raises: 404·종류오류. Side Effects: 읽기. */
    private Map<String, Object> request(Object requestId) { var request = one("SELECT r.*,p.year FROM submission_request r JOIN annual_plan p ON p.id=r.annual_plan_id WHERE r.id=?", requestId); if (!"MONTHLY_ACTUAL".equals(request.get("type"))) throw invalid("월간 실적 요청이 아닙니다."); return request; }
    /** 파싱·조직·연월을 포함한 월간 제출을 읽는다. Args: submissionId ID. Returns: 행. Raises: 404·종류오류. Side Effects: 읽기. */
    private Map<String, Object> submission(long submissionId) { var row = one("SELECT s.*,r.annual_plan_id,r.month,r.type,p.year,o.code AS organization_code,o.name AS organization_name,j.status AS parsing_status,j.attempt_no,j.result_object_key FROM submission s JOIN submission_request r ON r.id=s.request_id JOIN annual_plan p ON p.id=r.annual_plan_id JOIN organization o ON o.id=s.organization_id JOIN parsing_job j ON j.submission_id=s.id WHERE s.id=?", submissionId); if (!"MONTHLY_ACTUAL".equals(row.get("type"))) throw invalid("월간 실적 제출이 아닙니다."); return row; }
    /** 보고 메타를 한 번 읽는다. Args: 없음. Returns: ID별항목. Raises: DB 오류. Side Effects: 읽기. */
    private Map<String, Map<String, Object>> metadata() { var result = new HashMap<String, Map<String, Object>>(); for (var row : rows("SELECT id,code,kind FROM report_item")) result.put(row.get("id").toString(), row); return result; }
    /** 쿼리를 검사한다. Args: query 입력, allowed 허용키. Returns: 없음. Raises: 입력오류. Side Effects: 없음. */
    private void query(Map<String, String> query, Set<String> allowed) { if (!allowed.containsAll(query.keySet()) || query.values().stream().anyMatch(value -> value == null || value.isBlank())) throw invalid("허용되지 않거나 빈 조회 조건입니다."); }
    /** 페이지 크기를 읽는다. Args: query 입력. Returns: 1~100 기본50. Raises: 입력오류. Side Effects: 없음. */
    private int limit(Map<String, String> query) { return query.containsKey("limit") ? range(query.get("limit"), 1, 100) : 50; }
    /** 범위 정수를 검사한다. Args: value 문자열, min/max 범위. Returns: 정수. Raises: 입력오류. Side Effects: 없음. */
    private int range(String value, int min, int max) { try { if (value == null || !value.matches("[1-9][0-9]*")) throw new NumberFormatException(); int result = Integer.parseInt(value); if (result < min || result > max) throw new NumberFormatException(); return result; } catch (NumberFormatException exception) { throw invalid("정수 조회 범위가 올바르지 않습니다."); } }
    /** JDBC정수를 변환한다. Args: value 숫자. Returns: int. Raises: 타입오류. Side Effects: 없음. */
    private int integer(Object value) { return ((Number) value).intValue(); }
    /** JDBC정수를 변환한다. Args: value 숫자. Returns: long. Raises: 타입오류. Side Effects: 없음. */
    private long number(Object value) { return ((Number) value).longValue(); }
    /** nullable ID를 변환한다. Args: value ID. Returns: 문자열/null. Raises: 없음. Side Effects: 없음. */
    private String textId(Object value) { return value == null ? null : value.toString(); }
    /** nullable JSON ID를 읽는다. Args: value 노드. Returns: 문자열/null. Raises: 없음. Side Effects: 없음. */
    private String nullable(JsonNode value) { return value.isNull() || value.isMissingNode() ? null : value.asText(); }
    /** 내부 JSON을 만든다. Args: value DTO. Returns: JSON. Raises: 직렬화오류. Side Effects: 없음. */
    private String json(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception exception) { throw new IllegalStateException("월 마스터 직렬화 실패", exception); } }
    /** 필수행을 읽는다. Args: sql 조회문, params 바인딩. Returns: 행. Raises: 404·DB오류. Side Effects: 읽기. */
    private Map<String, Object> one(String sql, Object... params) { var rows = rows(sql, params); if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "요청한 자료를 찾을 수 없습니다."); return rows.getFirst(); }
    /** 행목록을 읽는다. Args: sql 조회문, params 바인딩. Returns: 행목록. Raises: DB오류. Side Effects: 읽기. */
    private List<Map<String, Object>> rows(String sql, Object... params) { return jdbc.sql(sql).params(params).query().listOfRows(); }
    /** 입력오류를 만든다. Args: message 설명. Returns:400. Raises:없음. Side Effects:없음. */
    private ApiException invalid(String message) { return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message); }
    /** 상태오류를 만든다. Args: code 코드,message 설명. Returns:409. Raises:없음. Side Effects:없음. */
    private ApiException conflict(String code, String message) { return new ApiException(HttpStatus.CONFLICT, code, message); }
    /** 근거오류를 만든다. Args:없음. Returns:409. Raises:없음. Side Effects:없음. */
    private ApiException invalidSource() { return conflict("MASTER_SOURCE_INVALID", "월 마스터의 고정 검증 근거와 실제 적재값이 일치하지 않습니다."); }
    /** 검증된 월간 출처다. 자동 접근자는 메모리 값만 반환한다. */
    private record Source(long organizationId, long submissionId, Map<Integer, String> fingerprints, List<Map<String, Object>> reports, Map<String, Object> view) {}
}
