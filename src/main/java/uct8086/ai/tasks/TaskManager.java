package uct8086.ai.tasks;

import uct8086.ai.common.enums.TaskStatus;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Manager for background tasks.
 * Maps to OpenHarness's Task Management system (TaskCreate/Get/List/Update/Stop/Output).
 *
 * <p>Supports:
 * <ul>
 *   <li>Creating background tasks with async execution</li>
 *   <li>Tracking task status (PENDING → RUNNING → COMPLETED/FAILED/CANCELLED)</li>
 *   <li>Retrieving task output</li>
 *   <li>Cancelling running tasks</li>
 * </ul>
 */
@Component
public class TaskManager {

    private static final Logger log = LoggerFactory.getLogger(TaskManager.class);

    private final Map<String, BackgroundTask> tasks = new ConcurrentHashMap<>();
    private final Map<String, Future<?>> taskFutures = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    /**
     * Create and start a background task.
     *
     * @param name        task name
     * @param description task description
     * @param work        the work to execute
     * @return the created task
     */
    public BackgroundTask createTask(String name, String description, Callable<String> work) {
        BackgroundTask task = new BackgroundTask(name, description);
        tasks.put(task.id(), task);

        Future<?> future = executor.submit(() -> {
            tasks.put(task.id(), task.started());
            log.info("Task started: {} ({})", name, task.id());

            try {
                String result = work.call();
                tasks.put(task.id(), task.completed(result));
                log.info("Task completed: {} ({})", name, task.id());
            } catch (InterruptedException e) {
                tasks.put(task.id(), task.cancelled());
                log.info("Task cancelled: {} ({})", name, task.id());
            } catch (Exception e) {
                tasks.put(task.id(), task.failed(e.getMessage()));
                log.error("Task failed: {} ({})", name, task.id(), e);
            }
        });

        taskFutures.put(task.id(), future);
        return task;
    }

    /**
     * Get a task by ID.
     */
    public Optional<BackgroundTask> getTask(String taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    /**
     * List all tasks.
     */
    public List<BackgroundTask> listTasks() {
        return tasks.values().stream()
                .sorted(Comparator.comparing(BackgroundTask::createdAt))
                .toList();
    }

    /**
     * List tasks by status.
     */
    public List<BackgroundTask> listTasks(TaskStatus status) {
        return tasks.values().stream()
                .filter(t -> t.status() == status)
                .sorted(Comparator.comparing(BackgroundTask::createdAt))
                .toList();
    }

    /**
     * Cancel a running task.
     */
    public boolean cancelTask(String taskId) {
        Future<?> future = taskFutures.get(taskId);
        if (future != null && !future.isDone()) {
            future.cancel(true);
            BackgroundTask task = tasks.get(taskId);
            if (task != null) {
                tasks.put(taskId, task.cancelled());
            }
            log.info("Task cancelled: {}", taskId);
            return true;
        }
        return false;
    }

    /**
     * Get task output.
     */
    public Optional<String> getTaskOutput(String taskId) {
        BackgroundTask task = tasks.get(taskId);
        return task != null ? Optional.ofNullable(task.output()) : Optional.empty();
    }

    /**
     * Remove a completed/failed/cancelled task.
     */
    public boolean removeTask(String taskId) {
        BackgroundTask task = tasks.get(taskId);
        if (task != null && task.status() != TaskStatus.RUNNING && task.status() != TaskStatus.PENDING) {
            tasks.remove(taskId);
            taskFutures.remove(taskId);
            return true;
        }
        return false;
    }

    /**
     * Shutdown the task manager.
     */
    public void shutdown() {
        executor.shutdownNow();
        log.info("Task manager shut down");
    }
}
