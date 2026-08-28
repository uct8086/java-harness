# 多 Agent 编排：单机实现版本（归档留档）

> 本文归档记录 2026-08-16 引入多 Agent 编排子系统时的**原始单机实现方式**，作为对照参考之用。
> 该实现已于 **2026-08-27** 被 Redis 化的分布式版本替代，现行实现见 [orchestration-patterns.md](./orchestration-patterns.md) §3 与 [总结.md](./总结.md) §4.9。
>
> 留档目的：让后人能快速理解"改造前是什么样子"，以及为什么改造、改造的对照点在哪里。这是一种工程演进过程的留痕，不作为推荐实现。
>
> 对应 commit：`9c8d2f4 feat: 多Agent编排子系统(...)` —— 引入三原语 + `SubagentRegistry` + LOCAL/COORDINATOR/SWARM 拓扑。

---

## 1. 总体设计

单机版编排完全在**单一 JVM 进程内**运作，状态、句柄、派发都不出本机：

```
编排者（同一 JVM）            进程内线程池               SubagentRegistry（内存 Map）
──────────────         ───────────────────         ─────────────────────────
AgentTool.doExecute
  ├─ createSession（MySQL 持久化）        ──── session 独立但跨实例可用
  ├─ subagentRegistry.register(name, role, sessionId)
  │                                        ──── 状态写入 JVM ConcurrentHashMap
  ├─ wait=true?
  │   ├─ 是 → runAndTrack() 同步阻塞返回
  │   └─ 否 → subagentExecutor.submit(...) → Future
  │              state.attachFuture(future) ──── Future 句柄写入 state
  │
  └─ 子代理跑完 → markCompleted/markFailed → state.status = COMPLETED
```

核心特征：**三个原语（`agent` / `send_message` / `task_stop`）的状态与句柄全部在 JVM 内存里**。

---

## 2. 三原语的单机实现

### 2.1 `AgentTool` —— 本地线程池派发

**字段定义**：进程内一个 `ThreadPoolExecutor`，core=2 / max=6 / queue=20，daemon 线程。

```java
// AgentTool.java（单机版，commit 9c8d2f4）
private final ExecutorService subagentExecutor = new ThreadPoolExecutor(
        2, 6, 60L, TimeUnit.SECONDS,
        new LinkedBlockingQueue<>(20),
        r -> {
            Thread t = new Thread(r, "uct8086-subagent-" + System.nanoTime());
            t.setDaemon(true);
            return t;
        });
```

**派发逻辑**（`doExecute`）：

```java
// 创建独立会话（MySQL 持久化）
SessionManager.ConversationSession subSession =
        sessionManager.createSession(userId, "subagent:" + name, true);

// 在内存注册表登记
SubagentRegistry.SubagentState state =
        subagentRegistry.register(userId, name, role, subSession.id());

if (wait) {
    // 同步：直接调用引擎，阻塞返回
    return toToolResult(name, runAndTrack(engine, userId, state, task), subSession.id());
}

// 异步：丢进本地线程池并行跑
try {
    Future<?> future = subagentExecutor.submit(() -> runAndTrack(engine, userId, state, task));
    state.attachFuture(future);          // ← Future 句柄挂在内存 state 上
} catch (RejectedExecutionException e) {
    subagentRegistry.markFailed(state, "sub-agent executor is saturated");
    return ToolResult.error("Cannot start sub-agent '" + name + "': too many sub-agents "
            + "are already queued. Wait for running ones to finish.");
}
return ToolResult.success("Sub-agent '" + name + "' started in the background ...", ...);
```

**`runAndTrack` 工作方法**：在子代理线程里执行 `engine.execute(..., AgentScope.SUBAGENT)`，根据成功/失败回写 register 状态。

```java
private AgentLoopResult runAndTrack(AgentEngine engine, Long userId,
                                    SubagentRegistry.SubagentState state, String task) {
    try {
        AgentLoopResult result = engine.execute(userId, task, state.sessionId(),
                state.role(), AgentScope.SUBAGENT);
        if (result.success()) {
            subagentRegistry.markCompleted(state, result.response());
        } else {
            subagentRegistry.markFailed(state, result.error());
        }
        return result;
    } catch (Exception e) {
        // task_stop 中断的子代理已经在 STOPPED 终态，这里的 markFailed 是 no-op
        subagentRegistry.markFailed(state, e.getMessage());
        return AgentLoopResult.failure(...);
    }
}
```

