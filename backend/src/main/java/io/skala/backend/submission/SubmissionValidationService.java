package io.skala.backend.submission;

import static io.skala.backend.submission.ApiData.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.skala.backend.common.ApiException;
import io.skala.backend.security.Actor;
import io.skala.backend.review.ReviewComparisonService;
import io.skala.backend.review.ReviewService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 파서와 업무 적재를 분리하고 초안 검증·금액 조회·검토 근거를 관리한다. */
@Service
public class SubmissionValidationService {
    public static final String POLICY_VERSION = "annual-plan/1.0";
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final FinancialValidationRules rules;
    private final ReviewComparisonService comparisons;
    private final ReviewService reviews;
    private static final String ROW_SQL = """
            SELECT s.*,o.code AS organization_code,r.type,r.month,r.status AS request_status,p.year,
                p.status AS plan_status,j.status AS parsing_status,j.attempt_no,j.result_object_key
            FROM submission s JOIN organization o ON o.id=s.organization_id
            JOIN submission_request r ON r.id=s.request_id JOIN annual_plan p ON p.id=r.annual_plan_id
            JOIN parsing_job j ON j.submission_id=s.id WHERE s.id=?
            """;

    /** DB와 순수 검증기를 연결한다. Args: jdbc DB, mapper JSON, rules 검증기, comparisons 고정 비교, reviews 소명 준비상태. Returns: 없음. Raises: 없음. Side Effects: 없음. */
    public SubmissionValidationService(JdbcClient jdbc, ObjectMapper mapper, FinancialValidationRules rules, ReviewComparisonService comparisons, ReviewService reviews) { this.jdbc = jdbc; this.mapper = mapper; this.rules = rules; this.comparisons = comparisons; this.reviews = reviews; }

