package uct8086.ai.core.cost;

/**
 * Aggregated token/cost totals returned by {@link CostUsageMapper} SUM queries.
 * Used as a MyBatis result mapping target.
 */
public class UsageAggregate {

    private long inputTokens;
    private long outputTokens;
    private long totalTokens;
    private double cost;

    public long getInputTokens() { return inputTokens; }
    public void setInputTokens(long inputTokens) { this.inputTokens = inputTokens; }
    public long getOutputTokens() { return outputTokens; }
    public void setOutputTokens(long outputTokens) { this.outputTokens = outputTokens; }
    public long getTotalTokens() { return totalTokens; }
    public void setTotalTokens(long totalTokens) { this.totalTokens = totalTokens; }
    public double getCost() { return cost; }
    public void setCost(double cost) { this.cost = cost; }
}
