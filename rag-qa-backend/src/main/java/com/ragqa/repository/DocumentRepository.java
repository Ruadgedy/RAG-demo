package com.ragqa.repository;

import com.ragqa.model.Document;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DocumentRepository extends JpaRepository<Document, UUID> {
    List<Document> findByKnowledgeBaseId(UUID knowledgeBaseId);
    
    Optional<Document> findByKnowledgeBaseIdAndFileName(UUID knowledgeBaseId, String fileName);
}
