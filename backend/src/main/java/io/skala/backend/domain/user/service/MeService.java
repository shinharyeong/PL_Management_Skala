package io.skala.backend.domain.user.service;

import io.skala.backend.domain.user.dto.BadgeResponse;
import io.skala.backend.domain.user.dto.MeResponse;
import io.skala.backend.domain.user.entity.AppUser;
import io.skala.backend.domain.user.entity.Organization;
import io.skala.backend.domain.user.repository.AppUserRepository;
import io.skala.backend.domain.user.value.UserRole;
import io.skala.backend.global.exception.BusinessException;
import io.skala.backend.global.exception.ErrorCode;
import io.skala.backend.global.security.AuthenticatedUser;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 로그인 사용자 정보·메뉴(AUTH-04)와 메뉴 배지 건수(AUTH-05). */
@Service
@RequiredArgsConstructor
public class MeService {

    private static final List<MeResponse.Menu> PLANNER_MENUS = List.of(
            new MeResponse.Menu("NOTIFICATIONS", "알림 모아보기", "/planning/notifications"),
            new MeResponse.Menu("HOME", "전사 손익 현황", "/planning"),
            new MeResponse.Menu("MONTHLY", "월간 시트 관리", "/planning/monthly"),
            new MeResponse.Menu("ANNUAL", "연간 계획 관리", "/planning/annual"),
            new MeResponse.Menu("ORGANIZATIONS", "조직별 현황", "/planning/organizations"),
            new MeResponse.Menu("REPORT", "보고서 생성", "/planning/reports"));
    private static final List<MeResponse.Menu> ORG_MENUS = List.of(
            new MeResponse.Menu("INBOX", "요청함", "/requests"),
            new MeResponse.Menu("HISTORY", "제출 이력 관리", "/history"));

    private final AppUserRepository userRepository;
    private final JdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public MeResponse me(Long userId) {
        AppUser user = userRepository.findById(userId)
                .filter(found -> Boolean.TRUE.equals(found.getActive()))
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
        Organization organization = user.getOrganization();
        boolean planner = user.getRole() == UserRole.PLANNER;
        return new MeResponse(
                new MeResponse.User(String.valueOf(user.getId()), user.getName(), user.getEmail()),
                user.getRole(),
                organization == null ? null : new MeResponse.OrganizationRef(
                        String.valueOf(organization.getId()), organization.getCode(), organization.getName()),
                planner ? "/planning" : "/requests",
                planner ? PLANNER_MENUS : ORG_MENUS);
    }

    @Transactional(readOnly = true)
    public BadgeResponse badges(AuthenticatedUser user) {
        if (user.isPlanner()) {
            return new BadgeResponse(
                    count("SELECT count(*) FROM notification WHERE recipient_id=? AND read_at IS NULL", user.id()),
                    count("""
                            SELECT count(*) FROM review_item ri
                            JOIN submission s ON s.id = ri.submission_id
                            JOIN submission_request r ON r.id = s.request_id
                            WHERE r.type = 'MONTHLY_ACTUAL' AND s.status = 'PENDING' AND ri.active
                              AND ri.workflow_state = 'NEEDS_REVIEW'"""),
                    count("""
                            SELECT count(*) FROM submission s
                            JOIN submission_request r ON r.id = s.request_id
                            WHERE r.type = 'ANNUAL_PLAN' AND s.status = 'PENDING'"""),
                    null);
        }
        return new BadgeResponse(null, null, null, count("""
                SELECT count(*) FROM submission_request r
                JOIN request_target t ON t.request_id = r.id
                WHERE r.status = 'OPEN' AND t.organization_id = ? AND t.selected AND NOT t.excluded
                  AND COALESCE((SELECT s.status FROM submission s WHERE s.request_id = r.id
                                  AND s.organization_id = t.organization_id AND s.status <> 'DRAFT'
                                  ORDER BY s.seq DESC LIMIT 1), 'REJECTED') = 'REJECTED'""",
                user.organizationId()));
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }
}
