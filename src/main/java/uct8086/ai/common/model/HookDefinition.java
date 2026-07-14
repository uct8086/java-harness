package uct8086.ai.common.model;

import uct8086.ai.common.enums.HookPhase;
import java.util.List;

/**
 * Definition of a lifecycle hook.
 * Hooks fire at PreToolUse/PostToolUse phases.
 */
public record HookDefinition(
        String name,
        HookPhase phase,
        List<String> toolPatterns,
        int priority
) {
    public HookDefinition(String name, HookPhase phase) {
        this(name, phase, List.of("*"), 0);
    }

    public HookDefinition(String name, HookPhase phase, int priority) {
        this(name, phase, List.of("*"), priority);
    }

    public boolean matches(String toolName) {
        return toolPatterns.stream().anyMatch(p -> {
            if ("*".equals(p)) return true;
            return toolName.matches(p.replace("*", ".*"));
        });
    }
}
