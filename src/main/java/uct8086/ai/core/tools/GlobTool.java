package uct8086.ai.core.tools;

import uct8086.ai.common.enums.ToolCategory;
import uct8086.ai.common.model.ToolExecutionContext;
import uct8086.ai.common.model.ToolResult;
import uct8086.ai.core.tool.AbstractTool;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Tool for finding files by glob pattern.
 * Maps to OpenHarness's Glob tool.
 */
@Component
public class GlobTool extends AbstractTool {

    public GlobTool() {
        super("glob",
              "Find files matching a glob pattern. Returns matching file paths.",
              ToolCategory.FILE_IO, true);
    }

    @Override
    protected ToolResult doExecute(Map<String, Object> arguments, ToolExecutionContext context) throws Exception {
        String pattern = requireString(arguments, "pattern");
        int maxResults = optionalInt(arguments, "max_results", 200);

        Path searchDir = context.workingDirectory() != null
                ? context.workingDirectory()
                : Path.of(".");

        // Convert glob pattern to regex
        String regex = pattern
                .replace(".", "\\.")
                .replace("**", "<<<GLOBSTAR>>>")
                .replace("*", "[^/\\\\]*")
                .replace("?", "[^/\\\\]?")
                .replace("<<<GLOBSTAR>>>", ".*");
        Pattern compiledPattern = Pattern.compile(regex);

        List<String> matches = new ArrayList<>();

        try {
            Files.walkFileTree(searchDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (matches.size() >= maxResults) {
                        return FileVisitResult.TERMINATE;
                    }

                    String relativePath = searchDir.relativize(file).toString().replace("\\", "/");
                    if (compiledPattern.matcher(relativePath).matches() ||
                        compiledPattern.matcher(file.getFileName().toString()).matches()) {
                        matches.add(relativePath);
                    }

                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String dirName = dir.getFileName() != null ? dir.getFileName().toString() : "";
                    if (dirName.equals(".git") || dirName.equals("node_modules") ||
                        dirName.equals("target") || dirName.equals("build") ||
                        dirName.equals(".idea") || dirName.equals("__pycache__")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            return ToolResult.error("Failed to search: " + e.getMessage());
        }

        if (matches.isEmpty()) {
            return ToolResult.success("No files found matching: " + pattern);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Found ").append(matches.size()).append(" files:\n\n");
        matches.forEach(m -> sb.append(m).append("\n"));
        return ToolResult.success(sb.toString());
    }
}
