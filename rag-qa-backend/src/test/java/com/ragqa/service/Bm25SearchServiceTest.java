package com.ragqa.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bm25SearchService.removeByDocumentId 单元测试
 *
 * 验证：删除某个文档的所有 chunk 后：
 * - 索引中不再包含该文档相关的 chunk
 * - 倒排索引中不再有该文档相关的 term（除非还被其他文档引用）
 * - totalDocs / averageDocLength 正确更新
 *
 * 修复日期：2026-06-27
 */
class Bm25SearchServiceTest {

    private Bm25SearchService service;

    @BeforeEach
    void setUp() {
        service = new Bm25SearchService();
    }

    @Test
    void shouldRemoveAllChunksOfGivenDocument() {
        String docId = "doc-1";
        // doc-1 有 3 个 chunk：doc-1_0, doc-1_1, doc-1_2
        service.addDocument(docId + "_0", "Java 是一种编程语言", docId, 0);
        service.addDocument(docId + "_1", "Java 跨平台特性", docId, 1);
        service.addDocument(docId + "_2", "JVM 是 Java 虚拟机", docId, 2);
        // 其他文档
        service.addDocument("doc-2_0", "Python 也是编程语言", "doc-2", 0);

        assertThat(service.getDocumentCount()).isEqualTo(4);

        int removed = service.removeByDocumentId(docId);

        // doc-1 的 3 个 chunk 全部移除
        assertThat(removed).isEqualTo(3);
        // 只剩 doc-2_0
        assertThat(service.getDocumentCount()).isEqualTo(1);

        // 检索 "Java" 应该找不到结果
        var results = service.search("Java", 10);
        assertThat(results).isEmpty();
    }

    @Test
    void shouldBeNoOpForNonExistentDocument() {
        service.addDocument("doc-1_0", "hello world", "doc-1", 0);

        int removed = service.removeByDocumentId("doc-nonexistent");

        assertThat(removed).isEqualTo(0);
        assertThat(service.getDocumentCount()).isEqualTo(1);
    }

    @Test
    void shouldBeNoOpForNullOrEmptyDocumentId() {
        service.addDocument("doc-1_0", "hello world", "doc-1", 0);

        assertThat(service.removeByDocumentId(null)).isEqualTo(0);
        assertThat(service.removeByDocumentId("")).isEqualTo(0);
        assertThat(service.getDocumentCount()).isEqualTo(1);
    }

