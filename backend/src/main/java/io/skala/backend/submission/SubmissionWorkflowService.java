package io.skala.backend.submission;

import static io.skala.backend.submission.ApiData.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.skala.backend.common.ApiException;
import io.skala.backend.foundation.CommandExecutor;
import io.skala.backend.security.Actor;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** 초안 검증과 최종 제출만 담당한다. 승인·반려·마스터를 만들지 않는다. */
@Service
public class SubmissionWorkflowService {
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final CommandExecutor commands;
    private final SubmissionService submissions;
    private final SubmissionValidationService validation;

    /** 검증·조회와 같은 DB 트랜잭션을 사용하는 명령 의존성을 연결한다.
     * Args: jdbc DB, mapper JSON, commands 멱등 경계, submissions 조회, validation 검증·적재.
     * Returns: 없음. Raises: 없음. Side Effects: 없음.
     */
    public SubmissionWorkflowService(JdbcClient jdbc, ObjectMapper mapper, CommandExecutor commands,
            SubmissionService submissions, SubmissionValidationService validation) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.commands = commands;
        this.submissions = submissions;
        this.validation = validation;
    }

    /** 현재 파싱 결과를 검증하고 검증 근거와 선택한 월별 상세를 원자적으로 채택한다.
     * Args: actor 본인 조직 담당자, submissionId 초안 ID, key 멱등키, input 현재 상태 토큰.
     * Returns: 제출 상세와 빈 검토 변경 배열; 업무 오류는 상세의 blocker로 반환한다.
     * Raises: ApiException 권한·토큰·파싱·초안 상태 오류, DB/직렬화 오류.
     * Side Effects: 조직·제출·계획 잠금, 검증 서비스의 DB 적재, 멱등 응답 저장. 파일 쓰기 없음.
     */
    public CommandExecutor.Result validate(Actor actor, String submissionId, String key, StateInput input) {
        actor.requireRole("ORG_MANAGER");
        submissions.submission(actor, submissionId);
        String route = "/api/v1/submissions/" + submissionId + "/validation";
        return commands.execute(actor, "POST " + route, key, fingerprint(input), () -> {
            Map<String, Object> row = lockDraft(actor, submissionId, input);
            lockAnnualPlan(row);
            validation.validateLocked(actor, submissionId);
            return new CommandExecutor.Result(200, json(object("submission", submissions.submission(actor, submissionId),
                    "changedReviewItemIds", List.of(), "answerRequiredReviewItemIds", List.of())),
                    "/api/v1/submissions/" + submissionId);
        });
    }

    /** 검증된 전체 초안에만 제출 회차·제출자를 부여하고 PENDING으로 잠근다.
     * Args: actor 본인 조직 담당자, submissionId 초안 ID, key 멱등키, input 최신 상태 토큰.
     * Returns: PENDING 제출 상세 또는 동일 명령의 최초 성공 응답.
     * Raises: ApiException 상태·기준·필수 소명·검증·조직 대기 제한 오류, DB/직렬화 오류.
     * Side Effects: 조직·제출·계획·요청 잠금, 제출 헤더와 멱등 응답의 원자적 갱신. 승인·파일 쓰기 없음.
     */
    public CommandExecutor.Result submit(Actor actor, String submissionId, String key, StateInput input) {
        actor.requireRole("ORG_MANAGER");
        submissions.submission(actor, submissionId);
        String route = "/api/v1/submissions/" + submissionId + "/submit";
        return commands.execute(actor, "POST " + route, key, fingerprint(input), () -> {
            Map<String, Object> row = lockDraft(actor, submissionId, input);
            checkSubmissionWindow(actor, row);
            validation.assertReadyForSubmit(actor, submissionId);
            int seq = jdbc.sql("SELECT COALESCE(MAX(seq),0)+1 FROM submission WHERE request_id=? AND organization_id=?")
                    .params(row.get("request_id"), row.get("organization_id")).query(Integer.class).single();
            int changed = jdbc.sql("""
                    UPDATE submission SET status='PENDING',seq=?,submitted_by=?,submitted_at=?
                    WHERE id=? AND status='DRAFT'
                    """).params(seq, id(actor.id()), OffsetDateTime.now(ZoneOffset.UTC), id(submissionId)).update();
            if (changed != 1) throw conflict("INVALID_SUBMISSION_STATE", "현재 초안은 제출할 수 없습니다.");
            return new CommandExecutor.Result(200, json(submissions.submission(actor, submissionId)),
                    "/api/v1/submissions/" + submissionId);
        });
    }

    /** 같은 조직의 명령을 직렬화하고 현재 초안·토큰·완료 파싱을 재확인한다.
     * Args: actor 조직 담당자, submissionId 조회 권한 확인된 ID, input 기대 상태.
     * Returns: 잠근 제출의 요청·조직 참조. Raises: ApiException 권한·상태·토큰·파싱 오류.
     * Side Effects: 호출 트랜잭션 동안 조직과 제출 행을 잠근다. 상태 변경 없음.
     */
    private Map<String, Object> lockDraft(Actor actor, String submissionId, StateInput input) {
        jdbc.sql("SELECT id FROM organization WHERE id=? FOR UPDATE")
                .param(id(actor.organization().id())).query(Long.class).single();
        var rows = jdbc.sql("SELECT * FROM submission WHERE id=? FOR UPDATE")
                .param(id(submissionId)).query().listOfRows();
        if (rows.isEmpty() || !actor.canAccessOrganization(rows.getFirst().get("organization_id").toString())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "자료를 찾을 수 없습니다.");
        }
        Map<String, Object> row = rows.getFirst();
        Map<String, Object> current = submissions.submission(actor, submissionId);
        if (!current.get("stateToken").equals(input.expectedStateToken())) {
            throw conflict("RESOURCE_CHANGED", "최신 제출 상태를 다시 조회하세요.");
        }
        if (!row.get("status").equals("DRAFT")) {
            throw conflict("INVALID_SUBMISSION_STATE", "최종 제출된 자료는 다시 검증하거나 제출할 수 없습니다.");
        }
        String parsingStatus = jdbc.sql("SELECT status FROM parsing_job WHERE submission_id=?")
                .param(id(submissionId)).query(String.class).single();
        if (!parsingStatus.equals("SUCCEEDED")) {
            throw conflict("PARSING_NOT_READY", "현재 파싱 결과를 확인한 뒤 검증·제출해 주세요.");
        }
        return row;
    }

    /** 제출 시점의 요청·연간 상태·대기·다른 기간 재제출 제한을 확인한다.
     * Args: actor 본인 조직 담당자, submission 잠근 제출 행.
     * Returns: 없음. Raises: ApiException 제출 가능 기간·대상·대기 조건 충돌.
     * Side Effects: 계획·요청 행을 잠그고 관련 제출을 읽는다. 데이터 변경 없음.
     */
    private void checkSubmissionWindow(Actor actor, Map<String, Object> submission) {
        long requestId = ((Number) submission.get("request_id")).longValue();
        Map<String, Object> plan = lockAnnualPlan(submission);
        long planId = ((Number) plan.get("id")).longValue();
        String planStatus = plan.get("status").toString();
        var request = jdbc.sql("SELECT type,status FROM submission_request WHERE id=? FOR UPDATE")
                .param(requestId).query().singleRow();
        if (!request.get("status").equals("OPEN")) throw conflict("REQUEST_CLOSED", "닫힌 요청에는 최종 제출할 수 없습니다.");
        if (request.get("type").equals("ANNUAL_PLAN")) {
            if (!planStatus.equals("COLLECTING")) throw conflict("ANNUAL_PLAN_LOCKED", "확정된 연간 계획에는 제출할 수 없습니다.");
        } else {
            if (!planStatus.equals("CONFIRMED")) throw conflict("ANNUAL_PLAN_NOT_CONFIRMED", "확정된 연간 계획이 필요합니다.");
            if (count("""
                    SELECT COUNT(*) FROM request_target t JOIN submission_request r ON r.id=t.request_id
                    WHERE r.annual_plan_id=? AND r.type='ANNUAL_PLAN' AND t.organization_id=?
                      AND t.confirmed_plan_submission_id IS NOT NULL
                    """, planId, id(actor.organization().id())) == 0) {
                throw conflict("PLAN_SOURCE_MISSING", "해당 조직의 고정된 연간 계획본이 없습니다.");
            }
        }
        if (count("SELECT COUNT(*) FROM submission WHERE organization_id=? AND status='PENDING'",
                submission.get("organization_id")) > 0) {
            throw conflict("PENDING_ALREADY_EXISTS", "이 조직에 검토 중인 제출본이 있습니다.");
        }
        if (count("""
                SELECT COUNT(*) FROM submission rejected
                WHERE rejected.organization_id=? AND rejected.request_id<>? AND rejected.status='REJECTED'
                  AND rejected.seq=(SELECT MAX(latest.seq) FROM submission latest
                    WHERE latest.organization_id=rejected.organization_id
                      AND latest.request_id=rejected.request_id AND latest.status<>'DRAFT')
                """, submission.get("organization_id"), requestId) > 0) {
            throw conflict("RESUBMISSION_SCOPE_CONFLICT", "재제출 요청을 받은 기간부터 제출해 주세요.");
        }
    }

    /** 같은 연도의 보고·계정 항목 최초 생성과 연간 상태 검사를 직렬화한다.
     * Args: submission 조직·제출 잠금이 끝난 내부 행.
     * Returns: 잠근 계획의 id와 status. Raises: DB 조회·잠금 오류.
     * Side Effects: 호출 트랜잭션 동안 annual_plan 행을 잠그며 값을 변경하지 않는다.
     */
    private Map<String, Object> lockAnnualPlan(Map<String, Object> submission) {
        long planId = jdbc.sql("SELECT annual_plan_id FROM submission_request WHERE id=?")
                .param(submission.get("request_id")).query(Long.class).single();
        return jdbc.sql("SELECT id,status FROM annual_plan WHERE id=? FOR UPDATE")
                .param(planId).query().singleRow();
    }

    /** 상태 명령 입력을 정규 JSON 해시로 묶는다.
     * Args: input 기대 토큰. Returns: SHA-256 요청 지문.
     * Raises: IllegalStateException JSON 직렬화 실패. Side Effects: 없음.
     */
    private String fingerprint(StateInput input) { return hash(json(input).getBytes(StandardCharsets.UTF_8)); }

    /** 고정 SQL의 조건 행 수를 읽는다.
     * Args: sql COUNT 쿼리, params 바인딩 값. Returns: 조건에 맞는 개수.
     * Raises: DB 접근 오류. Side Effects: 같은 트랜잭션의 DB 읽기.
     */
    private long count(String sql, Object... params) { return jdbc.sql(sql).params(params).query(Long.class).single(); }

    /** 성공 응답과 입력을 JSON으로 직렬화한다.
     * Args: value 계약 DTO. Returns: JSON 문자열.
     * Raises: IllegalStateException 직렬화 실패. Side Effects: 없음.
     */
    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("업무 명령 응답을 직렬화하지 못했습니다.");
        }
    }

    /** 안전한 상태 충돌을 만든다.
     * Args: code 고정 오류 코드, message 공개 안내. Returns: HTTP 409 예외.
     * Raises: 없음. Side Effects: 없음.
     */
    private ApiException conflict(String code, String message) { return new ApiException(HttpStatus.CONFLICT, code, message); }

    /** 상태 변경 시 조회한 최신 토큰을 요구한다. 자동 생성 접근자는 추가 동작이 없다. */
    public record StateInput(@jakarta.validation.constraints.NotBlank String expectedStateToken) {}
}
