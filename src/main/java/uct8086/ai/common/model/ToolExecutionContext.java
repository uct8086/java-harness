package uct8086.ai.common.model;

import uct8086.ai.common.enums.PermissionMode;
import java.nio.file.Path;
import java.util.Map;

/**
 * Context passed to tools during execution.
 * Contains session info, working directory, permissions, and shared state.
 */
public record ToolExecutionContext(
        String sessionId,
        Path workingDirectory,
        PermissionMode permissionMode,
        Map<String, Object> state
) {
    public ToolExecutionContext(String sessionId, Path workingDirectory, PermissionMode permissionMode) {
        this(sessionId, workingDirectory, permissionMode, Map.of());
    }

    /**
     * Get a state value by key.
     */
    @SuppressWarnings("unchecked")
    public <T> T getState(String key) {
        return (T) state.get(key);
    }

    /**
     * Create a new context with an additional state entry.
     */
    public ToolExecutionContext withState(String key, Object value) {
        var newState = new java.util.HashMap<>(state);
        newState.put(key, value);
        return new ToolExecutionContext(sessionId, workingDirectory, permissionMode, Map.copyOf(newState));
    }
}
