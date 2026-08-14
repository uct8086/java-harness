package uct8086.ai.tasks;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import uct8086.ai.common.enums.TaskStatus;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * Distributed background-task manager backed by Redis Stream (message queue).
 *
 * <p>Replaces the in-memory task map with a Redis Stream + consumer-group design so
 * that tasks are dispatched at-least-once across horizontally-scaled instances and
 * their state survives restarts.
 *
 * <p><b>Architecture:</b>
 * <ul>
 *   <li><b>Producers</b> call {@link #createTask} with a serializable task type +
 *       payload ({@code taskType} + {@code Map<String,String> payload}) and enqueue it
 *       onto the stream {@code harness:task:stream}.</li>
 *   <li><b>Consumers</b> (one per instance, sharing a consumer group) read messages and
 *       dispatch them to a {@link TaskHandler} registered for the task type.</li>
 *   <li><b>State</b> (status/output) is stored in a Redis Hash {@code harness:task:{userId}},
 *       visible to all instances.</li>
 * </ul>
 *
 * <p>Note: task bodies are serializable data (type + payload), NOT JVM
 * {@code Callable} objects, because a {@code Callable} cannot cross process boundaries.
 * Any code that previously passed an inline {@code Callable} must now submit a
 * task type + payload plus a registered {@link TaskHandler}.
 */
@Component
public class TaskManager {

    private static final Logger log = LoggerFactory.getLogger(TaskManager.class);

    private static final String STREAM_KEY = "harness:task:stream";
    private static final String CONSUMER_GROUP = "harness-task-consumers";
    private static final String TASK_HASH_PREFIX = "harness:task:";
    private static final String CANCEL_FLAG_PREFIX = "harness:task:cancel:";
    private static final Duration TASK_TTL = Duration.ofHours(24);
    private static final Duration CANCEL_FLAG_TTL = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /** taskType -> handler. Handlers run the actual work when a message is consumed. */
    private final Map<String, TaskHandler> handlers = new ConcurrentHashMap<>();

    private final Map<String, Future<?>> taskFutures = new ConcurrentHashMap<>();

    /** Bounded pool for executing task handlers. */
    private final ExecutorService executor = new ThreadPoolExecutor(
            4, 16, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(1000),
            r -> {
                Thread t = new Thread(r, "uct8086-task");
                t.setDaemon(true);
                return t;
            },
            (r, e) -> log.error("Task rejected: executor queue full"));

    /** Background poller that consumes the stream. */
    private volatile boolean running = false;
    private Thread pollerThread;

    public TaskManager(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void start() {
        ensureConsumerGroup();
        running = true;
        pollerThread = new Thread(this::pollLoop, "uct8086-task-consumer");
        pollerThread.setDaemon(true);
        pollerThread.start();
        log.info("TaskManager consumer started (stream={}, group={})", STREAM_KEY, CONSUMER_GROUP);
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        if (pollerThread != null) {
            pollerThread.interrupt();
        }
        executor.shutdownNow();
        log.info("Task manager shut down");
    }

    /**
     * Register a handler for a task type.
     */
    public void registerHandler(String taskType, TaskHandler handler) {
        handlers.put(taskType, handler);
    }

    /**
     * Create a task: persist its state and enqueue a message for consumers.
     */
    public BackgroundTask createTask(Long userId, String name, String description,
                                     String taskType, Map<String, String> payload) {
        BackgroundTask task = new BackgroundTask(name, description).withStatus(TaskStatus.PENDING);
        persistTask(userId, task);

        Map<String, String> message = new HashMap<>(payload);
        message.put("userId", String.valueOf(userId));
        message.put("taskId", task.id());
        message.put("taskType", taskType);

        redisTemplate.opsForStream().add(
                StreamRecords.newRecord().ofMap(message).withStreamKey(STREAM_KEY));

        log.info("Task enqueued: {} ({}) type={} for user {}", name, task.id(), taskType, userId);
        return task;
    }

    // ========== Consumer loop ==========

    private void pollLoop() {
        String consumerName = "consumer-" + UUID.randomUUID().toString().substring(0, 8);
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                List<MapRecord<String, Object, Object>> records =
                        redisTemplate.opsForStream().read(
                                Consumer.from(CONSUMER_GROUP, consumerName),
                                StreamReadOptions.empty().count(10).block(Duration.ofSeconds(2)),
                                StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed()));
                if (records == null || records.isEmpty()) {
                    continue;
                }
                for (MapRecord<String, Object, Object> record : records) {
                    // handle() acknowledges the message only after the handler has
                    // finished (success or failure), so a crash mid-execution leaves
                    // the message pending and it can be reclaimed by another instance.
                    handle(record);
                }
            } catch (Exception e) {
                log.warn("Task consumer poll error", e);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void handle(MapRecord<String, Object, Object> record) {
        String messageId = record.getId().getValue();
        Map<String, String> fields = new LinkedHashMap<>();
        record.getValue().forEach((k, v) -> fields.put(String.valueOf(k), String.valueOf(v)));

        Long userId = Long.valueOf(fields.get("userId"));
        String taskId = fields.get("taskId");
        String taskType = fields.getOrDefault("taskType", "default");

        TaskHandler handler = handlers.get(taskType);
        if (handler == null) {
            BackgroundTask task = loadTask(userId, taskId);
            if (task != null) {
                updateStatus(userId, taskId, task.failed("No handler registered for task type: " + taskType));
            }
            log.warn("No handler for task type '{}' (task {})", taskType, taskId);
            // No handler will ever exist: acknowledge so the message is not redelivered forever.
            acknowledge(messageId);
            return;
        }

        BackgroundTask task = loadTask(userId, taskId);
        if (task == null) {
            acknowledge(messageId);
            return;
        }
        updateStatus(userId, taskId, task.started());

        Future<?> future = executor.submit(() -> {
            try {
                if (isCancelRequested(taskId)) {
                    updateStatus(userId, taskId, loadTask(userId, taskId).cancelled());
                    log.info("Task cancelled before execution: {} ({})", task.name(), taskId);
                    return;
                }
                String result = handler.execute(fields);
                updateStatus(userId, taskId, loadTask(userId, taskId).completed(result));
                log.info("Task completed: {} ({})", task.name(), taskId);
            } catch (InterruptedException e) {
                updateStatus(userId, taskId, loadTask(userId, taskId).cancelled());
                log.info("Task cancelled: {} ({})", task.name(), taskId);
            } catch (Exception e) {
                updateStatus(userId, taskId, loadTask(userId, taskId).failed(e.getMessage()));
                log.error("Task failed: {} ({})", task.name(), taskId, e);
            } finally {
                taskFutures.remove(taskId);
                clearCancelFlag(taskId);
                // Acknowledge only after execution finishes so an unfinished task
                // (e.g. instance crash) stays pending and can be reclaimed.
                acknowledge(messageId);
            }
        });
        taskFutures.put(taskId, future);
    }

    private void acknowledge(String messageId) {
        try {
            redisTemplate.opsForStream().acknowledge(STREAM_KEY, CONSUMER_GROUP,
                    org.springframework.data.redis.connection.stream.RecordId.of(messageId));
        } catch (Exception e) {
            log.warn("Failed to acknowledge task message {}", messageId, e);
        }
    }

    // ========== Query API ==========

    public Optional<BackgroundTask> getTask(Long userId, String taskId) {
        BackgroundTask task = loadTask(userId, taskId);
        return task != null ? Optional.of(task) : Optional.empty();
    }

    public List<BackgroundTask> listTasks(Long userId) {
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(TASK_HASH_PREFIX + userId);
        return entries.values().stream()
                .map(v -> deserialize(String.valueOf(v)))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(BackgroundTask::createdAt))
                .toList();
    }

    public List<BackgroundTask> listTasks(Long userId, TaskStatus status) {
        return listTasks(userId).stream()
                .filter(t -> t.status() == status)
                .toList();
    }

    public boolean cancelTask(Long userId, String taskId) {
        BackgroundTask task = loadTask(userId, taskId);
        if (task == null || (task.status() != TaskStatus.RUNNING && task.status() != TaskStatus.PENDING)) {
            // Nothing to cancel.
            return false;
        }

        // 1. Set a cross-instance cancel flag in Redis so the executing instance
        //    (which may be a different node) observes it and aborts.
        setCancelFlag(taskId);

        // 2. If the task happens to run on THIS instance, interrupt its thread too.
        Future<?> future = taskFutures.get(taskId);
        if (future != null && !future.isDone()) {
            future.cancel(true);
        }

        // 3. Update status optimistically. If a worker is mid-flight, its finally
        //    block will also mark CANCELLED / clear the flag consistently.
        updateStatus(userId, taskId, task.cancelled());
        log.info("Task cancel requested: {} for user {}", taskId, userId);
        return true;
    }

    private void setCancelFlag(String taskId) {
        try {
            redisTemplate.opsForValue().set(CANCEL_FLAG_PREFIX + taskId, "1", CANCEL_FLAG_TTL);
        } catch (Exception e) {
            log.warn("Failed to set cancel flag for task {}", taskId, e);
        }
    }

    private boolean isCancelRequested(String taskId) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(CANCEL_FLAG_PREFIX + taskId));
        } catch (Exception e) {
            return false;
        }
    }

    private void clearCancelFlag(String taskId) {
        try {
            redisTemplate.delete(CANCEL_FLAG_PREFIX + taskId);
        } catch (Exception e) {
            log.warn("Failed to clear cancel flag for task {}", taskId, e);
        }
    }

    public Optional<String> getTaskOutput(Long userId, String taskId) {
        BackgroundTask task = loadTask(userId, taskId);
        return task != null ? Optional.ofNullable(task.output()) : Optional.empty();
    }

    public boolean removeTask(Long userId, String taskId) {
        BackgroundTask task = loadTask(userId, taskId);
        if (task != null && task.status() != TaskStatus.RUNNING && task.status() != TaskStatus.PENDING) {
            redisTemplate.opsForHash().delete(TASK_HASH_PREFIX + userId, taskId);
            taskFutures.remove(taskId);
            clearCancelFlag(taskId);
            return true;
        }
        return false;
    }

    // ========== Redis persistence helpers ==========

    private void ensureConsumerGroup() {
        try {
            redisTemplate.opsForStream().createGroup(STREAM_KEY, CONSUMER_GROUP);
        } catch (Exception e) {
            // group already exists (or streams unsupported) — ignore
        }
    }

    private void persistTask(Long userId, BackgroundTask task) {
        redisTemplate.opsForHash().put(TASK_HASH_PREFIX + userId, task.id(), serialize(task));
        redisTemplate.expire(TASK_HASH_PREFIX + userId, TASK_TTL);
    }

    private void updateStatus(Long userId, String taskId, BackgroundTask updated) {
        persistTask(userId, updated);
    }

    private BackgroundTask loadTask(Long userId, String taskId) {
        Object raw = redisTemplate.opsForHash().get(TASK_HASH_PREFIX + userId, taskId);
        return raw != null ? deserialize(String.valueOf(raw)) : null;
    }

    private String serialize(BackgroundTask task) {
        try {
            return objectMapper.writeValueAsString(task);
        } catch (Exception e) {
            log.error("Failed to serialize task {}", task.id(), e);
            return null;
        }
    }

    private BackgroundTask deserialize(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.error("Failed to deserialize task JSON", e);
            return null;
        }
    }

    /**
     * A task handler: executes the actual work for a consumed message and returns
     * the result string.
     */
    @FunctionalInterface
    public interface TaskHandler {
        String execute(Map<String, String> fields) throws Exception;
    }
}
