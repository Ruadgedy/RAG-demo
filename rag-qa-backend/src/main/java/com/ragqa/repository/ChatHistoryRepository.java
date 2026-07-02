package com.ragqa.repository;

import com.ragqa.model.ChatHistory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 对话历史 Repository（V6 重构版）
 *
 * 【V6 重构 2026-06-30】
 * - session_id 改为 conversation_id + chat_id
 * - 新增 turn_index 用于滑动窗口和排序
 */
@Repository
public interface ChatHistoryRepository extends JpaRepository<ChatHistory, String> {

    /**
     * 按对话组查询所有问答记录（按轮次升序）
     */
    List<ChatHistory> findByConversationIdOrderByTurnIndexAsc(String conversationId);

    /**
     * 按对话组查询所有问答记录（按轮次降序）
     */
    List<ChatHistory> findByConversationIdOrderByTurnIndexDesc(String conversationId);

    /**
     * 【滑动窗口】获取对话组最近 N 轮对话（用于注入 prompt）
     *
     * @param conversationId 对话组ID
     * @param pageable 分页参数（limit = 窗口大小）
     * @return 按 turn_index 升序排列的历史记录
     */
    @Query("SELECT h FROM ChatHistory h WHERE h.conversationId = :conversationId " +
           "ORDER BY h.turnIndex ASC")
    List<ChatHistory> findRecentByConversationId(
            @Param("conversationId") String conversationId,
            Pageable pageable);

    /**
     * 查询对话组的总轮次（用于生成下一轮的 turnIndex）
     */
    @Query("SELECT COALESCE(MAX(h.turnIndex), -1) + 1 FROM ChatHistory h " +
           "WHERE h.conversationId = :conversationId")
    int getNextTurnIndex(@Param("conversationId") String conversationId);

    /**
     * 按用户 + 对话组查询（防越权）
     */
    Optional<ChatHistory> findByConversationIdAndUserId(String conversationId, String userId);

    /**
     * 按用户查询所有历史记录（用于侧边栏会话列表）
     * 注意：这个方法现在返回的是单条记录，需要按 conversation_id 分组
     */
    List<ChatHistory> findByUserIdOrderByCreatedAtDesc(String userId);

    /**
     * 删除整个对话组的所有历史记录
     */
    void deleteByConversationId(String conversationId);

    /**
     * 统计对话组的问答轮次
     */
    @Query("SELECT COUNT(h) FROM ChatHistory h WHERE h.conversationId = :conversationId")
    long countByConversationId(@Param("conversationId") String conversationId);
}