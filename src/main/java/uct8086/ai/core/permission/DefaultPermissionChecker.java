package uct8086.ai.core.permission;

import uct8086.ai.common.enums.PermissionMode;
import uct8086.ai.common.model.PathRule;
import uct8086.ai.common.model.PermissionResult;
import uct8086.ai.common.model.ToolExecutionContext;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Default implementation of the permission checker.
 * Implements multi-level safety with fine-grained control.
 *
 * <p>Modes:
 * <ul>
 *   <li>DEFAULT - Ask before write/execute</li>
 *   <li>AUTO - Allow everything</li>
 *   <li>PLAN_MODE - Block all writes</li>
 *   <li>READ_ONLY - Allow read operations only</li>
 * </ul>
 */
@Component
public class DefaultPermissionChecker implements PermissionChecker {

    private static final Logger log = LoggerFactory.getLogger(DefaultPermissionChecker.class);

    private volatile PermissionMode mode = PermissionMode.DEFAULT;
    private final List<PathRule> pathRules = new CopyOnWriteArrayList<>();
    private final List<String> deniedCommands = new CopyOnWriteArrayList<>();

    /**
     * Commands that are always denied for safety.
     */
    private static final Set<String> DANGEROUS_COMMANDS = Set.of(
            "rm -rf /", "rm -rf ~", "rm -rf /*", "mkfs", ":(){ :|:& };:",
            "dd if=/dev/zero of=", "DROP TABLE", "DROP DATABASE"
    );

    public DefaultPermissionChecker() {
        // Default path rules - deny sensitive system paths
        pathRules.add(PathRule.deny("/etc/*"));
        pathRules.add(PathRule.deny("/var/log/*"));
        pathRules.add(PathRule.deny("/root/*"));
        pathRules.add(PathRule.deny("~/.ssh/*"));
        pathRules.add(PathRule.deny("~/.aws/*"));
    }

    @Override
    public PermissionResult check(String toolName, Map<String, Object> arguments, ToolExecutionContext context) {
        // AUTO mode allows everything
        if (mode == PermissionMode.AUTO) {
            return PermissionResult.allowed();
        }

        // READ_ONLY mode allows only read-only operations
        if (mode == PermissionMode.READ_ONLY) {
            Boolean isReadOnly = arguments.containsKey("__read_only") ? (Boolean) arguments.get("__read_only") : false;
            if (!isReadOnly) {
                return PermissionResult.denied("Read-only mode: write/execute operations are blocked");
            }
            return PermissionResult.allowed();
        }

        // PLAN_MODE blocks all writes
        if (mode == PermissionMode.PLAN_MODE) {
            return PermissionResult.denied("Plan mode: all write/execute operations are blocked");
        }

        // DEFAULT mode - check specific rules
        // Check for dangerous commands in arguments
        for (Map.Entry<String, Object> entry : arguments.entrySet()) {
            String value = entry.getValue() != null ? entry.getValue().toString() : "";
            String lowerValue = value.toLowerCase();

            for (String dangerous : DANGEROUS_COMMANDS) {
                if (lowerValue.contains(dangerous.toLowerCase())) {
                    return PermissionResult.denied("Dangerous command detected: " + dangerous);
                }
            }

            for (String denied : deniedCommands) {
                if (lowerValue.contains(denied.toLowerCase())) {
                    return PermissionResult.denied("Denied command pattern matched: " + denied);
                }
            }
        }

        // Check path rules
        String pathArg = getStringArg(arguments, "path");
        String fileArg = getStringArg(arguments, "file_path");
        String filePath = pathArg != null ? pathArg : fileArg;

        if (filePath != null) {
            Path resolvedPath = context.workingDirectory() != null
                    ? context.workingDirectory().resolve(filePath).normalize()
                    : Path.of(filePath).normalize();

            for (PathRule rule : pathRules) {
                if (matchesPath(resolvedPath.toString(), rule.pattern())) {
                    if (!rule.allow()) {
                        return PermissionResult.denied("Path denied by rule: " + rule.pattern());
                    }
                    return PermissionResult.allowed();
                }
            }
        }

        // In DEFAULT mode, write operations need user approval
        return PermissionResult.askUser("Tool '" + toolName + "' requires approval");
    }

    private boolean matchesPath(String path, String pattern) {
        String normalizedPath = path.replace("\\", "/");
        String normalizedPattern = pattern.replace("\\", "/");
        if (normalizedPattern.endsWith("/*")) {
            String prefix = normalizedPattern.substring(0, normalizedPattern.length() - 2);
            return normalizedPath.startsWith(prefix);
        }
        return normalizedPath.equals(normalizedPattern);
    }

    private String getStringArg(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        return value != null ? value.toString() : null;
    }

    @Override
    public PermissionMode getMode() {
        return mode;
    }

    @Override
    public void setMode(PermissionMode mode) {
        log.info("Permission mode changed: {} -> {}", this.mode, mode);
        this.mode = mode;
    }

    public void addPathRule(PathRule rule) {
        pathRules.add(rule);
    }

    public void addDeniedCommand(String command) {
        deniedCommands.add(command);
    }

    public List<PathRule> getPathRules() {
        return new ArrayList<>(pathRules);
    }

    public List<String> getDeniedCommands() {
        return new ArrayList<>(deniedCommands);
    }
}
