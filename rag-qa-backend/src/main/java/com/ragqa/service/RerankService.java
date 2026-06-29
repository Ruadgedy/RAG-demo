package com.ragqa.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.*;

/**
 * Cross-Encoder 重排序服务
 *
 * 作用：对初步检索结果做精细排序，提升 top-K 精度。
 *
 * 两阶段检索流程：
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────┐
 *   │  第一阶段：向量检索（召回）                                          │
 *   │  ────────────────────────────────────────────────────────────    │
 *   │  用户问题 → Embedding → Chroma 向量检索 → Top-20 候选                │
 *   │  目标：快，覆盖广                                                  │
 *   │  特点：可能漏掉正确答案，或召回若干"看着像但其实无关"的噪音              │
 *   ├─────────────────────────────────────────────────────────────────┤
 *   │  第二阶段：Cross-Encoder 重排（精排）★ 本服务                       │
 *   │  ────────────────────────────────────────────────────────────    │
 *   │  (候选, 问题) → Cross-Encoder → 相关性打分 → Top-3                  │
 *   │  目标：准，对候选精细排序                                            │
 *   │  特点：慢，但能识别"语义相似但实际不相关"的噪音                        │
 *   └─────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * Bi-Encoder vs Cross-Encoder：
 * <pre>
 *   ┌─────────────────┬──────────────────┬──────────────────┐
 *   │                 │  Bi-Encoder      │ Cross-Encoder    │
 *   ├─────────────────┼──────────────────┼──────────────────┤
 *   │ 工作方式         │ Q → emb,         │ (Q, D) →         │
 *   │                 │ D → emb,         │ joint emb →      │
 *   │                 │ cosine(embs)      │ score            │
 *   ├─────────────────┼──────────────────┼──────────────────┤
 *   │ 速度             │ 快（离线预计算）   │ 慢（在线两两计算）│
 *   │ 准确度           │ 中               │ 高               │
 *   └─────────────────┴──────────────────┴──────────────────┘
 * </pre>
 *
 * 【2026-06-29 升级 P1-02】从「关键词打分模拟」切换到真实 cross-encoder
 *
 * 历史问题：原 scoreCandidates() 用关键词匹配 + 位置加权做"伪 rerank"。
 *   实际效果：经常把"包含 query 字面词"的噪音文档排在前面，反而拖累 LLM。
 *
 * 新实现：调用 Ollama 0.4+ 的 /api/rerank 端点，用真正的 cross-encoder 模型。
 *   - 默认模型：qwen3-reranker:4b（与本地 embedding 模型 qwen3-embedding:4b 配套）
 *   - 备用模型：bge-reranker-v2-m3（多语言版，质量略高但稍慢）
 *   - 安装命令：ollama pull qwen3-reranker:4b
 *
 * 失败回退：Ollama 调用失败时（模型未拉取、超时、网络问题），自动降级为直接返回
 *   原始候选列表（按向量分数排序），并打 WARN 日志。不阻塞用户请求。
 *
 * 配置项：
 *   rerank.enabled           是否启用（默认 false，需手动开启）
 *   rerank.model             Ollama 中的 rerank 模型名
 *   rerank.topk              重排后返回数（传给 LLM 的 top-K）
 *   rerank.ollama-url        Ollama 地址（默认与 embedding 同地址）
 *   rerank.timeout-seconds   Ollama 调用超时（cross-encoder 比 embedding 慢）
 */
@Service
@Slf4j
public class RerankService {

    /**
     * RestTemplate：连接/读取超时单独配
     *
     * cross-encoder 在长文档上推理较慢（BGE-large 大约 500ms-2s/候选×20 候选 ≈ 10-40s），
     * 这里 connect=5s / read=60s（默认值 60s）。
     */
    private final RestTemplate restTemplate;

    /** JSON 解析器 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 是否启用重排序（默认关闭，需显式开启） */
    @Value("${rerank.enabled:false}")
    private boolean rerankEnabled;

    /** Ollama rerank 模型名（如 qwen3-reranker:4b / bge-reranker-v2-m3） */
    @Value("${rerank.model:qwen3-reranker:4b}")
    private String modelName;

    /** 重排后返回结果数（传给 LLM 的 top-K） */
    @Value("${rerank.topk:5}")
    private int defaultTopK;

    /** Ollama 服务地址，默认与 embedding 同一实例 */
    @Value("${rerank.ollama-url:http://localhost:11434}")
    private String ollamaUrl;

    /**
     * Ollama rerank 调用的 HTTP 超时（秒）
     *
     * 【为什么单独配】
     * - cross-encoder 比 embedding 模型慢一个数量级
     * - 20 个候选 + 4B 参数模型在 CPU 上可能 30s+ 完成
     * - 默认 RestTemplate 超时太短会误杀正常调用
     */
    @Value("${rerank.timeout-seconds:60}")
    private int timeoutSeconds;

