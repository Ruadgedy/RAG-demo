package com.ragqa.controller;

import com.ragqa.dto.ChatRequest;
import com.ragqa.service.ChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

    @Operation(summary = "问答（普通）", description = "基于知识库内容进行问答，等待完整回答后返回")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "问答成功",
                    content = @Content(mediaType = "text/plain", schema = @Schema(type = "string"))),
            @ApiResponse(responseCode = "401", description = "未认证"),
            @ApiResponse(responseCode = "404", description = "知识库不存在"),
            @ApiResponse(responseCode = "500", description = "服务器内部错误")
    })
    @PostMapping
    public ResponseEntity<String> chat(@RequestBody ChatRequest request) {
        String answer = chatService.chat(request);
        return ResponseEntity.ok(answer);
    }

    @Operation(summary = "问答（流式）", description = "基于知识库内容进行问答，通过 SSE 实时推送回答片段")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "问答成功（流式）",
                    content = @Content(mediaType = "text/event-stream")),
            @ApiResponse(responseCode = "401", description = "未认证"),
            @ApiResponse(responseCode = "404", description = "知识库不存在")
    })
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamChat(@RequestBody ChatRequest request) {
        return chatService.streamChat(request);
    }
}
