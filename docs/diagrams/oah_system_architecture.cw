# session_id: bc15364c-f35a-4c1c-8491-9b1322b6c082
classes: {
  zone_1: {
    style: {
      fill: "#F0F5FA"
      stroke: "#4185BF"
      font-color: "#333333"
      border-radius: 8
    }
  }
  zone_2: {
    style: {
      fill: "#F2F7FB"
      stroke: "#4185BF"
      font-color: "#333333"
      border-radius: 8
    }
  }
  zone_3: {
    style: {
      fill: "#F6F9FC"
      stroke: "#4185BF"
      font-color: "#333333"
      border-radius: 8
    }
  }
  zone_4: {
    style: {
      fill: "#F9FBFD"
      stroke: "#4185BF"
      font-color: "#333333"
      border-radius: 8
    }
  }
  zone_5: {
    style: {
      fill: "#FCFDFE"
      stroke: "#4185BF"
      font-color: "#333333"
      border-radius: 8
    }
  }
  entity: {
    style: {
      fill: "#FFFFFF"
      stroke: "#1F2937"
      font-color: "#333333"
      border-radius: 6
      shadow: true
    }
  }
  signal: {
    style: {
      fill: transparent
      font-color: "#6B7280"
    }
  }
}

# UCT8086-AI (OAH) 宏观分层系统架构图
# 布局：自上而下分层，包容式拓扑

direction: down

# ============================================================
# 1. 接入层 (Access Layer)
# ============================================================
access_layer: 接入层 {
  class: zone_1

  vue_ui: Vue3 Web UI {
    class: entity
    label: |`md
      **Vue3 Web UI**
      前端 SPA
      对话界面
    `|
  }

  rest_api: REST API {
    class: entity
    label: |`md
      **HarnessController**
      chat / chat/stream(SSE)
      sessions / tools / skills
      memory / cost / mcp / knowledge
    `|
    tooltip: |`md
      **HTTP 端点**
      - chat / chat/stream(SSE)
      - sessions / tools / skills
      - memory / cost / mcp / knowledge
      全部子系统 HTTP 端点
    `|
  }

  auth: 认证授权 {
    class: entity
    label: |`md
      **Spring Security + Cookie Token**
      ROLE_USER / ROLE_ADMIN
      全功能按用户隔离
    `|
  }
}

# ============================================================
# 2. 核心引擎层 (Core Engine Layer)
# ============================================================
engine_layer: 核心引擎层 {
  class: zone_2

  agent_engine: AgentEngine {
    class: entity
    label: |`md
      **Agent Loop 核心**
      组装系统提示 → 注入技能与记忆
      → RAG 检索 → 调用 LLM
      → 驱动工具循环 → SSE 流式输出
    `|
    tooltip: |`md
      **Agent Loop 执行链路**
      ① CostTracker 配额熔断检查
      ② 注入系统/用户技能 + 相关记忆 top5
      ③ RAG 语义检索（pgvector）
      ④ 加载 Redis 历史消息（最近N条）
      ⑤ Spring AI ChatClient 调用 DeepSeek
      ⑥ HarnessToolCallingAdvisor 驱动工具循环
      ⑦ CostTracker 记账 + ChatMetrics 打点
      ⑧ SSE 流式返回 token/tool/done 事件
    `|
  }

  tool_pipeline: 工具执行管线 {
    class: entity
    label: |`md
      **ToolExecutionService**
      权限检查 → PreToolUse Hook
      → 执行 → PostToolUse Hook
    `|
    tooltip: |`md
      **执行管线**
      ① 权限检查（DefaultPermissionChecker）
      ② PreToolUse Hook
      ③ 执行工具（bash/read_file/write_file/glob/grep）
      ④ PostToolUse Hook
      **由 AgentEngine 驱动**
    `|
  }

  builtin_tools: 内置工具 {
    class: entity
    label: |`md
      bash / read_file
      write_file / glob / grep
    `|
  }

  permission: 权限系统 {
    class: entity
    label: |`md
      **DefaultPermissionChecker**
      DEFAULT / AUTO / PLAN_MODE / READ_ONLY
      危险命令拦截
    `|
  }
}