**生命周期收尾**：`@PreDestroy` 调 `shutdownNow()` 关闭线程池。

```java
@PreDestroy
void shutdownExecutor() {
    subagentExecutor.shutdownNow();
}
```

### 2.2 `SendMessageTool` —— `Future.get` 有界等待

```java
// 子代理还在 RUNNING？
if (state.status() == SubagentRegistry.Status.RUNNING) {
    ToolResult awaited = awaitCompletion(state, name, waitSeconds);
    if (awaited != null) {
        return awaited;
    }
    // 完成后继续
}

// 等待逻辑：直接拿 state 里的 Future 句柄
private ToolResult awaitCompletion(SubagentRegistry.SubagentState state, String name,
                                   int waitSeconds) throws InterruptedException {
    var future = state.future();        // ← 内存中的 Future
    if (future == null || waitSeconds <= 0) {
        return ToolResult.error("Sub-agent '" + name + "' is still running"
                + (waitSeconds <= 0 ? "" : " but has no wait handle")
                + ". Retry in a moment or use task_stop.");
    }
    try {
        future.get(waitSeconds, TimeUnit.SECONDS);   // ← 阻塞等待本地 Future
    } catch (TimeoutException e) {
        return ToolResult.error("Sub-agent '" + name + "' is still running after " + waitSeconds + "s. ...");
    } catch (CancellationException e) {
        return ToolResult.error("Sub-agent '" + name + "' was stopped while running.");
    } catch (ExecutionException e) {
        // 后台执行抛异常，register 已记 FAILED，落下去报错
    }
    return null;
}

// 等完后用 state.sessionId() 续会话
AgentLoopResult result = engine.execute(userId, message, state.sessionId(),
        state.role(), AgentScope.SUBAGENT);
subagentRegistry.markCompleted(state, result.response());
```

### 2.3 `TaskStopTool` —— `Future.cancel` 本地中断

```java
if (state.status() == SubagentRegistry.Status.RUNNING) {
    subagentRegistry.markStopped(state);  // ← 内部调 future.cancel(true)
    log.info("Orchestrator stopped sub-agent '{}' (userId={})", name, userId);
    return ToolResult.success("Sub-agent '" + name + "' stopped. ...");
}
```

`SubagentRegistry.markStopped` 的实现（单机版）：

```java
public void markStopped(SubagentState state) {
    log.info("Sub-agent '{}' → STOPPED (cancelling execution handle)", state.name());
    state.status = Status.STOPPED;
    Future<?> future = state.future();
    if (future != null) {
        future.cancel(true);              // ← 本地线程中断
    }
}
```

---

## 3. `SubagentRegistry` 单机版结构

完全在 JVM 内存里的状态机：

```java
@Component
public class SubagentRegistry {

    public enum Status { RUNNING, COMPLETED, FAILED, STOPPED }

    public static final class SubagentState {
        private final String name;
        private final String role;
        private final String sessionId;
        private volatile Status status = Status.RUNNING;
        private volatile String lastResponse;
        private volatile Future<?> future;          // ← 关键：Future 句柄挂在内存里

        public void attachFuture(Future<?> future) { this.future = future; }
        // ... 其余 getter
    }

    // 一级 Map：userId → 二级 Map
    // 二级 Map：agentName → SubagentState
    private final Map<Long, ConcurrentHashMap<String, SubagentState>> byUser =
            new ConcurrentHashMap<>();

    public SubagentState register(Long userId, String name, String role, String sessionId) {
        ConcurrentHashMap<String, SubagentState> states =
                byUser.computeIfAbsent(userId, k -> new ConcurrentHashMap<>());
        SubagentState fresh = new SubagentState(name, role, sessionId);
        SubagentState prev = states.putIfAbsent(name, fresh);
        if (prev != null && prev.status() == Status.RUNNING) {
            throw new IllegalStateException("Sub-agent '" + name + "' is already running; ...");
        }
        // 终态可覆盖
        if (prev != null) states.put(name, fresh);
        return fresh;
    }

    // get / list / markCompleted / markFailed / markStopped
    // markCompleted/markFailed 对 STOPPED 状态 no-op（终态优先级最高）
}
```

**关键数据结构**：

