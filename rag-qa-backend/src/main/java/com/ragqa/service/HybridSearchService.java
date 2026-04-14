package com.ragqa.service;

import com.ragqa.service.Bm25SearchService.Bm25Result;
import com.ragqa.service.Bm25SearchService.DocumentChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * ============================================================
 * 混合检索服务
 * ============================================================
 *
 * 【功能说明】
 * 融合向量检索和 BM25 关键词检索，返回综合排序最优的结果。
 *
 * 【为什么需要混合检索】
 * ┌─────────────────────────────────────────────────────────────────┐
 * │                    单一检索的局限性                               │
 * ├─────────────────────────────────────────────────────────────────┤
 * │                                                                 │
 * │   【向量检索的问题】                                              │
 * │   ✅ 语义理解强：                                                │
 * │      "如何戒烟" → 能匹配 "减少抽烟的方法"                         │
 * │   ❌ 专有名词不精确：                                            │
 * │      "Python 3.10" → 可能匹配到 "Python 3.9"                   │
 * │   ❌ 拼写错误容忍：可能导致匹配错误的结果                         │
 * │                                                                 │
 * │   【BM25 检索的问题】                                            │
 * │   ✅ 关键词精确匹配：                                            │
 * │      "Java 继承" → 精确匹配含这些词的文档                        │
 * │   ✅ 词频权重合理：常见词（"的"）权重低，不常见词权重高          │
 * │   ❌ 语义理解弱：                                                │
 * │      "最长的河流" → 不会匹配到 "长江是世界上最长的河流"           │
 * │                                                                 │
 * └─────────────────────────────────────────────────────────────────┘
 *
 * 【混合检索的优势】
 * 1. 语义 + 精确兼顾
 * 2. 覆盖更多检索场景
 * 3. 鲁棒性更强
 *
 * 【融合方法：RRF (Reciprocal Rank Fusion)】
 *
 * 公式：score(d) = Σ 1 / (k + rank_i(d))
 *
 * 【通俗理解】
 * - 不看绝对分数，只看排名
 * - 排名靠前，贡献的分数就高
 * - 不同检索方法的结果取长补短
 *
 * 【示例】
 * 假设用户问："Java 多线程实现"
 *
 * 向量检索 Top-5：
 * 1. "Python 多线程教程"    score=0.92
 * 2. "Java 基础语法"        score=0.88
 * 3. "Java 并发编程"        score=0.85
 * 4. "多线程原理"           score=0.82
 * 5. "Java Thread 类"       score=0.80
 *
 * BM25 检索 Top-5：
 * 1. "Java 多线程实现方式"  score=15.2
 * 2. "Thread 和 Runnable"   score=12.8
 * 3. "Java 并发工具类"      score=10.5
 * 4. "多线程同步机制"       score=9.2
 * 5. "Python 多线程"        score=8.1
 *
 * RRF 融合（k=60）：
 * - "Java 多线程实现方式": 1/(60+2) + 1/(60+1) = 0.0161 + 0.0164 = 0.0325  ← 综合第1！
 * - "Java 基础语法":       1/(60+2) + 0 = 0.0161                      ← 降下去了
 * - "Java 并发编程":        1/(60+3) + 1/(60+3) = 0.0159 + 0.0159 = 0.0318
 * - "Python 多线程教程":    1/(60+1) + 1/(60+5) = 0.0164 + 0.0154 = 0.0318
 *
 * 最终结果：Java 多线程相关的内容排在前面！
 */
@Service
@Slf4j
public class HybridSearchService {

    // ============================================================
    // 配置项
    // ============================================================

    /** 是否启用混合检索 */
    // 【说明】设为 false 时，退化为纯向量检索
    @Value("${hybrid.retrieval.enabled:false}")
    private boolean hybridEnabled;

