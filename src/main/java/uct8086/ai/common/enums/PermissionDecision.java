package uct8086.ai.common.enums;

/**
 * Decision result from the permission checker.
 */
public enum PermissionDecision {
    /** Operation is allowed */
    ALLOWED,
    /** Operation is denied */
    DENIED,
    /** User must be asked for approval */
    ASK_USER
}
