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

    /** 【2026-06-28 新增】按用户查询所有历史记录（用于侧边栏会话列表） */
    List<ChatHistory> findByUserIdOrderByCreatedAtDesc(String userId);

    /** 【2026-06-28 新增】按用户 + 会话查询（防止越权查看他人历史） */
    List<ChatHistory> findBySessionIdAndUserIdOrderByCreatedAtAsc(String sessionId, String userId);
}
