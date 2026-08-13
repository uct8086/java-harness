# UCT8086-AI 生产部署评估报告

> 面向公司内部大规模部署的差距分析与优化清单。
> 生成日期：2026-08-13

---

## 一、总体结论

当前项目是一个**单机、无认证、无租户隔离**的开发态应用，距可规模化的内部生产服务存在明确差距。核心问题集中在四个层次：

1. **安全红线**：无认证/授权、无沙箱隔离、无租户隔离。
2. **正确性缺陷**：并发/线程安全问题（含一个"流式切换即触发"的权限绕过漏洞）。
3. **性能与可扩展性**：同步阻塞、状态不共享、无法水平扩展。
4. **可观测性与运维**：无监控指标、无健康检查、无容器化部署。

---

## 二、并发与线程安全问题清单（代码级确认）

> 以下问题均已通过阅读源码（含 Spring AI 2.0 官方源码反解）逐一确认，按严重度排序。

### 🔴 问题 1：`HarnessToolCallbackAdapter` 的 ThreadLocal —— 流式切换即触发的权限绕过

**位置**：`src/main/java/uct8086/ai/core/engine/HarnessToolCallbackAdapter.java:30`

**现状**：使用 `static ThreadLocal<ToolExecutionContext> contextHolder`，在 `call(String toolInput)` 里读取 context。当 context 为 null 时，静默降级到 `PermissionMode.AUTO` + `workingDirectory=null` 的默认 context。

**证据链**（基于 Spring AI 2.0 官方源码）：

1. 项目只覆写了**单参** `call(String)`，但 Spring AI `DefaultToolCallingManager` 实际调用的是**两参** `call(String, ToolContext)`，接口默认实现兜底转调单参方法。
2. **非流式路径**（当前 `.call()`）：`ToolCallingAdvisor.adviseCall` 是同步 `do-while` 循环，工具执行在同一 HTTP 线程上，ThreadLocal 碰巧可用。
3. **流式路径**（`.stream()`）：`handleToolCallRecursion` 明确 `toolCallFlux.subscribeOn(Schedulers.boundedElastic())`，注释原文 `tool execution is blocking`——工具执行被切到 Reactor 共享线程池，ThreadLocal 中无 context，触发 AUTO 兜底。

**风险**：切换到流式模式（大规模部署的必经之路）时，`bash` 等危险工具**确定性绕过权限检查**，且丢失工作目录隔离。

**修复方案**（推荐，改动最小）：
- 弃用 `static ThreadLocal`，将 context 存入**每次请求新建的 adapter 实例字段**（`buildToolCallbacks()` 每请求调用一次，adapter 天然是请求级单例）。
- 或利用 Spring AI 2.0 官方 `ToolContext` 通道：覆写两参 `call(String, ToolContext)`，在 `AgentEngine` 里通过 `OpenAiChatOptions.toolContext(...)` 透传 context。

### 🔴 问题 2：`ToolExecutionContext` 构造签名不一致

**位置**：`AgentEngine.java:122`（三参）vs `HarnessToolCallbackAdapter.java:78`（四参）

两处构造方式混用（三参 `state=Map.of()` / 四参），语义虽一致但易埋坑。`state` 字段目前完全未被任何工具使用，属设计冗余。

### 🔴 问题 3：权限模式是全局可变状态

**位置**：`src/main/java/uct8086/ai/core/permission/DefaultPermissionChecker.java:34`

`volatile PermissionMode mode` 单例字段。`AgentEngine.executeInternal` 每次请求 `setMode(...)`，`HarnessController.setPermissionMode` 也改同一全局变量。多用户并发时权限模式互相覆盖。

**修复**：权限模式改为请求级/租户级，通过 context 传递，不存单例字段。

### 🟠 问题 4：黑名单可绕过 + `askUser` 被 auto-approve

**位置**：`DefaultPermissionChecker.java:41` + `DefaultToolExecutionService.java:50`

