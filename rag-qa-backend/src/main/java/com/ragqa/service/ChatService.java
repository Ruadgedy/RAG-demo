package com.ragqa.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragqa.agent.AgenticRagService;
import com.ragqa.agent.trace.AgentTrace;
import com.ragqa.agent.trace.AgentTraceCollector;
import com.ragqa.dto.ChatMessage;
import com.ragqa.dto.ChatRequest;
import com.ragqa.dto.ChatResponse;
import com.ragqa.dto.SourceRef;
import com.ragqa.model.ChatHistory;
import com.ragqa.model.Conversation;
import com.ragqa.model.User;
import com.ragqa.repository.ChatHistoryRepository;
import com.ragqa.repository.ConversationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.codec.ServerSentEvent;
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
 * 问答服务（V6 重构版）
 *
 * 【V6 重大变更 2026-06-30】
 * 重构为"对话组 + 单次问答"模型：
 * - conversation：对话组，一次完整的多轮对话
 * - chat_history：单次问答（用户提问+AI回答），归属于某个 conversation
 *
 * 滑动窗口：
 * - 每次问答从数据库取最近 N 轮（historyWindow 控制）
 * - 注入 prompt，实现多轮上下文理解
 *
 * 标题生成：
 * - 第一轮问答完成后，调用 LLM 生成对话组标题
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChatService {

    private final RagService ragService;
    private final ChatClient.Builder chatClientBuilder;
    private final ChatHistoryRepository chatHistoryRepository;
    private final ConversationRepository conversationRepository;
    private final AgentTraceCollector agentTraceCollector;

    /** 是否启用流式输出 */
    @Value("${chat.streaming:true}")
    private boolean streamingEnabled;

    /** 来源 snippet 截取长度 */
    @Value("${chat.source.snippet-length:200}")
    private int sourceSnippetLength;

    /** 默认滑动窗口大小 */
    @Value("${rag.history.turns:3}")
    private int defaultHistoryWindow;

    /** 全局 RAG 模式默认值；conversation.rag_mode 非 null 时 per-conversation 覆盖 */
    @Value("${rag.mode:linear}")
    private String globalRagMode;

    private final AgenticRagService agenticRagService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 获取或创建对话组
     *
     * @param request 问答请求
     * @return Conversation 对象
     */
    private Conversation getOrCreateConversation(ChatRequest request) {
        String conversationId = request.getConversationId();

        if (conversationId != null && !conversationId.isBlank()) {
            // 使用已有对话组
            return conversationRepository.findById(conversationId)
                    .orElseThrow(() -> new IllegalArgumentException("对话组不存在: " + conversationId));
        }

        // 创建新对话组
        Conversation conv = new Conversation();
        conv.setUserId(getCurrentUserId());
        conv.setKnowledgeBaseId(request.getKnowledgeBaseId().toString());

        // 使用请求中的 historyWindow 或默认值
        int window = request.getHistoryWindow() != null ?
                Math.max(1, Math.min(10, request.getHistoryWindow())) : defaultHistoryWindow;
        conv.setHistoryWindow(window);

        // 保存第一轮的原始提问
        conv.setFirstQuery(truncate(request.getMessage(), 100));

        conv = conversationRepository.save(conv);
        log.info("创建新对话组: conversationId={}, userId={}, window={}",
                conv.getId(), conv.getUserId(), conv.getHistoryWindow());

        return conv;
    }

    /**
     * 获取对话历史（用于 prompt 注入）
     *
     * @param conversationId 对话组ID
     * @param windowSize 窗口大小
     * @return 历史消息列表（转换为 dto.ChatMessage 供 RagService 使用）
     */
    private List<ChatMessage> getHistory(String conversationId, int windowSize) {
        if (conversationId == null || windowSize <= 0) {
            return List.of();
        }

        List<ChatHistory> history = chatHistoryRepository.findRecentByConversationId(
                conversationId, PageRequest.of(0, windowSize * 2));  // ×2 因为每轮有 query + content

        return history.stream()
                .flatMap(h -> {
                    ChatMessage userMsg = new ChatMessage("user", h.getQuery());
                    ChatMessage assistantMsg = new ChatMessage("assistant", h.getContent());
                    return java.util.stream.Stream.of(userMsg, assistantMsg);
                })
                .collect(Collectors.toList());
    }

    /**
     * 生成对话组标题（第一轮完成后调用）
     */
    private void generateTitle(String conversationId, String firstQuery, String firstAnswer) {
        try {
            String prompt = """
                根据以下对话生成一个简短的标题（最多20个字符）：

                用户：%s
                助手：%s

                标题：
                """.formatted(truncate(firstQuery, 100), truncate(firstAnswer, 200));

            String title = chatClientBuilder.build()
                    .prompt(prompt)
                    .call()
                    .content();

            // 清理标题（去掉引号、换行等）
            String rawTitle = title.trim().replaceAll("^\"|\"$", "").replaceAll("\n", " ");
            if (rawTitle.length() > 20) {
                rawTitle = rawTitle.substring(0, 20) + "…";
            }
            final String finalTitle = rawTitle;

            // 更新对话组
            conversationRepository.findById(conversationId).ifPresent(conv -> {
                conv.setTitle(finalTitle);
                conversationRepository.save(conv);
                log.info("生成对话组标题: conversationId={}, title={}", conversationId, finalTitle);
            });

        } catch (Exception e) {
            log.warn("生成标题失败: conversationId={}, err={}", conversationId, e.getMessage());
        }
    }

    /**
     * 保存一轮问答
     *
     * @param chatId 预生成的 chatId，同时写入 chat_history.chat_id 与 agent_trace.chat_id
     *               （F21：保证 trace 行能与本轮 chat_history 行 join 起来）
     */
    private ChatHistory saveTurn(String chatId, String conversationId, UUID knowledgeBaseId, String userId,
                                  String query, String content, String ragMetadataJson,
                                  List<SourceRef> sources, int turnIndex) {
        ChatHistory history = new ChatHistory();
        history.setConversationId(conversationId);
        history.setChatId(chatId);
        history.setTurnIndex(turnIndex);
        history.setKnowledgeBaseId(knowledgeBaseId != null ? knowledgeBaseId.toString() : null);
        history.setUserId(userId);
        history.setQuery(query);
        history.setContent(content);
        history.setRagMetadata(ragMetadataJson);

        // 序列化 sources 到 chatMetadata
        if (sources != null && !sources.isEmpty()) {
            try {
                Map<String, Object> chatMeta = new LinkedHashMap<>();
                chatMeta.put("sources", sources);
                history.setChatMetadata(objectMapper.writeValueAsString(chatMeta));
            } catch (JsonProcessingException e) {
                log.warn("序列化 sources 失败: {}", e.getMessage());
            }
        }

        ChatHistory saved = chatHistoryRepository.saveAndFlush(history);
        log.info("问答落库: chatId={}, conversationId={}, turnIndex={}, queryLength={}, contentLength={}",
                saved.getChatId(), conversationId, turnIndex,
                query != null ? query.length() : 0,
                content != null ? content.length() : 0);

        return saved;
    }

    // ==================== 公开接口 ====================

    /**
     * 非流式问答
     */
    public ChatResponse chat(ChatRequest request) {
        String userId = getCurrentUserId();
        log.info("收到问答请求: userId={}, message={}", userId, request.getMessage());

        // 获取或创建对话组
        Conversation conv = getOrCreateConversation(request);
        String conversationId = conv.getId();

        // 获取历史（滑动窗口）
        List<ChatMessage> history = getHistory(conversationId, conv.getHistoryWindow());

        // chatId 唯一生成一次：F21 让 agent_trace.chat_id 与 chat_history.chat_id 对齐
        String chatId = UUID.randomUUID().toString();

        // 执行问答（按 ragMode 路由：conversation.ragMode > 全局 rag.mode 默认值）
        RagService.ChatResult result;
        String ragMode = conv.getRagMode() != null ? conv.getRagMode() : globalRagMode;
        try {
            if ("agentic".equals(ragMode)) {
                log.info("[rag路由] agentic: conversationId={}, chatId={}", conversationId, chatId);
                result = agenticRagService.chat(chatId, request.getMessage(), request.getKnowledgeBaseId(), history, conv.getHistoryWindow());
            } else {
                result = ragService.chat(request.getMessage(), request.getKnowledgeBaseId(), history, conv.getHistoryWindow());
            }
        } catch (Exception e) {
            log.error("RAG 问答失败: conversationId={}", conversationId, e);
            return new ChatResponse(conversationId, chatId, "抱歉，AI服务暂时不可用，请稍后重试。", List.of());
        }

        // 落库
        int turnIndex = chatHistoryRepository.getNextTurnIndex(conversationId);
        List<SourceRef> sources = buildSourceRefs(result.retrievedDocs());
        String ragMetadataJson = buildRagMetadataJson(
                result.retrievedDocs(), result.retrievalDurationMs(), result.rewrittenQuery(),
                result.agentMode(), result.agentRounds(), result.degraded());

        saveTurn(chatId, conversationId, request.getKnowledgeBaseId(), userId,
                request.getMessage(), result.answer(), ragMetadataJson, sources, turnIndex);

        // 第一轮生成标题
        if (turnIndex == 0) {
            Mono.fromRunnable(() ->
                    generateTitle(conversationId, request.getMessage(), result.answer())
            ).subscribeOn(Schedulers.boundedElastic()).subscribe();
        }

        log.info("问答完成: conversationId={}, turnIndex={}, retrievedDocs={}, agentMode={}, rounds={}, degraded={}",
                conversationId, turnIndex, result.retrievedDocs().size(),
                result.agentMode(), result.agentRounds(), result.degraded());

        return new ChatResponse(conversationId, chatId, result.answer(), sources);
    }

    /**
     * 流式问答（内部使用，由 Controller 传入已捕获的 Authentication）
     */
    public Flux<ServerSentEvent<String>> streamChat(ChatRequest request, Authentication auth) {
        String userId = auth != null && auth.getPrincipal() instanceof User user ? user.getUsername() : "unknown";
        if ("unknown".equals(userId)) {
            log.warn("streamChat 收到无效 Authentication");
        }
        log.info("收到流式问答: userId={}, message={}", userId, request.getMessage());

        // 获取或创建对话组
        Conversation conv;
        try {
            conv = getOrCreateConversation(request);
        } catch (Exception e) {
            log.error("获取对话组失败", e);
            return Flux.just(
                    sseEvent("error", e.getMessage()),
                    sseEvent("end", "")
            );
        }
        final String conversationId = conv.getId();
        final int historyWindow = conv.getHistoryWindow();

        // 如果配置关闭了流式
        if (!streamingEnabled) {
            return handleNonStreaming(request, conversationId, userId, historyWindow);
        }

        try {
            // 获取历史
            List<ChatMessage> history = getHistory(conversationId, historyWindow);

            // F21：chatId 一次性生成，同时供 agent trace 关联与 DB chat_history.chat_id 写入
            int turnIndex = chatHistoryRepository.getNextTurnIndex(conversationId);
            final String chatId = UUID.randomUUID().toString();
            final int finalTurnIndex = turnIndex;

            // 检索（按 ragMode 路由：conversation.ragMode > 全局 rag.mode）
            String ragMode = conv.getRagMode() != null ? conv.getRagMode() : globalRagMode;
            RagService.ChatResult retrievalResult;
            if ("agentic".equals(ragMode)) {
                log.info("[rag路由] agentic(stream): conversationId={}, chatId={}", conversationId, chatId);
                retrievalResult = agenticRagService.retrieveForStreaming(
                        chatId, request.getMessage(), request.getKnowledgeBaseId(), history, historyWindow);
            } else {
                retrievalResult = ragService.retrieveForStreaming(
                    request.getMessage(), request.getKnowledgeBaseId(), history, historyWindow);
            }
            List<RagService.RetrievalResult> docs = retrievalResult.retrievedDocs();
            long retrievalDurationMs = retrievalResult.retrievalDurationMs();
            String rewrittenQuery = retrievalResult.rewrittenQuery();

            // F21：拉取 agent trace，用于 SSE agent_step 事件
            final List<AgentTrace> agentTraces = "agentic".equals(ragMode)
                    ? agentTraceCollector.getTraces(chatId)
                    : List.of();

            if (docs.isEmpty()) {
                return handleEmptyKnowledgeBase(chatId, conversationId, request, userId,
                        retrievalDurationMs, rewrittenQuery, retrievalResult);
            }

            // 构建 prompt
            String context = buildContext(docs);
            String prompt = ragService.buildPromptWithHistory(context, history, request.getMessage(), historyWindow);

            // 流式 LLM 调用
            Flux<String> llmStream = Flux.defer(() ->
                    chatClientBuilder.build()
                            .prompt(prompt)
                            .advisors(new SimpleLoggerAdvisor())
                            .stream()
                            .content()
            );

            final long finalRetrievalDurationMs = retrievalDurationMs;
            final List<RagService.RetrievalResult> finalDocs = docs;
            final String finalRewrittenQuery = rewrittenQuery;
            final String finalAgentMode = retrievalResult.agentMode();
            final int finalAgentRounds = retrievalResult.agentRounds();
            final boolean finalDegraded = retrievalResult.degraded();
            final List<AgentTrace> finalAgentTraces = agentTraces;
            StringBuilder accumulator = new StringBuilder();

            return Flux.concat(
                    // 1. session-start 事件
                    Flux.just(sseEvent("session-start", conversationId + "|" + chatId)),

                    // 1.5 F21：agent_step 事件流（先 chunk 之前，前端可先展示"思考过程"）
                    buildAgentStepEvents(chatId, finalAgentTraces),

                    // 2. LLM 流
                    Flux.defer(() ->
                            llmStream.map(chunk -> {
                                accumulator.append(chunk);
                                return sseEvent("chunk", chunk);
                            })
                            .doOnComplete(() -> {
                                // 3. 流完成后落库
                                Mono.fromRunnable(() -> {
                                    String fullAnswer = accumulator.toString();
                                    List<SourceRef> sources = buildSourceRefs(finalDocs);
                                    String ragMetadataJson = buildRagMetadataJson(
                                            finalDocs, finalRetrievalDurationMs, finalRewrittenQuery,
                                            finalAgentMode, finalAgentRounds, finalDegraded);

                                    ChatHistory chatRecord = new ChatHistory();
                                    chatRecord.setConversationId(conversationId);
                                    chatRecord.setChatId(chatId);
                                    chatRecord.setTurnIndex(finalTurnIndex);
                                    chatRecord.setKnowledgeBaseId(request.getKnowledgeBaseId().toString());
                                    chatRecord.setUserId(userId);
                                    chatRecord.setQuery(request.getMessage());
                                    chatRecord.setContent(fullAnswer);
                                    chatRecord.setRagMetadata(ragMetadataJson);

                                    // 序列化 sources
                                    if (sources != null && !sources.isEmpty()) {
                                        try {
                                            Map<String, Object> chatMeta = new LinkedHashMap<>();
                                            chatMeta.put("sources", sources);
                                            chatRecord.setChatMetadata(objectMapper.writeValueAsString(chatMeta));
                                        } catch (JsonProcessingException e) {
                                            log.warn("序列化 sources 失败: {}", e.getMessage());
                                        }
                                    }

                                    chatHistoryRepository.saveAndFlush(chatRecord);
                                    log.info("流式问答落库: chatId={}, conversationId={}, turnIndex={}, agentMode={}, rounds={}, degraded={}",
                                            chatId, conversationId, finalTurnIndex,
                                            finalAgentMode, finalAgentRounds, finalDegraded);

                                    // 第一轮生成标题
                                    if (finalTurnIndex == 0) {
                                        generateTitle(conversationId, request.getMessage(), fullAnswer);
                                    }

                                }).subscribeOn(Schedulers.boundedElastic()).subscribe();
                            })
                    ),

                    // 4. sources + end
                    Flux.defer(() -> {
                        List<SourceRef> sources = buildSourceRefs(finalDocs);
                        return Flux.just(
                                sseEvent("sources", serializeSources(sources)),
                                sseEvent("end", "")
                        );
                    })
            );

        } catch (Exception e) {
            log.error("流式问答失败: conversationId={}", conversationId, e);
            return Flux.just(
                    sseEvent("error", "抱歉，AI服务暂时不可用，请稍后重试。"),
                    sseEvent("end", "")
            );
        }
    }

    private Flux<ServerSentEvent<String>> handleNonStreaming(ChatRequest request, String conversationId,
                                                             String userId, int historyWindow) {
        List<ChatMessage> history = getHistory(conversationId, historyWindow);

        RagService.ChatResult result;
        try {
            result = ragService.chat(request.getMessage(), request.getKnowledgeBaseId(), history, historyWindow);
        } catch (Exception e) {
            log.error("非流式回退失败", e);
            result = new RagService.ChatResult("抱歉，AI服务暂时不可用，请稍后重试。", List.of(), 0L, request.getMessage());
        }

        String chatId = UUID.randomUUID().toString();
        int turnIndex = chatHistoryRepository.getNextTurnIndex(conversationId);
        List<SourceRef> sources = buildSourceRefs(result.retrievedDocs());
        String ragMetadataJson = buildRagMetadataJson(
                result.retrievedDocs(), result.retrievalDurationMs(), result.rewrittenQuery(),
                result.agentMode(), result.agentRounds(), result.degraded());

        saveTurn(chatId, conversationId, request.getKnowledgeBaseId(), userId,
                request.getMessage(), result.answer(), ragMetadataJson, sources, turnIndex);

        final String finalAnswer = result.answer();
        if (turnIndex == 0) {
            Mono.fromRunnable(() ->
                    generateTitle(conversationId, request.getMessage(), finalAnswer)
            ).subscribeOn(Schedulers.boundedElastic()).subscribe();
        }

        return Flux.just(
                sseEvent("session-start", conversationId + "|" + chatId),
                sseEvent("chunk", result.answer()),
                sseEvent("sources", serializeSources(sources)),
                sseEvent("end", "")
        );
    }

    private Flux<ServerSentEvent<String>> handleEmptyKnowledgeBase(String chatId, String conversationId,
                                                                    ChatRequest request, String userId,
                                                                    long retrievalDurationMs,
                                                                    String rewrittenQuery,
                                                                    RagService.ChatResult retrievalResult) {
        String emptyMsg = "该知识库暂无文档，请先上传文档。";
        int turnIndex = chatHistoryRepository.getNextTurnIndex(conversationId);

        saveTurn(chatId, conversationId, request.getKnowledgeBaseId(), userId,
                request.getMessage(), emptyMsg,
                buildRagMetadataJson(List.of(), retrievalDurationMs, rewrittenQuery,
                        retrievalResult.agentMode(), retrievalResult.agentRounds(), retrievalResult.degraded()),
                List.of(), turnIndex);

        if (turnIndex == 0) {
            Mono.fromRunnable(() ->
                    generateTitle(conversationId, request.getMessage(), emptyMsg)
            ).subscribeOn(Schedulers.boundedElastic()).subscribe();
        }

        return Flux.just(
                sseEvent("session-start", conversationId + "|" + chatId),
                sseEvent("chunk", emptyMsg),
                sseEvent("end", "")
        );
    }

    /**
     * F21：把 agent_trace 行转成 SSE {@code agent_step} 事件流。
     * 没 trace 或非 agentic 模式时返回空 Flux（concat 会跳过）。
     *
     * <p>每行 trace 一条 SSE：{@code event: agent_step\r\ndata: {json}}，
     * 前端订阅 event.name === 'agent_step' 即可拿到"思考过程"动画数据。
     */
    private Flux<ServerSentEvent<String>> buildAgentStepEvents(String chatId, List<AgentTrace> traces) {
        if (traces == null || traces.isEmpty()) {
            return Flux.empty();
        }
        Flux<ServerSentEvent<String>> head = Flux.just();  // 占位避免空
        for (AgentTrace t : traces) {
            String json = agentTraceCollector.sseData(
                    chatId,
                    t.getRound(),
                    t.getToolName(),
                    t.getStatus(),
                    Map.of(
                            "durationMs", String.valueOf(t.getDurationMs() == null ? 0 : t.getDurationMs()),
                            "summary", t.getResultSummary() == null ? "" : t.getResultSummary()
                    )
            );
            head = head.concatWith(Flux.just(sseEvent("agent_step", json)));
        }
        return head;
    }

    // ==================== 工具方法 ====================

    private ServerSentEvent<String> sseEvent(String eventName, String data) {
        return ServerSentEvent.<String>builder()
                .event(eventName)
                .data(data == null ? "" : data)
                .build();
    }

    private String serializeSources(List<SourceRef> sources) {
        if (sources == null || sources.isEmpty()) return "[]";
        try {
            return objectMapper.writeValueAsString(sources);
        } catch (JsonProcessingException e) {
            log.warn("序列化 sources 失败: {}", e.getMessage());
            return "[]";
        }
    }

    /**
     * 构造 rag_metadata JSON 字符串
     *
     * <p>【2026-07-02 增量】增加 {@code rewritten_query} 字段：
     * <ul>
     *   <li>记录 LLM 改写后的检索 query（与 user 原始提问区分）</li>
     *   <li>便于后续检索质量评估、A/B 对比改写效果</li>
     *   <li>首轮/无改写时为空字符串</li>
     * </ul>
     *
     * <p>【2026-07-07 增量 F21】增加 agent 三元组：
     * <ul>
     *   <li>{@code agent_mode} —— 实际执行模式（linear / agentic）</li>
     *   <li>{@code agent_rounds} —— tool 调用轮次（linear 时 0）</li>
     *   <li>{@code degraded} —— agent 触发但降级 linear</li>
     * </ul>
     *
     * @param retrievedDocs      检索结果（可能为空）
     * @param retrievalDurationMs 检索耗时
     * @param rewrittenQuery      LLM/Simple 改写后的检索 query（可空）
     * @param agentMode          实际模式（linear / agentic）
     * @param agentRounds        agent loop 工具调用轮次
     * @param degraded           是否降级
     */
    private String buildRagMetadataJson(List<RagService.RetrievalResult> retrievedDocs,
                                        long retrievalDurationMs, String rewrittenQuery,
                                        String agentMode, int agentRounds, boolean degraded) {
        if (retrievedDocs == null) retrievedDocs = List.of();
        try {
            List<String> docIds = retrievedDocs.stream()
                    .map(r -> r.source().split("_")[0])
                    .distinct()
                    .collect(Collectors.toList());

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("retrieved_doc_count", docIds.size());
            meta.put("retrieved_chunk_count", retrievedDocs.size());
            meta.put("retrieved_doc_ids", docIds);
            meta.put("retrieval_duration_ms", retrievalDurationMs);
            // 改写后的检索 query：与 user 原始提问分开存，便于评估改写对检索的提升
            // 改写失败降级 simple / 首轮无历史时，等于 user 原始 query
            meta.put("rewritten_query", rewrittenQuery == null ? "" : rewrittenQuery);
            // F21：agent 模式三字段，linear 时 agent_rounds=0 / degraded=false
            meta.put("agent_mode", agentMode == null ? "linear" : agentMode);
            meta.put("agent_rounds", agentRounds);
            meta.put("degraded", degraded);

            return objectMapper.writeValueAsString(meta);
        } catch (JsonProcessingException e) {
            log.warn("序列化 rag_metadata 失败: {}", e.getMessage());
            return null;
        }
    }

    private List<SourceRef> buildSourceRefs(List<RagService.RetrievalResult> retrievedDocs) {
        if (retrievedDocs == null || retrievedDocs.isEmpty()) return List.of();
        List<SourceRef> refs = new java.util.ArrayList<>(retrievedDocs.size());
        for (RagService.RetrievalResult r : retrievedDocs) {
            String source = r.source();
            String documentId;
            Integer chunkIndex;
            int lastUnderscore = source.lastIndexOf('_');
            if (lastUnderscore > 0 && lastUnderscore < source.length() - 1) {
                documentId = source.substring(0, lastUnderscore);
                try {
                    chunkIndex = Integer.parseInt(source.substring(lastUnderscore + 1));
                } catch (NumberFormatException e) {
                    chunkIndex = null;
                }
            } else {
                documentId = source;
                chunkIndex = null;
            }

            String snippet = null;
            String content = r.content();
            if (content != null) {
                snippet = content.length() > sourceSnippetLength ?
                        content.substring(0, sourceSnippetLength) + "…" : content;
            }

            refs.add(SourceRef.builder()
                    .documentId(documentId)
                    .fileName(r.fileName())
                    .chunkIndex(chunkIndex)
                    .snippet(snippet)
                    .score(r.score())
                    .build());
        }
        return refs;
    }

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

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    private String getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof User user) {
            return user.getUsername();
        }
        log.warn("SecurityContext 中未找到用户信息");
        return "unknown";
    }
}