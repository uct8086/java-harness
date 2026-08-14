package uct8086.ai.coordinator;

import uct8086.ai.common.enums.AgentRole;
import uct8086.ai.tasks.TaskManager;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Coordinator for multi-agent operations.
 * Maps to OpenHarness's Multi-Agent coordination.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Spawn subagents for task delegation</li>
 *   <li>Manage agent lifecycle (create → run → complete)</li>
 *   <li>Coordinate communication between agents</li>
 *   <li>Track agent status</li>
 * </ul>
 */
@Component
public class AgentCoordinator {

    private static final Logger log = LoggerFactory.getLogger(AgentCoordinator.class);

    private final TaskManager taskManager;
    private final TeamRegistry teamRegistry;

    public AgentCoordinator(TaskManager taskManager, TeamRegistry teamRegistry) {
        this.taskManager = taskManager;
        this.teamRegistry = teamRegistry;
    }

    /**
     * Spawn a subagent for a specific task.
     *
     * @param name         the agent name
     * @param systemPrompt the system prompt for the subagent
     * @param task         the task to execute
     * @return the created subagent
     */
    public Subagent spawnSubagent(Long userId, String name, String systemPrompt, String task) {
        Subagent subagent = new Subagent(name, AgentRole.SUBAGENT, systemPrompt);

        // Create a background task for the subagent
        taskManager.createTask(userId, name, "Subagent task: " + task, () -> {
            // In a real implementation, this would call the AgentEngine
            // with the subagent's system prompt and task
            log.info("Subagent '{}' executing task: {}", name, task);
            return "Subagent task completed: " + task;
        });

        log.info("Spawned subagent: {} ({})", name, subagent.id());
        return subagent;
    }

    /**
     * Create a team of agents.
     */
    public TeamRegistry.AgentTeam createTeam(String name) {
        return teamRegistry.createTeam(name);
    }

    /**
     * Add a subagent to a team.
     */
    public void addToTeam(String teamId, Subagent subagent) {
        teamRegistry.getTeam(teamId).ifPresent(team -> {
            team.addMember(subagent);
            log.info("Added agent {} to team {}", subagent.name(), team.getName());
        });
    }

    /**
     * Get a subagent's status.
     */
    public Optional<String> getAgentStatus(String agentId) {
        // Check in teams
        for (var team : teamRegistry.listTeams()) {
            var member = team.getMember(agentId);
            if (member.isPresent()) {
                return Optional.of(member.get().status());
            }
        }
        return Optional.empty();
    }
}
