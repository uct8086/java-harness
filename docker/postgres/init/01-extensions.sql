-- =========================================
-- PostgreSQL + pgvector 扩展初始化
-- 容器首次启动时自动执行
-- =========================================

CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS hstore;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
