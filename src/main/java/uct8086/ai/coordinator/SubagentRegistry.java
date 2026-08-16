package uct8086.ai.coordinator;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * In-memory registry of sub-agents spawned through the orchestration tools
 * ('agent' / 'send_message' / 'task_stop'), keyed by user id and agent name.
 *
 * <p>Maps to OpenHarness's in-process swarm registry: every spawned agent gets
 * a stable name and its own session. The registry tracks the session id,
 * lifecycle status, latest response and execution handle, so the orchestrator
 * can later continue the agent (send_message) or abort it (task_stop) by name.
 *
 * <p>States and transitions (STOPPED is terminal and wins over late results):
 * <pre>
 *   RUNNING --&gt; COMPLETED | FAILED | STOPPED
 * </pre>
 */
@Component
public class SubagentRegistry {

    private static final Logger log = LoggerFactory.getLogger(SubagentRegistry.class);

    /** Lifecycle status of a spawned sub-agent. */
    public enum Status { RUNNING, COMPLETED, FAILED, STOPPED }

    /** Mutable tracking record for one spawned sub-agent. */
    public static final class SubagentState {
        private final String name;
        private final String role;
        private final String sessionId;
        private volatile Status status = Status.RUNNING;
        private volatile String lastResponse;
        private volatile Future<?> future;

        SubagentState(String name, String role, String sessionId) {
            this.name = name;
            this.role = role;
            this.sessionId = sessionId;
        }

        public String name() { return name; }
        public String role() { return role; }
        public String sessionId() { return sessionId; }
        public Status status() { return status; }
        public String lastResponse() { return lastResponse; }
        public Future<?> future() { return future; }

        /** Attach the execution handle of a background run (for send_message/task_stop). */
        public void attachFuture(Future<?> future) { this.future = future; }
    }

    private final Map<Long, ConcurrentHashMap<String, SubagentState>> byUser = new ConcurrentHashMap<>();

    /**
     * Register a freshly spawned sub-agent under the given name.
     *
     * @throws IllegalStateException if an agent with the same name is still RUNNING
     *                               (finished/stopped agents may be re-spawned)
     */
    public SubagentState register(Long userId, String name, String role, String sessionId) {
        ConcurrentHashMap<String, SubagentState> states =
                byUser.computeIfAbsent(userId, k -> new ConcurrentHashMap<>());
        SubagentState fresh = new SubagentState(name, role, sessionId);
        SubagentState prev = states.putIfAbsent(name, fresh);
        if (prev != null) {
            if (prev.status() == Status.RUNNING) {
                throw new IllegalStateException(
                        "Sub-agent '" + name + "' is already running; wait for it, use send_message, "
                                + "or task_stop it before re-spawning under the same name");
            }
            // Finished agents can be re-spawned: replace the stale entry.
            states.put(name, fresh);
        }
        return fresh;
    }

    /** Look up a spawned sub-agent by name. */
    public Optional<SubagentState> get(Long userId, String name) {
        ConcurrentHashMap<String, SubagentState> states = byUser.get(userId);
        return states == null ? Optional.empty() : Optional.ofNullable(states.get(name));
    }

    /** List the sub-agents spawned for a user (for diagnostics). */
    public List<SubagentState> list(Long userId) {
        ConcurrentHashMap<String, SubagentState> states = byUser.get(userId);
        return states == null ? List.of() : List.copyOf(states.values());
    }

    /** Mark the agent as finished successfully. No-op if it was already stopped. */
    public boolean markCompleted(SubagentState state, String response) {
        if (state.status() == Status.STOPPED) {
            log.info("Sub-agent '{}' result discarded: already STOPPED", state.name());
            return false;
        }
        log.info("Sub-agent '{}' → COMPLETED (sessionId={})", state.name(), state.sessionId());
        state.status = Status.COMPLETED;
        state.lastResponse = response;
        return true;
    }

    /** Mark the agent as failed. No-op if it was already stopped. */
    public boolean markFailed(SubagentState state, String error) {
        if (state.status() == Status.STOPPED) {
            log.info("Sub-agent '{}' failure discarded: already STOPPED", state.name());
            return false;
        }
        log.warn("Sub-agent '{}' → FAILED: {}", state.name(), error);
        state.status = Status.FAILED;
        state.lastResponse = error;
        return true;
    }

    /** Mark the agent as stopped (terminal). Cancels its execution handle if any. */
    public void markStopped(SubagentState state) {
        log.info("Sub-agent '{}' → STOPPED (cancelling execution handle)", state.name());
        state.status = Status.STOPPED;
        Future<?> future = state.future();
        if (future != null) {
            future.cancel(true);
        }
    }
}
