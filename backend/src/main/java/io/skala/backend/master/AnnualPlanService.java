package io.skala.backend.master;

import static io.skala.backend.submission.ApiData.*;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.skala.backend.common.ApiException;
import io.skala.backend.foundation.CommandExecutor;
import io.skala.backend.security.Actor;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 전 조직의 승인 계획본과 명시한 전사 Contingency를 함께 확정·잠근다. */
@Service
public class AnnualPlanService {
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final CommandExecutor commands;
    private final AnnualMasterService annualMasters;

    /** 확정 명령과 고정 계획 근거 조회 의존성을 연결한다.
     * Args: jdbc DB, mapper JSON, commands 멱등 트랜잭션, annualMasters 승인 계획 검증기.
     * Returns: 없음. Raises: 없음. Side Effects: 없음.
     */
    public AnnualPlanService(JdbcClient jdbc, ObjectMapper mapper, CommandExecutor commands, AnnualMasterService annualMasters) {
        this.jdbc = jdbc; this.mapper = mapper; this.commands = commands; this.annualMasters = annualMasters;
    }

    /** 연간 계획 상태와 최신 완료 제출의 확정 후보를 읽는다.
     * Args: actor 기획자, year 연도 문자열, query 빈 쿼리. Returns: AnnualPlanView.
     * Raises: 권한·입력·404 오류. Side Effects: 반복 가능한 DB 읽기.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> get(Actor actor, String year, Map<String, String> query) {
        actor.requireRole("PLANNER"); if (!query.isEmpty()) throw invalid("조회 조건을 받지 않습니다.");
        var plan = plan(year(year)); return view(plan);
    }

    /** 명시한 금액과 최신 토큰으로 전 조직 계획본을 원자적으로 고정한다.
     * Args: actor 기획자, key 멱등키, year 연도, input 현재 토큰과 0 이상 Contingency.
     * Returns: 확정 AnnualPlanView의 명령 결과. Raises: 권한·입력·상태·근거·토큰 오류.
     * Side Effects: 계획 잠금, 계획본 참조·Contingency·확정자/시각·요청 CLOSED와 멱등 응답을 같은 트랜잭션에 저장한다.
     */
    public CommandExecutor.Result confirm(Actor actor, String key, String year, ConfirmInput input) {
        actor.requireRole("PLANNER"); int targetYear = year(year); BigDecimal amount = contingency(input == null ? null : input.contingencyAmount());
        if (input.expectedStateToken() == null || input.expectedStateToken().isBlank()) throw invalid("현재 상태 토큰이 필요합니다.");
        String route = "/api/v1/annual-plans/" + targetYear + "/confirm";
        return commands.execute(actor, "POST " + route, key, hash(json(input).getBytes(StandardCharsets.UTF_8)), () -> {
            var plan = plan(targetYear);
            jdbc.sql("SELECT id FROM annual_plan WHERE id=? FOR UPDATE").param(plan.get("id")).query(Long.class).single();
            plan = plan(targetYear);
            if (!"COLLECTING".equals(plan.get("status"))) throw conflict("ANNUAL_PLAN_LOCKED", "이미 확정된 계획과 Contingency는 변경할 수 없습니다.");
            if (!input.expectedStateToken().equals(view(plan).get("stateToken"))) throw conflict("STATE_TOKEN_MISMATCH", "계획 후보가 변경되었습니다. 최신 상태를 확인해 주세요.");
            var sources = candidates(plan); var selected = new TreeMap<Long, Long>(); var errors = new ArrayList<ApiException.FieldError>();
            for (var row : sources) {
                if (!"APPROVED".equals(row.get("latest_status"))) errors.add(new ApiException.FieldError("organizationId[" + row.get("organization_id") + "]", "PLAN_APPROVAL_REQUIRED"));
                else selected.put(number(row.get("organization_id")), number(row.get("latest_id")));
            }
            if (sources.isEmpty() || !errors.isEmpty()) throw new ApiException(HttpStatus.CONFLICT, "PLAN_APPROVALS_INCOMPLETE", "모든 필수 조직의 최신 완료 제출을 승인해야 합니다.", errors);
            annualMasters.validatedPlanReports(plan.get("request_id").toString(), selected);
            for (var entry : selected.entrySet()) jdbc.sql("UPDATE request_target SET confirmed_plan_submission_id=? WHERE request_id=? AND organization_id=? AND excluded=FALSE")
                    .params(entry.getValue(), plan.get("request_id"), entry.getKey()).update();
            jdbc.sql("UPDATE annual_plan SET status='CONFIRMED',contingency_amount=?,confirmed_by=?,confirmed_at=? WHERE id=?")
                    .params(amount, id(actor.id()), OffsetDateTime.now(ZoneOffset.UTC), plan.get("id")).update();
            jdbc.sql("UPDATE submission_request SET status='CLOSED' WHERE id=?").param(plan.get("request_id")).update();
            return new CommandExecutor.Result(200, json(view(plan(targetYear))), "/api/v1/annual-plans/" + targetYear);
        });
    }