    /** 向量检索的融合权重 */
    // 【说明】向量检索在最终分数中的占比
    // 【示例】0.7 表示向量检索贡献 70% 的分数
    @Value("${hybrid.retrieval.vector_weight:0.7}")
    private double vectorWeight;

    /** BM25 检索的融合权重 */
    @Value("${hybrid.retrieval.bm25_weight:0.3}")
    private double bm25Weight;

    /** 最终返回的结果数量 */
    @Value("${hybrid.retrieval.topk:5}")
    private int defaultTopK;

    // ============================================================
    // 依赖服务
    // ============================================================

    /** 向量检索服务 */
    private final ChromaService chromaService;

    /** BM25 检索服务 */
    private final Bm25SearchService bm25Service;

    /** 向量化服务（用于生成查询向量） */
    private final EmbeddingService embeddingService;

    // ============================================================
    // 构造方法
    // ============================================================

    public HybridSearchService(
            ChromaService chromaService,
            Bm25SearchService bm25Service,
            EmbeddingService embeddingService) {
        this.chromaService = chromaService;
        this.bm25Service = bm25Service;
        this.embeddingService = embeddingService;
    }

    // ============================================================
    // 数据结构
    // ============================================================

    /**
     * 混合检索结果
     *
     * 【说明】包含检索结果的完整信息
     * - 文档内容
     * - 来源信息（文档ID、切片索引）
     * - 分数（综合分数、各方法分数）
     * - 来源标识（VECTOR/BM25/HYBRID）
     */
    public static class HybridSearchResult {
        /** 文档切片内容 */
        private final String content;
        /** 所属文档ID */
        private final String documentId;
        /** 切片索引 */
        private final int chunkIndex;
        /** 融合后的综合分数 */
        private final double score;
        /** 向量检索分数（如果有） */
        private final Double vectorScore;
        /** BM25 分数（如果有） */
        private final Double bm25Score;
        /** 来源：
         * - VECTOR: 仅向量检索命中
         * - BM25: 仅 BM25 命中
         * - HYBRID: 两者都命中
         */
        private final String source;

        public HybridSearchResult(String content, String documentId, int chunkIndex,
                                 double score, Double vectorScore, Double bm25Score, String source) {
            this.content = content;
            this.documentId = documentId;
            this.chunkIndex = chunkIndex;
            this.score = score;
            this.vectorScore = vectorScore;
            this.bm25Score = bm25Score;
            this.source = source;
        }

        // Getters
        public String getContent() { return content; }
        public String getDocumentId() { return documentId; }
        public int getChunkIndex() { return chunkIndex; }
        public double getScore() { return score; }
        public Double getVectorScore() { return vectorScore; }
        public Double getBm25Score() { return bm25Score; }
        public String getSource() { return source; }
    }

    // ============================================================
    // 核心方法
    // ============================================================

    /**
     * 执行混合检索
     *
     * 【入口方法】外部调用的主要接口
     *
     * 【处理逻辑】
     * 1. 检查是否启用混合检索
     * 2. 如果 BM25 索引为空或混合检索禁用 → 退化为纯向量检索
     * 3. 否则执行混合检索
     *
     * @param query 用户查询
     * @param topK 返回结果数（如果 ≤0，使用默认值）
     * @return 混合检索结果列表
     */
    public List<HybridSearchResult> search(String query, int topK) {
        // 使用默认值
        if (topK <= 0) {
            topK = defaultTopK;
        }

        // 检查是否可以执行混合检索
        if (bm25Service.getDocumentCount() == 0) {
            log.debug("BM25 索引为空，使用纯向量检索");
            return vectorOnlySearch(query, topK);
        }

        if (!hybridEnabled) {
            log.debug("混合检索已禁用，使用纯向量检索");
            return vectorOnlySearch(query, topK);
        }

        // 执行混合检索
        return hybridSearch(query, topK);
    }

