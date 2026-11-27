package io.skala.backend.domain.decisionworkflow.controller;

import static io.skala.backend.domain.decisionworkflow.dto.DecisionWorkflowDtos.*;

import io.skala.backend.domain.decisionagent.dto.DecisionAgentDtos.Baseline;
import io.skala.backend.domain.decisionagent.dto.DecisionAgentDtos.Items;
import io.skala.backend.domain.decisionworkflow.service.DecisionWorkflowService;
import io.skala.backend.domain.user.value.UserRole;
import io.skala.backend.global.idempotency.IdempotentCommandExecutor;
import io.skala.backend.global.security.AuthenticatedUser;
import io.skala.backend.global.security.CurrentUser;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 조직 인앱 요청과 기획자 검토·외부 결정 기록 API. 자동 발송이나 실제 임원 결재를 수행하지 않는다. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/decision-agent")
public class DecisionWorkflowController {
    private final DecisionWorkflowService service;
    private final CurrentUser currentUser;
    private final IdempotentCommandExecutor idempotency;

    /** 목적: 권한 범위의 요청을 조회한다. Args: analysisId 선택 분석 필터. Returns: 요청 목록. Raises: 권한 오류. Side Effects: DB 읽기. */
    @GetMapping("/work-items")
    public Items<WorkItem> list(@RequestParam(required = false) Long analysisId) {
        return service.list(currentUser.get(), analysisId);
    }

    /** 목적: 단일 요청을 조회한다. Args: id 요청. Returns: 요청 상세. Raises: 404 권한/미존재. Side Effects: DB 읽기. */
    @GetMapping("/work-items/{id}")
    public WorkItem get(@PathVariable long id) {
        return service.get(currentUser.get(), id);
    }

    /** 목적: 자기 조직의 후속 확정 기준을 조회한다. Args: id 요청. Returns: 후속 master 목록. Raises: 404 권한/미존재. Side Effects: DB 읽기. */
    @GetMapping("/work-items/{id}/followup-baselines")
    public Items<Baseline> followupBaselines(@PathVariable long id) {
        return service.followupBaselines(currentUser.get(), id);
    }

    /** 목적: 기획자가 확인 요청 초안을 멱등 생성한다. Args: analysisId 분석, key 멱등 키, request 초안. Returns: 201 요청. Raises: 권한/입력/409. Side Effects: DB 생성. */
    @PostMapping("/analyses/{analysisId}/work-items")
    public ResponseEntity<String> create(@PathVariable long analysisId, @RequestHeader(IdempotentCommandExecutor.HEADER) String key,
                                          @Valid @RequestBody CreateRequest request) {
        AuthenticatedUser actor = currentUser.get();
        actor.requireRole(UserRole.PLANNER);
        return idempotency.execute(actor.id(), "POST /api/v1/decision-agent/analyses/" + analysisId + "/work-items", key, request, () -> {
            WorkItem created = service.create(actor, analysisId, request);
            return ResponseEntity.created(URI.create("/api/v1/decision-agent/work-items/" + created.id())).body(created);
        });
    }

    /** 목적: 초안을 조직 요청함에 발행한다. Args: id 요청, key 멱등 키, request 버전. Returns: 발행 요청. Raises: 권한/409. Side Effects: 인앱 요청 DB 갱신, 외부 전송 없음. */
    @PostMapping("/work-items/{id}/issue")
    public ResponseEntity<String> issue(@PathVariable long id, @RequestHeader(IdempotentCommandExecutor.HEADER) String key,
                                         @Valid @RequestBody RevisionRequest request) {
        AuthenticatedUser actor = currentUser.get();
        return mutate(actor, UserRole.PLANNER, id, "issue", key, request, () -> service.issue(actor, id, request));
    }

