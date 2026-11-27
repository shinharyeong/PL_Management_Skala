package io.skala.backend.domain.decisionagent.service;

import static io.skala.backend.domain.decisionagent.dto.DecisionAgentDtos.*;

import io.skala.backend.global.exception.BusinessException;
import io.skala.backend.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/** AI와 분리된 원 단위 정밀 계산. 가정과 검토 완료 효과를 구분하며 중복 효과를 모두 제외한다. */
@Component
public class DecisionScenarioCalculator {
    /** 목적: 문자열 금액을 검증한다. Args: value 금액, positive 양수 필수 여부. Returns: 정밀 금액. Raises: INVALID_REQUEST 형식 오류. Side Effects: 없음. */
    public BigDecimal amount(String value, boolean positive) {
        if (value == null || !value.matches("(?:0|[1-9][0-9]{0,23})(?:\\.[0-9]{1,6})?")) {
            throw invalid("금액은 정수부 24자리, 소수부 6자리 이내의 원 단위 문자열이어야 합니다.");
        }
        BigDecimal parsed = new BigDecimal(value);
        if (positive && parsed.signum() <= 0) {
            throw invalid("목표와 후보 금액은 0보다 커야 합니다.");
        }
        return parsed;
    }

    /** 목적: 저장 전 후보 입력을 검증한다. Args: snapshot 고정 기준, candidates 후보. Returns: 없음. Raises: INVALID_REQUEST 의미 오류. Side Effects: 없음. */
    public void validate(Snapshot snapshot, List<Candidate> candidates) {
        if (candidates == null || candidates.size() > 100) {
            throw invalid("개선 후보는 최대 100개입니다.");
        }
        Set<String> organizations = new HashSet<>();
        snapshot.organizations().forEach(org -> organizations.add(org.id()));
        Set<String> ids = new HashSet<>();
        for (Candidate candidate : candidates) {
            if (candidate == null || candidate.id() == null || candidate.id().isBlank()
                    || !ids.add(candidate.id()) || !organizations.contains(candidate.organizationId())) {
                throw invalid("후보 ID는 고유해야 하며 담당 조직은 고정 기준에 포함되어야 합니다.");
            }
            if (candidate.title() == null || candidate.title().isBlank() || candidate.title().length() > 200
                    || candidate.type() == null || candidate.category() == null || candidate.evidenceStatus() == null
                    || candidate.duplicateGroup() == null || candidate.evidenceNote() == null || candidate.evidenceRef() == null
                    || candidate.recognitionMonth() < 1 || candidate.recognitionMonth() > 24) {
                throw invalid("후보 제목, 분류, 근거 상태 및 반영 월을 확인해 주세요.");
            }
            amount(candidate.amountWon(), true);
            amount(candidate.implementationCostWon(), false);
            amount(candidate.embeddedEffectWon(), false);
            if (candidate.type() == EffectType.REVENUE) {
                BigDecimal margin = amount(candidate.contributionMarginPercent(), false);
                if (margin.compareTo(new BigDecimal("100")) > 0) {
                    throw invalid("공헌이익률은 0~100%입니다.");
                }
            } else if (candidate.contributionMarginPercent() != null) {
                throw invalid("직접 증익 후보의 공헌이익률은 null이어야 합니다.");
            }
            if (candidate.evidenceStatus() == EvidenceStatus.REVIEWED
                    && (candidate.evidenceNote().isBlank() || candidate.evidenceRef().isBlank())) {
                throw invalid("검토 완료 후보에는 근거 설명과 근거 참조가 모두 필요합니다.");
            }
        }
    }

