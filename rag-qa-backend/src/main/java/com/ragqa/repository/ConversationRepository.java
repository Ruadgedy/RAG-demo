package com.ragqa.repository;

import com.ragqa.model.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 对话组 Repository
 *
 * 【V6 重构 2026-06-30】
 */
@Repository
public interface ConversationRepository extends JpaRepository<Conversation, String> {

    /**
     * 按用户查询对话组列表（按更新时间倒序）
     */
    List<Conversation> findByUserIdOrderByUpdatedAtDesc(String userId);

    /**
     * 按用户 + conversationId 查询（防越权）
     */
    Optional<Conversation> findByIdAndUserId(String id, String userId);

    /**
     * 查询用户某个知识库下的对话组数量
     */
    long countByUserIdAndKnowledgeBaseId(String userId, String knowledgeBaseId);

    /**
     * 删除用户某个对话组
     */
    void deleteByIdAndUserId(String id, String userId);

    /**
     * 获取对话组列表（带最近一条消息的摘要，用于历史列表展示）
     */
    @Query(value = """
        SELECT c.*,
               (SELECT ch.content FROM chat_history ch
                WHERE ch.conversation_id = c.id
                ORDER BY ch.turn_index ASC LIMIT 1) as latest_content,
               (SELECT ch.created_at FROM chat_history ch
                WHERE ch.conversation_id = c.id
                ORDER BY ch.turn_index DESC LIMIT 1) as latest_time
        FROM conversation c
        WHERE c.user_id = :userId
        ORDER BY c.updated_at DESC
        """, nativeQuery = true)
    List<Conversation> findConversationsWithLatestMessage(@Param("userId") String userId);

    /**
     * 更新对话的 rag_mode（Agentic RAG F20：per-conversation 切换模式）
     * @param id      conversation id
     * @param userId  防越权校验
     * @param ragMode 新模式：linear | agentic；传 null 表示恢复全局默认值
     * @return 更新行数（0=不存在或越权）
     */
    @Modifying
    @Query("UPDATE Conversation c SET c.ragMode = :ragMode WHERE c.id = :id AND c.userId = :userId")
    int updateRagMode(@Param("id") String id, @Param("userId") String userId, @Param("ragMode") String ragMode);
}