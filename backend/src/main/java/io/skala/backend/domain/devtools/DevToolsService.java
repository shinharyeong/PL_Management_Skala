package io.skala.backend.domain.devtools;

import io.skala.backend.domain.user.dto.LoginResponse;
import io.skala.backend.domain.user.entity.AppUser;
import io.skala.backend.domain.user.repository.AppUserRepository;
import io.skala.backend.domain.user.service.MeService;
import io.skala.backend.global.exception.BusinessException;
import io.skala.backend.global.exception.ErrorCode;
import io.skala.backend.global.security.SessionTokenStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 개발용 계정 선택 로그인(/dev). DB의 활성 사용자를 그대로 보여 주고 이메일 인증 없이 세션을 발급한다.
 * 인증을 건너뛰는 기능이라 기본은 꺼져 있고(app.dev-tools.enabled), 켜도 접근 키(X-Dev-Access-Key)가 맞아야 한다.
 * 로컬(local 프로필)만 require-access-key=false로 키 없이 쓴다. 운영 클러스터는 꺼 둔다(k8s/backend.yaml).
 */
@Service
public class DevToolsService {

    public static final String ACCESS_KEY_HEADER = "X-Dev-Access-Key";
    private static final String PLANNER_TEAM = "경영기획팀";

    private final boolean enabled;
    private final boolean requireAccessKey;
    private final String accessKey;
    private final AppUserRepository users;
    private final SessionTokenStore sessions;
    private final MeService profiles;

    public DevToolsService(@Value("${app.dev-tools.enabled:false}") boolean enabled,
                           @Value("${app.dev-tools.require-access-key:true}") boolean requireAccessKey,
                           @Value("${app.dev-tools.access-key:}") String accessKey,
                           AppUserRepository users, SessionTokenStore sessions, MeService profiles) {
        this.enabled = enabled;
        this.requireAccessKey = requireAccessKey;
        this.accessKey = accessKey == null ? "" : accessKey.trim();
        this.users = users;
        this.sessions = sessions;
        this.profiles = profiles;
    }

    public record Account(String email, String name, String hint) {}

    /** DB의 활성 사용자 전체. 경영기획팀(조직 없음)을 먼저, 그다음 조직 코드·이메일 순. */
    @Transactional(readOnly = true)
    public List<Account> accounts(String providedKey) {
        requireAccess(providedKey);
        return users.findAll().stream()
                .filter(u -> Boolean.TRUE.equals(u.getActive()))
                .sorted(Comparator.comparing((AppUser u) -> u.getOrganization() == null ? "" : u.getOrganization().getCode())
                        .thenComparing(AppUser::getEmail))
                .map(u -> new Account(u.getEmail(), organizationName(u), u.getName() + " · " + organizationCode(u)))
                .toList();
    }

    @Transactional(readOnly = true)
    public LoginResponse login(String email, String providedKey) {
        requireAccess(providedKey);
        AppUser user = activeUser(email.trim());
        SessionTokenStore.Issued issued = sessions.issue(user.getId());
        return new LoginResponse(issued.token(), issued.expiresAt(), profiles.me(user.getId()));
    }

    /** 공통 문구 편집: 개발 도구가 켜져 있고 로그인한 사용자가 활성이면 허용한다. */
    @Transactional(readOnly = true)
    public void requireEditor(Long userId) {
        requireEnabled();
        users.findById(userId).filter(u -> Boolean.TRUE.equals(u.getActive()))
                .orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN));
    }

    private AppUser activeUser(String email) {
        return users.findByEmailIgnoreCase(email).filter(u -> Boolean.TRUE.equals(u.getActive()))
                .orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN));
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
    }

    private void requireAccess(String providedKey) {
        requireEnabled();
        if (!requireAccessKey) {
            return;
        }
        // 키를 설정하지 않았으면 누구도 들어올 수 없다(빈 키로 열리지 않게).
        if (accessKey.isEmpty() || providedKey == null
                || !MessageDigest.isEqual(accessKey.getBytes(StandardCharsets.UTF_8),
                providedKey.trim().getBytes(StandardCharsets.UTF_8))) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }

    private static String organizationName(AppUser user) {
        return user.getOrganization() == null ? PLANNER_TEAM : user.getOrganization().getName();
    }

    private static String organizationCode(AppUser user) {
        return user.getOrganization() == null ? PLANNER_TEAM : user.getOrganization().getCode();
    }
}