- `DANGEROUS_COMMANDS` 是字符串 `contains` 匹配，可用 `rm -rf /tmp/../..`、`sh -c`、变量拼接等绕过。
- `DEFAULT` 模式对写操作返回 `askUser`，但 `DefaultToolExecutionService` 里是 `// TODO: Add interactive approval callback` + `log.debug("Auto-approving...")`，**所有写操作实际被放行**，权限系统形同虚设。

**修复**：黑名单改白名单 + 命令解析（AST）；`askUser` 需真正落地审批回调，未实现前写操作默认拒绝。

### 🟠 问题 5：`CostTracker.totalUsage` 非原子累加

**位置**：`CostTracker.java:36,55`

`volatile TokenUsage totalUsage` + `totalUsage = totalUsage.add(...)` 是 read-modify-write，非原子，高并发下总成本丢失更新。

**修复**：用 `AtomicLong` / `LongAdder` 拆字段，或加锁。

### 🟠 问题 6：`SessionManager.createSession()` 竞态 + 全表 count

**位置**：`SessionManager.java:89`

`sessionRepository.count()` 全表扫描 + `createSession("session-" + count)` 并发下重名。

**修复**：改用 UUID 命名或 DB 自增，去掉 count 全扫。

### 🟠 问题 7：文件并发写无锁（三处）

**位置**：`FileMemoryStore.persist()`、`McpConfigManager.persist()`、`SkillRegistry.persistSkill()`

三者都是 `ConcurrentHashMap`（保护内存）+ 无锁 `Files.writeString` / `mapper.writeValue`（不保护文件），并发写会文件交错损坏，且非原子写（应"临时文件 + rename"）。

### 🟠 问题 8：MCP 同步调用 + 每次 new ObjectMapper

**位置**：`McpClientService.java:103`

`callTool` 每次调用都 `new ObjectMapper()`，高频浪费性能；MCP 客户端同步阻塞，工具回调若阻塞会拖住整个 Agent Loop。

### 🟡 问题 9：`TaskManager` 无界线程池

**位置**：`TaskManager.java:29`

`Executors.newCachedThreadPool()` 线程数无上限，任务量大 OOM。改有界线程池 + 队列 + 拒绝策略。

### 🟡 问题 10：`BashTool` 输出无上限

**位置**：`BashTool.java:58`

`ArrayList<String>` 无限收集输出，大输出命令撑爆内存。需限制输出字节数并截断。

### 🟡 问题 11：`HarnessProperties.workingDirectory` 静态初始化

**位置**：`HarnessProperties.java:47`

`System.getProperty("user.dir")` 字段初始化，容器化部署下工作目录不确定。建议改 null + 运行时解析。

### 🟡 问题 12：DEBUG 日志泄漏敏感信息

**位置**：`application.yml` `logging.level.uct8086.ai: DEBUG`

生产环境 DEBUG 会打印用户 prompt 前 100 字符、工具调用、命令输出。改 INFO。

### 🟡 问题 13：无 Actuator / 健康检查

**位置**：`pom.xml`

无 `spring-boot-starter-actuator`，K8s 无法探活/就绪检查、无指标采集。

---

## 三、安全红线（P0）

| 项 | 现状 | 必做 |
|----|------|------|
| 认证/授权 | 无 Spring Security，所有 `/api/**` 裸奔 | 引入 Security + OAuth2/OIDC，对接公司 SSO，RBAC |
| Bash 沙箱 | 直通 `ProcessBuilder`，黑名单字符串匹配 | 沙箱容器隔离（gVisor/Firecracker/K8s Pod），白名单 + AST 解析 |
| 租户隔离 | 全部全局单例，无 userId 维度 | 全链路加 userId/tenantId，权限模式租户化 |
| 成本滥用 | `session-cost-hard-limit: 0.0`（关闭） | 按租户/用户配额 + 超限熔断 |

---

## 四、性能与可扩展性

