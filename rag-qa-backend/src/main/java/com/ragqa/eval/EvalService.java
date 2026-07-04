package com.ragqa.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragqa.dto.ChatMessage;
import com.ragqa.model.Document;
import com.ragqa.model.EvalRun;
import com.ragqa.model.EvalRunItem;
import com.ragqa.repository.DocumentRepository;
import com.ragqa.repository.EvalRunItemRepository;
import com.ragqa.repository.EvalRunRepository;
import com.ragqa.service.RagService;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 评估编排服务
 *
 * 一条主流程：
 *   1. 加载黄金数据集
 *   2. 抽样 N 条（默认全部）
 *   3. 对每条 Q&A：调 RagService 拿检索 + 答案 + 耗时
 *   4. 调 RetrievalEvaluator 打 Hit Rate / Recall / MRR / NDCG
 *   5. 调 AnswerEvaluator 用 LLM-judge 打 Faithfulness / Relevance
 *   6. 写 DB（eval_run + eval_run_item）
 *   7. 汇总输出 summary JSON
 *
 * 【2026-06-29 新增 P2-01】
 *
 * 与 RagService 的接口：
 *   - 不修改 RagService 任何代码
 *   - 通过 retrieveForStreaming() 拿 retrieval 结果（不调用 LLM）
 *   - 通过 chat() 拿最终答案（含 LLM 生成）
 *
 * 这样保证：评测跑的就是生产路径，结果反映真实质量。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EvalService {

    private final EvalDatasetLoader datasetLoader;
    private final RetrievalEvaluator retrievalEvaluator;
    private final AnswerEvaluator answerEvaluator;
    private final RagService ragService;
    private final DocumentRepository documentRepository;
    private final EvalRunRepository evalRunRepository;
    private final EvalRunItemRepository evalRunItemRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 跑批请求参数
     */
    @Data
    @Builder
    public static class RunRequest {
        private String kbId;             // 知识库 UUID（必填）
        private String dataset;          // 数据集名（必填）
        private Integer topK;            // 检索 top-K，默认 3
        private Integer sampleSize;      // 抽样条数，null 表示跑全部
    }

    /**
     * 跑批结果（同步返回，含 summary）
     */
    @Data
    @Builder
    public static class RunResult {
        private String runId;
        private String status;
        private RetrievalEvaluator.Summary retrievalSummary;
        private double avgFaithfulness;
        private double avgRelevance;
        private int totalItems;
        private int errorCount;
        private long totalDurationMs;
    }

    /**
     * 执行一次完整评估
     *
     * @param request 跑批参数
     * @return RunResult（含 summary + runId）
     */
    @Transactional
    public RunResult run(RunRequest request) throws Exception {
        if (request == null || request.getKbId() == null || request.getDataset() == null) {
            throw new IllegalArgumentException("kbId 和 dataset 必填");
        }
        int topK = request.getTopK() != null && request.getTopK() > 0 ? request.getTopK() : 3;
        UUID kbId = UUID.fromString(request.getKbId());

        // 1. 加载数据集
        EvalDataset dataset = datasetLoader.load(request.getDataset());

        // 数据集限定了 kbId 时校验一致性
        if (dataset.getKbId() != null && !dataset.getKbId().equals(request.getKbId())) {
            log.warn("数据集 {} 限定 kbId={} 与本次跑批 kbId={} 不一致",
                    dataset.getName(), dataset.getKbId(), request.getKbId());
        }

        // 2. 创建 eval_run 主记录
        EvalRun run = new EvalRun();
        run.setKbId(request.getKbId());
        run.setDatasetName(dataset.getName());
        run.setStatus("RUNNING");
        run.setStartedAt(java.time.LocalDateTime.now());
        run.setConfigJson(toJson(Map.of(
                "topK", topK,
                "sampleSize", request.getSampleSize() == null ? "all" : request.getSampleSize()
        )));
        run = evalRunRepository.saveAndFlush(run);
        final String runId = run.getId();

        log.info("=== 评估跑批开始 === runId={}, dataset={}, kbId={}, topK={}",
                runId, dataset.getName(), request.getKbId(), topK);

        long startMs = System.currentTimeMillis();
        List<EvalRunItem> itemEntities = new ArrayList<>();
        List<RetrievalEvaluator.ItemMetrics> retrievalMetricsList = new ArrayList<>();
        int errorCount = 0;

        try {
            // 3. 抽样
            List<EvalDataset.EvalItem> items = dataset.getItems();
            if (request.getSampleSize() != null && request.getSampleSize() > 0
                    && request.getSampleSize() < items.size()) {
                Collections.shuffle(items, new Random(42));   // 固定种子，便于复现
                items = items.subList(0, request.getSampleSize());
                log.info("抽样 {} 条（数据集总 {} 条）", items.size(), dataset.getItems().size());
            }

            // 4. 逐条评测
            for (int i = 0; i < items.size(); i++) {
                EvalDataset.EvalItem item = items.get(i);
                log.info("[{}/{}] 评测: {}", i + 1, items.size(), truncate(item.getQuestion(), 60));

                EvalRunItem entity = new EvalRunItem();
                entity.setRunId(runId);
                entity.setQuestion(item.getQuestion());
                entity.setGoldenAnswer(item.getGoldenAnswer());

                try {
                    evalSingle(item, kbId, topK, entity, retrievalMetricsList);
                } catch (Exception e) {
                    log.error("评测单条失败: question='{}', error={}", truncate(item.getQuestion(), 60), e.getMessage());
                    entity.setError(e.getMessage());
                    errorCount++;
                }

                itemEntities.add(entity);
            }

            // 5. 汇总
            RetrievalEvaluator.Summary retrievalSummary = retrievalEvaluator.summarize(retrievalMetricsList);
            double avgFaith = itemEntities.stream()
                    .map(EvalRunItem::getFaithfulness)
                    .filter(Objects::nonNull)
                    .filter(d -> d > 0)
                    .mapToDouble(Double::doubleValue)
                    .average().orElse(0.0);
            double avgRelevance = itemEntities.stream()
                    .map(EvalRunItem::getRelevance)
                    .filter(Objects::nonNull)
                    .filter(d -> d > 0)
                    .mapToDouble(Double::doubleValue)
                    .average().orElse(0.0);

            // 6. 写 DB
            evalRunItemRepository.saveAll(itemEntities);
            long totalDuration = System.currentTimeMillis() - startMs;
            run.setFinishedAt(java.time.LocalDateTime.now());
            run.setStatus("COMPLETED");
            run.setSummaryJson(toJson(Map.of(
                    "retrieval", retrievalSummary,
                    "avgFaithfulness", Math.round(avgFaith * 100.0) / 100.0,
                    "avgRelevance", Math.round(avgRelevance * 100.0) / 100.0,
                    "errorCount", errorCount,
                    "totalDurationMs", totalDuration
            )));
            evalRunRepository.save(run);

            log.info("=== 评估跑批完成 === runId={}, hitRate@{}={}, recall@{}={}, mrr@{}={}, ndcg@{}={}, avgFaith={}, avgRel={}, errors={}, duration={}ms",
                    runId, topK, formatPct(retrievalSummary.hitRateAtK()),
                    topK, formatPct(retrievalSummary.recallAtK()),
                    topK, formatPct(retrievalSummary.mrrAtK()),
                    topK, formatPct(retrievalSummary.ndcgAtK()),
                    formatPct(avgFaith / 5.0), formatPct(avgRelevance / 5.0),
                    errorCount, totalDuration);

            return RunResult.builder()
                    .runId(runId)
                    .status("COMPLETED")
                    .retrievalSummary(retrievalSummary)
                    .avgFaithfulness(avgFaith)
                    .avgRelevance(avgRelevance)
                    .totalItems(items.size())
                    .errorCount(errorCount)
                    .totalDurationMs(totalDuration)
                    .build();
        } catch (Exception e) {
            log.error("评估跑批异常终止: {}", e.getMessage(), e);
            run.setStatus("FAILED");
            run.setFinishedAt(java.time.LocalDateTime.now());
            run.setErrorMessage(e.getMessage());
            evalRunRepository.save(run);
            throw e;
        }
    }

    /**
     * 评测单条 Q&A：跑检索 + 生成答案 + 跑评估器
     */
    private void evalSingle(EvalDataset.EvalItem item, UUID kbId, int topK,
                            EvalRunItem entity,
                            List<RetrievalEvaluator.ItemMetrics> retrievalMetricsList) {
        // 1. 检索阶段（计时）— 不带历史，多轮对话评测时 history 为空
        long retrStart = System.currentTimeMillis();
        List<RagService.RetrievalResult> retrieved = ragService.retrieveForStreaming(
                item.getQuestion(), kbId, List.<ChatMessage>of(), 0).retrievedDocs();
        long retrievalMs = System.currentTimeMillis() - retrStart;
        entity.setRetrievalMs((int) retrievalMs);

        // 提取 UUID 列表 + 文件名列表
        List<String> retrievedIds = retrieved.stream()
                .map(r -> r.source().split("_")[0])
                .collect(Collectors.toList());
        List<String> retrievedNames = retrieved.stream()
                .map(RagService.RetrievalResult::fileName)
                .collect(Collectors.toList());
        entity.setRetrievedDocIds(toJson(retrievedIds));
        entity.setRetrievedDocNames(toJson(retrievedNames));

        // 2. 检索层评估
        List<String> expected = item.getExpectedDocIds() != null ? item.getExpectedDocIds() : List.of();
        RetrievalEvaluator.ItemMetrics retrievalM = retrievalEvaluator.evaluateOne(expected, retrievedIds, topK);
        retrievalMetricsList.add(retrievalM);
        entity.setRankOfFirstHit(retrievalM.rankOfFirstHit());

        // 3. 完整问答（含 LLM 生成，计时）
        long genStart = System.currentTimeMillis();
        String answer;
        try {
            RagService.ChatResult result = ragService.chat(
                    item.getQuestion(), kbId, List.<ChatMessage>of(), 0);
            answer = result.answer();
        } catch (Exception e) {
            log.warn("单条 LLM 生成失败: {}", e.getMessage());
            answer = "ERROR: " + e.getMessage();
            entity.setError("GEN_ERROR: " + e.getMessage());
        }
        long generationMs = System.currentTimeMillis() - genStart;
        entity.setGenerationMs((int) generationMs);
        entity.setTotalMs((int) (retrievalMs + generationMs));
        entity.setAnswer(answer);

        // 4. 答案层评估（LLM-judge）— 用 retrieved 的 context 作为评判依据
        if (answer != null && !answer.startsWith("ERROR")) {
            String context = retrieved.stream()
                    .map(r -> r.content())
                    .collect(Collectors.joining("\n\n"));
            AnswerEvaluator.Score score = answerEvaluator.evaluate(item.getQuestion(), context, answer);
            entity.setFaithfulness(score.faithfulness());
            entity.setRelevance(score.relevance());
            entity.setUnsupportedClaims(toJson(score.unsupportedClaims()));
        }
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            log.warn("JSON 序列化失败: {}", e.getMessage());
            return "{}";
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static String formatPct(double v) {
        return String.format("%.2f%%", v * 100);
    }
}