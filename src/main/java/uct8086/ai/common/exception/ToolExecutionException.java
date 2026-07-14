package uct8086.ai.common.exception;

/**
 * Thrown when a tool execution fails.
 */
public class ToolExecutionException extends Uct8086Exception {

    private final String toolName;

    public ToolExecutionException(String toolName, String message) {
        super("Tool '" + toolName + "' failed: " + message);
        this.toolName = toolName;
    }

    public ToolExecutionException(String toolName, String message, Throwable cause) {
        super("Tool '" + toolName + "' failed: " + message, cause);
        this.toolName = toolName;
    }

    public String getToolName() {
        return toolName;
    }
}
