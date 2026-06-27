package com.ragqa.service;

import com.ragqa.model.Document;
import com.ragqa.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;

/**
 * 文档处理恢复调度器
 *
 * 【作用】
 * 监控卡在 PROCESSING 状态的文档，处理因服务崩溃 / OOM Kill / 节点宕机
 * 导致状态永远停滞的"孤儿任务"。
 *
 * 【触发时机】
 * - 服务重启后第一次跑（启动 60 秒后）
 * - 之后每 5 分钟跑一次
 *
 * 【判定规则】
 * 文档状态 ∈ {UPLOADING, PARSING, CHUNKING, EMBEDDING} 且上传时间 > 30 分钟
 * → 视为卡死，自动置为 FAILED。
 *
 * 【修复日期 2026-06-27】
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentProcessRecoveryScheduler {

    private final DocumentRepository documentRepository;

    /** 卡死判定阈值：超过此时间仍处于中间态，视为卡死（分钟） */
    @Value("${document.recovery.timeout-minutes:30}")
    private int timeoutMinutes;

    /** 调度间隔：每多少毫秒执行一次（默认 5 分钟） */
    @Value("${document.recovery.interval-ms:300000}")
    private long intervalMs;

    /**
     * 处理中状态集合
     */
    private static final EnumSet<Document.DocumentStatus> PROCESSING_STATES = EnumSet.of(
            Document.DocumentStatus.UPLOADING,
            Document.DocumentStatus.PARSING,
            Document.DocumentStatus.CHUNKING,
            Document.DocumentStatus.EMBEDDING
    );

    /**
     * 定时清理卡死的文档
     *
     * fixedDelay = intervalMs：上次执行完成后间隔 intervalMs 毫秒再执行
     * initialDelay = 60000：服务启动后 60 秒才第一次执行（避免启动期与上传接口竞争）
     */
    @Scheduled(fixedDelayString = "${document.recovery.interval-ms:300000}", initialDelay = 60000)
    @Transactional
    public void recoverStuckDocuments() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(timeoutMinutes);
        List<Document> stuckDocs = documentRepository.findStuckDocuments(PROCESSING_STATES, threshold);

        if (stuckDocs.isEmpty()) {
            return;
        }

        log.warn("发现 {} 个卡死的文档（处理中状态超过 {} 分钟），自动置为 FAILED",
                stuckDocs.size(), timeoutMinutes);

        for (Document doc : stuckDocs) {
            doc.setStatus(Document.DocumentStatus.FAILED);
            doc.setErrorMessage(String.format(
                    "文档处理超时（卡在 %s 状态超过 %d 分钟），可能因服务重启/OOM 终止。",
                    doc.getStatus(), timeoutMinutes));
            documentRepository.save(doc);
            log.warn("卡死文档已标记 FAILED: id={}, fileName={}, 原状态={}",
                    doc.getId(), doc.getFileName(), doc.getStatus());
        }
    }
}