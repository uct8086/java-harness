package uct8086.ai.common.model;

import uct8086.ai.common.enums.HookPhase;
import java.util.Map;

/**
 * Context provided to hooks during the tool lifecycle.
 */
public record HookContext(
        HookPhase phase,
        String toolName,
        Map<String, Object> arguments,
        ToolResult result,
        String sessionId
) {
    public static HookContext preToolUse(String toolName, Map<String, Object> arguments, String sessionId) {
        return new HookContext(HookPhase.PRE_TOOL_USE, toolName, arguments, null, sessionId);
    }

    public static HookContext postToolUse(String toolName, Map<String, Object> arguments, ToolResult result, String sessionId) {
        return new HookContext(HookPhase.POST_TOOL_USE, toolName, arguments, result, sessionId);
    }
}
