package io.skala.backend.domain.submission.support;

import io.skala.backend.domain.user.entity.AppUser;
import io.skala.backend.domain.user.repository.AppUserRepository;
import io.skala.backend.global.exception.BusinessException;
import io.skala.backend.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 로그인 사용자 조회.
 * TODO(auth): 인증 필터가 들어오면 그 principal 형식에 맞춘다. 지금은 Authentication#getName()을 app_user.id로 읽는다.
 */
@Component
@RequiredArgsConstructor
public class CurrentActor {

    private final AppUserRepository appUserRepository;

    public AppUser get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
        long id;
        try {
            id = Long.parseLong(auth.getName());
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
        return appUserRepository.findById(id)
                .filter(u -> Boolean.TRUE.equals(u.getActive()))
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
    }
}