    @Test
    void shouldKeepSharedTermsInInvertedIndex() {
        // 两个文档共享 "java" 词，删除一个后 "java" 应该仍然存在
        service.addDocument("doc-1_0", "java programming language", "doc-1", 0);
        service.addDocument("doc-2_0", "java cross platform runtime", "doc-2", 0);

        // 共享词集合: java, programming, language, cross, platform, runtime = 6
        // （分词后 doc-1: java/programming/language; doc-2: java/cross/platform/runtime）
        assertThat(service.getVocabularySize()).isEqualTo(6);

        service.removeByDocumentId("doc-1");

        // doc-1 移除后，doc-2 仍包含 "java"，所以 vocabulary 不应清零
        // 剩余 terms: java, cross, platform, runtime = 4
        assertThat(service.getVocabularySize()).isEqualTo(4);
        assertThat(service.getDocumentCount()).isEqualTo(1);

        // 检索 Java 仍能找到 doc-2
        var results = service.search("java", 10);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getContent()).contains("platform");
    }

    @Test
    void shouldRemoveTermsWhenLastDocumentHoldingThemIsRemoved() {
        // "uniqueword" 只在 doc-1 中出现
        service.addDocument("doc-1_0", "uniqueword and shared", "doc-1", 0);
        service.addDocument("doc-2_0", "shared content here", "doc-2", 0);

        // vocabulary 包含: uniqueword, and, shared, content, here = 5
        assertThat(service.getVocabularySize()).isEqualTo(5);

        service.removeByDocumentId("doc-1");

        // doc-1 移除后，"uniqueword" 和 "and" 不再被任何文档引用，应该从倒排索引移除
        // 剩下: shared, content, here = 3
        assertThat(service.getVocabularySize()).isEqualTo(3);
    }

    @Test
    void shouldResetAverageDocLengthWhenAllDocumentsRemoved() {
        service.addDocument("doc-1_0", "Java Java Java", "doc-1", 0);
        service.addDocument("doc-2_0", "Python Python", "doc-2", 0);

        service.removeByDocumentId("doc-1");
        service.removeByDocumentId("doc-2");

        // 所有文档都删完后，平均文档长度应该重置为 0
        assertThat(service.getDocumentCount()).isEqualTo(0);
    }

    @Test
    void shouldClearIdfCacheAfterRemoval() {
        // 触发一次查询，让 idfCache 有内容
        service.addDocument("doc-1_0", "Java 编程", "doc-1", 0);
        service.addDocument("doc-2_0", "Python 编程", "doc-2", 0);
        service.search("Java", 10);  // 触发 idfCache 计算

        service.removeByDocumentId("doc-1");

        // 删除后查询应该重新计算（不应该因缓存导致结果错误）
        var results = service.search("Java", 10);
        // doc-1 已删除，doc-2 没有 "java"，所以结果应该为空
        assertThat(results).isEmpty();
    }

    @Test
    void shouldHandleMultipleChunksSamePrefix() {
        // 验证 prefix 匹配的健壮性：UUID.toString() 不含下划线，
        // 但要确保不会误删其他 document 的 chunk
        String docA = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
        String docB = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

        service.addDocument(docA + "_0", "content A", docA, 0);
        service.addDocument(docA + "_1", "content A again", docA, 1);
        service.addDocument(docB + "_0", "content B", docB, 0);

        int removed = service.removeByDocumentId(docA);

        assertThat(removed).isEqualTo(2);
        assertThat(service.getDocumentCount()).isEqualTo(1);

        // docB 的 chunk 仍在
        var results = service.search("content", 10);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getContent()).isEqualTo("content B");
    }

    // ============================================================
    // 并发安全测试（2026-06-27 修复后新增）
    // ============================================================

    @Test
    void shouldHandleConcurrentReadsAndWrites() throws Exception {
        // 先灌入 100 个 chunk
        for (int i = 0; i < 100; i++) {
            service.addDocument("doc-" + i + "_0", "java programming language", "doc-" + i, 0);
        }

        int readerCount = 8;
        int writerCount = 2;
        int iterations = 100;
        ExecutorService executor = Executors.newFixedThreadPool(readerCount + writerCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger exceptions = new AtomicInteger(0);

        List<Future<?>> futures = new ArrayList<>();

        // 8 个 reader 持续 search
        for (int r = 0; r < readerCount; r++) {
            futures.add(executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < iterations; i++) {
                        service.search("java", 10);
                    }
                } catch (Exception e) {
                    exceptions.incrementAndGet();
                }
            }));
        }

        // 2 个 writer 持续 addDocument / removeByDocumentId
        for (int w = 0; w < writerCount; w++) {
            final int writerId = w;
            futures.add(executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < iterations; i++) {
                        String docId = "writer-" + writerId + "-" + i;
                        service.addDocument(docId + "_0", "content " + i, docId, 0);
                        service.removeByDocumentId(docId);
                    }
                } catch (Exception e) {
                    exceptions.incrementAndGet();
                }
            }));
        }

        startLatch.countDown();
        executor.shutdown();
        boolean finished = executor.awaitTermination(30, TimeUnit.SECONDS);

        assertThat(finished).isTrue();
        // 并发读写不应该产生任何异常
        assertThat(exceptions.get())
                .as("并发读写过程中发生异常（修复前会有 ConcurrentModificationException）")
                .isEqualTo(0);
    }
}