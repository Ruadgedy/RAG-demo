package com.ragqa.controller;

import com.ragqa.dto.ChatRequest;
import com.ragqa.service.ChatService;
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
public class ChatController {

    private final ChatService chatService;

    /**
     * 非流式问答
     *
     * 等待LLM生成完整回答后一次性返回
     * 适用于短回答或对响应格式有要求的场景
     *
     * @param request 包含问题和知识库ID
     * @return 完整的回答字符串
     */
    @PostMapping
    public ResponseEntity<String> chat(@RequestBody ChatRequest request) {
        String answer = chatService.chat(request);
        return ResponseEntity.ok(answer);
    }

    /**
     * 流式问答
     *
     * 通过SSE（Server-Sent Events）实时推送回答片段
     * 适用于长回答，用户可以立即看到部分结果
     *
     * @param request 包含问题和知识库ID
     * @return Flux<String> 回答片段流
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamChat(@RequestBody ChatRequest request) {
        return chatService.streamChat(request);
    }
}
