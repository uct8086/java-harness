# 多 Agent 编排：模式与定位 / Multi-Agent Orchestration: Patterns & Positioning

> UCT8086-AI 的 **LLM-as-Orchestrator** 设计如何对应到业界主流的多 Agent 编排模式，以及我们在标准之外做了哪些加法。
> >
> > How UCT8086-AI's **LLM-as-Orchestrator** design maps to the industry's mainstream multi-agent orchestration patterns, and what we add beyond them.
> >
> > 面试参考——另见 [总结.md](./总结.md) §4.9 和 §4.10。
> >
> > Interview reference — see also [总结.md](./总结.md) §4.9 and §4.10.

---

## 1. 我们的定位：五大主流模式之一 / Where We Sit: One of Five Mainstream Patterns

到 2026 年，多 Agent 编排领域已收敛为五种典型模式。我们的设计属于第 2 种——也是生产环境中最广泛采用的一种。

By 2026 the multi-agent orchestration space has converged on five canonical patterns. Our design is pattern #2 — the one most widely used in production.

| # | 模式 / Pattern | 核心思想 / Core Idea | 代表项目 / Representative |
|---|----------------|---------------------|--------------------------|
| 1 | 顺序流水线 / Sequential Pipeline | 确定性工作流，节点与边预先定义 | LangGraph base graph, Temporal DAG |
| **2** | **层级监督 / Hierarchical Supervisor**（又称 LLM-as-Orchestrator / Orchestrator-Worker） | **一个主 LLM 决定是否拆分、拆几个、派给谁、何时聚合** | **Claude Agent SDK, OpenAI Agents SDK handoff, CrewAI hierarchical, LangGraph supervisor** |
| 3 | 群体 / Swarm（P2P） | 去中心化，Agent 之间作为对等节点协商 | OpenAI Swarm（已归档）, AutoGen GroupChat |
| 4 | 辩论 / Debate | Agent 之间互相 critique，由裁判裁决 | AutoGen debate mode |
| 5 | 扇出 / Fan-out | 独立子任务并行派发 | Claude Code parallel subagents |

我们的核心主张——「不是静态 DAG，LLM 在运行时做拆解决策」——正是模式 #2 的定义。Anthropic 的 Claude Code 和 Claude Research 功能就跑在完全相同的机制上。

Our core claim — *"not a static DAG; the LLM makes decomposition decisions at runtime"* — is the definition of pattern #2. Anthropic's Claude Code and Claude Research features run on exactly this mechanism.

---

## 2. 业界框架怎么做编排 / How Popular Frameworks Do Orchestration

| 框架 / Framework | 编排机制 / Orchestration mechanism | 一句话本质 / One-line essence |
|------------------|-----------------------------------|-------------------------------|
| **LangGraph** | 显式**图 + 状态机**：节点/边/条件边；Supervisor 是一种预设模式 | 开发者画出"谁调用谁"的图，运行时负责持久化/回放/人机协作 |
| **Claude Agent SDK** | **orchestrator-subagent**：主 Agent 通过 `task` 工具向子 Agent 委派有界任务；子 Agent 拥有独立上下文窗口 + 工具权限；MCP 是工具层 | **与我们的设计几乎同构** |
| **OpenAI Agents SDK** | **handoff**：一个 Agent 把对话转交给另一个 | 轻量分诊与路由；绑定 OpenAI 生态 |
| **CrewAI** | 角色驱动 crew（researcher/writer/reviewer）+ sequential/hierarchical process；hierarchical 模式用 manager LLM | 原型验证最快；生产可观测性较弱 |
| **AutoGen / AG2** | 对话式 GroupChat；manager（LLM 或规则）选下一个发言者 | 对话隐喻；适合辩论/评审循环 |
| **MetaGPT** | SOP 驱动角色扮演（PM → 架构师 → 工程师） | 强约束；软件生成 |
| **Microsoft Agent Framework 1.0** | 合并 Semantic Kernel + AutoGen（2026 年 4 月 GA） | Azure 生态 |
| **Google ADK** | 层级 Agent 树，可插拔后端 | Vertex AI / Workspace |

