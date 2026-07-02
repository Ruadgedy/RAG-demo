package com.ragqa.controller;

import com.ragqa.model.ChatHistory;
import com.ragqa.model.User;
import com.ragqa.repository.ChatHistoryRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

/**
 * 对话历史控制器（V6 兼容版）
 *
 * 【V6 2026-06-30】
 * - 新增 ConversationController 管理对话组
 * - 本控制器保留旧接口兼容，标记为 @Deprecated
 * - 旧接口按 conversation_id 聚合返回
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "对话历史", description = "聊天历史记录管理接口（V6 兼容）")
public class ChatHistoryController {

    private final ChatHistoryRepository chatHistoryRepository;

    /**
     * 轻量消息 DTO
     */
    public record ChatMessageDto(String role, String content) {}

    /**
     * 【V6 @Deprecated】获取当前用户的所有聊天会话
     * 建议使用 GET /api/conversations
     */
    @Deprecated
    @Operation(summary = "获取所有会话（已废弃）", description = "建议使用 GET /api/conversations")
    @GetMapping("/chat-history")
    public ResponseEntity<List<ChatHistory>> getAllSessions() {
        String userId = getCurrentUserId();
        List<ChatHistory> history = chatHistoryRepository.findByUserIdOrderByCreatedAtDesc(userId);
        return ResponseEntity.ok(history);
    }

    /**
     * 【V6 @Deprecated】获取指定会话的所有消息
     * 建议使用 GET /api/conversations/{id}/messages
     */
    @Deprecated
    @Operation(summary = "获取会话消息（已废弃）", description = "建议使用 GET /api/conversations/{id}/messages")
    @GetMapping("/chat-history/{sessionId}")
    public ResponseEntity<List<ChatMessageDto>> getHistoryBySession(
            @Parameter(description = "会话ID") @PathVariable String sessionId) {
        // V6: 此接口已废弃，请使用 GET /api/conversations/{id}/messages
        return ResponseEntity.status(410).build();
    }

    /**
     * 【V6 @Deprecated】获取知识库的会话列表
     * 建议使用 GET /api/conversations
     */
    @Deprecated
    @Operation(summary = "获取知识库的会话列表（已废弃）", description = "建议使用 GET /api/conversations")
    @GetMapping("/knowledge-bases/{kbId}/chat-history")
    public ResponseEntity<List<ChatHistory>> getHistoryByKnowledgeBase(
            @Parameter(description = "知识库ID") @PathVariable String kbId) {
        // V6: 此接口已废弃，请使用 GET /api/conversations
        return ResponseEntity.status(410).build();
    }

    /**
     * 【V6 @Deprecated】保存消息
     */
    @Deprecated
    @Operation(summary = "保存消息（已废弃）", description = "消息由 ChatService 自动保存")
    @PostMapping("/chat-history")
    public ResponseEntity<ChatHistory> saveMessage(@RequestBody ChatHistory chatHistory) {
        ChatHistory saved = chatHistoryRepository.save(chatHistory);
        return ResponseEntity.ok(saved);
    }

    /**
     * 【V6 @Deprecated】删除会话
     * 建议使用 DELETE /api/conversations/{id}
     * 实现已移除（原 deleteBySessionId 已废弃）
     */
    @Deprecated
    @Operation(summary = "删除会话（已废弃）", description = "建议使用 DELETE /api/conversations/{id}")
    @DeleteMapping("/chat-history/{sessionId}")
    public ResponseEntity<Void> deleteSession(
            @Parameter(description = "会话ID") @PathVariable String sessionId) {
        // V6: 此接口已废弃，请使用 DELETE /api/conversations/{id}
        return ResponseEntity.status(410).build();  // 410 Gone
    }

    private String getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof User user) {
            return user.getUsername();
        }
        return "unknown";
    }
}