-- =============================================
-- V2__add_user_id_to_chat_history.sql
-- 为对话历史添加用户关联
-- =============================================
-- 创建时间: 2026-06-28
-- 描述: chat_history 表新增 user_id 列，实现按用户隔离聊天历史
-- =============================================

ALTER TABLE chat_history ADD COLUMN user_id VARCHAR(255) AFTER id;
ALTER TABLE chat_history ADD INDEX idx_user_id (user_id);
