package io.skala.backend.domain.submission.support;

import io.skala.backend.domain.submission.entity.Submission;
import io.skala.backend.domain.submission.repository.SubmissionRepository;
import io.skala.backend.domain.user.value.UserRole;
import io.skala.backend.global.exception.BusinessException;
import io.skala.backend.global.exception.ErrorCode;
import io.skala.backend.global.security.AuthenticatedUser;
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

    /** 제출본 행을 잠그고 읽은 뒤 조회 권한을 확인한다. 상태를 바꾸는 명령의 첫 조회에 쓴다. */
    public Submission lockForRead(Long submissionId, AuthenticatedUser user) {
        Submission submission = submissionRepository.findByIdForUpdate(submissionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        user.requireOrganizationAccess(submission.getOrganizationId());
        return submission;
    }

    /** 조직 담당자 전용 명령: 역할 확인 후 제출본 행을 잠그고 읽는다. */
    public Submission lockForOrgManager(Long submissionId, AuthenticatedUser user) {
        user.requireRole(UserRole.ORG_MANAGER);
        return lockForRead(submissionId, user);
    }

    /** 경영기획팀은 모든 조직, 조직 담당자는 자기 조직 제출본만 조회 */
    public Submission loadForRead(Long submissionId, AuthenticatedUser user) {
        Submission submission = load(submissionId);
        user.requireOrganizationAccess(submission.getOrganizationId());
        return submission;
    }

    /** 조직 담당자 전용 명령. 다른 역할이거나 다른 조직이면 403. */
    public Submission loadForOrgManager(Long submissionId, AuthenticatedUser user) {
        user.requireRole(UserRole.ORG_MANAGER);
        return loadForRead(submissionId, user);
    }
}
