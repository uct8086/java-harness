package uct8086.ai.common.model;

/**
 * Result returned by a hook after processing.
 * Can block tool execution or modify the tool result.
 */
public record HookResult(
        boolean shouldContinue,
        boolean shouldBlock,
        String blockReason,
        ToolResult modifiedResult
) {
    public static HookResult continueExecution() {
        return new HookResult(true, false, null, null);
    }

    public static HookResult block(String reason) {
        return new HookResult(false, true, reason, null);
    }

    public static HookResult modifyResult(ToolResult newResult) {
        return new HookResult(true, false, null, newResult);
    }
}
