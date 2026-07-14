package uct8086.ai.core.tools;

import uct8086.ai.common.enums.ToolCategory;
import uct8086.ai.common.model.ToolExecutionContext;
import uct8086.ai.common.model.ToolResult;
import uct8086.ai.core.tool.AbstractTool;
import java.io.IOException;
import java.io.StringWriter;
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
 * Tool for searching file contents using regex.
 * Maps to OpenHarness's Grep tool.
 */
@Component
public class GrepTool extends AbstractTool {

    public GrepTool() {
        super("grep",
              "Search file contents using a regular expression. Returns matching lines with file paths.",
              ToolCategory.SEARCH, true);
    }

    @Override
    protected ToolResult doExecute(Map<String, Object> arguments, ToolExecutionContext context) throws Exception {
        String pattern = requireString(arguments, "pattern");
        String filePattern = optionalString(arguments, "file_pattern", "*");
        int maxResults = optionalInt(arguments, "max_results", 100);

        Path searchDir = context.workingDirectory() != null
                ? context.workingDirectory()
                : Path.of(".");

        Pattern regex;
        try {
            regex = Pattern.compile(pattern);
        } catch (Exception e) {
            return ToolResult.error("Invalid regex pattern: " + e.getMessage());
        }

        List<String> matches = new ArrayList<>();
        String fileRegex = filePattern.replace(".", "\\.").replace("*", ".*");
        Pattern filePatternRegex = Pattern.compile(fileRegex);

        try {
            Files.walkFileTree(searchDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (matches.size() >= maxResults) {
                        return FileVisitResult.TERMINATE;
                    }

                    String fileName = file.getFileName().toString();
                    if (!filePatternRegex.matcher(fileName).matches()) {
                        return FileVisitResult.CONTINUE;
                    }

                    // Skip binary/large files
                    if (attrs.size() > 1_000_000) {
                        return FileVisitResult.CONTINUE;
                    }

                    try {
                        List<String> lines = Files.readAllLines(file);
                        for (int i = 0; i < lines.size() && matches.size() < maxResults; i++) {
                            if (regex.matcher(lines.get(i)).find()) {
                                String relativePath = searchDir.relativize(file).toString();
                                matches.add(relativePath + ":" + (i + 1) + ": " + lines.get(i).trim());
                            }
                        }
                    } catch (IOException e) {
                        // Skip unreadable files
                    }

                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String dirName = dir.getFileName() != null ? dir.getFileName().toString() : "";
                    // Skip common ignored directories
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
            return ToolResult.success("No matches found for pattern: " + pattern);
        }

        StringWriter sw = new StringWriter();
        sw.write("Found " + matches.size() + " matches:\n\n");
        for (String match : matches) {
            sw.write(match);
            sw.write("\n");
        }
        return ToolResult.success(sw.toString());
    }
}
