package com.ragqa.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 对话历史实体类（V3 重构版）
 *
 * 对应数据库表：chat_history
 *
 * 【V3 重大变更 2026-06-28】
 * 1. 一个会话回合 = 一条记录：query（用户提问） + content（AI 回答） + rag_metadata（RAG 召回元数据）
 *    → 替代旧版"user/assistant 各一条 + role 字段"
 * 2. id / knowledge_base_id 改用 CHAR(36) 可读 UUID（Java 端用 String 承载）
 * 3. user_id 收紧到 varchar(64)
 * 4. 新增 rag_metadata JSON 字段（RAG 召回埋点：召回文档数、文档ID列表、召回片段数、检索耗时）
 *
 * 字段说明：
 * - id: 主键，UUID
 * - userId: 所属用户 username（按用户隔离聊天历史）
 * - sessionId: 同一会话的多轮回合共享同一个 sessionId
 * - knowledgeBaseId: 所属知识库，FK → knowledge_base.id，ON DELETE SET NULL
 * - query: 用户提问（最长 128 字符）
 * - content: 模型完整回答（TEXT）
 * - createdAt: 创建时间
 * - ragMetadata: RAG 召回元数据（JSON 字符串）
 */
@Data
@Entity
@Table(name = "chat_history")
public class ChatHistory {

    /** 主键 UUID（CHAR(36)） */
    @Id
    @Column(name = "id", columnDefinition = "CHAR(36)")
    private String id;

    /** 所属用户ID（username） */
    @Column(name = "user_id", length = 64)
    private String userId;

    /** 会话ID（同一会话的多轮回合共享） */
    @Column(name = "session_id")
    private String sessionId;

    /** 所属知识库ID（CHAR(36)） */
    @Column(name = "knowledge_base_id", columnDefinition = "CHAR(36)")
    private String knowledgeBaseId;

    /** 用户提问（最长 128 字符） */
    @Column(name = "query", length = 128)
    private String query;

    /** 模型答案 */
    @Lob
    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    /** 创建时间 */
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /**
     * RAG 召回元数据（JSON 字符串）。
     * 格式：
     * {
     *   "retrieved_doc_count": 3,
     *   "retrieved_chunk_count": 7,
     *   "retrieved_doc_ids": ["uuid1", "uuid2", "uuid3"],
     *   "retrieval_duration_ms": 245
     * }
     */
    @Column(name = "rag_metadata", columnDefinition = "json")
    private String ragMetadata;

    /**
     * 创建前自动设置 id + createdAt
     */
    @PrePersist
    protected void onCreate() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
