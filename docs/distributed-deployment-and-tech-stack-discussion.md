# 分布式部署与技术选型讨论纪要

> 关于大规模分布式部署、消息中间件选型、以及 Java 技术路线的讨论整理。
> 生成日期：2026-08-28

---

## 一、总体结论

项目**没有过时，技术路线正确**，可以放心继续投入。核心判断如下：

1. **技术栈是最新的**：Java 21 + Spring Boot 4.0 + Spring AI 2.0，均为当前最新大版本。
2. **架构思想与主流框架对齐**：「状态外置 + 无状态节点 + Redis Stream 分布式派发」这一套，与 AgentScope 的「会话状态外置 Redis」、LangGraph 的「Postgres Checkpointer」是同一个设计哲学。
3. **Java 实现 Agent 框架不会过时**，对「企业级、多用户、分布式」目标而言，Java 是比 Python 更合适的选择。

---

## 二、Redis 够用吗？还是需要消息中间件？

### 结论

**当前规模 Redis 够用，不需要再引入独立消息中间件。**

关键点在于：本项目**已经在用 Redis Stream 承担消息中间件的职责**（`TaskManager`），并非「只用 Redis 做缓存」，而是已经用 Redis 扮演了轻量 MQ 的角色。

### 项目当前的分布式设计（已较完整）

| 能力 | 实现方式 | 是否跨实例 |
|------|---------|-----------|
| 会话缓存 | Redis ZSET + meta + messages | ✅ 按 userId 隔离 |
| 任务派发 | Redis Stream + Consumer Group（`harness:task:stream`） | ✅ 多实例竞争消费 |
| 任务状态 | Redis Hash `harness:task:{userId}` | ✅ 跨实例可见 |
| 取消/熔断标记 | Redis KV flag | ✅ 跨实例可见 |
| 成本统计 | MySQL SUM 聚合（真相源） | ✅ |
| 会话/消息持久化 | MySQL | ✅ |

`TaskManager` 实现质量较好：`at-least-once` 语义（handler 跑完才 ACK，崩溃后 message 留在 pending 可被其他实例 reclaim）、consumer group、cancel flag 跨实例协同，均已做对。

### 什么时候才「真的不够用」，需要换消息中间件（Kafka/RabbitMQ/RocketMQ）

只有碰到以下硬需求时，Redis Stream 才成为瓶颈：

1. **消息不能丢（exactly-once / 强持久化）**：Redis 默认 AOF `everysec`，极端宕机丢最近 1 秒数据。任务派发场景若可接受重试/重提，则无问题。
2. **积压量巨大**：Redis Stream 数据全在内存，百万级长时间积压会吃爆内存。
3. **复杂路由/顺序/广播语义**：单 key 单 group 的简单竞争消费够用，但若需要「同用户任务顺序执行」「按 shard 分区」「延迟队列/死信队列」，MQ 成熟度更高。
4. **消费者组管理复杂度**：当前 poller 是手写 `block(2s)` 轮询，未优雅处理「consumer 挂掉后 pending message 的自动 rebalance / 死信」。
5. **多系统解耦**：若未来任务需被外部系统（账单/审计）订阅，MQ 协议和生态更好。

### 建议

- **当前阶段不引入 MQ**，Redis 已够用，且 Redis Stream 已用对。
- 真正优先要处理的是（比 MQ 更优先）：
  1. **Session 内存态问题**：Agent Loop 是同步阻塞 + SSE 流，多实例部署需确认同一会话不会在多个实例被同时处理（`SessionManager` 第 364-366 行注释明确假设了「单实例串行写」）。建议加**分布式锁或粘性会话**。
  2. **Redis 高可用**：上 Redis Sentinel 或 Cluster，别用单机（熔断标记、任务状态、会话缓存都依赖它）。
  3. **文件工具执行位置**：`bash`/`write_file` 操作的是 `working-directory`（默认 `${user.dir}`），多实例下每个实例本地文件系统不同。需共享文件系统（NFS/对象存储）或固定执行节点。
- **扩容到高吞吐、强可靠、多租户商用阶段**时，再评估把 Redis Stream 换成 RocketMQ（国内生态好）或 Kafka。

