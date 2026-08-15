# 项目结构详情

`uct8086-ai` 各模块的类职责说明。

## common

公共枚举、模型和异常定义：

- **枚举**: `AgentRole`、`HookPhase`、`PermissionDecision`、`PermissionMode`、`TaskStatus`、`ToolCategory`
- **模型**: `AgentMessage`、`HookContext`、`HookDefinition`、`HookResult`、`PathRule`、`PermissionResult`、`SessionInfo`、`TokenUsage`、`ToolDescriptor`、`ToolExecutionContext`、`ToolResult`（包：`uct8086.ai.common.model`）
- **异常**: `Uct8086Exception`、`PermissionDeniedException`、`SkillLoadException`、`ToolExecutionException`、`CostLimitExceededException`

## auth

认证与授权（Spring Security）：

- `UserEntity` / `RoleEntity` — 用户、角色实体（MySQL `auth_user`/`auth_role`）
- `UserMapper` / `RoleMapper` — MyBatis-Plus Mapper
- `AuthService` / `AuthTokenService` / `AuthController` — 登录、Token 签发
- `AuthTokenFilter` — Cookie Token 认证过滤器
- `SecurityConfig` — 安全配置（路径权限、放行规则）
- `CurrentUser` — 当前用户上下文（基于 ThreadLocal）

## persistence

持久化层（MyBatis-Plus）：

- `SessionEntity` / `MessageEntity` — 会话、消息实体
- `SessionMapper` / `MessageMapper` — 分页查询、增量查询

## core

核心引擎和子系统：

- **engine** — `AgentEngine`（Agent Loop）、`AgentLoopResult`、`HarnessToolCallbackAdapter`（Spring AI 桥接）（包：`uct8086.ai.core.engine`）
- **tool** — `ToolRegistry`、`HarnessTool` 接口、`AbstractTool`、`ToolExecutionService` 管线
- **tools** — 内置工具：`BashTool`、`FileReadTool`、`FileWriteTool`、`GlobTool`、`GrepTool`
- **permission** — `PermissionChecker` 接口、`DefaultPermissionChecker`（四级安全模式 + 路径规则 + 危险命令拦截）
- **hook** — `HookManager`、`ToolHook` 接口（PreToolUse/PostToolUse 生命周期）
- **prompt** — `PromptAssembler`（系统提示组装）
- **session** — `SessionManager`（会话管理 + 消息历史，Redis ZSET + meta 缓存，消息缓存限 100 条）
- **cost** — `CostTracker`（Token 用量与成本追踪，MySQL 明细 + 按用户配额熔断）
- **command** — `CommandRegistry`、`HarnessCommand` 接口（Slash 命令系统）
- **config** — `HarnessProperties`（`uct8086.ai.*` 配置）、`HarnessCoreAutoConfiguration`（自动注册工具）

## skills

技能加载与注册系统：

- `Skill` — 技能 record（name, description, content, sourcePath, metadata）（包：`uct8086.ai.skills`）
- `SkillLoader` — 从文件系统加载 Markdown 技能，解析 YAML frontmatter
- `SkillRegistry` — 技能注册表：系统技能（项目目录）+ 用户技能（MySQL `harness_skill` 表）
- `SkillEntity` / `SkillMapper` — 用户技能持久化

## memory

持久化记忆存储 + 向量检索 + 自动总结：

- `MemoryEntry` — 记忆条目 record（id, category, content, createdAt, updatedAt）（包：`uct8086.ai.memory`）
- `MemoryStore` — 记忆存储接口
- `MySqlMemoryStore` — 基于 MySQL `harness_memory` 表（真相源）
- `MemoryVectorService` — 记忆向量化写入 pgvector，按 userId 相关检索
- `MemoryConsolidationService` — 定时批量自动总结用户偏好/事实（`@Scheduled` + Redis 水位线）
- `FileMemoryStore` — 旧的文件实现（已弃用，不再注册为 Bean）

## tasks

后台任务管理（分布式）：

- `BackgroundTask` — 后台任务 record（状态机：PENDING → RUNNING → COMPLETED/FAILED/CANCELLED）（包：`uct8086.ai.tasks`）
- `TaskManager` — 基于 **Redis Stream + 消费组** 的分布式任务（任务状态存 Redis Hash，跨实例共享、重启可恢复）

## coordinator

多 Agent 协作：

- `Subagent` — 子 Agent record（id, name, role, systemPrompt, status）（包：`uct8086.ai.coordinator`）
- `AgentCoordinator` — 子 Agent 生成、任务委派
- `TeamRegistry` — Agent 团队注册表

## mcp

MCP（Model Context Protocol）客户端集成：

- `McpClientService` — 连接 MCP 服务器、列出工具、调用工具、读取资源
- `McpConnectionManager` — MCP 连接管理（懒加载自动连接）
- `McpConfigManager` — MCP 服务器配置管理
- `SyncMcpToolCallback` — Spring AI ToolCallback 适配

## api

REST API 层：

- `HarnessController` — REST API 控制器，暴露所有子系统
- `GlobalExceptionHandler` — 全局异常处理（`CostLimitExceededException` → 429 等）

## metrics

可观测性：

- `ChatMetrics` — 对话指标（请求数、Token、耗时），接入 Actuator/Prometheus

## app

Spring Boot 应用入口：

- `AiApplication` — 主启动类（含 `@EnableScheduling`）
