# OpenSandbox 沙箱平台分析

> 面向 AI Agent 的沙箱平台技术解读：项目定位、AI 协议接入的必要性、CLI 与沙箱的关系、AI Agent 沙箱的通用逻辑，以及隔离运行时的分层实现。
> 参考项目：https://github.com/opensandbox-group/OpenSandbox
> 生成日期：2026-09-15

---

## 一、项目定位

OpenSandbox 被定位为「面向 AI 应用的通用沙箱平台」：

> Secure, Fast, and Extensible Sandbox runtime for AI agents.
> （面向 AI 智能体的安全、快速、可扩展沙箱运行时。）

它提供多语言 SDK、统一沙箱 API，以及 Docker / Kubernetes 两种运行时，主要服务以下场景：

- Coding Agents（编码智能体）
- GUI Agents（图形界面智能体）
- Agent Evaluation（智能体评测）
- AI Code Execution（AI 代码执行）
- RL Training（强化学习训练）

架构采用组件化设计：

| 组件/目录 | 作用 |
|-----------|------|
| `server/` | 基于 Python FastAPI 的沙箱生命周期服务器 |
| `components/execd/` | 沙箱执行守护进程，负责命令执行和文件操作 |
| `components/ingress/` | 沙箱流量入口代理 |
| `components/egress/` | 沙箱网络出口控制 |
| `sdks/` | 多语言 SDK（Python、Java/Kotlin、TypeScript/JS、C#/.NET、Go） |
| `cli/` | `osb` 命令行工具 |
| `kubernetes/` | Kubernetes 部署与示例 |
| `specs/` | OpenAPI 规范与生命周期规范 |
| `oseps/` | OpenSandbox Enhancement Proposals（增强提案） |

---

## 二、为什么需要实现各类 AI 协议

核心结论：**「封闭」和「可被调用」是两个不同的层面，完全不冲突。**

### 2.1 沙箱有两个层面

| 层面 | 封闭/开放 | 作用 |
|------|-----------|------|
| **数据面**（沙箱内部） | 封闭、隔离 | 隔离执行环境，保证安全 |
| **控制面**（对外接口） | 开放、标准化 | 让 AI 能创建沙箱、执行命令、读写文件 |

沙箱的「封闭」指执行环境的内部隔离；而实现各类 AI 协议做的是**控制面**这一层，与数据面的封闭隔离不冲突。

### 2.2 AI 生态是碎片化的

不同的 AI 客户端有各自的工具调用方式：Claude Code（Anthropic）、OpenAI Codex CLI、Gemini CLI（Google）、Cursor、OpenCode、Qwen Code、Kimi CLI 等。

如果沙箱不给每类客户端提供「说同一套话」的能力，每个 AI 客户端都得自己写一套对接代码。

### 2.3 MCP 是解决碎片化的「通用语言」

MCP（Model Context Protocol，Anthropic 提出）是连接「AI 客户端」和「外部工具」的标准化协议。

OpenSandbox 实现了一个 **MCP server**，把沙箱能力（创建沙箱、执行命令、操作文件）暴露成标准 MCP 工具。实现**一次**，N 个客户端复用，不用为每个厂商写 adapter。

### 2.4 目标是成为「AI 基础设施层」

如果只做一个封闭沙箱、什么都不接，那就只是个普通容器运行时，和 Docker 没区别。真正的价值在于：**让任何 AI 都能安全、方便地把代码丢进来执行**。

**一句话类比**：像数据库——光有存储引擎不够，还得实现 JDBC / ODBC / SQL 协议让应用连上来。OpenSandbox 实现各类 AI 协议，本质是在做「沙箱世界的 JDBC」。

---

## 三、CLI 与沙箱的关系

关键澄清：**CLI 本身不是沙箱，CLI 是「被关进沙箱里的那个 AI」。**

| 角色 | 谁 | 是什么 |
|------|-----|--------|
| **CLI**（Kimi、Claude Code、Codex、Gemini、GLM…） | AI 编码 Agent | 那个「大脑」，会读写文件、执行命令 |
| **沙箱** | OpenSandbox 创建的容器 | 关住这个大脑的「笼子」 |

「Kimi CLI」「GLM 的 CLI」本质是各家 AI 公司的编码 Agent，之所以叫「CLI」，只是因为 coding agent 都以**命令行工具**的形式发布（在终端敲 `kimi`、`claude`、`codex` 就能对话）。

