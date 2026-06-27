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
     * 查找卡死的文档：状态在 PROCESSING_STATES 集合内，且 uploaded_at 早于 threshold。
     *
     * 用于 DocumentProcessRecoveryScheduler 定期清理因服务崩溃而永远卡在中间态的文档。
     */
    @Query("SELECT d FROM Document d WHERE d.status IN :statuses AND d.uploadedAt < :threshold")
    List<Document> findStuckDocuments(@Param("statuses") Collection<Document.DocumentStatus> statuses,
                                      @Param("threshold") LocalDateTime threshold);
}
