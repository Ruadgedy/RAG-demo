package com.ragqa.controller;

import com.ragqa.model.ChatHistory;
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
@Tag(name = "对话历史", description = "聊天历史记录管理接口")
public class ChatHistoryController {

    private final ChatHistoryRepository chatHistoryRepository;

    @Operation(summary = "获取所有会话", description = "获取当前用户的所有对话会话")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "获取成功"),
            @ApiResponse(responseCode = "401", description = "未认证")
    })
    @GetMapping("/chat-history")
    public ResponseEntity<List<ChatHistory>> getAllSessions() {
        List<ChatHistory> history = chatHistoryRepository.findAll();
        return ResponseEntity.ok(history);
    }

    @Operation(summary = "获取会话消息", description = "获取指定会话的所有消息，按时间正序排列")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "获取成功",
                    content = @Content(schema = @Schema(implementation = ChatHistory.class))),
            @ApiResponse(responseCode = "401", description = "未认证")
    })
    @GetMapping("/chat-history/{sessionId}")
    public ResponseEntity<List<ChatHistory>> getHistoryBySession(
            @Parameter(description = "会话ID") @PathVariable String sessionId) {
        List<ChatHistory> history = chatHistoryRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        return ResponseEntity.ok(history);
    }

    @Operation(summary = "获取知识库的会话列表", description = "获取指定知识库的所有对话会话")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "获取成功"),
            @ApiResponse(responseCode = "401", description = "未认证")
    })
    @GetMapping("/knowledge-bases/{kbId}/chat-history")
    public ResponseEntity<List<ChatHistory>> getHistoryByKnowledgeBase(
            @Parameter(description = "知识库ID") @PathVariable UUID kbId) {
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
}
