package com.ragqa.service;

import com.ragqa.dto.ChatRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * 问答服务
 * 
 * 作用：处理用户的问答请求，支持流式和非流式两种模式
 * 
 * 两种响应模式：
 * 1. 非流式（chat）：等LLM生成完整回答后一次性返回
 * 2. 流式（streamChat）：通过SSE（Server-Sent Events）实时推送回答片段
 * 
 * 流式的优势：
 * - 用户可以立即看到回答，无需等待完整生成
 * - 更好的用户体验，特别是长回答场景
 * - 可以实现打字机效果
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChatService {
    
    /** RAG服务，处理核心检索逻辑 */
    private final RagService ragService;
    /** Spring AI ChatClient构建器 */
    private final ChatClient.Builder chatClientBuilder;
    
    /** 是否启用流式输出，配置项：chat.streaming */
    @Value("${chat.streaming:true}")
    private boolean streamingEnabled;
    
    /**
     * 非流式问答
     * 
     * 等待LLM生成完整回答后一次性返回
     * @param request 问答请求（包含问题和知识库ID）
     * @return 完整回答字符串
     */
    public String chat(ChatRequest request) {
        log.info("收到问答请求: {}", request.getMessage());
        // 委托给RagService处理
        return ragService.chat(request.getMessage(), request.getKnowledgeBaseId());
    }
    
    /**
     * 流式问答
     * 
     * 通过Flux流式返回回答片段（SSE）
     * @param request 问答请求
     * @return Flux<String> - 回答片段流
     */
    public Flux<String> streamChat(ChatRequest request) {
        log.info("收到流式问答请求: {}, streamingEnabled={}", request.getMessage(), streamingEnabled);
        
        // 如果配置关闭了流式，则回退到非流式
        if (!streamingEnabled) {
            String response = ragService.chat(request.getMessage(), request.getKnowledgeBaseId());
            return Flux.just(response);
        }
        
        try {
            // 1. 检索相关文档
            var docs = ragService.retrieveForStreaming(request.getMessage(), request.getKnowledgeBaseId());
            
            if (docs.isEmpty()) {
                return Flux.just("该知识库暂无文档，请先上传文档。");
            }
            
            // 2. 构建上下文和提示词
            String context = buildContext(docs);
            String prompt = buildPrompt(context, request.getMessage());
            
            // 3. 调用LLM，返回流式响应
            // .stream()会将响应拆分成多个片段
            // .content()获取内容流
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
    
    /**
     * 构建上下文字符串（与RagService相同）
     */
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
    
    /**
     * 构建提示词（与RagService保持一致）
     */
    private String buildPrompt(String context, String question) {
        return """
            你是一个专业的智能问答助手，擅长从提供的文档中准确提取信息并清晰回答用户问题。

            === 参考文档 ===
            %s

            === 用户问题 ===
            %s

            === 回答要求 ===
            1. 只基于参考文档内容回答，不要编造信息
            2. 如果文档中没有相关信息，回答："抱歉，知识库中没有找到与您问题相关的内容。"
            3. 引用文档时使用【文档X】标注来源
            4. 回答结构：先给出结论，再引用证据，最后补充说明（如有）
            5. 对于复杂问题，用分点或编号的方式回答

            === 回答 ===
            """.formatted(context, question);
    }
}
