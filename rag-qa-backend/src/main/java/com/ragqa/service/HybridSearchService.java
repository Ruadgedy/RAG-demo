package com.ragqa.service;

import com.ragqa.service.Bm25SearchService.Bm25Result;
import com.ragqa.service.Bm25SearchService.DocumentChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 混合检索服务
 *
 * 作用：融合向量检索和 BM25 关键词检索，提供更准确的检索结果
 *
 * 为什么需要混合检索：
 * ┌─────────────────────────────────────────────────────────────────┐
 * │                    单一检索的局限性                               │
 * ├─────────────────────────────────────────────────────────────────┤
 * │  向量检索：                                                       │
 * │  ✅ 语义理解强："如何戒烟" → 能匹配"减少抽烟的方法"                │
 * │  ❌ 专有名词不精确："Python 3.10" → 可能匹配到其他版本            │
 * │  ❌ 拼写错误容忍：可能导致匹配错误的结果                          │
 * │                                                                  │
 * │  BM25 检索：                                                      │
 * │  ✅ 关键词精确匹配："Java 继承" → 精确匹配含这些词的文档            │
 * │  ✅ 词频权重：常见词权重降低，不常见词权重提高                    │
 * │  ❌ 语义理解弱："最长的河流" → 不会匹配"长江是世界上最长的河流"   │
 * └─────────────────────────────────────────────────────────────────┘
 *
 * 融合方法 - Reciprocal Rank Fusion (RRF)：
 *
 * RRF 是一种无参数的排名融合方法，计算简单且效果好：
 *
 *         score(d) = Σ -----------------------
 *                       k + rank_i(d)
 *
 * 其中：
 * - d 是文档
 * - rank_i(d) 是文档 d 在第 i 个检索结果列表中的排名（从1开始）
 * - k 是平滑因子（默认60，值越大排名差异影响越小）
 *
 * 示例：
 * 假设有两个检索列表：
 * - 向量检索：[DocA, DocB, DocC]
 * - BM25检索：[DocB, DocA, DocD]
 *
 * 计算 RRF 分数（k=60）：
 * - DocA: 1/(60+1) + 1/(60+2) = 0.01639 + 0.01587 = 0.03226
 * - DocB: 1/(60+2) + 1/(60+1) = 0.01587 + 0.01639 = 0.03226
 * - DocC: 1/(60+3) + 0        = 0.01562
 * - DocD: 0 + 1/(60+3)        = 0.01562
 *
 * 融合结果：DocA ≈ DocB > DocC = DocD
 *
 * 配置说明：
 * - hybrid.retrieval.enabled: 是否启用混合检索
 * - hybrid.retrieval.vector_weight: 向量检索权重（默认0.7）
 * - hybrid.retrieval.bm25_weight: BM25权重（默认0.3）
 * - hybrid.retrieval.topk: 最终返回的结果数
 */
@Service
@Slf4j
public class HybridSearchService {

    /** 是否启用混合检索 */
    @Value("${hybrid.retrieval.enabled:false}")
    private boolean hybridEnabled;

    /** 向量检索的权重 */
    @Value("${hybrid.retrieval.vector_weight:0.7}")
    private double vectorWeight;

    /** BM25 检索的权重 */
    @Value("${hybrid.retrieval.bm25_weight:0.3}")
    private double bm25Weight;

    /** 最终返回的结果数 */
    @Value("${hybrid.retrieval.topk:5}")
    private int defaultTopK;

    /** RRF 平滑因子 */
    private static final int RRF_K = 60;

    /** 向量检索服务 */
    private final ChromaService chromaService;

    /** BM25 检索服务 */
    private final Bm25SearchService bm25Service;

    /** 向量化服务（用于生成查询向量） */
    private final EmbeddingService embeddingService;

    /**
     * 构造方法
     *
     * @param chromaService 向量数据库服务
     * @param bm25Service BM25 检索服务
     * @param embeddingService 向量化服务
     */
    public HybridSearchService(
            ChromaService chromaService,
            Bm25SearchService bm25Service,
            EmbeddingService embeddingService) {
        this.chromaService = chromaService;
        this.bm25Service = bm25Service;
        this.embeddingService = embeddingService;
    }

    /**
     * 混合检索结果
     */
    public static class HybridSearchResult {
        /** 文档内容 */
        private final String content;
        /** 所属文档ID */
        private final String documentId;
        /** 切片索引 */
        private final int chunkIndex;
        /** 融合后的综合分数 */
        private final double score;
        /** 向量检索分数 */
        private final Double vectorScore;
        /** BM25 分数 */
        private final Double bm25Score;
        /** 来源：VECTOR=仅向量, BM25=仅BM25, HYBRID=两者融合 */
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

