package io.skala.backend.domain.submission.support;

import io.skala.backend.domain.submission.entity.Submission;
import io.skala.backend.domain.submission.repository.SubmissionRepository;
import io.skala.backend.domain.user.entity.AppUser;
import io.skala.backend.domain.user.value.UserRole;
import io.skala.backend.global.exception.BusinessException;
import io.skala.backend.global.exception.ErrorCode;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 제출본 조회·권한 확인. 조직 담당자는 자기 조직 제출본만(FR-MENU-002). */
@Component
@RequiredArgsConstructor
public class SubmissionAccess {

    private final SubmissionRepository submissionRepository;

    public Submission load(Long submissionId) {
        return submissionRepository.findById(submissionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    /** 조직 담당자 전용 명령. 다른 조직이면 403. */
    public Submission loadForOrgManager(Long submissionId, AppUser actor) {
        Submission submission = load(submissionId);
        if (actor.getRole() != UserRole.ORG_MANAGER || actor.getOrganization() == null
                || !Objects.equals(actor.getOrganization().getId(), submission.getOrganizationId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return submission;
    }
}