    /**
     * 执行混合检索（核心算法）
     *
     * 【流程】
     * 1. 并行执行向量检索和 BM25 检索
     * 2. 构建排名映射
     * 3. 计算 RRF 融合分数
     * 4. 按分数排序，返回 Top-K
     */
    private List<HybridSearchResult> hybridSearch(String query, int topK) {
        log.debug("执行混合检索: query={}, topK={}", query, topK);

        // Step 1: 并行执行两种检索
        // 【为什么向量检索取 Top-20，而 BM25 也取 Top-20？】
        // - 这是为了平衡准确率和召回率
        // - Top-20 已经覆盖大部分相关结果
        // - 太多会增加计算量，太少可能遗漏

        // 向量检索：初筛 Top-20
        List<ChromaService.SearchResult> vectorResults = chromaService.similaritySearch(query, 20);
        log.debug("向量检索返回 {} 条结果", vectorResults.size());

        // BM25 检索：返回 Top-20
        List<Bm25Result> bm25Results = bm25Service.search(query, 20);
        log.debug("BM25 检索返回 {} 条结果", bm25Results.size());

        // Step 2: 构建排名映射
        // docKey -> rank（在各自列表中的排名，从1开始）
        Map<String, Integer> vectorRanks = buildRankMap(vectorResults);
        Map<String, Integer> bm25Ranks = buildBm25RankMap(bm25Results);

        // Step 3: 获取所有文档的唯一集合
        Set<String> allDocKeys = new HashSet<>();
        allDocKeys.addAll(vectorRanks.keySet());
        allDocKeys.addAll(bm25Ranks.keySet());

        // Step 4: 计算 RRF 融合分数
        // 【公式】
        // fusion_score = vector_weight × (1 / (k + vector_rank))
        //              + bm25_weight × (1 / (k + bm25_rank))
        //
        // 【通俗理解】
        // - 如果文档在向量检索排第1，则贡献 1/(k+1)
        // - 如果文档在 BM25 排第2，则贡献 1/(k+2)
        // - 两者加权求和

        Map<String, Double> fusionScores = new HashMap<>();
        Map<String, HybridSearchResult> resultBuilder = new HashMap<>();

        for (String docKey : allDocKeys) {
            double fusionScore = 0;
            Double vScore = null;
            Double bScore = null;

            // 处理向量检索结果
            if (vectorRanks.containsKey(docKey)) {
                int rank = vectorRanks.get(docKey);
                double rrfScore = 1.0 / (60 + rank);  // RRF 公式
                fusionScore += vectorWeight * rrfScore;
                vScore = 1.0 / rank;  // 原始排名分数（1/rank）

                // 获取内容信息并构建结果
                ChromaService.SearchResult vr = findVectorResult(vectorResults, docKey);
                if (vr != null) {
                    int chunkIdx = Integer.parseInt(vr.chunkIndex());
                    resultBuilder.put(docKey, new HybridSearchResult(
                            vr.content(), vr.documentId(), chunkIdx,
                            fusionScore, vScore, null, "VECTOR"));
                }
            }

            // 处理 BM25 结果
            if (bm25Ranks.containsKey(docKey)) {
                int rank = bm25Ranks.get(docKey);
                double rrfScore = 1.0 / (60 + rank);
                fusionScore += bm25Weight * rrfScore;
                bScore = 1.0 / rank;

                // 如果向量结果已有，合并信息
                if (resultBuilder.containsKey(docKey)) {
                    HybridSearchResult existing = resultBuilder.get(docKey);
                    resultBuilder.put(docKey, new HybridSearchResult(
                            existing.getContent(), existing.getDocumentId(), existing.getChunkIndex(),
                            fusionScore, vScore, bScore, "HYBRID"));  // 来源改为 HYBRID
                } else {
                    // 仅 BM25 命中的结果
                    Bm25Result br = findBm25Result(bm25Results, docKey);
                    if (br != null) {
                        resultBuilder.put(docKey, new HybridSearchResult(
                                br.getContent(), br.getChunk().getDocumentId(), br.getChunk().getChunkIndex(),
                                fusionScore, null, bScore, "BM25"));
                    }
                }
            }

            fusionScores.put(docKey, fusionScore);
        }

        // Step 5: 按融合分数排序，取 Top-K
        List<Map.Entry<String, Double>> sorted = new ArrayList<>(fusionScores.entrySet());
        sorted.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        List<HybridSearchResult> results = new ArrayList<>();
        int count = 0;
        for (Map.Entry<String, Double> entry : sorted) {
            if (count >= topK) break;
            HybridSearchResult result = resultBuilder.get(entry.getKey());
            if (result != null) {
                results.add(result);
                count++;
            }
        }

        log.debug("混合检索最终返回 {} 条结果", results.size());
        return results;
    }

