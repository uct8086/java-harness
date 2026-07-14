package uct8086.ai.core.tools;

import uct8086.ai.common.enums.ToolCategory;
import uct8086.ai.common.model.ToolExecutionContext;
import uct8086.ai.common.model.ToolResult;
import uct8086.ai.core.tool.AbstractTool;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Tool for writing content to a file.
 * Maps to OpenHarness's Write tool.
 */
@Component
public class FileWriteTool extends AbstractTool {

    public FileWriteTool() {
        super("write_file",
              "Write content to a file. Creates the file if it does not exist, or overwrites if it does.",
              ToolCategory.FILE_IO, false);
    }

    @Override
    protected ToolResult doExecute(Map<String, Object> arguments, ToolExecutionContext context) throws Exception {
        String filePath = requireString(arguments, "path");
        String content = requireString(arguments, "content");
        boolean append = arguments.containsKey("append") && Boolean.parseBoolean(arguments.get("append").toString());

        Path path = resolvePath(filePath, context);

        // Create parent directories if needed
        Path parent = path.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }

        try {
            if (append) {
                Files.writeString(path, content, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } else {
                Files.writeString(path, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            }
            return ToolResult.success("File written: " + path + " (" + content.length() + " bytes)");
        } catch (IOException e) {
            return ToolResult.error("Failed to write file: " + e.getMessage());
        }
    }

    private Path resolvePath(String filePath, ToolExecutionContext context) {
        Path path = Path.of(filePath);
        if (!path.isAbsolute() && context.workingDirectory() != null) {
            path = context.workingDirectory().resolve(filePath).normalize();
        }
        return path;
    }
}
