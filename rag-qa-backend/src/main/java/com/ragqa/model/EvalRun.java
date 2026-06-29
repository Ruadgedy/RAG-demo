package com.ragqa.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 评估跑批主表（eval_run）
 *
 * 一次完整评测 = 一条记录（不论跑了多少条 Q&A）
 *
 * 【2026-06-29 修复 Hibernate schema-validation】
 *   - id / kb_id 在 V5 migration 里是 CHAR(36)
 *   - 因为字段类型是 String（不是 UUID），Hibernate 默认映射成 VARCHAR(36)
 *   - 必须显式 columnDefinition = "CHAR(36)" 才能通过 schema 校验
 *   - 选 String 而非 UUID 类型的原因：手动赋值便于跨服务复制日志/CSV
 */
@Data
@Entity
@Table(name = "eval_run")
public class EvalRun {

    @Id
    @Column(length = 36, columnDefinition = "CHAR(36)")
    private String id;

    /** 被评估的知识库 UUID */
    @Column(name = "kb_id", nullable = false, length = 36, columnDefinition = "CHAR(36)")
    private String kbId;

    /** 数据集名称（不含 .json） */
    @Column(name = "dataset_name", nullable = false, length = 128)
    private String datasetName;

    /** RUNNING / COMPLETED / FAILED */
    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    /** 跑批配置（topK / judgeModel / sampleSize） */
    @Column(name = "config_json", columnDefinition = "TEXT")
    private String configJson;

    /** 汇总指标 JSON */
    @Column(name = "summary_json", columnDefinition = "TEXT")
    private String summaryJson;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID().toString();
        if (startedAt == null) startedAt = LocalDateTime.now();
        if (status == null) status = "RUNNING";
    }
}