| Framework | Orchestration mechanism | One-line essence |
|-----------|-------------------------|------------------|
| **LangGraph** | Explicit **graph + state machine**: nodes/edges/conditional edges; Supervisor is one preset pattern | Developer draws "who calls whom" as a graph; runtime handles persistence/replay/HITL |
| **Claude Agent SDK** | **orchestrator-subagent**: lead agent delegates scoped tasks to subagents via a `task` tool; subagents have isolated context windows + tool permissions; MCP is the tool layer | **Nearly identical to ours** |
| **OpenAI Agents SDK** | **handoff**: one agent transfers the conversation to another | Lightweight triage-and-route; OpenAI-ecosystem bound |
| **CrewAI** | Role-based crews (researcher/writer/reviewer) + sequential/hierarchical process; hierarchical mode uses a manager LLM | Fastest path to prototype; weaker production observability |
| **AutoGen / AG2** | Conversational GroupChat; a manager (LLM or rule) picks the next speaker | Chat metaphor; good for debate/review loops |
| **MetaGPT** | SOP-driven role-play (PM → architect → engineer) | Strong constraints; software generation |
| **Microsoft Agent Framework 1.0** | Merged Semantic Kernel + AutoGen (GA April 2026) | Azure ecosystem |
| **Google ADK** | Hierarchical agent tree, pluggable backends | Vertex AI / Workspace |

---

## 3. 我们的实现：三个原语 / Our Implementation: The Three Primitives

我们把编排能力以**三个工具原语**的形式暴露给模型——harness 只提供派发原语和拓扑约束；拆分、并行、停止全部是 LLM 在运行时的决策。

We expose orchestration to the model as **three tool primitives** — the harness only provides dispatch primitives and topology constraints; decomposition, parallelism, and stopping are 100% runtime decisions by the LLM.

| 原语 / Primitive | 角色 / Role |
|------------------|-------------|
| `agent` | 派生子代理：`name` + `role`（角色提示）+ `task`（LLM 生成的子任务文本）+ `wait`（true=同步等待，false=后台并行） |
| `send_message` | 续接子代理对话 / 收取后台子代理结果（有界等待 `wait_seconds`） |
| `task_stop` | 终止子代理（置 STOPPED + 跨实例 cancel flag） |

| Primitive | Role |
|-----------|------|
| `agent` | Spawn a sub-agent: `name` + `role` (role prompt) + `task` (LLM-generated subtask text) + `wait` (true = synchronous, false = background parallel) |
| `send_message` | Continue a sub-agent conversation / collect a background sub-agent result (bounded wait `wait_seconds`) |
| `task_stop` | Terminate a sub-agent (set STOPPED + cross-instance cancel flag) |

### 3.1 分布式派发链路 / Distributed Dispatch Pipeline

> 这是 2026-08-27 的改造。之前 `agent` 工具在进程内线程池上跑子代理，`SubagentRegistry` 是 JVM 内存 Map，`task_stop` 用 `Future.cancel(true)`——这些在单机部署下能工作，但**水平扩展后跨实例失效**。单机实现的完整留档见 [orchestration-single-node-legacy.md](./orchestration-single-node-legacy.md)。

> This is the 2026-08-27 refactor. Previously the `agent` tool ran sub-agents on an in-process thread pool, `SubagentRegistry` was an in-JVM Map, and `task_stop` used `Future.cancel(true)` — all of which worked under single-instance deployment but **broke under horizontal scaling**. Full archive of the single-node implementation: [orchestration-single-node-legacy.md](./orchestration-single-node-legacy.md).

改造后的派发链路（生产可用、跨实例）：

The post-refactor dispatch pipeline (production-grade, cross-instance):

```
编排者实例 A                       Redis                       Worker 实例 B
──────────────         ──────────────────────────────         ────────────────
AgentTool.doExecute
  ├─ sessionManager.createSession ───► harness_session (MySQL)
  ├─ subagentRegistry.register  ─────► harness:subagent:{userId} (Redis Hash)
  ├─ taskManager.createTask      ─────► harness:task:stream (Redis Stream msg)
  │                                       │
  │                                       ▼
  │                                  TaskManager.pollLoop (Consumer Group)
  │                                    └─ "SUBTASK" handler (AgentCoordinator)
  │                                         ├─ engine.execute(..., AgentScope.SUBAGENT)
  │                                         ├─ subagentRegistry.markCompleted
  │                                         │   ─────────────► harness:subagent:{userId}
  │                                         └─ TaskManager.updateStatus
  │                                             ─────────────► harness:task:{userId}
  │
  └─ wait=true: 轮询 taskManager.getTask 直到 COMPLETED
       └─ 读 subagentRegistry.lastResponse → 返回给 LLM
```

