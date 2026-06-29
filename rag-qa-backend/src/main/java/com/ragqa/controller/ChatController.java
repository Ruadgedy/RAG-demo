package com.ragqa.controller;

import com.ragqa.dto.ChatRequest;
import com.ragqa.dto.ChatResponse;
import com.ragqa.service.ChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

/**
 * 问答控制器
 *
 * 作用：处理用户的问答请求
 *
 * 接口说明：
 * - POST /api/chat: 非流式问答，等待完整回答后返回
 * - POST /api/chat/stream: 流式问答，通过SSE实时推送回答片段
 *
 * 认证要求：
 * - 需要JWT认证（通过SecurityConfig配置）
 * - 请求头中需携带 Authorization: Bearer <token>
 *
 * 请求格式：
 * {
 *   "message": "用户问题",
 *   "knowledgeBaseId": "知识库UUID"
 * }
 */
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
@Tag(name = "问答", description = "RAG 问答接口")
public class ChatController {

    private final ChatService chatService;

    @Operation(summary = "问答（普通）", description = "基于知识库内容进行问答，等待完整回答后返回；同时把问答记录持久化到聊天历史")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "问答成功",
                    content = @Content(schema = @Schema(implementation = ChatResponse.class))),
            @ApiResponse(responseCode = "401", description = "未认证"),
            @ApiResponse(responseCode = "404", description = "知识库不存在"),
            @ApiResponse(responseCode = "500", description = "服务器内部错误")
    })
    @PostMapping
    public ResponseEntity<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        ChatResponse response = chatService.chat(request);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "问答（流式）", description = "基于知识库内容进行问答，通过 SSE 实时推送回答片段。"
            + "事件类型：session-start（首条，data=sessionId）/ chunk（文本片段）/ sources（P0-01 新增，data=SourceRef 列表 JSON）/ end（收尾标记）")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "问答成功（流式）",
                    content = @Content(mediaType = "text/event-stream")),
            @ApiResponse(responseCode = "401", description = "未认证"),
            @ApiResponse(responseCode = "404", description = "知识库不存在")
    })
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamChat(@Valid @RequestBody ChatRequest request) {
        return chatService.streamChat(request);
    }
}
