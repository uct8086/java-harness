# 配置参考

`uct8086-ai` 的全部配置项说明。配置位于 `src/main/resources/application.yml`，前缀 `uct8086.ai`。

## 核心配置

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `uct8086.ai.permission-mode` | `DEFAULT` | 权限模式 |
| `uct8086.ai.max-turns` | `50` | Agent Loop 最大迭代次数 |
| `uct8086.ai.max-history-messages` | `10` | 注入 Prompt 的历史消息最大条数 |
| `uct8086.ai.mcp-request-timeout-seconds` | `30` | MCP 工具调用超时（秒） |
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

## 成本配额 / 熔断

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `uct8086.ai.cost-alert-enabled` | `true` | 成本告警开关 |
| `uct8086.ai.session-cost-warn-threshold` | `5.0` | 会话成本告警阈值（元） |
| `uct8086.ai.session-cost-hard-limit` | `0.0` | 会话成本硬上限（0=禁用） |
| `uct8086.ai.user-cost-hard-limit` | `0.01` | 用户总成本硬上限，超限熔断（0=禁用） |
| `uct8086.ai.cost-breaker-enabled` | `true` | 成本熔断开关 |

## 记忆自动总结

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `uct8086.ai.memory.consolidation-cron` | `0 0 * * * *` | 记忆自动总结 cron（默认每小时） |

## 服务与模型

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `server.port` | `9081` | 服务端口 |
| `spring.ai.openai.timeout` | `300s` | OpenAI(DeepSeek) 请求超时 |
| `spring.ai.openai.embedding.enabled` | `false` | DeepSeek 不支持 Embedding，必须关闭 |
| `spring.ai.ollama.embedding.options.model` | `bge-m3` | Ollama Embedding 模型（1024 维） |
| `spring.ai.ollama.chat.enabled` | `false` | Ollama 不用于 Chat，只用 Embedding |
| `pgvector.datasource.url` | `jdbc:postgresql://localhost:5432/postgres` | PostgreSQL 连接（向量存储） |
