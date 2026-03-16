package com.ragqa.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 对话历史实体类
 * 
 * 对应数据库表：chat_history
 * 
 * 作用：存储用户与AI的对话记录
 * 
 * 说明：
 * - 同一个会话的所有消息有相同的sessionId
 * - 可以通过sessionId查询完整的对话历史
 * - 支持多轮对话，上下文记忆
 */
@Data
@Entity
@Table(name = "chat_history")
public class ChatHistory {
    
    /** 记录唯一标识(UUID) */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    
    /** 会话ID，用于关联同一轮对话的所有消息 */
    @Column(name = "session_id")
    private String sessionId;
    
    /** 所属知识库ID */
    @Column(name = "knowledge_base_id")
    private UUID knowledgeBaseId;
    
    /** 消息角色：user（用户）或 assistant（AI） */
    @Column(nullable = false)
    private String role;
    
    /** 消息内容 */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;
    
    /** 创建时间 */
    @Column(name = "created_at")
    private LocalDateTime createdAt;
    
    /**
     * 在创建记录前自动设置创建时间
     */
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