# ============================================================
# 3. 能力子系统层 (Capability Subsystem Layer)
# ============================================================
capability_layer: 能力子系统层 {
  class: zone_3

  skill_sys: 技能系统 {
    class: entity
    label: |`md
      **SkillLoader + SkillRegistry**
      系统技能（代码目录）
      用户技能（持久化 MySQL）
    `|
  }

  memory_sys: 记忆系统 {
    class: entity
    label: |`md
      **MySqlMemoryStore**（真相源）
      **MemoryVectorService**（向量检索）
      **MemoryConsolidationService**（定时总结）
    `|
  }

  rag: RAG 知识库 {
    class: entity
    label: |`md
      **pgvector 语义检索**
      注入系统提示
    `|
  }

  mcp_client: MCP 客户端 {
    class: entity
    label: |`md
      **McpConnectionManager**
      接入外部 MCP 服务器工具
    `|
    tooltip: |`md
      通过 McpConnectionManager 接入
      外部 MCP 服务器提供的工具，
      注入到 AgentEngine 工具循环中
    `|
  }

  task_sys: 任务系统 {
    class: entity
    label: |`md
      **TaskManager**
      Redis Stream 分布式任务
    `|
  }

  agent_coord: 多 Agent 协调 {
    class: entity
    label: |`md
      **AgentCoordinator**
      ⚠ 占位未启用
    `|
    style.opacity: 0.4
    tooltip: |`md
      **预留接口**
      当前未启用，后续版本接入
      多 Agent 协调能力
    `|
  }

  cost_tracker: 成本追踪 {
    class: entity
    label: |`md
      **CostTracker**
      Token 用量记账
      按用户配额熔断
    `|
  }

  metrics: 指标监控 {
    class: entity
    label: |`md
      **ChatMetrics**
      Actuator / Prometheus 打点
    `|
  }
}

# ============================================================
# 4. 数据存储层 (Data Storage Layer)
# ============================================================
storage_layer: 数据存储层 {
  class: zone_4

  mysql: MySQL {
    class: entity
    label: |`md
      **主数据源**
      用户角色 / 会话消息
      长期记忆 / 用户技能 / 成本明细
    `|
  }

  redis: Redis {
    class: entity
    label: |`md
      会话缓存 ZSET
      Task Stream
      熔断标记
    `|
  }

  pgvector: PostgreSQL + pgvector {
    class: entity
    label: |`md
      **向量存储**
      知识库文档 + 记忆向量
      1024 维
    `|
  }
}

# ============================================================
# 5. 外部依赖层 (External Dependency Layer)
# ============================================================
external_layer: 外部依赖层 {
  class: zone_5

  deepseek: DeepSeek API {
    class: entity
    label: |`md
      **Chat 生成**
      deepseek-v4-pro 模型
      OpenAI 兼容协议
    `|
  }

  ollama: Ollama bge-m3 {
    class: entity
    label: |`md
      **本地 Embedding**
      文本 → 1024 维中文向量
    `|
  }

  external_mcp: 外部 MCP 服务器 {
    class: entity
    label: |`md
      用户配置的
      Streamable HTTP 工具源
    `|
  }
}

# ============================================================
# 核心数据流 (Agent Loop 主链路)
# ============================================================

# 用户 → 接入层
access_layer.(vue_ui -> rest_api): 用户 Prompt

# 接入层 → 核心引擎（主链路）
access_layer.rest_api -> engine_layer.agent_engine: ① 请求进入

# Agent Loop 核心链路（精简为关键路径）
engine_layer.agent_engine -> external_layer.deepseek: ⑤ 调用 LLM
external_layer.deepseek -> engine_layer.agent_engine: 工具调用请求

# SSE 流式返回
engine_layer.agent_engine -> access_layer.rest_api: ⑧ SSE 流式返回
access_layer.(rest_api -> vue_ui): token / tool / done 事件

# 能力子系统 → 数据存储（关键持久化路径）
capability_layer.skill_sys -> storage_layer.mysql: 用户技能持久化
capability_layer.memory_sys -> storage_layer.mysql: 长期记忆真相源
capability_layer.memory_sys -> storage_layer.pgvector: 记忆向量
capability_layer.rag -> storage_layer.pgvector: 知识库向量检索
capability_layer.task_sys -> storage_layer.redis: Task Stream
capability_layer.cost_tracker -> storage_layer.mysql: 成本明细

# 外部依赖
external_layer.ollama -> storage_layer.pgvector: 生成 1024 维向量
external_layer.external_mcp -> capability_layer.mcp_client: Streamable HTTP 工具

# 占位未启用（弱化连线）
capability_layer.agent_coord -> engine_layer.agent_engine: 预留接口 {
  style.stroke-dash: 5
  style.opacity: 0.3
}