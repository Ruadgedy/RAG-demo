-- V7: 添加 conversation.rag_mode 字段（Agentic RAG F20：per-conversation 覆盖全局 rag.mode）
-- nullable：null = 继承全局 rag.mode 默认值（linear）

ALTER TABLE conversation
    ADD COLUMN rag_mode VARCHAR(16) DEFAULT NULL COMMENT '对话级 RAG 模式：linear（传统 RAG）| agentic（LLM 自主编排）；null=继承全局默认值';

-- 已有数据不强制，默认可选 global rag.mode
