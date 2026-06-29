package com.ragqa.repository;

import com.ragqa.model.Document;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DocumentRepository extends JpaRepository<Document, UUID> {
    List<Document> findByKnowledgeBaseId(UUID knowledgeBaseId);

    Optional<Document> findByKnowledgeBaseIdAndFileName(UUID knowledgeBaseId, String fileName);

    /**
     * 【2026-06-29 增量 P1-04】按文件内容 SHA-256 查找
     *
     * 用于内容级去重：即使文件名不同，相同内容也算重复。
     * 数据库层 uk_document_kb_file_hash 唯一约束保证并发安全。
     */
    Optional<Document> findByKnowledgeBaseIdAndFileHash(UUID knowledgeBaseId, String fileHash);

    /**
     * 查找卡死的文档：状态在 PROCESSING_STATES 集合内，且 uploaded_at 早于 threshold。
     *
     * 用于 DocumentProcessRecoveryScheduler 定期清理因服务崩溃而永远卡在中间态的文档。
     */
    @Query("SELECT d FROM Document d WHERE d.status IN :statuses AND d.uploadedAt < :threshold")
    List<Document> findStuckDocuments(@Param("statuses") Collection<Document.DocumentStatus> statuses,
                                      @Param("threshold") LocalDateTime threshold);
}