1. **同步阻塞**：`.call()` 长占 HTTP 线程，大并发耗尽线程池 → 改异步/流式（SSE）+ 独立线程池 + 限流背压。
2. **状态不共享**：`CostTracker`（内存）、`FileMemoryStore`（文件）、`TaskManager`（内存）无法水平扩展 → 外置到 Redis/DB。
3. **缓存粗糙**：会话列表全量缓存 + `invalidateListCache` 无 try/catch → 分页/按用户维度 + 容错降级。
4. **数据库**：`LONGTEXT` 存消息、`message_count` 手动 +1 竞态、Hikari 池默认 20、pgvector `topK=3` 每次全量 embedding → 优化。

---

## 五、可观测性与运维

1. 无 Micrometer/Prometheus 指标、无 OpenTelemetry 追踪、无 `/actuator/health`。
2. 敏感信息明文（MySQL 密码、pgvector 密码）→ Secret 管理（Vault/K8s Secret）。
3. 无 profile 区分（dev/prod）、无应用 Dockerfile、无 K8s 清单。

---

## 六、落地优先级 Roadmap

| 阶段 | 内容 | 优先级 |
|------|------|--------|
| P0 安全 | 认证/授权、沙箱隔离、租户隔离 | 立即 |
| P1 正确性 | ThreadLocal 修复、权限模式租户化、Flyway 迁移、并发累加/竞态修复 | 上线前 |
| P2 可扩展 | 异步/流式、状态外置、水平扩展 | 规模化前 |
| P3 可观测 | Actuator + Prometheus + 追踪 + 健康检查 | 随 P2 |
| P4 运维/合规 | Docker/K8s、Secret 管理、审计、成本配额 | 持续 |

---

## 七、附：问题清单速查表

| # | 问题 | 严重度 | 类别 | 位置 |
|---|------|--------|------|------|
| 1 | ThreadLocal 流式切换即触发权限绕过 | 🔴 严重 | 安全 | `HarnessToolCallbackAdapter` |
| 2 | context 构造签名不一致 | 🔴 严重 | 正确性 | `AgentEngine` vs Adapter |
| 3 | 权限模式全局可变 | 🔴 严重 | 正确性 | `DefaultPermissionChecker` |
| 4 | 黑名单可绕过 + askUser auto-approve | 🟠 中等 | 安全 | `DefaultPermissionChecker`/`DefaultToolExecutionService` |
| 5 | totalUsage 非原子累加 | 🟠 中等 | 正确性 | `CostTracker` |
| 6 | createSession 竞态 + count 全扫 | 🟠 中等 | 正确性 | `SessionManager` |
| 7 | 三处文件并发写无锁 | 🟠 中等 | 正确性 | Memory/MCP/Skill |
| 8 | MCP 同步调用 + 每次 new ObjectMapper | 🟠 中等 | 性能 | `McpClientService` |
| 9 | 无界线程池 | 🟡 轻微 | 性能 | `TaskManager` |
| 10 | Bash 输出无上限 | 🟡 轻微 | 性能 | `BashTool` |
| 11 | workingDirectory 静态初始化 | 🟡 轻微 | 设计 | `HarnessProperties` |
| 12 | DEBUG 日志泄漏敏感信息 | 🟡 轻微 | 安全 | `application.yml` |
| 13 | 无 Actuator/健康检查 | 🟡 轻微 | 可观测 | `pom.xml` |

---

## 八、修复记录（已完成，2026-08-13）

以下 9 项已修复并通过 `mvn -o compile` 编译验证。

