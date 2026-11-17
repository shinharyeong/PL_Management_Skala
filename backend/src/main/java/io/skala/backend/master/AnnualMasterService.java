package io.skala.backend.master;

import static io.skala.backend.submission.ApiData.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.skala.backend.common.ApiException;
import io.skala.backend.security.Actor;
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
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 승인된 전체 제출본의 조합을 고정하여 연간 계획 마스터를 발행·조회한다. */
@Service
public class AnnualMasterService {
    private static final String CALCULATION_VERSION = "annual-master/1.0";
    private static final String POLICY_VERSION = "annual-plan/1.0";
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    /** 마스터의 DB와 불변 검증 JSON 읽기를 연결한다.
     * Args: jdbc DB 클라이언트, mapper JSON 해석기. Returns: 없음. Raises: 없음. Side Effects: 없음.
     */
    public AnnualMasterService(JdbcClient jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }

    /** 연간 확정·월간 비교가 지정한 승인 계획본의 검증된 보고값을 읽는다.
     * Args: requestId 연간 요청, selected 조직별 고정 또는 확정 후보 제출 ID. Returns: 조직·월별 PLAN 보고값.
     * Raises: ApiException 승인 근거·적재값 불일치. Side Effects: DB 읽기만 수행하며 최신 제출로 바꾸지 않는다.
     */
    List<Map<String, Object>> validatedPlanReports(String requestId, Map<Long, Long> selected) {
        var request = annualRequest(requestId); var metadata = reportMetadata(); var result = new ArrayList<Map<String, Object>>();
        for (var entry : selected.entrySet()) result.addAll(source(entry.getValue(), entry.getKey(), request, metadata).reports());
        return result;
    }