    /** 목적: 담당 조직의 회신을 저장한다. Args: id 요청, key 멱등 키, request 회신. Returns: 회신 요청. Raises: 권한/입력/409. Side Effects: DB 갱신. */
    @PostMapping("/work-items/{id}/replies")
    public ResponseEntity<String> reply(@PathVariable long id, @RequestHeader(IdempotentCommandExecutor.HEADER) String key,
                                         @Valid @RequestBody ReplyRequest request) {
        AuthenticatedUser actor = currentUser.get();
        return mutate(actor, UserRole.ORG_MANAGER, id, "replies", key, request, () -> service.reply(actor, id, request));
    }

    /** 목적: 조직 회신의 기획자 검토를 저장한다. Args: id 요청, key 멱등 키, request 검토. Returns: 검토 요청. Raises: 권한/입력/409. Side Effects: 요청·분석 DB 갱신. */
    @PostMapping("/work-items/{id}/reviews")
    public ResponseEntity<String> review(@PathVariable long id, @RequestHeader(IdempotentCommandExecutor.HEADER) String key,
                                          @Valid @RequestBody ReviewRequest request) {
        AuthenticatedUser actor = currentUser.get();
        return mutate(actor, UserRole.PLANNER, id, "reviews", key, request, () -> service.review(actor, id, request));
    }

    /** 목적: 외부 경영진의 결정을 증빙과 함께 기록한다. Args: id 요청, key 멱등 키, request 결정. Returns: 결정 요청. Raises: 권한/입력/409. Side Effects: DB 기록, 실제 임원 결재 실행 없음. */
    @PostMapping("/work-items/{id}/decisions")
    public ResponseEntity<String> decide(@PathVariable long id, @RequestHeader(IdempotentCommandExecutor.HEADER) String key,
                                          @Valid @RequestBody DecisionRequest request) {
        AuthenticatedUser actor = currentUser.get();
        return mutate(actor, UserRole.PLANNER, id, "decisions", key, request, () -> service.decide(actor, id, request));
    }

    /** 목적: 자기 조직의 실행 경과와 확정본 비교를 저장한다. Args: id 요청, key 멱등 키, request 진행. Returns: 진행 요청. Raises: 권한/입력/409. Side Effects: DB 갱신. */
    @PostMapping("/work-items/{id}/progress")
    public ResponseEntity<String> progress(@PathVariable long id, @RequestHeader(IdempotentCommandExecutor.HEADER) String key,
                                            @Valid @RequestBody ProgressRequest request) {
        AuthenticatedUser actor = currentUser.get();
        return mutate(actor, UserRole.ORG_MANAGER, id, "progress", key, request, () -> service.progress(actor, id, request));
    }

    /** 목적: 진행 중 요청을 사유와 함께 취소한다. Args: id 요청, key 멱등 키, request 취소. Returns: 취소 요청. Raises: 권한/409. Side Effects: DB 갱신. */
    @PostMapping("/work-items/{id}/cancel")
    public ResponseEntity<String> cancel(@PathVariable long id, @RequestHeader(IdempotentCommandExecutor.HEADER) String key,
                                         @Valid @RequestBody CancelRequest request) {
        AuthenticatedUser actor = currentUser.get();
        return mutate(actor, UserRole.PLANNER, id, "cancel", key, request, () -> service.cancel(actor, id, request));
    }

    /** 목적: 멱등 응답 재사용 전에도 현재 역할·조직 범위를 검사한다. Args: actor 사용자, role 역할, id 요청, action 경로, key 멱등 키, request 입력, operation 변경. Returns: 멱등 응답. Raises: 권한/404/409. Side Effects: DB 조회·변경·멱등 기록. */
    private ResponseEntity<String> mutate(AuthenticatedUser actor, UserRole role, long id, String action, String key,
                                           Object request, Supplier<WorkItem> operation) {
        actor.requireRole(role);
        service.get(actor, id);
        return idempotency.execute(actor.id(), "POST /api/v1/decision-agent/work-items/" + id + "/" + action, key, request,
                () -> ResponseEntity.ok(operation.get()));
    }
}
