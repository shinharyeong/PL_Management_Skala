package io.skala.backend.domain.decisionagent.controller;

import static io.skala.backend.domain.decisionagent.dto.DecisionAssistantDtos.*;
import io.skala.backend.domain.decisionagent.service.DecisionAssistantGateway;
import io.skala.backend.domain.decisionagent.service.DecisionAssistantService;
import io.skala.backend.domain.report.service.ReportAiClient;
import io.skala.backend.global.exception.BusinessException;
import io.skala.backend.global.exception.ErrorCode;
import io.skala.backend.global.security.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** 사용자 대화와 내부 읽기 Tool의 인증 경계. 멱등 저장이나 업무 명령을 호출하지 않는다. */
@RestController
@RequiredArgsConstructor
public class DecisionAssistantController {
    private final DecisionAssistantService service;
    private final DecisionAssistantGateway gateway;
    private final ReportAiClient ai;
    private final CurrentUser currentUser;

    /** 목적: 인증한 기획자의 조회 대화를 실행한다. Args: request 기준과 대화. Returns: 답변·실제 호출·근거. Raises: 인증/입력/버전 오류. Side Effects: DB 읽기와 내부 AI 호출, 저장 없음. */
    @PostMapping("/api/v1/decision-agent/assistant")
    public Response ask(@Valid @RequestBody Request request) { return service.ask(currentUser.get(), request); }

    /** 목적: 내부 토큰과 실행 capability를 모두 확인해 조회한다. Args: token 내부 인증, capability 실행 권한, request 도구 인자. Returns: 실제 관찰. Raises: 403/429/입력 오류. Side Effects: DB 읽기·메모리 원장만. */
    @PostMapping("/internal/decision-agent/tools/call")
    public Observation call(@RequestHeader(value="X-Internal-Token", required=false) String token,
                            @RequestHeader(value="X-Decision-Tool-Token", required=false) String capability,
                            @Valid @RequestBody ToolRequest request) {
        if (!ai.acceptsToken(token)) throw new BusinessException(ErrorCode.FORBIDDEN);
        return gateway.call(capability, request);
    }
}
