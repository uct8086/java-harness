package uct8086.ai.coordinator;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Registry for agent teams.
 * Maps to OpenHarness's Team Registry & Task Management.
 *
 * <p>An agent team is a collection of subagents that can work on related tasks.
 */
@Component
public class TeamRegistry {

    private static final Logger log = LoggerFactory.getLogger(TeamRegistry.class);

    private final Map<String, AgentTeam> teams = new ConcurrentHashMap<>();

    /**
     * Create a new team.
     */
    public AgentTeam createTeam(String name) {
        AgentTeam team = new AgentTeam(name);
        teams.put(team.getId(), team);
        log.info("Created team: {} ({})", name, team.getId());
        return team;
    }

    /**
     * Get a team by ID.
     */
    public Optional<AgentTeam> getTeam(String teamId) {
        return Optional.ofNullable(teams.get(teamId));
    }

    /**
     * List all teams.
     */
    public List<AgentTeam> listTeams() {
        return new ArrayList<>(teams.values());
    }

    /**
     * Delete a team.
     */
    public boolean deleteTeam(String teamId) {
        AgentTeam removed = teams.remove(teamId);
        if (removed != null) {
            log.info("Deleted team: {}", teamId);
            return true;
        }
        return false;
    }

    /**
     * Represents a team of agents.
     */
    public static class AgentTeam {
        private final String id;
        private final String name;
        private final Map<String, Subagent> members = new ConcurrentHashMap<>();

        public AgentTeam(String name) {
            this.id = UUID.randomUUID().toString();
            this.name = name;
        }

        public void addMember(Subagent agent) {
            members.put(agent.id(), agent);
        }

        public void removeMember(String agentId) {
            members.remove(agentId);
        }

        public Optional<Subagent> getMember(String agentId) {
            return Optional.ofNullable(members.get(agentId));
        }

        public Collection<Subagent> getMembers() {
            return members.values();
        }

        public String getId() { return id; }
        public String getName() { return name; }
    }
}
