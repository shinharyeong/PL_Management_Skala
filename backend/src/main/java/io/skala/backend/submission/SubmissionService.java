package io.skala.backend.submission;

import static io.skala.backend.submission.ApiData.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.skala.backend.common.ApiException;
import io.skala.backend.foundation.CommandExecutor;
import io.skala.backend.security.Actor;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;

/** 제출 요청 관리·공통 항목 조회·원본 접수와 업무 상태 조회를 담당한다. */
@Service
public class SubmissionService {
    private static final Set<String> RETRYABLE = Set.of("PARSER_TIMEOUT", "PARSER_INTERRUPTED", "PARSER_UNAVAILABLE", "PARSER_PROTOCOL_INVALID");
    private static final String REQUEST_SQL = """
            SELECT r.*, p.year, p.status AS plan_status, u.name AS requester_name
            FROM submission_request r JOIN annual_plan p ON p.id=r.annual_plan_id
            JOIN app_user u ON u.id=r.requested_by
            """;
    private static final String SUBMISSION_SQL = """
            SELECT s.*, r.type, r.month, p.year, u.name AS uploader_name,
                submitter.name AS submitter_name, reviewer.name AS reviewer_name,
                j.id AS job_id, j.status AS parsing_status, j.attempt_no, j.error_code,
                j.parser_version, j.contract_version, j.mapping_version, j.result_object_key
            FROM submission s JOIN submission_request r ON r.id=s.request_id
            JOIN annual_plan p ON p.id=r.annual_plan_id JOIN app_user u ON u.id=s.uploaded_by
            LEFT JOIN app_user submitter ON submitter.id=s.submitted_by
            LEFT JOIN app_user reviewer ON reviewer.id=s.reviewed_by
            JOIN parsing_job j ON j.submission_id=s.id
            """;
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final CommandExecutor commands;
    private final SubmissionValidationService validation;