### 3.1 具体运行机制（以 Kimi CLI 为例）

把**整个 CLI 进程装进容器里跑**：

1. OpenSandbox 起一个隔离容器（基于 `code-interpreter` 镜像）；
2. 在容器内执行 `pip install kimi-cli`（宿主机不装任何依赖）；
3. 在容器内执行 `kimi -p "..."`（非交互式运行 Agent）；
4. Agent 的所有文件读写、命令执行全部发生在容器内；
5. 最后 `sandbox.kill()` 回收沙箱。

执行链路：

```
你的 SDK 脚本 → OpenSandbox server → 容器内 execd 代理 → 执行命令
```

宿主机上**只跑一个 OpenSandbox server**，Agent 被完全关在容器里，其 API Key 只在容器内可见，所有操作都被容器加固项约束。

### 3.2 结论

「OpenSandbox 支持各类 AI 的 CLI」=「能把各家的编码 Agent 都关进管理的沙箱里运行」。CLI 提供「聪明」，沙箱提供「安全」，OpenSandbox 负责把「聪明的 AI」关进「安全的笼子」里干活。

---

## 四、AI Agent 沙箱的通用逻辑

抽象逻辑上，所有 AI Agent 沙箱高度一致，都围绕同一件事：

> **让「不可信的 AI 生成的代码/操作」，在一个隔离、可回收、受控的环境里执行。**

### 4.1 共同骨架

| 核心模块 | 作用 | OpenSandbox 对应 |
|---------|------|-----------------|
| **隔离运行时** | 隔离进程/文件/网络 | Docker/K8s + gVisor/Kata/Firecracker |
| **统一控制面 API + 多语言 SDK** | 让 Agent 能调用沙箱 | Sandbox Protocol + SDK |
| **生命周期管理** | 创建、超时、销毁、回收 | `Sandbox.create` / `kill` |
| **命令执行 + 文件操作** | Agent 的两个基本动作 | `commands.run` / `files.write/read` |
| **网络策略** | 控制沙箱进出流量 | ingress/egress、FQDN 级出口控制 |
| **凭据安全注入** | 让 Agent 用密钥又不泄露 | Credential Vault |

同类产品（E2B、Daytona、Modal Sandbox、Cloudflare Sandbox、OpenAI Code Interpreter 后端）都遵循这套逻辑。

### 4.2 差异维度

「类似」不等于「一样」，差异主要在：

1. **隔离等级**：Docker 容器 < gVisor < Kata < Firecracker microVM，越往后越安全、启动越慢；
2. **冷启动速度**：快照（snapshot）或热启动能力是关键分水岭；
3. **编排规模**：单机 Docker vs K8s 分布式调度；
4. **有状态/持久化**：Agent 沙箱要长时间活着、文件要保留。

### 4.3 与传统代码沙箱的分水岭

| 类型 | 特征 |
|------|------|
| 传统代码执行沙箱（OJ、Judge0） | 短生命周期、无状态、跑完即毁 |
| **AI Agent 沙箱**（OpenSandbox、E2B） | 长生命周期、有状态、交互式命令、文件持久、有网络进出 |

Agent 场景对沙箱提出了比「跑段代码」复杂得多的需求，这也是 OpenSandbox 与传统沙箱拉开差距的地方。

---

## 五、隔离运行时的具体实现

OpenSandbox 的隔离是**分层的**——复用 Linux 容器技术 + 可选强隔离运行时 + 自研数据面/网络面控制。从下到上共 5 层：

### 第 1 层：基础容器隔离（namespace + cgroups）

依赖 Docker / Kubernetes 的标准容器能力，本质是 Linux 内核两套机制：

- **namespace**（隔离「能看见什么」）：PID / Mount / Network / UTS / IPC / user namespace；
- **cgroups**（限制「能用多少」）：CPU、内存、pids（如 `pids_limit`）。

**弱点**：所有容器共享同一个宿主机内核，内核漏洞可导致逃逸。

### 第 2 层：内核级强隔离（OSEP-0004，可选）

针对「AI 生成的不可信代码」换掉容器运行时，让沙箱不再共享宿主机内核：

| 运行时 | 隔离原理 | 启动 | 内存 |
|--------|---------|------|------|
| `runc`（默认） | 进程级 cgroups，共享内核 | ~0ms | 极低 |
| **gVisor** (`runsc`) | 用户态内核，拦截系统调用 | ~10-50ms | ~50MB |
| **Kata** (`kata-runtime`) | 每个沙箱一个 QEMU 轻量虚拟机，独立 guest 内核 | ~500ms | 20-50MB |
| **Firecracker** (`kata-fc`) | microVM 极简虚拟化 | ~125ms | ~5MB |