    /**
     * 纯向量检索（降级方案）
     *
     * 【使用场景】
     * 1. BM25 索引为空时
     * 2. 混合检索被禁用时
     */
    private List<HybridSearchResult> vectorOnlySearch(String query, int topK) {
        List<ChromaService.SearchResult> vectorResults = chromaService.similaritySearch(query, topK);

        List<HybridSearchResult> results = new ArrayList<>();
        for (int i = 0; i < vectorResults.size(); i++) {
            ChromaService.SearchResult r = vectorResults.get(i);
            // SearchResult.chunkIndex 是 String 类型，需要转换
            int chunkIdx = Integer.parseInt(r.chunkIndex());
            results.add(new HybridSearchResult(
                    r.content(), r.documentId(), chunkIdx,
                    1.0 / (i + 1),  // 排名分数
                    1.0 / (i + 1),
                    null,
                    "VECTOR"
            ));
        }
        return results;
    }

    // ============================================================
    // 辅助方法
    // ============================================================

    /**
     * 从向量检索结果构建排名映射
     *
     * @return Map<docKey, rank> rank 从 1 开始
     */
    private Map<String, Integer> buildRankMap(List<ChromaService.SearchResult> results) {
        Map<String, Integer> ranks = new HashMap<>();
        int rank = 1;
        for (ChromaService.SearchResult r : results) {
            ranks.put(getDocKey(r), rank++);
        }
        return ranks;
    }

    /**
     * 从 BM25 检索结果构建排名映射
     */
    private Map<String, Integer> buildBm25RankMap(List<Bm25Result> results) {
        Map<String, Integer> ranks = new HashMap<>();
        int rank = 1;
        for (Bm25Result r : results) {
            ranks.put(getBm25DocKey(r), rank++);
        }
        return ranks;
    }

    /**
     * 获取向量检索结果的文档键
     *
     * 【格式】documentId_chunkIndex
     */
    private String getDocKey(ChromaService.SearchResult r) {
        return r.documentId() + "_" + r.chunkIndex();
    }

    /**
     * 获取 BM25 检索结果的文档键
     */
    private String getBm25DocKey(Bm25Result r) {
        return r.getChunk().getId();
    }

    /**
     * 在向量检索结果中查找指定 docKey
     */
    private ChromaService.SearchResult findVectorResult(List<ChromaService.SearchResult> results, String docKey) {
        for (ChromaService.SearchResult r : results) {
            if (getDocKey(r).equals(docKey)) {
                return r;
            }
        }
        return null;
    }

    /**
     * 在 BM25 检索结果中查找指定 docKey
     */
    private Bm25Result findBm25Result(List<Bm25Result> results, String docKey) {
        for (Bm25Result r : results) {
            if (getBm25DocKey(r).equals(docKey)) {
                return r;
            }
        }
        return null;
    }

    // ============================================================
    // 配置查询
    // ============================================================

    public boolean isHybridEnabled() {
        return hybridEnabled;
    }

    public double getVectorWeight() {
        return vectorWeight;
    }

    public double getBm25Weight() {
        return bm25Weight;
    }
}
