package uct8086.ai.common.model;

import uct8086.ai.common.enums.PermissionDecision;

/**
 * Result of a permission check for a tool operation.
 */
public record PermissionResult(
        PermissionDecision decision,
        String reason
) {
    public static PermissionResult allowed() {
        return new PermissionResult(PermissionDecision.ALLOWED, "Operation permitted");
    }

    public static PermissionResult denied(String reason) {
        return new PermissionResult(PermissionDecision.DENIED, reason);
    }

    public static PermissionResult askUser(String reason) {
        return new PermissionResult(PermissionDecision.ASK_USER, reason);
    }

    public boolean isAllowed() {
        return decision == PermissionDecision.ALLOWED;
    }

    public boolean isDenied() {
        return decision == PermissionDecision.DENIED;
    }

    public boolean needsApproval() {
        return decision == PermissionDecision.ASK_USER;
    }
}
