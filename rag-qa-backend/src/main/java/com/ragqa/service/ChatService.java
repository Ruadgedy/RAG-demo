package com.ragqa.service;

import com.ragqa.dto.ChatRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * 问答服务
 * 
 * 作用：处理用户问答请求
 * 
 * 说明：
 * - 普通模式：调用RagService进行RAG检索+LLM回答
 * - 流式模式：暂未实现
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChatService {
    
    private final RagService ragService;
    
    /**
     * 普通问答
     * 
     * 接收问题，调用RagService返回回答
     */
    public String chat(ChatRequest request) {
        log.info("收到问答请求: {}", request.getMessage());
        
        // 委托给RagService处理
        return ragService.chat(request.getMessage(), request.getKnowledgeBaseId());
    }
    
    /**
     * 流式问答（暂未实现）
     */
    public Flux<String> streamChat(ChatRequest request) {
        log.info("收到流式问答请求: {}", request.getMessage());
        
        return Flux.just("流式RAG功能暂未实现，请使用普通模式");
    }
}
