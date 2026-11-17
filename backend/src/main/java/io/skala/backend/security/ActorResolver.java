package io.skala.backend.security;

import java.sql.ResultSet;
import java.sql.SQLException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** 인증된 이메일을 DB의 활성 사용자·조직과 연결하는 읽기 전용 경계다. */
@Component
public final class ActorResolver {

    private final JdbcClient jdbc;

    /**
     * 사용자 조회에 사용할 JDBC 클라이언트를 연결한다.
     *
     * <p>Args: jdbc는 애플리케이션 데이터소스의 JdbcClient다.
     * Returns: 생성자이므로 반환값이 없다.
     * Raises: 없음.
     * Side Effects: 참조만 보관하며 생성 시 DB를 조회하지 않는다.
     */
    public ActorResolver(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 현재 인증의 이메일로 사용자와 조직을 다시 확인한다.
     *
     * <p>Args: 명시적 인자는 없으며 SecurityContext의 이메일 또는 고정 조직 Actor principal을 사용한다.
     * Returns: DB에서 확인한 Actor.
     * Raises: 이메일 principal이 없으면 InsufficientAuthenticationException,
     * 사용자가 없거나 비활성·잘못된 소속 또는 고정한 역할·조직이 변경됐으면 BadCredentialsException,
     * DB 조회 실패는 Spring DataAccessException 계열로 전파한다.
     * Side Effects: 사용자·조직 DB를 읽는다. 인증 역할을 요청 입력에서 받지 않는다.
     */
    public Actor current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new InsufficientAuthenticationException("인증이 필요합니다.");
        }
        if (authentication.getPrincipal() instanceof Actor selected) {
            Actor current = resolveEmail(selected.email());
            if (!selected.id().equals(current.id()) || !"ORG_MANAGER".equals(current.role())
                    || selected.organization() == null || current.organization() == null
                    || !selected.organization().id().equals(current.organization().id())
                    || !selected.organization().code().equals(current.organization().code())) {
                throw new BadCredentialsException("선택한 체험 조직의 권한이 변경되었습니다.");
            }
            return current;
        }
        if (!(authentication.getPrincipal() instanceof String email)
                || email.isBlank()
                || "anonymousUser".equals(email)) {
            throw new InsufficientAuthenticationException("인증이 필요합니다.");
        }
        return resolveEmail(email);
    }

    /**
     * 인증된 이메일과 일치하는 활성 사용자 한 명을 조회한다.
     *
     * <p>Args: email은 검증된 인증 수단에서 받은 이메일이며 HTTP role/org 입력이 아니다.
     * Returns: 검증된 Actor.
     * Raises: 계정 미존재·비활성·소속 오류는 BadCredentialsException,
     * DB 접근 오류는 Spring DataAccessException 계열로 전파한다.
     * Side Effects: app_user와 organization을 읽으며 아무 값도 변경하지 않는다.
     */
    Actor resolveEmail(String email) {
        return jdbc.sql("""
                SELECT u.id, u.email, u.name, u.role, u.organization_id,
                       u.active AS user_active, o.code AS organization_code,
                       o.name AS organization_name, o.active AS organization_active
                FROM app_user u
                LEFT JOIN organization o ON o.id = u.organization_id
                WHERE u.email = :email
                """)
                .param("email", email)
                .query(this::mapActor)
                .optional()
                .orElseThrow(() -> new BadCredentialsException("인증 정보를 확인할 수 없습니다."));
    }

    /**
     * DB 행의 활성 상태와 역할별 조직 소속을 검사한다.
     *
     * <p>Args: result는 조회 행, rowNumber는 JDBC 행 번호이며 업무 판단에 사용하지 않는다.
     * Returns: 인증에 사용할 Actor.
     * Raises: 비활성 계정·조직이나 잘못된 역할/소속은 BadCredentialsException,
     * JDBC 컬럼 읽기 실패는 SQLException.
     * Side Effects: 현재 ResultSet 행만 읽는다.
     */
    private Actor mapActor(ResultSet result, int rowNumber) throws SQLException {
        String role = result.getString("role");
        String organizationId = result.getString("organization_id");
        boolean planner = "PLANNER".equals(role) && organizationId == null;
        boolean manager = "ORG_MANAGER".equals(role)
                && organizationId != null
                && result.getBoolean("organization_active");
        if (!result.getBoolean("user_active") || (!planner && !manager)) {
            throw new BadCredentialsException("인증 정보를 확인할 수 없습니다.");
        }
        Organization organization = manager
                ? new Organization(organizationId, result.getString("organization_code"),
                        result.getString("organization_name"))
                : null;
        return new Actor(result.getString("id"), result.getString("email"),
                result.getString("name"), role, organization);
    }
}