    /** 월간 비교에 사용할 확정 조직별 계획본과 불변 보고값을 읽는다.
     * Args: year 대상 연도. Returns: 확정 계획 메타·조직별 참조·보고값.
     * Raises: ApiException 미확정·계획본 누락·근거 오류. Side Effects: DB 읽기만 수행한다.
     */
    ConfirmedPlan confirmed(int year) {
        var plan = plan(year); if (!"CONFIRMED".equals(plan.get("status"))) throw conflict("ANNUAL_PLAN_NOT_CONFIRMED", "연간 계획을 먼저 확정해야 합니다.");
        var selected = new TreeMap<Long, Long>();
        for (var row : candidates(plan)) {
            if (row.get("confirmed_plan_submission_id") == null) throw conflict("PLAN_SOURCE_MISSING", "확정 조직 계획본이 없습니다.");
            selected.put(number(row.get("organization_id")), number(row.get("confirmed_plan_submission_id")));
        }
        if (selected.isEmpty()) throw conflict("PLAN_SOURCE_MISSING", "확정 조직 계획본이 없습니다.");
        return new ConfirmedPlan(plan, selected, annualMasters.validatedPlanReports(plan.get("request_id").toString(), selected));
    }

    /** 확정 전후 계획 뷰와 후보 집합을 포함한 토큰을 만든다.
     * Args: plan 연간 계획·요청 행. Returns: 공개 DTO. Raises: DB·JSON 오류. Side Effects: DB 읽기.
     */
    private Map<String, Object> view(Map<String, Object> plan) {
        var rows = candidates(plan); var sources = new ArrayList<Map<String, Object>>(); long approved = 0;
        for (var row : rows) {
            boolean candidate = "APPROVED".equals(row.get("latest_status")); if (candidate) approved++;
            sources.add(object("organizationId", row.get("organization_id").toString(), "candidateSubmissionId", candidate ? row.get("latest_id").toString() : null,
                    "confirmedPlanSubmissionId", textId(row.get("confirmed_plan_submission_id")), "latestStatus", row.get("latest_status") == null ? "NOT_SUBMITTED" : row.get("latest_status")));
        }
        var result = object("id", plan.get("id").toString(), "year", plan.get("year"), "status", plan.get("status"),
                "contingencyAmount", plan.get("contingency_amount") == null ? null : ((BigDecimal) plan.get("contingency_amount")).toPlainString(),
                "confirmedBy", plan.get("confirmed_by") == null ? null : object("id", textId(plan.get("confirmed_by")), "name", plan.get("confirmer_name")),
                "confirmedAt", instant(plan.get("confirmed_at")), "targetCount", sources.size(), "approvedCount", approved, "sources", sources);
        result.put("stateToken", hash(json(object("view", result, "candidates", rows)).getBytes(StandardCharsets.UTF_8))); return result;
    }

    /** 대상 조직의 최신 완료 회차와 확정 참조를 읽는다.
     * Args: plan 연간 계획·요청 행. Returns: 고정 조직 ID 순 후보. Raises: DB 오류. Side Effects: DB 읽기.
     */
    private List<Map<String, Object>> candidates(Map<String, Object> plan) {
        return jdbc.sql("""
                SELECT t.organization_id,t.confirmed_plan_submission_id,s.id AS latest_id,s.status AS latest_status,
                    s.source_sha256,v.validation_result_object_key
                FROM request_target t LEFT JOIN submission s ON s.request_id=t.request_id AND s.organization_id=t.organization_id
                    AND s.seq=(SELECT MAX(n.seq) FROM submission n WHERE n.request_id=t.request_id AND n.organization_id=t.organization_id AND n.status<>'DRAFT')
                LEFT JOIN submission_validation v ON v.submission_id=s.id
                WHERE t.request_id=? AND t.excluded=FALSE ORDER BY t.organization_id
                """).param(plan.get("request_id")).query().listOfRows();
    }

