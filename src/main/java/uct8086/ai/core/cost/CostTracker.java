package uct8086.ai.core.cost;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import uct8086.ai.auth.entity.UserEntity;
import uct8086.ai.auth.mapper.UserMapper;
import uct8086.ai.common.exception.CostLimitExceededException;
import uct8086.ai.common.model.TokenUsage;
import uct8086.ai.core.config.HarnessProperties;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/**
 * Tracks token usage and cost across sessions with budget alerting.
 *
 * <p>Usage records are persisted to MySQL ({@code cost_usage} table) instead of
 * in-memory accumulators, so costs survive restarts and aggregate correctly across
 * horizontally-scaled instances. All queries are scoped per user.
 *
 * <ul>
 *   <li>Per-user, per-session and total token/cost accumulation (via SQL SUM)</li>
 *   <li>Session-level cost warn/limit checks</li>
 *   <li>Total accumulated cost warn threshold</li>
 *   <li>Log-based alerting (WARN/ERROR)</li>
 *   <li>Programmatic alert listeners for external integration</li>
 * </ul>
 */
@Component
public class CostTracker {

    private static final Logger log = LoggerFactory.getLogger(CostTracker.class);

    /** Redis key prefix for the circuit-breaker flag: harness:cost:breaker:{userId} */
    private static final String BREAKER_PREFIX = "harness:cost:breaker:";

    private final HarnessProperties properties;
    private final ApplicationEventPublisher eventPublisher;
    private final CostUsageMapper costUsageMapper;
    private final StringRedisTemplate redisTemplate;
    private final UserMapper userMapper;

    private final List<BiConsumer<String, CostAlert>> alertListeners = new CopyOnWriteArrayList<>();

    public CostTracker(HarnessProperties properties,
                       ApplicationEventPublisher eventPublisher,
                       CostUsageMapper costUsageMapper,
                       StringRedisTemplate redisTemplate,
                       UserMapper userMapper) {
        this.properties = properties;
        this.eventPublisher = eventPublisher;
        this.costUsageMapper = costUsageMapper;
        this.redisTemplate = redisTemplate;
        this.userMapper = userMapper;
    }

    /**
     * Enforce the user-level cost quota before a request is processed. If the user's
     * total cost has exceeded the configured hard limit, throws
     * {@link CostLimitExceededException} to reject the request (circuit breaker).
     */
    public void assertQuota(Long userId) {
        if (!properties.isCostBreakerEnabled()) {
            return;
        }
        double hardLimit = properties.getUserCostHardLimit();
        if (hardLimit <= 0) {
            return;
        }
        double totalCost = getTotalUsage(userId).cost();
        if (totalCost >= hardLimit) {
            markBreaker(userId);
            throw new CostLimitExceededException(String.format(
                    "用户「%s」成本已达上限 ¥%.4f（上限 ¥%.4f），已熔断，请管理员调整配额",
                    resolveUserName(userId), totalCost, hardLimit));
        }
    }

    /**
     * Resolve a display-friendly name for the user (display name, falling back to
     * username, then the raw id).
     */
    private String resolveUserName(Long userId) {
        try {
            UserEntity user = userMapper.selectById(userId);
            if (user != null) {
                if (user.getDisplayName() != null && !user.getDisplayName().isBlank()) {
                    return user.getDisplayName();
                }
                if (user.getUsername() != null && !user.getUsername().isBlank()) {
                    return user.getUsername();
                }
            }
        } catch (Exception e) {
            log.warn("Failed to resolve user name for {}", userId, e);
        }
        return String.valueOf(userId);
    }

