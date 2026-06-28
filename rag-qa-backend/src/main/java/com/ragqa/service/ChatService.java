package com.ragqa.service;

import com.ragqa.dto.ChatRequest;
import com.ragqa.dto.ChatResponse;
import com.ragqa.model.ChatHistory;
import com.ragqa.model.User;
import com.ragqa.repository.ChatHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

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
     * 【修复 2026-06-28】
     * 1. 移除 @Transactional：每次 saveAndFlush() 独立自动提交（无事务时 JPA 默认 auto-commit），
     *    这样即使 RagService 抛异常，已保存的 user 问题不会回滚丢失
     * 2. 从 SecurityContext 提取 userId，关联到每条历史记录
     * 3. 将 RagService 调用包裹在 try/catch 中，失败时保存错误提示作为 assistant 记录
     *
     * @param request 问答请求（包含问题和知识库ID）
     * @return ChatResponse（含 sessionId 与 answer）
     */
    public ChatResponse chat(ChatRequest request) {
        String userId = getCurrentUserId();
        log.info("收到问答请求: userId={}, message={}", userId, request.getMessage());

        // 生成本次问答的会话ID，user 与 assistant 两条记录共享
        String sessionId = UUID.randomUUID().toString();

        // 1. 持久化用户问题（saveAndFlush 无事务时自动提交，不受后续异常影响）
        try {
            saveHistory(sessionId, request.getKnowledgeBaseId(), userId, "user", request.getMessage());
        } catch (Exception e) {
            // 【修复 2026-06-28】不静默吞——记录 WARN 级别告警
            log.warn("[落库告警] user 问题持久化失败: sessionId={}, userId={}, message={}",
                    sessionId, userId, request.getMessage(), e);
        }

        // 2. 委托给 RagService 执行检索增强生成（单独 try/catch 保护）
        String answer;
        try {
            answer = ragService.chat(request.getMessage(), request.getKnowledgeBaseId());
        } catch (Exception e) {
            log.error("RAG 问答失败: sessionId={}", sessionId, e);
            answer = "抱歉，AI服务暂时不可用，请稍后重试。";
        }

        // 3. 持久化 AI 回答
        try {
            saveHistory(sessionId, request.getKnowledgeBaseId(), userId, "assistant", answer);
        } catch (Exception e) {
            // 【修复 2026-06-28】不静默吞——记录 WARN 级别告警
            log.warn("[落库告警] assistant 回答持久化失败: sessionId={}, userId={}, answerLength={}",
                    sessionId, userId, answer == null ? 0 : answer.length(), e);
        }

        log.info("问答完成: sessionId={}, userId={}, answerLength={}", sessionId, userId, answer == null ? 0 : answer.length());
        return new ChatResponse(sessionId, answer);
    }

    /**
     * 保存单条聊天历史记录。
     *
     * 【修复 2026-06-28 第三次】不再静默吞异常——
     *   历史落库失败时把异常向上抛出，由 chat()/streamChat() 的调用方
     *   记录 WARN 级别告警。这样 schema 不匹配、SQL 错误等致命问题不会再被
     *   隐藏为"AI 回答正常 + 0 条落库"的反模式 bug。
     *
     * @throws RuntimeException 当数据库写入失败时抛出
     */
    private void saveHistory(String sessionId, UUID knowledgeBaseId, String userId, String role, String content) {
        ChatHistory history = new ChatHistory();
        history.setSessionId(sessionId);
        history.setKnowledgeBaseId(knowledgeBaseId);
        history.setUserId(userId);
        history.setRole(role);
        history.setContent(content);
        ChatHistory saved = chatHistoryRepository.saveAndFlush(history);
        log.info("聊天历史已落库: id={}, sessionId={}, userId={}, role={}, contentLength={}",
                saved.getId(), sessionId, userId, role, content != null ? content.length() : 0);
    }
    
    /**
     * 流式问答
     *
     * 通过Flux流式返回回答片段（SSE），每个 chunk 立即推送给客户端，
     * 同时在后台累积完整回答，流结束后保存到数据库。
     *
     * 【修复 2026-06-28】
     * 1. 用 doOnNext() 累积 chunk + doOnComplete() 保存，替代 collectList() 缓冲全量再重发
     *    —— 之前 collectList 会等所有 chunk 到齐才返回，完全不是真正的流式
     * 2. 新增 userId 关联
     * 3. 进入方法立即保存 user 问题（saveAndFlush 无事务自动提交）
     * 4. doOnError 保存错误提示作为 assistant 记录
     *
     * @param request 问答请求
     * @return Flux<String> - 回答片段流
     */
    public Flux<String> streamChat(ChatRequest request) {
        String userId = getCurrentUserId();
        log.info("收到流式问答请求: userId={}, message={}, streamingEnabled={}",
                userId, request.getMessage(), streamingEnabled);

        // 生成本次问答的会话ID（与非流式保持一致语义）
        String sessionId = UUID.randomUUID().toString();

        // 1. 立即保存 user 问题（saveAndFlush 无事务自动提交，不受后续异常影响）
        //    【修复 2026-06-28】不静默吞——失败时 WARN 告警
        try {
            saveHistory(sessionId, request.getKnowledgeBaseId(), userId, "user", request.getMessage());
        } catch (Exception e) {
            log.warn("[落库告警] user 问题持久化失败: sessionId={}, userId={}, message={}",
                    sessionId, userId, request.getMessage(), e);
        }

        // 如果配置关闭了流式，则回退到非流式
        if (!streamingEnabled) {
            String response;
            try {
                response = ragService.chat(request.getMessage(), request.getKnowledgeBaseId());
            } catch (Exception e) {
                log.error("非流式回退失败: sessionId={}", sessionId, e);
                response = "抱歉，AI服务暂时不可用，请稍后重试。";
            }
            try {
                saveHistory(sessionId, request.getKnowledgeBaseId(), userId, "assistant", response);
            } catch (Exception e) {
                log.warn("[落库告警] assistant 回答持久化失败: sessionId={}, userId={}",
                        sessionId, userId, e);
            }
            return Flux.just(response);
        }

        try {
            // 2. 检索相关文档
            var docs = ragService.retrieveForStreaming(request.getMessage(), request.getKnowledgeBaseId());

            if (docs.isEmpty()) {
                String emptyMsg = "该知识库暂无文档，请先上传文档。";
                try {
                    saveHistory(sessionId, request.getKnowledgeBaseId(), userId, "assistant", emptyMsg);
                } catch (Exception e) {
                    log.warn("[落库告警] 空知识库提示持久化失败: sessionId={}, userId={}",
                            sessionId, userId, e);
                }
                return Flux.just(emptyMsg);
            }

            // 3. 构建上下文和提示词
            String context = buildContext(docs);
            String prompt = buildPrompt(context, request.getMessage());

            // 4. 调用LLM，返回真正的流式响应
            //    - doOnNext: 每个 chunk 立即透传给客户端（不缓冲），同时在 StringBuilder 中累积
            //    - doOnComplete: 所有 chunk 发送完毕后，保存完整回答到数据库
            //    - doOnError: 流式失败时保存错误提示
            StringBuilder accumulator = new StringBuilder();

            return chatClientBuilder.build()
                    .prompt(prompt)
                    .stream()
                    .content()
                    .doOnNext(chunk -> accumulator.append(chunk))
                    .doOnComplete(() -> {
                        // 把阻塞的 JPA 操作调度到弹性线程池，避免在 Netty 事件循环中执行
                        String fullAnswer = accumulator.toString();
                        Mono.fromRunnable(() -> {
                            try {
                                saveHistory(sessionId, request.getKnowledgeBaseId(), userId, "assistant", fullAnswer);
                                log.info("流式问答完成并落库: sessionId={}, userId={}, answerLength={}",
                                        sessionId, userId, fullAnswer.length());
                            } catch (Exception e) {
                                // 【修复 2026-06-28】不静默吞——失败时 WARN 告警
                                log.warn("[落库告警] 流式 assistant 回答持久化失败: sessionId={}, userId={}, answerLength={}",
                                        sessionId, userId, fullAnswer.length(), e);
                            }
                        }).subscribeOn(Schedulers.boundedElastic()).subscribe();
                    })
                    .doOnError(e -> {
                        log.error("流式响应错误: sessionId={}", sessionId, e);
                        try {
                            saveHistory(sessionId, request.getKnowledgeBaseId(), userId, "assistant",
                                    "抱歉，AI服务暂时不可用，请稍后重试。");
                        } catch (Exception ex) {
                            log.warn("[落库告警] 错误提示持久化失败: sessionId={}, userId={}",
                                    sessionId, userId, ex);
                        }
                    });
        } catch (Exception e) {
            log.error("流式问答失败: sessionId={}", sessionId, e);
            String errorMsg = "抱歉，AI服务暂时不可用，请稍后重试。";
            try {
                saveHistory(sessionId, request.getKnowledgeBaseId(), userId, "assistant", errorMsg);
            } catch (Exception ex) {
                log.warn("[落库告警] 错误提示持久化失败: sessionId={}, userId={}",
                        sessionId, userId, ex);
            }
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

    /**
     * 从 Spring Security 上下文提取当前用户ID（username）。
     *
     * JwtAuthenticationFilter 在请求进入时把 User（实现 UserDetails）
     * 存入 SecurityContext 的 principal，这里安全地取出。
     * 如果上下文为空（理论上不会，因为 /api/** 需要认证），返回 "unknown"。
     */
    private String getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof User user) {
            return user.getUsername();
        }
        log.warn("SecurityContext 中未找到用户信息，可能认证已失效");
        return "unknown";
    }
}
