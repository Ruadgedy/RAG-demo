package com.ragqa.model;

import jakarta.persistence.*;
import lombok.Data;

/**
 * 评估单条明细（eval_run_item）
 *
 * 每条 Q&A 一行，存所有指标 + 耗时。
 *
 * 【2026-06-29 修复 Hibernate schema-validation】
 *   - run_id 在 V5 migration 是 CHAR(36)，与 eval_run.id 关联
 *   - 字段类型为 String，必须显式 columnDefinition = "CHAR(36)"
 */
@Data
@Entity
@Table(name = "eval_run_item")
public class EvalRunItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 关联 eval_run.id */
    @Column(name = "run_id", nullable = false, length = 36, columnDefinition = "CHAR(36)")
    private String runId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String question;

    /** 期望命中的文档 UUID 列表（JSON 数组） */
    @Column(name = "expected_doc_ids", columnDefinition = "JSON")
    private String expectedDocIds;

    /** 实际召回的 UUID 列表 */
    @Column(name = "retrieved_doc_ids", columnDefinition = "JSON")
    private String retrievedDocIds;

    /** 实际召回的文件名列表（调试用） */
    @Column(name = "retrieved_doc_names", columnDefinition = "JSON")
    private String retrievedDocNames;

    /** 第一个命中的排名（-1 = 未命中） */
    @Column(name = "rank_of_first_hit")
    private Integer rankOfFirstHit;

    @Column(columnDefinition = "LONGTEXT")
    private String answer;

    @Column(name = "golden_answer", columnDefinition = "LONGTEXT")
    private String goldenAnswer;

    /** 忠实度 0-5 */
    private Double faithfulness;

    /** 相关性 0-5 */
    private Double relevance;

    /** LLM 列出的无支撑声明（JSON 数组） */
    @Column(name = "unsupported_claims", columnDefinition = "JSON")
    private String unsupportedClaims;

    @Column(name = "retrieval_ms")
    private Integer retrievalMs;

    @Column(name = "generation_ms")
    private Integer generationMs;

    @Column(name = "total_ms")
    private Integer totalMs;

    @Column(columnDefinition = "TEXT")
    private String error;
}