---

## 三、框架选型讨论（AgentScope / OpenHands / LangChain）

### 共同点

这些框架**都不强制要求 MQ**，分布式能力主要靠「状态外置 + 无状态节点 + 水平扩容」实现，MQ 是「需要时再补」的可选项。

### 分框架对比

#### 1. AgentScope（阿里，最值得关注）

- 专为「企业级分布式多租户」设计，分布式能力**不靠 MQ**，而靠「会话状态外置到 Redis/对象存储 + 容器快照 + 节点无感切换」。
- **Redis 是其核心分布式会话后端**：对话历史、任务进度、待办列表、上下文摘要都外置到 Redis。
- **多租户三层强制隔离**：`RuntimeContext(userId+sessionId)` 贯穿 → 存储命名空间隔离 + 工作区路径隔离 + 沙箱环境隔离（框架底层强制）。
- **文件系统抽象 `AbstractFilesystem`**：本地盘 / 容器沙箱 / 远端存储（OSS/MySQL/Redis）可切换——正好解决「文件工具跨实例读写」问题。
- 对 AgentScope 而言，MQ 几乎不是必须，9000 人更需要的是 Redis Cluster + OSS。

#### 2. OpenHands

- 核心是「无状态 SDK + 沙箱(sandbox)」，状态全在事件流和持久化设置里。
- 多用户扩展靠「事件流 + 沙箱池」，不是 MQ。
- 9000 人规模下，OpenHands 的瓶颈通常在**沙箱编排和调度**（每用户一个沙箱容器，9000 并发沙箱是巨大资源压力），而非消息队列。

#### 3. LangChain / LangGraph

生产架构明确：

```
用户 → Nginx → Agent Server(多实例无状态)
                ↓ Redis(缓存)
                ↓ Postgres(Checkpointer 状态持久化) ← 多用户并发的关键
```

- **多用户并发靠 Postgres Checkpointer**（`thread_id` 隔离 + 连接池），不是 MQ。
- 默认不需要 MQ，水平扩展靠「无状态 Agent Server + Nginx 负载均衡 + 状态进 Postgres」。
- **MQ/队列引入点**：仅当要做「长任务后台执行（Background Runs）、削峰填谷、异步任务」时才需要上队列。

### 9000 人规模需要 MQ 吗？

**结论：MQ 不是「因为 9000 人」就必须有，而是「因为任务形态」才需要。**

#### 什么时候 9000 人也不需要 MQ
- 交互式、请求-响应的 agent（用户问一句答一句），状态外置到 Redis/PG 即可，靠无状态节点水平扩容。
- 典型：客服助手、内部问答、代码助手。

#### 什么时候 9000 人就需要 MQ 或队列
1. **任务异步化/削峰**：长任务（跑 10 分钟，跑完通知）不能挂 HTTP 请求，必须进队列让 worker 慢慢跑。
2. **负载均衡 + 背压**：瞬时并发可观时需队列缓冲。
3. **可靠投递**：任务不能丢（重试、死信）。
4. **顺序/聚合**：同用户任务顺序执行、多 agent 结果聚合。

### 落地建议（针对 9000 人）

1. **框架选型上，AgentScope Java 2.0 与本项目（Java 21 + Spring AI）方向最契合**，其分布式+多租户开箱即用。项目现有的 `TaskManager`（Redis Stream）思想与它同路线。
2. **Redis 必须升级为高可用**：用 Redis Cluster（或至少 Sentinel）。AgentScope 官方兼容 Standalone/Sentinel/Cluster。
3. **MQ 决策**：先用 Redis Stream 扛住，**当且仅当出现「长任务异步化 + 可靠重试/死信 + 削峰」明确需求时，再上 RocketMQ**。
4. **真正要提前设计的是**（比 MQ 优先级高）：
   - 沙箱/执行环境调度池（OpenHands 教训：9000 并发沙箱是最大瓶颈）
   - 文件/工作区共享（对象存储 OSS）
   - 状态存储容量和连接池（PG/Redis）
   - 成本熔断 + 限流（项目已有雏形）

---

## 四、Postgres Checkpointer 是什么（LangGraph 概念）