    /** 승인과 같은 트랜잭션에서 최초 전 조직 또는 이후 해당 조직의 마스터를 발행한다.
     * Args: actor 경영기획 담당자, submissionId 방금 승인한 최신 완료 제출 ID.
     * Returns: 연간 마스터 생성·갱신·동일·최초 대기 효과.
     * Raises: ApiException 권한·상태·고정 근거 오류, IllegalStateException 호출 트랜잭션 부재.
     * Side Effects: annual_plan 잠금과 마스터·출처 INSERT만 수행. 실패하면 호출자의 승인도 함께 롤백된다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String, Object> publishAfterApproval(Actor actor, String submissionId) {
        actor.requireRole("PLANNER");
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("승인 트랜잭션 안에서 마스터를 발행해야 합니다.");
        var trigger = one("SELECT * FROM submission WHERE id=?", id(submissionId));
        var request = annualRequest(trigger.get("request_id").toString());
        String planStatus = jdbc.sql("SELECT status FROM annual_plan WHERE id=? FOR UPDATE").param(request.get("annual_plan_id")).query(String.class).single();
        if (!"COLLECTING".equals(planStatus)) throw conflict("PLAN_ALREADY_CONFIRMED", "확정된 연간 계획에는 새 마스터를 발행할 수 없습니다.");
        var targets = targets(request.get("id"));
        var target = targets.stream().filter(row -> row.get("id").equals(trigger.get("organization_id"))).findFirst()
                .orElseThrow(() -> conflict("MASTER_TARGET_MISMATCH", "승인한 조직이 연간 마스터의 필수 제출 대상에 없습니다."));
        if (!"APPROVED".equals(trigger.get("status")) || !Objects.equals(target.get("submission_id"), trigger.get("id"))) {
            throw conflict("MASTER_SOURCE_NOT_APPROVED", "최신 완료 제출본의 승인만 마스터에 반영할 수 있습니다.");
        }
        var previous = latest(request.get("id"));
        if (previous == null && approvedCount(targets) != targets.size()) return effect(request, targets, null, "WAITING_INITIAL");
        var selected = new TreeMap<Long, Long>();
        if (previous == null) {
            for (var row : targets) selected.put(number(row.get("id")), number(row.get("submission_id")));
        } else {
            for (var row : rows("SELECT organization_id,submission_id FROM annual_master_source WHERE master_id=?", previous.get("id"))) {
                selected.put(number(row.get("organization_id")), number(row.get("submission_id")));
            }
            Set<Long> required = new TreeSet<>();
            for (var row : targets) required.add(number(row.get("id")));
            if (!selected.keySet().equals(required)) throw conflict("MASTER_TARGET_MISMATCH", "기존 마스터와 고정 제출 대상이 다릅니다. 대상 변경을 먼저 확인해야 합니다.");
        }
        var metadata = reportMetadata(); var selectedSources = new ArrayList<Source>();
        for (var entry : selected.entrySet()) {
            selectedSources.add(source(entry.getValue(), entry.getKey(), request, metadata));
        }
        if (previous != null) {
            if (!CALCULATION_VERSION.equals(previous.get("calculation_version"))
                    || !contentHash(request, selectedSources).equals(previous.get("content_sha256"))) throw invalidSource();
            long organizationId = number(trigger.get("organization_id"));
            if (selected.get(organizationId) != id(submissionId)) {
                selected.put(organizationId, id(submissionId));
                selectedSources.removeIf(source -> source.organizationId() == organizationId);
                selectedSources.add(source(id(submissionId), organizationId, request, metadata));
            }
        }
        String contentHash = contentHash(request, selectedSources);
        if (previous != null && contentHash.equals(previous.get("content_sha256"))) return effect(request, targets, previous, "UNCHANGED");
        var holder = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO annual_master(request_id,seq,previous_master_id,trigger_submission_id,
                    content_sha256,calculation_version,created_by,created_at) VALUES (?,?,?,?,?,?,?,?)
                """).params(request.get("id"), previous == null ? 1 : number(previous.get("seq")) + 1,
                        previous == null ? null : previous.get("id"), id(submissionId), contentHash,
                        CALCULATION_VERSION, id(actor.id()), OffsetDateTime.now(ZoneOffset.UTC)).update(holder, "id");
        long masterId = holder.getKey().longValue();
        for (var entry : selected.entrySet()) jdbc.sql("INSERT INTO annual_master_source(master_id,organization_id,submission_id) VALUES (?,?,?)")
                .params(masterId, entry.getKey(), entry.getValue()).update();
        return effect(request, targets, one("SELECT * FROM annual_master WHERE id=?", masterId), previous == null ? "CREATED" : "UPDATED");
    }

    /** 고정 필수 조직의 최신 완료 제출 상태와 최신 발행본을 읽는다.
     * Args: actor 경영기획 담당자, requestId 연간 요청, query 빈 쿼리. Returns: 발행 현황.
     * Raises: ApiException 권한·입력·404 오류. Side Effects: 일관된 읽기만 수행하며 조회로 발행하지 않는다.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> status(Actor actor, String requestId, Map<String, String> query) {
        actor.requireRole("PLANNER"); checkQuery(query, Set.of());
        var request = annualRequest(requestId); var targets = targets(request.get("id")); var latest = latest(request.get("id"));
        var missing = targets.stream().filter(row -> !"APPROVED".equals(row.get("status")))
                .map(row -> object("id", row.get("id").toString(), "code", row.get("code"), "name", row.get("name"),
                        "status", row.get("status") == null ? "NOT_SUBMITTED" : row.get("status"))).toList();
        return object("requestId", requestId, "year", request.get("year"), "scenario", "PLAN",
                "publicationState", latest == null ? "WAITING_INITIAL" : "PUBLISHED", "approvedOrganizationCount", approvedCount(targets),
                "requiredOrganizationCount", targets.size(), "missingOrganizations", missing,
                "latestMasterId", latest == null ? null : latest.get("id").toString(), "latestVersionLabel", latest == null ? null : version(request, latest));
    }

    /** 연간 요청의 발행 이력을 최신순으로 읽는다. 페이지 사이의 추가 발행은 스냅샷으로 고정하지 않는다.
     * Args: actor 경영기획 담당자, query 필수 requestId 및 limit·cursor. Returns: 이력 페이지.
     * Raises: ApiException 권한·입력·커서·404 오류. Side Effects: DB 읽기.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> list(Actor actor, Map<String, String> query) {
        actor.requireRole("PLANNER"); checkQuery(query, Set.of("requestId", "limit", "cursor"));
        var request = annualRequest(query.get("requestId"));
        String scope = "annual-masters:" + actor.id() + ":" + request.get("id"); int start = offset(query.get("cursor"), scope), limit = limit(query);
        var found = rows("SELECT * FROM annual_master WHERE request_id=? ORDER BY seq DESC,id DESC LIMIT ? OFFSET ?", request.get("id"), limit + 1, start);
        var items = found.stream().limit(limit).map(row -> summary(request, row)).toList();
        return object("items", items, "nextCursor", found.size() > limit ? cursor(start + limit, scope) : null);
    }

    /** 고정 마스터의 전사·조직별 금액과 이익률·기여율을 고정 출처에서 읽는다.
     * Args: actor 경영기획 담당자, masterId 고정 버전, query 빈 쿼리. Returns: 상세·출처·전사 및 조직별 연간·12개월 지표.
     * Raises: ApiException 권한·입력·404·근거 오류. Side Effects: DB 읽기. 최신 제출본을 끼워 넣지 않는다.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> detail(Actor actor, String masterId, Map<String, String> query) {
        actor.requireRole("PLANNER"); checkQuery(query, Set.of());
        var master = one("SELECT * FROM annual_master WHERE id=?", id(masterId)); var request = annualRequest(master.get("request_id").toString());
        var sources = sources(master, request); var result = summary(request, master);
        result.put("sources", sources.stream().map(Source::view).toList());
        result.putAll(profitSummaries(sources)); return result;
    }

    /** 고정 출처의 공통 보고 그리드를 전사 또는 한 조직으로 집계한다.
     * Args: actor 경영기획 담당자, masterId 버전, query organizationId·month·limit·cursor.
     * Returns: 보고 항목과 파생 합계를 포함한 페이지. Raises: ApiException 권한·입력·404·근거 오류.
     * Side Effects: DB 읽기. 전체 자료가 검증된 출처의 미존재 항목만 0으로 표현한다.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> reportValues(Actor actor, String masterId, Map<String, String> query) {
        actor.requireRole("PLANNER"); checkQuery(query, Set.of("organizationId", "month", "limit", "cursor"));
        Long organizationId = query.containsKey("organizationId") ? id(query.get("organizationId")) : null;
        Integer month = query.containsKey("month") ? integer(query.get("month"), 1, 12) : null;
        var master = one("SELECT * FROM annual_master WHERE id=?", id(masterId)); var request = annualRequest(master.get("request_id").toString());
        var sources = sources(master, request);
        if (organizationId != null && sources.stream().noneMatch(source -> source.organizationId() == organizationId)) throw invalid("마스터에 포함된 조직만 조회할 수 있습니다.");
        var values = aggregate(sources, organizationId).stream().filter(row -> month == null || month.equals(row.get("month"))).toList();
        String scope = "annual-master-values:" + actor.id() + ':' + masterId + ':' + organizationId + ':' + month;
        int start = offset(query.get("cursor"), scope), end = Math.min(values.size(), start + limit(query));
        return object("items", values.subList(Math.min(start, values.size()), end), "nextCursor", end < values.size() ? cursor(end, scope) : null);
    }

    /** 한 요청의 고정 대상과 각 조직의 최신 완료 제출을 읽는다.
     * Args: requestId 요청 PK. Returns: 조직 코드 순 대상 목록. Raises: DB 오류. Side Effects: DB 읽기.
     */
    private List<Map<String, Object>> targets(Object requestId) {
        return rows("""
                SELECT o.id,o.code,o.name,s.id AS submission_id,s.status
                FROM request_target t JOIN organization o ON o.id=t.organization_id
                LEFT JOIN submission s ON s.request_id=t.request_id AND s.organization_id=t.organization_id
                    AND s.seq=(SELECT MAX(latest.seq) FROM submission latest WHERE latest.request_id=t.request_id
                        AND latest.organization_id=t.organization_id AND latest.status<>'DRAFT')
                WHERE t.request_id=? AND t.excluded=FALSE ORDER BY o.code,o.id
                """, requestId);
    }

