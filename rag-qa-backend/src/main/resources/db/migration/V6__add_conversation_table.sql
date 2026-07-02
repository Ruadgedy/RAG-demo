-- V6: 对话组重构——新增 conversation 表，支持多轮对话滑动窗口
-- ============================================================
-- 背景：
-- 原设计：session_id 每次问答都新建，无法支持真正的多轮对话
-- 新设计：
--   - conversation：对话组，一次完整的多轮对话
--   - chat_history：单次问答（用户提问+AI回答），归属于某个 conversation
-- ============================================================

-- 1. 新建 conversation 表
CREATE TABLE IF NOT EXISTS conversation (
    id VARCHAR(36) PRIMARY KEY COMMENT '对话组ID（UUID）',
    user_id VARCHAR(64) NOT NULL COMMENT '所属用户',
    title VARCHAR(255) DEFAULT NULL COMMENT '对话组标题（大模型生成）',
    first_query VARCHAR(255) DEFAULT NULL COMMENT '第一轮原始提问（用于历史列表展示）',
    knowledge_base_id CHAR(36) DEFAULT NULL COMMENT '关联知识库ID',
    history_window INT DEFAULT 3 COMMENT '滑动窗口大小（取最近N轮注入prompt）',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    INDEX idx_conversation_user (user_id),
    INDEX idx_conversation_updated (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 2. 修改 chat_history 表
-- 2.1 新增字段
ALTER TABLE chat_history
    ADD COLUMN conversation_id VARCHAR(36) DEFAULT NULL COMMENT '所属对话组ID' AFTER knowledge_base_id,
    ADD COLUMN chat_id VARCHAR(36) DEFAULT NULL COMMENT '单次问答ID（UUID）' AFTER conversation_id,
    ADD COLUMN turn_index INT DEFAULT 0 COMMENT '第几轮对话（0,1,2...）' AFTER chat_id,
    ADD COLUMN chat_metadata JSON DEFAULT NULL COMMENT '扩展元数据（预留）';

-- 2.2 创建索引
ALTER TABLE chat_history
    ADD INDEX idx_chat_conversation (conversation_id),
    ADD INDEX idx_chat_id (chat_id),
    ADD INDEX idx_chat_turn (conversation_id, turn_index);

-- 2.3 删除旧的 session_id 字段（数据已迁移到 conversation_id + chat_id）
-- 注意：如果需要保留兼容，可以跳过这步
-- ALTER TABLE chat_history DROP COLUMN session_id;