关键变化对照：

Key changes matrix:

| 维度 / Dimension | 改造前 / Before | 改造后 / After |
|-------------------|----------------|----------------|
| 状态层 / State layer | JVM `ConcurrentHashMap` | Redis Hash `harness:subagent:{userId}` |
| 句柄 / Cancellation handle | `java.util.concurrent.Future`（不可跨进程） | `TaskManager` taskId + Redis cancel flag |
| `send_message` 等待 / Wait | `future.get(timeout)` | 轮询 `taskManager.getTask(taskId)` 状态 |
| `task_stop` 取消 / Cancel | `future.cancel(true)`（本地有效） | `taskManager.cancelTask`（跨实例生效） |
| 容量 / Capacity | 单进程 `ThreadPoolExecutor(2,6,queue=20)` | 所有 worker 共享 Consumer Group，弹性扩展 |

| Dimension | Before | After |
|-----------|--------|-------|
| State layer | JVM `ConcurrentHashMap` | Redis Hash `harness:subagent:{userId}` |
| Cancellation handle | `java.util.concurrent.Future` (cannot cross process) | `TaskManager` taskId + Redis cancel flag |
| `send_message` wait | `future.get(timeout)` | Poll `taskManager.getTask(taskId)` status |
| `task_stop` cancel | `future.cancel(true)` (local-only) | `taskManager.cancelTask` (cross-instance) |
| Capacity | Single-process `ThreadPoolExecutor(2,6,queue=20)` | All workers share one Consumer Group, elastic |

### 3.2 拓扑约束 / Topology Constraints

`OrchestrationMode` 定义"谁持有编排决策权"——即哪些 Agent 能看到三个编排原语。

`OrchestrationMode` defines *who holds the orchestration decision rights* — i.e. which agents get the orchestration primitives.

| 模式 / Mode | 拓扑 / Topology | 工具过滤策略 / Tool filtering strategy |
|-------------|-----------------|-----------------------------------------|
| `LOCAL` | 单 Agent | 编排工具不注册（最彻底） |
| `COORDINATOR`（默认） | 星型：主 Agent 派发，子代理不能再派发 | 子代理执行时剔除编排三件套 |
| `SWARM` | 递归网状，子代理可继续派发 | 不剔除 |

| Mode | Topology | Tool filtering strategy |
|------|----------|--------------------------|
| `LOCAL` | Single agent | Orchestration tools not registered at startup |
| `COORDINATOR` (default) | Star: main agent delegates, sub-agents cannot re-delegate | Sub-agents' orchestration tools stripped at execution |
| `SWARM` | Recursive mesh; sub-agents may spawn further | Not stripped |

**「子代理」的本质**：一次带新 session id + role prompt + `AgentScope.SUBAGENT` 的 `executeInternal` 递归调用——与主代理走同一个引擎循环，只是按拓扑模式剔除编排工具。子代理的实际执行发生在 worker 实例上（通过 Redis Stream `SUBTASK` 消息派发），编排者只持有 taskId 用于后续 await / cancel。

**The "sub-agent" is a recursive `executeInternal` call** with a new session id + role prompt + `AgentScope.SUBAGENT` — the same engine loop as the main agent, minus the three orchestration tools (per topology mode). The actual sub-agent execution happens on a worker instance (dispatched via Redis Stream `SUBTASK` message); the orchestrator only holds the taskId for subsequent await / cancel.

---

## 4. 最近的兄弟：Claude Agent SDK / Closest Cousin: Claude Agent SDK

我们的设计与 Claude Agent SDK 的 orchestrator-subagent 循环结构同构。

Our design is structurally isomorphic to Claude Agent SDK's orchestrator-subagent loop.