| # | 问题 | 修复方式 |
|---|------|---------|
| 1 | ThreadLocal 权限绕过 | 弃用 `static ThreadLocal`，改为构造注入实例字段 `context`；`AgentEngine` 删 `setContext`/`clearContext`/`finally`，改为 `buildToolCallbacks(context)` |
| 5 | `CostTracker` 非原子累加 | `volatile TokenUsage totalUsage` → `LongAdder totalInputTokens` + `LongAdder totalOutputTokens` + `DoubleAdder totalCostAdder` |
| 6 | `createSession` 竞态 + count 全扫 | `session-<count>` → `session-<UUID 前 8 位>` |
| 7 | 三处文件并发写无锁 | `FileMemoryStore`/`McpConfigManager`/`SkillRegistry` 的 persist 加 `synchronized` + 原子写（`.tmp` 临时文件 + `ATOMIC_MOVE`，回退 `REPLACE_EXISTING`） |
| 8 | MCP 每次 `new ObjectMapper` | 改为共享 `static final OBJECT_MAPPER` |
| 9 | `TaskManager` 无界线程池 | `newCachedThreadPool()` → 有界 `ThreadPoolExecutor(4, 16, 60s, LinkedBlockingQueue(1000))` + daemon 线程 + 拒绝策略记日志 |
| 10 | `BashTool` 输出无上限 | 限制 1MB，超限截断并追加 `...[output truncated at 1048576 bytes]` |
| 11 | `workingDirectory` 静态初始化 | `System.getProperty("user.dir")` 字段初始化 → null + getter 懒解析 |
| 12 | DEBUG 日志泄漏 | `application.yml` 的 `uct8086.ai: DEBUG` → `INFO` |

### 关键组件说明

**`BashTool`（是什么）**：Agent 的「执行 Shell 命令」工具。Agent 在对话中需要运行脚本、构建、测试等时，通过该工具把 `command` 交给 `ProcessBuilder` 起系统进程执行（Windows `cmd.exe /c`、Linux `bash -c`），并把标准输出读回模型。它**非只读、有副作用、能执行任意系统命令**，是项目权限系统（`DefaultPermissionChecker`）主要拦截的对象。对其做的 1MB 输出截断是防御性加固，不影响正常功能。

**`CostTracker`（改动意义）**：成本统计器——把每次模型调用的 token 换算成人民币，累计「会话成本」和「全局总成本」，并驱动超预算告警（`checkBudget` 依赖 `totalCost` 判断阈值）。

- **原问题**：`volatile TokenUsage totalUsage` + `totalUsage = totalUsage.add(...)` 是**非原子「读-改-写」**。`volatile` 只保证可见性、不保证原子性，高并发下多线程同时记账会「丢失更新」，总成本越算越少。
- **修复后**：改用 `LongAdder`/`DoubleAdder`，`add()` 原子累加，并发下计数精确。
- **价值**：让「全局成本统计」在多人并发使用时**算得准**，否则 `totalCostWarnThreshold`（总成本预警）形同虚设，可能不知不觉多烧 API 费用。

**`HarnessToolCallbackAdapter`（改动意义）**：这是本次修复中**最关键、最危险的一项**（问题 1）。原实现用 `static ThreadLocal<ToolExecutionContext>` 传上下文，`AgentEngine` 在请求线程 `setContext`、工具回调里 `getContext`。

- **原问题**：ThreadLocal 依赖「set 和 get 在同一线程」。Spring AI 2.0 源码佐证——**非流式** `.call()` 路径工具执行同线程、碰巧可用；但**流式** `.stream()` 路径会把工具执行切到 `Schedulers.boundedElastic()` 线程池（源码注释 `tool execution is blocking`），此时 `contextHolder.get()` 返回 null，触发 `PermissionMode.AUTO` + `workingDirectory=null` 兜底 → **危险命令绕过权限检查**。
- **修复后**：弃用 ThreadLocal，把 `context` 改为**构造注入的实例字段**。因 `buildToolCallbacks()` 每次请求都 new 一批 adapter（每个实例只服务一个请求），context 跟着实例走、与执行线程无关，彻底消除跨线程失效。
- **价值**：为将来切换流式模式扫清一个会引发安全漏洞的定时炸弹。

**`FileMemoryStore`（改动意义）**：记忆功能的文件存储实现，把记忆写到一个 `MEMORY.md`（默认 `<工作目录>/.uct8086/MEMORY.md`）。它是「内存 `ConcurrentHashMap`（快速查询）+ 文件（持久化）」双份存储，`persist()` 在每次增删改记忆时被调用。

