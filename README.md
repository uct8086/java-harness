# UCT8086-AI: Open Agent Harness

> A production-grade AI Agent infrastructure built with **Java 21 + Spring Boot 4.0 + Spring AI 2.0**, providing a complete Agent engineering toolkit.

## Overview

UCT8086-AI (Open Agent Harness) is an AI Agent management framework implemented in the Java stack. It delivers the full Harness workflow-governance pipeline inspired by OpenHarness — covering tool management, permission control, hook mechanisms, an Agent engine, prompt assembly, session management, cost tracking, and more — as a cohesive AI engineering platform.

### Core Capabilities

| Capability | Description |
|-----------|-------------|
| **Agent Loop** | Query → model call → tool execution → result loopback → iterate until done, with **SSE streaming** token-level output |
| **Authentication / Authorization** | Spring Security + Cookie Token login, user/role model (ROLE_USER/ROLE_ADMIN), **full per-user isolation** |
| **Tool Registry** | Tool registration, discovery, categorization; dynamic plugin and MCP tool registration |
| **Permission System** | Four security modes (DEFAULT / AUTO / PLAN_MODE / READ_ONLY), path-level rules, dangerous-command interception |
| **Hook System** | PreToolUse / PostToolUse lifecycle hooks; can block execution or mutate results |
| **Skill System** | System skills (Markdown in project dir) + user skills (MySQL `harness_skill` table) |
| **Memory System** | Cross-session memory: **MySQL persistence + auto-summarization + pgvector relevance retrieval** |
| **Session Management** | Session create/resume/history (Redis ZSET + meta cache), 100-message cache cap |
| **Cost Tracking** | Token usage & cost tracking (MySQL `cost_usage` ledger), **per-user quota circuit breaker** |
| **Multi-Agent Coordination** | LLM-driven dynamic agent orchestration: `agent`/`send_message`/`task_stop` primitives + LOCAL/COORDINATOR/SWARM topology + Redis Stream distributed dispatch |
| **RAG Knowledge Base** | Semantic retrieval via pgvector + Ollama, isolated by userId/type |
| **MCP Client** | Model Context Protocol client integration (Streamable HTTP, timeout + auto-connect) |
| **Metrics** | Actuator + Prometheus metrics (requests, tokens, latency) |
| **REST API** | Full-featured HTTP API exposing all subsystems |

## Tech Stack

| Component | Version |
|-----------|---------|
| Java | 21 |
| Spring Boot | 4.0.0 |
| Spring AI | 2.0.0 |
| Spring Security | 7.x (authn/authz) |
| MyBatis-Plus | 3.5.x (`mybatis-plus-spring-boot4-starter`) |
| MySQL | 8.0 (sessions/messages/memory/skills/cost/user-role persistence) |
| Redis | 7.x (session cache, task stream, breaker flags) |
| PostgreSQL + pgvector | 17 (vector store: knowledge base + memory) |
| Ollama | `bge-m3` model (local embedding) |
| Micrometer + Prometheus | Actuator metrics |
| Build tool | Maven |

## Module Structure

```
uct8086-ai/
├── pom.xml                  # Parent POM (module management + dependency versions)
├── common/                  # Shared: enums, models, exceptions
├── auth/                    # Auth: entities, mappers, services, controllers, security config
├── persistence/             # Persistence: session/message entities + mappers (MyBatis-Plus)
├── core/                    # Core: Agent engine, tools, permission, hooks, session, cost, prompt
├── skills/                  # Skills: system skill loading + user skills (MySQL)
├── memory/                  # Memory: MySQL storage + vector retrieval + auto-summarization
├── tasks/                   # Tasks: Redis Stream distributed tasks
├── coordinator/             # Coordinator: multi-agent collaboration
├── mcp/                     # MCP: Model Context Protocol client
├── metrics/                 # Metrics: ChatMetrics (Actuator/Prometheus)
├── config/                  # Config: RedisConfig, PgVectorConfig, etc.
├── api/                     # REST API: HarnessController, global exception handling
└── web/                     # Frontend: Vue 3 + Vite Web UI
```

