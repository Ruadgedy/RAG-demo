-- =============================================
-- V5__add_eval_tables.sql
-- 新增 eval_run + eval_run_item 两张表（RAG 评估体系）
-- =============================================
-- 创建时间: 2026-06-29
-- 描述:
--   P2-01 评估体系底层表。
--   - eval_run: 每次跑批一条（一个数据集一次完整评测）
--   - eval_run_item: 每条 Q&A 一行（包含 retrieval/answer 指标 + 耗时）
--
-- 设计取舍：
--   1. 用 JSON 字段存 summary / doc_ids，避免拆多列便于动态扩展
--   2. score 字段全部用 DOUBLE（RAGAS 也是这么干的）
--   3. 黄金数据集不放 DB（git 追踪 + 版本管理），只放历史跑批结果
-- =============================================

-- 评估跑批主表
CREATE TABLE eval_run (
    id            CHAR(36) PRIMARY KEY,
    kb_id         CHAR(36) NOT NULL COMMENT '被评估的知识库',
    dataset_name  VARCHAR(128) NOT NULL COMMENT '数据集文件名（不含 .json 后缀）',
    status        VARCHAR(16) NOT NULL COMMENT 'RUNNING / COMPLETED / FAILED',
    started_at    DATETIME NOT NULL,
    finished_at   DATETIME,
    config_json   TEXT COMMENT '跑批配置（topK, judgeModel, sampleSize）',
    summary_json  TEXT COMMENT '汇总指标 JSON（hitRate@K / recall@K / mrr@K / avgFaithfulness 等）',
    error_message TEXT,
    INDEX idx_eval_run_kb_started (kb_id, started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RAG 评估跑批记录';

-- 单条 Q&A 评估明细
CREATE TABLE eval_run_item (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id              CHAR(36) NOT NULL,
    question            TEXT NOT NULL,
    expected_doc_ids    JSON COMMENT '期望命中的文档 UUID 列表',
    retrieved_doc_ids   JSON COMMENT '实际召回的文档 UUID 列表',
    retrieved_doc_names JSON COMMENT '实际召回的文件名列表（调试用）',
    rank_of_first_hit   INT COMMENT '第一个期望文档的排名（用于 MRR）',
    answer              LONGTEXT,
    golden_answer       LONGTEXT COMMENT '黄金数据集里的人工标注答案',
    faithfulness        DOUBLE COMMENT 'LLM-judge 给的忠实度 0-5',
    relevance           DOUBLE COMMENT 'LLM-judge 给的相关性 0-5',
    unsupported_claims  JSON COMMENT 'faithfulness 评分时挑出的无支撑声明',
    retrieval_ms        INT COMMENT '检索阶段耗时',
    generation_ms       INT COMMENT 'LLM 生成耗时',
    total_ms            INT COMMENT '总耗时',
    error               TEXT COMMENT '单条评测异常（不影响其他条）',
    INDEX idx_item_run (run_id),
    CONSTRAINT fk_item_run FOREIGN KEY (run_id) REFERENCES eval_run(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='单条 Q&A 评估明细';