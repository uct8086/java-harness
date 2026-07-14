package uct8086.ai.tasks;

import uct8086.ai.common.enums.TaskStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Represents a background task in the harness.
 * Maps to OpenHarness's Task Management system.
 */
public record BackgroundTask(
        String id,
        String name,
        String description,
        TaskStatus status,
        String output,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        String error
) {
    public BackgroundTask(String name, String description) {
        this(UUID.randomUUID().toString(), name, description,
             TaskStatus.PENDING, null, Instant.now(), null, null, null);
    }

    public BackgroundTask withStatus(TaskStatus newStatus) {
        return new BackgroundTask(id, name, description, newStatus, output,
                createdAt, startedAt, completedAt, error);
    }

    public BackgroundTask withOutput(String newOutput) {
        return new BackgroundTask(id, name, description, status, newOutput,
                createdAt, startedAt, completedAt, error);
    }

    public BackgroundTask started() {
        return new BackgroundTask(id, name, description, TaskStatus.RUNNING, output,
                createdAt, Instant.now(), null, null);
    }

    public BackgroundTask completed(String output) {
        return new BackgroundTask(id, name, description, TaskStatus.COMPLETED, output,
                createdAt, startedAt, Instant.now(), null);
    }

    public BackgroundTask failed(String error) {
        return new BackgroundTask(id, name, description, TaskStatus.FAILED, output,
                createdAt, startedAt, Instant.now(), error);
    }

    public BackgroundTask cancelled() {
        return new BackgroundTask(id, name, description, TaskStatus.CANCELLED, output,
                createdAt, startedAt, Instant.now(), "Task cancelled");
    }
}