## Architecture

### Agent Loop

```
User input Prompt
      │
      ▼
┌─────────────────────────────┐
│  buildSystemPrompt (AgentEngine)  │  Assemble context: base system prompt + system/user skills
│                                  │  + relevant memory (pgvector top-5) + RAG documents
└──────┬───────────────────────┘
       │
       ▼
┌─────────────┐
│ SessionManager │  Read last 10 history messages (Redis message cache, 100 cap)
└──────┬──────┘
       │
       ▼
┌─────────────┐
│  ChatClient  │  Call Spring AI ChatClient with history + ToolCallback
│  (Spring AI) │
└──────┬──────┘
       │ (model requests a tool call)
┌──────────────────────────────────────────┐
│        HarnessToolCallbackAdapter         │  Adapts HarnessTool → Spring AI ToolCallback
│                    │                      │
│        ┌───────────▼──────────┐           │
│        │ ToolExecutionService  │           │
│        │    (execution pipeline)│          │
│        │  1. Permission Check  │           │
│        │  2. PreToolUse Hook   │           │
│        │  3. Execute Tool      │           │
│        │  4. PostToolUse Hook  │           │
│        └──────────────────────┘           │
└──────────────────────────────────────────┘
       │
       ▼
┌─────────────┐
│ CostTracker  │  Record token usage
└─────────────┘
       │
       ▼
   Return result
```

### Tool Execution Pipeline

Every tool call passes through a full pipeline:

1. **Permission Check** — checks permission mode, path rules, dangerous commands
2. **PreToolUse Hook** — pre-execution hook, can block execution
3. **Execute Tool** — runs the tool logic
4. **PostToolUse Hook** — post-execution hook, can mutate the result

### Permission Modes

| Mode | Behavior |
|------|----------|
| `DEFAULT` | Dangerous-command interception + path rules (write approval mechanism pending) |
| `AUTO` | Auto-allow all operations (sandboxed environment) |
| `PLAN_MODE` | Block all write operations (review mode) |
| `READ_ONLY` | Read-only operations only |

### RAG Knowledge Base

The project integrates RAG (Retrieval-Augmented Generation) powered by **pgvector + Ollama**, automatically retrieving relevant documents and injecting them into the system prompt before every agent call.

```
User Prompt
    │
    ▼
AgentEngine.enrichWithRag()
    │
    ├──→ Ollama bge-m3 (local embedding) ──→ 1024-dim vector
    │
    ├──→ PgVectorStore.similaritySearch() ──→ PostgreSQL + pgvector cosine similarity
    │
    ▼
System Prompt + "\n\n## Relevant Documents\n" + retrieved docs
    │
    ▼
DeepSeek Chat API (generation)
```

| Component | Role | Notes |
|-----------|------|-------|
| **DeepSeek API** | Chat | Dialogue generation (OpenAI-compatible; DeepSeek has no embedding API) |
| **Ollama `bge-m3`** | Embedding | Local 1024-dim vectors; free; data stays on-prem |
| **PostgreSQL + pgvector** | Vector store | Cosine similarity + HNSW index |

> **Memory retrieval also uses pgvector**: long-term memories (`harness_memory` table) are embedded to the same pgvector store on write; retrieval filters by `metadata.type=memory` + `metadata.userId` to isolate from knowledge-base docs, injecting only the current user's relevant memories (top-5).

### Built-in Tools

| Tool | Category | Read-only | Description |
|------|----------|-----------|-------------|
| `bash` | SHELL | No | Execute shell commands with timeout control |
| `read_file` | FILE_IO | Yes | Read file contents with path resolution & truncation |
| `write_file` | FILE_IO | No | Write files, append mode supported |
| `glob` | FILE_IO | Yes | Find files by glob pattern |
| `grep` | SEARCH | Yes | Regex search over file contents |

