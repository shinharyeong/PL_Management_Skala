package io.skala.backend.domain.decisionagent.service;

import static io.skala.backend.domain.decisionagent.dto.DecisionAgentDtos.*;
import static io.skala.backend.domain.decisionagent.dto.DecisionResearchDtos.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.skala.backend.domain.report.service.ReportAiClient;
import io.skala.backend.domain.user.value.UserRole;
import io.skala.backend.global.exception.BusinessException;
import io.skala.backend.global.exception.ErrorCode;
import io.skala.backend.global.security.AuthenticatedUser;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 출처가 있는 검토 가설을 만들고 금액 확정은 조직 회신·기획자 검토로 남긴다. */
@Service
@RequiredArgsConstructor
public class DecisionDiscoveryService {
    private final DecisionAnalysisStore analyses;
    private final DecisionResearchStore research;
    private final DecisionEvidenceTool evidenceTool;
    private final DecisionScenarioCalculator calculator;
    private final ReportAiClient ai;
    private final ObjectMapper mapper;

    /** 목적: 자연어 목표를 사용자 확인용으로 제안한다. Args: actor 기획자, request 원문·기본 연도. Returns: 미확인 목표 제안. Raises: 역할/입력 오류. Side Effects: 내부 AI HTTP 호출, 업무 저장 없음. */
    public GoalResponse interpretGoal(AuthenticatedUser actor, GoalRequest request) {
        actor.requireRole(UserRole.PLANNER);
        try {
            GoalResponse result = mapper.treeToValue(ai.post("/v1/decision-agent/interpret-goal", request, actor.id()), GoalResponse.class);
            if (result != null && List.of("MODEL", "TEMPLATE").contains(result.mode()) && validGoal(result.goalProposal())) return result;
        } catch (Exception ignored) { }
        return new GoalResponse("TEMPLATE", new GoalProposal(null, request.year(), List.of(), true,
                List.of("목표 해석 서비스를 사용할 수 없습니다. 목표 금액·연도·보호 조건을 직접 확인해 입력해 주세요.")));
    }

    /** 목적: 소유 분석의 고정 근거와 마지막 가설을 조회한다. Args: actor 기획자, analysisId 분석. Returns: 조사 또는 null. Raises: 403/404. Side Effects: DB 읽기. */
    @Transactional(readOnly=true)
    public Research get(AuthenticatedUser actor, long analysisId) {
        actor.requireRole(UserRole.PLANNER);
        Analysis analysis = analyses.get(actor.id(), analysisId, false);
        return research.find(analysis.id());
    }

    /** 목적: 같은 기준에서 자료를 찾고 검토 가설을 생성한다. Args: actor 기획자, analysisId 분석, request 기대 revision. Returns: 저장된 근거·미확인 가설. Raises: 권한/409/원천 검증 오류. Side Effects: 근거·가설 저장, 내부 AI/읽기 도구 호출. */
    @Transactional
    public Research discover(AuthenticatedUser actor, long analysisId, DiscoverRequest request) {
        actor.requireRole(UserRole.PLANNER);
        Analysis analysis = analyses.get(actor.id(), analysisId, true);
        if (analysis.revision() != request.expectedRevision()) throw new BusinessException(ErrorCode.RESOURCE_CHANGED);
        Research captured = research.capture(analysis);
        String token = null;
        Discovery discovery;
        try {
            token = evidenceTool.grant(analysis.id(), analysis.revision(), captured.evidence());
            List<CatalogEntry> catalog = captured.evidence().stream().map(item -> new CatalogEntry(item.refId(), item.kind(), item.organizationId(), item.label())).toList();
            ComposeDiscovery input = new ComposeDiscovery(analysis.id(), analysis.revision(), analysis.prompt(), analysis.snapshot(),
                    analysis.protectedCategories(), catalog, token);
            Discovery proposed = mapper.treeToValue(ai.post("/v1/decision-agent/discover", input, actor.id()), Discovery.class);
            discovery = validated(proposed, captured.evidence());
        } catch (Exception ignored) {
            discovery = fallback(analysis, captured.evidence());
        } finally {
            evidenceTool.revoke(token);
        }
        return research.save(captured, analysis.revision(), discovery);
    }

