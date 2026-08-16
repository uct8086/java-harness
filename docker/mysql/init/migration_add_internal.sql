-- ============================================================
-- 增量迁移：为已有数据库添加 internal 列（子代理会话隐藏）
-- 适用：已经用旧版 init.sql 建过表的数据库
-- 执行：mysql -u root -p uct8086_ai < migration_add_internal.sql
-- 背景：多 Agent 编排的 agent 工具会为子代理创建独立会话，
--       这些会话不应出现在前端会话列表中。
--       新版 init.sql 建表已含此列，仅需旧库执行本脚本。
-- ============================================================

-- 1. harness_session 表加 internal 列（0=普通会话, 1=子代理内部会话）
ALTER TABLE `harness_session`
    ADD COLUMN `internal` TINYINT(1) NOT NULL DEFAULT 0
    COMMENT '内部会话(子代理派生,会话列表不显示)' AFTER `message_count`;

-- 2. （可选）标记历史上已存在的子代理会话
--    特征：name 形如 session-xxxxxxxx 且消息较少（由旧版 AgentTool 创建）
--    先查询确认，再按 id 批量标记：
-- SELECT id, name, message_count, created_at FROM harness_session
--     WHERE internal = 0 AND name LIKE 'session-%' AND message_count <= 4;
-- UPDATE harness_session SET internal = 1 WHERE id IN ('<id1>', '<id2>', '<id3>');