    /** 해당 연간 요청이 있는 계획을 읽는다.
     * Args: year 연도. Returns: 계획·요청 ID·확정자 이름. Raises: 404. Side Effects: DB 읽기.
     */
    private Map<String, Object> plan(int year) {
        var rows = jdbc.sql("""
                SELECT p.*,r.id AS request_id,u.name AS confirmer_name FROM annual_plan p
                JOIN submission_request r ON r.annual_plan_id=p.id AND r.type='ANNUAL_PLAN'
                LEFT JOIN app_user u ON u.id=p.confirmed_by WHERE p.year=?
                """).param(year).query().listOfRows();
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "연간 계획 요청을 찾을 수 없습니다."); return rows.getFirst();
    }

    /** 연도 입력을 검증한다.
     * Args: value 연도 문자열. Returns: 2000~9999 정수. Raises: 입력 오류. Side Effects: 없음.
     */
    static int year(String value) {
        if (value == null || !value.matches("[2-9][0-9]{3}")) throw invalid("연도 형식이 올바르지 않습니다."); return Integer.parseInt(value);
    }

    /** Contingency의 명시적 십진 문자열과 저장 정밀도를 검증한다.
     * Args: value 필수 원 단위 금액. Returns: 0 이상 NUMERIC(30,6) 금액. Raises: 입력 오류. Side Effects: 없음.
     */
    private BigDecimal contingency(String value) {
        if (value == null || !value.matches("(?:0|[1-9][0-9]{0,23})(?:\\.[0-9]{1,6})?")) throw invalid("Contingency는 0 이상의 원 단위 십진 문자열로 입력해야 합니다."); return new BigDecimal(value);
    }

    /** 내부 DTO를 JSON으로 바꾼다.
     * Args: value DTO. Returns: JSON. Raises: 직렬화 오류. Side Effects: 없음.
     */
    private String json(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception exception) { throw new IllegalStateException("계획 응답 직렬화 실패", exception); } }
    /** DB 정수를 읽는다. Args: value 정수. Returns: long. Raises: DB 타입 오류. Side Effects: 없음. */
    private long number(Object value) { return ((Number) value).longValue(); }
    /** nullable ID를 변환한다. Args: value ID. Returns: 문자열/null. Raises: 없음. Side Effects: 없음. */
    private String textId(Object value) { return value == null ? null : value.toString(); }
    /** 입력 오류를 만든다. Args: message 공개 설명. Returns: 400. Raises: 없음. Side Effects: 없음. */
    private static ApiException invalid(String message) { return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message); }
    /** 상태 오류를 만든다. Args: code 코드, message 설명. Returns: 409. Raises: 없음. Side Effects: 없음. */
    private ApiException conflict(String code, String message) { return new ApiException(HttpStatus.CONFLICT, code, message); }
    /** 확정 입력이다. 자동 접근자는 메모리 값만 반환하며 금액에는 기본값이 없다. */
    public record ConfirmInput(@NotBlank String expectedStateToken, @NotBlank String contingencyAmount) {
        /** 금액을 JSON 숫자에서 문자열로 강제 변환하지 않고 명시적 문자열 입력만 받는다.
         * Args: input HTTP JSON 객체. Returns: 확정 입력. Raises: 미지원 필드·숫자·null·누락 입력 오류. Side Effects: 없음.
         */
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static ConfirmInput read(JsonNode input) {
            if (input == null || !input.isObject() || input.size() != 2 || !input.path("expectedStateToken").isTextual() || !input.path("contingencyAmount").isTextual()) throw invalid("상태 토큰과 Contingency를 문자열로 입력해야 합니다.");
            return new ConfirmInput(input.path("expectedStateToken").asText(), input.path("contingencyAmount").asText());
        }
    }
    /** 내부 확정 계획 묶음이다. 자동 접근자는 고정 참조·검증된 값만 반환한다. */
    record ConfirmedPlan(Map<String, Object> plan, Map<Long, Long> sources, List<Map<String, Object>> reports) {}
}
