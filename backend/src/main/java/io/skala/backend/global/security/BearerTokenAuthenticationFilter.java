package io.skala.backend.global.security;

import io.skala.backend.domain.user.entity.AppUser;
import io.skala.backend.domain.user.repository.AppUserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Authorization: Bearer <token>을 읽어 로그인 사용자를 SecurityContext에 넣는다. 없거나 틀리면 익명으로 둔다. */
@RequiredArgsConstructor
public class BearerTokenAuthenticationFilter extends OncePerRequestFilter {

    public static final String TOKEN_ATTRIBUTE = BearerTokenAuthenticationFilter.class.getName() + ".token";

    private final SessionTokenStore sessionTokenStore;
    private final AppUserRepository appUserRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String token = header.substring(7).trim();
            sessionTokenStore.resolve(token)
                    .flatMap(appUserRepository::findById)
                    .filter(user -> Boolean.TRUE.equals(user.getActive()))
                    .ifPresent(user -> authenticate(request, token, user));
        }
        chain.doFilter(request, response);
    }

    private void authenticate(HttpServletRequest request, String token, AppUser user) {
        Long organizationId = user.getOrganization() == null ? null : user.getOrganization().getId();
        AuthenticatedUser principal = new AuthenticatedUser(user.getId(), user.getRole(), organizationId);
        var authentication = new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        request.setAttribute(TOKEN_ATTRIBUTE, token);
    }
}
