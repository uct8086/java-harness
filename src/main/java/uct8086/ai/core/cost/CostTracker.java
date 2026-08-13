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
 * <p>
 * Features:
 * <ul>
 *   <li>Per-session and total token/cost accumulation</li>
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
    private final Map<String, TokenUsage> sessionUsage = new ConcurrentHashMap<>();
    // Atomic accumulators for thread-safe total usage aggregation (avoid lost updates).
    private final LongAdder totalInputTokens = new LongAdder();
    private final LongAdder totalOutputTokens = new LongAdder();
    private final DoubleAdder totalCostAdder = new DoubleAdder();
    private final List<BiConsumer<String, CostAlert>> alertListeners = new CopyOnWriteArrayList<>();

    public CostTracker(HarnessProperties properties, ApplicationEventPublisher eventPublisher) {
        this.properties = properties;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Record token usage for a session and check budget thresholds.
     */
    public void record(String sessionId, TokenUsage usage) {
        // Ensure cost is calculated if not already
        TokenUsage usageWithCost = usage.cost() == 0.0 && (usage.inputTokens() > 0 || usage.outputTokens() > 0)
                ? TokenUsage.of(usage.inputTokens(), usage.outputTokens(),
                        inputPrice(), outputPrice())
                : usage;

        TokenUsage sessionTotal = sessionUsage.merge(sessionId, usageWithCost, TokenUsage::add);
        totalInputTokens.add(usageWithCost.inputTokens());
        totalOutputTokens.add(usageWithCost.outputTokens());
        totalCostAdder.add(usageWithCost.cost());

        log.debug("Session [{}] cost: +¥{} (in={} out={}), session total=¥{}, global total=¥{}",
                sessionId, String.format("%.4f", usageWithCost.cost()),
                usageWithCost.inputTokens(), usageWithCost.outputTokens(),
                String.format("%.4f", sessionTotal.cost()),
                String.format("%.4f", totalCostAdder.sum()));

        if (!properties.isCostAlertEnabled()) {
            return;
        }

        checkBudget(sessionId, sessionTotal);
    }

    private void checkBudget(String sessionId, TokenUsage sessionTotal) {
        double sessionCost = sessionTotal.cost();
        double totalCost = totalCostAdder.sum();

        // Check session hard limit first
        double hardLimit = properties.getSessionCostHardLimit();
        if (hardLimit > 0 && sessionCost >= hardLimit) {
            CostAlert alert = CostAlert.hardLimit(sessionId, sessionCost, hardLimit, totalCost);
            log.error("COST HARD LIMIT EXCEEDED: session={}, sessionCost=¥{}, limit=¥{}, totalCost=¥{}",
                    sessionId, String.format("%.4f", sessionCost),
                    String.format("%.4f", hardLimit),
                    String.format("%.4f", totalCost));
            fireAlert(sessionId, alert);
            return;
        }

        // Check session warn threshold
        double sessionWarn = properties.getSessionCostWarnThreshold();
        if (sessionCost >= sessionWarn) {
            CostAlert alert = CostAlert.sessionWarn(sessionId, sessionCost, sessionWarn, totalCost);
            log.warn("COST WARNING (session): session={}, sessionCost=¥{}, threshold=¥{}, totalCost=¥{}",
                    sessionId, String.format("%.4f", sessionCost),
                    String.format("%.4f", sessionWarn),
                    String.format("%.4f", totalCost));
            fireAlert(sessionId, alert);
        }

        // Check total warn threshold
        double totalWarn = properties.getTotalCostWarnThreshold();
        if (totalCost >= totalWarn) {
            CostAlert alert = CostAlert.totalWarn(totalCost, totalWarn);
            log.warn("COST WARNING (total): totalCost=¥{}, threshold=¥{}",
                    String.format("%.4f", totalCost),
                    String.format("%.4f", totalWarn));
            fireAlert(null, alert);
        }
    }

    private void fireAlert(String sessionId, CostAlert alert) {
        // Publish as Spring event
        try {
            eventPublisher.publishEvent(alert);
        } catch (Exception e) {
            log.debug("No listeners for cost alert: {}", e.getMessage());
        }
        // Notify programmatic listeners
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
     * Get total usage for a session.
     */
    public TokenUsage getSessionUsage(String sessionId) {
        return sessionUsage.getOrDefault(sessionId, new TokenUsage());
    }

    /**
     * Get total usage across all sessions.
     */
    public TokenUsage getTotalUsage() {
        long input = totalInputTokens.sum();
        long output = totalOutputTokens.sum();
        return TokenUsage.of(input, output, totalCostAdder.sum());
    }

    /**
     * Reset usage for a session.
     */
    public void resetSession(String sessionId) {
        sessionUsage.remove(sessionId);
    }

    /**
     * Reset all usage.
     */
    public void resetAll() {
        sessionUsage.clear();
        totalInputTokens.reset();
        totalOutputTokens.reset();
        totalCostAdder.reset();
    }
}
