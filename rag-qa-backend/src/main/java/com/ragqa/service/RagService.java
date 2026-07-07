package com.ragqa.service;

import com.ragqa.dto.ChatMessage;
import com.ragqa.model.Document;
import com.ragqa.repository.DocumentChunkRepository;
import com.ragqa.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * RAG（检索增强生成）服务
 *
 * 作用：实现基于知识库的智能问答
 *
 * RAG工作流程：
 * 1. 检索（Retrieval）：根据用户问题从知识库中查找相关文档
 * 2. 增强（Augmentation）：将检索到的文档作为上下文
 * 3. 生成（Generation）：调用LLM基于上下文生成回答
 *
 * 核心流程：
 * chat() → retrieve() → buildContext() → buildPrompt() → LLM生成
 *
 * 检索策略：
 * - 默认使用混合检索（向量 + BM25），提供更准确的检索结果
 * - 通过 HybridSearchService 统一管理检索逻辑
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RagService {

    /** 文档切片数据库仓库 */
    private final DocumentChunkRepository documentChunkRepository;
    /** 文档数据库仓库 */
    private final DocumentRepository documentRepository;
    /** Spring AI ChatClient构建器，用于调用LLM */
    private final ChatClient.Builder chatClientBuilder;
    /** 向量化服务 */
    private final EmbeddingService embeddingService;
    /** Chroma向量数据库服务 */
    private final ChromaService chromaService;
    /** 混合检索服务（向量 + BM25） */
    private final HybridSearchService hybridSearchService;
    /**
     * 【2026-06-29 增量 P1-02】Cross-Encoder 重排序服务
     *
     * 之前是只注入没用上，现在接入 retrieve() 的两阶段检索链路。
     */
    private final RerankService rerankService;
    /**
     * 【2026-07-02 增量】查询改写服务（LLM Rewrite）
     *
     * 替代老逻辑 {@code rewriteQueryWithHistory} 的简单空格拼接：
     * - 多轮场景：调 LLM 把当前问题 + 上下文改写为独立检索 query
     * - 失败/超时：自动降级到 simple 拼接，主流程不中断
     * - 首轮：直接返回原 query，零开销
     *
     * 模式由 {@code rag.query.rewrite.mode} 控制（llm|simple|none）。
     */
    private final QueryRewriteService queryRewriteService;

    /** 检索返回的最终结果数量（传给 LLM 的 top-K） */
    @Value("${retrieval.topk:3}")
    private int TOP_K;

    /**
     * 【2026-06-29 增量 P1-02】第一阶段召回数量
     *
     * 两阶段检索：先从 Chroma 召回 candidatesTopK 个，再用 RerankService 精排到 TOP_K。
     * 召回数 > 返回数 才能让 rerank 有空间挑选更好的 top-K。
     *
     * 启用 rerank 时建议 ≥20；未启用时此参数无意义（直接返回 TOP_K）。
     */
    @Value("${retrieval.candidates.topk:20}")
    private int candidatesTopK;

    /**
     * Fallback 检索最大加载切片数（防 OOM）
     *
     * 【为什么需要】fallbackRetrieve 把所有 chunk 向量加载到内存做余弦相似度，
     * 4096 维 × N chunks 会占用大量堆内存。高并发场景下，单次请求可能吃光 JVM 堆。
     *
     * 默认 5000 切片 ≈ 80MB / 请求（4096 维 × 4 字节 × 5000）。超过则截断 + 警告。
     */
    @Value("${retrieval.fallback.max-chunks:5000}")
    private int fallbackMaxChunks;

    /**
     * 【2026-06-29 增量 P0-02】多轮对话 — 注入 prompt 的最近历史轮数
     *
     * 配置项：rag.history.turns
     * 默认 3 轮 = user/assistant × 3 = 6 条消息。
     *
     * 为什么是 3：
     *   - 1 轮：太短，指代（"它"、"那个"）无法消解
     *   - 3 轮：覆盖大多数真实多轮场景，prompt 不会爆
     *   - 5+ 轮：token 占用大，LLM 注意力会分散到无关早期对话
     */
    @Value("${rag.history.turns:3}")
    private int historyTurns;

    /**
     * 检索结果记录
     *
     * 【2026-06-29 增量 P0-01】新增 fileName 字段
     *
     * 历史：source 只有 "docId_chunkIndex" 字符串，前端拿到后无法直接显示文档名。
     * 修复：在 retrieve() 时把 docId 同时关联到 Document 表的 fileName，一并返回。
     *
     * @param content 文档切片内容
     * @param source 来源标识（documentId_chunkIndex）
     * @param score 相似度得分
     * @param fileName 原始文件名（如 "产品手册.pdf"），P0-01 新增
     */
    public record RetrievalResult(String content, String source, double score, String fileName) {}

    /**
     * RAG 问答结果（V3 新增：含 RAG 召回元数据，用于持久化到 chat_history.rag_metadata）。
     *
     * <p>【2026-07-02 增量】新增 {@code rewrittenQuery} 字段：
     * <ul>
     *   <li>流式场景下 answer 为 null（边生成边推，本方法只负责检索 + 改写）</li>
     *   <li>非流式场景下 answer 是 LLM 生成的完整回答</li>
     *   <li>rewrittenQuery 是 LLM/Simple 改写后的检索 query（用于落库 rag_metadata）</li>
     * </ul>
     *
     * @param answer              LLM 生成的最终回答（流式场景可为 null）
     * @param retrievedDocs       实际参与本次生成的检索结果列表（已按知识库过滤）
     * @param retrievalDurationMs 检索阶段耗时（毫秒）
     * @param rewrittenQuery      改写后的检索 query（用于评估检索质量、A/B 对比）
     */
    public record ChatResult(String answer, List<RetrievalResult> retrievedDocs,
                             long retrievalDurationMs, String rewrittenQuery) {}

    /**
     * 处理用户问答（非流式），返回含 RAG 元数据的结果。
     *
     * 【V3 变更】返回值从 String 改为 ChatResult，便于 ChatService 拼装 rag_metadata JSON
     * 落库到 chat_history 表。
     *
     * 【2026-06-29 增量 P0-02】新增 history 参数
     *   - 检索阶段：把 history 中最近 N 轮的 user 问题拼接到当前 query 之前，
     *     让 embedding 看到上下文（消除"它"、"那个"等指代歧义）
     *   - 生成阶段：把 history 注入 prompt，让 LLM 也知道上文
     *
     * 【2026-07-02 增量】historyWindow 参数 + rewrittenQuery 落库
     *   - historyWindow 由 ChatService 从 Conversation.historyWindow 取值传入，
     *     与 prompt 注入的历史窗口保持单一来源
     *   - 改写后的 query 一并写入 ChatResult，供 ChatService 落库 rag_metadata
     *
     * @param message         用户当前问题
     * @param knowledgeBaseId 知识库ID
     * @param history         多轮对话历史（前端已传；ChatService 兜底传 List.of()）
     * @param historyWindow   改写 + prompt 注入共同使用的历史轮数（来自 Conversation.historyWindow）
     * @return ChatResult（answer + 召回列表 + 检索耗时 + 改写后 query）
     */
    public ChatResult chat(String message, UUID knowledgeBaseId, List<ChatMessage> history, int historyWindow) {
        log.info("RAG问答: message='{}', historySize={}, historyWindow={}",
                message, history == null ? 0 : history.size(), historyWindow);

        // 1. 检查知识库是否有已处理的文档
        List<Document> documents = documentRepository.findByKnowledgeBaseId(knowledgeBaseId);

        boolean hasCompletedDocs = documents.stream()
                .anyMatch(doc -> doc.getStatus() == Document.DocumentStatus.COMPLETED);

        if (!hasCompletedDocs) {
            return new ChatResult("该知识库暂无文档，请先上传文档。", java.util.List.of(), 0L, message);
        }

        // 2. 检索相关文档（query 用 QueryRewriteService 改写后的版本，让 embedding 看到上下文）
        String rewrittenQuery = queryRewriteService.rewrite(message, history, historyWindow);
        log.debug("query rewrite: '{}' → '{}'", message, rewrittenQuery);
        long retrievalStart = System.currentTimeMillis();
        List<RetrievalResult> retrieved = retrieve(rewrittenQuery, knowledgeBaseId);
        long retrievalDurationMs = System.currentTimeMillis() - retrievalStart;

        if (retrieved.isEmpty()) {
            return new ChatResult("该知识库暂无文档，请先上传文档。", java.util.List.of(), retrievalDurationMs, rewrittenQuery);
        }

        // 3. 构建上下文：将检索到的文档拼接成上下文字符串
        String context = buildContext(retrieved);

        // 4. 构建提示词：将上下文 + history + 问题组合成完整提示
        String prompt = buildPromptWithHistory(context, history, message, historyWindow);

        // 5. 调用LLM生成回答
        try {
            String response = chatClientBuilder.build()
                    .prompt(prompt)
                    .call()
                    .content();

            if (response == null || response.isEmpty()) {
                log.warn("LLM返回空响应，可能余额不足");
                return new ChatResult("AI服务余额不足，请联系管理员充值后继续使用。", retrieved, retrievalDurationMs, rewrittenQuery);
            }

            return new ChatResult(response, retrieved, retrievalDurationMs, rewrittenQuery);
        } catch (Exception e) {
            log.error("LLM调用失败: {}", e.getMessage());

            // 检查是否是余额不足错误
            String errorMsg = e.getMessage();
            if (errorMsg != null && (errorMsg.contains("insufficient_balance") ||
                errorMsg.contains("insufficient balance") || errorMsg.contains("1008"))) {
                return new ChatResult("AI服务余额不足，请联系管理员充值后继续使用。", retrieved, retrievalDurationMs, rewrittenQuery);
            }

            return new ChatResult("抱歉，AI服务暂时不可用，请稍后重试。", retrieved, retrievalDurationMs, rewrittenQuery);
        }
    }

    /**
     * 检索相关文档（两阶段检索）
     *
     * 【2026-06-29 升级 P1-02】改造为「召回调 Rerank」流程：
     *   Stage 1 (召回)：从 Chroma 拉 top-{candidatesTopK} 候选（默认 20）
     *   Stage 2 (精排)：如果 rerankService 启用，调用 cross-encoder 重排到 top-{TOP_K}；否则直接截前 TOP_K
     *
     * 【设计权衡】
     *   - 召回集 ≥ 返回集：让 rerank 有挑选空间（top-3 候选里挑 top-3 没意义）
     *   - candidatesTopK 默认 20：覆盖绝大多数真实场景（top-3 → top-20 召回率提升明显）
     *   - 旧配置 retrieval.topk=3 不变：用户感知层面没有任何行为差异
     *
     * @param query 用户问题
     * @param knowledgeBaseId 知识库ID
     * @return 检索结果列表（最多 TOP_K 条，已 rerank 排序）
     */
    public List<RetrievalResult> retrieve(String query, UUID knowledgeBaseId) {
        try {
            // 1. 召回：从 Chroma 拉取较多候选（默认 20 个）
            // 用 candidatesTopK 而不是 TOP_K，给 rerank 留出挑选空间
            int fetchSize = rerankService.isEnabled()
                    ? Math.max(candidatesTopK, TOP_K)  // 启用 rerank 时多召一些
                    : TOP_K;                             // 未启用则少召节省时间
            // 按知识库过滤召回，避免跨知识库串答（依赖切片 metadata 带 knowledgeBaseId）
            List<ChromaService.SearchResult> candidates = chromaService.similaritySearch(query, knowledgeBaseId, fetchSize);

            if (candidates.isEmpty()) {
                // 【诊断】Chroma 正常返回但无任何召回。可能原因：collection 为空、
                // 连接异常被 similaritySearch 内部吞掉返回空、或 query embedding 命中 0 条。
                log.warn("Chroma 召回为空: query='{}', kbId={}, fetchSize={}", query, knowledgeBaseId, fetchSize);
                return Collections.emptyList();
            }

            // 2. 一次性查询该知识库下所有 COMPLETED 状态的文档
            // 【修复 N+1】原代码每个 Chroma 结果都执行一次 findByKnowledgeBaseId，
            // TopK=20 就是 20 次查询。现改为一次查询 + Map.get()
            // 【2026-06-29 增量 P0-01】顺便把 fileName 也一次性拉出来，避免后面对每个候选都查一次
            Map<UUID, Document> docMap = documentRepository.findByKnowledgeBaseId(knowledgeBaseId).stream()
                    .filter(doc -> doc.getStatus() == Document.DocumentStatus.COMPLETED)
                    .collect(Collectors.toMap(Document::getId, doc -> doc));
            Set<UUID> validDocIds = docMap.keySet();

            // 3. 过滤：只保留属于该知识库且状态为COMPLETED的文档
            List<ChromaService.SearchResult> validCandidates = candidates.stream()
                    .filter(r -> {
                        try {
                            UUID docId = UUID.fromString(r.documentId());
                            return validDocIds.contains(docId);
                        } catch (Exception e) {
                            return false;
                        }
                    })
                    .collect(Collectors.toList());

            if (validCandidates.isEmpty()) {
                // 【诊断】Chroma 召回了候选，但没有一条属于当前知识库的 COMPLETED 文档。
                // 可能原因：Chroma collection 全局共享、命中的都是其他知识库的切片；
                // 或本知识库文档状态非 COMPLETED（被 recovery scheduler 置 FAILED 等）。
                log.warn("Chroma 召回 {} 条但无一条属于当前知识库 COMPLETED 文档: query='{}', kbId={}, validDocIds={}, 命中 documentIds={}",
                        candidates.size(), query, knowledgeBaseId, validDocIds,
                        candidates.stream().map(ChromaService.SearchResult::documentId).toList());
                return Collections.emptyList();
            }

            // 4. 精排：调用 RerankService 重排（如果启用）
            // RerankService.rerank() 内部已处理：未启用/Ollama 失败时自动降级
            List<RerankService.RerankResult> reranked = rerankService.rerank(query, validCandidates, TOP_K);

            // 5. 转换为统一的 RetrievalResult（含 fileName，P0-01）
            return reranked.stream()
                    .map(r -> {
                        UUID docId;
                        try {
                            docId = UUID.fromString(r.getDocumentId());
                        } catch (Exception e) {
                            docId = null;
                        }
                        // 从预加载的 docMap 拿 fileName，缺失则降级为 docId 前 8 位
                        Document doc = docId != null ? docMap.get(docId) : null;
                        String fileName = doc != null ? doc.getFileName()
                                : (docId != null ? docId.toString().substring(0, 8) : "unknown");
                        return new RetrievalResult(
                                r.getContent(),                                  // 切片文本
                                r.getDocumentId() + "_" + r.getChunkIndex(),    // 来源标识
                                r.getScore(),                                    // 分数
                                fileName                                         // 文件名（P0-01）
                        );
                    })
                    .collect(Collectors.toList());
        } catch (Exception e) {
            // Chroma 检索失败时，尝试从数据库检索（回退方案）
            log.warn("Chroma 检索失败，回退到数据库检索: {}", e.getMessage());
            return fallbackRetrieve(query, knowledgeBaseId);
        }
    }

    /**
     * 流式检索 - 公开方法供ChatService调用
     *
     * 【2026-06-29 增量 P0-02】接受 history 参数，复用非流式的 query 重写逻辑
     *
     * 【2026-07-02 增量】historyWindow 透传 + 返回 ChatResult
     *   - historyWindow 来自 Conversation.historyWindow，与 chat() 保持单一来源
     *   - 返回 ChatResult（answer=null）携带 rewrittenQuery，供 ChatService 落库
     *
     * @param query          用户当前问题
     * @param knowledgeBaseId 知识库ID
     * @param history        对话历史（可空）
     * @param historyWindow  改写用的历史轮数（来自 Conversation.historyWindow）
     * @return ChatResult（answer=null，含召回列表 + 检索耗时 + 改写后 query）
     */
    public ChatResult retrieveForStreaming(String query, UUID knowledgeBaseId, List<ChatMessage> history, int historyWindow) {
        long retrievalStart = System.currentTimeMillis();
        String rewrittenQuery = queryRewriteService.rewrite(query, history, historyWindow);
        log.debug("[stream] query rewrite: '{}' → '{}'", query, rewrittenQuery);
        List<RetrievalResult> results = retrieve(rewrittenQuery, knowledgeBaseId);
        long retrievalDurationMs = System.currentTimeMillis() - retrievalStart;
        log.info("[stream] 检索完成: retrieved={}, kbId={}, rewrittenQuery='{}'",
                results.size(), knowledgeBaseId, rewrittenQuery);
        return new ChatResult(null, results, retrievalDurationMs, rewrittenQuery);
    }

    /**
     * 回退方案：从MySQL数据库检索
     * 
//     * 当Chroma不可用时，直接从数据库加载所有文档切片，
     * 在内存中计算相似度（效率较低，但作为备份方案）
     */
    private List<RetrievalResult> fallbackRetrieve(String query, UUID knowledgeBaseId) {
        List<RetrievalResult> results = new ArrayList<>();

        // 1. 将问题转换为向量
        float[] queryEmbedding = embeddingService.embed(query);

        // 2. 遍历知识库中的所有文档
        var documents = documentRepository.findByKnowledgeBaseId(knowledgeBaseId);

        int totalChunksLoaded = 0;
        boolean truncated = false;

        for (var doc : documents) {
            // 只处理已完成处理的文档
            if (doc.getStatus() != Document.DocumentStatus.COMPLETED) {
                continue;
            }

            // 3. 获取该文档的所有切片
            var chunks = documentChunkRepository.findByDocumentId(doc.getId());

            // 4. 计算每个切片与问题的相似度
            for (var chunk : chunks) {
                // 【OOM 防护】超过阈值后停止加载，避免单请求耗尽 JVM 堆
                if (totalChunksLoaded >= fallbackMaxChunks) {
                    truncated = true;
                    break;
                }
                totalChunksLoaded++;

                // 从数据库读取存储的向量字符串
                String embeddingStr = chunk.getEmbedding();
                float[] chunkEmbedding = parseEmbedding(embeddingStr);

                if (chunkEmbedding.length > 0) {
                    // 计算余弦相似度
                    double similarity = cosineSimilarity(queryEmbedding, chunkEmbedding);
                    // 【2026-06-29 增量 P0-01】fallback 路径也需要 fileName
                    // 这里的 doc 变量已经在 for 循环里，fileName 可直接拿到
                    results.add(new RetrievalResult(
                        chunk.getContent(),
                        chunk.getDocumentId() + "_" + chunk.getChunkIndex(),
                        similarity,
                        doc.getFileName()
                    ));
                }
            }

            if (truncated) {
                break;
            }
        }

        if (truncated) {
            log.warn("Fallback 检索达到最大切片数限制 {}，结果可能不完整。建议修复 Chroma 服务后回切正常检索路径。",
                    fallbackMaxChunks);
        }

        // 5. 按相似度降序排序，返回TopK个结果
        results.sort((a, b) -> Double.compare(b.score(), a.score()));
        return results.stream().limit(TOP_K).toList();
    }

    /**
     * 构建上下文字符串
     *
     * 将检索到的多个文档切片拼接成连续的上下文
     * 格式：
     * 参考文档：
     *
     * 【文档1】
     * xxx内容xxx
     *
     * 【文档2】
     * xxx内容xxx
     */
    private String buildContext(List<RetrievalResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("参考文档：\n\n");
        for (int i = 0; i < results.size(); i++) {
            RetrievalResult r = results.get(i);
            sb.append("【文档").append(i + 1).append("】\n");
            sb.append(r.content()).append("\n\n");
        }
        return sb.toString();
    }

    /**
     * 构建提示词（V1：仅上下文 + 当前问题，不含历史）
     *
     * 【2026-06-29 P0-02】保留此方法作为"无历史对话"场景的 fallback
     * 主要路径已切到 buildPromptWithHistory()
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
     * 【2026-06-29 增量 P0-02】构建带对话历史的提示词
     *
     * 与 buildPrompt() 的关键区别：增加了"对话历史"section，让 LLM 看到上文，
     * 解决多轮对话中"它/那个/前一条"等指代词的消解问题。
     *
     * 格式：
     * <pre>
     * === 对话历史（最近 N 轮）===
     * [第1轮] user: 介绍一下产品A
     * [第1轮] assistant: 产品A是xxx...
     * [第2轮] user: 它的价格？
     * [第2轮] assistant: 产品A 价格是...
     * [第3轮] user: 有优惠吗？   ← 当前问题
     *
     * === 参考文档 ===
     * ...
     *
     * === 用户当前问题 ===
     * 有优惠吗？
     *
     * === 回答要求 ===
     * 1. 结合对话历史理解指代
     * 2. 只基于参考文档内容回答
     * 3. ...
     * </pre>
     *
     * @param context        检索到的文档上下文
     * @param history        对话历史（前端传入；可空/可少于 N 轮）
     * @param currentMessage 用户当前消息
     * @param historyWindow  注入 prompt 的历史轮数（来自 Conversation.historyWindow）
     */
    public String buildPromptWithHistory(String context, List<ChatMessage> history, String currentMessage, int historyWindow) {
        StringBuilder historySection = new StringBuilder();
        // 空 history 走 fallback 路径（保持行为兼容）
        if (history == null || history.isEmpty()) {
            historySection.append("（这是新对话，无上文）");
        } else {
            // 取最近 historyWindow * 2 条消息（每轮 user + assistant）
            int n = Math.min(history.size(), historyWindow * 2);
            int start = history.size() - n;
            for (int i = start; i < history.size(); i++) {
                ChatMessage m = history.get(i);
                String roleLabel = "user".equals(m.getRole()) ? "用户" : "助手";
                String content = m.getContent() == null ? "" : m.getContent();
                // 单条消息过长截断（避免 prompt 爆炸）
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
            """.formatted(historyWindow, historySection.toString(), context, currentMessage);
    }

    /**
     * 【2026-06-29 增量 P0-02 / 2026-07-02 替换】query rewriting 已下沉到 {@link QueryRewriteService}
     *
     * 历史：此处曾经是简单的空格拼接实现。2026-07-02 替换为 LLM 改写（失败/超时降级到 simple 拼接）。
     * 详见 {@link QueryRewriteService#rewrite(String, List, int)}。
     *
     * <p>原实现保留在注释中以便回溯：
     * <pre>{@code
     * // 只取最近 historyTurns 个 user 消息
     * List<String> recentUserMsgs = new ArrayList<>();
     * for (int i = history.size() - 1; i >= 0 && recentUserMsgs.size() < historyTurns; i--) {
     *     ChatMessage m = history.get(i);
     *     if ("user".equals(m.getRole()) && m.getContent() != null && !m.getContent().isBlank()) {
     *         recentUserMsgs.add(0, m.getContent());
     *     }
     * }
     * StringBuilder sb = new StringBuilder();
     * for (String msg : recentUserMsgs) {
     *     if (sb.length() > 0) sb.append(" ");
     *     sb.append(msg);
     * }
     * sb.append(" ").append(currentMessage);
     * return sb.toString();
     * }</pre>
     *
     * <p>设计权衡：保留 {@code rag.query.rewrite.mode=simple} 配置项即可一键回退到该拼接逻辑，
     * 无需恢复此处代码。
     */

    /**
     * 解析向量字符串
     *
     * 数据库中向量存储格式为 "[0.1, 0.2, 0.3]"
     * 需要解析为 float[] 数组
     */
    private float[] parseEmbedding(String embeddingStr) {
        if (embeddingStr == null || embeddingStr.isEmpty()) {
            return new float[0];
        }
        
        try {
            // 去掉首尾的方括号，按逗号分隔
            String[] parts = embeddingStr.substring(1, embeddingStr.length() - 1).split(", ");
            float[] result = new float[parts.length];
            for (int i = 0; i < parts.length; i++) {
                result[i] = Float.parseFloat(parts[i]);
            }
            return result;
        } catch (Exception e) {
            log.error("解析向量失败: {}", e.getMessage());
            return new float[0];
        }
    }

    /**
     * 计算余弦相似度
     * 
     * 余弦相似度衡量两个向量在方向上的相似程度
     * 取值范围 [-1, 1]，越接近1表示越相似
     * 
     * 公式: cos(A,B) = (A·B) / (||A|| × ||B||)
     * 
     * @param a 向量A
     * @param b 向量B
     * @return 相似度得分
     */
    private double cosineSimilarity(float[] a, float[] b) {
        // 向量维度不同，无法比较
        if (a.length != b.length) return 0;
        
        double dotProduct = 0;  // 点积：a·b
        double normA = 0;       // 向量A的模
        double normB = 0;       // 向量B的模
        
        // 计算点积和各向量的模
        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        
        // 避免除零
        if (normA == 0 || normB == 0) return 0;
        
        // 计算余弦相似度
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
