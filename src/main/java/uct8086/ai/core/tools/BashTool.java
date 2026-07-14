package uct8086.ai.core.tools;

import uct8086.ai.common.enums.ToolCategory;
import uct8086.ai.common.model.ToolExecutionContext;
import uct8086.ai.common.model.ToolResult;
import uct8086.ai.core.tool.AbstractTool;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Tool for executing shell commands.
 * Maps to OpenHarness's Bash tool.
 */
@Component
public class BashTool extends AbstractTool {

    public BashTool() {
        super("bash",
              "Execute a shell command and return the output. Use for running scripts, building, testing, etc.",
              ToolCategory.SHELL, false);
    }

    @Override
    protected ToolResult doExecute(Map<String, Object> arguments, ToolExecutionContext context) throws Exception {
        String command = requireString(arguments, "command");
        int timeoutSeconds = optionalInt(arguments, "timeout", 120);

        // Determine shell based on OS
        String os = System.getProperty("os.name").toLowerCase();
        ProcessBuilder pb;
        if (os.contains("win")) {
            pb = new ProcessBuilder("cmd.exe", "/c", command);
        } else {
            pb = new ProcessBuilder("bash", "-c", command);
        }

        // Set working directory
        if (context.workingDirectory() != null) {
            pb.directory(context.workingDirectory().toFile());
        }

        pb.redirectErrorStream(true);

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            return ToolResult.error("Failed to start process: " + e.getMessage());
        }

        // Capture output
        List<String> outputLines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                outputLines.add(line);
            }
        }

        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            return ToolResult.error("Command timed out after " + timeoutSeconds + " seconds");
        }

        int exitCode = process.exitValue();
        String output = String.join("\n", outputLines);

        if (exitCode != 0) {
            return new ToolResult(output, true, Map.of("exitCode", exitCode));
        }

        return ToolResult.success(output, Map.of("exitCode", exitCode));
    }
}
