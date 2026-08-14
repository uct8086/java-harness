package uct8086.ai.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Micrometer metrics for chat/agent-loop observability.
 *
 * <p>Exposes Prometheus metrics (via {@code /actuator/prometheus}):
 * <ul>
 *   <li>{@code uct8086_chat_requests_total} — total chat requests (by outcome)</li>
 *   <li>{@code uct8086_chat_duration_seconds} — agent-loop wall time</li>
 *   <li>{@code uct8086_chat_tokens_total} — token consumption (by type)</li>
 *   <li>{@code uct8086_chat_tool_calls_total} — tool invocation count</li>
 *   <li>{@code uct8086_chat_turns_total} — agent loop turns</li>
 * </ul>
 */
@Component
public class ChatMetrics {

    private final Counter requests;
    private final Counter errors;
    private final Timer duration;
    private final DistributionSummary inputTokens;
    private final DistributionSummary outputTokens;
    private final Counter toolCalls;
    private final Counter turns;

    public ChatMetrics(MeterRegistry registry) {
        this.requests = Counter.builder("uct8086.chat.requests")
                .description("Total chat requests")
                .register(registry);
        this.errors = Counter.builder("uct8086.chat.errors")
                .description("Total chat request failures")
                .register(registry);
        this.duration = Timer.builder("uct8086.chat.duration")
                .description("Agent loop wall time")
                .register(registry);
        this.inputTokens = DistributionSummary.builder("uct8086.chat.tokens.input")
                .description("Input (prompt) tokens per request")
                .register(registry);
        this.outputTokens = DistributionSummary.builder("uct8086.chat.tokens.output")
                .description("Output (completion) tokens per request")
                .register(registry);
        this.toolCalls = Counter.builder("uct8086.chat.tool_calls")
                .description("Total tool invocations")
                .register(registry);
        this.turns = Counter.builder("uct8086.chat.turns")
                .description("Total agent loop turns")
                .register(registry);
    }

    /** Record a successful chat request. */
    public void recordSuccess(long elapsedMs, long inputTokens, long outputTokens, int turns, int toolCalls) {
        requests.increment();
        duration.record(elapsedMs, TimeUnit.MILLISECONDS);
        this.inputTokens.record(inputTokens);
        this.outputTokens.record(outputTokens);
        this.turns.increment(turns);
        this.toolCalls.increment(toolCalls);
    }

    /** Record a failed chat request. */
    public void recordFailure(long elapsedMs) {
        requests.increment();
        errors.increment();
        duration.record(elapsedMs, TimeUnit.MILLISECONDS);
    }
}
