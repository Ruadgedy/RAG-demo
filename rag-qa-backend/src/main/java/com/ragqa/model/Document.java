package com.ragqa.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 文档实体类
 * 
 * 对应数据库表：document
 * 
 * 作用：表示上传到知识库的文档文件
 * 
 * 处理流程（状态变化）：
 * 1. UPLOADING(10%)    - 文件上传中
 * 2. PARSING(30%)      - 解析文档提取文本
 * 3. CHUNKING(50%)     - 文本分片
 * 4. EMBEDDING(70-100%) - 向量化处理
 * 5. COMPLETED(100%)  - 处理完成
 * 
 * 失败状态：
 * - UPLOAD_FAILED  - 上传失败
 * - PARSE_FAILED   - 解析失败
 * - CHUNK_FAILED   - 分片失败
 * - EMBEDDING_FAILED - 向量化失败
 * - FAILED         - 通用失败
 * 
 * 关联关系：
 * - 属于一个知识库 (KnowledgeBase)
 * - 包含多个文档切片 (DocumentChunk)
 */
@Data
@Entity
@Table(name = "document")
public class Document {
    
    /** 文档唯一标识(UUID) */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    
    /** 所属知识库ID */
    @Column(name = "knowledge_base_id", nullable = false)
    private UUID knowledgeBaseId;
    
    /** 原始文件名 */
    @Column(name = "file_name", nullable = false)
    private String fileName;
    
    /** 文件类型：pdf, docx, txt */
    @Column(name = "file_type")
    private String fileType;
    
    /** 文件存储路径 */
    @Column(name = "file_path")
    private String filePath;
    
    /** 处理状态 */
    @Enumerated(EnumType.STRING)
    private DocumentStatus status = DocumentStatus.UPLOADING;
    
    /** 处理进度 0-100 */
    @Column(name = "progress")
    private Integer progress = 0;
    
    /** 错误信息（失败时） */
    @Column(name = "error_message")
    private String errorMessage;
    
    /** 切片数量 */
    @Column(name = "chunk_count")
    private Integer chunkCount = 0;
    
    /** 上传时间 */
    @Column(name = "uploaded_at")
    private LocalDateTime uploadedAt;
    
    /** 处理完成时间 */
    @Column(name = "processed_at")
    private LocalDateTime processedAt;
    
    /**
     * 在创建记录前自动设置上传时间
     */
    @PrePersist
    protected void onCreate() {
        uploadedAt = LocalDateTime.now();
    }
    
    /**
     * 文档处理状态枚举
     */
    public enum DocumentStatus {
        UPLOADING,      // 上传中
        UPLOAD_FAILED,  // 上传失败
        PARSING,        // 解析中
        PARSE_FAILED,   // 解析失败
        CHUNKING,       // 切片中
        CHUNK_FAILED,   // 切片失败
        EMBEDDING,      // 向量化中
        EMBEDDING_FAILED, // 向量化失败
        COMPLETED,      // 完成
        FAILED          // 失败
    }
}
