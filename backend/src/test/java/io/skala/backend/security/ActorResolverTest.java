package io.skala.backend.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** 실제 마이그레이션을 메모리 DB에 적용해 사용자·조직 권한 경계를 확인한다. */
class ActorResolverTest {

    private EmbeddedDatabase database;
    private JdbcClient jdbc;
    private ActorResolver resolver;

    /**
     * 파일 없이 실제 스키마와 가상 계정을 메모리에 준비한다.
     *
     * <p>Args: 없음.
     * Returns: 없음.
     * Raises: 메모리 DB 또는 SQL 준비가 실패하면 런타임 예외.
     * Side Effects: 메모리 DB만 생성·수정하고 인증 문맥을 비운다.
     */
    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .setName("actor_" + UUID.randomUUID() + ";MODE=PostgreSQL;NON_KEYWORDS=YEAR,MONTH")
                .build();
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/V1__submission_parser_foundation.sql")).execute(database);
        jdbc = JdbcClient.create(database);
        jdbc.sql("""
                INSERT INTO organization (id, code, name, active)
                VALUES (1, 'TEST_A', '테스트 조직', TRUE),
                       (2, 'TEST_B', '비활성 조직', FALSE)
                """).update();
        jdbc.sql("""
                INSERT INTO app_user (id, email, name, role, organization_id, active)
                VALUES (1, 'planner@example.test', '테스트 기획', 'PLANNER', NULL, TRUE),
                       (2, 'manager@example.test', '테스트 담당', 'ORG_MANAGER', 1, TRUE),
                       (3, 'disabled@example.test', '비활성 담당', 'ORG_MANAGER', 1, FALSE),
                       (4, 'closed-org@example.test', '폐쇄 조직 담당', 'ORG_MANAGER', 2, TRUE)
                """).update();
        resolver = new ActorResolver(jdbc);
    }

    /**
     * 테스트의 인증 문맥과 메모리 DB를 해제한다.
     *
     * <p>Args: 없음.
     * Returns: 없음.
     * Raises: DB 종료가 실패하면 런타임 예외.
     * Side Effects: 메모리 자원을 해제하며 파일을 삭제하거나 생성하지 않는다.
     */
    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        if (database != null) {
            database.shutdown();
        }
    }

    /**
     * 인증 문맥이 없는 조회를 거부하는지 확인한다.
     *
     * <p>Args: 없음.
     * Returns: 없음.
     * Raises: 계약이 다르면 AssertionError.
     * Side Effects: 메모리 인증 문맥을 읽는다.
     */
    @Test
    void requiresAuthenticatedEmail() {
        assertThatThrownBy(resolver::current).isInstanceOf(InsufficientAuthenticationException.class);
    }

    /**
     * 인증 객체의 권한 주장 대신 DB의 역할과 조직을 사용하는지 확인한다.
     *
     * <p>Args: 없음.
     * Returns: 없음.
     * Raises: 조회 또는 권한 계약이 다르면 AssertionError.
     * Side Effects: 테스트 인증 문맥을 설정하고 메모리 DB를 읽는다.
     */
    @Test
    void resolvesRoleAndOrganizationFromDatabase() {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("manager@example.test", null,
                        List.of(new SimpleGrantedAuthority("ROLE_PLANNER"))));
        Actor actor = resolver.current();
        assertThat(actor.id()).isEqualTo("2");
        assertThat(actor.role()).isEqualTo("ORG_MANAGER");
        assertThat(actor.organization()).isEqualTo(new Organization("1", "TEST_A", "테스트 조직"));
        assertThat(actor.canAccessOrganization("1")).isTrue();
        assertThat(actor.canAccessOrganization("2")).isFalse();
        assertThatThrownBy(() -> actor.requireRole("PLANNER")).isInstanceOf(AccessDeniedException.class);
    }

    /**
     * 미등록·비활성 계정과 비활성 조직을 모두 거부하는지 확인한다.
     *
     * <p>Args: email은 각 거부 조건에 대응하는 가상 이메일이다.
     * Returns: 없음.
     * Raises: 인증 거부가 없으면 AssertionError.
     * Side Effects: 메모리 DB만 조회한다.
     */
    @ParameterizedTest
    @ValueSource(strings = {"missing@example.test", "disabled@example.test", "closed-org@example.test"})
    void rejectsUnavailableAccountsAndOrganizations(String email) {
        assertThatThrownBy(() -> resolver.resolveEmail(email)).isInstanceOf(BadCredentialsException.class);
    }

    /**
     * 기획 역할과 요청 중 DB에서 바뀐 활성 상태를 다시 확인하는지 검증한다.
     *
     * <p>Args: 없음.
     * Returns: 없음.
     * Raises: DB 갱신 또는 계약 불일치는 런타임 예외·AssertionError.
     * Side Effects: 메모리 DB의 사용자 활성 상태만 변경한다.
     */
    @Test
    void rechecksAccountStateInsteadOfCachingPermissions() {
        Actor actor = resolver.resolveEmail("planner@example.test");
        assertThat(actor.organization()).isNull();
        assertThat(actor.canAccessOrganization("2")).isTrue();
        assertThat(actor.canAccessOrganization(null)).isFalse();
        actor.requireRole("PLANNER");
        jdbc.sql("UPDATE app_user SET active = FALSE WHERE id = 1").update();
        assertThatThrownBy(() -> resolver.resolveEmail("planner@example.test"))
                .isInstanceOf(BadCredentialsException.class);
    }

    /** 고정 조직 principal의 인증 후 업무 조회 사이에 바뀐 소속·역할을 거부한다.
     * Args: 없음. Returns: 없음. Raises: DB 오류 또는 검증 실패.
     * Side Effects: 메모리 인증 문맥·테스트 사용자·조직을 변경한다.
     */
    @Test
    void pinnedOrganizationPrincipalRejectsChangesBeforeBusinessLookup() {
        Actor selected = resolver.resolveEmail("manager@example.test");
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(selected, null,
                        List.of(new SimpleGrantedAuthority("ROLE_ORG_MANAGER"))));
        assertThat(resolver.current()).isEqualTo(selected);
        jdbc.sql("UPDATE organization SET active=TRUE WHERE id=2").update();
        jdbc.sql("UPDATE app_user SET organization_id=2 WHERE id=2").update();
        assertThatThrownBy(resolver::current).isInstanceOf(BadCredentialsException.class);
        jdbc.sql("UPDATE app_user SET role='PLANNER',organization_id=NULL WHERE id=2").update();
        assertThatThrownBy(resolver::current).isInstanceOf(BadCredentialsException.class);
        jdbc.sql("UPDATE app_user SET role='ORG_MANAGER',organization_id=1 WHERE id=2").update();
        jdbc.sql("UPDATE organization SET code='CHANGED' WHERE id=1").update();
        assertThatThrownBy(resolver::current).isInstanceOf(BadCredentialsException.class);
    }
}
