#!/usr/bin/env python3
"""
skillhub_persist.py — 将 SkillHub 安装的技能持久化到项目 OAH 的 MySQL。

背景：
  SkillHub 默认只把技能装到本地 ~/.claude/skills/，项目 OAH(UCT8086-AI) 感知不到。
  项目 OAH 有自己的技能系统：用户技能存 MySQL `harness_skill` 表，经 `POST /api/skills`
  接口写入，并在对话时注入 system prompt（"Your Skills"）。

本脚本的作用：
  1. 读取本地已安装 skill 的 SKILL.md，解析 YAML frontmatter（name/displayName/description/summary）。
  2. 登录项目 OAH（POST /api/auth/login），获取 auth_token Cookie。
  3. 调用 POST /api/skills 持久化（后端按 (user_id, name) 幂等 upsert）。
  4. 输出结果并回读校验。

用法：
  # 方式一：直接给 skill 目录
  python scripts/skillhub_persist.py --skill-dir "C:/Users/belie/.claude/skills/@user_bddf3fe6/contextweave-interactive-architecture"

  # 方式二：给 slug + skills 根目录，自动定位
  python scripts/skillhub_persist.py --slug contextweave-interactive-architecture \
      --skills-root "C:/Users/belie/.claude/skills"

可选参数：
  --base-url    项目地址，默认 http://localhost:9081
  --username    登录用户名，默认 Cruise
  --password    登录密码，默认 321432
  --skill-name  覆盖技能名（默认取 SKILL.md 的 displayName，其次 name）
  --description 覆盖技能描述（默认取 SKILL.md 的 description，其次 summary）
  --no-source   不在 description 中追加来源信息
  --strip-frontmatter  content 去掉 frontmatter（默认保留完整 frontmatter）
"""

import argparse
import re
import sys
from pathlib import Path

import requests


def _configure_utf8_stdout() -> None:
    """Windows 控制台默认 GBK，强制 stdout/stderr 用 UTF-8，避免中文乱码。"""
    for stream in (sys.stdout, sys.stderr):
        if stream is not None and hasattr(stream, "reconfigure"):
            try:
                stream.reconfigure(encoding="utf-8")
            except Exception:
                pass


def parse_frontmatter(text: str) -> tuple[dict, str]:
    """解析 SKILL.md 的 YAML frontmatter，返回 (字段字典, 去掉 frontmatter 的正文)。"""
    if not text.startswith("---"):
        return {}, text.strip()

    end = text.find("\n---", 3)
    if end == -1:
        return {}, text.strip()

    fm_text = text[3:end].strip()
    body = text[end + 4:].strip()  # 跳过 "\n---\n"

    fields: dict[str, str] = {}
    for line in fm_text.splitlines():
        line = line.rstrip()
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        # 只解析顶层 key: value（缩进的子项、多行结构不解析）
        if line[0].isspace():
            continue
        m = re.match(r"^([A-Za-z_][A-Za-z0-9_-]*)\s*:\s*(.*)$", line)
        if m:
            fields[m.group(1)] = m.group(2).strip().strip("'\"")
    return fields, body


def resolve_skill_dir(skill_dir: str | None, slug: str | None, skills_root: str | None) -> Path:
    if skill_dir:
        return Path(skill_dir)
    if not slug:
        raise SystemExit("必须提供 --skill-dir 或 --slug")
    root = Path(skills_root or str(Path.home() / ".claude" / "skills"))
    if not root.exists():
        raise SystemExit(f"skills 根目录不存在: {root}")
    # 目录结构：<root>/@user_xxx/<slug>/SKILL.md
    for candidate in root.rglob("SKILL.md"):
        if candidate.parent.name == slug:
            return candidate.parent
    # 兜底：按目录名精确匹配
    for candidate in root.rglob(slug):
        if candidate.is_dir() and (candidate / "SKILL.md").exists():
            return candidate
    raise SystemExit(f"未在 {root} 下找到 slug={slug} 的 skill 目录")


