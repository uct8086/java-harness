package uct8086.ai.auth.entity;

import java.util.List;

/**
 * Authenticated user identity carried in the auth token.
 */
public record AuthUserPrincipal(
        Long id,
        String username,
        String displayName,
        List<String> roles
) {
}
