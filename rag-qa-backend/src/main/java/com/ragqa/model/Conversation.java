package com.ragqa.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 对话组实体
 *
 * 【V6 重构 2026-06-30】
 * 用于支持真正的多轮对话：
 * - conversation：一个完整的多轮对话会话（对话组）
 * - chat_history：单次问答（用户提问+AI回答），归属于某个 conversation
 *
 * 关键字段：
 * - id：conversation_id，对话组唯一标识
 * - title：大模型生成的第一轮对话摘要
 * - first_query：第一轮原始提问，用于历史列表展示
 * - history_window：滑动窗口大小，控制注入 prompt 的历史轮数
 */
@Data
@Entity
@Table(name = "conversation")
public class Conversation {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "user_id", length = 64, nullable = false)
    private String userId;

    @Column(name = "title", length = 255)
    private String title;

    @Column(name = "first_query", length = 255)
    private String firstQuery;

    @Column(name = "knowledge_base_id", columnDefinition = "CHAR(36)")
    private String knowledgeBaseId;

    /**
     * 滑动窗口大小，默认 3
     * 控制每次问答时注入 prompt 的最近历史轮数
     * 前端可配置，范围建议 1-10
     */
    @Column(name = "history_window")
    private Integer historyWindow = 3;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (id == null || id.isBlank()) {
            id = java.util.UUID.randomUUID().toString();
        }
        if (historyWindow == null) {
            historyWindow = 3;
        }
    }
}