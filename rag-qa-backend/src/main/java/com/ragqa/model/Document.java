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
 * 设计理念：
 * - 文档是知识库的内容载体
 * - 上传后需要异步处理（解析→切分→向量化）
 * - 处理过程有进度跟踪，便于前端展示
 *
 * 处理流程（状态变化）：
 * 1. UPLOADING(10%)       - 文件上传中
 * 2. PARSING(30%)         - 解析文档提取文本（使用Apache Tika）
 * 3. CHUNKING(50%)        - 文本分片（使用TextSplitter）
 * 4. EMBEDDING(70-100%)   - 向量化处理（使用EmbeddingService）
 * 5. COMPLETED(100%)      - 处理完成，可用于问答
 *
 * 失败状态：
 * - UPLOAD_FAILED     - 上传失败
 * - PARSE_FAILED      - 解析失败（文件损坏或格式不支持）
 * - CHUNK_FAILED      - 分片失败（文本过长）
 * - EMBEDDING_FAILED  - 向量化失败（模型服务不可用）
 * - FAILED            - 通用失败
 *
 * 关联关系：
 * - 属于一个知识库 (KnowledgeBase)
 * - 包含多个文档切片 (DocumentChunk)
 *
 * 存储信息：
 * - 原始文件名和文件类型
 * - 文件存储路径（非向量内容）
 * - 处理进度和状态
 * - 切片数量统计
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

    /**
     * 【2026-06-29 增量 P1-04】文件内容 SHA-256（hex 64 字符）
     *
     * 用途：内容级去重
     *   - 之前按 fileName 去重，用户改个名就能重复上传同一份文件
     *   - 改为 SHA-256 → (kb_id, file_hash) 唯一约束
     *   - 即使文件名改了，内容相同也算重复
     *
     * 性能影响：计算 SHA-256 一个 50MB 文件约 50ms，可接受
     *
     * 【2026-06-29 修复 Hibernate schema-validation】
     *   - V4 migration 用的是 CHAR(64)，不是 VARCHAR(64)
     *   - 必须显式声明 columnDefinition = "CHAR(64)"，否则 Hibernate 默认 VARCHAR，
     *     启动时报 SchemaManagementException（this was the bug）
     *   - CHAR 更符合语义：SHA-256 永远是 64 字符定长，无浪费存储
     */
    @Column(name = "file_hash", length = 64, columnDefinition = "CHAR(64)")
    private String fileHash;

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
