package uct8086.ai.common.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.Map;

/**
 * Result of a tool execution.
 * Maps to OpenHarness's ToolResult pattern.
 */
@JsonPropertyOrder({"output", "is_error", "metadata"})
public record ToolResult(
        @JsonProperty("output") String output,
        @JsonProperty("is_error") boolean isError,
        @JsonProperty("metadata") Map<String, Object> metadata
) {
    public ToolResult(String output) {
        this(output, false, Map.of());
    }

    public ToolResult(String output, boolean isError) {
        this(output, isError, Map.of());
    }

    public static ToolResult success(String output) {
        return new ToolResult(output, false, Map.of());
    }

    public static ToolResult success(String output, Map<String, Object> metadata) {
        return new ToolResult(output, false, metadata);
    }

    public static ToolResult error(String message) {
        return new ToolResult(message, true, Map.of());
    }

    public static ToolResult error(String message, Map<String, Object> metadata) {
        return new ToolResult(message, true, metadata);
    }
}
