package com.ragqa.repository;

import com.ragqa.model.ChatHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.UUID;

@Repository
public interface ChatHistoryRepository extends JpaRepository<ChatHistory, UUID> {
    
    List<ChatHistory> findBySessionIdOrderByCreatedAtAsc(String sessionId);
    
    List<ChatHistory> findByKnowledgeBaseIdOrderByCreatedAtDesc(UUID knowledgeBaseId);
    
    void deleteBySessionId(String sessionId);
}
