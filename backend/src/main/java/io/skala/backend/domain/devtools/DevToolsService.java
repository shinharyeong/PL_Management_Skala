package io.skala.backend.domain.devtools;

import io.skala.backend.domain.user.dto.LoginResponse;
import io.skala.backend.domain.user.entity.AppUser;
import io.skala.backend.domain.user.repository.AppUserRepository;
import io.skala.backend.domain.user.service.MeService;
import io.skala.backend.global.security.SessionTokenStore;
import io.skala.backend.global.exception.BusinessException;
import io.skala.backend.global.exception.ErrorCode;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 개발 환경의 허용 계정 선택 로그인. URL은 권한 경계가 아니며 배포에서 명시적으로 켠다. */
@Service
public class DevToolsService {
    private final boolean enabled;
    private final List<String> allowed;
    private final AppUserRepository users;
    private final SessionTokenStore sessions;
    private final MeService profiles;
    public DevToolsService(@Value("${app.dev-tools.enabled:false}") boolean enabled,
            @Value("${app.dev-tools.allowed-emails:kim.plan@sk.com,f10.test@sk.com,f12.test@sk.com,f20.test@sk.com,f40.test@sk.com,f39.test@sk.com,f50.test@sk.com,f16.test@sk.com,f13.test@sk.com,f25.test@sk.com,f43.test@sk.com,f42.test@sk.com,f44.test@sk.com,f73.test@sk.com,f38.test@sk.com,f66.test@sk.com,f36.test@sk.com,f37.test@sk.com}") String allowed,
            AppUserRepository users, SessionTokenStore sessions, MeService profiles) {
        this.enabled=enabled; this.allowed=Arrays.stream(allowed.split(",")).map(s->s.trim().toLowerCase(Locale.ROOT)).filter(s->!s.isEmpty()).toList();
        this.users=users; this.sessions=sessions; this.profiles=profiles;
    }
    private void requireEnabled() {
        if (!enabled) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
    }
    public record Account(String email, String name, String hint) {}
    @Transactional(readOnly=true)
    public List<Account> accounts() {
        requireEnabled();
        return allowed.stream().map(users::findByEmailIgnoreCase).flatMap(java.util.Optional::stream)
            .filter(u->Boolean.TRUE.equals(u.getActive()))
            .map(u->new Account(u.getEmail(),u.getOrganization()==null?"경영기획팀":u.getOrganization().getName(),
                    u.getName()+" · "+(u.getOrganization()==null?"경영기획팀":u.getOrganization().getCode()))).toList();
    }
    @Transactional(readOnly=true)
    public LoginResponse login(String email) {
        requireEnabled();
        String normalized=email.trim().toLowerCase(Locale.ROOT);
        if (!allowed.contains(normalized)) throw new BusinessException(ErrorCode.FORBIDDEN);
        AppUser user=users.findByEmailIgnoreCase(normalized).filter(u->Boolean.TRUE.equals(u.getActive()))
                .orElseThrow(()->new BusinessException(ErrorCode.FORBIDDEN));
        SessionTokenStore.Issued issued=sessions.issue(user.getId());
        return new LoginResponse(issued.token(),issued.expiresAt(),profiles.me(user.getId()));
    }
    @Transactional(readOnly=true)
    public void requireEditor(Long userId) {
        requireEnabled();
        AppUser user=users.findById(userId).filter(u->Boolean.TRUE.equals(u.getActive()))
                .orElseThrow(()->new BusinessException(ErrorCode.FORBIDDEN));
        if (!allowed.contains(user.getEmail().toLowerCase(Locale.ROOT))) throw new BusinessException(ErrorCode.FORBIDDEN);
    }
}
