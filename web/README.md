# uct8086-ai-web

UCT8086-AI Agent Harness 的 Web 界面（Vue 3 + Vite），对接 `java-harness` 后端的 `HarnessController` REST API（`/api`）。

## 前置条件

- Node.js 18+（含 npm）
- 后端 `java-harness` 已启动并在 `http://localhost:9081` 提供服务

## 安装与运行

```bash
cd uct8086-ai-web
npm install
npm run dev
```

开发服务器默认在 `http://localhost:5173`。

## 与后端的联调

开发时无需处理跨域：`vite.config.js` 已配置代理，将 `/api` 转发到 `http://localhost:9081`。
直接启动后端 Spring Boot 应用，再 `npm run dev` 即可。

## 构建生产包

```bash
npm run build      # 输出到 dist/
npm run preview    # 本地预览构建产物
```

如需让 Spring Boot 直接托管前端，把 `dist/` 内容放到后端静态资源目录，并保证所有请求前缀为 `/api`。

## 功能页面

| 路由 | 对接接口 | 说明 |
|------|----------|------|
| `/` 对话 | `POST /api/chat`、`POST /api/chat-with-context`、`GET /api/sessions/{id}/messages` | 聊天气泡流（按会话持久化历史）、工具调用记录与 Token 消耗、代码块一键复制 |
| `/sessions` | `GET/POST/DELETE /api/sessions` | 会话的增删查 |
| `/tools` | `GET /api/tools` | 工具注册表 |
| `/skills` | `GET/POST /api/skills` | 技能加载与新增 |
| `/memory` | `GET/POST /api/memory`、`GET /api/memory/search` | 持久化记忆 |
| `/tasks` | `GET/DELETE /api/tasks` | 后台任务（运行中自动刷新） |
| `/settings` | `GET/PUT /api/permission/mode`、`GET /api/cost/total` | 权限模式与累计费用 |

## 技术栈

- Vue 3（`<script setup>` 组合式 API）
- Vue Router 4
- Vite 5（含 `/api` 代理）
- Axios
