package com.ragqa.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Cross-Encoder 重排序服务
 *
 * 作用：对初步检索结果进行精细排序，提高最终答案质量
 *
 * 为什么需要重排序：
 * ┌─────────────────────────────────────────────────────────────────┐
 * │                    两阶段检索流程                                  │
 * ├─────────────────────────────────────────────────────────────────┤
 * │                                                                 │
 * │   第一阶段：向量检索（召回）                                        │
 * │   ─────────────────────────────────────────────────────────      │
 * │   用户问题 → Embedding → Chroma 向量检索 → Top-20 候选           │
 * │   目标：快速从海量文档中找到可能相关的候选                            │
 * │   特点：快，但可能漏掉或误匹配                                        │
 * │                                                                 │
 * │   第二阶段：Cross-Encoder 重排（精排）                              │
 * │   ─────────────────────────────────────────────────────────      │
 * │   候选文档 + 问题 → Cross-Encoder → 精确相关性打分 → Top-5          │
 * │   目标：对候选进行精细排序，找到最相关的                               │
 * │   特点：准，但需要逐个计算（比向量检索慢）                            │
 * │                                                                 │
 * └─────────────────────────────────────────────────────────────────┘
 *
 * Cross-Encoder vs Bi-Encoder：
 *
 * ┌─────────────────┬──────────────────┬──────────────────┐
 * │                 │  Bi-Encoder      │ Cross-Encoder    │
 * ├─────────────────┼──────────────────┼──────────────────┤
 * │ 工作方式         │                  │                  │
 * │                 │ 问题 → Encoder →  │ [问题, 文档] →   │
 * │                 │ 文档 → Encoder →  │ Encoder → score  │
 * │                 │ 向量 → 相似度     │                  │
 * ├─────────────────┼──────────────────┼──────────────────┤
 * │ 速度             │ 快（一次编码）     │ 慢（两两计算）    │
 * │                 │ 可预计算向量       │ 需要实时计算      │
 * ├─────────────────┼──────────────────┼──────────────────┤
 * │ 准确度           │ 中等              │ 高               │
 * │                 │ 依赖向量空间       │ 直接学习相关性     │
 * ├─────────────────┼──────────────────┼──────────────────┤
 * │ 适用场景         │ 第一阶段召回       │ 第二阶段精排      │
 * └─────────────────┴──────────────────┴──────────────────┘
 *
 * 使用场景示例：
 * 用户问："Java 中如何实现多线程？"
 *
 * 向量检索结果（可能的问题）：
 * 1. "Python 的多线程实现方式"      score=0.85  ← 语义相关但不够精确
 * 2. "Java 继承的使用方法"         score=0.82  ← Java 相关但不是多线程
 * 3. "Java 多线程的 Thread 类"     score=0.80  ← ✅ 精确匹配
 * 4. "并发编程概述"                 score=0.78  ← 相关但不精确
 * 5. "Thread 和 Runnable 区别"      score=0.75  ← ✅ 精确匹配
 *
 * Cross-Encoder 重排后：
 * 1. "Java 多线程的 Thread 类"     score=0.95  ← 精确匹配
 * 2. "Thread 和 Runnable 区别"      score=0.93  ← 精确匹配
 * 3. "并发编程概述"                 score=0.72  ← 降级
 * 4. "Python 的多线程实现方式"      score=0.65  ← 降级（不是 Java）
 * 5. "Java 继承的使用方法"         score=0.55  ← 降级（不是多线程）
 *
 * 配置项：
 * - rerank.enabled: 是否启用重排序
 * - rerank.model: 重排序模型（可选本地 Ollama 或云服务）
 * - rerank.topk: 重排后返回的结果数
 */
@Service
@Slf4j
public class RerankService {

    /** 是否启用重排序 */
    @Value("${rerank.enabled:false}")
    private boolean rerankEnabled;

    /** 重排序模型名称 */
    @Value("${rerank.model:bge-reranker-base}")
    private String modelName;

    /** 重排后返回的结果数 */
    @Value("${rerank.topk:5}")
    private int defaultTopK;

    /**
     * 重排序候选结果
     */
    public static class RerankCandidate {
        private final String content;      // 文档内容
        private final String documentId;  // 文档ID
        private final int chunkIndex;     // 切片索引
        private final double originalScore; // 原始分数（向量检索）
        private double rerankScore;       // 重排分数
        private double finalScore;        // 综合分数（融合）

        public RerankCandidate(String content, String documentId, int chunkIndex, double originalScore) {
            this.content = content;
            this.documentId = documentId;
            this.chunkIndex = chunkIndex;
            this.originalScore = originalScore;
            this.rerankScore = 0.0;
            this.finalScore = 0.0;
        }

