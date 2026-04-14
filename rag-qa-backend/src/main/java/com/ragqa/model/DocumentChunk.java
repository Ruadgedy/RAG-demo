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
 * 设计理念：
 * - 文档被切分成多个小块（chunk）是为了更精细的检索
 * - 每个chunk有唯一索引，便于追溯来源
 * - 同时存储原文和向量，MySQL用于持久化，Chroma用于快速检索
 *
 * 向量说明：
 * - 向量是将文本转换为高维数值数组
 * - 相似文本有相似的向量
 * - 通过余弦相似度可以找到与问题最相关的文档片段
 *
 * 关联关系：
 * - 属于一个文档 (Document)
 * - documentId + chunkIndex 组合唯一确定一个切片
 *
 * 典型检索流程：
 * 1. 用户问题 → 转换为向量
 * 2. 在Chroma中查找相似向量 → 得到chunk列表
 * 3. 根据chunk的documentId过滤知识库
 * 4. 返回最相关的几个chunk作为上下文
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
