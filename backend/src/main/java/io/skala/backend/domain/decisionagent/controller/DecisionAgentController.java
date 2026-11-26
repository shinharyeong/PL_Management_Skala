package io.skala.backend.domain.decisionagent.controller;

import static io.skala.backend.domain.decisionagent.dto.DecisionAgentDtos.*;

import io.skala.backend.domain.decisionagent.service.DecisionAgentService;
import io.skala.backend.domain.user.value.UserRole;
import io.skala.backend.global.idempotency.IdempotentCommandExecutor;
import io.skala.backend.global.security.AuthenticatedUser;
import io.skala.backend.global.security.CurrentUser;
import jakarta.validation.Valid;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 로그인한 경영기획팀의 개인 의사결정 분석 API. 모든 상태 변경에 멱등 키를 요구한다. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/decision-agent")
public class DecisionAgentController {
    private final DecisionAgentService service;
    private final CurrentUser currentUser;
    private final IdempotentCommandExecutor idempotency;

    /** 목적: 확정 기준 목록을 조회한다. Args: year 연도. Returns: 기준 목록. Raises: 권한/입력 오류. Side Effects: DB 읽기. */
    @GetMapping("/baselines")
    public Items<Baseline> baselines(@RequestParam(required = false) Integer year) {
        return service.baselines(currentUser.get(), year);
    }

    /** 목적: 본인의 분석을 조회한다. Args: 없음. Returns: 목록. Raises: 권한 오류. Side Effects: DB 읽기. */
    @GetMapping("/analyses")
    public Items<AnalysisSummary> list() {
        return service.list(currentUser.get());
    }

    /** 목적: 본인의 저장된 분석을 조회한다. Args: id 분석. Returns: 분석. Raises: 권한/404. Side Effects: DB 읽기. */
    @GetMapping("/analyses/{id}")
    public Analysis get(@PathVariable long id) {
        return service.get(currentUser.get(), id);
    }

    /** 목적: 멱등하게 분석을 생성한다. Args: key 멱등 키, request 목표/제약/기준. Returns: 201 분석. Raises: 권한/입력/멱등 오류. Side Effects: 분석·멱등 기록 DB 생성. */
    @PostMapping("/analyses")
    public ResponseEntity<String> create(@RequestHeader(IdempotentCommandExecutor.HEADER) String key,
                                          @Valid @RequestBody CreateRequest request) {
        AuthenticatedUser actor = currentUser.get();
        actor.requireRole(UserRole.PLANNER);
        return idempotency.execute(actor.id(), "POST /api/v1/decision-agent/analyses", key, request, () -> {
            Analysis saved = service.create(actor, request);
            return ResponseEntity.created(URI.create("/api/v1/decision-agent/analyses/" + saved.id())).body(saved);
        });
    }

    /** 목적: 멱등하게 후보를 교체한다. Args: id 분석, key 멱등 키, request 후보. Returns: 분석. Raises: 권한/입력/409. Side Effects: 분석·멱등 기록 갱신. */
    @PutMapping("/analyses/{id}/candidates")
    public ResponseEntity<String> candidates(@PathVariable long id,
                                              @RequestHeader(IdempotentCommandExecutor.HEADER) String key,
                                              @Valid @RequestBody CandidatesRequest request) {
        AuthenticatedUser actor = currentUser.get();
        actor.requireRole(UserRole.PLANNER);
        return idempotency.execute(actor.id(), "PUT /api/v1/decision-agent/analyses/" + id + "/candidates", key, request,
                () -> ResponseEntity.ok(service.candidates(actor, id, request)));
    }

    /** 목적: 멱등하게 계산과 설명 생성을 실행한다. Args: id 분석, key 멱등 키, request 예상 버전. Returns: 계산 분석. Raises: 권한/입력/409. Side Effects: AI 호출·DB 상태와 멱등 기록 갱신. */
    @PostMapping("/analyses/{id}/runs")
    public ResponseEntity<String> run(@PathVariable long id,
                                       @RequestHeader(IdempotentCommandExecutor.HEADER) String key,
                                       @Valid @RequestBody RunRequest request) {
        AuthenticatedUser actor = currentUser.get();
        actor.requireRole(UserRole.PLANNER);
        return idempotency.execute(actor.id(), "POST /api/v1/decision-agent/analyses/" + id + "/runs", key, request,
                () -> ResponseEntity.ok(service.run(actor, id, request)));
    }
}