        public String getContent() { return content; }
        public String getDocumentId() { return documentId; }
        public int getChunkIndex() { return chunkIndex; }
        public double getOriginalScore() { return originalScore; }
        public double getRerankScore() { return rerankScore; }
        public double getFinalScore() { return finalScore; }
        public void setRerankScore(double rerankScore) { this.rerankScore = rerankScore; }
        public void setFinalScore(double finalScore) { this.finalScore = finalScore; }
    }

    /**
     * 重排序结果
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
     * 执行重排序
     *
     * @param query 用户查询
     * @param candidates 候选结果（来自向量检索）
     * @param topK 返回结果数
     * @return 重排后的结果
     */
    public List<RerankResult> rerank(String query, List<?> candidates, int topK) {
        if (!rerankEnabled) {
            log.debug("重排序已禁用");
            return convertToSimpleResults(candidates);
        }

        if (candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }

        log.info("开始重排序，候选数: {}, 查询: {}", candidates.size(), query);

        // 转换为候选列表
        List<RerankCandidate> candidateList = convertToCandidates(candidates);
        if (candidateList.isEmpty()) {
            return Collections.emptyList();
        }

        // Step 1: Cross-Encoder 打分
        // 这里使用简化实现，实际应该调用 Cross-Encoder 模型
        // 由于本地可能没有 Cross-Encoder 模型，提供基于关键词的轻量打分
        scoreCandidates(query, candidateList);

        // Step 2: 融合原始分数和重排分数
        fuseScores(candidateList);

        // Step 3: 按融合分数排序
        candidateList.sort((a, b) -> Double.compare(b.getFinalScore(), a.getFinalScore()));

        // Step 4: 取 Top-K
        List<RerankResult> results = new ArrayList<>();
        int count = 0;
        for (RerankCandidate c : candidateList) {
            if (count >= topK) break;
            results.add(new RerankResult(
                    c.getContent(), c.getDocumentId(), c.getChunkIndex(),
                    c.getFinalScore(), "RERANKED"));
            count++;
        }

        log.info("重排序完成，返回 {} 条结果", results.size());
        return results;
    }

    /**
     * 简化版重排序（禁用时的降级方案）
     */
    private List<RerankResult> convertToSimpleResults(List<?> candidates) {
        List<RerankResult> results = new ArrayList<>();
        for (Object c : candidates) {
            try {
                if (c instanceof ChromaService.SearchResult) {
                    ChromaService.SearchResult r = (ChromaService.SearchResult) c;
                    results.add(new RerankResult(
                            r.getContent(), r.getDocumentId(), r.getChunkIndex(),
                            r.getScore(), "VECTOR"));
                } else if (c instanceof HybridSearchService.HybridSearchResult) {
                    HybridSearchService.HybridSearchResult r = (HybridSearchService.HybridSearchResult) c;
                    results.add(new RerankResult(
                            r.getContent(), r.getDocumentId(), r.getChunkIndex(),
                            r.getScore(), r.getSource()));
                }
            } catch (Exception e) {
                log.warn("转换候选结果失败: {}", e.getMessage());
            }
        }
        return results;
    }

    /**
     * 转换为候选列表
     */
    @SuppressWarnings("unchecked")
    private List<RerankCandidate> convertToCandidates(List<?> candidates) {
        List<RerankCandidate> result = new ArrayList<>();

        for (Object c : candidates) {
            try {
                if (c instanceof ChromaService.SearchResult) {
                    ChromaService.SearchResult r = (ChromaService.SearchResult) c;
                    result.add(new RerankCandidate(
                            r.getContent(), r.getDocumentId(), r.getChunkIndex(), r.getScore()));
                } else if (c instanceof HybridSearchService.HybridSearchResult) {
                    HybridSearchService.HybridSearchResult r = (HybridSearchService.HybridSearchResult) c;
                    result.add(new RerankCandidate(
                            r.getContent(), r.getDocumentId(), r.getChunkIndex(), r.getScore()));
                }
            } catch (Exception e) {
                log.warn("跳过无效候选: {}", e.getMessage());
            }
        }

        return result;
    }

    /**
     * 对候选进行打分
     *
     * 简化实现：基于关键词匹配的打分
     *
     * 实际生产环境应该：
     * 1. 调用 Ollama 部署的 Cross-Encoder 模型
     * 2. 或调用 SiliconFlow 等云服务的 Rerank API
     *
     * @param query 用户查询
     * @param candidates 候选列表
     */
    private void scoreCandidates(String query, List<RerankCandidate> candidates) {
        // 提取查询关键词
        List<String> queryKeywords = extractKeywords(query);

        for (RerankCandidate candidate : candidates) {
            double score = calculateRelevance(query, queryKeywords, candidate.getContent());
            candidate.setRerankScore(score);
            log.debug("候选 [{}] 重排分数: {}", candidate.getChunkIndex(), score);
        }
    }