    /** 요청이 연간 계획 요청인지 확인한다.
     * Args: requestId 양수 ID. Returns: 연도 포함 요청 행. Raises: ApiException 입력·404·종류 오류. Side Effects: DB 읽기.
     */
    private Map<String, Object> annualRequest(String requestId) {
        var request = one("SELECT r.*,p.year FROM submission_request r JOIN annual_plan p ON p.id=r.annual_plan_id WHERE r.id=?", id(requestId));
        if (!"ANNUAL_PLAN".equals(request.get("type"))) throw invalid("연간 계획 요청만 마스터로 조회·발행할 수 있습니다.");
        return request;
    }

    /** 가장 최근 발행 행을 읽는다.
     * Args: requestId 요청 PK. Returns: 최신 마스터 또는 null. Raises: DB 오류. Side Effects: DB 읽기.
     */
    private Map<String, Object> latest(Object requestId) {
        var found = rows("SELECT * FROM annual_master WHERE request_id=? ORDER BY seq DESC LIMIT 1", requestId);
        return found.isEmpty() ? null : found.getFirst();
    }

    /** 마스터에 기록된 제출 참조만 읽는다.
     * Args: master 버전 행, request 요청 행. Returns: 조직 ID 순 검증된 출처.
     * Raises: ApiException 근거 오류·출처 누락·조합 지문 불일치. Side Effects: DB 읽기.
     */
    private List<Source> sources(Map<String, Object> master, Map<String, Object> request) {
        var result = new ArrayList<Source>(); var metadata = reportMetadata();
        for (var row : rows("SELECT organization_id,submission_id FROM annual_master_source WHERE master_id=? ORDER BY organization_id", master.get("id"))) {
            result.add(source(number(row.get("submission_id")), number(row.get("organization_id")), request, metadata));
        }
        if (result.isEmpty() || !CALCULATION_VERSION.equals(master.get("calculation_version"))
                || !contentHash(request, result).equals(master.get("content_sha256"))) throw invalidSource();
        return result;
    }

