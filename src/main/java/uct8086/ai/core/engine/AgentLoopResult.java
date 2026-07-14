package uct8086.ai.core.engine;

import uct8086.ai.common.model.TokenUsage;
import java.util.List;

/**
 * Result of an agent loop execution.
 * Contains the final response, turn count, tool call history, and cost.
 */
public record AgentLoopResult(
        String response,
        int turns,
        List<ToolCallRecord> toolCalls,
        TokenUsage tokenUsage,
        boolean success,
        String error
) {
    public static AgentLoopResult success(String response, int turns, List<ToolCallRecord> toolCalls, TokenUsage usage) {
        return new AgentLoopResult(response, turns, toolCalls, usage, true, null);
    }

    public static AgentLoopResult failure(String error, int turns, List<ToolCallRecord> toolCalls, TokenUsage usage) {
        return new AgentLoopResult(null, turns, toolCalls, usage, false, error);
    }

    /**
     * Record of a single tool call within the agent loop.
     */
    public record ToolCallRecord(
            String toolName,
            String arguments,
            String result,
            boolean isError,
            long durationMs
    ) {}
}