**一句话解释**：LangGraph 用来把「图（Agent 工作流）每一步执行到哪了」这个状态，持久化存到 PostgreSQL 的组件。即 Agent 工作流的「存档点 / 断点续传」机制。

### 为什么需要它

LangGraph 的 Agent 是有状态的图（Graph），由多个节点组成，执行过程中经过很多节点。Checkpointer 解决：

1. 程序中途崩溃了，怎么知道执行到哪一步（避免重头跑，之前的 LLM 调用和工具执行白费）。
2. 用户中途打断，怎么接着聊。
3. 图里有循环，怎么记录每轮中间状态。

### 为什么叫「Checkpointer」而不叫「数据库」

因为它是**可插拔接口**，存储后端可换：

| 后端 | 适用场景 |
|------|---------|
| `MemorySaver` | 开发调试，存内存，重启即丢 |
| `SqliteSaver` | 本地单机小项目 |
| **`PostgresSaver`（Postgres Checkpointer）** | **生产环境，多用户并发** |

### 与多用户/水平扩展的关系

- 每个用户/会话状态靠 `thread_id` 隔离。
- 状态都存进 Postgres（而非实例内存），所以任何无状态 Agent Server 实例都能接手任意用户会话——这正是水平扩容的基础。
- 生产环境「必须用 Postgres 而非 SQLite/内存」的原因：多实例部署时，状态存内存/单机文件会导致「用户这次打到实例 1、下次打到实例 2，状态对不上」。存到共享 Postgres 才能支撑多用户并发 + 水平扩展。

### 类比到本项目

| LangGraph | 本项目 java-harness |
|-----------|---------------------|
| `PostgresSaver`（Checkpointer） | MySQL 持久化（`harness_session` / `harness_message` 表） |
| `thread_id` 会话隔离 | `userId` + `sessionId` 隔离 |
| 存共享 DB 实现多实例无状态 | MySQL + Redis 缓存做「状态外置」 |

**本质是同一件事**：把会话/工作流状态从「进程内存」外置到「共享数据库」，让服务无状态、可水平扩展、崩溃可恢复。LangGraph 叫 Checkpointer，本项目叫 MySQL 会话持久化，AgentScope 叫「会话状态外置到 Redis」——三家设计思想相通。

---

## 五、Java 实现 Agent 框架会过时吗？

**结论：Java 实现 Agent 框架不会过时，对「企业级、多用户、分布式」目标而言，Java 是比 Python 更合适的选择。**

### 1. Java 生态已在 Agent 领域全面发力

「Agent 框架都是 Python」是 2023-2024 年的印象，现在（2026）Java 已有成熟一线选择：

- **Spring AI**（本项目正在用，2.0 已发布）——Spring 官方下场。
- **AgentScope Java 2.0**——阿里通义实验室，专为企业级分布式多租户设计。
- **LangChain4j**——LangChain 官方 Java 版。

### 2. Python 看起来多，但企业生产环境倾向 Java

| 维度 | Python | Java |
|------|--------|------|
| 生态起步 | 早（LangChain/PyTorch 先发） | 晚，但已成熟 |
| 原型开发速度 | 快 | 稍慢（但框架已搭好） |
| **类型安全** | 弱，大项目易出错 | 强，编译期拦截错误 |
| **并发/性能** | GIL 限制，多进程绕 | 原生多线程/虚拟线程，强 |
| **企业工程化** | 一般 | 强（Maven、强类型、成熟工具链） |
| **团队人才** | 算法/研究多 | 后端工程多，易招易维护 |
| **微服务/分布式** | 一般 | 强（Spring 全家桶） |

关键点：**Agent 本质是「后端工程问题」，不是「算法研究问题」**。要做的是把 LLM 调用、工具执行、会话、权限、成本、多租户这些工程化能力做扎实——这正是 Java 的强项。

### 3. 本项目的技术选型印证了这一点

README 里的能力（Spring Security 认证、MyBatis-Plus、Redis Stream、pgvector、Actuator/Prometheus 指标）**全是 Java/Spring 生态最成熟的东西**。

### 4. 唯一需注意的点

