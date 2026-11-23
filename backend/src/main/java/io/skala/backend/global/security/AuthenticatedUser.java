package io.skala.backend.global.security;

import io.skala.backend.domain.user.value.UserRole;
import io.skala.backend.global.exception.BusinessException;
import io.skala.backend.global.exception.ErrorCode;
import java.security.Principal;
import java.util.Objects;

/**
 * 로그인한 사용자. SecurityContext의 principal이며 Authentication#getName()은 app_user.id 문자열이다.
 * 조직 담당자는 organizationId가 있고 경영기획팀은 null이다.
 */
public record AuthenticatedUser(Long id, UserRole role, Long organizationId) implements Principal {

    @Override
    public String getName() {
        return String.valueOf(id);
    }

    public boolean isPlanner() {
        return role == UserRole.PLANNER;
    }

    /** 역할이 다르면 403 FORBIDDEN */
    public void requireRole(UserRole required) {
        if (role != required) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }

    /** 경영기획팀은 모든 조직, 조직 담당자는 자기 조직만. 아니면 403 FORBIDDEN (FR-MENU-002) */
    public void requireOrganizationAccess(Long targetOrganizationId) {
        if (!isPlanner() && !Objects.equals(organizationId, targetOrganizationId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }
}
