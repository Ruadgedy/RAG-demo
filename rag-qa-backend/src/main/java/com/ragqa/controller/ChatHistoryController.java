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
 * 对话历史控制器（V3 重构版）
 *
 * 【V3 变更】
 * 1. knowledgeBaseId 类型 UUID → String（CHAR(36) 可读 UUID）
 * 2. /api/chat-history/{sessionId} 改为返回展开后的消息列表（前端 ChatView 的 loadSession 不需要改）：
 *    - DB 一条记录 = (query + content) → 展开为 [{role:user, content:query}, {role:assistant, content:content}]
 * 3. /api/knowledge-bases/{kbId}/chat-history 同步改为 String kbId
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "对话历史", description = "聊天历史记录管理接口")
public class ChatHistoryController {

    private final ChatHistoryRepository chatHistoryRepository;

    /**
     * 轻量消息 DTO，用于把单条 chat_history 展开为"用户问 + AI 答"两条消息。
     * 保持与旧版 API 兼容：前端 loadSession 直接用 res.data.map(h => ({role, content}))。
     */
    public record ChatMessageDto(String role, String content) {}

    /**
     * 获取当前用户的所有聊天会话。
     */
    @Operation(summary = "获取所有会话", description = "获取当前用户的所有对话会话")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "获取成功"),
            @ApiResponse(responseCode = "401", description = "未认证")
    })
    @GetMapping("/chat-history")
    public ResponseEntity<List<ChatHistory>> getAllSessions() {
        String userId = getCurrentUserId();
        List<ChatHistory> history = chatHistoryRepository.findByUserIdOrderByCreatedAtDesc(userId);
        return ResponseEntity.ok(history);
    }

    /**
     * 获取指定会话的所有消息。
     *
     * 【V3 变更】每条 chat_history 记录展开为 {user, assistant} 两条消息，
     * 返回 [{role, content}] 列表。前端 ChatView.loadSession 不需要改：
     *   res.data.map(h => ({ role: h.role, content: h.content }))
     */
    @Operation(summary = "获取会话消息", description = "获取指定会话的所有消息，按时间正序排列")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "获取成功",
                    content = @Content(schema = @Schema(implementation = ChatMessageDto.class))),
            @ApiResponse(responseCode = "401", description = "未认证")
    })
    @GetMapping("/chat-history/{sessionId}")
    public ResponseEntity<List<ChatMessageDto>> getHistoryBySession(
            @Parameter(description = "会话ID") @PathVariable String sessionId) {
        String userId = getCurrentUserId();
        List<ChatHistory> turns = chatHistoryRepository
                .findBySessionIdAndUserIdOrderByCreatedAtAsc(sessionId, userId);

        // 把单条记录展开为两条消息：user 问 + assistant 答
        List<ChatMessageDto> messages = new ArrayList<>(turns.size() * 2);
        for (ChatHistory turn : turns) {
            if (turn.getQuery() != null) {
                messages.add(new ChatMessageDto("user", turn.getQuery()));
            }
            if (turn.getContent() != null) {
                messages.add(new ChatMessageDto("assistant", turn.getContent()));
            }
        }
        return ResponseEntity.ok(messages);
    }

    /**
     * 获取知识库的会话列表。
     *
     * 【V3 变更】kbId 类型 UUID → String
     */
    @Operation(summary = "获取知识库的会话列表", description = "获取指定知识库的所有对话会话")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "获取成功"),
            @ApiResponse(responseCode = "401", description = "未认证")
    })
    @GetMapping("/knowledge-bases/{kbId}/chat-history")
    public ResponseEntity<List<ChatHistory>> getHistoryByKnowledgeBase(
            @Parameter(description = "知识库ID（CHAR(36)）") @PathVariable String kbId) {
        List<ChatHistory> history = chatHistoryRepository.findByKnowledgeBaseIdOrderByCreatedAtDesc(kbId);
        return ResponseEntity.ok(history);
    }

    @Operation(summary = "保存消息", description = "保存单条聊天消息到历史记录")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "保存成功",
                    content = @Content(schema = @Schema(implementation = ChatHistory.class))),
            @ApiResponse(responseCode = "401", description = "未认证")
    })
    @PostMapping("/chat-history")
    public ResponseEntity<ChatHistory> saveMessage(@RequestBody ChatHistory chatHistory) {
        ChatHistory saved = chatHistoryRepository.save(chatHistory);
        return ResponseEntity.ok(saved);
    }

    @Operation(summary = "删除会话", description = "删除指定会话及其所有消息")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "删除成功"),
            @ApiResponse(responseCode = "401", description = "未认证")
    })
    @DeleteMapping("/chat-history/{sessionId}")
    public ResponseEntity<Void> deleteSession(
            @Parameter(description = "会话ID") @PathVariable String sessionId) {
        chatHistoryRepository.deleteBySessionId(sessionId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 从 SecurityContext 提取当前用户名作为 userId。
     */
    private String getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof User user) {
            return user.getUsername();
        }
        return "unknown";
    }
}
