package com.ragqa.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragqa.dto.ChatRequest;
import com.ragqa.dto.ChatResponse;
import com.ragqa.dto.SourceRef;
import com.ragqa.model.ChatHistory;
import com.ragqa.model.User;
import com.ragqa.repository.ChatHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
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
     * 【2026-06-29 增量 P0-01】来源 snippet 截取长度
     *
     * 前端「参考文档」卡片默认显示前 N 字符的内容摘要。值越大信息越全但响应体越大。
     * 200 字符 ≈ 100-150 中文字，能展示一段话的核心信息。
     */
    @Value("${chat.source.snippet-length:200}")
    private int sourceSnippetLength;

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
        //    【2026-06-29 增量 P0-02】传入 history，让 RagService 做 query rewrite + prompt 注入
        RagService.ChatResult result;
        try {
            result = ragService.chat(
                    request.getMessage(),
                    request.getKnowledgeBaseId(),
                    request.getHistory());
        } catch (Exception e) {
            log.error("RAG 问答失败: userId={}", userId, e);
            // 失败时仍要落库"用户问题"+"错误提示"，便于后续排查
            try {
                saveTurn(sessionId, request.getKnowledgeBaseId(), userId, request.getMessage(),
                        "抱歉，AI服务暂时不可用，请稍后重试。", null);
            } catch (Exception ex) {
                log.warn("[落库告警] 错误回合持久化失败: sessionId={}, userId={}", sessionId, userId, ex);
            }
            return new ChatResponse(sessionId, "抱歉，AI服务暂时不可用，请稍后重试。", List.of());
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

        // 3. 【2026-06-29 增量 P0-01】构建来源引用列表（用于前端展示"参考文档"卡片）
        List<SourceRef> sources = buildSourceRefs(result.retrievedDocs());

        log.info("问答完成: sessionId={}, userId={}, answerLength={}, retrievedDocs={}, sources={}",
                sessionId, userId,
                result.answer() == null ? 0 : result.answer().length(),
                result.retrievedDocs().size(),
                sources.size());
        return new ChatResponse(sessionId, result.answer(), sources);
    }

    /**
     * 流式问答
     *
     * 【2026-06-29 增量 P0-01】返回类型从 Flux&lt;String&gt; 升级为 Flux&lt;ServerSentEvent&lt;String&gt;&gt;
     *
     * 历史问题：原协议只有文本片段，前端无法在流式响应结束时拿到 sources。
     * 修复：用 Spring 的 ServerSentEvent 区分事件名：
     *   - event=session-start : 首条事件，data 为 sessionId（前端用于刷新侧边栏）
     *   - event=chunk        : 普通文本片段（与之前等价）
     *   - event=sources      : 【新】收尾前发出，data 为 JSON 序列化的 List&lt;SourceRef&gt;
     *   - event=end          : 【新】结束标记（前端可据此关闭 loading）
     *
     * 向后兼容：前端 parseSSE 收到 event=chunk 的 data 时追加，遇到 event=sources 时存到消息元数据。
     *
     * @param request 问答请求
     * @return SSE 事件流
     */
    public Flux<ServerSentEvent<String>> streamChat(ChatRequest request) {
        String userId = getCurrentUserId();
        log.info("收到流式问答请求: userId={}, message={}, streamingEnabled={}",
                userId, request.getMessage(), streamingEnabled);

        String sessionId = UUID.randomUUID().toString();

        // 如果配置关闭了流式，则回退到非流式
        if (!streamingEnabled) {
            RagService.ChatResult result;
            try {
                result = ragService.chat(
                    request.getMessage(),
                    request.getKnowledgeBaseId(),
                    request.getHistory());
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
            // 【P0-01】非流式回退也带上 sources event 和 end event，前端能正确收尾
            List<SourceRef> sources = buildSourceRefs(result.retrievedDocs());
            return Flux.just(
                    sseEvent("session-start", sessionId),
                    sseEvent("chunk", result.answer()),
                    sseEvent("sources", serializeSources(sources)),
                    sseEvent("end", "")
            );
        }

        try {
            // 2. 检索相关文档（带计时）
            long retrievalStart = System.currentTimeMillis();
            var docs = ragService.retrieveForStreaming(
                    request.getMessage(),
                    request.getKnowledgeBaseId(),
                    request.getHistory());
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
                // 空知识库：无 sources，但仍发 session-start + chunk + end
                return Flux.just(
                        sseEvent("session-start", sessionId),
                        sseEvent("chunk", emptyMsg),
                        sseEvent("end", "")
                );
            }

            // 3. 构建上下文和提示词
            //    【2026-06-29 增量 P0-02】流式路径也用带历史的 prompt，与非流式保持一致
            String context = buildContext(docs);
            String prompt = buildPromptWithHistory(context, request.getHistory(), request.getMessage());

            // 4. 调用 LLM，返回真正的流式响应
            // 【修复 2026-06-29】不能用 chunkEvents + tailEvents 分开订阅的设计：
            //   chunkEvents 是 cold flux，第一次 SSE 订阅就消费完了，
            //   tailEvents 里的 collectList() 订阅时已经无数据可用。
            //   改用 Flux.defer() 包裹，让 LLM 流 + 收尾逻辑在同一个订阅周期内完成。
            final long finalRetrievalDurationMs = retrievalDurationMs;
            final List<RagService.RetrievalResult> finalDocs = docs;

            // 构建 LLM 流式响应 flux（用 defer 延迟创建，确保 advisor 链正确初始化）
            Flux<String> llmStream = Flux.defer(() ->
                    chatClientBuilder.build()
                            .prompt(prompt)
                            .advisors(new SimpleLoggerAdvisor())
                            .stream()
                            .content()
            );

            // 累积器用于收集完整回答
            StringBuilder accumulator = new StringBuilder();

            // 使用 concatWith + defer 将 session-start / sources / end 与 LLM 流拼接
            // concat 是顺序拼接：先发 start，再发 LLM chunk，最后发 sources + end
            return Flux.concat(
                    Flux.just(sseEvent("session-start", sessionId)),
                    Flux.defer(() ->
                            llmStream
                                    .map(chunk -> {
                                        accumulator.append(chunk);
                                        return sseEvent("chunk", chunk);
                                    })
                                    .doOnComplete(() -> {
                                        // LLM 流完成后，异步落库
                                        Mono.fromRunnable(() -> {
                                            try {
                                                String ragMetadataJson = buildRagMetadataJson(
                                                        finalDocs, finalRetrievalDurationMs);
                                                saveTurn(sessionId, request.getKnowledgeBaseId(), userId,
                                                        request.getMessage(), accumulator.toString(), ragMetadataJson);
                                                log.info("流式问答完成并落库: sessionId={}, userId={}, answerLength={}, retrievedDocs={}",
                                                        sessionId, userId, accumulator.length(), finalDocs.size());
                                            } catch (Exception e) {
                                                log.warn("[落库告警] 流式回合持久化失败: sessionId={}, userId={}, answerLength={}",
                                                        sessionId, userId, accumulator.length(), e);
                                            }
                                        }).subscribeOn(Schedulers.boundedElastic()).subscribe();
                                    })
                    ),
                    Flux.defer(() -> {
                        List<SourceRef> sources = buildSourceRefs(finalDocs);
                        return Flux.just(
                                sseEvent("sources", serializeSources(sources)),
                                sseEvent("end", "")
                        );
                    })
            );
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
            return Flux.just(sseEvent("chunk", errorMsg), sseEvent("end", ""));
        }
    }

    /**
     * 【2026-06-29 增量 P0-01】构造一个 SSE 事件
     */
    private ServerSentEvent<String> sseEvent(String eventName, String data) {
        return ServerSentEvent.<String>builder()
                .event(eventName)
                .data(data == null ? "" : data)
                .build();
    }

    /**
     * 【2026-06-29 增量 P0-01】把 SourceRef 列表序列化为 JSON 字符串
     */
    private String serializeSources(List<SourceRef> sources) {
        if (sources == null || sources.isEmpty()) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(sources);
        } catch (JsonProcessingException e) {
            log.warn("SourceRef 列表序列化失败: {}", e.getMessage());
            return "[]";
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
     * 【2026-06-29 增量 P0-01】把 RagService 召回结果转换为前端可消费的 SourceRef 列表
     *
     * 字段映射：
     *   - documentId : 从 source "docId_chunkIndex" 中提取 docId
     *   - fileName   : 直接用 RagService 已关联好的 fileName
     *   - chunkIndex : 从 source 末尾解析 "_N"
     *   - snippet    : content 前 N 字符 + 省略号（N 由 sourceSnippetLength 配置）
     *   - score      : retrieval / rerank 分数
     *
     * @param retrievedDocs 检索结果
     * @return SourceRef 列表；空输入返回空列表
     */
    private List<SourceRef> buildSourceRefs(List<RagService.RetrievalResult> retrievedDocs) {
        if (retrievedDocs == null || retrievedDocs.isEmpty()) {
            return List.of();
        }
        List<SourceRef> refs = new java.util.ArrayList<>(retrievedDocs.size());
        for (RagService.RetrievalResult r : retrievedDocs) {
            // source 格式："docId_chunkIndex"，按最后一个 "_" 切分
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

            // snippet：截取前 N 字符 + 省略号（如果有截断）
            String snippet = null;
            String content = r.content();
            if (content != null) {
                if (content.length() > sourceSnippetLength) {
                    snippet = content.substring(0, sourceSnippetLength) + "…";
                } else {
                    snippet = content;
                }
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
     * 构建提示词（V1：无历史）
     *
     * 【2026-06-29 P0-02】已重构为带历史版本（buildPromptWithHistory）。
     * 保留此方法作为兼容 / 单轮问答 fallback。
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
     * 【2026-06-29 增量 P0-02】带对话历史的提示词构建
     *
     * 与 RagService.buildPromptWithHistory 逻辑相同（流式路径独立复制一份，
     * 因为流式需要 context 构建与 RagService 分离的 prompt）。
     *
     * 为什么不抽公共方法：保持 RagService 与 ChatService 的低耦合。
     * 双方各自实现相同逻辑的成本 < 引入共享抽象的复杂度。
     */
    private String buildPromptWithHistory(String context, java.util.List<com.ragqa.dto.ChatMessage> history, String currentMessage) {
        StringBuilder historySection = new StringBuilder();
        if (history == null || history.isEmpty()) {
            historySection.append("（这是新对话，无上文）");
        } else {
            // 取最近 6 条消息（默认 3 轮 user+assistant）
            int n = Math.min(history.size(), 6);
            int start = history.size() - n;
            for (int i = start; i < history.size(); i++) {
                com.ragqa.dto.ChatMessage m = history.get(i);
                String roleLabel = "user".equals(m.getRole()) ? "用户" : "助手";
                String content = m.getContent() == null ? "" : m.getContent();
                if (content.length() > 500) {
                    content = content.substring(0, 500) + "…";
                }
                historySection.append(roleLabel).append(": ").append(content).append("\n");
            }
        }

        return """
            你是一个专业的智能问答助手，擅长从提供的文档中准确提取信息并清晰回答用户问题。

            === 对话历史（最近 %d 轮）===
            %s

            === 参考文档 ===
            %s

            === 用户当前问题 ===
            %s

            === 回答要求 ===
            1. **优先结合对话历史理解指代**：用户当前问题中的"它/那个/前一条"等代词，先在历史中找到指代对象
            2. 只基于参考文档内容回答，不要编造信息
            3. 如果文档中没有相关信息，回答："抱歉，知识库中没有找到与您问题相关的内容。"
            4. 引用文档时使用【文档X】标注来源
            5. 回答结构：先给出结论，再引用证据，最后补充说明（如有）
            6. 对于复杂问题，用分点或编号的方式回答

            === 回答 ===
            """.formatted(3, historySection.toString(), context, currentMessage);
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
