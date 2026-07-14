package uct8086.ai.core.cost;

import uct8086.ai.common.model.TokenUsage;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Tracks token usage and cost across sessions.
 * Maps to OpenHarness's Token Counting & Cost Tracking feature.
 */
@Component
public class CostTracker {

    private final Map<String, TokenUsage> sessionUsage = new ConcurrentHashMap<>();
    private TokenUsage totalUsage = new TokenUsage();

    /**
     * Record token usage for a session.
     */
    public void record(String sessionId, TokenUsage usage) {
        sessionUsage.merge(sessionId, usage, TokenUsage::add);
        totalUsage = totalUsage.add(usage);
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
        return totalUsage;
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
        totalUsage = new TokenUsage();
    }
}