```
ConcurrentHashMap<Long userId, ConcurrentHashMap<String name, SubagentState>>
                                                              │
                                                              ▼
                                                         SubagentState
                                                         ├─ name
                                                         ├─ role
                                                         ├─ sessionId
                                                         ├─ status (volatile)
                                                         ├─ lastResponse (volatile)
                                                         └─ future (volatile) ← 句柄
```

`future` 字段是单机版的核心，它承载了 `send_message` 的等待（`future.get`）和 `task_stop` 的中断（`future.cancel`）。

---

## 4. 状态机

```
       register()
          │
          ▼
       RUNNING ───────► COMPLETED  (markCompleted: 子代理成功)
          │
          ├────────────► FAILED     (markFailed: 子代理失败 / 异常)
          │
          └────────────► STOPPED    (markStopped: task_stop 调用，future.cancel(true))

  markCompleted / markFailed 对 STOPPED 状态 no-op：
  子代理被 task_stop 中断后，迟到的成功/失败结果被丢弃并记日志
```

---

## 5. 单机版的几个设计亮点

虽然这套实现后来被替换，它本身在单机场景下是**正确且自洽**的，有些设计在新版依然保留：

1. **状态机的终态优先**：`markStopped` 在 `markCompleted`/`markFailed` 之前发生时，后两者 no-op，避免"已停止的子代理被迟到的结果复活"。新版（Redis 版）也保留了这个语义，靠 re-read + 状态判断实现。

2. **同名重 spawn 容忍**：终态的子代理可被新 spawn 覆盖，只有 RUNNING 状态才拒绝重 spawn。新版用 `get().status == RUNNING` 判断实现等价语义。

3. **`AgentScope.SUBAGENT` 拓扑过滤**：子代理执行的引擎调用带这个 scope，让 `OrchestrationMode`（COORDINATOR 默认）剥离子代理的编排三件套，防止递归蔓延。**这个机制新版完全没动**。

4. **会话隔离**：子代理独立 session，与主代理对话历史在 `harness_session` 物理隔离。新版完全保留，只是 sessionId 现在跨实例可访问。

5. **有界线程池 + 拒绝策略**：core=2/max=6/queue=20，超过阈值抛 `RejectedExecutionException` 友好降级为 `ToolResult.error`。这是单机版自带的容量保护。

---

## 6. 单机版的失效场景

以下是这套实现在水平扩展下必然失效的点，也是改造的根本动机：

### 6.1 跨实例找不到子代理

实例 A 派发子代理 `researcher`，状态写入 A 的 `byUser` 内存 Map。下一轮请求路由到实例 B，`SendMessageTool.doExecute` 调 `subagentRegistry.get(userId, "researcher")`：

```java
public Optional<SubagentState> get(Long userId, String name) {
    ConcurrentHashMap<String, SubagentState> states = byUser.get(userId);
    return states == null ? Optional.empty() : Optional.ofNullable(states.get(name));
    //                                                       ↑ B 实例这个 Map 是空的
}
```

直接返回 `"No sub-agent named 'researcher'. Spawn one first with the agent tool."` ——编排对话被迫中断。

### 6.2 `Future` 不可跨进程

即使 B 实例能查到 state，`state.future()` 是 A JVM 内的 `java.util.concurrent.Future` 对象，**不能跨进程序列化传递**。B 上的 `future.cancel(true)` 对 A 上跑的子代理线程无效；B 上的 `future.get(timeout)` 永远拿不到结果。

### 6.3 `subagentExecutor` 容量与节点数线性

max=6 + queue=20 是**单实例的硬上限**，全局容量 = 实例数 × 6。扩展只能改 `maximumPoolSize` 重新发版，无法弹性调度。Redis Stream Consumer Group 那种"任务自动在节点间负载均衡"的能力完全没用上。

### 6.4 实例重启全丢

`byUser` 是 `ConcurrentHashMap`，不持久化。实例 A 重启（滚动发布 / OOM / 宿主机故障）→ 内存里所有子代理状态、正在跑的 Future、未完成的子任务**全部丢失**。`send_message` 永远拿不到结果，`task_stop` 也找不到要停的子代理。

---

## 7. 单机版与分布式版的对照

