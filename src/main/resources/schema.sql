-- =========================================
-- UCT8086-AI Harness Schema
-- Prerequisite: the database must already exist, e.g.
--   CREATE DATABASE IF NOT EXISTS uct8086_ai DEFAULT CHARSET utf8mb4;
-- =========================================

CREATE DATABASE IF NOT EXISTS uct8086_ai DEFAULT CHARSET utf8mb4;


CREATE TABLE IF NOT EXISTS `harness_session` (
    `id` VARCHAR(64) NOT NULL COMMENT '会话ID(UUID)',
    `name` VARCHAR(200) COMMENT '会话名称',
    `created_at` DATETIME(3) COMMENT '创建时间',
    `updated_at` DATETIME(3) COMMENT '更新时间',
    `message_count` INT NOT NULL DEFAULT 0 COMMENT '消息数量',
    PRIMARY KEY (`id`),
    KEY `idx_updated_at` (`updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI会话表';

CREATE TABLE IF NOT EXISTS `harness_message` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `session_id` VARCHAR(64) NOT NULL COMMENT '会话ID',
    `role` VARCHAR(20) NOT NULL COMMENT '消息角色: SYSTEM/USER/ASSISTANT/TOOL',
    `content` LONGTEXT COMMENT '消息内容',
    `tool_calls_json` LONGTEXT COMMENT '工具调用列表(JSON)',
    `tool_call_id` VARCHAR(128) COMMENT '工具调用ID',
    `created_at` DATETIME(3) COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_session_id` (`session_id`),
    KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI会话消息表';
