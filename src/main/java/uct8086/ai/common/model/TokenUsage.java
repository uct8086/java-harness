package uct8086.ai.common.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Tracks token usage and cost for API calls.
 * Maps to OpenHarness's token counting & cost tracking feature.
 *
 * Pricing is per 1M tokens (USD), converted at configurable rate.
 * Default pricing: DeepSeek V4 Pro (input=$0.435/M, output=$0.28/M, cache hit input=$0.0036/M).
 * Source: DeepSeek official permanent pricing as of 2026-08.
 */
public record TokenUsage(
        long inputTokens,
        long outputTokens,
        long totalTokens,
        double cost
) {
    /** Price per 1M input tokens (cache miss, USD) — DeepSeek V4 Pro */
    public static final double DEFAULT_INPUT_PRICE_PER_M = 0.435;
    /** Price per 1M output tokens (USD) — DeepSeek V4 Pro */
    public static final double DEFAULT_OUTPUT_PRICE_PER_M = 0.28;
    /** USD → CNY conversion rate (approximate) */
    public static final double USD_TO_CNY = 7.25;

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

    /**
     * Create TokenUsage with cost calculated from token counts using default pricing.
     * Cost is in CNY (yuan).
     */
    public static TokenUsage of(long inputTokens, long outputTokens) {
        double costUsd = (inputTokens / 1_000_000.0) * DEFAULT_INPUT_PRICE_PER_M
                       + (outputTokens / 1_000_000.0) * DEFAULT_OUTPUT_PRICE_PER_M;
        double costCny = round2(costUsd * USD_TO_CNY);
        return new TokenUsage(inputTokens, outputTokens, inputTokens + outputTokens, costCny);
    }

    /**
     * Create TokenUsage with cost calculated using custom pricing per 1M tokens (USD).
     */
    public static TokenUsage of(long inputTokens, long outputTokens,
                                 double inputPricePerM, double outputPricePerM) {
        double costUsd = (inputTokens / 1_000_000.0) * inputPricePerM
                       + (outputTokens / 1_000_000.0) * outputPricePerM;
        double costCny = round2(costUsd * USD_TO_CNY);
        return new TokenUsage(inputTokens, outputTokens, inputTokens + outputTokens, costCny);
    }

    /**
     * Create TokenUsage with pre-calculated cost (no pricing logic applied).
     */
    public static TokenUsage of(long inputTokens, long outputTokens, double cost) {
        return new TokenUsage(inputTokens, outputTokens, inputTokens + outputTokens, cost);
    }

    private static double round2(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }
}
