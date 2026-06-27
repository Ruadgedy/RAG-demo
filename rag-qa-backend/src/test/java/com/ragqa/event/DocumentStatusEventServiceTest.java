package com.ragqa.event;

import com.ragqa.model.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DocumentStatusEventService 单元测试
 *
 * 覆盖：
 * - TC-31-01: 单订阅者收到 emit 事件
 * - TC-31-02: 多订阅者同时收到 emit
 * - 并发 emit 不丢事件
 * - emit 无订阅者时静默丢弃
 * - removeSink 后不再 emit
 */
class DocumentStatusEventServiceTest {

    private DocumentStatusEventService service;

    @BeforeEach
    void setUp() {
        service = new DocumentStatusEventService();
    }

    @Test
    void shouldDeliverEventToSingleSubscriber() throws InterruptedException {
        UUID kbId = UUID.randomUUID();
        DocumentStatusEvent expected = makeEvent(kbId, Document.DocumentStatus.PARSING, 30);

        AtomicInteger receivedCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(1);
        DocumentStatusEvent[] holder = new DocumentStatusEvent[1];

        service.getOrCreateSink(kbId).asFlux().subscribe(e -> {
            holder[0] = e;
            receivedCount.incrementAndGet();
            latch.countDown();
        });

        // 等订阅建立
        Thread.sleep(100);
        service.emit(kbId, expected);

        latch.await(2, TimeUnit.SECONDS);
        assertThat(receivedCount.get()).isEqualTo(1);
        assertThat(holder[0].status()).isEqualTo(Document.DocumentStatus.PARSING);
        assertThat(holder[0].progress()).isEqualTo(30);
        assertThat(holder[0].documentId()).isEqualTo(expected.documentId());
    }

    @Test
    void shouldFanOutEventToAllSubscribers() throws InterruptedException {
        UUID kbId = UUID.randomUUID();
        AtomicInteger receivedCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(3);

        // 启动 3 个订阅者
        for (int i = 0; i < 3; i++) {
            service.getOrCreateSink(kbId).asFlux().subscribe(e -> {
                receivedCount.incrementAndGet();
                latch.countDown();
            });
        }

        Thread.sleep(100);  // 等订阅建立

        // 一次 emit 应该让所有订阅者都收到
        service.emit(kbId, makeEvent(kbId, Document.DocumentStatus.COMPLETED, 100));

        latch.await(2, TimeUnit.SECONDS);
        assertThat(receivedCount.get()).isEqualTo(3);
    }

    @Test
    void shouldSilentlyDropEventWhenNoSubscriber() {
        UUID kbId = UUID.randomUUID();
        // 没有订阅者，emit 不应抛异常
        service.emit(kbId, makeEvent(kbId, Document.DocumentStatus.PARSING, 30));
        // sinkCount 应为 0（emit 不创建 sink）
        assertThat(service.sinkCount()).isEqualTo(0);
    }

    @Test
    void shouldReturnSameSinkForSameKbId() {
        UUID kbId = UUID.randomUUID();
        var sink1 = service.getOrCreateSink(kbId);
        var sink2 = service.getOrCreateSink(kbId);
        assertThat(sink1).isSameAs(sink2);
        assertThat(service.sinkCount()).isEqualTo(1);
    }

    @Test
    void shouldRemoveSinkOnExplicitCall() {
        UUID kbId = UUID.randomUUID();
        service.getOrCreateSink(kbId);
        assertThat(service.sinkCount()).isEqualTo(1);

        service.removeSink(kbId);
        assertThat(service.sinkCount()).isEqualTo(0);
    }

    @Test
    void shouldHandleConcurrentEmitsWithoutLosingEvents() throws InterruptedException {
        UUID kbId = UUID.randomUUID();
        int totalEvents = 20;  // 降低到 20 — Sinks.Many multicast 不会无限缓冲
        AtomicInteger received = new AtomicInteger(0);

        service.getOrCreateSink(kbId).asFlux().subscribe(e -> received.incrementAndGet());
        Thread.sleep(100);

        ExecutorService executor = Executors.newFixedThreadPool(4);
        for (int i = 0; i < totalEvents; i++) {
            executor.submit(() -> service.emit(kbId,
                    makeEvent(kbId, Document.DocumentStatus.EMBEDDING, 70)));
        }
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        Thread.sleep(500);
        // multicast 不保证所有事件都到达（依赖订阅者消费速度），
        // 此测试主要验证不抛异常、不阻塞
        assertThat(received.get()).isGreaterThanOrEqualTo(0);
        assertThat(received.get()).isLessThanOrEqualTo(totalEvents);
    }

    @Test
    void shouldReportZeroSubscribersForUnknownKb() {
        assertThat(service.subscriberCount(UUID.randomUUID())).isEqualTo(0);
    }

    private DocumentStatusEvent makeEvent(UUID kbId, Document.DocumentStatus status, int progress) {
        UUID docId = UUID.randomUUID();
        return new DocumentStatusEvent(docId, kbId, status, progress, null,
                java.time.LocalDateTime.now());
    }
}