package uct8086.ai.common.model;

/**
 * Tracks token usage and cost for API calls.
 * Maps to OpenHarness's token counting & cost tracking feature.
 */
public record TokenUsage(
        long inputTokens,
        long outputTokens,
        long totalTokens,
        double cost
) {
    public TokenUsage() {
        this(0, 0, 0, 0.0);
    }

    public TokenUsage add(TokenUsage other) {
        return new TokenUsage(
                this.inputTokens + other.inputTokens,
                this.outputTokens + other.outputTokens,
                this.totalTokens + other.totalTokens,
                this.cost + other.cost
        );
    }

    public static TokenUsage of(long inputTokens, long outputTokens) {
        return new TokenUsage(inputTokens, outputTokens, inputTokens + outputTokens, 0.0);
    }

    public static TokenUsage of(long inputTokens, long outputTokens, double cost) {
        return new TokenUsage(inputTokens, outputTokens, inputTokens + outputTokens, cost);
    }
}
