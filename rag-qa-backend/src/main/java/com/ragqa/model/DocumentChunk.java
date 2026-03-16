package com.ragqa.model;

import jakarta.persistence.*;
import lombok.Data;
import java.util.UUID;

/**
 * 文档切片实体类
 * 
 * 对应数据库表：document_chunk
 * 
 * 作用：存储文档切片及其向量表示
 * 
 * 说明：
 * - 文档上传后会被切分成多个小片段（chunks）
 * - 每个片段会被转换成向量存储
 * - 问答时通过向量相似度检索相关片段
 * 
 * 关联关系：
 * - 属于一个文档 (Document)
 */
@Data
@Entity
@Table(name = "document_chunk")
public class DocumentChunk {
    
    /** 切片唯一标识(UUID) */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    
    /** 所属文档ID */
    @Column(name = "document_id", nullable = false)
    private UUID documentId;
    
    /** 切片索引（从0开始） */
    @Column(name = "chunk_index")
    private Integer chunkIndex;
    
    /** 切片原文内容 */
    @Column(columnDefinition = "TEXT")
    private String content;
    
    /** 向量数据（JSON格式存储） */
    @Column(columnDefinition = "LONGTEXT")
    private String embedding;
}