## Quick Start

### Prerequisites

- JDK 21+
- Maven 3.8+
- MySQL 8.x (external instance `10.94.77.17:3506`, sessions/messages persistence)
- Redis 7.x (session cache)
- PostgreSQL 16+ with pgvector extension (vector store)
- Ollama (local embedding model)

### Install Dependency Services

The root `docker-compose.yml` starts Redis and PostgreSQL+pgvector (MySQL uses an external instance, not managed here):

```powershell
docker compose up -d        # start
docker compose ps           # status
docker compose logs -f      # logs
docker compose down         # stop
docker compose down -v      # stop + wipe data volumes
```

| Service | Port | Username | Password |
|---------|------|----------|----------|
| Redis 7 | 6379 | — | none |
| PostgreSQL 17 + pgvector | 5432 | postgres | 321432 |

**MySQL initialization (run once, manually):**

```bash
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS uct8086_ai DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
mysql -u root -p uct8086_ai < docker/mysql/init/init.sql
```

**Ollama** (local embedding, install separately):

```bash
# Download: https://ollama.com/download/windows
ollama pull bge-m3   # ~1.2GB Chinese embedding model
```

### Build

```bash
mvn clean compile
mvn clean package -DskipTests
```

### Configuration

API keys are injected via environment variables (never hardcoded, never committed):

```powershell
# PowerShell
$env:SPRING_AI_OPENAI_API_KEY="sk-your-deepseek-key"
```

```bash
# Linux / macOS / Git Bash
export SPRING_AI_OPENAI_API_KEY=sk-your-deepseek-key
```

### Run

```powershell
$env:SPRING_AI_OPENAI_API_KEY="sk-your-deepseek-key"; mvn spring-boot:run
```

The REST API is available at `http://localhost:9081`. See [docs/web-frontend.md](./docs/web-frontend.md) for the Vue 3 frontend.

## Extension Development

### Custom Tool

```java
@Component
public class MyCustomTool extends AbstractTool {
    public MyCustomTool() {
        super("my_tool",
              "Describe what this tool does and when the model should use it",
              ToolCategory.META,    // FILE_IO | SHELL | SEARCH | WEB | MCP | TASK | AGENT | META
              false);               // read-only?
    }

    @Override
    protected ToolResult doExecute(Map<String, Object> arguments,
                                   ToolExecutionContext context) throws Exception {
        String input = requireString(arguments, "input");
        return ToolResult.success("result");
    }
}
```

### Custom Hook

```java
@Component
public class LoggingHook implements ToolHook {
    @Override
    public HookDefinition getDefinition() {
        return new HookDefinition(
            "log-all",               // hook name
            HookPhase.PRE_TOOL_USE,  // PRE_TOOL_USE | POST_TOOL_USE
            "*",                     // matched tool name (wildcards supported)
            100                      // priority (lower runs first)
        );
    }

    @Override
    public HookResult onEvent(HookContext context) {
        return HookResult.continueExecution();
        // or: return HookResult.block("not allowed");
    }
}
```

### Custom Slash Command

```java
@Component
public class HelpCommand implements HarnessCommand {
    @Override
    public String getName() { return "help"; }
    @Override
    public String getDescription() { return "Show available commands"; }
    @Override
    public String execute(List<String> args, Map<String, Object> context) {
        return "Available commands: /help, /plan, /commit, ...";
    }
}
```

### Custom Skill

Create a Markdown file (e.g. `.uct8086/skills/git-guide.md`):

```markdown
---
name: git-guide
description: Git operations guide and best practices
---
# Git Guide
...
```

## Documentation

- [Project structure](./docs/project-structure.md) — class responsibilities per module
- [Configuration reference](./docs/configuration.md) — all config options
- [Production-readiness assessment](./docs/production-readiness-assessment.md) — concurrency/security gap analysis & fix log

## License

[Apache License 2.0](./LICENSE)
