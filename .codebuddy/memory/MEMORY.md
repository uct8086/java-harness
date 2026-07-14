# 项目长期记忆 (java-harness)

## 命名约定
- 包名/品牌已从 `oah-ai`(Open Agent Harness) 重命名为 **`uct8086-ai`**。
- Java 包根：`uct8086.ai`（Maven `groupId: uct8086.ai`，`artifactId: uct8086-ai`）。
- 启动类：`uct8086.ai.AiApplication`（非 `Uct8086AiApplication`）。
- 配置前缀：`uct8086.ai`（`application.yml` 顶层键 `uct8086:` + `ai:`）。
- 异常基类：`Uct8086Exception`（原 `OahException`）。
- 运行时目录：`.uct8086`（skills、MEMORY.md 等）。

## 子项目
- `c:\Workspace\uct8086-ai-web`：前端 Web 界面（Vue3 + Vite），与 `java-harness` 同级，对接 `HarnessController` 的 `/api`（端口 9081）。Vite 代理 `/api`→`http://localhost:9081` 规避跨域。运行：`npm install && npm run dev`（需 Node 18+）。
- 后端无 CORS 配置，前端依赖 Vite 代理或由后端静态托管 `dist/`。
- 后端 IntelliJ 调试曾因 `uct8086-ai.iml` 缺失导致 classpath 仅有 `idea_rt.jar`、主类找不到；需 Maven 重新导入（建议勾选 Work offline，因 spring-boot 4.0.0/spring-ai 2.0.0 为非标准版本）。

## 开发环境
- 用户机器：Windows，使用 **nvm-windows** 管理 Node.js，Node 已安装（需 18+）。新终端若 `node -v` 无反应，先 `nvm use <版本>`。

## 运行时依赖（后端 java-harness）
- 项目已改为 **MySQL 持久化 + Redis 缓存** 架构。
  - MySQL：`localhost:3306`，库 `uct8086_ai`（需先手动建库，`schema.sql` 只建表 `harness_session`/`harness_message`），账号 `root` / 密码 `root.2026`（见 `application.yml` 的 `MYSQL_*`）。
  - Redis：`localhost:6379`，无密码（`REDIS_PASSWORD` 空）。Lettuce 懒连接，不启动不影响应用启动，但运行时会话操作需 Redis（`SessionManager` 用 `StringRedisTemplate` 缓存会话列表/消息，`invalidateListCache` 无 try/catch）。
  - `spring.sql.init.mode: always` 启动时执行 `schema.sql`，需要 MySQL 在线。
- `SessionManager` 现注入 `SessionRepository`/`MessageRepository`(Spring Data JDBC) + `StringRedisTemplate` + `ObjectMapper`；`ConversationSession` 改为不可变元数据类。`uct8086.ai.config.RedisConfig` 提供 `RedisTemplate<String,Object>`。
- 前端 `uct8086-ai-web` 的“对话历史”气泡流：发送后直接从 `AgentLoopResult.response` 追加 assistant 气泡；切换会话用 `GET /api/sessions/{id}/messages` 拉取（该接口现在由 MySQL 提供）。
