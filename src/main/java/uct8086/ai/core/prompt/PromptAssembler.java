package uct8086.ai.core.prompt;

import java.util.List;
import java.util.Set;
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
        return buildSystemPrompt(additionalContext, Set.of());
    }

    /**
     * Build the complete system prompt, hiding the given tools from the
     * "Available tools" list and from the orchestration guidance. Used by
     * {@code AgentEngine} to implement the orchestration mode topology
     * (e.g. COORDINATOR hides the orchestration primitives from sub-agents).
     *
     * @param additionalContext extra context to append (skills, memory, etc.)
     * @param excludedTools     tool names hidden from this run's prompt
     * @return the assembled system prompt
     */
    public String buildSystemPrompt(String additionalContext, Set<String> excludedTools) {
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

        // Tool descriptions (minus the ones excluded for this run)
        toolRegistry.listTools().stream()
                .filter(desc -> !excludedTools.contains(desc.name()))
                .forEach(desc -> {
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

        // Orchestration guidance (only when the 'agent' tool is registered AND visible here)
        if (toolRegistry.hasTool("agent") && !excludedTools.contains("agent")) {
            sb.append("""

                --- Agent Coordination ---
                You may act as an orchestrator. For a complex task that can be broken into
                independent parts, use the 'agent' tool to delegate each part to a sub-agent
                with a focused role. Each sub-agent runs in its own isolated session and its
                result comes back to you, so you can combine results into the final answer.

                Coordination primitives:
                - agent(name, role, task): delegate a subtask and wait for the sub-agent's answer.
                - agent(..., wait=false): start the sub-agent in the background and keep working;
                  call this several times to run sub-agents in parallel.
                - send_message(name, message): continue a finished sub-agent (it remembers its
                  session context), or wait for a background one and get its reply.
                - task_stop(name): abort a sub-agent that went off track or is no longer needed.

                Prefer delegating only when it genuinely helps; otherwise solve the task directly.
                """);
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
