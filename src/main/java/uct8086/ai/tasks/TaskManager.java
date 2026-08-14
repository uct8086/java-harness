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
 * <p>Tasks are scoped per user, so each user only sees and manipulates their own
 * tasks.
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

    // userId -> taskId -> task
    private final Map<Long, Map<String, BackgroundTask>> tasks = new ConcurrentHashMap<>();
    private final Map<String, Future<?>> taskFutures = new ConcurrentHashMap<>();

    // Bounded pool instead of newCachedThreadPool() to avoid unbounded thread creation
    // (which risks OOM under heavy load). Rejected tasks mark the task as failed.
    private final ExecutorService executor = new ThreadPoolExecutor(
            4, 16, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(1000),
            new ThreadFactory() {
                private final java.util.concurrent.atomic.AtomicInteger counter =
                        new java.util.concurrent.atomic.AtomicInteger();
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "uct8086-task-" + counter.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }
            },
            (r, executor) -> log.error("Task rejected: executor queue full, task dropped"));

    private Map<String, BackgroundTask> tasksFor(Long userId) {
        return tasks.computeIfAbsent(userId, k -> new ConcurrentHashMap<>());
    }

    /**
     * Create and start a background task for the given user.
     *
     * @param name        task name
     * @param description task description
     * @param work        the work to execute
     * @return the created task
     */
    public BackgroundTask createTask(Long userId, String name, String description, Callable<String> work) {
        BackgroundTask task = new BackgroundTask(name, description);
        tasksFor(userId).put(task.id(), task);

        Future<?> future = executor.submit(() -> {
            tasksFor(userId).put(task.id(), task.started());
            log.info("Task started: {} ({}) for user {}", name, task.id(), userId);

            try {
                String result = work.call();
                tasksFor(userId).put(task.id(), task.completed(result));
                log.info("Task completed: {} ({}) for user {}", name, task.id(), userId);
            } catch (InterruptedException e) {
                tasksFor(userId).put(task.id(), task.cancelled());
                log.info("Task cancelled: {} ({}) for user {}", name, task.id(), userId);
            } catch (Exception e) {
                tasksFor(userId).put(task.id(), task.failed(e.getMessage()));
                log.error("Task failed: {} ({}) for user {}", name, task.id(), userId, e);
            }
        });

        taskFutures.put(task.id(), future);
        return task;
    }

    /**
     * Get a task by ID for the given user.
     */
    public Optional<BackgroundTask> getTask(Long userId, String taskId) {
        Map<String, BackgroundTask> map = tasks.get(userId);
        return map != null ? Optional.ofNullable(map.get(taskId)) : Optional.empty();
    }

    /**
     * List all tasks for the given user.
     */
    public List<BackgroundTask> listTasks(Long userId) {
        Map<String, BackgroundTask> map = tasks.get(userId);
        if (map == null) {
            return List.of();
        }
        return map.values().stream()
                .sorted(Comparator.comparing(BackgroundTask::createdAt))
                .toList();
    }

    /**
     * List tasks by status for the given user.
     */
    public List<BackgroundTask> listTasks(Long userId, TaskStatus status) {
        Map<String, BackgroundTask> map = tasks.get(userId);
        if (map == null) {
            return List.of();
        }
        return map.values().stream()
                .filter(t -> t.status() == status)
                .sorted(Comparator.comparing(BackgroundTask::createdAt))
                .toList();
    }

    /**
     * Cancel a running task for the given user.
     */
    public boolean cancelTask(Long userId, String taskId) {
        Future<?> future = taskFutures.get(taskId);
        if (future != null && !future.isDone()) {
            future.cancel(true);
            Map<String, BackgroundTask> map = tasks.get(userId);
            BackgroundTask task = map != null ? map.get(taskId) : null;
            if (task != null) {
                map.put(taskId, task.cancelled());
            }
            log.info("Task cancelled: {} for user {}", taskId, userId);
            return true;
        }
        return false;
    }

    /**
     * Get task output for the given user.
     */
    public Optional<String> getTaskOutput(Long userId, String taskId) {
        Map<String, BackgroundTask> map = tasks.get(userId);
        BackgroundTask task = map != null ? map.get(taskId) : null;
        return task != null ? Optional.ofNullable(task.output()) : Optional.empty();
    }

    /**
     * Remove a completed/failed/cancelled task for the given user.
     */
    public boolean removeTask(Long userId, String taskId) {
        Map<String, BackgroundTask> map = tasks.get(userId);
        BackgroundTask task = map != null ? map.get(taskId) : null;
        if (task != null && task.status() != TaskStatus.RUNNING && task.status() != TaskStatus.PENDING) {
            map.remove(taskId);
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
