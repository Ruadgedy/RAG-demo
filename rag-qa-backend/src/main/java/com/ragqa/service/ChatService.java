package com.ragqa.service;

import com.ragqa.dto.ChatRequest;
import com.ragqa.dto.ChatResponse;
import com.ragqa.model.ChatHistory;
import com.ragqa.repository.ChatHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;

import java.util.UUID;

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
    /** 聊天历史仓库，持久化 user 问题与 AI 回答 */
    private final ChatHistoryRepository chatHistoryRepository;
    
    /** 是否启用流式输出，配置项：chat.streaming */
    @Value("${chat.streaming:true}")
    private boolean streamingEnabled;
    
    /**
     * 非流式问答
     *
     * 等待LLM生成完整回答后一次性返回，同时把 user 问题和 assistant 回答
     * 持久化到 chat_history 表（共享同一 sessionId），供前端「聊天历史」展示。
     *
     * 【修复 2026-06-27 部署反馈】
     * 部署后历史未落库，问题在于：
     * 1. save() 在无事务包裹时不立即 flush，依赖隐式事务可能丢失
     * 2. catch 只 log warn + message，定位不到真实异常（DataIntegrityViolation 等）
     * 3. 没显式 @Transactional 边界，RagService 内部若抛出导致事务回滚，save 也连带回滚
     *
     * 修复：saveAndFlush 强制立即写入 + @Transactional + 异常 stack trace。
     *
     * @param request 问答请求（包含问题和知识库ID）
     * @return ChatResponse（含 sessionId 与 answer）
     */
    @Transactional
    public ChatResponse chat(ChatRequest request) {
        log.info("收到问答请求: {}", request.getMessage());

        // 生成本次问答的会话ID，user 与 assistant 两条记录共享
        String sessionId = UUID.randomUUID().toString();

        // 1. 持久化用户问题（saveAndFlush 强制立即写入，失败立即抛出异常）
        saveHistory(sessionId, request.getKnowledgeBaseId(), "user", request.getMessage());

        // 2. 委托给 RagService 执行检索增强生成
        String answer = ragService.chat(request.getMessage(), request.getKnowledgeBaseId());

        // 3. 持久化 AI 回答
        saveHistory(sessionId, request.getKnowledgeBaseId(), "assistant", answer);

        log.info("问答完成并落库: sessionId={}", sessionId);
        return new ChatResponse(sessionId, answer);
    }

    /**
     * 保存单条聊天历史记录。
     *
     * 【修复 2026-06-27】
     * - 使用 saveAndFlush 替代 save，强制立即写入数据库（避免依赖外层事务 commit）
     * - 异常日志增加 stack trace（之前只打 message，定位 DataIntegrity 之类错误困难）
     * - 成功时打 info 日志，便于排查「是否真的落库了」
     *
     * 容错策略：历史记录属于辅助功能，持久化失败不应影响主问答流程，
     * 因此捕获异常仅记录 error 日志（带堆栈），不向上抛出。
     */
    private void saveHistory(String sessionId, java.util.UUID knowledgeBaseId, String role, String content) {
        try {
            ChatHistory history = new ChatHistory();
            history.setSessionId(sessionId);
            history.setKnowledgeBaseId(knowledgeBaseId);
            history.setRole(role);
            history.setContent(content);
            ChatHistory saved = chatHistoryRepository.saveAndFlush(history);
            log.debug("聊天历史已落库: id={}, sessionId={}, role={}", saved.getId(), sessionId, role);
        } catch (Exception e) {
            // 关键修复：传 e 作为最后一个参数，让 SLF4J 输出完整堆栈
            log.error("保存聊天历史失败（不阻断问答）: sessionId={}, role={}",
                    sessionId, role, e);
        }
    }
    
    /**
     * 流式问答
     *
     * 通过Flux流式返回回答片段（SSE）。
     *
     * 【修复 2026-06-27 部署反馈】
     * 原实现完全没保存历史——一旦前端切换到流式接口，聊天历史侧边栏就为空。
     * 现改造：
     * 1. 进入方法先生成 sessionId，立即保存 user 问题
     * 2. 流式响应通过 .collectList().map() 收集完整内容后再保存 assistant 回答
     * 3. doOnError 保存"流式生成失败"提示作为 assistant 内容（保证前端历史可追溯）
     *
     * @param request 问答请求
     * @return Flux<String> - 回答片段流
     */
    public Flux<String> streamChat(ChatRequest request) {
        log.info("收到流式问答请求: {}, streamingEnabled={}", request.getMessage(), streamingEnabled);

        // 生成本次问答的会话ID（与非流式保持一致语义）
        String sessionId = UUID.randomUUID().toString();

        // 1. 立即保存 user 问题（saveAndFlush 立即写入，不依赖后续事务）
        saveHistory(sessionId, request.getKnowledgeBaseId(), "user", request.getMessage());

        // 如果配置关闭了流式，则回退到非流式
        if (!streamingEnabled) {
            String response = ragService.chat(request.getMessage(), request.getKnowledgeBaseId());
            saveHistory(sessionId, request.getKnowledgeBaseId(), "assistant", response);
            return Flux.just(response);
        }

        try {
            // 2. 检索相关文档
            var docs = ragService.retrieveForStreaming(request.getMessage(), request.getKnowledgeBaseId());

            if (docs.isEmpty()) {
                String emptyMsg = "该知识库暂无文档，请先上传文档。";
                saveHistory(sessionId, request.getKnowledgeBaseId(), "assistant", emptyMsg);
                return Flux.just(emptyMsg);
            }

            // 3. 构建上下文和提示词
            String context = buildContext(docs);
            String prompt = buildPrompt(context, request.getMessage());

            // 4. 调用LLM，返回流式响应
            // .stream()会将响应拆分成多个片段
            // .content()获取内容流
            return chatClientBuilder.build()
                    .prompt(prompt)
                    .stream()
                    .content()
                    .collectList()                                  // 收集所有片段
                    .map(chunks -> {
                        // 拼接完整回答
                        String fullAnswer = String.join("", chunks);
                        saveHistory(sessionId, request.getKnowledgeBaseId(), "assistant", fullAnswer);
                        log.info("流式问答完成并落库: sessionId={}", sessionId);
                        return chunks;
                    })
                    .flatMapMany(Flux::fromIterable)
                    .doOnError(e -> {
                        log.error("流式响应错误: {}", e.getMessage(), e);
                        // 流式失败时保存错误提示，方便前端历史展示
                        saveHistory(sessionId, request.getKnowledgeBaseId(), "assistant",
                                "抱歉，AI服务暂时不可用，请稍后重试。");
                    });
        } catch (Exception e) {
            log.error("流式问答失败: {}", e.getMessage(), e);
            String errorMsg = "抱歉，AI服务暂时不可用，请稍后重试。";
            saveHistory(sessionId, request.getKnowledgeBaseId(), "assistant", errorMsg);
            return Flux.just(errorMsg);
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
