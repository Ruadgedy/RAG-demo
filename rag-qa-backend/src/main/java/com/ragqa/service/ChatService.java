package com.ragqa.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 问答服务（V3 重构版）
 *
 * 作用：处理用户的问答请求，支持流式和非流式两种模式
 *
 * 【V3 重大变更 2026-06-28】
 * 1. chat_history 表结构重构：一次问答 = 一条记录（query + content + rag_metadata）
 *    → 替代旧版"user/assistant 各一条 + role 字段"的双记录模式
 * 2. saveHistory 拆解为两步：用户提问先 in-memory 暂存，AI 回答生成后合并成一条 saveTurn()
 *    → 原子性更好（不会出现"只有 user 问题但没有 assistant 回答"的孤儿记录）
 * 3. rag_metadata JSON 字段：每次问答都把 RAG 召回元数据落库（召回文档数/ID列表/片段数/检索耗时）
 *    → 用于后续 RAG 质量评估与可观测性
 *
 * 两种响应模式：
 * 1. 非流式（chat）：等LLM生成完整回答后一次性返回
 * 2. 流式（streamChat）：通过SSE（Server-Sent Events）实时推送回答片段
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChatService {

    /** RAG服务，处理核心检索逻辑 */
    private final RagService ragService;
    /** Spring AI ChatClient构建器 */
    private final ChatClient.Builder chatClientBuilder;
    /** 聊天历史仓库（V3：实体已是 query+content 单记录） */
    private final ChatHistoryRepository chatHistoryRepository;

    /** 是否启用流式输出，配置项：chat.streaming */
    @Value("${chat.streaming:true}")
    private boolean streamingEnabled;

    /**
     * 用于把 rag_metadata 序列化为 JSON 字符串。
     * 与 ChromaService 保持一致（new 一个独立实例，避免和 Spring MVC 共用产生干扰）
     */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 非流式问答
     *
     * @param request 问答请求
     * @return ChatResponse（含 sessionId 与 answer）
     */
    public ChatResponse chat(ChatRequest request) {
        String userId = getCurrentUserId();
        log.info("收到问答请求: userId={}, message={}", userId, request.getMessage());

        // 生成本次问答的会话ID（用户连续对话的多次问答共享，用于关联）
        String sessionId = UUID.randomUUID().toString();

        // 1. 委托给 RagService 执行检索增强生成
        //    【V3】返回值改为 ChatResult(answer, retrievedDocs, retrievalDurationMs)
        RagService.ChatResult result;
        try {
            result = ragService.chat(request.getMessage(), request.getKnowledgeBaseId());
        } catch (Exception e) {
            log.error("RAG 问答失败: userId={}", userId, e);
            // 失败时仍要落库"用户问题"+"错误提示"，便于后续排查
            try {
                saveTurn(sessionId, request.getKnowledgeBaseId(), userId, request.getMessage(),
                        "抱歉，AI服务暂时不可用，请稍后重试。", null);
            } catch (Exception ex) {
                log.warn("[落库告警] 错误回合持久化失败: sessionId={}, userId={}", sessionId, userId, ex);
            }
            return new ChatResponse(sessionId, "抱歉，AI服务暂时不可用，请稍后重试。");
        }

        // 2. 拼装 rag_metadata JSON 并落库
        //    【V3】单条记录：query + content + rag_metadata
        try {
            String ragMetadataJson = buildRagMetadataJson(
                    result.retrievedDocs(), result.retrievalDurationMs());
            saveTurn(sessionId, request.getKnowledgeBaseId(), userId, request.getMessage(),
                    result.answer(), ragMetadataJson);
        } catch (Exception e) {
            // 【修复 2026-06-28】不静默吞——记录 WARN 级别告警
            log.warn("[落库告警] 问答回合持久化失败: sessionId={}, userId={}, message={}",
                    sessionId, userId, request.getMessage(), e);
        }

        log.info("问答完成: sessionId={}, userId={}, answerLength={}, retrievedDocs={}",
                sessionId, userId,
                result.answer() == null ? 0 : result.answer().length(),
                result.retrievedDocs().size());
        return new ChatResponse(sessionId, result.answer());
    }

    /**
     * 流式问答
     *
     * @param request 问答请求
     * @return Flux<String> - 回答片段流
     */
    public Flux<String> streamChat(ChatRequest request) {
        String userId = getCurrentUserId();
        log.info("收到流式问答请求: userId={}, message={}, streamingEnabled={}",
                userId, request.getMessage(), streamingEnabled);

        String sessionId = UUID.randomUUID().toString();

        // 如果配置关闭了流式，则回退到非流式
        if (!streamingEnabled) {
            RagService.ChatResult result;
            try {
                result = ragService.chat(request.getMessage(), request.getKnowledgeBaseId());
            } catch (Exception e) {
                log.error("非流式回退失败: sessionId={}", sessionId, e);
                result = new RagService.ChatResult(
                        "抱歉，AI服务暂时不可用，请稍后重试。", List.of(), 0L);
            }
            try {
                String ragMetadataJson = buildRagMetadataJson(
                        result.retrievedDocs(), result.retrievalDurationMs());
                saveTurn(sessionId, request.getKnowledgeBaseId(), userId, request.getMessage(),
                        result.answer(), ragMetadataJson);
            } catch (Exception e) {
                log.warn("[落库告警] 流式回退回合持久化失败: sessionId={}, userId={}",
                        sessionId, userId, e);
            }
            return Flux.just(result.answer());
        }

        try {
            // 2. 检索相关文档（带计时）
            long retrievalStart = System.currentTimeMillis();
            var docs = ragService.retrieveForStreaming(request.getMessage(), request.getKnowledgeBaseId());
            long retrievalDurationMs = System.currentTimeMillis() - retrievalStart;

            if (docs.isEmpty()) {
                String emptyMsg = "该知识库暂无文档，请先上传文档。";
                try {
                    saveTurn(sessionId, request.getKnowledgeBaseId(), userId, request.getMessage(),
                            emptyMsg, buildRagMetadataJson(List.of(), retrievalDurationMs));
                } catch (Exception e) {
                    log.warn("[落库告警] 空知识库提示持久化失败: sessionId={}, userId={}",
                            sessionId, userId, e);
                }
                return Flux.just(emptyMsg);
            }

            // 3. 构建上下文和提示词
            String context = buildContext(docs);
            String prompt = buildPrompt(context, request.getMessage());

            // 4. 调用 LLM，返回真正的流式响应
            StringBuilder accumulator = new StringBuilder();
            // 把 docs 列表引用捕获，doOnComplete 时用
            final long finalRetrievalDurationMs = retrievalDurationMs;
            final List<RagService.RetrievalResult> finalDocs = docs;

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
                                String ragMetadataJson = buildRagMetadataJson(
                                        finalDocs, finalRetrievalDurationMs);
                                saveTurn(sessionId, request.getKnowledgeBaseId(), userId, request.getMessage(),
                                        fullAnswer, ragMetadataJson);
                                log.info("流式问答完成并落库: sessionId={}, userId={}, answerLength={}, retrievedDocs={}",
                                        sessionId, userId, fullAnswer.length(), finalDocs.size());
                            } catch (Exception e) {
                                // 【修复 2026-06-28】不静默吞——失败时 WARN 告警
                                log.warn("[落库告警] 流式回合持久化失败: sessionId={}, userId={}, answerLength={}",
                                        sessionId, userId, fullAnswer.length(), e);
                            }
                        }).subscribeOn(Schedulers.boundedElastic()).subscribe();
                    })
                    .doOnError(e -> {
                        log.error("流式响应错误: sessionId={}", sessionId, e);
                        try {
                            saveTurn(sessionId, request.getKnowledgeBaseId(), userId, request.getMessage(),
                                    "抱歉，AI服务暂时不可用，请稍后重试。",
                                    buildRagMetadataJson(finalDocs, finalRetrievalDurationMs));
                        } catch (Exception ex) {
                            log.warn("[落库告警] 流式错误提示持久化失败: sessionId={}, userId={}",
                                    sessionId, userId, ex);
                        }
                    });
        } catch (Exception e) {
            log.error("流式问答失败: sessionId={}", sessionId, e);
            String errorMsg = "抱歉，AI服务暂时不可用，请稍后重试。";
            try {
                saveTurn(sessionId, request.getKnowledgeBaseId(), userId, request.getMessage(),
                        errorMsg, buildRagMetadataJson(List.of(), 0L));
            } catch (Exception ex) {
                log.warn("[落库告警] 流式外层错误提示持久化失败: sessionId={}, userId={}",
                        sessionId, userId, ex);
            }
            return Flux.just(errorMsg);
        }
    }

    /**
     * 保存一次问答回合（V3：单记录 = query + content + rag_metadata）。
     *
     * 【修复 2026-06-28 第三次】不再静默吞异常——
     *   历史落库失败时把异常向上抛出，由 chat()/streamChat() 的调用方
     *   记录 WARN 级别告警。这样 schema 不匹配、SQL 错误等致命问题不会再被
     *   隐藏为"AI 回答正常 + 0 条落库"的反模式 bug。
     *
     * @param sessionId       会话ID
     * @param knowledgeBaseId 知识库ID（UUID）
     * @param userId          用户名
     * @param query           用户提问
     * @param content         模型回答
     * @param ragMetadataJson RAG 召回元数据 JSON（可为 null）
     */
    private void saveTurn(String sessionId, UUID knowledgeBaseId, String userId,
                          String query, String content, String ragMetadataJson) {
        ChatHistory history = new ChatHistory();
        history.setSessionId(sessionId);
        // V3: knowledge_base_id 改为 CHAR(36)，UUID 序列化为字符串
        history.setKnowledgeBaseId(knowledgeBaseId != null ? knowledgeBaseId.toString() : null);
        history.setUserId(userId);
        history.setQuery(query);
        history.setContent(content);
        history.setRagMetadata(ragMetadataJson);
        ChatHistory saved = chatHistoryRepository.saveAndFlush(history);
        log.info("聊天历史已落库: id={}, sessionId={}, userId={}, queryLength={}, contentLength={}, ragMetadata={}",
                saved.getId(), sessionId, userId,
                query != null ? query.length() : 0,
                content != null ? content.length() : 0,
                ragMetadataJson != null ? "已设置" : "空");
    }

    /**
     * 拼装 rag_metadata JSON 字符串。
     *
     * 格式：
     * <pre>
     * {
     *   "retrieved_doc_count": 3,
     *   "retrieved_chunk_count": 7,
     *   "retrieved_doc_ids": ["uuid1", "uuid2", "uuid3"],
     *   "retrieval_duration_ms": 245
     * }
     * </pre>
     *
     * @param retrievedDocs       实际参与本次生成的检索结果列表
     * @param retrievalDurationMs 检索阶段耗时（毫秒）
     * @return JSON 字符串，序列化失败时返回 null（不阻断主流程）
     */
    private String buildRagMetadataJson(List<RagService.RetrievalResult> retrievedDocs,
                                        long retrievalDurationMs) {
        if (retrievedDocs == null) {
            retrievedDocs = List.of();
        }
        try {
            // 文档 ID 去重（同一文档可能命中多个 chunk）
            List<String> docIds = retrievedDocs.stream()
                    .map(r -> r.source().split("_")[0])  // source 格式 "docId_chunkIndex"
                    .distinct()
                    .collect(Collectors.toList());

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("retrieved_doc_count", docIds.size());
            meta.put("retrieved_chunk_count", retrievedDocs.size());
            meta.put("retrieved_doc_ids", docIds);
            meta.put("retrieval_duration_ms", retrievalDurationMs);

            return objectMapper.writeValueAsString(meta);
        } catch (JsonProcessingException e) {
            log.warn("rag_metadata JSON 序列化失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 构建上下文字符串（与RagService相同）
     */
    private String buildContext(List<RagService.RetrievalResult> results) {
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
