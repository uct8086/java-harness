package uct8086.ai.auth.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import uct8086.ai.auth.controller.AuthController;
import uct8086.ai.auth.entity.AuthUserPrincipal;
import uct8086.ai.auth.service.AuthTokenService;

import java.io.IOException;
import java.util.List;

/**
 * Reads the auth_token cookie, resolves it to a principal, and populates the
 * Spring Security context. Runs once per request.
 */
@Component
public class AuthTokenFilter extends OncePerRequestFilter {

    private final AuthTokenService tokenService;

    public AuthTokenFilter(AuthTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = extractToken(request);
        if (token != null) {
            AuthUserPrincipal principal = tokenService.resolve(token);
            if (principal != null) {
                List<SimpleGrantedAuthority> authorities = principal.roles().stream()
                        .map(SimpleGrantedAuthority::new)
                        .toList();
                var authentication = new UsernamePasswordAuthenticationToken(
                        principal, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(authentication);
                request.setAttribute("authPrincipal", principal);
            }
        }
        filterChain.doFilter(request, response);
    }

    private String extractToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie c : cookies) {
            if (AuthController.AUTH_COOKIE.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }
}