    /** 목적: 요청 초안에 사용할 실제 저장 가설만 반환한다. Args: actor 소유자, analysisId 분석, hypothesisId 가설. Returns: 가설 사본. Raises: 404/409. Side Effects: DB 읽기. */
    @Transactional(readOnly=true)
    public Hypothesis findHypothesis(AuthenticatedUser actor, long analysisId, String hypothesisId) {
        Research saved = get(actor, analysisId);
        if (saved == null || saved.discovery() == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "먼저 근거 기반 후보를 찾아 주세요.");
        return saved.discovery().hypotheses().stream().filter(item -> item.id().equals(hypothesisId)).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "현재 조사에 없는 후보입니다. 새로 조회해 주세요."));
    }

    /** 목적: AI가 금액·출처·조직을 만들어 낸 가설을 저장하지 않는다. Args: proposed AI 응답, evidence 허용 근거. Returns: 새 ID를 부여한 가설. Raises: IllegalArgumentException 계약 위반. Side Effects: UUID 생성. */
    Discovery validated(Discovery proposed, List<Evidence> evidence) {
        if (proposed == null || !List.of("MODEL", "TEMPLATE").contains(proposed.mode()) || !validGoal(proposed.goalProposal())
                || proposed.hypotheses() == null || proposed.hypotheses().size() > 20 || !texts(proposed.limitations(), 30, 2000)
                || proposed.steps() == null || proposed.steps().size() > 20
                || proposed.steps().stream().anyMatch(step -> step == null || !text(step.key(), 100) || !text(step.label(), 200) || !text(step.status(), 40))) {
            throw new IllegalArgumentException("후보 응답 계약 오류");
        }
        Map<String, Evidence> sources = new LinkedHashMap<>();
        evidence.forEach(item -> sources.put(item.refId(), item));
        List<Hypothesis> accepted = new ArrayList<>();
        for (Hypothesis item : proposed.hypotheses()) {
            if (item == null || item.category() == null || !text(item.title(), 200) || !text(item.rationale(), 2000)
                    || !text(item.sourceQuote(), 4000) || !texts(item.questions(), 10, 1000) || item.questions().isEmpty()
                    || item.amountWon() != null || item.recognitionMonth() != null || !item.needsConfirmation()
                    || item.evidenceRefs() == null || item.evidenceRefs().isEmpty() || item.evidenceRefs().size() > 10
                    || new HashSet<>(item.evidenceRefs()).size() != item.evidenceRefs().size()) {
                throw new IllegalArgumentException("금액 미정 검토 후보 계약 오류");
            }
            boolean quoteFound = false;
            for (String ref : item.evidenceRefs()) {
                Evidence source = sources.get(ref);
                if (source == null || !source.organizationId().equals(item.organizationId())) throw new IllegalArgumentException("후보 출처·조직 불일치");
                quoteFound |= source.text().contains(item.sourceQuote());
            }
            if (!quoteFound) throw new IllegalArgumentException("원문에 없는 인용");
            accepted.add(new Hypothesis("hyp-" + UUID.randomUUID(), item.organizationId(), item.title(), item.rationale(),
                    List.copyOf(item.evidenceRefs()), null, null, item.category(), List.copyOf(item.questions()), true, item.sourceQuote()));
        }
        List<String> limitations = new ArrayList<>(proposed.limitations());
        limitations.add("자동 제안은 검토 가설입니다. 조직 회신과 기획자 검토 전에는 효과 금액을 계산에 반영하지 않습니다.");
        limitations.add("조회 범위는 기준 제출본의 손익 상위 100항목과 수락 소명 최대 50개입니다. 전체 기회를 망라하거나 최적안을 보장하지 않습니다.");
        return new Discovery(proposed.mode(), proposed.goalProposal(), List.copyOf(accepted), List.copyOf(limitations), proposed.steps());
    }

    /** 목적: 모델 없이도 실제 근거를 확인할 요청 초안을 만든다. Args: analysis 기준, evidence 고정 근거. Returns: 금액을 채우지 않는 가설. Raises: 없음. Side Effects: UUID 생성. */
    private Discovery fallback(Analysis analysis, List<Evidence> evidence) {
        Map<String, Evidence> byOrg = new LinkedHashMap<>();
        evidence.stream().filter(item -> "APPROVED_EXPLANATION".equals(item.kind())).forEach(item -> byOrg.putIfAbsent(item.organizationId(), item));
        evidence.forEach(item -> byOrg.putIfAbsent(item.organizationId(), item));
        List<Hypothesis> candidates = byOrg.values().stream().limit(12).map(item -> new Hypothesis("hyp-" + UUID.randomUUID(),
                item.organizationId(), item.label().substring(0, Math.min(180, item.label().length())) + " 개선 여지 확인", "고정한 손익·소명에 나타난 조건이 잔여 기간에 바뀔 수 있는지 담당자 확인이 필요합니다.",
                List.of(item.refId()), null, null, Category.UNKNOWN,
                List.of("실행 가능한 개선 과제가 있습니까? 없으면 불가 사유를 회신해 주세요.", "가능액·반영 월·추가 비용·전망 기반영분과 보호 비용 영향을 확인해 주세요.",
                        "계약·납품·예산 등 검토 근거와 다른 과제의 효과 중복 여부를 알려 주세요."), true,
                item.text().substring(0, Math.min(300, item.text().length())))).toList();
        return new Discovery("TEMPLATE", new GoalProposal(analysis.targetProfitWon(), analysis.snapshot().year(), analysis.protectedCategories(), true, List.of()),
                candidates, List.of("AI 후보 제안을 사용할 수 없어 고정 근거별 확인 요청을 구성했습니다. 새로운 개선액이나 원인을 확정하지 않았습니다.",
                "손익 최대 100항목·수락 소명 최대 50개에서 조직별 최대 12개 요청을 제시합니다."),
                List.of(new Step("sources", "고정 제출본 손익·수락 소명 조회", "COMPLETED"), new Step("hypotheses", "근거 확인 요청 구성", "FALLBACK")));
    }

    /** 목적: 목표 제안이 허용 범위의 미확인 입력인지 검사한다. Args: goal 제안. Returns: 허용 여부. Raises: 없음. Side Effects: 없음. */
    private boolean validGoal(GoalProposal goal) {
        if (goal == null || !goal.needsConfirmation() || goal.protectedCategories() == null || goal.protectedCategories().size() > 2
                || goal.protectedCategories().stream().anyMatch(item -> item != Category.HEADCOUNT && item != Category.RND)
                || new HashSet<>(goal.protectedCategories()).size() != goal.protectedCategories().size()
                || (goal.targetYear() != null && (goal.targetYear() < 2000 || goal.targetYear() > 9999)) || !texts(goal.uncertainties(), 20, 2000)) return false;
        try { if (goal.targetProfitWon() != null) calculator.amount(goal.targetProfitWon(), true); return true; }
        catch (BusinessException invalid) { return false; }
    }

    /** 목적: 단일 설명 길이를 제한한다. Args: value 문장, maximum 상한. Returns: 유효 여부. Raises: 없음. Side Effects: 없음. */
    private boolean text(String value, int maximum) { return value != null && !value.isBlank() && value.length() <= maximum; }

    /** 목적: 응답 배열 크기를 제한한다. Args: values 문장 배열, count 개수 상한, length 길이 상한. Returns: 유효 여부. Raises: 없음. Side Effects: 없음. */
    private boolean texts(List<String> values, int count, int length) {
        return values != null && values.size() <= count && values.stream().allMatch(value -> text(value, length));
    }
}
