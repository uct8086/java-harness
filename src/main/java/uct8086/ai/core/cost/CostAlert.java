package uct8086.ai.core.cost;

/**
 * Cost alert event, published via Spring ApplicationEventPublisher when budget thresholds are exceeded.
 * <p>
 * Three alert types:
 * <ul>
 *   <li>{@link AlertType#SESSION_WARN} — per-session cost exceeded warn threshold</li>
 *   <li>{@link AlertType#SESSION_HARD_LIMIT} — per-session cost exceeded hard limit</li>
 *   <li>{@link AlertType#TOTAL_WARN} — total accumulated cost across all sessions exceeded warn threshold</li>
 * </ul>
 */
public class CostAlert {

    public enum AlertType {
        SESSION_WARN,
        SESSION_HARD_LIMIT,
        TOTAL_WARN
    }

    private final AlertType type;
    private final String sessionId;       // null for TOTAL_WARN
    private final double currentCost;     // CNY
    private final double threshold;       // CNY
    private final double totalCost;       // CNY (global total, for context)
    private final long timestamp;

    private CostAlert(AlertType type, String sessionId, double currentCost, double threshold, double totalCost) {
        this.type = type;
        this.sessionId = sessionId;
        this.currentCost = currentCost;
        this.threshold = threshold;
        this.totalCost = totalCost;
        this.timestamp = System.currentTimeMillis();
    }

    public static CostAlert sessionWarn(String sessionId, double sessionCost, double threshold, double totalCost) {
        return new CostAlert(AlertType.SESSION_WARN, sessionId, sessionCost, threshold, totalCost);
    }

    public static CostAlert hardLimit(String sessionId, double sessionCost, double limit, double totalCost) {
        return new CostAlert(AlertType.SESSION_HARD_LIMIT, sessionId, sessionCost, limit, totalCost);
    }

    public static CostAlert totalWarn(double totalCost, double threshold) {
        return new CostAlert(AlertType.TOTAL_WARN, null, totalCost, threshold, totalCost);
    }

    // Getters

    public AlertType getType() { return type; }
    public String getSessionId() { return sessionId; }
    public double getCurrentCost() { return currentCost; }
    public double getThreshold() { return threshold; }
    public double getTotalCost() { return totalCost; }
    public long getTimestamp() { return timestamp; }

    @Override
    public String toString() {
        return String.format("CostAlert[%s] session=%s, current=¥%.4f, threshold=¥%.4f, total=¥%.4f",
                type, sessionId, currentCost, threshold, totalCost);
    }
}
