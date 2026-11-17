package io.skala.backend.security;

import org.springframework.security.access.AccessDeniedException;

/**
 * 서버 DB에서 확인한 현재 사용자의 역할과 조직 범위를 보관한다.
 *
 * <p>record 접근자는 각 필드만 반환하며 인증 토큰을 포함하지 않는다.
 */
public record Actor(
        String id,
        String email,
        String name,
        String role,
        Organization organization) {

    /**
     * 두 역할과 조직 소속의 불변식을 검증해 사용자를 구성한다.
     *
     * <p>Args: id·email·name은 DB 사용자 정보, role은 PLANNER 또는 ORG_MANAGER,
     * organization은 조직 담당자의 활성 조직이며 경영기획 담당자는 null이다.
     * Returns: 생성자이므로 반환값이 없다.
     * Raises: 역할 또는 역할별 조직 소속이 잘못되면 IllegalArgumentException.
     * Side Effects: 없음. 입력값을 변경하거나 외부 자료를 읽지 않는다.
     */
    public Actor {
        boolean planner = "PLANNER".equals(role) && organization == null;
        boolean manager = "ORG_MANAGER".equals(role) && organization != null;
        if (!planner && !manager) {
            throw new IllegalArgumentException("사용자 역할과 조직 소속이 일치하지 않습니다.");
        }
    }

    /**
     * 현재 사용자에게 지정 역할이 있는지 확인한다.
     *
     * <p>Args: requiredRole은 업무 명령이 요구하는 역할 문자열이다.
     * Returns: 역할이 같으면 정상 반환하며 값은 없다.
     * Raises: 역할이 다르면 AccessDeniedException.
     * Side Effects: 없음.
     */
    public void requireRole(String requiredRole) {
        if (!role.equals(requiredRole)) {
            throw new AccessDeniedException("요청한 작업을 수행할 권한이 없습니다.");
        }
    }

    /**
     * 조직 ID가 현재 사용자의 조회·수정 범위에 속하는지 판단한다.
     *
     * <p>Args: organizationId는 대상 조직의 십진 문자열 ID다.
     * Returns: PLANNER는 지정 조직, ORG_MANAGER는 본인 조직이면 true다.
     * null 또는 빈 조직 ID는 false다.
     * Raises: 없음.
     * Side Effects: 없음. 조직의 실제 존재 여부는 호출 서비스가 별도로 확인한다.
     */
    public boolean canAccessOrganization(String organizationId) {
        if (organizationId == null || organizationId.isBlank()) {
            return false;
        }
        return "PLANNER".equals(role) || organization.id().equals(organizationId);
    }
}
