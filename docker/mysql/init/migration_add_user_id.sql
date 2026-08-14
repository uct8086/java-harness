-- ============================================================
-- 增量迁移：为已有数据库添加 user_id 列（用户数据隔离）
-- 适用：已经用旧版 init.sql 建过表的数据库
-- 执行：mysql -u root -p uct8086_ai < migration_add_user_id.sql
-- ============================================================

-- 1. harness_session 表加 user_id
ALTER TABLE `harness_session`
    ADD COLUMN `user_id` BIGINT NOT NULL DEFAULT 0 COMMENT '所属用户ID' AFTER `id`,
    ADD KEY `idx_user_id` (`user_id`);

-- 2. harness_message 表加 user_id
ALTER TABLE `harness_message`
    ADD COLUMN `user_id` BIGINT NOT NULL DEFAULT 0 COMMENT '所属用户ID' AFTER `id`,
    ADD KEY `idx_user_id` (`user_id`);
