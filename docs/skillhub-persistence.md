# SkillHub 技能持久化到项目 MySQL

## 背景

`SkillHub`（`skillhub` CLI）默认只把技能安装到**本地** `~/.claude/skills/`，项目 OAH（UCT8086-AI）**感知不到**。

项目 OAH 有自己的技能系统：
- **系统技能**：代码目录 `.uct8086/skills/*.md`，启动时加载，全用户共享；
- **用户技能**：MySQL `harness_skill` 表，按用户隔离，经 `POST /api/skills` 接口写入，并在每次对话时注入 system prompt（"Your Skills"）。

因此，要让 SkillHub 安装的技能真正在项目对话中生效，必须把它**持久化到 MySQL**。

## 方案

脚本 `scripts/skillhub_persist.py` 把「读取本地技能 → 登录项目 → 写入 MySQL」串成一条龙：

1. 解析本地 `SKILL.md` 的 YAML frontmatter（`name` / `displayName` / `description` / `summary`）。
2. 登录项目 OAH（`POST /api/auth/login`），获取 `auth_token` Cookie。
3. 调用 `POST /api/skills` 持久化（后端按 `(user_id, name)` 幂等 upsert）。
4. 回读校验。

### 字段映射规则

| MySQL 字段 | 取值 |
|---|---|
| `user_id` | 由后端自动取当前登录用户（`CurrentUser.requireId()`） |
| `name` | `displayName` → `name` → 目录名（可 `--skill-name` 覆盖） |
| `description` | `description` → `summary`，并追加来源信息 `来源:SkillHub slug=xxx v1.2.0`（可 `--no-source` 关闭） |
| `content` | `SKILL.md` **完整内容（含 frontmatter）**（可 `--strip-frontmatter` 去掉 frontmatter） |

## 用法

```bash
# 第一步：SkillHub 安装到本地（照常）
skillhub install <slug> --dir "C:/Users/belie/.claude/skills"

# 第二步：持久化到项目 MySQL
python scripts/skillhub_persist.py --slug <slug> --skills-root "C:/Users/belie/.claude/skills"
```

也可以直接给 skill 目录：

```bash
python scripts/skillhub_persist.py --skill-dir "C:/Users/belie/.claude/skills/@user_bddf3fe6/<slug>"
```

### 可选参数

| 参数 | 默认 | 说明 |
|---|---|---|
| `--base-url` | `http://localhost:9081` | 项目 OAH 地址 |
| `--username` | `Cruise` | 登录用户名 |
| `--password` | `321432` | 登录密码 |
| `--skill-name` | — | 覆盖技能名 |
| `--description` | — | 覆盖技能描述 |
| `--no-source` | — | 不在 description 追加来源信息 |
| `--strip-frontmatter` | — | content 去掉 frontmatter（默认保留完整 frontmatter） |

## 验证记录

### 2026-08-16 初次验证

已将 SkillHub 技能「架构图一键生成」（`contextweave-interactive-architecture` v1.2.0）持久化到 MySQL（content 为去掉 frontmatter 的正文）：

| id | user_id | name | content 长度 |
|---|---|---|---|
| 2 | 3（Cruise） | 架构图一键生成 | 10981 字符 |

重复执行脚本为幂等（同名技能原地 update，不重复插入）。

### 2026-08-16 调整：content 保留完整 frontmatter

按需求将 content 改为保留**完整 frontmatter**（含 `name` / `slug` / `displayName` / `version` / `summary` / `license` / `description` / `metadata`）。重新执行后原地更新：

| id | user_id | name | content 长度 | content 开头 |
|---|---|---|---|---|
| 2 | 3（Cruise） | 架构图一键生成 | 11398 字符 | `---\nname: interactive-architecture-diagram\nslug: ...` |