| 维度 | 单机版（本文档） | 分布式版（现行） |
|------|------------------|------------------|
| 状态层 | JVM `ConcurrentHashMap` | Redis Hash `harness:subagent:{userId}` |
| 句柄 | `java.util.concurrent.Future` | `TaskManager` taskId + Redis cancel flag |
| `agent` 派发 | `subagentExecutor.submit(...)` 进程内线程 | `taskManager.createTask("SUBTASK", ...)` Redis Stream 消费组 |
| `send_message` 等待 | `future.get(waitSeconds, TimeUnit.SECONDS)` | 轮询 `taskManager.getTask(taskId)` 状态（500ms 一次） |
| `task_stop` 取消 | `future.cancel(true)`（本地线程中断） | `taskManager.cancelTask`（Redis cancel flag 跨实例生效） |
| 子代理执行位置 | 当前 JVM 的 `subagentExecutor` 线程 | 任意 worker 实例的 `TaskManager.executor` 线程 |
| 容量上限 | 单实例 6 + queue 20 | 所有 worker 共享 Consumer Group，弹性扩展 |
| 重启行为 | 全丢 | pending 消息被其他实例 reclaim，状态从 Redis 恢复 |
| 跨实例可见性 | ❌ | ✅ |
| 实现复杂度 | 简单（标准 JUC API） | 中等（需要 Redis + 轮询 + 句柄转换） |

---

## 8. 为什么单机版会被替换——根本原因

**单机心智模型 + 阶段性交付 = 隐性单机约束**。

引入编排子系统时（2026-08-16），项目已经做了一轮生产化改造（CostTracker 外置 MySQL、TaskManager 外置 Redis Stream 等），但**那一轮改造时编排骨子还不存在**，所以 review 范围没覆盖到它。后来加编排时，开发者沿用了"单机就够用"的心智模型，用了 `subagentExecutor` + 内存 Map 的简化实现——单机下确实工作得很好。

**问题在于文档里没有标注"此路径不支持分布式部署"**，于是变成了一个静默的隐性约束。等到真的多节点部署时，三个原语跨实例全部失效。

这不是设计水平问题，是**演进式系统的通病**——单机时工作得很好的代码，在文档里通常不被标注为"单机约束"，于是后来加节点时就炸了。类似的例子：

- Spring `@SessionScope` 内存版，加 Redis Session 前单机跑得好好的
- `synchronized` 单机锁，加多实例后临界区失效
- `ThreadLocal` 用户上下文，分布式后下游实例拿不到

识别这种"阶段性盲区"本身就是工程成熟度的体现——所以本文档作为演进过程留档，保留这份单机实现的全貌，便于后人理解为什么需要分布式化、以及改造的对照点在哪里。

---

## 9. 改造的关键决策

把单机版改造成分布式版时，做了一个核心决策：**不引入新中间件**。

项目里 Redis 已经被用作缓存（Session）和分布式任务队列（TaskManager），它本身就具备消息队列的能力（Redis Stream = 持久化 + Consumer Group + at-least-once + cancel flag）。再引入 RabbitMQ/Kafka 等于：

1. 多一个进程要部署运维
2. 多一个故障域
3. 多一套客户端依赖
4. `TaskManager` 整个推倒重写

而 `TaskManager` 这套设计对**子代理编排这种规模**（每秒几十个任务，不是每秒几万）完全够用。所以方案是**复用阶段一已经搭好的 Redis Stream 底座 + Redis Hash 状态层**，把 `AgentTool` 接到 `TaskManager` 上，把 `SubagentRegistry` 内存 Map 换成 Redis Hash。改造路径清晰、改动量小、无新依赖。

详见 [orchestration-patterns.md](./orchestration-patterns.md) §3 分布式派发链路。

---

## 附：单机版文件清单（改造前的样子）

| 文件 | 关键内容 |
|------|---------|
| `coordinator/SubagentRegistry.java` | `ConcurrentHashMap<Long, ConcurrentHashMap<String, SubagentState>>` + `volatile Future<?> future` 字段 |
| `coordinator/AgentCoordinator.java` | 注册 `SUBTASK` handler，但 `spawnSubagent` 方法**无任何调用方**——是个孤岛 |
| `core/tool/AgentTool.java` | 进程内 `subagentExecutor` 线程池，`submit(() -> runAndTrack(...))` + `state.attachFuture(future)` |
| `core/tool/SendMessageTool.java` | `awaitCompletion` 调 `state.future().get(waitSeconds, TimeUnit.SECONDS)` |
| `core/tool/TaskStopTool.java` | 调 `subagentRegistry.markStopped(state)`，内部 `future.cancel(true)` |

改造后的现行版本：见 `docs/orchestration-patterns.md` §3 与 `docs/总结.md` §4.9。
