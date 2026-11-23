package io.skala.backend.domain.review.service;

import io.skala.backend.domain.review.dto.GapThresholdListResponse;
import io.skala.backend.domain.review.dto.GapThresholdRequest;
import io.skala.backend.domain.review.dto.GapThresholdResponse;
import io.skala.backend.domain.review.entity.GapComparison;
import io.skala.backend.domain.review.entity.GapThreshold;
import io.skala.backend.domain.review.repository.GapComparisonRepository;
import io.skala.backend.domain.review.repository.GapThresholdRepository;
import io.skala.backend.domain.review.value.GapComparisonType;
import io.skala.backend.domain.user.repository.AppUserRepository;
import io.skala.backend.domain.user.value.UserRole;
import io.skala.backend.global.exception.BusinessException;
import io.skala.backend.global.exception.ErrorCode;
import io.skala.backend.global.security.AuthenticatedUser;
import io.skala.backend.global.util.StateToken;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** GAP-03 현재 기준, GAP-04 적용 전 영향 건수, GAP-05 기준 적용(새 행, 이후 제출부터) */
@Service
@RequiredArgsConstructor
public class GapThresholdService {

    private final GapThresholdRepository gapThresholdRepository;
    private final GapComparisonRepository gapComparisonRepository;
    private final AppUserRepository appUserRepository;
    private final Clock clock;

    @Transactional(readOnly = true)
    public GapThresholdListResponse current(AuthenticatedUser user) {
        user.requireRole(UserRole.PLANNER);
        return snapshot();
    }

    /** 검토 대기 제출본의 비교 결과 중 새 기준으로 Gap이 되는 건수 */
    @Transactional(readOnly = true)
    public int preview(AuthenticatedUser user, GapThresholdRequest request) {
        user.requireRole(UserRole.PLANNER);
        validate(request);
        BigDecimal percent = new BigDecimal(request.percent());
        BigDecimal amount = new BigDecimal(request.amount());
        int count = 0;
        for (GapComparison g : gapComparisonRepository.findAllOfPendingSubmissions()) {
            GapComparisonType type = GapRules.typeOf(g.getReviewItem().getScenario(), g.getBasis());
            if (type == request.comparison() && GapRules.exceeded(request.percentEnabled(), percent,
                    request.amountEnabled(), amount, g.getBaselineAmount(), g.getCurrentAmount())) {
                count++;
            }
        }
        return count;
    }

    @Transactional
    public GapThresholdResponse apply(AuthenticatedUser user, GapThresholdRequest request) {
        user.requireRole(UserRole.PLANNER);
        validate(request);
        gapThresholdRepository.lockGuardForApply();
        StateToken.verify(request.expectedStateToken(), snapshot().stateToken());
        GapThreshold saved = gapThresholdRepository.save(GapThreshold.apply(request.comparison(),
                request.percentEnabled(), new BigDecimal(request.percent()), request.amountEnabled(),
                new BigDecimal(request.amount()), appUserRepository.getReferenceById(user.id()),
                OffsetDateTime.now(clock)));
        gapThresholdRepository.flush();
        return GapThresholdResponse.of(gapThresholdRepository.findById(saved.getId()).orElseThrow());
    }

    private GapThresholdListResponse snapshot() {
        List<GapThresholdResponse> items = Arrays.stream(GapComparisonType.values())
                .map(type -> gapThresholdRepository.findFirstByComparisonOrderByIdDesc(type)
                        .map(GapThresholdResponse::of).orElse(GapThresholdResponse.none(type)))
                .toList();
        // 비교 종류별 현재 기준 ID로 만든다(새 기준을 적용하면 바뀜). 배열을 값 하나로 넘기지 않도록 펼친다.
        Object[] parts = items.stream().map(i -> i.comparison() + "=" + i.id()).toArray();
        return new GapThresholdListResponse(StateToken.of(parts), items);
    }

    private static void validate(GapThresholdRequest request) {
        if (!request.percentEnabled() && !request.amountEnabled()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "차이율과 최소 금액 중 하나 이상을 켜 주세요.");
        }
    }
}