- gVisor：`runsc` 在用户态实现内核，拦截 syscall，不暴露真内核；
- Kata/Firecracker：每个沙箱开一个真正的微型虚拟机，实现硬件级隔离。

配置为**服务器级、一次生效、对用户透明**：SDK 不改代码，OpenSandbox 自动向 Docker `HostConfig` 注入 `runtime` 字段，或向 K8s Pod spec 注入 `runtimeClassName`。

### 第 3 层：execd 数据面守护进程

每个沙箱内注入一个 Go 程序 `execd`（Gin HTTP server），是沙箱内部的**唯一受控入口**：

```
外部 SDK / CLI / MCP → OpenSandbox server → 容器内 execd → 真正执行
```

外部从不直接进容器，所有动作通过 execd 的 HTTP API：

- `/command` → 执行 shell 命令（SSE 流式输出）
- `/session` → 持久化 bash session
- `/pty` → 交互式终端（WebSocket）
- `/files` `/directories` → 文件/目录操作
- `/code` → Jupyter 代码执行
- `/metrics` → CPU/内存采集

execd 相当于「白名单入口」，把 Agent 能做的事限定在受控动作上，而不是给一个裸 shell。

### 第 4 层：网络隔离（Ingress + Egress sidecar）

**双向管控**，出口走独立 egress sidecar（源码 `components/egress/nft.go`）：

```
默认 deny（全部拒绝出站）
  ↓
DNS 解析过滤（dns-only 或 dns+nft 模式）
  ↓
nftables 动态放行解析出的 IP
  ↓
支持 FQDN 通配符（如 *.example.com）
  ↓
运行时可用 PATCH /policy 热更新
  ↓
可选：透明 HTTPS MITM + Credential Vault
```

粒度精确到 FQDN 级，Agent 访问某域名需先被 DNS 过滤、再由 nftables 精确放行对应 IP。

### 第 5 层：安全加固（细节项）

- **drop_capabilities**：去掉多余 Linux 权限；
- **no_new_privileges**：禁止 setuid 等提升权限；
- **pids_limit**：限制进程数，防 fork 炸弹；
- **TTL 生命周期**：`timeout` 到期自动销毁，防沙箱泄漏；
- **Credential Vault**：API Key 通过透明 MITM 注入，沙箱代码看不到真实密钥（experimental）。

### 隔离层次总览

```
┌─────────────────────────────────────────┐
│  第5层 安全加固：cap/priv/pids/TTL/Vault │
│  ┌───────────────────────────────────┐  │
│  │ 第4层 网络隔离：egress sidecar     │  │
│  │   (DNS + nftables 默认 deny)      │  │
│  │  ┌─────────────────────────────┐  │  │
│  │  │ 第3层 execd：唯一受控入口    │  │  │
│  │  │  ┌───────────────────────┐  │  │  │
│  │  │  │ 第1层 namespace+cgroups │  │  │  │
│  │  │  │   (或第2层 gVisor/Kata │  │  │  │
│  │  │  │    /Firecracker 强隔离)│  │  │  │
│  │  │  │   ← AI 的代码跑在这里 │  │  │  │
│  │  │  └───────────────────────┘  │  │  │
│  │  └─────────────────────────────┘  │  │
│  └───────────────────────────────────┘  │
└─────────────────────────────────────────┘
```

---

## 六、总结

1. **沙箱的封闭性与协议开放性不冲突**：封闭的是执行环境内部，开放的是对 AI 的控制面接口。
2. **CLI 不是沙箱**：CLI 是各家 AI 编码 Agent 的分发形态，沙箱是它们的安全运行环境。
3. **AI Agent 沙箱逻辑骨架一致**：隔离 + 统一 API + 生命周期 + 安全控制，差异在隔离强度、启动速度、规模与持久化。
4. **隔离是分层实现的**：namespace/cgroups 打底 + gVisor/Kata/Firecracker 可选加固 + execd 收口操作 + egress sidecar 管死网络 + 安全收紧项。

核心一句话：**OpenSandbox 没有自造隔离内核，而是把 Linux 已有的隔离技术，用「唯一入口 + 默认拒绝」的工程方式，组合成一套面向 AI Agent 的完整沙箱方案。**
