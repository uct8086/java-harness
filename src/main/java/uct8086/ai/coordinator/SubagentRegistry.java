package uct8086.ai.coordinator;

import com.fasterxml.jackson.core.type.TypeReference;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Distributed registry of sub-agents spawned through the orchestration tools
 * ('agent' / 'send_message' / 'task_stop'), keyed by user id and agent name.
 *
 * <p>Backed by Redis so any instance in a horizontally-scaled deployment can
 * read and update sub-agent state. State is stored as JSON in a Redis Hash
 * keyed by {@code harness:subagent:{userId}} with field = agent name. Each
 * entry holds: {@code role}, {@code sessionId}, {@code taskId}, {@code status},
 * {@code lastResponse}.
 *
 * <p>The {@code taskId} links the sub-agent to a {@link uct8086.ai.tasks.TaskManager}
 * task (Redis Stream message). Awaiting completion and cancellation are delegated
 * to {@code TaskManager} (polling the task's status / calling {@code cancelTask}),
 * which already supports cross-instance at-least-once dispatch and a distributed
 * cancel flag. This replaces the previous in-memory {@code Future} handle that
 * only worked on the JVM that spawned the sub-agent.
 *
 * <p>States and transitions (STOPPED is terminal and wins over late results):
 * <pre>
 *   RUNNING --> COMPLETED | FAILED | STOPPED
 * </pre>
 *
 * <p>Note: the previous in-memory design used a {@code ConcurrentHashMap} plus a
 * {@code java.util.concurrent.Future} for cancellation. Under multi-instance
 * deployment that design broke: the registry, the spawned session id, and the
 * cancellation handle were all local to the JVM that created them, so
 * {@code send_message} / {@code task_stop} issued from another instance could
 * not find the sub-agent. This Redis-backed version restores cross-instance
 * visibility for all three orchestration primitives.
 */
@Component
public class SubagentRegistry {

    private static final Logger log = LoggerFactory.getLogger(SubagentRegistry.class);

    private static final String HASH_PREFIX = "harness:subagent:";
    private static final Duration TTL = Duration.ofHours(24);

    /** Shared Jackson mapper for (de)serializing SubagentState JSON. */
    static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** Lifecycle status of a spawned sub-agent. */
    public enum Status { RUNNING, COMPLETED, FAILED, STOPPED }

    /** Mutable tracking record for one spawned sub-agent. */
    public static final class SubagentState {
        private final String name;
        private final String role;
        private final String sessionId;
        private final String taskId;
        private volatile Status status;
        private volatile String lastResponse;

        /**
         * Transient reference to the owning userId — NOT persisted to Redis. It is
         * set when the snapshot is loaded via {@link #get} or {@link #register} so
         * that {@link #markCompleted} / {@link #markFailed} / {@link #markStopped}
         * called on this snapshot in the same orchestration flow can write back to
         * the right Redis key without the caller having to thread the userId through
         * every call.
         */
        private transient Long userIdRef;

        SubagentState(String name, String role, String sessionId, String taskId,
                      Status status, String lastResponse) {
            this.name = name;
            this.role = role;
            this.sessionId = sessionId;
            this.taskId = taskId;
            this.status = status;
            this.lastResponse = lastResponse;
        }

        /** Fresh RUNNING state for a newly spawned sub-agent. */
        SubagentState(String name, String role, String sessionId, String taskId) {
            this(name, role, sessionId, taskId, Status.RUNNING, null);
        }

        public String name() { return name; }
        public String role() { return role; }
        public String sessionId() { return sessionId; }
        public String taskId() { return taskId; }
        public Status status() { return status; }
        public String lastResponse() { return lastResponse; }

        /** Serialize the state to a JSON string for Redis Hash storage. */
        String toJson() {
            try {
                Map<String, String> m = new LinkedHashMap<>();
                m.put("name", name);
                m.put("role", role == null ? "" : role);
                m.put("sessionId", sessionId == null ? "" : sessionId);
                m.put("taskId", taskId == null ? "" : taskId);
                m.put("status", status.name());
                m.put("lastResponse", lastResponse == null ? "" : lastResponse);
                return SubagentRegistry.MAPPER.writeValueAsString(m);
            } catch (Exception e) {
                throw new RuntimeException("Failed to serialize SubagentState", e);
            }
        }

        /** Deserialize from the JSON string stored in Redis. */
        static SubagentState fromJson(String json) {
            if (json == null || json.isBlank()) return null;
            try {
                Map<String, String> m = SubagentRegistry.MAPPER.readValue(
                        json, new TypeReference<Map<String, String>>() {});
                String name = m.get("name");
                String role = nullOrEmpty(m.get("role"));
                String sessionId = nullOrEmpty(m.get("sessionId"));
                String taskId = nullOrEmpty(m.get("taskId"));
                Status status;
                try {
                    status = Status.valueOf(m.getOrDefault("status", "RUNNING"));
                } catch (Exception e) {
                    status = Status.RUNNING;
                }
                String lastResponse = nullOrEmpty(m.get("lastResponse"));
                return new SubagentState(name, role, sessionId, taskId, status, lastResponse);
            } catch (Exception e) {
                SubagentRegistry.log.warn("Failed to deserialize SubagentState JSON: {}", e.getMessage());
                return null;
            }
        }

        private static String nullOrEmpty(String s) {
            return (s == null || s.isEmpty()) ? null : s;
        }
    }

    private final StringRedisTemplate redisTemplate;

    public SubagentRegistry(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    private static String hashKey(Long userId) {
        return HASH_PREFIX + userId;
    }

    /**
     * Register a freshly spawned sub-agent under the given name.
     *
     * @throws IllegalStateException if an agent with the same name is still RUNNING
     *                               (finished/stopped agents may be re-spawned)
     */
    public SubagentState register(Long userId, String name, String role, String sessionId) {
        return register(userId, name, role, sessionId, null);
    }

    /**
     * Register with an explicit taskId (the distributed task id assigned by
     * TaskManager when the sub-agent is dispatched via Redis Stream).
     */
    public SubagentState register(Long userId, String name, String role, String sessionId, String taskId) {
        SubagentState existing = get(userId, name).orElse(null);
        if (existing != null && existing.status() == Status.RUNNING) {
            throw new IllegalStateException(
                    "Sub-agent '" + name + "' is already running; wait for it, use send_message, "
                            + "or task_stop it before re-spawning under the same name");
        }
        SubagentState fresh = new SubagentState(name, role, sessionId, taskId);
        fresh.userIdRef = userId;
        save(userId, fresh);
        log.info("Registered sub-agent '{}' (userId={}, sessionId={}, taskId={})",
                name, userId, sessionId, taskId);
        return fresh;
    }

    /** Look up a spawned sub-agent by name. */
    public Optional<SubagentState> get(Long userId, String name) {
        try {
            Object raw = redisTemplate.opsForHash().get(hashKey(userId), name);
            if (raw == null) return Optional.empty();
            SubagentState state = SubagentState.fromJson(String.valueOf(raw));
            if (state == null) return Optional.empty();
            state.userIdRef = userId;
            return Optional.of(state);
        } catch (Exception e) {
            log.warn("Failed to read sub-agent '{}' for user {}: {}", name, userId, e.getMessage());
            return Optional.empty();
        }
    }

    /** List the sub-agents spawned for a user (for diagnostics). */
    public List<SubagentState> list(Long userId) {
        try {
            Map<Object, Object> entries = redisTemplate.opsForHash().entries(hashKey(userId));
            List<SubagentState> result = new ArrayList<>();
            for (Object v : entries.values()) {
                SubagentState s = SubagentState.fromJson(String.valueOf(v));
                if (s != null) {
                    s.userIdRef = userId;
                    result.add(s);
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("Failed to list sub-agents for user {}: {}", userId, e.getMessage());
            return List.of();
        }
    }

    /** Mark the agent as finished successfully. No-op if it was already stopped. */
    public boolean markCompleted(SubagentState state, String response) {
        if (state.status() == Status.STOPPED) {
            log.info("Sub-agent '{}' result discarded: already STOPPED", state.name());
            return false;
        }
        Long userId = resolveUserIdFor(state);
        if (userId == null) {
            // Best-effort fallback: update the in-memory snapshot directly.
            state.status = Status.COMPLETED;
            state.lastResponse = response;
            return true;
        }
        SubagentState fresh = get(userId, state.name()).orElse(state);
        if (fresh.status() == Status.STOPPED) {
            log.info("Sub-agent '{}' result discarded: already STOPPED (re-read)", fresh.name());
            return false;
        }
        SubagentState updated = new SubagentState(
                fresh.name(), fresh.role(), fresh.sessionId(), fresh.taskId(),
                Status.COMPLETED, response);
        updated.userIdRef = userId;
        save(userId, updated);
        // Reflect back on the caller's reference so its .status() / .lastResponse() reads are consistent.
        state.status = Status.COMPLETED;
        state.lastResponse = response;
        log.info("Sub-agent '{}' -> COMPLETED (sessionId={})", fresh.name(), fresh.sessionId());
        return true;
    }

    /** Mark the agent as failed. No-op if it was already stopped. */
    public boolean markFailed(SubagentState state, String error) {
        if (state.status() == Status.STOPPED) {
            log.info("Sub-agent '{}' failure discarded: already STOPPED", state.name());
            return false;
        }
        Long userId = resolveUserIdFor(state);
        if (userId == null) {
            state.status = Status.FAILED;
            state.lastResponse = error;
            return true;
        }
        SubagentState fresh = get(userId, state.name()).orElse(state);
        if (fresh.status() == Status.STOPPED) {
            log.info("Sub-agent '{}' failure discarded: already STOPPED (re-read)", fresh.name());
            return false;
        }
        SubagentState updated = new SubagentState(
                fresh.name(), fresh.role(), fresh.sessionId(), fresh.taskId(),
                Status.FAILED, error);
        updated.userIdRef = userId;
        save(userId, updated);
        state.status = Status.FAILED;
        state.lastResponse = error;
        log.warn("Sub-agent '{}' -> FAILED: {}", fresh.name(), error);
        return true;
    }

    /** Mark the agent as stopped (terminal). */
    public void markStopped(SubagentState state) {
        log.info("Sub-agent '{}' -> STOPPED", state.name());
        Long userId = resolveUserIdFor(state);
        if (userId == null) {
            state.status = Status.STOPPED;
            return;
        }
        SubagentState fresh = get(userId, state.name()).orElse(state);
        SubagentState updated = new SubagentState(
                fresh.name(), fresh.role(), fresh.sessionId(), fresh.taskId(),
                Status.STOPPED, fresh.lastResponse());
        updated.userIdRef = userId;
        save(userId, updated);
        state.status = Status.STOPPED;
    }

    /**
     * Update only the taskId field (e.g. when the orchestrator created the sub-agent
     * registry entry before enqueuing the distributed task and now knows the taskId
     * returned by {@code TaskManager.createTask}).
     */
    public boolean updateTaskId(Long userId, String name, String taskId) {
        try {
            SubagentState fresh = get(userId, name).orElse(null);
            if (fresh == null) return false;
            SubagentState updated = new SubagentState(
                    fresh.name(), fresh.role(), fresh.sessionId(), taskId,
                    fresh.status(), fresh.lastResponse());
            updated.userIdRef = userId;
            save(userId, updated);
            log.info("Sub-agent '{}' taskId updated to {}", name, taskId);
            return true;
        } catch (Exception e) {
            log.warn("Failed to update taskId for sub-agent '{}': {}", name, e.getMessage());
            return false;
        }
    }

    /**
     * Update only the sessionId field (e.g. when the distributed worker created
     * the session on its side and needs to publish it back so other instances
     * can later resume the sub-agent's conversation).
     */
    public boolean updateSessionId(Long userId, String name, String sessionId) {
        try {
            SubagentState fresh = get(userId, name).orElse(null);
            if (fresh == null) return false;
            SubagentState updated = new SubagentState(
                    fresh.name(), fresh.role(), sessionId, fresh.taskId(),
                    fresh.status(), fresh.lastResponse());
            updated.userIdRef = userId;
            save(userId, updated);
            log.info("Sub-agent '{}' sessionId updated to {}", name, sessionId);
            return true;
        } catch (Exception e) {
            log.warn("Failed to update sessionId for sub-agent '{}': {}", name, e.getMessage());
            return false;
        }
    }

    // ========== Internal helpers ==========

    /**
     * We need the userId to write back to Redis, but SubagentState doesn't carry it
     * (the previous in-memory version didn't need it because the Map was keyed by
     * userId). The orchestrating tool (AgentTool / SendMessageTool / TaskStopTool)
     * always knows the userId, so they pass it via the register/get APIs; but
     * markCompleted/markFailed/markStopped are called with a SubagentState that
     * was retrieved via get(userId, name), so we can't recover the userId from it.
     *
     * <p>Solution: SubagentState stores a transient userId reference set on get()
     * and register() — not persisted to Redis, just kept in memory on the snapshot
     * for the duration of one orchestration call. This keeps the public API the
     * same as the in-memory version.
     */
    private Long resolveUserIdFor(SubagentState state) {
        return state.userIdRef;
    }

    private void save(Long userId, SubagentState state) {
        try {
            String key = hashKey(userId);
            redisTemplate.opsForHash().put(key, state.name(), state.toJson());
            redisTemplate.expire(key, TTL);
            // Attach the userId reference for subsequent mark* calls in the same orchestration flow.
            state.userIdRef = userId;
        } catch (Exception e) {
            log.error("Failed to persist sub-agent state '{}' for user {}", state.name(), userId, e);
        }
    }
}
