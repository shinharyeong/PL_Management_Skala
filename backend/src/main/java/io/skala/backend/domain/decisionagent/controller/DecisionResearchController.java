package io.skala.backend.domain.decisionagent.controller;

import static io.skala.backend.domain.decisionagent.dto.DecisionResearchDtos.*;
import io.skala.backend.domain.decisionagent.service.DecisionDiscoveryService;
import io.skala.backend.domain.decisionagent.service.DecisionEvidenceTool;
import io.skala.backend.domain.report.service.ReportAiClient;
import io.skala.backend.domain.user.value.UserRole;
import io.skala.backend.global.exception.BusinessException;
import io.skala.backend.global.exception.ErrorCode;
import io.skala.backend.global.idempotency.IdempotentCommandExecutor;
import io.skala.backend.global.security.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 사용자 조사 명령과 분석 한정 내부 읽기 도구의 서로 다른 인증 경계. */
@RestController
@RequiredArgsConstructor
public class DecisionResearchController {
    private final DecisionDiscoveryService service;
    private final DecisionEvidenceTool evidence;
    private final ReportAiClient ai;
    private final CurrentUser currentUser;
    private final IdempotentCommandExecutor idempotency;

    /** 목적: 사용자 확인용 목표를 저장 없이 해석한다. Args: request 원문·기본 연도. Returns: 미확인 해석 제안. Raises: 인증/역할/입력 오류. Side Effects: 내부 AI HTTP 호출만, 멱등 기록·업무 DB 저장 없음. 기존 Idempotency-Key 헤더는 무시한다. */
    @PostMapping("/api/v1/decision-agent/goal-proposals")
    public GoalResponse goal(@Valid @RequestBody GoalRequest request) {
        var actor = currentUser.get(); actor.requireRole(UserRole.PLANNER);
        return service.interpretGoal(actor, request);
    }

    /** 목적: 고정 근거와 가설을 다시 연다. Args: id 분석. Returns: 조사 또는 미조사 204. Raises: 권한/404. Side Effects: DB 읽기. */
    @GetMapping("/api/v1/decision-agent/analyses/{id}/research")
    public ResponseEntity<Research> get(@PathVariable long id) {
        Research found = service.get(currentUser.get(), id);
        return found == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(found);
    }

    /** 목적: 고정 근거 조회와 후보 발굴을 명시적으로 실행한다. Args: id 분석, key 멱등 키, request 버전. Returns: 조사 결과. Raises: 권한/409/입력 오류. Side Effects: 고정 근거·가설·멱등 응답 저장, AI 호출. */
    @PostMapping("/api/v1/decision-agent/analyses/{id}/discover")
    public ResponseEntity<String> discover(@PathVariable long id, @RequestHeader(IdempotentCommandExecutor.HEADER) String key,
                                            @Valid @RequestBody DiscoverRequest request) {
        var actor = currentUser.get(); actor.requireRole(UserRole.PLANNER);
        return idempotency.execute(actor.id(), "POST /api/v1/decision-agent/analyses/" + id + "/discover", key, request,
                () -> ResponseEntity.ok(service.discover(actor, id, request)));
    }

    /** 목적: 내부 인증과 단기 분석 권한을 모두 확인해 근거를 읽는다. Args: token 내부 토큰, capability 분석 토큰, request 허용 참조. Returns: 근거. Raises: 403 인증/범위 오류. Side Effects: 메모리 읽기만, DB·상태 변경 없음. */
    @PostMapping("/internal/decision-agent/evidence/read")
    public ReadEvidenceResponse read(@RequestHeader(value="X-Internal-Token", required=false) String token,
                                     @RequestHeader(value="X-Decision-Tool-Token", required=false) String capability,
                                     @Valid @RequestBody ReadEvidenceRequest request) {
        if (!ai.acceptsToken(token)) throw new BusinessException(ErrorCode.FORBIDDEN);
        return evidence.read(capability, request);
    }
}
