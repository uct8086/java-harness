package uct8086.ai.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Instant;

/**
 * Service that handles Qoder authentication via token exchange.
 * <p>
 * On first call (or when the token expires), it authenticates with the
 * configured username/password and caches the resulting Bearer token.
 * Subsequent calls return the cached token until it is near expiry.
 * <p>
 * The token response is expected to be a JSON object containing at least:
 * <pre>{"token": "...", "expiresIn": 3600}</pre>
 * Adjust the field names via the constants below if your Qoder server
 * uses different keys (e.g. {@code access_token}, {@code expires_in}).
 */
@Service
@ConditionalOnProperty(name = "qoder.auth.enabled", havingValue = "true", matchIfMissing = true)
public class QoderTokenService {

    private static final Logger log = LoggerFactory.getLogger(QoderTokenService.class);

    /** JSON field name for the access token in the login response. */
    private static final String TOKEN_FIELD = "token";

    /** JSON field name for token TTL in seconds (fallback if absent = 1 hour). */
    private static final String EXPIRES_IN_FIELD = "expiresIn";

    /** Refresh the token 60 seconds before it actually expires. */
    private static final long REFRESH_BUFFER_SECONDS = 60L;

    private final QoderAuthProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    private volatile String cachedToken;
    private volatile Instant tokenExpiresAt = Instant.MIN;

    public QoderTokenService(QoderAuthProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.create(properties.getBaseUrl());
    }

    /**
     * Returns a valid Bearer token, performing login if the current
     * token is missing or about to expire.
     */
    public synchronized String getToken() {
        if (cachedToken != null && Instant.now().plusSeconds(REFRESH_BUFFER_SECONDS).isBefore(tokenExpiresAt)) {
            return cachedToken;
        }
        return login();
    }

    /**
     * Force a token refresh on the next call to {@link #getToken()}.
     */
    public synchronized void invalidate() {
        this.cachedToken = null;
        this.tokenExpiresAt = Instant.MIN;
        log.info("Qoder token invalidated; next request will trigger re-login.");
    }

    // -----------------------------------------------------------------
    // Internal
    // -----------------------------------------------------------------

    private String login() {
        log.info("Authenticating with Qoder: {}{}", properties.getBaseUrl(), properties.getLoginPath());

        try {
            String body = """
                    {"username":"%s","password":"%s"}
                    """.formatted(properties.getUsername(), properties.getPassword());

            String responseBody = restClient.post()
                    .uri(properties.getLoginPath())
                    .header("Content-Type", "application/json")
                    .body(body)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(responseBody);

            // Extract token — try common field names
            String token = extractField(root, TOKEN_FIELD, "access_token", "data.token");
            if (token == null || token.isBlank()) {
                throw new IllegalStateException(
                        "Qoder login response did not contain a token field. Response: " + responseBody);
            }

            // Extract TTL — default to 1 hour
            long ttlSeconds = 3600L;
            JsonNode expiresNode = root.path(EXPIRES_IN_FIELD);
            if (expiresNode.isNumber()) {
                ttlSeconds = expiresNode.asLong();
            } else {
                // Also try expires_in (snake_case)
                JsonNode altNode = root.path("expires_in");
                if (altNode.isNumber()) {
                    ttlSeconds = altNode.asLong();
                }
            }

            this.cachedToken = token;
            this.tokenExpiresAt = Instant.now().plusSeconds(ttlSeconds);

            log.info("Qoder login successful, token expires at {}", tokenExpiresAt);
            return token;

        } catch (Exception e) {
            log.error("Qoder authentication failed", e);
            throw new RuntimeException("Failed to authenticate with Qoder", e);
        }
    }

    /**
     * Tries multiple JSON field paths to extract the token value.
     * Supports dot-notation for nested fields (e.g. "data.token").
     */
    private static String extractField(JsonNode root, String... paths) {
        for (String path : paths) {
            JsonNode node = root;
            for (String segment : path.split("\\.")) {
                node = node.path(segment);
            }
            if (node.isTextual() && !node.asText().isBlank()) {
                return node.asText();
            }
        }
        return null;
    }
}
