package uct8086.ai.core.tool;

import uct8086.ai.common.exception.PermissionDeniedException;
import uct8086.ai.common.exception.ToolExecutionException;
import uct8086.ai.common.model.HookResult;
import uct8086.ai.common.model.PermissionResult;
import uct8086.ai.common.model.ToolExecutionContext;
import uct8086.ai.common.model.ToolResult;
import uct8086.ai.core.hook.HookManager;
import uct8086.ai.core.permission.PermissionChecker;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Default implementation of the tool execution service.
 * Implements the full execution pipeline: permission → hooks → execute → hooks.
 */
@Service
public class DefaultToolExecutionService implements ToolExecutionService {

    private static final Logger log = LoggerFactory.getLogger(DefaultToolExecutionService.class);

    private final ToolRegistry toolRegistry;
    private final PermissionChecker permissionChecker;
    private final HookManager hookManager;

    public DefaultToolExecutionService(ToolRegistry toolRegistry,
                                       PermissionChecker permissionChecker,
                                       HookManager hookManager) {
        this.toolRegistry = toolRegistry;
        this.permissionChecker = permissionChecker;
        this.hookManager = hookManager;
    }

    @Override
    public ToolResult execute(String toolName, Map<String, Object> arguments, ToolExecutionContext context) {
        log.debug("Executing tool: {} with args: {}", toolName, arguments);

        // 1. Find the tool
        HarnessTool tool = toolRegistry.getTool(toolName)
                .orElseThrow(() -> new ToolExecutionException(toolName, "Tool not found: " + toolName));

        // 2. Permission check
        PermissionResult permResult = permissionChecker.check(toolName, arguments, context);
        if (permResult.isDenied()) {
            throw new PermissionDeniedException(toolName, permResult.reason());
        }
        if (permResult.needsApproval()) {
            // In non-interactive mode, we auto-approve if the tool is read-only
            // In interactive mode, this would prompt the user
            if (tool.isReadOnly()) {
                log.debug("Auto-approving read-only tool: {}", toolName);
            } else {
                // For now, auto-approve in non-interactive mode
                // TODO: Add interactive approval callback
                log.debug("Auto-approving tool (non-interactive): {}", toolName);
            }
        }

        // 3. PreToolUse hooks
        HookResult preHookResult = hookManager.firePreToolUse(toolName, arguments, context.sessionId());
        if (preHookResult.shouldBlock()) {
            return ToolResult.error("Blocked by hook: " + preHookResult.blockReason());
        }

        // 4. Execute the tool
        ToolResult result;
        try {
            result = tool.execute(arguments, context);
        } catch (ToolExecutionException e) {
            result = ToolResult.error(e.getMessage());
        } catch (Exception e) {
            result = ToolResult.error("Unexpected error: " + e.getMessage());
        }

        // 5. PostToolUse hooks
        result = hookManager.firePostToolUse(toolName, arguments, result, context.sessionId());

        log.debug("Tool {} completed: isError={}", toolName, result.isError());
        return result;
    }
}