    /** 주입된 DB·직렬화·멱등 경계를 연결한다.
     * Args: jdbc DB 접근, mapper JSON 변환, commands 트랜잭션 명령, validation 검증·적재 조회.
     * Returns: 없음. Raises: 없음. Side Effects: 없음.
     */
    public SubmissionService(JdbcClient jdbc, ObjectMapper mapper, CommandExecutor commands, SubmissionValidationService validation) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.commands = commands;
        this.validation = validation;
    }

    /** 현재 행위자의 조직 조회 범위를 반환한다.
     * Args: actor 인증 행위자, query active/limit/cursor 필터.
     * Returns: 조직 페이지. Raises: ApiException 잘못된 필터. Side Effects: DB 읽기.
     */
    public Map<String, Object> organizations(Actor actor, Map<String, String> query) {
        checkQuery(query, Set.of("active", "limit", "cursor"));
        var params = new ArrayList<Object>();
        String where = " WHERE 1=1";
        if (!actor.role().equals("PLANNER")) {
            where += " AND id=?";
            params.add(id(actor.organization().id()));
        }
        if (query.containsKey("active")) {
            if (!Set.of("true", "false").contains(query.get("active"))) throw invalid("active는 true/false입니다.");
            where += " AND active=?";
            params.add(Boolean.parseBoolean(query.get("active")));
        }
        return page(actor, query, "organizations", "SELECT * FROM organization" + where + " ORDER BY id", params,
                row -> object("id", row.get("id").toString(), "code", row.get("code"), "name", row.get("name"), "active", row.get("active"), "recipients",recipients(((Number)row.get("id")).longValue())));
    }

    /** 보고 항목의 실제 분류·계층을 필터와 페이지 범위에서 조회한다.
     * Args: actor 인증 행위자, query active/kind/limit/cursor 필터.
     * Returns: ReportItem 페이지. Raises: ApiException 미정의 필터·잘못된 enum/커서.
     * Side Effects: 공통 분류 DB 읽기만 수행하며 항목을 생성하거나 금액을 노출하지 않는다.
     */
    public Map<String, Object> reportItems(Actor actor, Map<String, String> query) {
        checkQuery(query, Set.of("active", "kind", "limit", "cursor"));
        var params = new ArrayList<Object>();
        String where = " WHERE 1=1";
        if (query.containsKey("active")) {
            if (!Set.of("true", "false").contains(query.get("active"))) throw invalid("active는 true/false입니다.");
            where += " AND active=?";
            params.add(Boolean.parseBoolean(query.get("active")));
        }
        if (query.containsKey("kind")) {
            if (!Set.of("REVENUE", "COST", "DERIVED").contains(query.get("kind"))) throw invalid("보고 항목 구분을 확인하세요.");
            where += " AND kind=?";
            params.add(query.get("kind"));
        }
        return page(actor, query, "report-items", "SELECT * FROM report_item" + where + " ORDER BY display_order,id", params,
                row -> object("id", row.get("id").toString(), "code", row.get("code"), "name", row.get("name"),
                        "kind", row.get("kind"), "parentId", row.get("parent_id") == null ? null : row.get("parent_id").toString(),
                        "isLeaf", row.get("is_leaf"), "calculationCode", row.get("calculation_code"),
                        "displayOrder", row.get("display_order"), "active", row.get("active")));
    }

    /** 제출 요청의 안내·표시 기한·개폐 상태만 현재 토큰과 멱등 키로 변경한다.
     * Args: actor 기획자, requestId 요청 ID, key 멱등 키, input 생략과 null을 구분하는 변경 JSON.
     * Returns: 갱신된 요청 상세의 200 또는 같은 명령의 저장 응답.
     * Raises: ApiException 권한·입력·토큰 충돌·확정 계획 재개방 오류.
     * Side Effects: 계획→요청 순서로 잠그고 요청 헤더와 멱등 응답만 갱신한다. 제출·소명·대상은 보존한다.
     */
    public CommandExecutor.Result updateRequest(Actor actor, String requestId, String key, JsonNode input) {
        actor.requireRole("PLANNER");
        long request = id(requestId);
        if (input == null || !input.isObject() || input.size() < 2 || !input.path("expectedStateToken").isTextual()
                || input.path("expectedStateToken").asText().isBlank()) throw invalid("현재 상태 토큰과 변경할 필드가 필요합니다.");
        input.fieldNames().forEachRemaining(field -> {
            if (!Set.of("expectedStateToken", "message", "dueAt", "status", "organizationIds").contains(field)) throw invalid("정의되지 않은 변경 필드입니다.");
        });
        if (input.has("message") && !input.get("message").isNull()
                && (!input.get("message").isTextual() || input.get("message").textValue().length() > 5000)) throw invalid("안내는 5000자 이하 문자열 또는 null입니다.");
        if (input.has("status") && (!input.get("status").isTextual()
                || !Set.of("OPEN", "CLOSED").contains(input.get("status").textValue()))) throw invalid("요청 상태는 OPEN/CLOSED입니다.");
        OffsetDateTime dueAt = requestDueAt(input);
        List<String> selectedIds = null;
        if (input.has("organizationIds")) {
            if (!input.get("organizationIds").isArray() || input.get("organizationIds").isEmpty()) throw invalid("요청할 조직을 선택하세요.");
            selectedIds = new ArrayList<>();
            for (var value : input.get("organizationIds")) { if (!value.isTextual()) throw invalid("조직 ID는 문자열입니다."); selectedIds.add(value.asText()); }
        }
        final List<String> organizationsToSelect = selectedIds;
        String route = "/api/v1/submission-requests/" + request;
        return commands.execute(actor, "PATCH " + route, key,
                hash(json(mapper.convertValue(input, TreeMap.class)).getBytes(StandardCharsets.UTF_8)), () -> {
            var row = lockRequest(actor, request);
            if (!input.path("expectedStateToken").asText().equals(requestView(actor, row, false).get("stateToken"))) {
                throw conflict("RESOURCE_CHANGED", "요청이 변경되었습니다. 최신 상태를 다시 확인하세요.");
            }
            Object status = input.has("status") ? input.get("status").textValue() : row.get("status");
            if ("OPEN".equals(status) && "ANNUAL_PLAN".equals(row.get("type")) && !"COLLECTING".equals(row.get("plan_status"))) {
                throw conflict("ANNUAL_PLAN_LOCKED", "확정된 연간 계획 요청은 다시 열 수 없습니다.");
            }
            if (organizationsToSelect != null) {
                if (!"OPEN".equals(status) || ("ANNUAL_PLAN".equals(row.get("type")) && !"COLLECTING".equals(row.get("plan_status"))))
                    throw conflict("REQUEST_CLOSED", "열린 요청에서만 추가 조직에 요청할 수 있습니다.");
                selectTargets(request, organizationsToSelect, false);
            }
            jdbc.sql("UPDATE submission_request SET message=?,due_at=?,status=? WHERE id=?")
                    .params(input.has("message") ? input.get("message").isNull() ? null : input.get("message").textValue() : row.get("message"),
                            input.has("dueAt") ? dueAt : row.get("due_at"), status, request).update();
            return new CommandExecutor.Result(200, json(request(actor, Long.toString(request))), route);
        });
    }

    /** 요청 PATCH의 nullable 기한을 오프셋이 있는 시각으로 검증한다.
     * Args: input 변경 JSON. Returns: 제공된 기한 또는 생략/null의 null.
     * Raises: ApiException 날짜 형식·자료형 오류. Side Effects: 없음; 생략 여부는 호출자가 별도로 유지한다.
     */
    private OffsetDateTime requestDueAt(JsonNode input) {
        if (!input.has("dueAt") || input.get("dueAt").isNull()) return null;
        if (!input.get("dueAt").isTextual()) throw invalid("dueAt은 오프셋이 있는 시각 문자열 또는 null입니다.");
        try { return OffsetDateTime.parse(input.get("dueAt").textValue()); }
        catch (DateTimeParseException exception) { throw invalid("dueAt의 날짜와 시간대 형식을 확인하세요."); }
    }

    /** 전사 필수 조직을 고정하고 선택한 조직에 제출 요청을 생성한다.
     * Args: actor 기획 담당자, key 멱등키, input 검증된 입력.
     * Returns: 최초 생성 또는 동일 명령의 저장 응답. Raises: ApiException 계획/중복/대상 오류.
     * Side Effects: 계획·요청·대상·멱등 응답을 한 DB 트랜잭션에서 생성.
     */
    public CommandExecutor.Result createRequest(Actor actor, String key, RequestInput input) {
        actor.requireRole("PLANNER");
        boolean annual = input.type().equals("ANNUAL_PLAN");
        if (annual != (input.month() == null)) throw invalid("연간 요청은 월이 없고 월간 요청은 월이 필수입니다.");
        String route = "/api/v1/submission-requests";
        return commands.execute(actor, "POST " + route, key, hash(json(input).getBytes(StandardCharsets.UTF_8)), () -> {
            String period = input.year() + (annual ? "-PLAN" : "-" + input.month());
            if (count("SELECT COUNT(*) FROM submission_request WHERE period_key=?", period) != 0) {
                throw conflict("REQUEST_ALREADY_EXISTS", "해당 기간의 요청이 이미 있습니다.");
            }
            var plans = rows("SELECT * FROM annual_plan WHERE year=?", input.year());
            long planId;
            if (annual) {
                if (!plans.isEmpty()) throw conflict("ANNUAL_PLAN_LOCKED", "기존 연간 계획 요청을 사용하세요.");
                planId = insert("INSERT INTO annual_plan(year,status) VALUES (?,'COLLECTING')", input.year());
            } else {
                if (plans.isEmpty() || !plans.getFirst().get("status").equals("CONFIRMED")) {
                    throw conflict("ANNUAL_PLAN_NOT_CONFIRMED", "확정된 연간 계획이 필요합니다.");
                }
                planId = ((Number) plans.getFirst().get("id")).longValue();
            }
            long requestId = insert("""
                    INSERT INTO submission_request(annual_plan_id,type,month,status,message,due_at,requested_by,requested_at,period_key)
                    VALUES (?,?,?,'OPEN',?,?,?,?,?)
                    """, planId, input.type(), input.month(), input.message(), input.dueAt(), id(actor.id()), now(), period);
            int targetCount = jdbc.sql("""
                    INSERT INTO request_target(request_id,organization_id) SELECT ?,id FROM organization WHERE active=TRUE
                    """).param(requestId).update();
            if (targetCount == 0) throw conflict("REQUEST_TARGETS_EMPTY", "제출 대상 조직이 없습니다.");
            if (input.organizationIds() != null) selectTargets(requestId, input.organizationIds(), true);
            return new CommandExecutor.Result(201, json(request(actor, Long.toString(requestId))), route + "/" + requestId);
        });
    }

    /** 고정된 전사 대상 안에서 실제 요청 조직을 선정하며 이미 발송된 대상은 제거하지 않는다.
     * Args: requestId 요청, organizationIds 선택 조직, initial 최초 생성 여부.
     * Returns: 없음. Raises: ApiException 빈값·중복·대상 밖 조직. Side Effects: 현재 트랜잭션의 selected만 갱신하며 마스터 필수 조직 집합은 보존한다.
     */
    private void selectTargets(long requestId,List<String> organizationIds,boolean initial) {
        if(organizationIds.isEmpty()||organizationIds.size()>1000) throw invalid("1개 이상의 요청 대상 조직을 선택하세요.");
        var selected=new java.util.TreeSet<Long>();
        for(String organizationId:organizationIds) if(!selected.add(id(organizationId))) throw invalid("중복된 조직을 선택할 수 없습니다.");
        var available=new java.util.HashSet<Long>();
        for(var target:rows("SELECT organization_id FROM request_target WHERE request_id=? AND excluded=FALSE",requestId)) available.add(((Number)target.get("organization_id")).longValue());
        if(!available.containsAll(selected)) throw invalid("요청 생성 시 고정된 사업조직 중에서 선택하세요.");
        if(initial) jdbc.sql("UPDATE request_target SET selected=FALSE WHERE request_id=?").param(requestId).update();
        for(Long organizationId:selected) jdbc.sql("UPDATE request_target SET selected=TRUE WHERE request_id=? AND organization_id=?").params(requestId,organizationId).update();
    }

    /** 등록된 조직 담당자만 수신 대상 정보로 읽는다.
     * Args: organizationId 조직. Returns: 실제 활성 담당자의 ID·이름·이메일 목록. Raises: DB 오류. Side Effects: DB 읽기만 수행하며 이메일을 추정하거나 발송하지 않는다.
     */
    private List<Map<String,Object>> recipients(long organizationId) {
        return rows("SELECT id,name,email FROM app_user WHERE organization_id=? AND role='ORG_MANAGER' AND active=TRUE ORDER BY id",organizationId)
                .stream().map(row->object("id",row.get("id").toString(),"name",row.get("name"),"email",row.get("email"))).toList();
    }

    /** 요청함을 권한과 필터에 따라 조회한다.
     * Args: actor 인증 행위자, query 명세의 목록 필터.
     * Returns: 요청 페이지. Raises: ApiException 잘못된 필터. Side Effects: DB 읽기.
     */
    public Map<String, Object> requests(Actor actor, Map<String, String> query) {
        checkQuery(query, Set.of("year", "month", "type", "status", "limit", "cursor"));
        var params = new ArrayList<Object>();
        String where = " WHERE 1=1";
        if (!actor.role().equals("PLANNER")) {
            where += " AND EXISTS (SELECT 1 FROM request_target t WHERE t.request_id=r.id AND t.organization_id=? AND t.excluded=FALSE AND t.selected=TRUE)";
            params.add(id(actor.organization().id()));
        }
        where += filters(query, params, false);
        return page(actor, query, "requests", REQUEST_SQL + where + " ORDER BY r.id DESC", params,
                row -> requestView(actor, row, false));
    }

    /** 단일 요청과 본인 권한 범위의 대상 현황을 조회한다.
     * Args: actor 인증 행위자, requestId 요청 ID.
     * Returns: 요청 DTO. Raises: ApiException 없는/권한 밖 요청. Side Effects: DB 읽기.
     */
    public Map<String, Object> request(Actor actor, String requestId) {
        return requestView(actor, requestRow(actor, id(requestId)), true);
    }

    /** 바이트 원본과 QUEUED 작업을 함께 접수한다.
     * Args: actor 조직 담당자, requestId 대상 요청, key 멱등키, filename 검증할 이름, bytes 전체 원본.
     * Returns: 202 응답과 조회 위치. Raises: ApiException 권한/파일/요청/계획/대기 상태 오류.
     * Side Effects: DB 원본·초안·파싱 작업·성공 응답 생성; 파일시스템 쓰기 없음.
     */
    public CommandExecutor.Result upload(Actor actor, String requestId, String key, String filename, byte[] bytes) {
        actor.requireRole("ORG_MANAGER");
        long request = id(requestId);
        Map<String, Object> accessible = requestRow(actor, request);
        String expected = actor.organization().code() + "_" + accessible.get("year")
                + (accessible.get("type").equals("ANNUAL_PLAN") ? "-PLAN" : "-" + String.format("%02d", accessible.get("month"))) + ".xlsx";
        if (!expected.equals(filename)) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_FILENAME", "파일명과 조직·요청 기간이 일치해야 합니다.");
        if (bytes.length == 0 || bytes.length > 16777216) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "파일은 1바이트 이상 16MiB 이하이어야 합니다.");
        }
        if (bytes.length < 4 || bytes[0] != 'P' || bytes[1] != 'K' || bytes[2] != 3 || bytes[3] != 4) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_FILE_TYPE", "암호화되지 않은 XLSX 파일이 필요합니다.");
        }
        String sourceHash = hash(bytes);
        String route = "/api/v1/submission-requests/" + request + "/submissions";
        String fingerprint = hash(json(List.of(requestId, filename, sourceHash)).getBytes(StandardCharsets.UTF_8));
        return commands.execute(actor, "POST " + route, key, fingerprint, () -> {
            jdbc.sql("SELECT id FROM organization WHERE id=? FOR UPDATE").param(id(actor.organization().id())).query(Long.class).single();
            var row = lockRequest(actor, request);
            if (!row.get("status").equals("OPEN")) throw conflict("REQUEST_CLOSED", "닫힌 요청입니다.");
            if (row.get("type").equals("ANNUAL_PLAN") && !row.get("plan_status").equals("COLLECTING")) {
                throw conflict("ANNUAL_PLAN_LOCKED", "연간 계획이 이미 확정되었습니다.");
            }
            if (row.get("type").equals("MONTHLY_ACTUAL")) {
                if (!row.get("plan_status").equals("CONFIRMED")) throw conflict("ANNUAL_PLAN_NOT_CONFIRMED", "연간 계획 확정이 필요합니다.");
                if (count("""
                        SELECT COUNT(*) FROM request_target t JOIN submission_request r ON r.id=t.request_id
                        WHERE r.annual_plan_id=? AND r.type='ANNUAL_PLAN' AND t.organization_id=?
                        AND t.excluded=FALSE AND t.confirmed_plan_submission_id IS NOT NULL
                        """, row.get("annual_plan_id"), id(actor.organization().id())) == 0) {
                    throw conflict("PLAN_SOURCE_MISSING", "해당 조직의 확정 계획본이 없습니다.");
                }
            }
            if (count("SELECT COUNT(*) FROM submission WHERE organization_id=? AND request_id=? AND status='PENDING'", id(actor.organization().id()), request) > 0) {
                throw conflict("PENDING_ALREADY_EXISTS", "검토 중인 제출본이 있습니다.");
            }

            if (count("""
                    SELECT COUNT(*) FROM submission s JOIN parsing_job j ON j.submission_id=s.id
                    WHERE s.organization_id=? AND j.status IN ('QUEUED','RUNNING')
                    """, id(actor.organization().id())) >= 10) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PARSER_BUSY", "조직의 파싱 대기 한도에 도달했습니다.");
            }
            String objectKey = "source/" + UUID.randomUUID();
            jdbc.sql("INSERT INTO stored_object(object_key,content_type,payload) VALUES (?,?,?)")
                    .params(objectKey, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes).update();
            long submissionId = insert("""
                    INSERT INTO submission(request_id,organization_id,status,original_filename,source_object_key,source_sha256,uploaded_by,uploaded_at)
                    VALUES (?,?,'DRAFT',?,?,?,?,?)
                    """, request, id(actor.organization().id()), filename, objectKey, sourceHash, id(actor.id()), now());
            insert("INSERT INTO parsing_job(submission_id,status,engine,attempt_no) VALUES (?,'QUEUED','excel_parser',1)", submissionId);
            return new CommandExecutor.Result(202, json(submission(actor, Long.toString(submissionId))), "/api/v1/submissions/" + submissionId);
        });
    }

    /** 요청·조직·기간·상태로 제출 이력을 조회한다.
     * Args: actor 인증 행위자, query 목록 필터.
     * Returns: 제출 요약 페이지. Raises: ApiException 잘못된 필터/다른 조직 필터. Side Effects: DB 읽기.
     */
    public Map<String, Object> submissions(Actor actor, Map<String, String> query) {
        checkQuery(query, Set.of("requestId", "organizationId", "year", "month", "type", "status", "submittedOnly", "limit", "cursor"));
        var params = new ArrayList<Object>();
        String where = " WHERE 1=1";
        if (query.containsKey("submittedOnly")) {
            if (!Set.of("true", "false").contains(query.get("submittedOnly"))) throw invalid("submittedOnly는 true/false입니다.");
            if ("true".equals(query.get("submittedOnly"))) {
                if ("DRAFT".equals(query.get("status"))) throw invalid("제출 완료 필터와 초안 상태를 함께 선택할 수 없습니다.");
                where += " AND s.status<>'DRAFT'";
            }
        }
        if (query.containsKey("organizationId")) {
            String organizationId = query.get("organizationId");
            if (!actor.canAccessOrganization(organizationId)) throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "다른 조직을 조회할 수 없습니다.");
            where += " AND s.organization_id=?";
            params.add(id(organizationId));
        } else if (!actor.role().equals("PLANNER")) {
            where += " AND s.organization_id=?";
            params.add(id(actor.organization().id()));
        }
        if (query.containsKey("requestId")) {
            where += " AND s.request_id=?";
            params.add(id(query.get("requestId")));
        }
        where += filters(query, params, true);
        return page(actor, query, "submissions", SUBMISSION_SQL + where + " ORDER BY s.uploaded_at DESC,s.id DESC", params,
                this::submissionSummary);
    }

    /** 활성 파싱 시도와 제출 준비 상태를 조회한다.
     * Args: actor 인증 행위자, submissionId 제출 ID.
     * Returns: DRAFT/파싱/검증 전 상태 DTO. Raises: ApiException 권한 밖/없는 제출. Side Effects: DB 읽기.
     */
    public Map<String, Object> submission(Actor actor, String submissionId) {
        return submissionView(submissionRow(actor, id(submissionId)));
    }

    /** 활성 파싱 시도의 안전한 진단만 반환한다.
     * Args: actor 인증 행위자, submissionId 제출 ID, query 진단 필터.
     * Returns: 진단 페이지. Raises: ApiException 파싱 미완료/잘못된 필터. Side Effects: DB 읽기.
     */
    public Map<String, Object> diagnostics(Actor actor, String submissionId, Map<String, String> query) {
        checkQuery(query, Set.of("severity", "stage", "limit", "cursor"));
        if (query.containsKey("severity") && !Set.of("ERROR", "WARNING").contains(query.get("severity"))) throw invalid("severity가 올바르지 않습니다.");
        if (query.containsKey("stage") && !Set.of("FILE", "PARSING", "MAPPING", "COMPLETENESS", "COMPARISON").contains(query.get("stage"))) throw invalid("stage가 올바르지 않습니다.");
        var row = submissionRow(actor, id(submissionId));
        if (Set.of("QUEUED", "RUNNING").contains(row.get("parsing_status"))) throw conflict("PARSING_NOT_READY", "파싱 중입니다.");
        JsonNode result = readResult(row);
        var items = new ArrayList<JsonNode>();
        var combined = new ArrayList<JsonNode>();
        result.path("diagnostics").forEach(combined::add);
        combined.addAll(validation.diagnostics(submissionId, result));
        for (JsonNode diagnostic : combined) {
            if (query.containsKey("severity") && !query.get("severity").equals(diagnostic.path("severity").asText())) continue;
            if (query.containsKey("stage") && !query.get("stage").equals(diagnostic.path("stage").asText())) continue;
            items.add(diagnostic);
        }
        int limit = limit(query);
        String scope = scope(actor, query, "diagnostics/" + submissionId + "/" + row.get("attempt_no") + "/" + row.get("parsing_status") + "/" + validation.validationFingerprint(submissionId));
        int offset = offset(query.get("cursor"), scope);
        int end = Math.min(items.size(), offset + limit);
        return object("items", items.subList(Math.min(offset, items.size()), end), "nextCursor", end < items.size() ? cursor(end, scope) : null);
    }

    /** 권한을 확인한 뒤 최초 원본 바이트를 반환한다.
     * Args: actor 인증 행위자, submissionId 제출 ID.
     * Returns: 이름과 바이트. Raises: ApiException 권한 밖/없는 제출. Side Effects: DB 읽기.
     */
    public OriginalFile original(Actor actor, String submissionId) {
        var row = submissionRow(actor, id(submissionId));
        byte[] bytes = jdbc.sql("SELECT payload FROM stored_object WHERE object_key=?").param(row.get("source_object_key")).query(byte[].class).single();
        return new OriginalFile(row.get("original_filename").toString(), bytes);
    }

    /** 운영 오류로 끝난 동일 원본의 재파싱을 등록한다.
     * Args: actor 조직 담당자, submissionId 제출 ID, key 멱등키, input 기대 토큰·시도.
     * Returns: 새 활성 시도의 202 응답. Raises: ApiException 권한/토큰/시도/종료 상태 충돌.
     * Side Effects: 기존 작업의 시도 증가·결과 참조 제거·멱등 기록 저장.
     */
    public CommandExecutor.Result retry(Actor actor, String submissionId, String key, RetryInput input) {
        actor.requireRole("ORG_MANAGER");
        submissionRow(actor, id(submissionId));
        String route = "/api/v1/submissions/" + submissionId + "/parsing-retries";
        return commands.execute(actor, "POST " + route, key, hash(json(input).getBytes(StandardCharsets.UTF_8)), () -> {
            jdbc.sql("SELECT id FROM submission WHERE id=? FOR UPDATE").param(id(submissionId)).query(Long.class).single();
            var row = submissionRow(actor, id(submissionId));
            if (!token(row).equals(input.expectedStateToken())) throw conflict("RESOURCE_CHANGED", "최신 제출 상태를 다시 조회하세요.");
            if (!row.get("status").equals("DRAFT") || !retryAllowed(row)
                    || ((Number) row.get("attempt_no")).intValue() != input.expectedAttemptNo()) {
                throw conflict("PARSING_RETRY_NOT_ALLOWED", "현재 파일·시도는 재파싱할 수 없습니다.");
            }
            jdbc.sql("""
                    UPDATE parsing_job SET status='QUEUED',attempt_no=attempt_no+1,result_object_key=NULL,
                        error_code=NULL,error_message=NULL,started_at=NULL,finished_at=NULL,
                        parser_version=NULL,contract_version=NULL,mapping_version=NULL WHERE id=?
                    """).param(row.get("job_id")).update();
            return new CommandExecutor.Result(202, json(submission(actor, submissionId)), "/api/v1/submissions/" + submissionId);
        });
    }

    /** 공통 요청 행과 조직 대상 소속을 확인한다.
     * Args: actor 인증 행위자, requestId 정수 ID. Returns: 내부 DB 행.
     * Raises: ApiException 없는/권한 밖 요청. Side Effects: DB 읽기.
     */
    private Map<String, Object> requestRow(Actor actor, long requestId) {
        var found = rows(REQUEST_SQL + " WHERE r.id=?", requestId);
        if (found.isEmpty() || (!actor.role().equals("PLANNER")
                && count("SELECT COUNT(*) FROM request_target WHERE request_id=? AND organization_id=? AND excluded=FALSE AND selected=TRUE", requestId, id(actor.organization().id())) == 0)) {
            throw notFound();
        }
        return found.getFirst();
    }

    /** 개폐·업로드가 확정·최종 제출과 같은 계획→요청 순서로 상태를 읽도록 잠근다.
     * Args: actor 인증 행위자, requestId 요청 ID. Returns: 잠금 이후 새로 읽은 요청 행.
     * Raises: ApiException 없는 요청·권한 오류, DB 잠금 충돌.
     * Side Effects: 호출 트랜잭션 종료까지 계획·요청 행 잠금; 데이터 변경 없음.
     */
    private Map<String, Object> lockRequest(Actor actor, long requestId) {
        var row = requestRow(actor, requestId);
        jdbc.sql("SELECT id FROM annual_plan WHERE id=? FOR UPDATE").param(row.get("annual_plan_id")).query(Long.class).single();
        jdbc.sql("SELECT id FROM submission_request WHERE id=? FOR UPDATE").param(requestId).query(Long.class).single();
        return requestRow(actor, requestId);
    }

    /** 단일 제출 접근 범위를 확인한다.
     * Args: actor 인증 행위자, submissionId 정수 ID. Returns: 내부 DB 행.
     * Raises: ApiException 없는/권한 밖 제출. Side Effects: DB 읽기.
     */
    private Map<String, Object> submissionRow(Actor actor, long submissionId) {
        var found = rows(SUBMISSION_SQL + " WHERE s.id=?", submissionId);
        if (found.isEmpty() || !actor.canAccessOrganization(found.getFirst().get("organization_id").toString())) throw notFound();
        return found.getFirst();
    }

    /** 요청·권한 범위 집계를 DTO로 조립한다.
     * Args: actor 인증 행위자, row 내부 행, detail 대상 상세 포함 여부.
     * Returns: 요청 DTO. Raises: DB 접근 오류. Side Effects: 대상/제출 현황 DB 읽기.
     */
    private Map<String, Object> requestView(Actor actor, Map<String, Object> row, boolean detail) {
        var params = new ArrayList<Object>();
        params.add(row.get("id"));
        String where = "";
        if (!actor.role().equals("PLANNER")) {
            where = " AND t.organization_id=? AND t.selected=TRUE";
            params.add(id(actor.organization().id()));
        }
        boolean includeDraft = detail || actor.role().equals("ORG_MANAGER");
        String draftFields = includeDraft ? "draft.id AS draft_id,draft_job.status AS draft_parsing_status"
                : "NULL AS draft_id,NULL AS draft_parsing_status";
        String draftJoins = includeDraft ? """
                LEFT JOIN submission draft ON draft.id=(
                    SELECT d.id FROM submission d WHERE d.request_id=t.request_id AND d.organization_id=t.organization_id
                    AND d.status='DRAFT' AND (latest.id IS NULL OR d.uploaded_at>COALESCE(latest.submitted_at,latest.uploaded_at))
                    ORDER BY d.uploaded_at DESC,d.id DESC LIMIT 1)
                LEFT JOIN parsing_job draft_job ON draft_job.submission_id=draft.id
                """ : "";
        var targets = jdbc.sql("""
                SELECT o.id,o.code,o.name,t.selected,
                    (SELECT COUNT(*) FROM submission d WHERE d.request_id=t.request_id AND d.organization_id=t.organization_id AND d.status='DRAFT') AS draft_count,
                    latest.id AS latest_id,latest.status AS latest_status,
                """ + draftFields + """

                FROM request_target t JOIN organization o ON o.id=t.organization_id
                LEFT JOIN submission latest ON latest.id=(
                    SELECT s.id FROM submission s WHERE s.request_id=t.request_id AND s.organization_id=t.organization_id
                    AND s.status<>'DRAFT' ORDER BY s.seq DESC LIMIT 1)
                """ + draftJoins + """
                WHERE t.request_id=? AND t.excluded=FALSE
                """ + where + " ORDER BY o.id").params(params).query().listOfRows();
        var targetViews = new ArrayList<Map<String, Object>>();
        int submitted = 0;
        int approved = 0;
        for (var target : targets) {
            Object latestId = target.get("latest_id");
            String latestStatus = latestId == null ? "NOT_SUBMITTED"
                    : target.get("latest_status").toString();
            if (Boolean.TRUE.equals(target.get("selected")) && latestId != null) submitted++;
            if (Boolean.TRUE.equals(target.get("selected")) && latestStatus.equals("APPROVED")) approved++;
            targetViews.add(object("organization", object("id", target.get("id").toString(), "code", target.get("code"), "name", target.get("name")),
                    "selected",target.get("selected"),"recipients",recipients(((Number)target.get("id")).longValue()),
                    "latestSubmittedSubmissionId", latestId == null ? null : latestId.toString(),
                    "latestStatus", latestStatus, "draftCount", target.get("draft_count"),
                    "latestDraftSubmissionId", target.get("draft_id") == null ? null : target.get("draft_id").toString(),
                    "latestDraftParsingStatus", target.get("draft_parsing_status")));
        }
        var dto = object("id", row.get("id").toString(), "annualPlanId", row.get("annual_plan_id").toString(),
                "type", row.get("type"), "year", row.get("year"), "month", row.get("month"),
                "status", row.get("status"), "message", row.get("message"), "dueAt", instant(row.get("due_at")),
                "requestedBy", object("id", row.get("requested_by").toString(), "name", row.get("requester_name")),
                "requestedAt", instant(row.get("requested_at")), "targetCount", targets.size(),
                "requiredOrganizationCount",targets.size(),"selectedTargetCount",targets.stream().filter(target->Boolean.TRUE.equals(target.get("selected"))).count(),
                "submittedCount", submitted, "approvedCount", approved);
        dto.put("stateToken", hash(json(List.of(dto, targets.stream().map(t -> t.get("id")).toList())).getBytes(StandardCharsets.UTF_8)));
        if (detail) dto.put("targets", targetViews);
        if (actor.role().equals("ORG_MANAGER") && !targetViews.isEmpty()) dto.put("myWorkflow", workflowView(actor, row, targetViews.getFirst()));
        return dto;
    }

    /** 조직 요청함에 실제 제출·초안·소명 상태와 새 업로드를 막는 기존 업무 조건을 연결한다.
     * Args: actor 본인 조직 담당자, request 요청 행, target 본인 조직 대상 DTO.
     * Returns: 최신 제출/초안 ID·소명 수·canUpload·차단 코드/관련 제출 ID.
     * Raises: DB 또는 저장 검증 근거 오류. Side Effects: 본인 조직 자료만 조회하며 상태를 변경하지 않는다.
     */
    private Map<String, Object> workflowView(Actor actor, Map<String, Object> request, Map<String, Object> target) {
        var workflow = object("latestSubmittedSubmissionId", target.get("latestSubmittedSubmissionId"),
                "latestStatus", target.get("latestStatus"), "latestDraftSubmissionId", target.get("latestDraftSubmissionId"),
                "latestDraftParsingStatus", target.get("latestDraftParsingStatus"),
                "requiredCount", null, "unansweredCount", null, "pendingReviewCount", null,
                "canUpload", true, "blockingCode", null, "blockingSubmissionId", null);
        Object selected = "PENDING".equals(target.get("latestStatus")) ? target.get("latestSubmittedSubmissionId")
                : target.get("latestDraftSubmissionId") != null ? target.get("latestDraftSubmissionId") : target.get("latestSubmittedSubmissionId");
        if (selected != null) {
            var readiness = validation.readiness(object("id", selected));
            for (String field : List.of("requiredCount", "unansweredCount", "pendingReviewCount")) workflow.put(field, readiness.get(field));
        }
        long organizationId = id(actor.organization().id());
        var pending = rows("SELECT id FROM submission WHERE organization_id=? AND request_id=? AND status='PENDING' ORDER BY id DESC LIMIT 1", organizationId, request.get("id"));

        String blocked = null;
        Object blockingSubmission = null;
        if (!pending.isEmpty()) { blocked = "PENDING_ALREADY_EXISTS"; blockingSubmission = pending.getFirst().get("id").toString(); }
        else if (!"OPEN".equals(request.get("status"))) blocked = "REQUEST_CLOSED";
        else if ("ANNUAL_PLAN".equals(request.get("type")) && !"COLLECTING".equals(request.get("plan_status"))) blocked = "ANNUAL_PLAN_LOCKED";
        else if ("MONTHLY_ACTUAL".equals(request.get("type"))) {
            if (!"CONFIRMED".equals(request.get("plan_status"))) blocked = "ANNUAL_PLAN_NOT_CONFIRMED";
            else if (count("""
                    SELECT COUNT(*) FROM request_target t JOIN submission_request r ON r.id=t.request_id
                    WHERE r.annual_plan_id=? AND r.type='ANNUAL_PLAN' AND t.organization_id=?
                      AND t.excluded=FALSE AND t.confirmed_plan_submission_id IS NOT NULL
                    """, request.get("annual_plan_id"), organizationId) == 0) blocked = "PLAN_SOURCE_MISSING";
        }
        if (blocked == null && count("""
                SELECT COUNT(*) FROM submission s JOIN parsing_job j ON j.submission_id=s.id
                WHERE s.organization_id=? AND j.status IN ('QUEUED','RUNNING')
                """, organizationId) >= 10) blocked = "PARSER_BUSY";
        workflow.put("canUpload", blocked == null);
        workflow.put("blockingCode", blocked);
        workflow.put("blockingSubmissionId", blockingSubmission);
        return workflow;
    }

    /** 저장된 제출 헤더를 공개 요약으로 변환한다.
     * Args: row 내부 행. Returns: 제출 요약. Raises: 없음. Side Effects: 없음.
     */
    private Map<String, Object> submissionSummary(Map<String, Object> row) {
        String versionLabel = row.get("seq") == null ? null
                : String.format(Locale.ROOT, "%04d-%s-%02d", ((Number) row.get("year")).intValue(),
                        row.get("type").equals("ANNUAL_PLAN") ? "PLAN"
                                : String.format(Locale.ROOT, "%02d", ((Number) row.get("month")).intValue()),
                        ((Number) row.get("seq")).intValue());
        return object("id", row.get("id").toString(), "requestId", row.get("request_id").toString(),
                "organizationId", row.get("organization_id").toString(), "type", row.get("type"),
                "year", row.get("year"), "month", row.get("month"), "status", row.get("status"), "seq", row.get("seq"),
                "versionLabel", versionLabel, "originalFilename", row.get("original_filename"),
                "uploadedBy", object("id", row.get("uploaded_by").toString(), "name", row.get("uploader_name")),
                "uploadedAt", instant(row.get("uploaded_at")),
                "submittedBy", row.get("submitted_by") == null ? null
                        : object("id", row.get("submitted_by").toString(), "name", row.get("submitter_name")),
                "submittedAt", instant(row.get("submitted_at")),
                "reviewedBy", row.get("reviewed_by") == null ? null
                        : object("id", row.get("reviewed_by").toString(), "name", row.get("reviewer_name")),
                "reviewedAt", instant(row.get("reviewed_at")), "parsingStatus", row.get("parsing_status"),
                "requiredCount", null, "unansweredCount", null, "pendingReviewCount", null);
    }

    /** 현재 파싱·업무 검증과 제출 준비 상태를 조립한다.
     * Args: row 내부 행. Returns: 제출 상세 DTO. Raises: 저장 결과 손상. Side Effects: 검증 DB 읽기.
     */
    private Map<String, Object> submissionView(Map<String, Object> row) {
        var dto = submissionSummary(row);
        dto.remove("parsingStatus");
        dto.remove("requiredCount");
        dto.remove("unansweredCount");
        dto.remove("pendingReviewCount");
        String parsingStatus = row.get("parsing_status").toString();
        dto.put("rejectReason", row.get("reject_reason"));
        dto.put("parsing", object("jobId", row.get("job_id").toString(), "status", parsingStatus,
                "attemptNo", row.get("attempt_no"), "parserVersion", row.get("parser_version"),
                "contractVersion", row.get("contract_version"), "mappingVersion", row.get("mapping_version"),
                "retryAllowed", retryAllowed(row), "errorCode", row.get("error_code")));
        dto.put("comparisonContext", validation.comparisonContext(row.get("id").toString()));
        dto.put("readiness", validation.readiness(row));
        dto.put("stateToken", token(row));
        return dto;
    }

    /** 활성 작업·검증 변경을 탐지하는 불투명 토큰을 계산한다.
     * Args: row 내부 제출·작업 행. Returns: SHA-256 토큰. Raises: DB 오류. Side Effects: 검증 참조 읽기.
     */
    private String token(Map<String, Object> row) {
        return hash(json(object("id", row.get("id"), "status", row.get("status"), "seq", row.get("seq"),
                "reviewedBy", row.get("reviewed_by"), "reviewedAt", instant(row.get("reviewed_at")), "rejectReason", row.get("reject_reason"), "job", row.get("job_id"),
                "attempt", row.get("attempt_no"), "parsing", row.get("parsing_status"), "error", row.get("error_code"),
                "result", row.get("result_object_key"), "validation", validation.validationFingerprint(row.get("id").toString()))).getBytes(StandardCharsets.UTF_8));
    }

    /** 동일 바이트를 다시 실행할 수 있는 운영 오류만 허용한다.
     * Args: row 내부 행. Returns: 재시도 가능 여부. Raises: 없음. Side Effects: 없음.
     */
    private boolean retryAllowed(Map<String, Object> row) {
        return row.get("status").equals("DRAFT") && row.get("parsing_status").equals("FAILED")
                && row.get("error_code") != null && RETRYABLE.contains(row.get("error_code").toString());
    }

    /** 현재 결과 객체를 읽는다.
     * Args: row 제출 행. Returns: 프로토콜 JSON. Raises: IllegalStateException 저장 자료 손상. Side Effects: DB 읽기.
     */
    private JsonNode readResult(Map<String, Object> row) {
        if (row.get("result_object_key") == null) {
            return mapper.valueToTree(object("diagnostics", List.of(object("code", row.get("error_code"),
                    "severity", "ERROR", "stage", "PARSING", "message", "파싱 작업이 중단되었습니다.", "source", null))));
        }
        byte[] bytes = jdbc.sql("SELECT payload FROM stored_object WHERE object_key=?").param(row.get("result_object_key")).query(byte[].class).single();
        try {
            return mapper.readTree(bytes);
        } catch (Exception exception) {
            throw new IllegalStateException("저장 결과를 해석할 수 없습니다.");
        }
    }

    /** 목록 공통 필터를 바인딩한다.
     * Args: query 필터, params 바인딩 값, submissions 제출 상태 여부.
     * Returns: SQL 조건. Raises: ApiException 유효하지 않은 필터. Side Effects: params에 바인딩 추가.
     */
    private String filters(Map<String, String> query, List<Object> params, boolean submissions) {
        StringBuilder sql = new StringBuilder();
        if (query.containsKey("month") && !query.containsKey("year")) throw invalid("month는 year와 함께 사용합니다.");
        for (String field : List.of("year", "month", "type", "status")) {
            if (!query.containsKey(field)) continue;
            String value = query.get(field);
            Object parameter = value;
            if (field.equals("year") || field.equals("month")) {
                try {
                    int number = Integer.parseInt(value);
                    if (field.equals("year") ? number < 2000 || number > 9999 : number < 1 || number > 12) throw new NumberFormatException();
                    parameter = number;
                } catch (NumberFormatException exception) {
                    throw invalid("연도·월 범위를 확인하세요.");
                }
            } else if (!(field.equals("type") ? Set.of("ANNUAL_PLAN", "MONTHLY_ACTUAL")
                    : submissions ? Set.of("DRAFT", "PENDING", "REJECTED", "APPROVED") : Set.of("OPEN", "CLOSED")).contains(value)) {
                throw invalid("유형·상태 필터를 확인하세요.");
            }
            String column = field.equals("year") ? "p.year" : field.equals("status") && submissions ? "s.status" : "r." + field;
            sql.append(" AND ").append(column).append("=?");
            params.add(parameter);
        }
        return sql.toString();
    }

    /** DB 목록을 한 페이지로 제한한다.
     * Args: actor 행위자, query 조회, route 범위명, sql 고정 SQL, params 바인딩, convert DTO 변환.
     * Returns: items/nextCursor. Raises: ApiException 커서/limit 오류. Side Effects: DB 읽기.
     */
    private Map<String, Object> page(Actor actor, Map<String, String> query, String route, String sql,
            List<Object> params, java.util.function.Function<Map<String, Object>, Map<String, Object>> convert) {
        int limit = limit(query);
        String scope = scope(actor, query, route);
        int offset = offset(query.get("cursor"), scope);
        var bindings = new ArrayList<>(params);
        bindings.add(limit + 1);
        bindings.add(offset);
        var found = jdbc.sql(sql + " LIMIT ? OFFSET ?").params(bindings).query().listOfRows();
        boolean hasMore = found.size() > limit;
        return object("items", found.stream().limit(limit).map(convert).toList(),
                "nextCursor", hasMore ? cursor(offset + limit, scope) : null);
    }

    /** 최대 100의 페이지 크기를 적용한다.
     * Args: query 필터. Returns: 기본 50 또는 지정 크기. Raises: ApiException 범위 오류. Side Effects: 없음.
     */
    private int limit(Map<String, String> query) {
        try {
            int value = Integer.parseInt(query.getOrDefault("limit", "50"));
            if (value < 1 || value > 100) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException exception) {
            throw invalid("limit는 1~100입니다.");
        }
    }

    /** 커서를 사용자·필터에 결합한다.
     * Args: actor 행위자, query 필터, route 범위명. Returns: 정규 범위. Raises: 없음. Side Effects: 없음.
     */
    private String scope(Actor actor, Map<String, String> query, String route) {
        var normalized = new TreeMap<>(query);
        normalized.remove("cursor");
        return route + ":" + actor.id() + ":" + actor.role() + ":" + (actor.organization() == null ? "" : actor.organization().id()) + ":" + json(normalized);
    }

    /** 알 수 없는 쿼리 이름을 거부한다.
     * Args: query 실제 쿼리, allowed 허용 이름. Returns: 없음. Raises: ApiException 미정의 필드. Side Effects: 없음.
     */
    public void checkQuery(Map<String, String> query, Set<String> allowed) {
        if (!allowed.containsAll(query.keySet())) throw invalid("정의되지 않은 쿼리 필드입니다.");
    }

    /** 생성된 identity를 반환한다.
     * Args: sql INSERT, params 바인딩. Returns: 새 ID. Raises: DB 오류. Side Effects: DB 행 추가.
     */
    private long insert(String sql, Object... params) {
        var holder = new GeneratedKeyHolder();
        jdbc.sql(sql).params(params).update(holder, "id");
        return holder.getKey().longValue();
    }

    /** 내부 행을 조회한다.
     * Args: sql 고정 조회, params 바인딩. Returns: 행 목록. Raises: DB 오류. Side Effects: DB 읽기.
     */
    private List<Map<String, Object>> rows(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query().listOfRows();
    }

    /** 조건에 맞는 행 수를 조회한다.
     * Args: sql COUNT 조회, params 바인딩. Returns: 개수. Raises: DB 오류. Side Effects: DB 읽기.
     */
    private long count(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(Long.class).single();
    }

    /** 응답·요청을 JSON 문자열로 직렬화한다.
     * Args: value 직렬화 대상. Returns: JSON. Raises: IllegalStateException 계약 직렬화 실패. Side Effects: 없음.
     */
    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("JSON 계약을 직렬화할 수 없습니다.");
        }
    }

    /** UTC 서버 시각을 반환한다. Args: 없음. Returns: 현재 시각. Raises: 없음. Side Effects: 시계 읽기. */
    private OffsetDateTime now() { return OffsetDateTime.now(ZoneOffset.UTC); }

    /** 400 오류를 만든다. Args: message 공개 메시지. Returns: 오류. Raises: 없음. Side Effects: 없음. */
    private ApiException invalid(String message) { return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message); }

    /** 409 오류를 만든다. Args: code 코드, message 공개 메시지. Returns: 오류. Raises: 없음. Side Effects: 없음. */
    private ApiException conflict(String code, String message) { return new ApiException(HttpStatus.CONFLICT, code, message); }

    /** 리소스 존재를 숨기는 404 오류를 만든다. Args: 없음. Returns: 오류. Raises: 없음. Side Effects: 없음. */
    private ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "자료를 찾을 수 없습니다."); }

    /** 요청 생성 입력. null 월은 연간 요청에만 허용한다. */
    public record RequestInput(
            @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Pattern(regexp="ANNUAL_PLAN|MONTHLY_ACTUAL") String type,
            @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Min(2000) @jakarta.validation.constraints.Max(9999) Integer year,
            @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(12) Integer month,
            @jakarta.validation.constraints.Size(max=5000) String message,
            OffsetDateTime dueAt,
            @jakarta.validation.constraints.Size(min=1,max=1000) List<@jakarta.validation.constraints.NotBlank String> organizationIds) {
        /** 기존 전 조직 요청 호출을 유지한다. Args: type 유형, year 연도, month 월, message 안내, dueAt 기한. Returns: 없음. Raises: 없음. Side Effects: 없음. */
        public RequestInput(String type,Integer year,Integer month,String message,OffsetDateTime dueAt) { this(type,year,month,message,dueAt,null); }
    }

    /** 기존 원본의 재파싱 기대 상태. */
    public record RetryInput(@jakarta.validation.constraints.NotBlank String expectedStateToken,
            @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Min(1) Integer expectedAttemptNo) {}

    /** 권한 확인 후 전달되는 원본. 자동 생성 접근자는 바이트 복사본을 별도 만들지 않는다. */
    public record OriginalFile(String filename, byte[] bytes) {}
}
