# UCT8086-AI: Open Agent Harness

> 基于 Java 21 + Spring Boot 4.0 + Spring AI 2.0 构建的 AI Agent Harness 基础设施，参考 [OpenHarness](https://github.com/HKUDS/OpenHarness) 架构设计，提供完整的 Agent 工程化能力。

## 项目简介

UCT8086-AI（Open Agent Harness）是一个用 Java 技术栈实现的 AI Agent 管理框架，目标是提供类似 OpenHarness 的全套 Harness 流程管控能力，涵盖工具管理、权限控制、Hook 机制、Agent 引擎、Prompt 组装、会话管理、成本追踪等完整 AI 工程化能力。

### 核心能力

| 能力 | 说明 |
|------|------|
| **Agent Loop** | 查询 → 模型调用 → 工具执行 → 结果回传 → 循环直到完成 |
| **Tool Registry** | 工具注册、发现、分类管理，支持动态注册插件和 MCP 工具 |
| **Permission System** | 四级安全模式（DEFAULT / AUTO / PLAN_MODE / READ_ONLY），路径级规则控制，危险命令拦截 |
| **Hook System** | PreToolUse / PostToolUse 生命周期钩子，支持阻止执行或修改结果 |
| **Skill System** | 基于 Markdown 的技能加载，支持 YAML frontmatter，多目录加载 |
| **Memory System** | 持久化跨会话记忆，基于 MEMORY.md 文件存储 |
| **Session Management** | 会话创建、恢复、历史记录、消息追踪 |
| **Cost Tracking** | Token 用量和成本追踪，按会话和全局维度统计 |
| **Multi-Agent Coordination** | 子 Agent 生成、团队管理、任务委派 |
| **RAG Knowledge Base** | 基于 pgvector + Ollama 的语义检索，自动注入相关文档到 Prompt |
| **MCP Client** | Model Context Protocol 客户端集成 |
| **Slash Commands** | 斜杠命令系统（`/help`、`/commit` 等） |
| **REST API** | 全功能 HTTP API，暴露所有子系统 |

## 技术栈

| 组件 | 版本 |
|------|------|
| Java | 21 |
| Spring Boot | 4.0.0 |
| Spring AI | 2.0.0 |
| MySQL | 8.x（会话/消息持久化） |
| Redis | 7.x（会话缓存） |
| PostgreSQL + pgvector | 16+（向量存储） |
| Ollama | `bge-m3` 模型（本地 Embedding） |
| Lombok | (Spring Boot managed) |
| 构建工具 | Maven |

## 模块结构

```
uct8086-ai/
├── pom.xml                          # 父 POM（模块管理 + 依赖版本）
├── common/                   # 公共模块：枚举、模型、异常
├── core/                     # 核心模块：Agent 引擎、工具、权限、Hook、会话、成本
├── skills/                   # 技能模块：Markdown 技能加载与注册
├── memory/                    # 记忆模块：持久化记忆存储
├── tasks/                    # 任务模块：后台任务管理
├── coordinator/              # 协调模块：多 Agent 协作
├── mcp/                      # MCP 模块：Model Context Protocol 客户端
└── app/                      # 应用模块：Spring Boot 启动 + REST API
```

### 模块依赖关系

```
app
├── common
├── core
│   └── common
├── skills
│   └── common
├── memory
│   └── common
├── tasks
│   └── common
├── coordinator
│   ├── common
│   └── tasks
└── mcp
    └── common
```

## 架构设计

### Agent Loop 流程

```
用户输入 Prompt
      │
      ▼
┌─────────────┐
│ PromptAssembler │  组装系统提示（Agent 身份 + 工具描述 + 技能 + 记忆 + 安全指南）
└──────┬──────┘
       │
       ▼
┌─────────────┐
│ SessionManager │  创建或恢复会话，记录消息历史
└──────┬──────┘
       │
       ▼
┌─────────────┐
│  ChatClient  │  调用 Spring AI ChatClient，携带 ToolCallback
│  (Spring AI) │
└──────┬──────┘
       │
       ▼ (模型请求工具调用)
┌──────────────────────────────────────────┐
│        HarnessToolCallbackAdapter         │  适配 HarnessTool → Spring AI ToolCallback
│                    │                      │
│        ┌───────────▼──────────┐           │
│        │ ToolExecutionService  │           │
│        │    (执行管线)          │           │
│        │  1. Permission Check  │           │
│        │  2. PreToolUse Hook   │           │
│        │  3. Execute Tool      │           │
│        │  4. PostToolUse Hook  │           │
│        └──────────────────────┘           │
└──────────────────────────────────────────┘
       │
       ▼
┌─────────────┐
│ CostTracker  │  记录 Token 用量
└─────────────┘
       │
       ▼
   返回结果
```

### 工具执行管线

每次工具调用都会经过完整的管线：

1. **Permission Check** — 检查权限模式、路径规则、危险命令
2. **PreToolUse Hook** — 执行前置钩子，可阻止执行
3. **Execute Tool** — 执行工具逻辑
4. **PostToolUse Hook** — 执行后置钩子，可修改结果

### 权限模式

| 模式 | 行为 |
|------|------|
| `DEFAULT` | 写操作前询问用户确认（日常开发模式） |
| `AUTO` | 自动允许所有操作（沙箱环境） |
| `PLAN_MODE` | 阻止所有写操作（审查模式） |
| `READ_ONLY` | 仅允许只读操作 |

### RAG 知识库

项目集成了基于 **pgvector + Ollama** 的 RAG（检索增强生成）能力，在每次 Agent 调用前自动从知识库检索相关文档注入到系统 Prompt 中。

```
用户 Prompt
    │
    ▼
AgentEngine.enrichWithRag()
    │
    ├──→ Ollama bge-m3 (本地 Embedding) ──→ 将 Prompt 转为 1024 维向量
    │
    ├──→ PgVectorStore.similaritySearch() ──→ PostgreSQL + pgvector 余弦相似度搜索
    │
    ▼
系统 Prompt + "\n\n## Relevant Documents\n" + 检索到的文档
    │
    ▼
DeepSeek Chat API（生成回答）
```

**架构说明：**

| 组件 | 角色 | 说明 |
|------|------|------|
| **DeepSeek API** | Chat | 对话生成（OpenAI 兼容协议，DeepSeek 不支持 Embedding） |
| **Ollama `bge-m3`** | Embedding | 本地运行，将文本转为 1024 维向量，免费、数据不出内网 |
| **PostgreSQL + pgvector** | 向量存储 | 存储知识库文档向量，支持余弦相似度搜索和 HNSW 索引 |

**为什么不直接用 DeepSeek 做 Embedding？** DeepSeek 专注对话/推理模型，不提供 Embedding API。Ollama 本地补位，无需额外购买 API Key。

### 内置工具

| 工具名 | 类别 | 只读 | 说明 |
|--------|------|------|------|
| `bash` | SHELL | 否 | 执行 Shell 命令，支持超时控制 |
| `read_file` | FILE_IO | 是 | 读取文件内容，支持路径解析和截断 |
| `write_file` | FILE_IO | 否 | 写入文件，支持追加模式 |
| `glob` | FILE_IO | 是 | 按 Glob 模式查找文件 |
| `grep` | SEARCH | 是 | 正则搜索文件内容 |

## 快速开始

### 环境要求

- JDK 21+
- Maven 3.8+
- MySQL 8.x（会话/消息持久化）
- Redis 7.x（会话缓存）
- PostgreSQL 16+ + pgvector 扩展（向量存储）
- Ollama（本地 Embedding 模型）

### 安装依赖服务

**1. MySQL** — 手动建库：
```sql
CREATE DATABASE uct8086_ai CHARACTER SET utf8mb4;
```
表结构由 `schema.sql` 启动时自动创建。

**2. Redis** — 默认 `localhost:6379`，无密码。

**3. PostgreSQL + pgvector**：
```sql
-- 安装扩展
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS hstore;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
```
`vector_store` 表由 `PgVectorStore` 启动时自动创建（1024 维）。

**4. Ollama**（本地 Embedding）：
```bash
# 下载安装：https://ollama.com/download/windows
# 拉取中文 Embedding 模型（约 1.2GB）
ollama pull bge-m3
```
默认运行在 `localhost:11434`，无需额外配置。

### 构建项目

```bash
# 设置 JAVA_HOME 指向 JDK 21
export JAVA_HOME=/path/to/jdk-21

# 编译所有模块
mvn clean compile

# 打包
mvn clean package -DskipTests
```

### 配置

API Key 通过环境变量注入，不硬编码在配置文件中（不会被提交到版本库）。

**方式一：直接设环境变量**（推荐，避免每次输入）

```powershell
# PowerShell
$env:SPRING_AI_OPENAI_API_KEY="sk-your-deepseek-key"
```

```bash
# Linux / macOS / Git Bash
export SPRING_AI_OPENAI_API_KEY=sk-your-deepseek-key
```

**方式二：IntelliJ 启动配置中设置**

Run/Debug Configuration → Environment variables → 添加：
```
SPRING_AI_OPENAI_API_KEY=sk-your-deepseek-key
```

**application.yml 中的引用（无需修改）：**

```yaml
spring:
  ai:
    # Chat: DeepSeek (OpenAI 兼容协议)
    openai:
      api-key: ${SPRING_AI_OPENAI_API_KEY:}
      base-url: https://api.deepseek.com
      chat:
        options:
          model: deepseek-v4-pro
          temperature: 0.7
      embedding:
        enabled: false              # DeepSeek 不支持 Embedding API

    # Embedding: 本地 Ollama
    ollama:
      embedding:
        enabled: true
        options:
          model: bge-m3             # 1024 维中文 Embedding 模型
      chat:
        enabled: false              # Chat 只用 DeepSeek

  # 排除 PgVectorStore 自动配置（手动创建，用独立 PostgreSQL 数据源）
  autoconfigure:
    exclude:
      - org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration

# pgvector 数据源（独立于 MySQL 主数据源）
pgvector:
  datasource:
    url: jdbc:postgresql://${PGVECTOR_HOST:localhost}:5432/${PGVECTOR_DB:postgres}
    username: ${PGVECTOR_USER:postgres}
    password: ${PGVECTOR_PASSWORD:321432}

uct8086:
  ai:
    permission-mode: DEFAULT
    max-turns: 50
    working-directory: ${user.dir}
```

### 运行

```powershell
# PowerShell — 设置 Key 并启动
$env:SPRING_AI_OPENAI_API_KEY="sk-your-deepseek-key"
mvn spring-boot:run

# 或一行搞定
$env:SPRING_AI_OPENAI_API_KEY="sk-your-deepseek-key"; mvn spring-boot:run
```

```bash
# Linux / macOS / Git Bash
SPRING_AI_OPENAI_API_KEY=sk-your-deepseek-key mvn spring-boot:run
```

应用启动后，REST API 在 `http://localhost:9081` 可用。前端在 `http://localhost:5173`。

## REST API

### Agent 对话

```bash
# 发送对话
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"prompt": "列出当前目录下的文件", "sessionId": null}'

# 带额外上下文对话
curl -X POST http://localhost:8080/api/chat-with-context \
  -H "Content-Type: application/json" \
  -d '{"prompt": "分析这段代码", "sessionId": null, "additionalContext": "..."}'
```

### 会话管理

```bash
# 创建会话
curl -X POST "http://localhost:8080/api/sessions?name=my-session"

# 列出会话
curl http://localhost:8080/api/sessions

# 删除会话
curl -X DELETE http://localhost:8080/api/sessions/{id}
```

### 工具与权限

```bash
# 列出已注册工具
curl http://localhost:8080/api/tools

# 查看当前权限模式
curl http://localhost:8080/api/permission/mode

# 切换权限模式
curl -X PUT "http://localhost:8080/api/permission/mode?mode=AUTO"
```

### 成本追踪

```bash
# 总成本
curl http://localhost:8080/api/cost/total

# 按会话查询成本
curl http://localhost:8080/api/cost/session/{sessionId}
```

### 技能与记忆

```bash
# 列出技能
curl http://localhost:8080/api/skills

# 列出记忆
curl http://localhost:8080/api/memory

# 添加记忆
curl -X POST http://localhost:8080/api/memory \
  -H "Content-Type: application/json" \
  -d '{"category": "project", "content": "使用 Maven 构建系统"}'

# 搜索记忆
curl "http://localhost:8080/api/memory/search?keyword=Maven"
```

### RAG 知识库

```bash
# 摄入文档到知识库（自动向量化并存入 pgvector）
curl -X POST http://localhost:8080/api/knowledge/ingest \
  -H "Content-Type: application/json" \
  -d '{"content": "公司年假政策：入职满一年享5天年假...", "metadata": {"source": "hr-handbook"}}'

# 语义搜索知识库
curl "http://localhost:8080/api/knowledge/search?q=年假怎么算&topK=5"
```

摄入的文档会被 Ollama `bge-m3` 转为向量存入 PostgreSQL。Agent 每次对话时自动检索 Top-3 相关文档注入 Prompt。

### 后台任务

```bash
# 列出任务
curl http://localhost:8080/api/tasks

# 查看任务详情
curl http://localhost:8080/api/tasks/{id}

# 取消任务
curl -X DELETE http://localhost:8080/api/tasks/{id}
```

## 扩展开发

### 自定义工具

实现 `HarnessTool` 接口或继承 `AbstractTool`，并注册为 Spring Bean：

```java
import uct8086.ai.common.enums.ToolCategory;
import uct8086.ai.common.model.ToolExecutionContext;
import uct8086.ai.common.model.ToolResult;
import uct8086.ai.core.tool.AbstractTool;
import java.util.Map;

@Component
public class MyCustomTool extends AbstractTool {

    public MyCustomTool() {
        super("my_tool",
              "描述这个工具的用途，让模型知道何时使用",
              ToolCategory.META,    // FILE_IO | SHELL | SEARCH | WEB | MCP | TASK | AGENT | META
              false);               // 是否只读
    }

    @Override
    protected ToolResult doExecute(Map<String, Object> arguments,
                                   ToolExecutionContext context) throws Exception {
        String input = requireString(arguments, "input");
        // 工具逻辑...
        return ToolResult.success("result");
    }
}
```

工具会自动被 `HarnessCoreAutoConfiguration` 注册到 `ToolRegistry`。

### 自定义 Hook

实现 `ToolHook` 接口：

```java
import uct8086.ai.common.enums.HookPhase;
import uct8086.ai.common.model.HookContext;
import uct8086.ai.common.model.HookDefinition;
import uct8086.ai.common.model.HookResult;
import uct8086.ai.core.hook.ToolHook;

@Component
public class LoggingHook implements ToolHook {

    @Override
    public HookDefinition getDefinition() {
        return new HookDefinition(
            "log-all",              // hook 名称
            HookPhase.PRE_TOOL_USE,  // PRE_TOOL_USE | POST_TOOL_USE
            "*",                     // 匹配的工具名（支持通配符）
            100                      // 优先级（数值越小越先执行）
        );
    }

    @Override
    public HookResult onEvent(HookContext context) {
        // 记录日志、阻止执行或修改结果
        return HookResult.continueExecution();
        // 或: return HookResult.block("不允许执行此操作");
    }
}
```

### 自定义 Slash 命令

实现 `HarnessCommand` 接口：

```java
@Component
public class HelpCommand implements HarnessCommand {

    @Override
    public String getName() { return "help"; }

    @Override
    public String getDescription() { return "显示可用命令"; }

    @Override
    public String execute(List<String> args, Map<String, Object> context) {
        return "Available commands: /help, /plan, /commit, ...";
    }
}
```

### 自定义技能

创建 Markdown 文件（如 `.uct8086/skills/git-guide.md`）：

```markdown
---
name: git-guide
description: Git 操作指南和最佳实践
---
# Git 操作指南

## 常用命令
- `git status` — 查看状态
- `git log --oneline` — 简洁日志
...
```

技能会自动加载并注入到系统 Prompt 中。

## 项目结构详情

### common

公共枚举、模型和异常定义：

- **枚举**: `AgentRole`、`HookPhase`、`PermissionDecision`、`PermissionMode`、`TaskStatus`、`ToolCategory`
- **模型**: `AgentMessage`、`HookContext`、`HookDefinition`、`HookResult`、`PathRule`、`PermissionResult`、`SessionInfo`、`TokenUsage`、`ToolDescriptor`、`ToolExecutionContext`、`ToolResult`（包：`uct8086.ai.common.model`）
- **异常**: `Uct8086Exception`、`PermissionDeniedException`、`SkillLoadException`、`ToolExecutionException`

### core

核心引擎和子系统：

- **engine** — `AgentEngine`（Agent Loop）、`AgentLoopResult`、`HarnessToolCallbackAdapter`（Spring AI 桥接）（包：`uct8086.ai.core.engine`）
- **tool** — `ToolRegistry`、`HarnessTool` 接口、`AbstractTool`、`ToolExecutionService` 管线
- **tools** — 内置工具：`BashTool`、`FileReadTool`、`FileWriteTool`、`GlobTool`、`GrepTool`
- **permission** — `PermissionChecker` 接口、`DefaultPermissionChecker`（四级安全模式 + 路径规则 + 危险命令拦截）
- **hook** — `HookManager`、`ToolHook` 接口（PreToolUse/PostToolUse 生命周期）
- **prompt** — `PromptAssembler`（系统提示组装）
- **session** — `SessionManager`（会话管理 + 消息历史）
- **cost** — `CostTracker`（Token 用量与成本追踪）
- **command** — `CommandRegistry`、`HarnessCommand` 接口（Slash 命令系统）
- **config** — `HarnessProperties`（`uct8086.ai.*` 配置）、`HarnessCoreAutoConfiguration`（自动注册工具）

### skills

技能加载与注册系统：

- `Skill` — 技能 record（name, description, content, sourcePath, metadata）（包：`uct8086.ai.skills`）
- `SkillLoader` — 从文件系统加载 Markdown 技能，解析 YAML frontmatter
- `SkillRegistry` — 技能注册表，支持项目 `.uct8086/skills/` 目录加载

### memory

持久化记忆存储：

- `MemoryEntry` — 记忆条目 record（id, category, content, createdAt, updatedAt）（包：`uct8086.ai.memory`）
- `MemoryStore` — 记忆存储接口
- `FileMemoryStore` — 基于 MEMORY.md 文件实现，内存索引 + 文件持久化

### tasks

后台任务管理：

- `BackgroundTask` — 后台任务 record（状态机：PENDING → RUNNING → COMPLETED/FAILED/CANCELLED）（包：`uct8086.ai.tasks`）
- `TaskManager` — 任务创建、异步执行、状态追踪、取消

### coordinator

多 Agent 协作：

- `Subagent` — 子 Agent record（id, name, role, systemPrompt, status）（包：`uct8086.ai.coordinator`）
- `AgentCoordinator` — 子 Agent 生成、任务委派
- `TeamRegistry` — Agent 团队注册表

### mcp

MCP（Model Context Protocol）客户端集成：

- `McpClientService` — 连接 MCP 服务器、列出工具、调用工具、读取资源

### app

Spring Boot 应用入口：

- `AiApplication` — 主启动类
- `HarnessController` — REST API 控制器，暴露所有子系统

## 配置参考

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `uct8086.ai.permission-mode` | `DEFAULT` | 权限模式 |
| `uct8086.ai.max-turns` | `50` | Agent Loop 最大迭代次数 |
| `uct8086.ai.retry-enabled` | `true` | 是否启用 API 重试 |
| `uct8086.ai.max-retries` | `3` | 最大重试次数 |
| `uct8086.ai.retry-delay-ms` | `1000` | 重试初始延迟（毫秒） |
| `uct8086.ai.parallel-tool-execution` | `true` | 并行工具执行 |
| `uct8086.ai.context-compression` | `true` | 上下文压缩 |
| `uct8086.ai.compression-threshold` | `100000` | 压缩阈值（token） |
| `uct8086.ai.working-directory` | `${user.dir}` | 工作目录 |
| `uct8086.ai.model` | (null) | 模型覆盖 |
| `uct8086.ai.temperature` | `0.7` | 温度参数 |
| `uct8086.ai.system-prompt` | (null) | 自定义系统提示（null = 默认） |
| `server.port` | `9081` | 服务端口 |
| `spring.ai.openai.embedding.enabled` | `false` | DeepSeek 不支持 Embedding，必须关闭 |
| `spring.ai.ollama.embedding.options.model` | `bge-m3` | Ollama Embedding 模型（1024 维） |
| `spring.ai.ollama.chat.enabled` | `false` | Ollama 不用于 Chat，只用 Embedding |
| `pgvector.datasource.url` | `jdbc:postgresql://localhost:5432/postgres` | PostgreSQL 连接（向量存储） |

## License

本项目基于 [Apache License 2.0](./LICENSE) 开源协议。
