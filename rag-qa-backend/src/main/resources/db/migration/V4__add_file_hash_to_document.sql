-- =============================================
-- V4__add_file_hash_to_document.sql
-- document 表新增 file_hash 列（用于内容去重）
-- =============================================
-- 创建时间: 2026-06-29
-- 描述:
--   P1-04 上传去重改造：
--   之前的去重仅按 fileName，用户改个名就能重复上传同一份文件，浪费 embedding 算力。
--   改为计算文件内容的 SHA-256 哈希（64 hex 字符），按 (kb_id, file_hash) 联合查重。
--
--   加唯一约束而非仅索引的原因：
--     - 并发上传两个相同文件时（不同请求），普通索引会让两个都进入处理队列
--     - 唯一约束会在第二条 INSERT 时直接报错，由 Service 捕获并返回 409 Conflict
--     - 这是更严格的去重，避免竞态
-- =============================================

-- 加 file_hash 列（先允许 NULL，老数据不会失败）
ALTER TABLE document
  ADD COLUMN file_hash CHAR(64) NULL COMMENT 'SHA-256 of file content (hex)';

-- 给同一 KB 内的 (file_hash) 加唯一索引
-- 注意：MySQL 8 支持函数索引，但 CHAR(64) 本身就是定长，直接普通唯一索引即可
CREATE UNIQUE INDEX uk_document_kb_file_hash
    ON document (knowledge_base_id, file_hash);