        public String getContent() { return content; }
        public String getDocumentId() { return documentId; }
        public int getChunkIndex() { return chunkIndex; }
        public double getScore() { return score; }
        public Double getVectorScore() { return vectorScore; }
        public Double getBm25Score() { return bm25Score; }
        public String getSource() { return source; }
    }

    /**
     * 执行混合检索
     *
     * 根据配置决定使用单一检索还是混合检索
     *
     * @param query 用户查询
     * @param topK 返回结果数（如果为0，使用默认值）
     * @return 混合检索结果列表
     */
    public List<HybridSearchResult> search(String query, int topK) {
        if (topK <= 0) {
            topK = defaultTopK;
        }

        // 如果 BM25 索引为空，退化为纯向量检索
        if (bm25Service.getDocumentCount() == 0) {
            log.debug("BM25 索引为空，使用纯向量检索");
            return vectorOnlySearch(query, topK);
        }

        // 如果禁用混合检索，退化为纯向量检索
        if (!hybridEnabled) {
            log.debug("混合检索已禁用，使用纯向量检索");
            return vectorOnlySearch(query, topK);
        }

        // 执行混合检索
        return hybridSearch(query, topK);
    }

    /**
     * 执行混合检索
     *
     * 同时执行向量检索和 BM25 检索，然后使用 RRF 融合
     */
    private List<HybridSearchResult> hybridSearch(String query, int topK) {
        log.debug("执行混合检索: query={}, topK={}", query, topK);

        // Step 1: 并行执行两种检索
        // 向量检索：初筛 Top-20（给 BM25 更多候选）
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
        // score = vectorWeight * (1 / (RRF_K + vectorRank)) + bm25Weight * (1 / (RRF_K + bm25Rank))
        Map<String, Double> fusionScores = new HashMap<>();
        Map<String, HybridSearchResult> resultBuilder = new HashMap<>();

        for (String docKey : allDocKeys) {
            double fusionScore = 0;
            Double vScore = null;
            Double bScore = null;

            // 向量检索贡献
            if (vectorRanks.containsKey(docKey)) {
                int rank = vectorRanks.get(docKey);
                double rrfScore = 1.0 / (RRF_K + rank);
                fusionScore += vectorWeight * rrfScore;
                vScore = 1.0 / rank;  // 原始排名分数（1/rank）

                // 获取内容信息
                ChromaService.SearchResult vr = vectorResults.stream()
                        .filter(r -> getDocKey(r).equals(docKey))
                        .findFirst().orElse(null);
                if (vr != null) {
                    resultBuilder.put(docKey, new HybridSearchResult(
                            vr.getContent(), vr.getDocumentId(), vr.getChunkIndex(),
                            fusionScore, vScore, null, "VECTOR"));
                }
            }

            // BM25 检索贡献
            if (bm25Ranks.containsKey(docKey)) {
                int rank = bm25Ranks.get(docKey);
                double rrfScore = 1.0 / (RRF_K + rank);
                fusionScore += bm25Weight * rrfScore;
                bScore = 1.0 / rank;

                // 如果向量结果已有，合并信息
                if (resultBuilder.containsKey(docKey)) {
                    HybridSearchResult existing = resultBuilder.get(docKey);
                    resultBuilder.put(docKey, new HybridSearchResult(
                            existing.getContent(), existing.getDocumentId(), existing.getChunkIndex(),
                            fusionScore, vScore, bScore, "HYBRID"));
                } else {
                    // 仅 BM25 命中的结果
                    Bm25Result br = bm25Results.stream()
                            .filter(r -> getBm25DocKey(r).equals(docKey))
                            .findFirst().orElse(null);
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
     */
    private List<HybridSearchResult> vectorOnlySearch(String query, int topK) {
        List<ChromaService.SearchResult> vectorResults = chromaService.similaritySearch(query, topK);

        List<HybridSearchResult> results = new ArrayList<>();
        for (ChromaService.SearchResult r : vectorResults) {
            results.add(new HybridSearchResult(
                    r.getContent(), r.getDocumentId(), r.getChunkIndex(),
                    1.0 / (vectorResults.indexOf(r) + 1),  // 归一化分数
                    1.0 / (vectorResults.indexOf(r) + 1),
                    null,
                    "VECTOR"
            ));
        }
        return results;
    }

    /**
     * 从向量检索结果构建排名映射
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
     */
    private String getDocKey(ChromaService.SearchResult r) {
        return r.getDocumentId() + "_" + r.getChunkIndex();
    }

    /**
     * 获取 BM25 检索结果的文档键
     */
    private String getBm25DocKey(Bm25Result r) {
        return r.getChunk().getId();
    }

    /**
     * 检查混合检索是否启用
     */
    public boolean isHybridEnabled() {
        return hybridEnabled;
    }

    /**
     * 获取向量检索权重
     */
    public double getVectorWeight() {
        return vectorWeight;
    }

    /**
     * 获取 BM25 检索权重
     */
    public double getBm25Weight() {
        return bm25Weight;
    }
}