| 我们 / Ours | Claude Agent SDK |
|-------------|-------------------|
| `agent` 工具（name + role + task + wait） | `task` 工具（delegate scoped task to subagent） |
| 子代理独立会话（`harness_session` 物理隔离 + Redis Hash 状态） | Subagent isolated context window |
| 子代理剔除编排三件套（防递归蔓延） | Subagent scoped tool permissions |
| `task_stop` 跨实例取消（Redis cancel flag + taskId） | Cancellation / timeout |
| SUBTASK 经 Redis Stream 跨 worker 派发 | （SDK 为单进程，无对应物） |

| Ours | Claude Agent SDK |
|------|-------------------|
| `agent` tool (name + role + task + wait) | `task` tool (delegate scoped task to subagent) |
| Sub-agent isolated session (physical separation in `harness_session` + Redis Hash state) | Subagent isolated context window |
| Sub-agent strips the 3 orchestration tools (prevents recursive sprawl) | Subagent scoped tool permissions |
| `task_stop` cross-instance cancel (Redis cancel flag + taskId) | Cancellation / timeout |
| SUBTASK dispatched across workers via Redis Stream | (SDK is single-process; no equivalent) |

所以我们没发明一个 wild idea——而是独立落地到了 Anthropic 在生产中验证过的同一个模式。这本身就是一个 talking point：*"我没有抄框架，但我做出来的东西和 Claude Agent SDK 的核心机制结构上完全一致。"*

So we didn't invent a wild idea — we independently landed on the same pattern Anthropic validated in production. That is itself a talking point: *"I didn't copy a framework, but what I built is structurally identical to Claude Agent SDK's core mechanism."*

---

## 5. 我们在"标准"之外做了什么 / What We Add Beyond "Standard"

大多数框架给你编排但不给你**容纳**。我们额外加了三个多数框架没有的工程约束：

Most frameworks give you orchestration but not **containment**. We add three engineering constraints most frameworks lack:

### 5.1 拓扑约束（LOCAL/COORDINATOR/SWARM）/ Topology Constraints

纯 Swarm 的最大失败模式是无界递归派生；我们的默认 COORDINATOR（星型）在根节点就封住了蔓延深度。

Pure Swarm's biggest failure mode is unbounded recursive spawning; our default COORDINATOR (star) caps sprawl depth at the root.

### 5.2 双层工具过滤一致性 / Dual-Layer Tool-Filter Consistency

`buildToolCallbacks`（模型**实际能调**的工具）和 `buildSystemPrompt`（模型**自以为有**的工具）必须用**同一份排除集**，否则模型会尝试调用不存在的工具。几乎没框架文档化这个坑，但我们处理了。

`buildToolCallbacks` (what the model can *actually* call) and `buildSystemPrompt` (what the model *thinks* it has) must use the **same exclusion set**, otherwise the model tries to call a tool that doesn't exist. Almost no framework documents this, but we handle it.

### 5.3 子代理会话隔离 / Sub-Agent Session Isolation

主/子代理对话历史在 `harness_session` 表**物理隔离**，防止上下文互相污染；子代理状态在 Redis Hash 按 `userId` 命名空间化，跨实例可见但不跨用户。

Main/sub-agent conversation histories are physically separated in the `harness_session` table, preventing context cross-contamination; sub-agent state lives in a Redis Hash namespaced by `userId` — visible across instances but not across users.

### 5.4 分布式编排一致性 / Distributed Orchestration Consistency（新增 / Added）

三个原语在多实例部署下行为一致：

The three primitives behave consistently under multi-instance deployment:

- `agent` 工具：经 Redis Stream 派发到任意 worker，编排者通过 taskId 跟踪
- `send_message`：轮询 TaskManager 状态而非本地 `Future`，任何实例都能续会话
- `task_stop`：Redis cancel flag 跨实例生效，worker 在执行前/中检测到就中断

- `agent` tool: dispatched to any worker via Redis Stream; orchestrator tracks via taskId
- `send_message`: polls TaskManager status instead of local `Future`; any instance can resume the session
- `task_stop`: Redis cancel flag takes effect cross-instance; worker observes it before/during execution and aborts

---

## 6. 面试 Q&A / Interview Q&A

**Q：为什么不用 LangGraph 的 Supervisor？**
> LangGraph 把编排建模为**显式图**——节点和边由开发者预先定义。这适合确定性、可审计、可回放的工作流。我们的工作负载是**开放式**的：要不要拆、拆几个、怎么拆，模型运行时才知道，画成图很别扭。而且我们定位是**运行时基础设施**（权限、成本、多租户、可观测）——恰好是 LangGraph 不覆盖的层，我在其上补齐。