    /** 목적: 고정 전망과 후보의 조건부 효과를 산출한다. Args: snapshot 기준, target 목표, protectedCategories 보호 비용, candidates 후보. Returns: 검증된 계산 결과. Raises: INVALID_REQUEST 입력 오류. Side Effects: 없음. */
    public Result calculate(Snapshot snapshot, String target, List<Category> protectedCategories, List<Candidate> candidates) {
        validate(snapshot, candidates);
        BigDecimal goal = amount(target, true);
        BigDecimal baseline = new BigDecimal(snapshot.baselineProfitWon());
        var duplicateCounts = new HashMap<String, Integer>();
        candidates.stream().map(Candidate::duplicateGroup).map(String::strip).filter(group -> !group.isEmpty())
                .forEach(group -> duplicateCounts.merge(group, 1, Integer::sum));
        BigDecimal reviewed = BigDecimal.ZERO;
        BigDecimal conditional = BigDecimal.ZERO;
        List<Effect> effects = new ArrayList<>();
        for (Candidate candidate : candidates) {
            BigDecimal gross = amount(candidate.amountWon(), true);
            if (candidate.type() == EffectType.REVENUE) {
                gross = gross.multiply(new BigDecimal(candidate.contributionMarginPercent())).movePointLeft(2);
            }
            BigDecimal net = gross.subtract(new BigDecimal(candidate.implementationCostWon()))
                    .subtract(new BigDecimal(candidate.embeddedEffectWon()));
            List<String> exclusions = new ArrayList<>();
            if (protectedCategories.contains(candidate.category())) exclusions.add("PROTECTED_CATEGORY");
            if (!protectedCategories.isEmpty() && candidate.category() == Category.UNKNOWN) exclusions.add("UNCLASSIFIED_PROTECTED_SCOPE");
            if (candidate.recognitionMonth() <= snapshot.baseMonth()) exclusions.add("ALREADY_ACTUAL_PERIOD");
            if (candidate.recognitionMonth() > 12) exclusions.add("OUTSIDE_TARGET_YEAR");
            if (duplicateCounts.getOrDefault(candidate.duplicateGroup().strip(), 0) > 1) exclusions.add("DUPLICATE_EFFECT");
            if (net.signum() <= 0) exclusions.add("NON_POSITIVE_NET_EFFECT");
            boolean isReviewed = candidate.evidenceStatus() == EvidenceStatus.REVIEWED;
            if (exclusions.isEmpty()) {
                conditional = conditional.add(net);
                if (isReviewed) reviewed = reviewed.add(net);
            }
            effects.add(new Effect(candidate.id(), decimal(net), exclusions.isEmpty(), isReviewed, List.copyOf(exclusions)));
        }
        BigDecimal reviewedOutlook = baseline.add(reviewed);
        BigDecimal conditionalOutlook = baseline.add(conditional);
        List<String> warnings = new ArrayList<>();
        warnings.add("모든 개선 효과는 미래 실현을 보장하지 않습니다. 검토 완료는 사용자 입력이며 추진 승인이나 실적 확정이 아닙니다.");
        if (candidates.stream().anyMatch(candidate -> candidate.evidenceStatus() == EvidenceStatus.ASSUMPTION)) {
            warnings.add("조건부 전망에는 사용자가 입력한 미검증 가정이 포함될 수 있습니다.");
        }
        if (effects.stream().anyMatch(effect -> !effect.included())) warnings.add("제약·기간·중복·순효과 검사에서 제외된 후보가 있습니다.");
        if (candidates.isEmpty()) warnings.add("입력된 개선 후보가 없어 기준 전망으로 계산했습니다.");
        return new Result(decimal(baseline), decimal(goal), gap(goal, baseline), decimal(reviewed), decimal(conditional),
                decimal(reviewedOutlook), decimal(conditionalOutlook), gap(goal, reviewedOutlook), gap(goal, conditionalOutlook),
                decimal(conditionalOutlook.multiply(new BigDecimal("100")).divide(goal, 6, RoundingMode.HALF_UP)),
                List.copyOf(effects), List.copyOf(warnings));
    }

    /** 목적: 금액을 지수 없는 문자열로 표시한다. Args: value 정밀 금액. Returns: 정규화 문자열. Raises: 없음. Side Effects: 없음. */
    public static String decimal(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    /** 목적: 목표를 초과한 경우 잔여 부족액을 0으로 제한한다. Args: target 목표, outlook 전망. Returns: 잔여 부족액. Raises: 없음. Side Effects: 없음. */
    private String gap(BigDecimal target, BigDecimal outlook) {
        return decimal(target.subtract(outlook).max(BigDecimal.ZERO));
    }

    /** 목적: 일관된 입력 오류를 구성한다. Args: message 설명. Returns: 업무 예외. Raises: 없음. Side Effects: 없음. */
    private BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.INVALID_REQUEST, message);
    }
}
