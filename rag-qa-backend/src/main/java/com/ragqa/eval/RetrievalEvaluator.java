package com.ragqa.eval;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 检索层评估器
 *
 * 计算 RAG 检索阶段的 4 个核心指标（全部程序化，无 LLM 调用）：
 *
 * 1. **Hit Rate@K**    — top-K 里是否至少命中一个 expected doc
 * 2. **Recall@K**       — top-K 命中的 expected doc 占全部 expected 的比例
 * 3. **MRR@K**          — 第一个命中的 expected doc 的排名倒数平均
 * 4. **NDCG@K**         — 考虑多相关性的归一化折损累计增益
 *
 * 【2026-06-29 新增 P2-01】
 *
 * 设计参考 RAGAS (Es et al., 2023) 的 context_precision / context_recall 指标定义，
 * 但实现细节自研以便与项目 RAGService 输出格式无缝衔接。
 */
@Component
@Slf4j
public class RetrievalEvaluator {

    /**
     * 单条 Q&A 的检索评估结果
     */
    public record ItemMetrics(
            boolean hitAtK,            // top-K 是否至少命中一个
            double recallAtK,          // top-K 召回率 [0, 1]
            double mrrAtK,             // 第一个命中的倒数排名 [0, 1]
            double ndcgAtK,            // NDCG@K [0, 1]
            int rankOfFirstHit         // 第一个命中的排名（0-based；-1 表示未命中）
    ) {}

    /**
     * 整次跑批的汇总指标
     */
    public record Summary(
            double hitRateAtK,          // 平均 Hit Rate [0, 1]
            double recallAtK,           // 平均 Recall [0, 1]
            double mrrAtK,              // 平均 MRR [0, 1]
            double ndcgAtK,             // 平均 NDCG [0, 1]
            int totalItems,
            int hitCount                // 命中的条目数
    ) {}

    /**
     * 计算单条 Q&A 的检索指标
     *
     * @param expectedDocIds 期望命中的文档 UUID 列表（来自黄金数据集）
     * @param retrievedDocIds 实际 top-K 召回的文档 UUID 列表（来自 RagService）
     * @param K top-K 值
     * @return ItemMetrics
     */
    public ItemMetrics evaluateOne(List<String> expectedDocIds, List<String> retrievedDocIds, int K) {
        if (expectedDocIds == null || expectedDocIds.isEmpty()) {
            // 无期望文档：跳过该条（视作指标无意义）
            return new ItemMetrics(false, 0.0, 0.0, 0.0, -1);
        }
        if (retrievedDocIds == null || retrievedDocIds.isEmpty() || K <= 0) {
            return new ItemMetrics(false, 0.0, 0.0, 0.0, -1);
        }

        // 用 Set 做 O(1) 命中查找
        Set<String> expectedSet = new HashSet<>(expectedDocIds);
        List<String> topK = retrievedDocIds.subList(0, Math.min(K, retrievedDocIds.size()));

        // 1) Hit Rate@K
        boolean hit = topK.stream().anyMatch(expectedSet::contains);

        // 2) Recall@K
        long hitCount = topK.stream().filter(expectedSet::contains).count();
        double recall = (double) hitCount / expectedSet.size();

        // 3) MRR@K — 第一个命中的倒数排名
        int firstHitRank = -1;
        for (int i = 0; i < topK.size(); i++) {
            if (expectedSet.contains(topK.get(i))) {
                firstHitRank = i;
                break;
            }
        }
        double mrr = (firstHitRank >= 0) ? 1.0 / (firstHitRank + 1) : 0.0;

        // 4) NDCG@K
        // DCG = Σ (rel_i / log2(i+2))   其中 rel_i = 1 if expected else 0
        // IDCG = 理想情况：所有 expected 都在前面
        // NDCG = DCG / IDCG
        double dcg = 0.0;
        for (int i = 0; i < topK.size(); i++) {
            if (expectedSet.contains(topK.get(i))) {
                dcg += 1.0 / Math.log(i + 2);   // log2(i+2) = log base 2 of (rank+1)
            }
        }
        double idcg = 0.0;
        int idealCount = Math.min(expectedSet.size(), K);
        for (int i = 0; i < idealCount; i++) {
            idcg += 1.0 / Math.log(i + 2);
        }
        double ndcg = (idcg > 0) ? dcg / idcg : 0.0;

        return new ItemMetrics(hit, recall, mrr, ndcg, firstHitRank);
    }

    /**
     * 计算整次跑批的汇总指标
     *
     * @param items 所有单条 ItemMetrics
     * @return Summary
     */
    public Summary summarize(List<ItemMetrics> items) {
        if (items == null || items.isEmpty()) {
            return new Summary(0.0, 0.0, 0.0, 0.0, 0, 0);
        }

        int total = items.size();
        long hitCount = items.stream().filter(ItemMetrics::hitAtK).count();
        double avgHitRate = (double) hitCount / total;
        double avgRecall = items.stream().mapToDouble(ItemMetrics::recallAtK).average().orElse(0.0);
        double avgMrr = items.stream().mapToDouble(ItemMetrics::mrrAtK).average().orElse(0.0);
        double avgNdcg = items.stream().mapToDouble(ItemMetrics::ndcgAtK).average().orElse(0.0);

        return new Summary(avgHitRate, avgRecall, avgMrr, avgNdcg, total, (int) hitCount);
    }
}