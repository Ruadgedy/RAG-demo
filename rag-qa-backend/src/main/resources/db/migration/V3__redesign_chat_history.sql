-- =============================================
-- V3__redesign_chat_history.sql
-- chat_history 表结构重构
-- =============================================
-- 创建时间: 2026-06-28
-- 描述:
--   1. 一个会话回合 = 一条记录（user 问题 + AI 回答 + RAG 召回元数据）
--      → 替代旧的"user/assistant 各一条 + role 区分"
--   2. id / knowledge_base_id 改用 CHAR(36) 可读 UUID
--   3. user_id 收紧到 varchar(64)
--   4. 新增 query 字段（用户提问，最长 128）
--   5. 新增 rag_metadata JSON 字段（RAG 召回埋点）
--   6. 恢复 V1 原本该有的 idx_session / idx_knowledge_base / idx_created_at
--   7. 恢复外键约束 knowledge_base_id → knowledge_base(id) ON DELETE SET NULL
--   8. 保留 V2 加的 idx_user_id（用于按用户隔离查询）
--
-- 数据迁移：直接 DROP + 重建（4 条记录均为 smoke test，不保留）
-- =============================================

DROP TABLE IF EXISTS chat_history;

CREATE TABLE chat_history (
    id CHAR(36) PRIMARY KEY,
    user_id VARCHAR(64) DEFAULT NULL COMMENT '用户ID（username）',
    session_id VARCHAR(255) DEFAULT NULL COMMENT '会话ID',
    knowledge_base_id CHAR(36) DEFAULT NULL COMMENT '所属知识库ID',
    query VARCHAR(128) DEFAULT NULL COMMENT '用户提问（最长 128 字符）',
    content TEXT NOT NULL COMMENT '模型答案',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    rag_metadata JSON DEFAULT NULL COMMENT 'RAG 召回元数据：召回文档数、文档ID列表、召回片段数、检索耗时',

    -- 注：knowledge_base.id 是 binary(16)，knowledge_base_id 是 CHAR(36)，
    --     MySQL 不允许这两种类型直接做 FK 约束，因此本表不建 FK（仅靠应用层保证一致性）
    --     现状：document.knowledge_base_id (binary(16) → knowledge_base.id binary(16)) 也没有 FK 约束，
    --     与历史保持一致。如果未来要做强一致，建议把 knowledge_base.id 统一改成 CHAR(36)。
    INDEX idx_session (session_id),
    INDEX idx_user_id (user_id),
    INDEX idx_knowledge_base (knowledge_base_id),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
