package uct8086.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for Qoder authentication.
 * <p>
 * Example usage in {@code application.yml}:
 * <pre>
 * qoder:
 *   auth:
 *     base-url: https://qoder.example.com
 *     login-path: /api/auth/login
 *     username: your-username
 *     password: your-password
 * </pre>
 */
@ConfigurationProperties(prefix = "qoder.auth")
public class QoderAuthProperties {

    /** Base URL of the Qoder authentication server. */
    private String baseUrl = "https://api.qoder.ai";

    /** Login endpoint path (relative to baseUrl). */
    private String loginPath = "/api/auth/login";

    /** Login username. */
    private String username;

    /** Login password. */
    private String password;

    /** Whether Qoder authentication is enabled. */
    private boolean enabled = true;

    // --- getters & setters ---

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getLoginPath() { return loginPath; }
    public void setLoginPath(String loginPath) { this.loginPath = loginPath; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
