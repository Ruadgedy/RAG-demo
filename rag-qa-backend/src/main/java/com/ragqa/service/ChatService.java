package com.ragqa.service;

import com.ragqa.dto.ChatRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import java.util.StringJoiner;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatService {
    
    private final ChatClient.Builder chatClientBuilder;
    
    public String chat(ChatRequest request) {
        log.info("收到问答请求: {}", request.getMessage());
        
        String prompt = buildPrompt(request.getMessage());
        
        try {
            String response = chatClientBuilder.build()
                    .prompt(prompt)
                    .call()
                    .content();
            return response;
        } catch (Exception e) {
            log.error("LLM调用失败: {}", e.getMessage());
            return "抱歉，AI服务暂时不可用。请检查配置后重试。";
        }
    }
    
    public Flux<String> streamChat(ChatRequest request) {
        log.info("收到流式问答请求: {}", request.getMessage());
        
        String prompt = buildPrompt(request.getMessage());
        
        return chatClientBuilder.build()
                .prompt(prompt)
                .stream()
                .content()
                .doOnError(e -> {
                    log.error("LLM流式调用失败: {}", e.getMessage());
                });
    }
    
    private String buildPrompt(String message) {
        StringJoiner sj = new StringJoiner("\n");
        sj.add("你是一个智能问答助手，请根据以下上下文回答用户的问题。");
        sj.add("如果上下文中没有相关信息，请如实告知用户。");
        sj.add("");
        sj.add("用户问题: " + message);
        return sj.toString();
    }
}
