package com.ragqa.service;

import com.ragqa.dto.ChatRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatService {
    
    private final RagService ragService;
    private final ChatClient.Builder chatClientBuilder;
    
    @Value("${chat.streaming:true}")
    private boolean streamingEnabled;
    
    public String chat(ChatRequest request) {
        log.info("收到问答请求: {}", request.getMessage());
        return ragService.chat(request.getMessage(), request.getKnowledgeBaseId());
    }
    
    public Flux<String> streamChat(ChatRequest request) {
        log.info("收到流式问答请求: {}, streamingEnabled={}", request.getMessage(), streamingEnabled);
        
        if (!streamingEnabled) {
            String response = ragService.chat(request.getMessage(), request.getKnowledgeBaseId());
            return Flux.just(response);
        }
        
        try {
            var docs = ragService.retrieveForStreaming(request.getMessage(), request.getKnowledgeBaseId());
            
            if (docs.isEmpty()) {
                return Flux.just("该知识库暂无文档，请先上传文档。");
            }
            
            String context = buildContext(docs);
            String prompt = buildPrompt(context, request.getMessage());
            
            return chatClientBuilder.build()
                    .prompt(prompt)
                    .stream()
                    .content()
                    .doOnError(e -> log.error("流式响应错误: {}", e.getMessage()));
        } catch (Exception e) {
            log.error("流式问答失败: {}", e.getMessage());
            return Flux.just("抱歉，AI服务暂时不可用，请稍后重试。");
        }
    }
    
    private String buildContext(java.util.List<RagService.RetrievalResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("参考文档：\n\n");
        for (int i = 0; i < results.size(); i++) {
            RagService.RetrievalResult r = results.get(i);
            sb.append("【文档").append(i + 1).append("】\n");
            sb.append(r.content()).append("\n\n");
        }
        return sb.toString();
    }
    
    private String buildPrompt(String context, String question) {
        return """
            你是一个智能问答助手，请根据以下参考文档回答用户的问题。
            如果参考文档中没有相关信息，请如实告知用户。
            
            %s
            用户问题：%s
            """.formatted(context, question);
    }
}
