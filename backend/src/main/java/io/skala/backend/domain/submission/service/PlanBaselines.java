package io.skala.backend.domain.submission.service;

import io.skala.backend.domain.request.entity.RequestTarget;
import io.skala.backend.domain.request.entity.RequestTargetId;
import io.skala.backend.domain.request.repository.RequestTargetRepository;
import io.skala.backend.domain.submission.entity.Submission;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 확정 연간 계획 제출본 찾기. 월간 요청을 보낼 때 B가 request_target.confirmed_plan_submission_id에
 * 그 시점 최신 연간 master의 조직별 승인본을 고정해 둔다. 값이 없으면(연간 계획 이후 추가된 조직) '계획 없음'.
 */
@Component
@RequiredArgsConstructor
public class PlanBaselines {

    private final RequestTargetRepository requestTargetRepository;

    public Optional<Submission> confirmedPlan(Long requestId, Long organizationId) {
        return requestTargetRepository.findById(new RequestTargetId(requestId, organizationId))
                .map(RequestTarget::getConfirmedPlanSubmission);
    }
}