**Q: Why not LangGraph's Supervisor?**
> LangGraph models orchestration as an *explicit graph* — nodes and edges are predefined by the developer. That fits deterministic, auditable, replayable workflows. Our workload is *open-ended*: whether to split, how many, and how — the model only knows at runtime, so expressing it as a graph is awkward. And our positioning is a *runtime infrastructure* (permission, cost, multi-tenancy, observability) — precisely the layers LangGraph doesn't cover; I built them on top.

**Q：你和 OpenAI handoff 有什么区别？**
> Handoff 是**对话转交**（一对一控制权交接），适合路由/分诊。我们的是**任务委派**（一对多）：主 Agent 可以并行派发多个子代理（`wait=false`）并聚合结果。这就是 orchestrator-worker vs. routing 的区别。

**Q: How is this different from OpenAI handoffs?**
> Handoff is *conversation transfer* (one-to-one control handover), suited to routing/triage. Ours is *task delegation* (one-to-many): the main agent can dispatch multiple sub-agents in parallel (`wait=false`) and aggregate results. That's the orchestrator-worker vs. routing distinction.

**Q：LLM 编排最大的成本是什么，你怎么控制？**
> Token 成本。Supervisor 模式相对单 Agent 消耗 4-15x token（Anthropic 自己的数据：Claude Research 的 Opus-lead + Sonnet-workers 在内部评测上比单 Agent 高 ~90%，但 token 用量 ~15x）。所以我们内置了成本熔断、`max-turns` 上限、上下文压缩、模型分层（编排者用强模型，worker 用便宜模型）。

**Q: What's the biggest cost of LLM orchestration, and how do you control it?**
> Token cost. The supervisor pattern consumes 4–15x tokens vs. a single agent (Anthropic's own numbers: Claude Research's Opus-lead + Sonnet-workers beat single-agent by ~90% on internal evals but at ~15x tokens). So we built in cost circuit-breaking, `max-turns` caps, context compression, and model tiering (strong model for the orchestrator, cheap models for workers).

**Q：分布式部署下子代理怎么跨实例工作？**（新增）
> 三个原语全部走 Redis：`agent` 工具调 `TaskManager.createTask("SUBTASK", ...)` 把子任务丢进 Redis Stream，任意 worker 实例的消费组会捡起来执行；子代理状态（sessionId、role、taskId、status、lastResponse）存在 Redis Hash `harness:subagent:{userId}`，所有实例都能读写；`send_message` 通过轮询 `TaskManager.getTask` 而不是本地 `Future` 来等待子代理完成；`task_stop` 用 Redis cancel flag 跨实例中断 worker。这样编排者实例和 worker 实例可以不是同一个，水平扩展时三个原语行为完全一致。

**Q: How do sub-agents work across instances in distributed deployment?** (Added)
> All three primitives go through Redis: the `agent` tool calls `TaskManager.createTask("SUBTASK", ...)` to enqueue the subtask onto a Redis Stream; any worker instance in the consumer group picks it up and executes it; sub-agent state (sessionId, role, taskId, status, lastResponse) lives in a Redis Hash `harness:subagent:{userId}`, readable/writable from all instances; `send_message` polls `TaskManager.getTask` instead of a local `Future` to wait for the sub-agent; `task_stop` uses a Redis cancel flag to interrupt the worker cross-instance. This way the orchestrator instance and the worker instance don't have to be the same — the three primitives behave consistently under horizontal scaling.

---

## TL;DR（面试一句话 / one line for interviews）

> 我的编排是业界标准的 **LLM-as-Orchestrator / Supervisor** 模式——与 Claude Agent SDK 的 orchestrator-subagent 循环结构同构——额外加了四个多数框架不提供的工程约束：**拓扑容纳、双层工具过滤一致性、子代理会话隔离、分布式编排一致性**。

> My orchestration is the industry-standard **LLM-as-Orchestrator / Supervisor** pattern — structurally identical to Claude Agent SDK's orchestrator-subagent loop — with four extra engineering constraints most frameworks don't provide: **topology containment, dual-layer tool-filter consistency, sub-agent session isolation, and distributed orchestration consistency**.