    /** 고정 조합의 조직별 의미 지문을 안정된 순서로 계산한다.
     * Args: request 연간 요청, sources 검증된 고정 출처. Returns: 발행·조회 공용 SHA-256.
     * Raises: 없음. Side Effects: 메모리 계산만 수행.
     */
    private String contentHash(Map<String, Object> request, List<Source> sources) {
        var fingerprint = new StringBuilder(CALCULATION_VERSION).append(':').append(request.get("id"));
        sources.stream().sorted(Comparator.comparingLong(Source::organizationId)).forEach(source ->
                fingerprint.append('|').append(source.organizationId()).append(':').append(source.fingerprint()));
        return hash(fingerprint.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** 한 발행·조회에서 공유할 보고 항목의 식별자·코드·종류를 한 번 읽는다.
     * Args: 없음. Returns: 보고 ID별 메타데이터. Raises: DB 오류. Side Effects: DB 읽기.
     */
    private Map<String, Map<String, Object>> reportMetadata() {
        var metadata = new HashMap<String, Map<String, Object>>();
        for (var report : rows("SELECT id,code,kind FROM report_item")) metadata.put(report.get("id").toString(), report);
        return metadata;
    }

    /** 제출 당시 검증 스냅샷과 실제 적재값의 동일성·완전성을 검사한다.
     * Args: submissionId 고정 제출, organizationId 기대 조직, request 기대 연간 요청, metadata 호출 범위의 보고 항목.
     * Returns: 검증된 보고값·의미 지문·공개 출처. Raises: ApiException 근거 누락·불일치.
     * Side Effects: DB 읽기. 재파싱·재검증·금액 보정은 하지 않는다.
     */
    private Source source(long submissionId, long organizationId, Map<String, Object> request, Map<String, Map<String, Object>> metadata) {
        var found = rows("""
                SELECT s.*,o.code AS organization_code,o.name AS organization_name,j.status AS parsing_status,
                    j.attempt_no,j.result_object_key,v.attempt_no AS validated_attempt,v.source_sha256 AS validated_hash,
                    v.parser_result_object_key,v.policy_version,b.payload
                FROM submission s JOIN organization o ON o.id=s.organization_id
                JOIN parsing_job j ON j.submission_id=s.id JOIN submission_validation v ON v.submission_id=s.id
                JOIN stored_object b ON b.object_key=v.validation_result_object_key WHERE s.id=?
                """, submissionId);
        if (found.isEmpty()) throw invalidSource(); var row = found.getFirst(); JsonNode snapshot;
        try { snapshot = mapper.readTree((byte[]) row.get("payload")); } catch (Exception exception) { throw invalidSource(); }
        if (snapshot == null || !snapshot.isObject()) throw invalidSource();
        String previous = row.get("previous_submission_id") == null ? null : row.get("previous_submission_id").toString();
        JsonNode snapshotPrevious = snapshot.path("previousSubmissionId");
        if (!"APPROVED".equals(row.get("status")) || number(row.get("organization_id")) != organizationId
                || !row.get("request_id").equals(request.get("id")) || !"SUCCEEDED".equals(row.get("parsing_status"))
                || !Objects.equals(row.get("attempt_no"), row.get("validated_attempt"))
                || !Objects.equals(row.get("source_sha256"), row.get("validated_hash"))
                || !Objects.equals(row.get("result_object_key"), row.get("parser_result_object_key"))
                || !POLICY_VERSION.equals(row.get("policy_version")) || !POLICY_VERSION.equals(snapshot.path("policyVersion").asText())
                || !Objects.equals(previous, snapshotPrevious.isNull() || snapshotPrevious.isMissingNode() ? null : snapshotPrevious.asText())
                || !snapshot.path("complete").asBoolean() || !snapshot.path("summary").path("complete").asBoolean()
                || !snapshot.path("fingerprint").asText().matches("[a-f0-9]{64}") || !snapshot.path("diagnostics").isArray()
                || !snapshot.path("reportValues").isArray() || snapshot.path("reportValues").isEmpty()) throw invalidSource();
        for (JsonNode diagnostic : snapshot.path("diagnostics")) if ("ERROR".equals(diagnostic.path("severity").asText())) throw invalidSource();
        long lines = jdbc.sql("SELECT COUNT(*) FROM financial_line WHERE submission_id=?").param(submissionId).query(Long.class).single();
        var facts = rows("""
                SELECT l.report_item_id,r.code,r.kind,v.year,v.month,v.scenario,v.amount
                FROM financial_line l JOIN financial_value v ON v.line_id=l.id JOIN report_item r ON r.id=l.report_item_id
                WHERE l.submission_id=?
                """, submissionId);
        if (lines == 0 || facts.isEmpty() || lines != snapshot.path("summary").path("lineCount").asLong(-1)
                || facts.size() != snapshot.path("summary").path("valueCount").asInt(-1)) throw invalidSource();
        var amounts = new HashMap<String, BigDecimal>();
        for (var fact : facts) {
            if (!fact.get("year").equals(request.get("year")) || !"PLAN".equals(fact.get("scenario"))
                    || !("REVENUE".equals(fact.get("kind")) || "COST".equals(fact.get("kind")))) throw invalidSource();
            BigDecimal amount = (BigDecimal) fact.get("amount");
            amounts.merge(fact.get("report_item_id") + ":" + fact.get("month"), amount, BigDecimal::add);
            amounts.merge(fact.get("kind") + ":" + fact.get("month"), amount, BigDecimal::add);
        }
        var reports = new ArrayList<Map<String, Object>>(); var keys = new HashSet<String>(); var reportIds = new HashSet<String>(); var totalCodes = new HashSet<String>();
        for (JsonNode value : snapshot.path("reportValues")) {
            String reportId = value.path("reportItemId").asText(); int month = value.path("month").asInt(); var report = metadata.get(reportId);
            if (report == null || !report.get("kind").equals(value.path("kind").asText())
                    || value.path("year").asInt() != number(request.get("year")) || month < 1 || month > 12
                    || !"PLAN".equals(value.path("scenario").asText()) || !"AVAILABLE".equals(value.path("valueStatus").asText())
                    || !Long.toString(organizationId).equals(value.path("organizationId").asText()) || !keys.add(reportId + ':' + month)) throw invalidSource();
            String code = report.get("code").toString(); BigDecimal expected = amounts.getOrDefault(reportId + ':' + month, BigDecimal.ZERO);
            if ("DERIVED".equals(report.get("kind"))) {
                BigDecimal revenue = amounts.getOrDefault("REVENUE:" + month, BigDecimal.ZERO), cost = amounts.getOrDefault("COST:" + month, BigDecimal.ZERO);
                expected = switch (code) { case "REVENUE_TOTAL" -> revenue; case "COST_TOTAL" -> cost; case "OP" -> revenue.subtract(cost); default -> throw invalidSource(); };
                totalCodes.add(code);
            }
            try { if (new BigDecimal(value.path("amount").asText()).compareTo(expected) != 0) throw invalidSource(); }
            catch (NumberFormatException exception) { throw invalidSource(); }
            reportIds.add(reportId);
            reports.add(object("reportItemId", reportId, "reportItemCode", code, "reportItemName", value.path("reportItemName").asText(),
                    "kind", report.get("kind"), "year", request.get("year"), "month", month, "scenario", "PLAN",
                    "organizationId", Long.toString(organizationId), "amount", expected.toPlainString(), "valueStatus", "AVAILABLE"));
        }
        if (keys.size() != reportIds.size() * 12 || !totalCodes.equals(Set.of("REVENUE_TOTAL", "COST_TOTAL", "OP"))
                || facts.stream().anyMatch(fact -> !reportIds.contains(fact.get("report_item_id").toString()))) throw invalidSource();
        return new Source(organizationId, snapshot.path("fingerprint").asText(), reports,
                object("organizationId", Long.toString(organizationId), "organizationCode", row.get("organization_code"), "organizationName", row.get("organization_name"),
                        "submissionId", Long.toString(submissionId), "versionLabel", String.format(Locale.ROOT, "%04d-PLAN-%02d", number(request.get("year")), number(row.get("seq")))));
    }

    /** 검증된 출처의 항목 합집합을 동일한 그리드로 집계한다.
     * Args: sources 고정 출처, organizationId null이면 전사. Returns: 항목 코드·월 순 보고값.
     * Raises: ApiException 동일 항목 메타데이터 불일치. Side Effects: 메모리 집계만 수행.
     */
    private List<Map<String, Object>> aggregate(List<Source> sources, Long organizationId) {
        var grid = new TreeMap<String, Map<String, Object>>(); var amounts = new HashMap<String, BigDecimal>();
        for (var source : sources) for (var report : source.reports()) {
            String key = report.get("reportItemCode") + ":" + String.format(Locale.ROOT, "%02d", report.get("month"));
            var previous = grid.putIfAbsent(key, report);
            if (previous != null && (!previous.get("reportItemId").equals(report.get("reportItemId")) || !previous.get("kind").equals(report.get("kind")))) throw invalidSource();
            if (organizationId == null || source.organizationId() == organizationId) amounts.merge(key, new BigDecimal(report.get("amount").toString()), BigDecimal::add);
        }
        var result = new ArrayList<Map<String, Object>>();
        for (var entry : grid.entrySet()) {
            var row = new LinkedHashMap<>(entry.getValue()); row.put("organizationId", organizationId == null ? null : organizationId.toString());
            row.put("amount", amounts.getOrDefault(entry.getKey(), BigDecimal.ZERO).toPlainString()); result.add(row);
        }
        return result;
    }

    /** 검증된 보고값을 한 번 순회해 전사와 고정 출처별 12개월 매출·비용을 함께 모은다.
     * Args: sources 검증된 마스터 고정 출처. Returns: 전사 totals·months 및 출처 메타가 포함된 organizations.
     * Raises: 없음. Side Effects: 메모리 계산만 수행하며 현재 조직·제출·DB를 다시 조회하지 않는다.
     */
    private Map<String, Object> profitSummaries(List<Source> sources) {
        var company = emptyMonthlyAmounts(); var organizations = new ArrayList<BigDecimal[][]>();
        for (var source : sources) {
            var amounts = emptyMonthlyAmounts();
            for (var report : source.reports()) {
                int column = switch (report.get("reportItemCode").toString()) { case "REVENUE_TOTAL" -> 0; case "COST_TOTAL" -> 1; default -> -1; };
                if (column < 0) continue;
                int month = ((Number) report.get("month")).intValue() - 1;
                BigDecimal amount = new BigDecimal(report.get("amount").toString());
                amounts[month][column] = amount; company[month][column] = company[month][column].add(amount);
            }
            organizations.add(amounts);
        }
        var result = profitSummary(company, null); var organizationViews = new ArrayList<Map<String, Object>>();
        for (int index = 0; index < sources.size(); index++) {
            var view = new LinkedHashMap<>(sources.get(index).view());
            view.putAll(profitSummary(organizations.get(index), company)); organizationViews.add(view);
        }
        result.put("organizations", organizationViews); return result;
    }

    /** 12개월 매출·비용을 정확한 0으로 초기화한다.
     * Args: 없음. Returns: 월 인덱스별 매출·비용의 12×2 배열. Raises: 없음. Side Effects: 메모리 배열 생성만 수행한다.
     */
    private BigDecimal[][] emptyMonthlyAmounts() {
        var result = new BigDecimal[12][2];
        for (var month : result) Arrays.fill(month, BigDecimal.ZERO);
        return result;
    }

    /** 월별 금액에서 연간·월별 손익 지표를 만들고 조직이면 같은 기간 전사 손익으로 기여율을 계산한다.
     * Args: amounts 월별 매출·비용, company 조직 조회의 전사 월별 금액이며 전사 자체이면 null.
     * Returns: totals·months. Raises: 없음. Side Effects: 메모리 계산만 수행하며 연간 비율은 월 비율 합계가 아닌 연간 금액으로 계산한다.
     */
    private Map<String, Object> profitSummary(BigDecimal[][] amounts, BigDecimal[][] company) {
        BigDecimal revenue = BigDecimal.ZERO, cost = BigDecimal.ZERO, companyProfit = BigDecimal.ZERO;
        var months = new ArrayList<Map<String, Object>>();
        for (int month = 0; month < 12; month++) {
            BigDecimal monthRevenue = amounts[month][0], monthCost = amounts[month][1];
            revenue = revenue.add(monthRevenue); cost = cost.add(monthCost);
            var values = totals(monthRevenue, monthCost); values.put("month", month + 1);
            if (company != null) {
                BigDecimal monthCompanyProfit = company[month][0].subtract(company[month][1]);
                companyProfit = companyProfit.add(monthCompanyProfit);
                values.put("operatingProfitShare", percentage(monthRevenue.subtract(monthCost), monthCompanyProfit));
            }
            months.add(values);
        }
        var annual = totals(revenue, cost);
        if (company != null) annual.put("operatingProfitShare", percentage(revenue.subtract(cost), companyProfit));
        return object("totals", annual, "months", months);
    }

    /** 매출·비용·손익과 영업이익률을 십진 문자열로 만든다.
     * Args: revenue 매출, cost 비용. Returns: 정확한 세 금액과 소수 둘째 자리 이익률, 매출이 0이면 이익률 null.
     * Raises: 없음. Side Effects: 없음. 금액은 반올림하지 않는다.
     */
    private Map<String, Object> totals(BigDecimal revenue, BigDecimal cost) {
        BigDecimal profit = revenue.subtract(cost);
        return object("revenue", revenue.toPlainString(), "cost", cost.toPlainString(), "operatingProfit", profit.toPlainString(),
                "operatingMargin", percentage(profit, revenue));
    }

    /** 부호를 유지하는 백분율을 소수 둘째 자리에서 HALF_UP으로 표시한다.
     * Args: numerator 분자 금액, denominator 같은 기간의 분모 금액. Returns: 소수 2자리 문자열 또는 분모 0일 때 null.
     * Raises: 없음. Side Effects: 없음. 분모가 음수이거나 기여율이 100을 넘어도 보정하지 않는다.
     */
    private String percentage(BigDecimal numerator, BigDecimal denominator) {
        return denominator.signum() == 0 ? null : numerator.multiply(BigDecimal.valueOf(100)).divide(denominator, 2, RoundingMode.HALF_UP).toPlainString();
    }

    /** 마스터의 공통 헤더를 공개 DTO로 변환한다.
     * Args: request 요청 행, master 마스터 행. Returns: 헤더. Raises: DB 오류. Side Effects: 작성자 DB 읽기.
     */
    private Map<String, Object> summary(Map<String, Object> request, Map<String, Object> master) {
        return object("id", master.get("id").toString(), "requestId", request.get("id").toString(), "year", request.get("year"), "scenario", "PLAN",
                "seq", master.get("seq"), "versionLabel", version(request, master), "previousMasterId", textId(master.get("previous_master_id")),
                "triggerSubmissionId", textId(master.get("trigger_submission_id")), "createdAt", instant(master.get("created_at")),
                "createdBy", object("id", textId(master.get("created_by")), "name", one("SELECT name FROM app_user WHERE id=?", master.get("created_by")).get("name")),
                "calculationVersion", master.get("calculation_version"));
    }

    /** 승인 응답에 포함할 연간 마스터 효과를 만든다.
     * Args: request 요청, targets 현재 필수 대상, master nullable 결과, action 작업 결과. Returns: 효과 DTO. Raises: 없음. Side Effects: 없음.
     */
    private Map<String, Object> effect(Map<String, Object> request, List<Map<String, Object>> targets, Map<String, Object> master, String action) {
        return object("kind", "ANNUAL_PLAN", "year", request.get("year"), "month", null, "action", action,
                "masterId", master == null ? null : textId(master.get("id")), "previousMasterId", master == null ? null : textId(master.get("previous_master_id")),
                "approvedOrganizationCount", approvedCount(targets), "requiredOrganizationCount", targets.size(),
                "versionLabel", master == null ? null : version(request, master));
    }

    /** 최신 완료 제출이 승인된 조직 수를 센다.
     * Args: targets 대상 목록. Returns: 승인 수. Raises: 없음. Side Effects: 없음.
     */
    private long approvedCount(List<Map<String, Object>> targets) { return targets.stream().filter(row -> "APPROVED".equals(row.get("status"))).count(); }

    /** 연도와 발행 회차로 마스터 라벨을 만든다.
     * Args: request 요청, master 마스터. Returns: 표시 라벨. Raises: 없음. Side Effects: 없음.
     */
    private String version(Map<String, Object> request, Map<String, Object> master) { return String.format(Locale.ROOT, "master-%04d-PLAN-%02d", number(request.get("year")), number(master.get("seq"))); }

    /** DB의 nullable ID를 JSON 문자열로 바꾼다.
     * Args: value nullable ID. Returns: 문자열 또는 null. Raises: 없음. Side Effects: 없음.
     */
    private String textId(Object value) { return value == null ? null : value.toString(); }

    /** JDBC 정수를 long으로 읽는다.
     * Args: value 정수. Returns: long. Raises: ClassCastException 잘못된 DB 타입. Side Effects: 없음.
     */
    private long number(Object value) { return ((Number) value).longValue(); }

    /** 알려진 쿼리 키와 빈 값 여부를 검사한다.
     * Args: query 외부 쿼리, allowed 허용 키. Returns: 없음. Raises: ApiException 입력 오류. Side Effects: 없음.
     */
    private void checkQuery(Map<String, String> query, Set<String> allowed) {
        if (!allowed.containsAll(query.keySet()) || query.values().stream().anyMatch(value -> value == null || value.isBlank())) throw invalid("허용되지 않거나 비어 있는 조회 조건입니다.");
    }

    /** 페이지 크기를 검증한다.
     * Args: query 조회 조건. Returns: 기본 50, 최대 100. Raises: ApiException 입력 오류. Side Effects: 없음.
     */
    private int limit(Map<String, String> query) { return query.containsKey("limit") ? integer(query.get("limit"), 1, 100) : 50; }

    /** 정규 십진 정수와 허용 범위를 검사한다.
     * Args: value 문자열, min 최솟값, max 최댓값. Returns: 정수. Raises: ApiException 입력 오류. Side Effects: 없음.
     */
    private int integer(String value, int min, int max) {
        try { if (!value.matches("[1-9][0-9]*")) throw new NumberFormatException(); int result = Integer.parseInt(value); if (result < min || result > max) throw new NumberFormatException(); return result; }
        catch (NumberFormatException exception) { throw invalid("숫자 조회 조건이 허용 범위를 벗어났습니다."); }
    }

    /** 내부 SQL의 단일 필수 행을 읽는다.
     * Args: sql 고정 조회문, params 바인딩. Returns: 행. Raises: ApiException 404. Side Effects: DB 읽기.
     */
    private Map<String, Object> one(String sql, Object... params) {
        var found = rows(sql, params); if (found.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "요청한 자료를 찾을 수 없습니다."); return found.getFirst();
    }

    /** 내부 SQL의 행 목록을 읽는다.
     * Args: sql 고정 조회문, params 바인딩. Returns: 행 목록. Raises: DB 오류. Side Effects: DB 읽기.
     */
    private List<Map<String, Object>> rows(String sql, Object... params) { return jdbc.sql(sql).params(params).query().listOfRows(); }

    /** 잘못된 조회 조건 오류를 만든다.
     * Args: message 공개 설명. Returns: 400 오류. Raises: 없음. Side Effects: 없음.
     */
    private ApiException invalid(String message) { return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message); }

    /** 업무 상태 충돌 오류를 만든다.
     * Args: code 오류 코드, message 공개 설명. Returns: 409 오류. Raises: 없음. Side Effects: 없음.
     */
    private ApiException conflict(String code, String message) { return new ApiException(HttpStatus.CONFLICT, code, message); }

    /** 불완전한 원본 조합의 발행·숫자 표시를 막는다.
     * Args: 없음. Returns: 409 오류. Raises: 없음. Side Effects: 없음.
     */
    private ApiException invalidSource() { return conflict("MASTER_SOURCE_INVALID", "승인 제출본의 고정 검증 근거와 적재값이 일치하지 않아 마스터를 구성할 수 없습니다."); }

    /** 검증된 출처의 메모리 표현이다. 자동 접근자는 파일·DB에 접근하지 않는다. */
    private record Source(long organizationId, String fingerprint, List<Map<String, Object>> reports, Map<String, Object> view) {}
}
