package uct8086.ai.core.hook;

import uct8086.ai.common.enums.HookPhase;
import uct8086.ai.common.model.HookContext;
import uct8086.ai.common.model.HookResult;
import uct8086.ai.common.model.ToolResult;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Manager for tool lifecycle hooks.
 * Maps to OpenHarness's Hook Manager.
 *
 * <p>Manages hook registration and firing at PreToolUse/PostToolUse phases.
 * Hooks are sorted by priority (lower = higher priority) and can:
 * <ul>
 *   <li>Continue execution (pass through)</li>
 *   <li>Block execution (with reason)</li>
 *   <li>Modify the tool result (PostToolUse only)</li>
 * </ul>
 */
@Component
public class HookManager {

    private static final Logger log = LoggerFactory.getLogger(HookManager.class);

    private final List<ToolHook> hooks = new CopyOnWriteArrayList<>();

    /**
     * Register a hook.
     */
    public void register(ToolHook hook) {
        Objects.requireNonNull(hook, "Hook cannot be null");
        hooks.add(hook);
        hooks.sort(Comparator.comparingInt(h -> h.getDefinition().priority()));
        log.debug("Registered hook: {} (phase={}, priority={})",
                hook.getDefinition().name(),
                hook.getDefinition().phase(),
                hook.getDefinition().priority());
    }

    /**
     * Unregister a hook by name.
     */
    public void unregister(String name) {
        hooks.removeIf(h -> h.getDefinition().name().equals(name));
    }

    /**
     * Fire PreToolUse hooks for a tool.
     * Returns a combined result - if any hook blocks, execution is blocked.
     */
    public HookResult firePreToolUse(String toolName, Map<String, Object> arguments, String sessionId) {
        List<ToolHook> matchingHooks = hooks.stream()
                .filter(h -> h.getDefinition().phase() == HookPhase.PRE_TOOL_USE)
                .filter(h -> h.getDefinition().matches(toolName))
                .toList();

        for (ToolHook hook : matchingHooks) {
            HookContext context = HookContext.preToolUse(toolName, arguments, sessionId);
            HookResult result;
            try {
                result = hook.onEvent(context);
            } catch (Exception e) {
                log.error("Hook '{}' threw exception", hook.getDefinition().name(), e);
                continue;
            }

            if (result.shouldBlock()) {
                log.info("Hook '{}' blocked tool execution: {}", hook.getDefinition().name(), result.blockReason());
                return result;
            }
        }

        return HookResult.continueExecution();
    }

    /**
     * Fire PostToolUse hooks for a tool.
     * Returns the potentially modified result.
     */
    public ToolResult firePostToolUse(String toolName, Map<String, Object> arguments, ToolResult result, String sessionId) {
        List<ToolHook> matchingHooks = hooks.stream()
                .filter(h -> h.getDefinition().phase() == HookPhase.POST_TOOL_USE)
                .filter(h -> h.getDefinition().matches(toolName))
                .toList();

        ToolResult currentResult = result;

        for (ToolHook hook : matchingHooks) {
            HookContext context = HookContext.postToolUse(toolName, arguments, currentResult, sessionId);
            HookResult hookResult;
            try {
                hookResult = hook.onEvent(context);
            } catch (Exception e) {
                log.error("Hook '{}' threw exception", hook.getDefinition().name(), e);
                continue;
            }

            if (hookResult.modifiedResult() != null) {
                log.debug("Hook '{}' modified tool result", hook.getDefinition().name());
                currentResult = hookResult.modifiedResult();
            }
        }

        return currentResult;
    }

    /**
     * Get all registered hooks.
     */
    public List<ToolHook> getHooks() {
        return Collections.unmodifiableList(hooks);
    }
}
