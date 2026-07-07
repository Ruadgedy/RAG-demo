-- V8: 添加 agent_trace 表（Agentic RAG F21：每轮 tool 调用记录，供可观测 + 前端展示思考过程）
-- chat_id 关联 chat_history.id；round 从 1 开始连续递增

CREATE TABLE IF NOT EXISTS agent_trace (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    chat_id VARCHAR(36) NOT NULL COMMENT '关联 chat_history.id',
    round INT NOT NULL COMMENT '第几轮 tool 调用（从 1 开始',
    tool_name VARCHAR(64) NOT NULL COMMENT '工具名：kb_search / web_search / direct_answer',
    tool_args TEXT COMMENT 'JSON 格式的工具入参',
    result_summary TEXT COMMENT 'tool 返回摘要（截断 500 字）',
    duration_ms INT COMMENT '工具执行耗时（毫秒）',
    status VARCHAR(16) NOT NULL DEFAULT 'done' COMMENT 'start=开始，done=完成',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_chat (chat_id),
    INDEX idx_chat_round (chat_id, round)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
