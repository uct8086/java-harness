package uct8086.ai.auth.controller;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import uct8086.ai.auth.entity.AuthUserPrincipal;
import uct8086.ai.auth.service.AuthService;
import uct8086.ai.auth.service.AuthTokenService;

import java.util.Map;
import java.util.Optional;

/**
 * Authentication endpoints: login / logout / current user.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public static final String AUTH_COOKIE = "auth_token";

    private final AuthService authService;
    private final AuthTokenService tokenService;

    public AuthController(AuthService authService, AuthTokenService tokenService) {
        this.authService = authService;
        this.tokenService = tokenService;
    }

    /**
     * Login with username/password. On success sets the auth_token cookie and returns user info.
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request, HttpServletResponse response) {
        Optional<AuthUserPrincipal> principal =
                authService.authenticate(request.username(), request.password());
        if (principal.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "用户名或密码错误"));
        }

        AuthUserPrincipal user = principal.get();
        String token = tokenService.issueToken(user);

        Cookie cookie = new Cookie(AUTH_COOKIE, token);
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setMaxAge(24 * 60 * 60);
        response.addCookie(cookie);

        return ResponseEntity.ok(Map.of(
                "id", user.id(),
                "username", user.username(),
                "displayName", user.displayName() == null ? user.username() : user.displayName(),
                "roles", user.roles()
        ));
    }

    /**
     * Logout: revoke token and clear the cookie.
     */
    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest request, HttpServletResponse response) {
        String token = extractToken(request);
        if (token != null) {
            tokenService.revoke(token);
        }
        Cookie cookie = new Cookie(AUTH_COOKIE, "");
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setMaxAge(0);
        response.addCookie(cookie);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /**
     * Return the currently authenticated user.
     */
    @GetMapping("/me")
    public ResponseEntity<?> me() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthUserPrincipal principal)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "未登录"));
        }
        return ResponseEntity.ok(Map.of(
                "id", principal.id(),
                "username", principal.username(),
                "displayName", principal.displayName() == null ? principal.username() : principal.displayName(),
                "roles", principal.roles()
        ));
    }

    private String extractToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie c : cookies) {
            if (AUTH_COOKIE.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }

    public record LoginRequest(String username, String password) {
    }
}
