package uct8086.ai.core.cost;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import uct8086.ai.common.model.TokenUsage;
import uct8086.ai.core.config.HarnessProperties;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BiConsumer;

/**
 * Tracks token usage and cost across sessions with budget alerting.
 *
 * <p>Usage is scoped per user: each user has their own per-session and total
 * accumulation, isolated from other users.
 *
 * <ul>
 *   <li>Per-user, per-session and total token/cost accumulation</li>
 *   <li>Session-level cost warn/limit checks</li>
 *   <li>Total accumulated cost warn threshold</li>
 *   <li>Log-based alerting (WARN/ERROR)</li>
 *   <li>Programmatic alert listeners for external integration</li>
 * </ul>
 */
@Component
public class CostTracker {

    private static final Logger log = LoggerFactory.getLogger(CostTracker.class);

    private final HarnessProperties properties;
    private final ApplicationEventPublisher eventPublisher;

    // userId -> sessionId -> usage
    private final Map<Long, Map<String, TokenUsage>> sessionUsage = new ConcurrentHashMap<>();
    // userId -> total accumulators (atomic)
    private final Map<Long, LongAdder> totalInputByUser = new ConcurrentHashMap<>();
    private final Map<Long, LongAdder> totalOutputByUser = new ConcurrentHashMap<>();
    private final Map<Long, DoubleAdder> totalCostByUser = new ConcurrentHashMap<>();

    private final List<BiConsumer<String, CostAlert>> alertListeners = new CopyOnWriteArrayList<>();

    public CostTracker(HarnessProperties properties, ApplicationEventPublisher eventPublisher) {
        this.properties = properties;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Record token usage for a user + session and check budget thresholds.
     */
    public void record(Long userId, String sessionId, TokenUsage usage) {
        TokenUsage usageWithCost = usage.cost() == 0.0 && (usage.inputTokens() > 0 || usage.outputTokens() > 0)
                ? TokenUsage.of(usage.inputTokens(), usage.outputTokens(),
                        inputPrice(), outputPrice())
                : usage;

        Map<String, TokenUsage> userSessions = sessionUsage.computeIfAbsent(userId, k -> new ConcurrentHashMap<>());
        TokenUsage sessionTotal = userSessions.merge(sessionId, usageWithCost, TokenUsage::add);

        totalInputByUser.computeIfAbsent(userId, k -> new LongAdder()).add(usageWithCost.inputTokens());
        totalOutputByUser.computeIfAbsent(userId, k -> new LongAdder()).add(usageWithCost.outputTokens());
        totalCostByUser.computeIfAbsent(userId, k -> new DoubleAdder()).add(usageWithCost.cost());

        log.debug("User [{}] session [{}] cost: +¥{} (in={} out={}), session total=¥{}, user total=¥{}",
                userId, sessionId, String.format("%.4f", usageWithCost.cost()),
                usageWithCost.inputTokens(), usageWithCost.outputTokens(),
                String.format("%.4f", sessionTotal.cost()),
                String.format("%.4f", totalCostByUser.get(userId).sum()));

        if (!properties.isCostAlertEnabled()) {
            return;
        }

        checkBudget(userId, sessionId, sessionTotal);
    }

    private void checkBudget(Long userId, String sessionId, TokenUsage sessionTotal) {
        double sessionCost = sessionTotal.cost();
        double totalCost = totalCostByUser.getOrDefault(userId, new DoubleAdder()).sum();

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
     * Get total usage for a user + session.
     */
    public TokenUsage getSessionUsage(Long userId, String sessionId) {
        Map<String, TokenUsage> userSessions = sessionUsage.get(userId);
        if (userSessions == null) {
            return new TokenUsage();
        }
        return userSessions.getOrDefault(sessionId, new TokenUsage());
    }

    /**
     * Get total usage across all sessions for a user.
     */
    public TokenUsage getTotalUsage(Long userId) {
        long input = totalInputByUser.getOrDefault(userId, new LongAdder()).sum();
        long output = totalOutputByUser.getOrDefault(userId, new LongAdder()).sum();
        double cost = totalCostByUser.getOrDefault(userId, new DoubleAdder()).sum();
        return TokenUsage.of(input, output, cost);
    }

    /**
     * Reset usage for a user + session.
     */
    public void resetSession(Long userId, String sessionId) {
        Map<String, TokenUsage> userSessions = sessionUsage.get(userId);
        if (userSessions != null) {
            userSessions.remove(sessionId);
        }
    }

    /**
     * Reset all usage for a user.
     */
    public void resetAll(Long userId) {
        Map<String, TokenUsage> userSessions = sessionUsage.get(userId);
        if (userSessions != null) {
            userSessions.clear();
        }
        LongAdder in = totalInputByUser.get(userId);
        if (in != null) in.reset();
        LongAdder out = totalOutputByUser.get(userId);
        if (out != null) out.reset();
        DoubleAdder cost = totalCostByUser.get(userId);
        if (cost != null) cost.reset();
    }
}