    /** 잠긴 초안을 검사하고 유효 상세·검증 근거를 같은 트랜잭션에 저장한다.
     * Args: actor 조직 담당자, submissionId 초안. 호출자가 조직·제출 잠금과 트랜잭션을 소유한다.
     * Returns: 없음. Raises: ApiException 권한·상태 오류, IllegalStateException 저장 계약 손상.
     * Side Effects: 해당 DRAFT의 적재값·현재 검증 참조만 교체. 원본과 과거 결과 객체는 보존.
     */
    public void validateLocked(Actor actor, String submissionId) {
        actor.requireRole("ORG_MANAGER");
        Map<String, Object> row = accessible(actor, submissionId);
        if (!"DRAFT".equals(row.get("status")) || !"SUCCEEDED".equals(row.get("parsing_status"))) throw conflict("VALIDATION_NOT_ALLOWED", "파싱을 완료한 초안만 검증할 수 있습니다.");
        JsonNode parsed = result(row);
        var evaluated = rules.evaluate(parsed.path("submissionData"), row.get("source_sha256").toString(), row.get("organization_code").toString(), ((Number) row.get("year")).intValue(), row.get("type").toString(), row.get("month") == null ? null : ((Number) row.get("month")).intValue());
        var diagnostics = (List<Map<String, Object>>) evaluated.get("diagnostics");
        Map<String,Object> context = comparisons.context(id(submissionId));
        Long previous = comparisons.nullableId(context.get("previousSubmissionId"));
        jdbc.sql("DELETE FROM financial_value WHERE line_id IN (SELECT id FROM financial_line WHERE submission_id=?)").param(id(submissionId)).update();
        jdbc.sql("DELETE FROM financial_line WHERE submission_id=?").param(id(submissionId)).update();
        var reportIds = new HashMap<String, Long>();
        for (Map<String, Object> report : (List<Map<String, Object>>) evaluated.get("reports")) reportIds.put((String) report.get("key"), reportId(report));
        int valueCount = 0;
        for (Map<String, Object> line : (List<Map<String, Object>>) evaluated.get("lines")) {
            Map<String, Object> source = (Map<String, Object>) line.get("source");
            long lineId = insert("""
                    INSERT INTO financial_line(submission_id,account_item_id,report_item_id,detail_id,detail_revision,
                        headquarters_code,team_code,task_code,management_category,actual_basis,cost_type,description,source_sheet,source_row)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, id(submissionId), accountId(line), reportIds.get(line.get("reportItemKey")), line.get("detailId"), string(line.get("detailRevision")),
                    line.get("headquartersCode"), line.get("teamCode"), line.get("taskCode"), line.get("managementCategory"), line.get("actualBasis"), line.get("costType"), line.get("description"), source.get("sheet"), source.get("row"));
            for (Map<String, Object> value : (List<Map<String, Object>>) line.get("values")) {
                jdbc.sql("INSERT INTO financial_value(line_id,year,month,scenario,amount,source_record_id,source_cell) VALUES (?,?,?,?,?,?,?)")
                        .params(lineId, value.get("year"), value.get("month"), value.get("scenario"), value.get("amount"), value.get("sourceRecordId"), value.get("sourceCell")).update();
                valueCount++;
            }
        }
        boolean complete = diagnostics.stream().noneMatch(item -> "ERROR".equals(item.get("severity")));
        var values = (List<Map<String, Object>>) evaluated.get("reportValues");
        for (var value : values) {
            value.put("reportItemId", reportIds.get(value.remove("reportKey")).toString());
            value.put("organizationId", row.get("organization_id").toString());
            if (!complete) { value.put("valueStatus", "UNRESOLVED"); value.put("amount", null); }
        }
        diagnostics.addAll(comparisons.prepare(id(submissionId), context, complete));
        complete = diagnostics.stream().noneMatch(item -> "ERROR".equals(item.get("severity")));
        if (!complete) for (var value : values) { value.put("valueStatus", "UNRESOLVED"); value.put("amount", null); }
        Map<String, Object> snapshot = object("policyVersion", policy(row), "comparisonContext", context, "previousSubmissionId", previous == null ? null : previous.toString(),
                "fingerprint", evaluated.get("fingerprint"), "complete", complete, "diagnostics", diagnostics, "reportValues", values,
                "summary", object("lineCount", ((List<?>) evaluated.get("lines")).size(), "valueCount", valueCount, "complete", complete, "validationState", "CURRENT"));
        String resultKey = "validation/" + UUID.randomUUID();
        jdbc.sql("INSERT INTO stored_object(object_key,content_type,payload) VALUES (?,?,?)").params(resultKey, "application/json", json(snapshot).getBytes(StandardCharsets.UTF_8)).update();
        jdbc.sql("DELETE FROM submission_validation WHERE submission_id=?").param(id(submissionId)).update();
        jdbc.sql("""
                INSERT INTO submission_validation(submission_id,attempt_no,source_sha256,parser_result_object_key,policy_version,
                    validation_result_object_key,validated_by,validated_at) VALUES (?,?,?,?,?,?,?,?)
                """).params(id(submissionId), row.get("attempt_no"), row.get("source_sha256"), row.get("result_object_key"), policy(row), resultKey, id(actor.id()), OffsetDateTime.now(ZoneOffset.UTC)).update();
        jdbc.sql("UPDATE submission SET previous_submission_id=? WHERE id=? AND status='DRAFT'").params(previous, id(submissionId)).update();
    }

    /** 최종 제출 직전 최신 검증과 비교 문맥을 재검사한다.
     * Args: actor 조직 담당자, submissionId 잠긴 초안. Returns: 없음.
     * Raises: ApiException 준비 안 됨·문맥 변경. Side Effects: DB 읽기만 수행하며 재검증하지 않는다.
     */
    public void assertReadyForSubmit(Actor actor, String submissionId) {
        Map<String, Object> row = accessible(actor, submissionId);
        var state = readiness(row);
        if ("STALE".equals(state.get("validationState"))) throw conflict("REVIEW_CONTEXT_CHANGED", "비교 기준 또는 파싱 시도가 달라졌습니다. 다시 검증해 주세요.");
        if (!Boolean.TRUE.equals(state.get("canSubmit"))) throw conflict("SUBMISSION_NOT_READY", "최종 제출 조건을 충족하지 못했습니다. 검증 결과를 확인해 주세요.");
    }

    /** 최종 제출 때 고정한 검증 근거·소명 수용·적재 완전성을 승인 전에 확인한다.
     * Args: actor 경영기획 담당자, submissionId 호출자가 조직·제출 잠금을 소유한 제출본.
     * Returns: 없음. Raises: ApiException 제출 당시 근거 부재·불일치·미완료·적재 누락.
     * Side Effects: DB 읽기만 수행. 최신 요청 상태·비교본·정책으로 재검증하거나 금융값을 변경하지 않는다.
     */
    public void assertReadyForApproval(Actor actor, String submissionId) {
        actor.requireRole("PLANNER");
        Map<String, Object> row = accessible(actor, submissionId);
        var saved = rows("SELECT * FROM submission_validation WHERE submission_id=?", id(submissionId));
        if (saved.isEmpty()) throw conflict("VALIDATION_REQUIRED", "제출 당시 업무 검증 근거가 없어 승인할 수 없습니다.");
        Map<String, Object> validation = saved.getFirst();
        JsonNode snapshot = objectResult(validation.get("validation_result_object_key").toString());
        if (!"PENDING".equals(row.get("status"))
                || !"SUCCEEDED".equals(row.get("parsing_status"))
                || !policy(row).equals(validation.get("policy_version"))
                || !Objects.equals(validation.get("policy_version"), snapshot.path("policyVersion").asText())
                || !Objects.equals(validation.get("attempt_no"), row.get("attempt_no"))
                || !Objects.equals(validation.get("source_sha256"), row.get("source_sha256"))
                || !Objects.equals(validation.get("parser_result_object_key"), row.get("result_object_key"))
                || !Objects.equals(nullableId(snapshot.path("previousSubmissionId")), row.get("previous_submission_id"))) {
            throw conflict("REVIEW_CONTEXT_CHANGED", "제출 당시 검증 근거와 현재 제출본이 일치하지 않아 승인할 수 없습니다.");
        }
        if (!snapshot.path("complete").asBoolean() || !snapshot.path("summary").path("complete").asBoolean()
                || !snapshot.path("diagnostics").isArray() || !snapshot.path("reportValues").isArray()
                || snapshot.path("reportValues").isEmpty() || snapshot.path("fingerprint").asText().isBlank()) {
            throw conflict("SUBMISSION_NOT_READY", "완전한 검증 근거가 확인된 제출본만 승인할 수 있습니다.");
        }
        for (JsonNode diagnostic : snapshot.path("diagnostics")) {
            if ("ERROR".equals(diagnostic.path("severity").asText())) {
                throw conflict("SUBMISSION_NOT_READY", "미해결 검증 오류 또는 필수 수정 소명이 있어 승인할 수 없습니다.");
            }
        }
        var reviewCounts = reviews.counts(id(submissionId));
        if (((Number) reviewCounts.get("pendingReviewCount")).intValue() != 0) throw conflict("EXPLANATIONS_NOT_ACCEPTED", "필수 소명을 모두 수용한 뒤 승인할 수 있습니다.");
        if ("MONTHLY_ACTUAL".equals(row.get("type"))) {
            var fixed = snapshot.path("comparisonContext");
            if (!Objects.equals(stringNode(fixed.path("planSubmissionId")), comparisons.context(id(submissionId)).get("planSubmissionId"))) throw conflict("REVIEW_CONTEXT_CHANGED", "조직별 확정 계획 기준이 일치하지 않습니다.");
        }
        long lines = jdbc.sql("SELECT COUNT(*) FROM financial_line WHERE submission_id=?")
                .param(id(submissionId)).query(Long.class).single();
        long values = jdbc.sql("SELECT COUNT(*) FROM financial_value v JOIN financial_line l ON l.id=v.line_id WHERE l.submission_id=?")
                .param(id(submissionId)).query(Long.class).single();
        if (lines == 0 || values == 0 || lines != snapshot.path("summary").path("lineCount").asLong(-1)
                || values != snapshot.path("summary").path("valueCount").asLong(-1)) {
            throw conflict("SUBMISSION_NOT_READY", "제출 당시 검증한 금액의 적재 구성이 일치하지 않습니다.");
        }
    }

    /** 저장된 근거로 현재 준비 상태를 계산한다.
     * Args: submissionRow id를 가진 내부 제출 행. Returns: readiness DTO.
     * Raises: IllegalStateException 저장 결과 손상. Side Effects: DB 읽기; 상태·금액 변경 없음.
     */
    public Map<String, Object> readiness(Map<String, Object> submissionRow) {
        Map<String, Object> row = row(submissionRow.get("id").toString());
        List<Map<String, Object>> saved = rows("SELECT * FROM submission_validation WHERE submission_id=?", row.get("id"));
        var blockers = new ArrayList<Map<String, Object>>();
        String validationState = "NOT_RUN";
        Object attempt = null;
        boolean preparing = Set.of("QUEUED", "RUNNING").contains(row.get("parsing_status"));
        if (saved.isEmpty()) blockers.add(blocker(preparing ? "PARSING_NOT_READY" : "VALIDATION_REQUIRED", preparing ? "파싱 완료 후 검증할 수 있습니다." : "업무 검증을 실행해 주세요.", "COMPLETENESS"));
        else {
            Map<String, Object> validation = saved.getFirst();
            JsonNode snapshot = objectResult(validation.get("validation_result_object_key").toString());
            attempt = validation.get("attempt_no");
            boolean current = Objects.equals(validation.get("attempt_no"), row.get("attempt_no")) && Objects.equals(validation.get("source_sha256"), row.get("source_sha256")) && Objects.equals(validation.get("parser_result_object_key"), row.get("result_object_key")) && policy(row).equals(validation.get("policy_version"));
            if ("DRAFT".equals(row.get("status")) && !Objects.equals(snapshot.has("comparisonContext") ? mapper.convertValue(snapshot.path("comparisonContext"), Map.class) : legacyContext(snapshot), comparisons.context(((Number) row.get("id")).longValue()))) current = false;
            validationState = current ? "CURRENT" : "STALE";
            if (!current) blockers.add(blocker("VALIDATION_STALE", "파싱 또는 비교 문맥이 변경되어 다시 검증해야 합니다.", "COMPARISON"));
            else for (JsonNode item : snapshot.path("diagnostics")) if ("ERROR".equals(item.path("severity").asText())) blockers.add(mapper.convertValue(item, Map.class));
        }
        if (!"SUCCEEDED".equals(row.get("parsing_status")) && !preparing) blockers.add(blocker("PARSING_REQUIRES_ATTENTION", "파싱 진단을 해결한 파일을 다시 접수해 주세요.", "PARSING"));
        if ("DRAFT".equals(row.get("status")) && !"OPEN".equals(row.get("request_status"))) blockers.add(blocker("REQUEST_CLOSED", "닫힌 요청에는 최종 제출할 수 없습니다.", "COMPLETENESS"));
        if ("ANNUAL_PLAN".equals(row.get("type")) && !"COLLECTING".equals(row.get("plan_status"))) blockers.add(blocker("ANNUAL_PLAN_LOCKED", "확정된 연간 계획입니다.", "COMPLETENESS"));
        if (!rows("SELECT id FROM submission WHERE organization_id=? AND status='PENDING' AND id<>?", row.get("organization_id"), row.get("id")).isEmpty()) blockers.add(blocker("PENDING_ALREADY_EXISTS", "검토 중인 다른 제출본이 있습니다.", "COMPLETENESS"));
        if (!rows("""
                SELECT rejected.id FROM submission rejected WHERE rejected.organization_id=?
                  AND rejected.request_id<>? AND rejected.status='REJECTED'
                  AND rejected.seq=(SELECT MAX(latest.seq) FROM submission latest
                    WHERE latest.organization_id=rejected.organization_id AND latest.request_id=rejected.request_id
                      AND latest.status<>'DRAFT')
                """, row.get("organization_id"), row.get("request_id")).isEmpty()) blockers.add(blocker("RESUBMISSION_SCOPE_CONFLICT", "재제출 요청을 받은 기간부터 제출해 주세요.", "COMPLETENESS"));
        if (rows("SELECT request_id FROM request_target WHERE request_id=? AND organization_id=? AND excluded=FALSE", row.get("request_id"), row.get("organization_id")).isEmpty()) blockers.add(blocker("REQUEST_TARGET_EXCLUDED", "현재 제출·승인 대상에서 제외된 조직입니다.", "COMPLETENESS"));
        if ("MONTHLY_ACTUAL".equals(row.get("type")) && !"CONFIRMED".equals(row.get("plan_status"))) blockers.add(blocker("ANNUAL_PLAN_NOT_CONFIRMED", "확정된 연간 계획이 필요합니다.", "COMPLETENESS"));
        boolean countsKnown = "CURRENT".equals(validationState) && saved.size() == 1;
        var counts = reviews.counts(((Number) row.get("id")).longValue());
        boolean draft = "DRAFT".equals(row.get("status"));
        if (countsKnown && draft && ((Number) counts.get("unansweredCount")).intValue() > 0) blockers.add(blocker("REQUIRED_EXPLANATIONS_MISSING", "수정·Gap 필수 소명을 작성해 주세요.", "EXPLANATION"));
        if (countsKnown && "PENDING".equals(row.get("status")) && ((Number) counts.get("pendingReviewCount")).intValue() > 0) blockers.add(blocker("EXPLANATIONS_NOT_ACCEPTED", "필수 소명 수용이 필요합니다.", "EXPLANATION"));
        boolean ready = "CURRENT".equals(validationState) && blockers.isEmpty() && draft;
        return object("state", preparing ? "PREPARING" : "STALE".equals(validationState) ? "STALE" : ready ? "READY" : "BLOCKED", "validationState", validationState, "validatedAttemptNo", attempt, "canSubmit", ready,
                "canApprove", "PENDING".equals(row.get("status")) && "CURRENT".equals(validationState) && blockers.isEmpty(), "blockers", blockers,
                "requiredCount", countsKnown ? counts.get("requiredCount") : null, "unansweredCount", countsKnown ? counts.get("unansweredCount") : null, "pendingReviewCount", countsKnown ? counts.get("pendingReviewCount") : null);
    }

    /** 검증 결과 변경을 상태 토큰에 연결한다. Args: submissionId 제출. Returns: 참조문자열/빈문자열. Raises: 없음. Side Effects: DB 읽기. */
    public String validationFingerprint(String submissionId) { var found = rows("SELECT validation_result_object_key FROM submission_validation WHERE submission_id=?", id(submissionId)); var row = row(submissionId); return json(object("result", found.isEmpty() ? null : found.getFirst().get("validation_result_object_key"), "context", "DRAFT".equals(row.get("status")) ? comparisons.context(id(submissionId)) : comparisonContext(submissionId), "reviews", reviews.revision(id(submissionId)))); }

    /** 고정된 비교 문맥을 읽는다. Args: submissionId 제출. Returns: 비교 DTO. Raises: 없음. Side Effects: DB 읽기. */
    public Map<String, Object> comparisonContext(String submissionId) { JsonNode snapshot = validationResult(submissionId); return snapshot != null && snapshot.has("comparisonContext") ? mapper.convertValue(snapshot.path("comparisonContext"), Map.class) : legacyContext(snapshot); }

    /** 이전 연간 검증 근거를 원래 비교 범위로 읽는다. Args: snapshot 기존 JSON/null. Returns: 문맥. Raises: 없음. Side Effects: 없음. */
    private Map<String,Object> legacyContext(JsonNode snapshot) { return object("previousSubmissionId",snapshot == null ? null : stringNode(snapshot.path("previousSubmissionId")),"planSubmissionId",null,"actualThresholdId",null,"estimateThresholdId",null,"reviewPolicyVersion",snapshot == null ? null : POLICY_VERSION); }

    /** 요청 유형의 검증 정책을 선택한다. Args: row 제출문맥. Returns: 정책명. Raises: 없음. Side Effects: 없음. */
    private String policy(Map<String,Object> row) { return "MONTHLY_ACTUAL".equals(row.get("type")) ? "monthly-actual/1.0" : POLICY_VERSION; }

    /** 검증에서 구성한 검토 ID를 명령 응답에 연결한다. Args: submissionId 제출, unanswered 미답변만. Returns: ID목록. Raises: DB 오류. Side Effects: 읽기. */
    public List<String> reviewIds(String submissionId, boolean unanswered) { return reviews.ids(id(submissionId), unanswered); }

    /** 현재 검증 진단을 읽는다. Args: submissionId 제출. Returns: 진단 목록. Raises: 저장 손상. Side Effects: DB 읽기. */
    public List<JsonNode> diagnostics(String submissionId) { JsonNode snapshot = validationResult(submissionId); var result = new ArrayList<JsonNode>(); if (snapshot != null) { if ("STALE".equals(readiness(row(submissionId)).get("validationState"))) result.add(mapper.valueToTree(blocker("VALIDATION_STALE", "파싱 또는 비교 문맥이 변경되어 다시 검증해야 합니다.", "COMPARISON"))); else snapshot.path("diagnostics").forEach(result::add); } return result; }

    /** 실제 적재된 월별 값을 권한·필터 범위에서 조회한다.
     * Args: actor 행위자, submissionId 제출, query 연월·보고항목·커서.
     * Returns: 적재 요약과 월별 상세 페이지. Raises: ApiException 권한·검증 미실행·필터 오류.
     * Side Effects: DB 읽기만 수행하며 후보를 자동 적재하지 않는다.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> financialValues(Actor actor, String submissionId, Map<String, String> query) {
        Map<String, Object> row = accessible(actor, submissionId);
        JsonNode snapshot = currentResult(row);
        checkFilters(query);
        var params = new ArrayList<Object>(); params.add(id(submissionId));
        String where = " WHERE l.submission_id=?";
        for (String field : List.of("year", "month", "scenario", "reportItemId")) if (query.containsKey(field)) { where += " AND " + (field.equals("reportItemId") ? "l.report_item_id" : "v." + field) + "=?"; params.add(filterValue(field, query.get(field))); }
        int limit = limit(query), offset = offset(query.get("cursor"), scope(actor, submissionId, query, "values"));
        params.add(limit + 1); params.add(offset);
        var found = jdbc.sql("""
                SELECT v.*,l.detail_id,l.description,l.team_code,l.task_code,l.source_sheet,l.source_row,
                    a.source_code,a.source_name,r.id AS report_id,r.code AS report_code,r.name AS report_name,r.kind
                FROM financial_value v JOIN financial_line l ON l.id=v.line_id
                JOIN report_item r ON r.id=l.report_item_id LEFT JOIN account_item a ON a.id=l.account_item_id
                """ + where + " ORDER BY l.source_sheet,l.source_row,v.year,v.month,v.id LIMIT ? OFFSET ?").params(params).query().listOfRows();
        var items = new ArrayList<Map<String, Object>>();
        for (var value : found.stream().limit(limit).toList()) items.add(object("id", value.get("id").toString(), "lineId", value.get("line_id").toString(), "reportItem", object("id", value.get("report_id").toString(), "code", value.get("report_code"), "name", value.get("report_name"), "kind", value.get("kind")), "accountCode", value.get("source_code"), "accountName", value.get("source_name"), "teamCode", value.get("team_code"), "taskCode", value.get("task_code"), "detailId", value.get("detail_id"), "description", value.get("description"), "year", value.get("year"), "month", value.get("month"), "scenario", value.get("scenario"), "amount", ((BigDecimal) value.get("amount")).toPlainString(), "source", object("sheet", value.get("source_sheet"), "cell", value.get("source_cell"), "row", value.get("source_row"))));
        return object("summary", mapper.convertValue(snapshot.path("summary"), Map.class), "items", items, "nextCursor", found.size() > limit ? cursor(offset + limit, scope(actor, submissionId, query, "values")) : null);
    }

    /** 검증된 전체 보고 항목의 집계를 조회한다.
     * Args: actor 행위자, submissionId 제출, query 필터.
     * Returns: ReportValue 페이지. Raises: ApiException 권한·현재성·필터 오류.
     * Side Effects: 저장된 검증 스냅샷만 읽으며 불완전 합계를 숫자로 바꾸지 않는다.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> reportValues(Actor actor, String submissionId, Map<String, String> query) {
        var row = accessible(actor, submissionId); JsonNode snapshot = currentResult(row); checkFilters(query);
        var items = new ArrayList<JsonNode>();
        for (JsonNode value : snapshot.path("reportValues")) {
            boolean match = true;
            for (String field : List.of("year", "month", "scenario", "reportItemId")) if (query.containsKey(field) && !value.path(field).asText().equals(query.get(field))) match = false;
            if (match) items.add(value);
        }
        int start = offset(query.get("cursor"), scope(actor, submissionId, query, "reports")), end = Math.min(items.size(), start + limit(query));
        return object("items", items.subList(Math.min(start, items.size()), end), "nextCursor", end < items.size() ? cursor(end, scope(actor, submissionId, query, "reports")) : null);
    }

    /** 파서가 보존한 실제 한 셀의 원문·수식·단위를 조회한다.
     * Args: actor 행위자, submissionId 제출, query sheet/cell 두 값.
     * Returns: SourceCell. Raises: ApiException 권한·주소·미수집 셀·파싱 중 오류.
     * Side Effects: DB의 보존 자료만 읽으며 원본 수식 실행·빈 셀 추정을 하지 않는다.
     */
    public JsonNode sourceCell(Actor actor, String submissionId, Map<String, String> query) {
        var row = accessible(actor, submissionId);
        if (!query.keySet().equals(Set.of("sheet", "cell")) || query.get("sheet").isBlank() || query.get("sheet").length() > 255 || !query.get("cell").matches("[A-Z]{1,3}[1-9][0-9]{0,6}")) throw invalid("시트명과 단일 셀 주소가 필요합니다.");
        if (row.get("result_object_key") == null) throw conflict("PARSING_NOT_READY", "원본 셀 추출이 완료되지 않았습니다.");
        JsonNode parsed = result(row);
        for (JsonNode source : parsed.path("submissionData").path("sourceCells")) if (query.get("sheet").equals(source.path("sheet").asText()) && query.get("cell").equals(source.path("cell").asText())) return sourceEvidence(parsed, source);
        throw new ApiException(HttpStatus.NOT_FOUND, "SOURCE_CELL_NOT_FOUND", "해당 시트·셀의 수집 근거가 없습니다.");
    }

    /** 공유 수식 종속 셀에 실제 보존된 기준 수식만 덧붙인다.
     * Args: parsed 불변 파싱 결과, source 선택한 SourceCell.
     * Returns: 원본 필드와 nullable sharedFormulaBase를 가진 복사본.
     * Raises: 없음. Side Effects: 메모리 복사만 수행하며 상대참조 전개·수식 실행·저장 객체 변경 없음.
     */
    private JsonNode sourceEvidence(JsonNode parsed, JsonNode source) {
        var view = (com.fasterxml.jackson.databind.node.ObjectNode) source.deepCopy();
        view.putNull("sharedFormulaBase");
        if (source.hasNonNull("formula")) return view;
        for (JsonNode record : parsed.path("records")) {
            JsonNode location = record.path("source");
            if (!source.path("sheet").asText().equals(location.path("sheet").asText()) || !source.path("cell").asText().equals(location.path("cell").asText())) continue;
            JsonNode formula = record.path("formula"), base = formula.path("shared_base");
            if ("shared".equals(formula.path("attributes").path("t").asText()) && base.hasNonNull("address") && base.hasNonNull("raw_text") && formula.path("attributes").path("si").equals(base.path("attributes").path("si"))) {
                view.set("sharedFormulaBase", mapper.valueToTree(object("sheet", source.path("sheet").asText(), "cell", base.path("address").asText(), "formula", "=" + base.path("raw_text").asText(), "range", stringNode(base.path("attributes").path("ref")))));
            }
            break;
        }
        return view;
    }

    /** 현재 검증 결과만 조회에 허용한다. Args: row 제출. Returns: 검증 JSON. Raises: ApiException 미실행·오래된 검증. Side Effects: DB 읽기. */
    private JsonNode currentResult(Map<String, Object> row) { String state = readiness(row).get("validationState").toString(); if (!"CURRENT".equals(state)) throw conflict("STALE".equals(state) ? "VALIDATION_STALE" : "VALIDATION_REQUIRED", "현재 파일의 업무 검증이 필요합니다."); return validationResult(row.get("id").toString()); }

    /** 보고 항목을 기존 고유 코드와 연결한다. Args: report 검증된 분류. Returns: DB ID. Raises: 저장 충돌. Side Effects: 없는 보고 항목만 삽입. */
    private long reportId(Map<String, Object> report) {
        var found = rows("SELECT * FROM report_item WHERE code=?", report.get("code"));
        if (!found.isEmpty()) { var item = found.getFirst(); if (!Objects.equals(item.get("name"), report.get("name")) || !Objects.equals(item.get("kind"), report.get("kind"))) throw conflict("REPORT_CATALOG_CONFLICT", "기존 보고 항목의 이름·분류가 원본과 충돌합니다."); return ((Number) item.get("id")).longValue(); }
        boolean derived = "DERIVED".equals(report.get("kind"));
        return insert("INSERT INTO report_item(code,name,kind,is_leaf,calculation_code) VALUES (?,?,?,?,?)", report.get("code"), report.get("name"), report.get("kind"), !derived, derived ? report.get("code") : null);
    }

    /** 원본 계정을 보고 분류와 독립적으로 연결한다. Args: line 검증 행. Returns: 계정 ID/null. Raises: 저장 오류. Side Effects: 없는 원본 계정만 삽입. */
    private Long accountId(Map<String, Object> line) {
        Map<String, Object> account = (Map<String, Object>) line.get("account");
        if (account == null || account.get("code") == null || account.get("code").toString().isBlank()) return null;
        String type = "REVENUE".equals(line.get("domain")) ? "REVENUE_CLASS" : "EXPENSE_BUDGET", name = Objects.toString(account.get("name"), "");
        var found = rows("SELECT id FROM account_item WHERE source_type=? AND source_code=? AND source_name=?", type, account.get("code"), name);
        return found.isEmpty() ? insert("INSERT INTO account_item(source_type,source_code,source_name) VALUES (?,?,?)", type, account.get("code"), name) : ((Number) found.getFirst().get("id")).longValue();
    }

    /** 조회 권한을 DB 소속에 맞춰 검사한다. Args: actor 행위자, submissionId 제출. Returns: 내부 행. Raises: ApiException 권한 밖은 404. Side Effects: DB 읽기. */
    private Map<String, Object> accessible(Actor actor, String submissionId) { var value = row(submissionId); if (!actor.canAccessOrganization(value.get("organization_id").toString())) throw new ApiException(HttpStatus.NOT_FOUND, "SUBMISSION_NOT_FOUND", "제출본을 찾을 수 없습니다."); return value; }

    /** 단일 제출·요청·파싱 문맥을 읽는다. Args: submissionId ID. Returns: 내부 행. Raises: ApiException 없음. Side Effects: DB 읽기. */
    private Map<String, Object> row(String submissionId) { var found = rows(ROW_SQL, id(submissionId)); if (found.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "SUBMISSION_NOT_FOUND", "제출본을 찾을 수 없습니다."); return found.getFirst(); }

    /** 현재 검증 객체를 읽는다. Args: submissionId 제출. Returns: JSON/null. Raises: 저장 손상. Side Effects: DB 읽기. */
    private JsonNode validationResult(String submissionId) { var found = rows("SELECT validation_result_object_key FROM submission_validation WHERE submission_id=?", id(submissionId)); return found.isEmpty() ? null : objectResult(found.getFirst().get("validation_result_object_key").toString()); }

    /** 저장된 파서 JSON을 읽는다. Args: row 제출. Returns: JSON. Raises: 저장 손상. Side Effects: DB 읽기. */
    private JsonNode result(Map<String, Object> row) { return objectResult(row.get("result_object_key").toString()); }

    /** 내부 객체 키의 JSON을 읽는다. Args: key 서버가 선택한 키. Returns: JSON. Raises: IllegalStateException 손상. Side Effects: DB 읽기; 경로·원문 오류 미노출. */
    private JsonNode objectResult(String key) { try { return mapper.readTree(jdbc.sql("SELECT payload FROM stored_object WHERE object_key=?").param(key).query(byte[].class).single()); } catch (Exception exception) { throw new IllegalStateException("저장된 검증 자료를 읽지 못했습니다."); } }

    /** 고정 SQL의 행을 읽는다. Args: sql 서버 SQL, params 바인딩. Returns: 행 목록. Raises: DB 오류. Side Effects: DB 읽기. */
    private List<Map<String, Object>> rows(String sql, Object... params) { return jdbc.sql(sql).params(params).query().listOfRows(); }

    /** 새 업무 행의 ID를 받는다. Args: sql 서버 INSERT, params 값. Returns: ID. Raises: DB 오류. Side Effects: 현재 트랜잭션에 삽입. */
    private long insert(String sql, Object... params) { var key = new GeneratedKeyHolder(); jdbc.sql(sql).params(params).update(key, "id"); return Objects.requireNonNull(key.getKey()).longValue(); }

    /** nullable ID를 읽는다. Args: node JSON. Returns: ID/null. Raises: 잘못된 내부 값. Side Effects: 없음. */
    private Long nullableId(JsonNode node) { return node.isNull() || node.isMissingNode() ? null : id(node.asText()); }

    /** nullable JSON 문자열을 읽는다. Args: node JSON. Returns: 문자열/null. Raises: 없음. Side Effects: 없음. */
    private String stringNode(JsonNode node) { return node.isNull() || node.isMissingNode() ? null : node.asText(); }

    /** nullable 객체를 문자열로 맞춘다. Args: value 값. Returns: 문자열/null. Raises: 없음. Side Effects: 없음. */
    private String string(Object value) { return value == null ? null : value.toString(); }

    /** 내부 검증 자료를 JSON으로 만든다. Args: value DTO. Returns: JSON. Raises: IllegalStateException 실패. Side Effects: 없음. */
    private String json(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception exception) { throw new IllegalStateException("검증 자료 직렬화 실패"); } }

    /** 안전한 공통 차단 진단을 만든다. Args: code/message/stage 규정 값. Returns: 진단. Raises: 없음. Side Effects: 없음. */
    private Map<String, Object> blocker(String code, String message, String stage) { return object("code", code, "severity", "ERROR", "stage", stage, "message", message, "source", null); }

    /** 허용 조회 필터를 검증한다. Args: query 필터. Returns: 없음. Raises: ApiException 입력 오류. Side Effects: 없음. */
    private void checkFilters(Map<String, String> query) { if (!Set.of("year", "month", "scenario", "reportItemId", "limit", "cursor").containsAll(query.keySet())) throw invalid("지원하지 않는 조회 필터입니다."); for (String key : List.of("year", "month", "scenario", "reportItemId")) if (query.containsKey(key)) filterValue(key, query.get(key)); limit(query); }

    /** 조회 값을 안전한 DB 타입으로 검증한다. Args: key 필드, value 값. Returns: 바인딩 값. Raises: ApiException 범위 오류. Side Effects: 없음. */
    private Object filterValue(String key, String value) { try { if (key.equals("reportItemId")) return id(value); if (key.equals("scenario")) { if (!Set.of("PLAN", "ACTUAL", "ESTIMATE").contains(value)) throw new IllegalArgumentException(); return value; } int number = Integer.parseInt(value); if (key.equals("year") ? number < 2000 || number > 9999 : number < 1 || number > 12) throw new IllegalArgumentException(); return number; } catch (IllegalArgumentException exception) { throw invalid("연도·월·시나리오 필터를 확인해 주세요."); } }

    /** 페이지 크기를 읽는다. Args: query 필터. Returns: 1~100. Raises: ApiException 오류. Side Effects: 없음. */
    private int limit(Map<String, String> query) { try { int value = Integer.parseInt(query.getOrDefault("limit", "50")); if (value < 1 || value > 100) throw new IllegalArgumentException(); return value; } catch (IllegalArgumentException exception) { throw invalid("limit는 1~100입니다."); } }

    /** 커서를 인증·제출·검증·필터에 연결한다. Args: actor 행위자, submissionId 제출, query 필터, name 조회명. Returns: 범위. Raises: 없음. Side Effects: 검증 참조 DB 읽기. */
    private String scope(Actor actor, String submissionId, Map<String, String> query, String name) { var filters = new TreeMap<>(query); filters.remove("cursor"); return json(List.of(actor.id(), submissionId, validationFingerprint(submissionId), name, filters)); }

    /** 입력 오류를 만든다. Args: message 안내. Returns: 400 예외. Raises: 없음. Side Effects: 없음. */
    private ApiException invalid(String message) { return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message); }

    /** 현재 상태 충돌을 만든다. Args: code 코드, message 안내. Returns: 409 예외. Raises: 없음. Side Effects: 없음. */
    private ApiException conflict(String code, String message) { return new ApiException(HttpStatus.CONFLICT, code, message); }
}