    /**
     * Whether the user is currently circuit-broken (over quota).
     */
    public boolean isBreakerTripped(Long userId) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(BREAKER_PREFIX + userId));
        } catch (Exception e) {
            return false;
        }
    }

    private void markBreaker(Long userId) {
        try {
            redisTemplate.opsForValue().set(BREAKER_PREFIX + userId, "1", Duration.ofHours(24));
        } catch (Exception e) {
            log.warn("Failed to set circuit-breaker flag for user {}", userId, e);
        }
    }

    /**
     * Reset the circuit-breaker flag for a user (admin action after adjusting quota).
     */
    public void resetBreaker(Long userId) {
        try {
            redisTemplate.delete(BREAKER_PREFIX + userId);
            log.info("Circuit breaker reset for user {}", userId);
        } catch (Exception e) {
            log.warn("Failed to reset circuit-breaker flag for user {}", userId, e);
        }
    }

    /**
     * Record token usage for a user + session and check budget thresholds.
     * Persists a usage detail row, then reads back the aggregated session total
     * to drive alerting.
     */
    public void record(Long userId, String sessionId, TokenUsage usage) {
        TokenUsage usageWithCost = usage.cost() == 0.0 && (usage.inputTokens() > 0 || usage.outputTokens() > 0)
                ? TokenUsage.of(usage.inputTokens(), usage.outputTokens(),
                        inputPrice(), outputPrice())
                : usage;

        CostUsageEntity entity = new CostUsageEntity();
        entity.setUserId(userId);
        entity.setSessionId(sessionId);
        entity.setInputTokens(usageWithCost.inputTokens());
        entity.setOutputTokens(usageWithCost.outputTokens());
        entity.setTotalTokens(usageWithCost.totalTokens());
        entity.setCost(usageWithCost.cost());
        entity.setCreatedAt(LocalDateTime.now());
        costUsageMapper.insert(entity);

        log.debug("User [{}] session [{}] cost: +¥{} (in={} out={})",
                userId, sessionId, String.format("%.4f", usageWithCost.cost()),
                usageWithCost.inputTokens(), usageWithCost.outputTokens());

        if (!properties.isCostAlertEnabled()) {
            return;
        }

        TokenUsage sessionTotal = getSessionUsage(userId, sessionId);
        checkBudget(userId, sessionId, sessionTotal);
    }

    private void checkBudget(Long userId, String sessionId, TokenUsage sessionTotal) {
        double sessionCost = sessionTotal.cost();
        double totalCost = getTotalUsage(userId).cost();

        double hardLimit = properties.getSessionCostHardLimit();
        if (hardLimit > 0 && sessionCost >= hardLimit) {
            CostAlert alert = CostAlert.hardLimit(sessionId, sessionCost, hardLimit, totalCost);
            log.error("COST HARD LIMIT EXCEEDED: user={}, session={}, sessionCost=¥{}, limit=¥{}, totalCost=¥{}",
                    userId, sessionId, String.format("%.4f", sessionCost),
                    String.format("%.4f", hardLimit),
                    String.format("%.4f", totalCost));
            fireAlert(sessionId, alert);
            return;
        }

        double sessionWarn = properties.getSessionCostWarnThreshold();
        if (sessionCost >= sessionWarn) {
            CostAlert alert = CostAlert.sessionWarn(sessionId, sessionCost, sessionWarn, totalCost);
            log.warn("COST WARNING (session): user={}, session={}, sessionCost=¥{}, threshold=¥{}, totalCost=¥{}",
                    userId, sessionId, String.format("%.4f", sessionCost),
                    String.format("%.4f", sessionWarn),
                    String.format("%.4f", totalCost));
            fireAlert(sessionId, alert);
        }

        double totalWarn = properties.getTotalCostWarnThreshold();
        if (totalCost >= totalWarn) {
            CostAlert alert = CostAlert.totalWarn(totalCost, totalWarn);
            log.warn("COST WARNING (total): user={}, totalCost=¥{}, threshold=¥{}",
                    userId,
                    String.format("%.4f", totalCost),
                    String.format("%.4f", totalWarn));
            fireAlert(null, alert);
        }

        // User-level hard limit → trip the circuit breaker.
        double userHardLimit = properties.getUserCostHardLimit();
        if (properties.isCostBreakerEnabled() && userHardLimit > 0 && totalCost >= userHardLimit) {
            markBreaker(userId);
            log.error("COST USER HARD LIMIT EXCEEDED: user={}, totalCost=¥{}, limit=¥{}, circuit breaker tripped",
                    userId,
                    String.format("%.4f", totalCost),
                    String.format("%.4f", userHardLimit));
        }
    }

    private void fireAlert(String sessionId, CostAlert alert) {
        try {
            eventPublisher.publishEvent(alert);
        } catch (Exception e) {
            log.debug("No listeners for cost alert: {}", e.getMessage());
        }
        for (BiConsumer<String, CostAlert> listener : alertListeners) {
            try {
                listener.accept(sessionId, alert);
            } catch (Exception ex) {
                log.warn("Alert listener failed", ex);
            }
        }
    }

    /**
     * Register a programmatic alert listener.
     * @param listener receives (sessionId, alert) — sessionId may be null for total alerts
     */
    public void addAlertListener(BiConsumer<String, CostAlert> listener) {
        alertListeners.add(listener);
    }

    public void removeAlertListener(BiConsumer<String, CostAlert> listener) {
        alertListeners.remove(listener);
    }

    private double inputPrice() {
        Double p = properties.getInputPricePerM();
        return p != null ? p : TokenUsage.DEFAULT_INPUT_PRICE_PER_M;
    }

    private double outputPrice() {
        Double p = properties.getOutputPricePerM();
        return p != null ? p : TokenUsage.DEFAULT_OUTPUT_PRICE_PER_M;
    }

    /**
     * Get total usage for a user + session (aggregated from persisted records).
     */
    public TokenUsage getSessionUsage(Long userId, String sessionId) {
        UsageAggregate agg = costUsageMapper.sumByUserAndSession(userId, sessionId);
        if (agg == null) {
            return new TokenUsage();
        }
        return TokenUsage.of(agg.getInputTokens(), agg.getOutputTokens(), agg.getCost());
    }

    /**
     * Get total usage across all sessions for a user (aggregated from persisted records).
     */
    public TokenUsage getTotalUsage(Long userId) {
        UsageAggregate agg = costUsageMapper.sumByUser(userId);
        if (agg == null) {
            return new TokenUsage();
        }
        return TokenUsage.of(agg.getInputTokens(), agg.getOutputTokens(), agg.getCost());
    }

    /**
     * Reset usage for a user + session (deletes persisted records).
     */
    public void resetSession(Long userId, String sessionId) {
        costUsageMapper.deleteByUserAndSession(userId, sessionId);
    }

    /**
     * Reset all usage for a user (deletes persisted records).
     */
    public void resetAll(Long userId) {
        costUsageMapper.deleteByUser(userId);
    }
}
