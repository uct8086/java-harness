# UCT8086-AI: Open Agent Harness

> 基于 Java 21 + Spring Boot 4.0 + Spring AI 2.0 构建的 AI Agent Harness 基础设施，提供完整的 Agent 工程化能力。

## 项目简介

UCT8086-AI（Open Agent Harness）是一个用 Java 技术栈实现的 AI Agent 管理框架，目标是提供类似 OpenHarness 的全套 Harness 流程管控能力，涵盖工具管理、权限控制、Hook 机制、Agent 引擎、Prompt 组装、会话管理、成本追踪等完整 AI 工程化能力。

### 核心能力

| 能力 | 说明 |
|------|------|
| **Agent Loop** | 查询 → 模型调用 → 工具执行 → 结果回传 → 循环直到完成，支持 **SSE 流式** token 级输出 |
| **Authentication / Authorization** | Spring Security + Cookie Token 登录，用户/角色（ROLE_USER/ROLE_ADMIN），**全功能按用户隔离** |
| **Tool Registry** | 工具注册、发现、分类管理，支持动态注册插件和 MCP 工具 |
| **Permission System** | 四级安全模式（DEFAULT / AUTO / PLAN_MODE / READ_ONLY），路径级规则控制，危险命令拦截 |
| **Hook System** | PreToolUse / PostToolUse 生命周期钩子，支持阻止执行或修改结果 |
| **Skill System** | 系统技能（代码目录 Markdown）+ 用户技能（MySQL `harness_skill` 表） |
| **Memory System** | 跨会话记忆，**MySQL 持久化 + 系统自动总结 + pgvector 相关检索** |
| **Session Management** | 会话创建、恢复、历史记录（Redis ZSET + meta 缓存），消息缓存限 100 条 |
| **Cost Tracking** | Token 用量与成本追踪（MySQL `cost_usage` 明细），**按用户配额熔断** |
| **Multi-Agent Coordination** | 子 Agent 生成、团队管理、任务委派（Redis Stream 分布式任务） |
| **RAG Knowledge Base** | 基于 pgvector + Ollama 的语义检索，与记忆按 userId/type 隔离 |
| **MCP Client** | Model Context Protocol 客户端集成（Streamable HTTP，超时 + 自动连接） |
| **Metrics** | Actuator + Prometheus 指标（请求、Token、耗时） |
| **REST API** | 全功能 HTTP API，暴露所有子系统 |

## 技术栈

| 组件 | 版本 |
|------|------|
| Java | 21 |
| Spring Boot | 4.0.0 |
| Spring AI | 2.0.0 |
| Spring Security | 7.x（认证授权） |
| MyBatis-Plus | 3.5.x（`mybatis-plus-spring-boot4-starter`） |
| MySQL | 8.0（会话/消息/记忆/技能/成本/用户角色持久化） |
| Redis | 7.x（会话缓存、Task Stream、熔断标记） |
| PostgreSQL + pgvector | 17（向量存储：知识库 + 记忆） |
| Ollama | `bge-m3` 模型（本地 Embedding） |
| Micrometer + Prometheus | Actuator 指标 |
| 构建工具 | Maven |

## 模块结构

```
uct8086-ai/
├── pom.xml                          # 父 POM（模块管理 + 依赖版本）
├── common/                   # 公共模块：枚举、模型、异常
├── auth/                     # 认证授权：实体、Mapper、Service、Controller、Security 配置
├── persistence/              # 持久化：会话/消息 Entity + Mapper（MyBatis-Plus）
├── core/                     # 核心模块：Agent 引擎、工具、权限、Hook、会话、成本、Prompt
├── skills/                   # 技能模块：系统技能加载 + 用户技能（MySQL）
├── memory/                   # 记忆模块：MySQL 存储 + 向量检索 + 自动总结
├── tasks/                    # 任务模块：Redis Stream 分布式任务
├── coordinator/              # 协调模块：多 Agent 协作
├── mcp/                      # MCP 模块：Model Context Protocol 客户端
├── metrics/                  # 指标：ChatMetrics（Actuator/Prometheus）
├── config/                   # 配置：RedisConfig、PgVectorConfig 等
├── api/                      # REST API：HarnessController、全局异常处理
└── web/                      # 前端模块：Vue 3 + Vite Web UI
```


## 架构设计

### Agent Loop 流程