- Java 在「最新前沿模型/研究的接入速度」上偶尔比 Python 慢半拍（新模型 SDK 先有 Python 版），但不影响企业生产（稳定商业 API 的 Java SDK 早已具备）。
- 若未来要频繁做「模型微调、RL 训练、向量实验」等研究型工作，Python 更顺手；但做「Agent 服务给 9000 人用」，Java 更稳。

**一句话：Python 赢在「多、快、研究生态」，Java 赢在「稳、工程化、企业生产」，本项目恰好属于后者。**

---

## 六、流程编排与 Embabel

### 纠正一个误解

本项目**并非「没有流程编排」**，而是用了「LLM 驱动的隐式编排」（让大模型自己决定每一步调用哪个工具、怎么循环），区别于 LangGraph 那种「代码/图驱动的显式编排」（人用代码画死节点、边、条件）。

```
LLM 自治（本项目路线，ReAct）：
  模型自己决定："现在调 bash" → "结果不好，再读文件" → "完成"
  优点：灵活，能处理未预料任务
  缺点：不可控、不可审计、成本难预测

确定性编排（LangGraph / Embabel）：
  人写死："先查库 → 判断分支 → 调工具 → 汇总"
  优点：可预测、可审计、成本可控
  缺点：只能处理预设流程
```

所以本项目是「选择了 LLM 自治这条路」，是合理选择，只是与部分框架路线不同。

### Embabel 是什么

- **Spring 之父 Rod Johnson 的新框架**，JVM 原生（Kotlin 编写，运行于 JVM），Apache 2.0 协议。
- 定位是「Spring AI 之上的高层编排层」，与本项目用的 Spring AI 是**互补关系，不是替代关系**。
- 核心思想是 **GOAP（目标导向行动规划）**：把「规划」从 LLM 手里抢回来交给确定性代码，LLM 只负责「不确定的部分」（如生成文本），「接下来做什么」由代码根据目标、动作、条件确定性算出。
- 它解决的正痛点：纯 LLM 自治在企业生产中的「不可审计、成本不可预测、行为不可控」，9000 人规模下会被放大。

### 判断

1. **本项目没过时**，只是站在「LLM 自治」这一端；Embabel 的出现说明业界正往「确定性编排」一端补，两路线正在收敛。
2. **不需要现在去学/用 Embabel**（Kotlin 编写、刚起步，生态未成熟到承载 9000 人生产）。
3. **值得吸收一个思想**：未来做「成本可控、可审计」的固定业务流程时，可加一层「确定性编排」，与现有 LLM 自治并存——复杂开放任务走自治，固定流程走编排（成熟产品的常见做法）。
4. **侧面印证选 Java 是对的**：Spring 之父都回来做 JVM Agent 框架，Java 在 Agent 领域不但不过时，还在被顶级人物加码。

---

## 七、Subagent 任务逻辑现状分析

### 结论

**当前 Subagent 编排走的是「本地线程」，且是「纯 JVM 内存态」，不是分布式。**

### 两条 Subagent 执行路径（关键区别）

#### 路径一：`agent` 工具（LLM 驱动编排）→ 本地线程池 ✅ 当前默认路径

- `wait=true`（默认）：**直接在发起请求的线程里同步跑**（`runAndTrack` 直接调 `engine.execute`）。
- `wait=false`：丢进**本地 `ThreadPoolExecutor`**（核心 2 线程、最大 6、队列 20）后台跑，返回 `Future` 句柄。
- 两个都是**单 JVM 进程内线程**，不是分布式。

#### 路径二：`AgentCoordinator.spawnSubagent` → Redis Stream 分布式 ✅ 真正跨实例

走 `TaskManager` 的 Redis Stream 队列，是真正跨实例的。

> 注意：`agent`/`send_message`/`task_stop` 三原语走的是路径一（内存线程），`AgentCoordinator` 走的是路径二（Redis Stream）。这是**两套独立的东西**，目前三原语这套编排还没接到分布式上。

### 关键问题：状态存哪了？

`SubagentRegistry`（编排的状态中枢）是**纯内存的 `ConcurrentHashMap`**：

```java
private final Map<Long, ConcurrentHashMap<String, SubagentState>> byUser = new ConcurrentHashMap<>();
```

