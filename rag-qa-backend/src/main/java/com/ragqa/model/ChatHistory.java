package com.ragqa.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 对话历史实体类（V6 重构版）
 *
 * 对应数据库表：chat_history
 *
 * 【V6 重大变更 2026-06-30】
 * 重构为"对话组 + 单次问答"模型：
 * - conversation：对话组，一次完整的多轮对话
 * - chat_history：单次问答（用户提问+AI回答），归属于某个 conversation
 *
 * 字段说明：
 * - id: 主键，UUID
 * - userId: 所属用户 username（按用户隔离聊天历史）
 * - conversationId: 所属对话组ID
 * - chatId: 单次问答ID（UUID，用于 SSE session-start 事件）
 * - turnIndex: 第几轮对话（0,1,2...），用于滑动窗口和排序
 * - knowledgeBaseId: 所属知识库
 * - query: 用户提问（最长 128 字符）
 * - content: 模型完整回答（TEXT）
 * - createdAt: 创建时间
 * - ragMetadata: RAG 召回元数据（JSON 字符串）
 * - chatMetadata: 扩展元数据（JSON 字符串，预留）
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

    /** 所属对话组ID */
    @Column(name = "conversation_id", length = 36)
    private String conversationId;

    /** 单次问答ID（UUID，用于 SSE session-start 事件） */
    @Column(name = "chat_id", length = 36)
    private String chatId;

    /** 第几轮对话（0,1,2...），用于滑动窗口和排序 */
    @Column(name = "turn_index")
    private Integer turnIndex;

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
     * 扩展元数据（JSON 字符串，预留）
     * 可用于存储 token 数量、模型名称等扩展信息
     */
    @Column(name = "chat_metadata", columnDefinition = "json")
    private String chatMetadata;

    /**
     * 创建前自动设置 id + createdAt
     */
    @PrePersist
    protected void onCreate() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        if (chatId == null || chatId.isBlank()) {
            chatId = UUID.randomUUID().toString();
        }
        if (turnIndex == null) {
            turnIndex = 0;
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}