def login(base_url: str, username: str, password: str):
    resp = requests.post(
        f"{base_url}/api/auth/login",
        json={"username": username, "password": password},
        timeout=10,
    )
    if resp.status_code != 200:
        raise SystemExit(f"登录失败（HTTP {resp.status_code}）：{resp.text}")
    data = resp.json()
    print(f"[login] 登录成功：{data.get('username')}（id={data.get('id')}）")
    return resp.cookies


def persist_skill(base_url: str, cookies, name: str, description: str, content: str) -> dict:
    resp = requests.post(
        f"{base_url}/api/skills",
        json={"name": name, "description": description, "content": content},
        cookies=cookies,
        timeout=30,
    )
    if resp.status_code != 200:
        raise SystemExit(f"持久化失败（HTTP {resp.status_code}）：{resp.text}")
    return resp.json()


def main() -> None:
    _configure_utf8_stdout()

    ap = argparse.ArgumentParser(description="将 SkillHub 安装的技能持久化到项目 OAH 的 MySQL")
    ap.add_argument("--skill-dir", help="本地 skill 目录（含 SKILL.md）")
    ap.add_argument("--slug", help="SkillHub slug，配合 --skills-root 自动定位目录")
    ap.add_argument("--skills-root", help="skills 根目录，默认 ~/.claude/skills")
    ap.add_argument("--base-url", default="http://localhost:9081")
    ap.add_argument("--username", default="Cruise")
    ap.add_argument("--password", default="321432")
    ap.add_argument("--skill-name", help="覆盖技能名")
    ap.add_argument("--description", help="覆盖技能描述")
    ap.add_argument("--no-source", action="store_true", help="不在 description 中追加来源信息")
    ap.add_argument("--strip-frontmatter", action="store_true", help="content 去掉 frontmatter（默认保留完整 frontmatter）")
    args = ap.parse_args()

    skill_dir = resolve_skill_dir(args.skill_dir, args.slug, args.skills_root)
    skill_md = skill_dir / "SKILL.md"
    if not skill_md.exists():
        raise SystemExit(f"未找到 SKILL.md：{skill_md}")

    raw = skill_md.read_text(encoding="utf-8")
    fields, body = parse_frontmatter(raw)

    name = args.skill_name or fields.get("displayName") or fields.get("name") or skill_dir.name
    description = args.description or fields.get("description") or fields.get("summary") or ""

    if not args.no_source:
        source = "SkillHub"
        slug = fields.get("slug") or skill_dir.name
        version = fields.get("version") or ""
        tag = f" | 来源:{source} slug={slug}"
        if version:
            tag += f" v{version}"
        description = f"{description}{tag}" if description else tag

    # 默认保留完整 frontmatter（含 name/slug/displayName/version/description/metadata 等）
    content = body if args.strip_frontmatter else raw

    print(f"[skill] 目录   : {skill_dir}")
    print(f"[skill] 名称   : {name}")
    print(f"[skill] 描述   : {description[:80]}{'...' if len(description) > 80 else ''}")
    print(f"[skill] 内容   : {len(content)} 字符（{'已去掉 frontmatter' if args.strip_frontmatter else '保留完整 frontmatter'}）")

    cookies = login(args.base_url, args.username, args.password)
    result = persist_skill(args.base_url, cookies, name, description, content)
    print(f"[persist] 已写入: name={result.get('name')} description={(result.get('description') or '')[:60]}")

    # 回读校验
    import urllib.parse
    verify = requests.get(
        f"{args.base_url}/api/skills/{urllib.parse.quote(name)}",
        cookies=cookies,
        timeout=10,
    )
    if verify.status_code == 200 and verify.json():
        v = verify.json()
        print(f"[verify] 回读成功: name={v.get('name')} content={len(v.get('content') or '')} 字符")
    else:
        print(f"[verify] 回读失败: HTTP {verify.status_code}")


if __name__ == "__main__":
    main()