`SubagentState` 甚至直接持有 `Future<?>`（JVM 线程句柄），**无法跨进程序列化**。

### 多实例水平部署的后果

一旦多实例部署，路径一（`agent`/`send_message`/`task_stop` 三原语）会出问题：

1. **`SubagentRegistry` 各实例独立内存 Map**：主 Agent 在实例 A 派了后台子 Agent，后续 `send_message` 请求被负载均衡打到实例 B，实例 B 查不到该 subagent，报「No sub-agent named xxx」。
2. **`Future` 句柄跨实例无效**：实例 A 的 `Future.cancel(true)` 只能中断实例 A 自己的线程，管不到实例 B 上正在跑的子 Agent。
3. **内存态重启即丢**：实例重启，所有 RUNNING 的 subagent 状态全丢。
4. **执行位置不确定**：`engine.execute` 落到某实例本地线程池，文件工具操作的是该实例本地文件系统。

### 改造方向

若要做 9000 人分布式，需将 `SubagentRegistry` 状态**从内存 `ConcurrentHashMap` 外置到 Redis**：

- 把 `SubagentState` 里那个 `Future` 句柄换成可跨实例的取消标记（`TaskManager` 里的 `CANCEL_FLAG` 已示范正确做法）。
- 对齐已有的 `TaskManager`（Redis Stream + Redis Hash 状态 + 跨实例 cancel flag）思路。

---

## 八、K8s 与公司公共部署平台

### 结论

**分布式部署 + 高可用，主流解法就是 K8s。但「是否自己上手 K8s」取决于公司部署形态——本项目走的是公司内部 PaaS 平台，底层是 K8s，但业务开发者不直接写 K8s YAML。**

### 先分清两个概念

| 工具 | 定位 | 是否高可用 |
|------|------|-----------|
| Docker Compose | 单机编排（一台机器拉多个容器） | ❌ 只能本机重启（`restart`），做不到「整机挂了换机拉起」 |
| K8s | 集群编排 + 自愈 + 自动扩缩容 | ✅ 多节点调度、Pod 自动重建、滚动发布 |

本项目的 `docker-compose.yml` 是「本地开发环境」（文件注释即写明），不是高可用部署方案。

### 为什么「高可用 + 分布式」≈ K8s

本项目「状态外置 + 无状态节点 + 水平扩容」的设计哲学，天然对应 K8s 原语：

| 文档点出的问题 | K8s 对应能力 |
|---|---|
| 无状态 Agent Server 多实例水平扩容 | Deployment + ReplicaSet |
| 实例/机器挂了自动拉起 | 自愈 + 节点故障重调度 |
| 同一会话不被多实例同时处理 | Service `sessionAffinity` / Redis 分布式锁 |
| 流量入口负载均衡 | Service / Ingress（替代手写 Nginx） |
| 文件工具跨实例读写 | PVC / 共享存储（NFS、OSS CSI） |
| Redis/MySQL 本身高可用 | Redis Sentinel/Cluster、PG 主从，或云托管 |
| 9000 并发沙箱是最大瓶颈 | HPA 按指标自动扩 worker |

### 边界：K8s 不是银弹

1. **K8s 管「应用层 + 编排层」，不管「有状态中间件」的高可用。** Redis/MySQL 塞进 K8s 做高可用最麻烦，务实做法是：无状态应用上 K8s，有状态中间件用云托管服务（阿里云 Redis 集群版、RDS）。
2. **看规模。** 小规模（3~5 台）可用 Docker Swarm；本项目是 9000 人规模，直接 K8s（自建或云托管 ACK/EKS/GKE/TKE）。
3. **上 K8s 前，代码侧的状态问题必须先补**（K8s 只是「运输工具」，货得先打包好）：
   - `SubagentRegistry` 内存态 `ConcurrentHashMap` + `Future` 句柄 → 外置 Redis；
   - Session 同一会话并发写 → 分布式锁 / 粘性会话；
   - 文件工具执行位置 → 共享存储；
   - Redis/PG 本身高可用。

### 演进路线

