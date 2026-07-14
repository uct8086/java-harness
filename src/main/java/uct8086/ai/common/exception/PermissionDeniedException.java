package uct8086.ai.common.exception;

/**
 * Thrown when a permission check denies an operation.
 */
public class PermissionDeniedException extends Uct8086Exception {

    private final String toolName;
    private final String reason;

    public PermissionDeniedException(String toolName, String reason) {
        super("Permission denied for tool '" + toolName + "': " + reason);
        this.toolName = toolName;
        this.reason = reason;
    }

    public String getToolName() {
        return toolName;
    }

    public String getReason() {
        return reason;
    }
}
