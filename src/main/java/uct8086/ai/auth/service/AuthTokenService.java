package uct8086.ai.auth.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import uct8086.ai.auth.entity.AuthUserPrincipal;

import java.time.Duration;
import java.util.UUID;

/**
 * Issues and validates opaque auth tokens stored in Redis.
 *
 * <p>Token format: random UUID. Stored in Redis under {@code auth:token:<uuid>} with
 * the user's identity (id/username/roles) serialized as JSON, with a TTL. This is
 * simpler than JWT, supports immediate revocation, and is stateless across app nodes.
 */
@Service
public class AuthTokenService {

    private static final Logger log = LoggerFactory.getLogger(AuthTokenService.class);

    private static final String KEY_PREFIX = "auth:token:";
    private static final Duration TTL = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public AuthTokenService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Issue a new token for the given principal.
     */
    public String issueToken(AuthUserPrincipal principal) {
        String token = UUID.randomUUID().toString().replace("-", "");
        try {
            String json = objectMapper.writeValueAsString(principal);
            redisTemplate.opsForValue().set(KEY_PREFIX + token, json, TTL);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize principal", e);
        }
        return token;
    }

    /**
     * Resolve a token to its principal, or {@code null} if invalid/expired.
     */
    public AuthUserPrincipal resolve(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String json = redisTemplate.opsForValue().get(KEY_PREFIX + token);
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, AuthUserPrincipal.class);
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize principal for token", e);
            return null;
        }
    }

    /**
     * Invalidate a token (logout).
     */
    public void revoke(String token) {
        if (token != null && !token.isBlank()) {
            redisTemplate.delete(KEY_PREFIX + token);
        }
    }
}
