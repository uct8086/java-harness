package uct8086.ai.common.model;

import uct8086.ai.common.enums.PermissionMode;
import java.nio.file.Path;

/**
 * Context passed to tools during execution.
 * Contains session info, working directory, and permissions.
 */
public record ToolExecutionContext(
        String sessionId,
        Path workingDirectory,
        PermissionMode permissionMode
) {
}
