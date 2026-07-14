package uct8086.ai.core.prompt;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import uct8086.ai.core.tool.ToolRegistry;

/**
 * Assembles the system prompt for the agent.
 * Maps to OpenHarness's Prompt Assembly + CLAUDE.md injection.
 *
 * <p>System prompt assembly includes:
 * <ul>
 *   <li>Base agent identity and instructions</li>
 *   <li>Available tool descriptions</li>
 *   <li>Loaded skills (if any)</li>
 *   <li>Memory content (if any)</li>
 *   <li>Project-level UCT8086.md content (if present)</li>
 * </ul>
 */
@Component
public class PromptAssembler {

    private static final Logger log = LoggerFactory.getLogger(PromptAssembler.class);

    private final ToolRegistry toolRegistry;

    public PromptAssembler(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    /**
     * Build the complete system prompt.
     *
     * @param additionalContext extra context to append (skills, memory, etc.)
     * @return the assembled system prompt
     */
    public String buildSystemPrompt(String additionalContext) {
        StringBuilder sb = new StringBuilder();

        // Base identity
        sb.append("""
            You are an AI agent powered by the UCT8086 (Open Agent Harness) system.
            You have access to tools that let you interact with the file system, run commands,
            search the web, and coordinate with other agents.

            When you need to perform an action, use the appropriate tool.
            Always think step by step and explain your reasoning.

            Available tools:
            """);

        // Tool descriptions
        toolRegistry.listTools().forEach(desc -> {
            sb.append("- ").append(desc.name())
              .append(" (").append(desc.category()).append(")")
              .append(": ").append(desc.description()).append("\n");
        });

        // Additional context (skills, memory, project config)
        if (additionalContext != null && !additionalContext.isBlank()) {
            sb.append("\n--- Additional Context ---\n");
            sb.append(additionalContext);
            sb.append("\n");
        }

        // Safety guidelines
        sb.append("""

            --- Safety Guidelines ---
            - Always check file paths before writing
            - Be cautious with shell commands
            - Ask for confirmation before destructive operations
            - Respect permission modes (DEFAULT, AUTO, PLAN_MODE)
            """);

        String prompt = sb.toString();
        log.debug("System prompt assembled ({} chars)", prompt.length());
        return prompt;
    }

    /**
     * Build system prompt with tool list only.
     */
    public String buildSystemPrompt() {
        return buildSystemPrompt(null);
    }

    /**
     * Build a prompt with loaded skill content.
     */
    public String buildSystemPromptWithSkills(List<String> skillContents) {
        StringBuilder context = new StringBuilder();
        if (skillContents != null && !skillContents.isEmpty()) {
            context.append("\n--- Loaded Skills ---\n");
            for (String skill : skillContents) {
                context.append(skill).append("\n\n");
            }
        }
        return buildSystemPrompt(context.toString());
    }
}
