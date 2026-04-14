package com.ragqa.controller;

import com.ragqa.model.ChatHistory;
import com.ragqa.repository.ChatHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * 对话历史控制器
 *
 * 作用：管理用户与AI的对话历史记录
 *
 * 接口说明：
 * - GET /api/chat-history: 获取所有会话
 * - GET /api/chat-history/{sessionId}: 获取指定会话的所有消息
 * - GET /api/knowledge-bases/{kbId}/chat-history: 获取指定知识库的会话列表
 * - POST /api/chat-history: 保存单条消息
 * - DELETE /api/chat-history/{sessionId}: 删除整个会话
 *
 * 会话概念：
 * - sessionId: 同一轮对话的所有消息共享同一个sessionId
 * - role: 消息角色，user（用户）或 assistant（AI）
 * - 消息按创建时间排序（ASC为时间正序）
 *
 * 认证要求：
 * - 所有接口需要JWT认证
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ChatHistoryController {

    private final ChatHistoryRepository chatHistoryRepository;

    /**
     * 获取所有会话
     *
     * @return 所有对话历史记录
     */
    @GetMapping("/chat-history")
    public ResponseEntity<List<ChatHistory>> getAllSessions() {
        List<ChatHistory> history = chatHistoryRepository.findAll();
        return ResponseEntity.ok(history);
    }

    /**
     * 获取指定会话的所有消息
     *
     * @param sessionId 会话ID
     * @return 按时间正序排列的消息列表
     */
    @GetMapping("/chat-history/{sessionId}")
    public ResponseEntity<List<ChatHistory>> getHistoryBySession(@PathVariable String sessionId) {
        List<ChatHistory> history = chatHistoryRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        return ResponseEntity.ok(history);
    }

    /**
     * 获取指定知识库的所有会话
     *
     * @param kbId 知识库ID
     * @return 按时间倒序的会话列表
     */
    @GetMapping("/knowledge-bases/{kbId}/chat-history")
    public ResponseEntity<List<ChatHistory>> getHistoryByKnowledgeBase(@PathVariable UUID kbId) {
        List<ChatHistory> history = chatHistoryRepository.findByKnowledgeBaseIdOrderByCreatedAtDesc(kbId);
        return ResponseEntity.ok(history);
    }

    /**
     * 保存单条消息
     *
     * @param chatHistory 消息内容（包含sessionId、role、content等）
     * @return 保存后的消息记录
     */
    @PostMapping("/chat-history")
    public ResponseEntity<ChatHistory> saveMessage(@RequestBody ChatHistory chatHistory) {
        ChatHistory saved = chatHistoryRepository.save(chatHistory);
        return ResponseEntity.ok(saved);
    }

    /**
     * 删除整个会话
     *
     * @param sessionId 要删除的会话ID
     * @return 204 No Content
     */
    @DeleteMapping("/chat-history/{sessionId}")
    public ResponseEntity<Void> deleteSession(@PathVariable String sessionId) {
        chatHistoryRepository.deleteBySessionId(sessionId);
        return ResponseEntity.noContent().build();
    }
}