```
用户输入 Prompt
      │
      ▼
┌─────────────────────────────┐
│  buildSystemPrompt (AgentEngine)  │  组装上下文：基础系统提示 + 系统/用户技能
│                                  │  + 相关记忆（pgvector 检索 top5）+ RAG 文档
└──────┬───────────────────────┘
       │
       ▼
┌─────────────┐
│ SessionManager │  读取最近 10 条历史消息（Redis 消息缓存，限 100 条）
└──────┬──────┘
       │
       ▼
┌─────────────┐
│  ChatClient  │  调用 Spring AI ChatClient，携带历史消息 + ToolCallback
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
| `DEFAULT` | 危险命令拦截 + 路径规则（写操作审批机制待实现） |
| `AUTO` | 自动允许所有操作（沙箱环境） |
| `PLAN_MODE` | 阻止所有写操作（审查模式） |
| `READ_ONLY` | 仅允许只读操作 |

> **注意**：当前 `DEFAULT` 模式下，危险命令检测仅对 `bash`（SHELL 类）工具生效，文件工具内容不会被误判。写操作的「用户确认审批」机制尚未实现（`askUser` 为 TODO），属已知待办。

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

> **记忆检索同样走 pgvector**：用户的长期记忆（`harness_memory` 表）在写入时也同步 embedding 到同一个 pgvector，检索时通过 `metadata.type=memory` + `metadata.userId` 与知识库文档隔离，只注入当前用户的相关记忆（top-5）。

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
- MySQL 8.x（外部实例 `10.94.77.17:3506`，会话/消息持久化）
- Redis 7.x（会话缓存）
- PostgreSQL 16+ + pgvector 扩展（向量存储）
- Ollama（本地 Embedding 模型）

### 安装依赖服务

项目根目录提供了 `docker-compose.yml`，一键启动 Redis、PostgreSQL+pgvector（MySQL 使用外部实例，不包含在内）：

```powershell
# 启动所有服务（后台运行）
docker compose up -d

# 查看服务状态
docker compose ps

# 查看日志
docker compose logs -f

# 停止服务
docker compose down

# 停止并清理数据卷（重置数据库）
docker compose down -v
```

各服务端口与凭证（与 `application.yml` 默认值一致）：

| 服务 | 端口 | 用户名 | 密码 |
|------|------|--------|------|
| Redis 7 | 6379 | — | 无 |
| PostgreSQL 17 + pgvector | 5432 | postgres | 321432 |

> **说明：** MySQL 使用外部实例 `10.94.77.17:3506`（user: `root`，password: `root.2026`），不在 Docker Compose 中管理。数据库 `uct8086_ai` 需预先创建，表结构通过手动执行 `docker/mysql/init/init.sql` 创建（应用启动不再自动建表）。
> PostgreSQL 的 `vector`、`hstore`、`uuid-ossp` 扩展由 `docker/postgres/init/01-extensions.sql` 自动安装。
> `vector_store` 表由 `PgVectorStore` 启动时自动创建（1024 维）。

**MySQL 数据库初始化（手动执行一次）：**

```bash
# 1. 创建数据库（如尚未创建）
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS uct8086_ai DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"

# 2. 执行建表脚本（幂等，可重复执行）
mysql -u root -p uct8086_ai < docker/mysql/init/init.sql
```

脚本会创建以下表，并初始化默认角色（`ROLE_USER`/`ROLE_ADMIN`）与默认管理员账号 **`admin / admin123`**（首次部署后请尽快修改密码）：

- `auth_user`、`auth_role`、`auth_user_role` — 用户、角色及关联
- `harness_session`、`harness_message` — 会话与消息
- `harness_memory` — 用户长期记忆（MySQL 真相源）
- `cost_usage` — 成本使用明细（配额熔断依据）
- `harness_skill` — 用户自定义技能

**4. Ollama**（本地 Embedding，需单独安装）：
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

应用启动后，REST API 在 `http://localhost:9081` 可用。

> **Web 前端**：`web/` 目录的 Vue 3 前端项目（页面、启动、构建）详见 [docs/web-frontend.md](./docs/web-frontend.md)。


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

> **项目结构详情**：各模块类职责说明详见 [docs/project-structure.md](./docs/project-structure.md)。
>
> **配置参考**：全部配置项说明详见 [docs/configuration.md](./docs/configuration.md)。

## License

本项目基于 [Apache License 2.0](./LICENSE) 开源协议。
