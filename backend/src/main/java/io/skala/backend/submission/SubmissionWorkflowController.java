package io.skala.backend.submission;

import io.skala.backend.foundation.CommandExecutor;
import io.skala.backend.security.ActorResolver;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 조직 담당자의 명시적 업무 검증·최종 제출 HTTP 진입점이다. */
@RestController
@RequestMapping("/api/v1/submissions/{submissionId}")
public class SubmissionWorkflowController {
    private final SubmissionWorkflowService workflow;
    private final SubmissionService submissions;
    private final ActorResolver actors;

    /** 업무 명령과 기존 쿼리·인증 경계를 받는다.
     * Args: workflow 명령 서비스, submissions 쿼리 검증, actors 현재 사용자 해석기.
     * Returns: 없음. Raises: 없음. Side Effects: 없음.
     */
    public SubmissionWorkflowController(SubmissionWorkflowService workflow, SubmissionService submissions, ActorResolver actors) {
        this.workflow = workflow;
        this.submissions = submissions;
        this.actors = actors;
    }

    /** 명시한 초안에 현재 업무 검증을 실행한다.
     * Args: submissionId 초안, key 멱등키, input 기대 토큰, query 빈 쿼리.
     * Returns: 검증 결과 200 또는 저장 응답 재전달. Raises: 인증·권한·입력·상태 오류.
     * Side Effects: 서비스 트랜잭션에서 검증 근거와 선택된 금융행을 저장한다.
     */
    @PostMapping("/validation")
    public ResponseEntity<String> validate(@PathVariable String submissionId,
            @RequestHeader(value="Idempotency-Key", required=false) String key,
            @Valid @RequestBody SubmissionWorkflowService.StateInput input, @RequestParam Map<String, String> query) {
        submissions.checkQuery(query, Set.of());
        return command(workflow.validate(actors.current(), submissionId, key, input));
    }

    /** 검증·요청 조건을 다시 확인하고 최종 제출한다.
     * Args: submissionId 초안, key 멱등키, input 기대 토큰, query 빈 쿼리.
     * Returns: PENDING 상세 200 또는 저장 응답 재전달. Raises: 인증·권한·입력·상태 오류.
     * Side Effects: 제출 회차·제출자·제출 시각과 멱등 응답을 원자적으로 저장한다.
     */
    @PostMapping("/submit")
    public ResponseEntity<String> submit(@PathVariable String submissionId,
            @RequestHeader(value="Idempotency-Key", required=false) String key,
            @Valid @RequestBody SubmissionWorkflowService.StateInput input, @RequestParam Map<String, String> query) {
        submissions.checkQuery(query, Set.of());
        return command(workflow.submit(actors.current(), submissionId, key, input));
    }

    /** 멱등 저장 결과의 상태·위치·재전달 헤더를 복원한다.
     * Args: result 명령 결과. Returns: JSON HTTP 응답.
     * Raises: 없음. Side Effects: 없음.
     */
    private ResponseEntity<String> command(CommandExecutor.Result result) {
        var response = ResponseEntity.status(result.status()).contentType(MediaType.APPLICATION_JSON);
        if (!result.location().isBlank()) response.header(HttpHeaders.LOCATION, result.location());
        if (result.replayed()) response.header("Idempotency-Replayed", "true");
        return response.body(result.body());
    }
}