- **原问题**：`persist()` 直接 `Files.writeString` 覆盖写，无锁、非原子。多线程同时保存记忆会写交错/损坏，写一半进程崩溃会读到半截内容。
- **修复后**：加 `synchronized`（串行化写）+ 原子写（临时文件 `.tmp` + `ATOMIC_MOVE`，文件系统不支持时回退 `REPLACE_EXISTING`）。
- **架构提醒**：项目的「记忆」用本地文件（`FileMemoryStore`），而「会话/消息」已用 MySQL（`SessionManager` + `SessionRepository`）。文件式记忆在大规模部署时无法水平扩展，是需迁移到 Redis/DB 的点。

**`McpClientService`（改动意义）**：`callTool` 原本每次调用都 `new ObjectMapper()`。

- **原问题**：`ObjectMapper` 是重量级、但线程安全的对象，每次 new 都重复做模块注册/序列化器初始化，纯浪费且增加 GC 压力。
- **修复后**：提取为 `static final OBJECT_MAPPER` 共享实例。属纯性能优化，不改变行为。
- **未处理**：`callTool` 仍是**同步阻塞**调用 MCP 工具，MCP 响应慢会拖住整个 Agent Loop，属更大规模的并发优化，暂未动。

**`SkillRegistry`（改动意义）**：技能注册时 `persistSkill()` 写文件（每个技能一个 `.md` 文件）。

- **原问题**：无锁写文件，与 `FileMemoryStore`/`McpConfigManager` 同属「内存 ConcurrentHashMap + 无锁写文件」模式，存在并发写交错风险。
- **修复后**：加 `synchronized` 串行化。因每个技能写独立文件（非多条目写同一文件），故未做原子 rename，避免过度设计。

**`TaskManager`（改动意义）**：后台任务线程池。

- **原问题**：`Executors.newCachedThreadPool()` 线程数无上限（最大 `Integer.MAX_VALUE`），任务激增时线程爆炸 → 内存耗尽 OOM。
- **修复后**：换成有界 `ThreadPoolExecutor(4, 16, 60s, LinkedBlockingQueue(1000))` + daemon 线程 + 拒绝策略记日志。
- **注意**：当前拒绝策略是「丢弃任务 + 记日志」，队列满时新任务会被直接丢弃（`submit` 抛 `RejectedExecutionException`）。如需「调用方感知重试」或「阻塞等待」，可改用 `CallerRunsPolicy`。

**`HarnessProperties`（改动意义）**：`workingDirectory` 字段。

- **原问题**：字段初始化器 `= System.getProperty("user.dir")` 在 Bean 构造时执行，值被「冻结」在实例化时刻。容器化部署（Docker/K8s）下进程启动工作目录常为 `/` 或不确定目录，导致 Agent 文件操作、`.uct8086/MEMORY.md`、`.uct8086/skills` 写到错误位置。
- **修复后**：字段改 null，getter 懒解析——yml 配了用配置值，没配才运行时取 `user.dir`，保证读到的始终是「当前」工作目录。

**`SessionManager`（改动意义）**：`createSession()` 命名。

- **原问题**：`sessionRepository.count()`（全表扫描）+ `createSession("session-" + count)`，并发下重名、且有性能隐患。
- **修复后**：改为 `session-<UUID 前 8 位>`，去掉 count 全扫、消除竞态。

---

## 九、遗留项（未处理，需单独排期）

- **问题 2 / 3 / 4**（安全红线 P0）：context 构造签名不一致、权限模式全局 `volatile`、黑名单绕过 + `askUser` 被 auto-approve（当前写操作实际全放行）。涉及 `PermissionChecker`、`DefaultToolExecutionService` 架构级改动。
- 认证/授权、沙箱隔离、租户隔离、异步流式、状态外置、Actuator 监控等（详见文档第三至五节）。