    /**
     * 计算内容与查询的相关性分数
     *
     * 基于：
     * 1. 关键词命中数量
     * 2. 关键词位置（标题优先）
     * 3. 关键词密度
     *
     * @param query 用户查询
     * @param queryKeywords 查询关键词
     * @param content 文档内容
     * @return 0-1 之间的相关性分数
     */
    private double calculateRelevance(String query, List<String> queryKeywords, String content) {
        if (content == null || queryKeywords.isEmpty()) {
            return 0.0;
        }

        content = content.toLowerCase();
        int matchCount = 0;
        int positionBonus = 0;

        for (String keyword : queryKeywords) {
            keyword = keyword.toLowerCase();

            // 统计命中次数
            int occurrences = countOccurrences(content, keyword);
            matchCount += occurrences;

            // 位置奖励：关键词出现在前面给高分
            int position = content.indexOf(keyword);
            if (position >= 0 && position < 50) {
                positionBonus += (50 - position) / 10;
            }
        }

        // 归一化分数
        double keywordScore = Math.min(1.0, matchCount / (double) queryKeywords.size());
        double positionScore = Math.min(1.0, positionBonus / (double) queryKeywords.size());

        // 综合分数：关键词权重 0.7，位置权重 0.3
        return keywordScore * 0.7 + positionScore * 0.3;
    }

    /**
     * 提取查询关键词
     */
    private List<String> extractKeywords(String query) {
        List<String> keywords = new ArrayList<>();

        // 简单分词：按空格和标点分割
        String[] words = query.split("[\\s\\p{Punct}]+");

        for (String word : words) {
            word = word.trim();
            // 过滤太短和太长的词
            if (word.length() >= 2 && word.length() <= 20) {
                // 过滤常见停用词
                if (!isStopWord(word)) {
                    keywords.add(word);
                }
            }
        }

        return keywords;
    }

    /**
     * 判断是否为停用词
     */
    private boolean isStopWord(String word) {
        Set<String> stopWords = Set.of(
                "的", "了", "和", "是", "在", "我", "有", "个", "之", "与",
                "the", "a", "an", "is", "are", "was", "were", "be", "been",
                "of", "and", "in", "to", "for", "with", "on", "at", "by",
                "如何", "怎么", "什么", "哪个", "哪里", "为什么", "怎么样"
        );
        return stopWords.contains(word.toLowerCase());
    }

    /**
     * 统计字符串中子串出现次数
     */
    private int countOccurrences(String text, String sub) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }

    /**
     * 融合原始分数和重排分数
     *
     * 公式：finalScore = vectorWeight * norm(vectorScore) + rerankWeight * norm(rerankScore)
     *
     * @param candidates 候选列表
     */
    private void fuseScores(List<RerankCandidate> candidates) {
        // 找出最大和最小的原始分数，用于归一化
        double maxOrig = 0, minOrig = Double.MAX_VALUE;
        double maxRerank = 0, minRerank = Double.MAX_VALUE;

        for (RerankCandidate c : candidates) {
            maxOrig = Math.max(maxOrig, c.getOriginalScore());
            minOrig = Math.min(minOrig, c.getOriginalScore());
            maxRerank = Math.max(maxRerank, c.getRerankScore());
            minRerank = Math.min(minRerank, c.getRerankScore());
        }

        // 避免除零
        double origRange = (maxOrig - minOrig) > 0 ? (maxOrig - minOrig) : 1;
        double rerankRange = (maxRerank - minRerank) > 0 ? (maxRerank - minRerank) : 1;

        // 权重
        double vectorWeight = 0.4;
        double rerankWeight = 0.6;

        for (RerankCandidate c : candidates) {
            // Min-Max 归一化
            double normOrig = (c.getOriginalScore() - minOrig) / origRange;
            double normRerank = (c.getRerankScore() - minRerank) / rerankRange;

            // 综合分数
            c.setFinalScore(vectorWeight * normOrig + rerankWeight * normRerank);
        }
    }

    /**
     * 检查重排序是否启用
     */
    public boolean isEnabled() {
        return rerankEnabled;
    }

    /**
     * 获取重排序模型名称
     */
    public String getModelName() {
        return modelName;
    }

    /**
     * 获取配置信息
     */
    public String getConfigInfo() {
        return String.format("重排序配置: enabled=%s, model=%s, topk=%d",
                rerankEnabled, modelName, defaultTopK);
    }
}
