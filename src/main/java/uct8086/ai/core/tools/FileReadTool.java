package uct8086.ai.core.tools;

import uct8086.ai.common.enums.ToolCategory;
import uct8086.ai.common.model.ToolExecutionContext;
import uct8086.ai.common.model.ToolResult;
import uct8086.ai.core.tool.AbstractTool;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Tool for reading file contents.
 * Maps to OpenHarness's Read tool.
 */
@Component
public class FileReadTool extends AbstractTool {

    public FileReadTool() {
        super("read_file",
              "Read the contents of a file. Returns the file content as text.",
              ToolCategory.FILE_IO, true);
    }

    @Override
    protected ToolResult doExecute(Map<String, Object> arguments, ToolExecutionContext context) throws Exception {
        String filePath = requireString(arguments, "path");
        int maxLines = optionalInt(arguments, "max_lines", 2000);

        Path path = resolvePath(filePath, context);

        if (!Files.exists(path)) {
            return ToolResult.error("File not found: " + path);
        }
        if (!Files.isRegularFile(path)) {
            return ToolResult.error("Not a regular file: " + path);
        }
        if (!Files.isReadable(path)) {
            return ToolResult.error("File is not readable: " + path);
        }

        try {
            String content = Files.readString(path);
            // Truncate if too long
            if (content.length() > maxLines * 100) {
                content = content.substring(0, maxLines * 100) + "\n... (truncated)";
            }
            return ToolResult.success(content);
        } catch (IOException e) {
            return ToolResult.error("Failed to read file: " + e.getMessage());
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
