package com.ragqa.event;

import com.ragqa.model.Document;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 文档状态变更事件
 *
 * 作用：在文档处理流程（PARSING/CHUNKING/EMBEDDING/COMPLETED/FAILED）中
 *       通过 {@link DocumentStatusEventService} 实时推送给订阅者（前端 SSE 客户端）。
 *
 * 设计要点：
 * 1. 不可变 record — 多线程安全，无需同步
 * 2. 自包含 — 携带前端需要的全部状态信息（避免 SSE 客户端再去查 REST API）
 * 3. 静态工厂方法 {@link #from(Document)} 简化调用方代码
 *
 * 关联：
 * - 由 DocumentProcessService 在每个状态变更点发布
 * - 由 DocumentController.streamDocumentStatus() 订阅并通过 SSE 推送
 *
 * @see DocumentStatusEventService
 * @see com.ragqa.service.DocumentProcessService
 */
public record DocumentStatusEvent(
        UUID documentId,
        UUID knowledgeBaseId,
        Document.DocumentStatus status,
        Integer progress,
        String errorMessage,
        LocalDateTime updatedAt
) {
    /**
     * 从 Document 实体快速构造事件。
     * 注意：updatedAt 使用 now()，因为 Document 实体没有自动更新时间戳。
     */
    public static DocumentStatusEvent from(Document doc) {
        return new DocumentStatusEvent(
                doc.getId(),
                doc.getKnowledgeBaseId(),
                doc.getStatus(),
                doc.getProgress(),
                doc.getErrorMessage(),
                LocalDateTime.now()
        );
    }
}