    public RerankService() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(60).toMillis()); // 默认值，会被 setReadTimeout 覆盖
        this.restTemplate = new RestTemplate(factory);
    }

    /**
     * 重排序候选（内部数据结构，保留供向后兼容）
     */
    public static class RerankCandidate {
        private final String content;
        private final String documentId;
        private final int chunkIndex;
        private final double originalScore;

        public RerankCandidate(String content, String documentId, int chunkIndex, double originalScore) {
            this.content = content;
            this.documentId = documentId;
            this.chunkIndex = chunkIndex;
            this.originalScore = originalScore;
        }

        public String getContent() { return content; }
        public String getDocumentId() { return documentId; }
        public int getChunkIndex() { return chunkIndex; }
        public double getOriginalScore() { return originalScore; }
    }

    /**
     * 重排序结果（对外接口）
     *
     * @param content 切片内容
     * @param documentId 文档 UUID（String 形式）
     * @param chunkIndex 切片在文档中的索引
     * @param score 重排分数（cross-encoder 输出，范围由模型决定，常见 [0,1] 或 [-1,1]）
     * @param source 来源标识：VECTOR（未重排）/ RERANKED（cross-encoder 重排过）
     */
    public static class RerankResult {
        private final String content;
        private final String documentId;
        private final int chunkIndex;
        private final double score;
        private final String source;

        public RerankResult(String content, String documentId, int chunkIndex, double score, String source) {
            this.content = content;
            this.documentId = documentId;
            this.chunkIndex = chunkIndex;
            this.score = score;
            this.source = source;
        }

        public String getContent() { return content; }
        public String getDocumentId() { return documentId; }
        public int getChunkIndex() { return chunkIndex; }
        public double getScore() { return score; }
        public String getSource() { return source; }
    }

    /**
     * 执行重排序（统一入口）
     *
     * 行为：
     *   - rerank.enabled=false → 直接 passthrough（按输入顺序截前 topK）
     *   - rerank.enabled=true  → 调用 Ollama /api/rerank，按模型返回的相关性重排
     *   - Ollama 调用失败      → 自动降级为 passthrough + WARN 日志（不阻塞请求）
     *
     * @param query 用户查询
     * @param candidates 候选列表（来自向量检索或混合检索）
     * @param topK 返回前 K 个
     * @return 重排后的结果（始终不返回 null）
     */
    public List<RerankResult> rerank(String query, List<?> candidates, int topK) {
        if (candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }

        // 未启用 → 直接 passthrough（保留旧行为兼容性）
        if (!rerankEnabled) {
            log.debug("rerank.enabled=false，直接 passthrough");
            return passthrough(candidates, topK);
        }

        log.info("开始 Cross-Encoder 重排序：model={}, candidates={}, query='{}'",
                modelName, candidates.size(), truncate(query, 50));

        try {
            // 1. 提取候选文本 + 元数据
            List<RerankCandidate> candidateList = extractCandidates(candidates);
            if (candidateList.isEmpty()) {
                return Collections.emptyList();
            }

            // 2. 调用 Ollama /api/rerank
            List<Integer> rankedIndices = callOllamaRerank(query, candidateList, topK);

            // 3. 按 rerank 分数从高到低组装结果
            List<RerankResult> results = new ArrayList<>();
            for (Integer idx : rankedIndices) {
                if (idx < 0 || idx >= candidateList.size()) continue;
                RerankCandidate c = candidateList.get(idx);
                results.add(new RerankResult(
                        c.getContent(), c.getDocumentId(), c.getChunkIndex(),
                        c.getOriginalScore(), "RERANKED"));
            }

            log.info("重排序完成：{} 候选 → top-{} 结果", candidateList.size(), results.size());
            return results;

        } catch (Exception e) {
            // Ollama 调用失败（模型未拉取、超时、网络问题）→ 降级 passthrough
            log.warn("Cross-Encoder rerank 调用失败，降级为 passthrough：{}", e.getMessage());
            return passthrough(candidates, topK);
        }
    }

    /**
     * 候选直通（rerank 关闭或失败时使用）
     *
     * 按候选原始分数降序排，取 topK。
     */
    private List<RerankResult> passthrough(List<?> candidates, int topK) {
        List<RerankResult> results = new ArrayList<>();
        for (Object c : candidates) {
            try {
                if (c instanceof ChromaService.SearchResult) {
                    ChromaService.SearchResult r = (ChromaService.SearchResult) c;
                    results.add(new RerankResult(
                            r.content(), r.documentId(), Integer.parseInt(r.chunkIndex()),
                            r.score(), "VECTOR"));
                } else if (c instanceof HybridSearchService.HybridSearchResult) {
                    HybridSearchService.HybridSearchResult r = (HybridSearchService.HybridSearchResult) c;
                    results.add(new RerankResult(
                            r.getContent(), r.getDocumentId(), r.getChunkIndex(),
                            r.getScore(), r.getSource()));
                } else {
                    log.warn("未知候选类型，跳过: {}", c == null ? "null" : c.getClass().getName());
                }
            } catch (Exception e) {
                log.warn("候选转换失败: {}", e.getMessage());
            }
        }

        // 已经有分数 → 按分数降序
        results.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
        if (results.size() > topK) {
            return results.subList(0, topK);
        }
        return results;
    }

    /**
     * 提取通用候选为 RerankCandidate 列表
     */
    private List<RerankCandidate> extractCandidates(List<?> candidates) {
        List<RerankCandidate> result = new ArrayList<>();
        for (Object c : candidates) {
            try {
                if (c instanceof ChromaService.SearchResult) {
                    ChromaService.SearchResult r = (ChromaService.SearchResult) c;
                    result.add(new RerankCandidate(
                            r.content(), r.documentId(), Integer.parseInt(r.chunkIndex()), r.score()));
                } else if (c instanceof HybridSearchService.HybridSearchResult) {
                    HybridSearchService.HybridSearchResult r = (HybridSearchService.HybridSearchResult) c;
                    result.add(new RerankCandidate(
                            r.getContent(), r.getDocumentId(), r.getChunkIndex(), r.getScore()));
                } else {
                    log.warn("跳过未知候选类型: {}", c == null ? "null" : c.getClass().getName());
                }
            } catch (Exception e) {
                log.warn("候选解析失败: {}", e.getMessage());
            }
        }
        return result;
    }

    /**
     * 调用 Ollama /api/rerank 端点
     *
     * Ollama rerank API（0.4+）：
     *   POST {ollamaUrl}/api/rerank
     *   Body: {"model": "...", "query": "...", "documents": [...], "top_n": N}
     *   Response: {"model": "...", "results": [{"index": 0, "relevance_score": 0.95}, ...]}
     *
     * 注意：response 里的 results 已按 relevance_score 降序排，直接取 index 用即可。
     *
     * @param query 查询
     * @param candidates 候选列表
     * @param topN 返回前 N 个的索引
     * @return 按相关性降序排列的候选索引列表（已限制 topN 个）
     * @throws Exception 网络/HTTP/解析异常（由调用方降级处理）
     */
    private List<Integer> callOllamaRerank(String query, List<RerankCandidate> candidates, int topN) throws Exception {
        // 1. 动态设置 read timeout（构造时的默认值会被本次覆盖）
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(timeoutSeconds).toMillis());
        RestTemplate rt = new RestTemplate(factory);

        // 2. 构建请求体
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelName);
        body.put("query", query);
        body.put("top_n", topN);

        // documents 字段：纯字符串数组（Ollama rerank API 格式）
        List<String> documents = new ArrayList<>();
        for (RerankCandidate c : candidates) {
            // 截断过长的文档（cross-encoder 有 token 上限，通常 512-8192 tokens）
            // 8000 字符 ≈ 2000-4000 中文字，安全范围
            String content = c.getContent();
            if (content != null && content.length() > 8000) {
                content = content.substring(0, 8000) + "...";
            }
            documents.add(content == null ? "" : content);
        }
        body.put("documents", documents);

        // 3. 发送请求
        String url = ollamaUrl + "/api/rerank";
        String response = rt.postForObject(url, body, String.class);

        // 4. 解析响应
        JsonNode root = objectMapper.readTree(response);
        JsonNode results = root.get("results");
        if (results == null || !results.isArray()) {
            throw new RuntimeException("Ollama rerank 响应缺少 results 字段");
        }

        List<Integer> rankedIndices = new ArrayList<>();
        for (JsonNode item : results) {
            int idx = item.get("index").asInt();
            rankedIndices.add(idx);
        }

        log.debug("Ollama rerank 返回 {} 个索引（按相关性降序）", rankedIndices.size());
        return rankedIndices;
    }

    /**
     * 检查 rerank 是否启用
     */
    public boolean isEnabled() {
        return rerankEnabled;
    }

    /**
     * 获取配置信息（调试 / 启动日志用）
     */
    public String getConfigInfo() {
        return String.format("rerank config: enabled=%s, model=%s, topk=%d, ollama=%s, timeout=%ds",
                rerankEnabled, modelName, defaultTopK, ollamaUrl, timeoutSeconds);
    }

    /**
     * 字符串截断（用于日志，避免超长 query 刷屏）
     */
    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}