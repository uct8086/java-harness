package uct8086.ai.common.model;

import java.util.List;

/**
 * A message in a conversation.
 * Maps to the chat message format used by LLM APIs.
 */
public record AgentMessage(
        Role role,
        String content,
        List<ToolCall> toolCalls,
        String toolCallId
) {
    public enum Role {
        SYSTEM, USER, ASSISTANT, TOOL
    }

    public AgentMessage(Role role, String content) {
        this(role, content, List.of(), null);
    }

    public static AgentMessage system(String content) {
        return new AgentMessage(Role.SYSTEM, content, List.of(), null);
    }

    public static AgentMessage user(String content) {
        return new AgentMessage(Role.USER, content, List.of(), null);
    }

    public static AgentMessage assistant(String content) {
        return new AgentMessage(Role.ASSISTANT, content, List.of(), null);
    }

    public static AgentMessage tool(String content, String toolCallId) {
        return new AgentMessage(Role.TOOL, content, List.of(), toolCallId);
    }

    /**
     * A tool call requested by the model.
     */
    public record ToolCall(
            String id,
            String name,
            String arguments
    ) {}
}
