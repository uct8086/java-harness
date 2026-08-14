package uct8086.ai.core.permission;

import uct8086.ai.common.enums.PermissionMode;
import uct8086.ai.common.model.PathRule;
import uct8086.ai.common.model.PermissionResult;
import uct8086.ai.common.model.ToolExecutionContext;
import uct8086.ai.core.config.HarnessProperties;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
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

    // The "default" permission mode used when a request context does not carry one.
    // The actual check reads the mode from ToolExecutionContext (per-request), so
    // concurrent requests no longer overwrite each other's mode.
    private final AtomicReference<PermissionMode> defaultMode;
    private final List<PathRule> pathRules = new CopyOnWriteArrayList<>();
    private final List<String> deniedCommands = new CopyOnWriteArrayList<>();

    /**
     * Commands that are always denied for safety. Matching is done on a normalized
     * (whitespace-collapsed, lower-cased) command string so that extra spaces or
     * case variations do not bypass the check.
     */
    private static final Set<String> DANGEROUS_COMMANDS = Set.of(
            "rm -rf /", "rm -rf ~", "rm -rf /*", "rm -fr /", "rm -r /",
            "mkfs", "mkfs.ext4", "mkfs.xfs", "mkfs.btrfs",
            ":(){ :|:& };:", // fork bomb
            "dd if=/dev/zero", "dd if=/dev/urandom",
            "drop table", "drop database", "truncate table",
            "shutdown", "reboot", "halt", "poweroff",
            "chmod 777 /", "chown -r root /", "format c:", "del /f /s /q c:\\"
    );

    /**
     * Dangerous command fragments (substrings) that indicate destructive intent.
     * Each entry is a normalized lowercase substring checked via {@code contains}.
     */
    private static final List<String> DANGEROUS_FRAGMENTS = List.of(
            "rm -rf", "rm -fr", "rm -r", "rm -f", "del /f", "del /s", "del /q",
            "format ", "mkfs", "fdisk", "diskpart",
            ":(){ :|:& };:", "dd if=/dev/zero", "dd if=/dev/random", "dd if=/dev/urandom",
            "drop table", "drop database", "truncate table", "delete from ",
            "shutdown", "reboot", "> /dev/sda", "of=/dev/sd", "chmod 777", "chmod -r 777",
            "chown -r root", "git push --force", "git push -f", "git reset --hard"
    );

    /**
     * Regular expressions for structural command-injection patterns that simple
     * substring matching cannot reliably catch.
     */
    private static final List<Pattern> DANGEROUS_PATTERNS = List.of(
            // path traversal escaping a directory
            Pattern.compile("(\\.\\./){2,}"),
            Pattern.compile("(\\.\\.\\\\){2,}"),
            // command substitution: $(...) or backticks
            Pattern.compile("\\$\\([^)]*\\)"),
            Pattern.compile("`[^`]+`"),
            // command chaining with destructive commands
            Pattern.compile(";\\s*(rm|mkfs|dd|shutdown|reboot|format)\\b"),
            Pattern.compile("&&\\s*(rm|mkfs|dd|shutdown|reboot|format)\\b"),
            Pattern.compile("\\|\\s*(rm|mkfs|dd|shutdown|reboot|format)\\b")
    );

    public DefaultPermissionChecker(HarnessProperties properties) {
        // Seed the default mode from configuration (uct8086.ai.permission-mode),
        // falling back to DEFAULT when not configured.
        PermissionMode configured = properties.getPermissionMode();
        this.defaultMode = new AtomicReference<>(configured != null ? configured : PermissionMode.DEFAULT);

        // Default path rules - deny sensitive system paths
        pathRules.add(PathRule.deny("/etc/*"));
        pathRules.add(PathRule.deny("/var/log/*"));
        pathRules.add(PathRule.deny("/root/*"));
        pathRules.add(PathRule.deny("~/.ssh/*"));
        pathRules.add(PathRule.deny("~/.aws/*"));
    }

    @Override
    public PermissionResult check(String toolName, Map<String, Object> arguments, ToolExecutionContext context) {
        // Resolve the effective mode per-request: prefer the context's mode, fall
        // back to the default mode. This removes the global mutable state that caused
        // concurrent requests to overwrite each other's permission mode.
        PermissionMode mode = context != null && context.permissionMode() != null
                ? context.permissionMode()
                : defaultMode.get();

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

        // DEFAULT mode - check specific rules.
        // Dangerous-command detection only applies to shell-executing tools
        // (e.g. "bash"). File I/O tools like write_file legitimately contain
        // backticks / $() / semicolons in their content, so treating every
        // argument as a shell command causes false positives.
        if (isShellTool(toolName)) {
            for (Map.Entry<String, Object> entry : arguments.entrySet()) {
                String value = entry.getValue() != null ? entry.getValue().toString() : "";
                if (value.isBlank()) {
                    continue;
                }

                String denied = detectDangerousCommand(value);
                if (denied != null) {
                    return PermissionResult.denied("Dangerous command detected: " + denied);
                }

                // User-supplied denied command patterns
                String normalized = normalize(value);
                for (String cmd : deniedCommands) {
                    if (normalized.contains(normalize(cmd))) {
                        return PermissionResult.denied("Denied command pattern matched: " + cmd);
                    }
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

    /**
     * Tools that execute shell commands. Only these tools should have their
     * arguments scanned for dangerous-command patterns.
     */
    private static final Set<String> SHELL_TOOLS = Set.of("bash");

    /**
     * Whether the given tool name corresponds to a shell-executing tool.
     */
    private static boolean isShellTool(String toolName) {
        return toolName != null && SHELL_TOOLS.contains(toolName);
    }

    /**
     * Normalize a command/value string: trim, collapse consecutive whitespace to a
     * single space, and lower-case. This defeats trivial bypasses via extra spaces
     * or case variations (e.g. {@code rm  -rf  /} vs {@code rm -rf /}).
     */
    private static String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase();
    }

    /**
     * Detect whether a command value is dangerous. Returns a human-readable reason
     * if dangerous, or {@code null} if safe.
     *
     * <p>Checks, in order:
     * <ol>
     *   <li>Exact dangerous commands (on the normalized string)</li>
     *   <li>Dangerous fragments (substring match on normalized string)</li>
     *   <li>Structural command-injection patterns (regex)</li>
     * </ol>
     */
    private static String detectDangerousCommand(String value) {
        String normalized = normalize(value);

        // 1. Exact dangerous commands
        if (DANGEROUS_COMMANDS.contains(normalized)) {
            return normalized;
        }

        // 2. Dangerous fragments (substring)
        for (String fragment : DANGEROUS_FRAGMENTS) {
            if (normalized.contains(fragment)) {
                return fragment;
            }
        }

        // 3. Structural patterns (path traversal, command substitution/chaining)
        for (Pattern pattern : DANGEROUS_PATTERNS) {
            if (pattern.matcher(value).find()) {
                return "pattern: " + pattern.pattern();
            }
        }

        return null;
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
        return defaultMode.get();
    }

    @Override
    public void setMode(PermissionMode mode) {
        PermissionMode previous = defaultMode.getAndSet(mode);
        log.info("Default permission mode changed: {} -> {}", previous, mode);
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
