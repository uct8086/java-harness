package uct8086.ai.auth.service;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import uct8086.ai.auth.entity.AuthUserPrincipal;

/**
 * Facade for retrieving the currently authenticated user from the Spring Security
 * context. Provides a single, consistent way for business modules to obtain the
 * current {@code userId} (and other identity fields) without reaching into
 * {@code SecurityContextHolder} directly.
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    /**
     * Get the current principal, or {@code null} if not authenticated.
     */
    public static AuthUserPrincipal principal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthUserPrincipal principal) {
            return principal;
        }
        return null;
    }

    /**
     * Get the current user's id, or {@code null} if not authenticated.
     */
    public static Long id() {
        AuthUserPrincipal principal = principal();
        return principal != null ? principal.id() : null;
    }

    /**
     * Get the current user's id, throwing if not authenticated.
     */
    public static Long requireId() {
        Long id = id();
        if (id == null) {
            throw new IllegalStateException("No authenticated user in security context");
        }
        return id;
    }
}
