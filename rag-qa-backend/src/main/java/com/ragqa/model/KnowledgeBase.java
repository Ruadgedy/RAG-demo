package com.ragqa.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 知识库实体类
 * 
 * 对应数据库表：knowledge_base
 * 
 * 作用：表示一个知识库，用于组织和管理多个文档
 * 
 * 关联关系：
 * - 一个知识库可以包含多个文档 (Document)
 * - 一个知识库可以有多条对话历史 (ChatHistory)
 */
@Data
@Entity
@Table(name = "knowledge_base")
public class KnowledgeBase {
    
    /** 知识库唯一标识(UUID) */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    
    /** 知识库名称，不能重复 */
    @Column(nullable = false, unique = true)
    private String name;
    
    /** 知识库描述（可选） */
    private String description;
    
    /** 创建时间 */
    @Column(name = "created_at")
    private LocalDateTime createdAt;
    
    /** 更新时间 */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
    
    /**
     * 在创建记录前自动设置创建时间
     */
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }
    
    /**
     * 在更新记录前自动设置更新时间
     */
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
