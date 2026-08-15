# Web 前端

`web/` 目录是 `uct8086-ai-web` 前端项目，基于 **Vue 3 + Vite 5** 构建，作为 java-harness 后端的图形化管理界面。

## 技术栈

| 组件 | 版本 | 说明 |
|------|------|------|
| Vue | ^3.4 | 组合式 API（`<script setup>`） |
| Vite | ^5.2 | 开发服务器 + 构建工具 |
| Vue Router | ^4.3 | SPA 路由 |
| Axios | ^1.6 | HTTP 客户端 |
| marked + DOMPurify | — | Markdown 渲染 |

## 功能页面

| 路由 | 对接 API | 功能 |
|------|----------|------|
| `/login` | `POST /api/auth/login` | 登录（Cookie Token 认证） |
| `/` (ChatView) | `POST /api/chat/stream`, `GET /api/sessions/{id}/messages` | 对话界面：SSE 流式、工具调用记录、Token 消耗 |
| `/sessions` | `GET/POST/DELETE /api/sessions` | 会话增删查（分页） |
| `/tools` | `GET /api/tools` | 工具注册表浏览 |
| `/skills` | `GET/POST /api/skills` | 技能浏览与新增（用户技能存 MySQL） |
| `/memory` | `GET/PUT/DELETE /api/memory`, `POST /api/memory/consolidate` | 记忆查看/编辑/删除 + 手动触发总结 |
| `/tasks` | `GET/DELETE /api/tasks` | 后台任务监控 |
| `/knowledge` | `POST /api/knowledge/ingest`, `GET /api/knowledge/search` | RAG 知识库摄入与搜索 |
| `/settings` | `GET/PUT /api/permission/mode`, `GET /api/cost/total` | 权限模式与费用统计 |

## 启动

**前置条件：** Node.js 18+（Windows 建议用 nvm-windows 管理版本）

```bash
# 进入 web 目录
cd web

# 安装依赖
npm install

# 启动开发服务器（端口 5173）
npm run dev
```

访问 `http://localhost:5173`。Vite 自动将 `/api` 请求代理到 `http://localhost:9081`（后端），开发环境无跨域问题。

## 生产构建

```bash
cd web
npm run build     # 输出到 dist/
```

`dist/` 目录可交由 Spring Boot 静态托管，实现前后端一体部署。

## 目录结构

- **路由** — 8 个页面：对话、会话、工具、技能、记忆、知识库、任务、设置
- **api/client.js** — Axios 封装，所有请求走 `/api` 代理到后端 9081 端口
- **views/** — 页面组件（`ChatView` 为核心对话页，`MemoryView` 为记忆管理页）
- **components/** — `Markdown.vue` 渲染组件
