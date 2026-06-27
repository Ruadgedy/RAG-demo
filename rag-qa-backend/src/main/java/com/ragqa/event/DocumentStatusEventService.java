package com.ragqa.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Sinks;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 文档状态事件总线
 *
 * 作用：管理每个知识库的文档状态事件流，支持发布 / 订阅。
 *
 * 架构选型：
 * - 使用 Reactor {@link Sinks.Many#multicast()} 而非 ApplicationEventPublisher 的原因：
 *   1. 天然适配 Spring MVC 的 Flux&lt;String&gt; SSE 端点（与 ChatController.streamChat 一致）
 *   2. 内置线程安全与 backpressure 处理
 *   3. multicast 支持多订阅者（同一 KB 多用户/多标签页订阅）
 *   4. onBackpressureBuffer 允许短暂订阅者处理慢导致的事件堆积
 *
 * 生命周期：
 * - Sink 在第一次订阅时懒创建（computeIfAbsent）
 * - 当前实现：KB 删除时不主动清理 sink（KB 删除后无订阅者会自动变孤儿，内存占用极小）
 * - 未来优化：KnowledgeBaseService.delete() 中调用 {@link #removeSink(UUID)} 主动清理
 *
 * 容量规划：
 * - bufferSize=100：每个 KB 最多缓存 100 条事件，超过会丢弃最老的
 * - autoCancel=false：发布者完成时不自动取消订阅（允许后续订阅者）
 *
 * 线程安全：
 * - ConcurrentMap 保证 sinks 容器的并发安全
 * - Sinks.Many 的 tryEmitNext 是线程安全的
 *
 * @see DocumentStatusEvent
 */
@Service
@Slf4j
public class DocumentStatusEventService {

    /** 每个知识库对应一个 Sinks.Many 实例 */
    private final ConcurrentMap<UUID, Sinks.Many<DocumentStatusEvent>> sinks = new ConcurrentHashMap<>();

    /** 单 sink 缓冲区大小（超过则丢弃最老的事件） */
    private static final int BUFFER_SIZE = 100;

    /**
     * 获取或创建指定知识库的事件 sink。
     * 多次调用返回同一实例（multicast 语义）。
     *
     * @param kbId 知识库 ID
     * @return 该知识库的事件流 sink
     */
    public Sinks.Many<DocumentStatusEvent> getOrCreateSink(UUID kbId) {
        return sinks.computeIfAbsent(kbId, k -> {
            log.debug("Creating new document status sink for kbId={}", k);
            return Sinks.many()
                    .multicast()
                    .onBackpressureBuffer(BUFFER_SIZE, false);
        });
    }

    /**
     * 发布一个文档状态变更事件到指定知识库。
     * 若该 KB 尚无任何订阅者，事件被静默丢弃（multicast 语义）。
     *
     * @param kbId  知识库 ID
     * @param event 状态变更事件
     */
    public void emit(UUID kbId, DocumentStatusEvent event) {
        Sinks.Many<DocumentStatusEvent> sink = sinks.get(kbId);
        if (sink == null) {
            // 无订阅者，事件被丢弃（这是 multicast 的正常行为）
            log.trace("No subscriber for kbId={}, dropping event docId={}", kbId, event.documentId());
            return;
        }
        Sinks.EmitResult result = sink.tryEmitNext(event);
        if (result.isFailure()) {
            // FAIL_TERMINATED / FAIL_OVERFLOW / FAIL_NON_SERIALIZED 等
            log.warn("Failed to emit doc status event for kbId={}, docId={}, reason={}",
                    kbId, event.documentId(), result);
        }
    }

    /**
     * 获取当前订阅者数量（用于监控 / 调试）。
     *
     * @param kbId 知识库 ID
     * @return 当前订阅者数，0 表示无 sink 或无订阅者
     */
    public int subscriberCount(UUID kbId) {
        Sinks.Many<DocumentStatusEvent> sink = sinks.get(kbId);
        return sink == null ? 0 : sink.currentSubscriberCount();
    }

    /**
     * 主动清理指定知识库的 sink（当前未使用，预留给 KB 删除场景）。
     * 未来由 KnowledgeBaseService.delete() 调用，避免长时间运行累积孤儿 sink。
     *
     * @param kbId 知识库 ID
     */
    public void removeSink(UUID kbId) {
        Sinks.Many<DocumentStatusEvent> sink = sinks.remove(kbId);
        if (sink != null) {
            sink.tryEmitComplete();
            log.debug("Removed document status sink for kbId={}", kbId);
        }
    }

    /**
     * 当前已注册的 sink 数量（用于监控 / 测试）。
     */
    public int sinkCount() {
        return sinks.size();
    }
}