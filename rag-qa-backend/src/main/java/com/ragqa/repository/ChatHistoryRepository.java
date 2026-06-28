package com.ragqa.repository;

import com.ragqa.model.ChatHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 对话历史仓库（V3 重构版）
 *
 * 【V3 变更】
 * - 主键类型 UUID → String（CHAR(36) 可读 UUID）
 * - knowledgeBaseId 类型 UUID → String
 * - 新增 findByKnowledgeBaseIdOrderByCreatedAtDesc(String)
 */
@Repository
public interface ChatHistoryRepository extends JpaRepository<ChatHistory, String> {

    /** 按 sessionId 升序查询完整会话回合（每个回合 = 一条记录：query + content） */
    List<ChatHistory> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    /** 按知识库 ID 降序查询（CHAR(36) UUID 字符串） */
    List<ChatHistory> findByKnowledgeBaseIdOrderByCreatedAtDesc(String knowledgeBaseId);

    /** 删除整个会话 */
    void deleteBySessionId(String sessionId);

    /** 按用户查询所有历史记录（用于侧边栏会话列表） */
    List<ChatHistory> findByUserIdOrderByCreatedAtDesc(String userId);

    /** 按用户 + 会话查询（防止越权查看他人历史） */
    List<ChatHistory> findBySessionIdAndUserIdOrderByCreatedAtAsc(String sessionId, String userId);
}