```
当前：docker-compose 单机开发
   │
   ├─ 第一步（不改基础设施）：补应用层状态外置
   │    SubagentRegistry → Redis；Session 加分布式锁/粘性；工作目录 → 共享卷
   │
   ├─ 第二步：中间件高可用
   │    Redis → Sentinel/Cluster（或云托管）；MySQL/PG → 主从/云托管
   │
   └─ 第三步：上 K8s 编排无状态应用
        Deployment(多副本) + Service(sessionAffinity) + Ingress
        + HPA + PVC/共享存储 + 云托管中间件
```

### 公司公共平台的真实形态

公司「容器化 + K8s」的公共平台，本质是 K8s 被平台封装成了简单表单：

```
提交代码 → 流水线(CI/CD) 构建镜像 + 测试
   → 推送镜像仓库(Harbor)
   → 平台按资源申请在 K8s 集群调度/拉起 Pod
   → 服务对外暴露 Service/Ingress
```

**「申请 2 台服务器」的真实含义**：大概率是 2 个副本（Replica）被反亲和调度到不同 Node 上，实现高可用，而非手动装 Docker 的两台物理机。

#### 开发者面向平台的关注点（不需要直接碰 K8s）

1. 资源申请：实例/副本数、每实例 CPU/内存
2. 流水线配置：分支触发、构建命令、镜像名
3. 配置/密钥：环境变量、ConfigMap、Secret
4. 服务暴露：域名、负载均衡、健康检查探针
5. 日志/监控：平台统一采集

#### 对「第三步」的修正

接入公司平台后，业务开发者**只需做**：

- ✅ 写 Dockerfile（把 jar 打进镜像）——本项目当前**尚未有 Dockerfile**，这是接入平台的第一步；
- ✅ 按平台规范配流水线 + 申请资源（副本数、CPU/内存）。

**不需要做**：

- ❌ 手写 Deployment/Service/HPA YAML（平台代劳）；
- ❌ 自建/维护 K8s 集群（平台团队负责）。

**仍然落在业务头上、平台帮不了的**（这才是上 K8s 前必须改完的）：

- `SubagentRegistry` 内存态 → 外置 Redis（否则 2 副本状态不一致）；
- Session 同一会话并发写 → 分布式锁/粘性会话；
- 文件工具执行位置 → 共享存储（否则请求打到不同 Pod，写文件落在不同容器本地盘）。

#### 需向平台确认的歧义

「申请 2 台服务器」有两种含义，需看平台申请页面区分：

| 说法 | 含义 | 是否高可用 |
|------|------|-----------|
| 填「实例数/副本数 = 2」 | 2 个 Pod + 前面负载均衡自动切换 | ✅ 真正高可用 |
| 填「机器数/节点数 = 2」 | 资源池 2 台，但副本数可能仍是 1 | ❌ 只是机器冗余，非高可用 |

---

## 附：讨论时间线摘要

| 议题 | 核心结论 |
|------|---------|
| Redis 是否够用 / 需不需要 MQ | 当前 Redis 够用，Redis Stream 已承担 MQ 职责；出现「长任务异步化 + 可靠重试 + 削峰」需求时再上 RocketMQ |
| 9000 人用 AgentScope/OpenHands/LangChain 需不需要 MQ | 框架本身都不强制 MQ；MQ 是「任务形态」触发的可选项，不是「人数」触发的必需件 |
| Postgres Checkpointer 是什么 | LangGraph 的「工作流状态持久化」组件，本质与本项目 MySQL 会话持久化同构 |
| Java 实现会不会过时 | 不会；对「企业级、多用户、分布式」目标，Java 比 Python 更合适 |
| 流程编排 / Embabel | 本项目是「LLM 自治」路线，Embabel 是「确定性编排」路线，两者互补不冲突 |
| Subagent 逻辑是本地线程吗 | 是；三原语走本地线程池 + 内存 `SubagentRegistry`，需外置 Redis 才能分布式 |
| 分布式部署要不要上 K8s | 高可用+分布式的主流解法是 K8s；本项目走公司 PaaS 平台（底层 K8s），业务开发者只需写 Dockerfile + 配流水线，不直接写 K8s YAML；上 K8s 前代码侧状态问题（SubagentRegistry 内存态、Session 并发写、文件工具位置）须先补 |
