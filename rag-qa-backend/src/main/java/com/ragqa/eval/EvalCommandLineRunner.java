package com.ragqa.eval;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 评估 CLI 入口
 *
 * 启动方式：
 *   mvn spring-boot:run \
 *     -Dspring-boot.run.arguments=--eval.kbId=<UUID> \
 *                         --eval.dataset=golden-default \
 *                         --eval.topK=3 \
 *                         --eval.sampleSize=20
 *
 * 行为：
 *   - 解析 --eval.* 命令行参数
 *   - 调用 EvalService.run() 跑批
 *   - 把 summary 打到日志
 *   - 调用 System.exit(0) 退出（成功）/ System.exit(1)（失败）
 *
 * 设计取舍：
 *   - 仅当传了 --eval.kbId 才生效（生产启动不会触发）
 *   - 同步跑（等结果出来再退出），适合 CI 集成
 *   - 异步版本可后续加（@Async + 进度轮询）
 *
 * 【2026-06-29 新增 P2-01】
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EvalCommandLineRunner implements ApplicationRunner {

    private final EvalService evalService;

    @Override
    public void run(ApplicationArguments args) {
        String kbId = args.getOptionValues("eval.kbId") != null
                ? args.getOptionValues("eval.kbId").get(0) : null;
        if (kbId == null) {
            // 没传 --eval.kbId → 不触发评测，正常启动应用
            return;
        }

        String dataset = args.getOptionValues("eval.dataset") != null
                ? args.getOptionValues("eval.dataset").get(0) : "golden-default";
        Integer topK = args.getOptionValues("eval.topK") != null
                ? Integer.parseInt(args.getOptionValues("eval.topK").get(0)) : 3;
        Integer sampleSize = args.getOptionValues("eval.sampleSize") != null
                ? Integer.parseInt(args.getOptionValues("eval.sampleSize").get(0)) : null;

        log.info("=== 触发评估 CLI === kbId={}, dataset={}, topK={}, sampleSize={}",
                kbId, dataset, topK, sampleSize);

        try {
            EvalService.RunResult result = evalService.run(EvalService.RunRequest.builder()
                    .kbId(kbId).dataset(dataset).topK(topK).sampleSize(sampleSize).build());

            log.info("=== 评估完成 === runId={}, status={}, hitRate@{}={}, recall@{}={}, mrr={}, avgFaith={}/5, avgRel={}/5, errors={}, duration={}ms",
                    result.getRunId(), result.getStatus(),
                    topK, String.format("%.2f%%", result.getRetrievalSummary().hitRateAtK() * 100),
                    topK, String.format("%.2f%%", result.getRetrievalSummary().recallAtK() * 100),
                    String.format("%.4f", result.getRetrievalSummary().mrrAtK()),
                    String.format("%.2f", result.getAvgFaithfulness()),
                    String.format("%.2f", result.getAvgRelevance()),
                    result.getErrorCount(),
                    result.getTotalDurationMs());

            System.exit(0);
        } catch (Exception e) {
            log.error("CLI 评估失败", e);
            System.exit(1);
        }
    }
}