package io.skala.backend.domain.decisionagent.service;

import static io.skala.backend.domain.decisionagent.dto.DecisionAgentDtos.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.skala.backend.domain.report.service.ReportAiClient;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 계산 확정 후 내부 AI에 설명만 요청하며 장애 시에도 계산 결과를 유지한다. */
@Service
@RequiredArgsConstructor
public class DecisionNarrativeService {
    private final ReportAiClient client;
    private final ObjectMapper mapper;

    /** 목적: 금액을 변경하지 않는 의사결정 설명을 만든다. Args: userId 감사용 사용자, analysis 기준, result 확정 계산. Returns: AI 또는 기본 설명. Raises: 없음. Side Effects: 내부 AI HTTP 호출. */
    public Narrative compose(long userId, Analysis analysis, Result result) {
        try {
            var response = client.post("/v1/decision-agent/compose", new ComposeRequest(analysis.id(), analysis.title(),
                    analysis.prompt(), analysis.targetProfitWon(), analysis.protectedCategories(), analysis.snapshot(), analysis.candidates(), result), userId);
            Narrative narrative = mapper.treeToValue(response, Narrative.class);
            if (narrative != null && List.of("MODEL", "TEMPLATE").contains(narrative.mode())
                    && narrative.summary() != null && !narrative.summary().isBlank() && narrative.summary().length() <= 10000
                    && validTexts(narrative.decisionRequests()) && validTexts(narrative.limitations())
                    && narrative.steps() != null && narrative.steps().size() <= 20
                    && narrative.steps().stream().allMatch(step -> step != null && step.key() != null && step.label() != null && step.status() != null)) {
                return narrative;
            }
        } catch (Exception exception) {
            // 설명 장애는 수치 검증 결과와 저장의 성공 여부를 바꾸지 않는다.
        }
        return fallback(result);
    }

    /** 목적: 설명 배열의 응답 크기를 제한한다. Args: values 문장 배열. Returns: 허용 여부. Raises: 없음. Side Effects: 없음. */
    private boolean validTexts(List<String> values) {
        return values != null && values.size() <= 30 && values.stream().allMatch(value -> value != null && value.length() <= 4000);
    }

    /** 목적: AI 없이도 근거 수준과 부족액을 설명한다. Args: result 검증 계산. Returns: 기본 설명. Raises: 없음. Side Effects: 없음. */
    private Narrative fallback(Result result) {
        return new Narrative("TEMPLATE", "현재 전망 " + result.baselineProfitWon() + "원, 검토 완료 후보 반영 전망 "
                + result.reviewedOutlookWon() + "원, 미검증 가정까지 포함한 조건부 전망 " + result.conditionalOutlookWon()
                + "원입니다. 조건부 전망 기준 남은 목표 부족액은 " + result.conditionalGapWon() + "원입니다.",
                List.of("검토 완료 후보의 근거와 실행 조건을 확인한 후 추진 여부를 결정해 주세요.",
                        "미검증 가정과 남은 목표 부족액에 대한 추가 검토가 필요합니다."),
                List.of("AI 설명을 사용할 수 없어 계산 결과로 기본 설명을 제공했습니다.",
                        "검토 완료는 입력한 근거의 검토 상태이며 승인·효과 실현·실적 확정을 뜻하지 않습니다."),
                List.of(new Step("baseline", "확정 기준 고정", "COMPLETED"),
                        new Step("calculate", "제약 및 시나리오 계산", "COMPLETED"),
                        new Step("compose", "기본 의사결정 설명", "FALLBACK")));
    }
}
