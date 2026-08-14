-- ============================================================
-- UCT8086-AI 数据库初始化脚本（MySQL 8.0+）
-- ============================================================
-- 用法：
--   1. 创建数据库（如尚未创建）：
--        CREATE DATABASE IF NOT EXISTS `uct8086_ai` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
--   2. 执行本脚本：
--        mysql -u root -p uct8086_ai < init.sql
--   （或直接在 Navicat / DataGrip 中打开执行）
-- 说明：本脚本幂等（IF NOT EXISTS），可重复执行。
-- ============================================================

-- ------------------------------------------------------------
-- 1. 会话表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `harness_session` (
    `id`            VARCHAR(64)  NOT NULL COMMENT '会话ID(UUID)',
    `user_id`       BIGINT       NOT NULL COMMENT '所属用户ID',
    `name`          VARCHAR(200) NULL COMMENT '会话名称',
    `created_at`    DATETIME(3)  NULL COMMENT '创建时间',
    `updated_at`    DATETIME(3)  NULL COMMENT '更新时间',
    `message_count` INT          NOT NULL DEFAULT 0 COMMENT '消息数量',
    PRIMARY KEY (`id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_updated_at` (`updated_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'AI会话表';

-- ------------------------------------------------------------
-- 2. 会话消息表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `harness_message` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `user_id`         BIGINT       NOT NULL COMMENT '所属用户ID',
    `session_id`      VARCHAR(64)  NOT NULL COMMENT '会话ID',
    `role`            VARCHAR(20)  NOT NULL COMMENT '消息角色: SYSTEM/USER/ASSISTANT/TOOL',
    `content`         LONGTEXT     NULL COMMENT '消息内容',
    `tool_calls_json` LONGTEXT     NULL COMMENT '工具调用列表(JSON)',
    `tool_call_id`    VARCHAR(128) NULL COMMENT '工具调用ID',
    `created_at`      DATETIME(3)  NULL COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_session_id` (`session_id`),
    KEY `idx_created_at` (`created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'AI会话消息表';

-- ------------------------------------------------------------
-- 3. 用户表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `auth_user` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `username`      VARCHAR(64)  NOT NULL COMMENT '用户名(唯一)',
    `password_hash` VARCHAR(128) NOT NULL COMMENT '密码哈希(BCrypt)',
    `display_name`  VARCHAR(128) NULL COMMENT '显示名',
    `enabled`       TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否启用',
    `created_at`    DATETIME(3)  NULL COMMENT '创建时间',
    `updated_at`    DATETIME(3)  NULL COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '用户表';

-- ------------------------------------------------------------
-- 4. 角色表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `auth_role` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `name`        VARCHAR(64)  NOT NULL COMMENT '角色名(如 ROLE_ADMIN)',
    `description` VARCHAR(255) NULL COMMENT '角色描述',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_role_name` (`name`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '角色表';

-- ------------------------------------------------------------
-- 5. 用户-角色关联表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `auth_user_role` (
    `user_id` BIGINT NOT NULL COMMENT '用户ID',
    `role_id` BIGINT NOT NULL COMMENT '角色ID',
    PRIMARY KEY (`user_id`, `role_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '用户-角色关联表';

-- ------------------------------------------------------------
-- 6. 初始化默认角色
-- ------------------------------------------------------------
INSERT INTO `auth_role` (`name`, `description`) VALUES
    ('ROLE_USER', '普通用户'),
    ('ROLE_ADMIN', '管理员')
ON DUPLICATE KEY UPDATE `description` = VALUES(`description`);

-- ------------------------------------------------------------
-- 7. 初始化默认管理员账号：admin / admin123
--    密码为 BCrypt 哈希。首次部署后请尽快修改密码！
-- ------------------------------------------------------------
INSERT INTO `auth_user` (`username`, `password_hash`, `display_name`, `enabled`, `created_at`, `updated_at`)
VALUES ('admin', '$2a$10$z4Wu9NFQg7C5a8KwL6hZeehUcsK0U.OvseVTOJBic162lTe3uI0Nm', '系统管理员', 1, NOW(3), NOW(3))
ON DUPLICATE KEY UPDATE `username` = `username`;

-- ------------------------------------------------------------
-- 8. 关联 admin 用户到 ADMIN 角色
-- ------------------------------------------------------------
INSERT INTO `auth_user_role` (`user_id`, `role_id`)
SELECT u.id, r.id FROM `auth_user` u, `auth_role` r
WHERE u.username = 'admin' AND r.name = 'ROLE_ADMIN'
ON DUPLICATE KEY UPDATE `user_id` = `user_id`;
