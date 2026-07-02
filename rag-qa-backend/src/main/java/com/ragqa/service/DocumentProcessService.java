package com.ragqa.service;

import com.ragqa.event.DocumentStatusEvent;
import com.ragqa.event.DocumentStatusEventService;
import com.ragqa.model.Document;
import com.ragqa.model.DocumentChunk;
import com.ragqa.repository.DocumentChunkRepository;
import com.ragqa.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.sax.WriteOutContentHandler;
import com.ragqa.config.AsyncConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.xml.sax.ContentHandler;

import java.io.InputStream;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * 文档处理服务
 *
 * 作用：异步处理上传的文档，完成解析、切分、向量化存储的完整流程
 *
 * 处理流程（4个阶段）：
 * 1. 解析（Parsing）：提取文档中的文本内容
 * 2. 切分（Chunking）：将长文本切分为小块
 * 3. 向量化（Embedding）：将文本块转换为向量
 * 4. 存储（Storage）：同时存入MySQL、Chroma和BM25索引
 *
 * 特点：
 * - 使用@Async异步执行，不阻塞主线程
 * - 每个阶段更新进度，便于前端显示
 * - 支持多种文档格式（PDF、Word、TXT等）
 * - 同时维护向量检索（Chroma）和关键词检索（BM25）索引
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentProcessService {

    /** 文档数据库仓库 */
    private final DocumentRepository documentRepository;
    /** 文档切片数据库仓库 */
    private final DocumentChunkRepository documentChunkRepository;
    /** 文本分块服务 */
    private final TextSplitter textSplitter;
    /** 向量化服务 */
    private final EmbeddingService embeddingService;
    /** Chroma向量数据库服务 */
    private final ChromaService chromaService;
    /** BM25关键词检索服务 */
    private final Bm25SearchService bm25Service;
    /** OCR 文字识别服务 */
    private final OcrService ocrService;
    /** 表格抽取服务 */
    private final TableExtractorService tableExtractor;
    /** 【2026-06-27 增量】文档状态事件服务 — 用于 SSE 实时推送 */
    private final DocumentStatusEventService eventService;

    /** Apache Tika - 文档解析库，支持PDF、Word、TXT等格式 */
    private final Tika tika = new Tika();

    /**
     * 【2026-06-29 增量 P1-05】向量化批次大小
     * 控制每批处理的 chunk 数量，平衡内存占用和处理速度。
     * 800 字符 × 50 个 chunk ≈ 40KB 文本，预估 embedding 批次约 2-5 秒。
     */
    @Value("${document.process.batch-size:50}")
    private int embeddingBatchSize;

    /**
     * 【2026-06-29 增量 P1-05】临时文本文件目录
     * 用于流式解析时存储中间结果，避免 200MB 文本全量在内存中。
     */
    @Value("${document.process.temp-dir:./temp}")
    private String tempDir;

    /**
     * 保存并发布状态变更事件。
     * 把 DB 写入 + SSE 推送封装成原子操作（虽然两个操作不是事务性的，
     * 但 SSE 是 best-effort 推送，丢一两条不影响前端正确性 — 前端有轮询降级兜底）。
     */
    private void saveAndEmit(Document doc) {
        documentRepository.save(doc);
        try {
            eventService.emit(doc.getKnowledgeBaseId(), DocumentStatusEvent.from(doc));
        } catch (Exception e) {
            // SSE 推送失败不影响主流程，仅记录日志
            log.warn("Failed to emit doc status event for docId={}: {}", doc.getId(), e.getMessage());
        }
    }

    /**
     * 异步处理文档
     *
     * 【2026-06-29 P1-05 升级】支持 200MB 大文件流式处理：
     * 1. Tika 流式解析：分块输出文本到临时文件，不占用堆内存
     * 2. 分批向量化：每批 50 个 chunks，处理完释放内存后再处理下一批
     *
     * @param documentId 文档ID（数据库中的记录ID）
     * @param filePath 上传文件的存储路径
     */
    @Async(AsyncConfig.DOCUMENT_PROCESS_EXECUTOR)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processDocumentAsync(UUID documentId, Path filePath) {
        Path tempTextFile = null;
        try {
            // 从数据库获取文档记录
            Document document = documentRepository.findById(documentId).orElseThrow(() ->
                new RuntimeException("文档不存在: " + documentId));

            // ========== 阶段1: 流式解析文档 ==========
            document.setStatus(Document.DocumentStatus.PARSING);
            document.setProgress(10);
            saveAndEmit(document);

            if (!Files.exists(filePath)) {
                throw new RuntimeException("文件不存在: " + filePath);
            }

            long fileSize = Files.size(filePath);
            log.info("开始解析文件: {}, 大小: {} bytes", filePath, fileSize);

            String fileName = filePath.getFileName().toString().toLowerCase();

            // 【P1-05 升级】流式解析：根据文件大小决定策略
            String text;
            if (fileSize > 50 * 1024 * 1024) {
                // 大文件（>50MB）：流式解析到临时文件
                log.info("大文件检测（{}MB），启用流式解析", fileSize / 1024 / 1024);
                tempTextFile = streamParseToTempFile(filePath, documentId);
                text = Files.readString(tempTextFile);
            } else {
                // 小文件：直接解析到内存
                text = parseDocument(filePath, fileName);
            }

            log.info("解析文档成功，文本长度: {}", text.length());

            // 检查是否成功提取文本
            if (text == null || text.trim().isEmpty()) {
                throw new RuntimeException("无法从文档中提取文字");
            }

            // ========== 阶段2: 文本切分（分批） ==========
            document.setStatus(Document.DocumentStatus.CHUNKING);
            document.setProgress(40);
            saveAndEmit(document);

            // 将文本写入临时文件（如果还没写过），支持大文本切分
            Path textTempFile = tempTextFile != null ? tempTextFile :
                writeTextToTempFile(text, documentId);

            // 分批切分 + 向量化（流式处理，不需要一次性把所有 chunks 加载到内存）
            int totalChunks = countChunks(text);
            int processedChunks = 0;
            int successCount = 0;
            int failCount = 0;

            log.info("文本切片完成，预计切片数量: {}, 策略: {}, 批次大小: {}",
                    totalChunks, textSplitter.getChunkStrategy(), embeddingBatchSize);

            // ========== 阶段3&4: 分批向量化与存储 ==========
            document.setStatus(Document.DocumentStatus.EMBEDDING);
            saveAndEmit(document);

            // 分批读取 chunks 并处理
            List<String> batch = new ArrayList<>(embeddingBatchSize);
            int chunkIndex = 0;
            Iterator<String> chunkIterator = textSplitter.splitIteratively(text);

            while (chunkIterator.hasNext()) {
                batch.add(chunkIterator.next());

                if (batch.size() >= embeddingBatchSize) {
                    // 处理这一批
                    var result = processBatch(documentId, batch, chunkIndex, document);
                    successCount += result.successCount;
                    failCount += result.failCount;
                    chunkIndex += batch.size();
                    processedChunks += batch.size();

                    // 更新进度（40% -> 90%）
                    int embedProgress = totalChunks > 0 ? 40 + (processedChunks * 50 / totalChunks) : 90;
                    document.setProgress(embedProgress);
                    saveAndEmit(document);

                    // 清空批次，释放内存
                    batch.clear();
                    log.info("批次处理完成，已处理 {}/{} 个切片", processedChunks, totalChunks);
                }
            }

            // 处理剩余的批次
            if (!batch.isEmpty()) {
                var result = processBatch(documentId, batch, chunkIndex, document);
                successCount += result.successCount;
                failCount += result.failCount;
                processedChunks += batch.size();
            }

            // 清理临时文件
            cleanupTempFile(textTempFile);

            // ========== 完成 ==========
            log.info("向量化完成，成功: {}, 失败: {}", successCount, failCount);
            document.setChunkCount(successCount);

            if (successCount == 0) {
                document.setStatus(Document.DocumentStatus.FAILED);
                document.setErrorMessage("所有切片向量化失败");
            } else if (failCount > 0) {
                document.setStatus(Document.DocumentStatus.COMPLETED);
                document.setErrorMessage("部分切片向量化失败，成功: " + successCount + ", 失败: " + failCount);
            } else {
                document.setStatus(Document.DocumentStatus.COMPLETED);
            }

            document.setProgress(100);
            document.setProcessedAt(LocalDateTime.now());
            saveAndEmit(document);
            log.info("文档处理完成，切片数量: {}", successCount);

        } catch (Exception e) {
            log.error("文档处理失败: {}", e.getMessage(), e);
            try {
                Document document = documentRepository.findById(documentId).orElseThrow();
                document.setStatus(Document.DocumentStatus.FAILED);
                document.setErrorMessage(e.getMessage());
                saveAndEmit(document);
            } catch (Exception ex) {
                log.error("更新文档状态失败: {}", ex.getMessage());
            }
        } finally {
            // 确保清理临时文件
            if (tempTextFile != null) {
                cleanupTempFile(tempTextFile);
            }
        }
    }

    /**
     * 【2026-06-29 P1-05】流式解析文档到临时文件
     *
     * 使用 Tika 直接解析文件到临时文件，避免大文本 OOM。
     * Tika 的 WriteOutContentHandler 内部会处理大文件分块。
     */
    private Path streamParseToTempFile(Path filePath, UUID documentId) throws Exception {
        Path tempDirPath = Paths.get(tempDir).toAbsolutePath().normalize();
        Files.createDirectories(tempDirPath);

        Path tempFile = Files.createTempFile(
            tempDirPath,
            "parse_" + documentId + "_",
            ".txt"
        );

        log.info("流式解析到临时文件: {}", tempFile);

        try {
            // 直接解析文件到 StringWriter
            StringWriter writer = new StringWriter();
            AutoDetectParser parser = new AutoDetectParser();
            Metadata metadata = new Metadata();

            // 使用 FileInputStream 解析文件
            WriteOutContentHandler writeOutHandler = new WriteOutContentHandler(writer, 200 * 1024 * 1024);  // 200MB
            org.apache.tika.sax.BodyContentHandler contentHandler = new org.apache.tika.sax.BodyContentHandler(writeOutHandler);

            // 使用 FileInputStream
            try (java.io.FileInputStream fis = new java.io.FileInputStream(filePath.toFile())) {
                parser.parse(fis, contentHandler, metadata);
            }

            String content = writer.toString();
            log.info("解析完成，文本长度: {} chars", content.length());

            // 写入临时文件
            Files.writeString(tempFile, content);
            return tempFile;

        } catch (Exception e) {
            log.error("流式解析失败: {}", e.getMessage());
            // 写入错误信息到临时文件
            Files.writeString(tempFile, "解析失败: " + e.getMessage());
            return tempFile;
        }
    }

    /**
     * 【2026-06-29 P1-05】解析文档（非大文件路径）
     */
    private String parseDocument(Path filePath, String fileName) throws Exception {
        String text;

        if (fileName.endsWith(".txt")) {
            try {
                text = Files.readString(filePath);
            } catch (Exception e) {
                try {
                    text = new String(Files.readAllBytes(filePath), "GBK");
                } catch (Exception e2) {
                    text = new String(Files.readAllBytes(filePath), "UTF-8");
                }
            }
        } else if (fileName.endsWith(".pdf")) {
            text = tika.parseToString(filePath.toFile());

            if (text.trim().length() < 50 && ocrService.isAvailable()) {
                log.info("PDF 文字层内容过少（{} 字符），启用 OCR 识别", text.trim().length());
                String ocrText = ocrService.extractTextFromPdf(filePath);
                if (ocrText != null && !ocrText.isEmpty()) {
                    text = text.trim() + "\n\n--- OCR 识别内容 ---\n\n" + ocrText;
                }
            }
        } else if (fileName.endsWith(".docx")) {
            text = tika.parseToString(filePath.toFile());

            List<TableExtractorService.TableInfo> tables = tableExtractor.extractTablesFromWord(filePath);
            if (!tables.isEmpty()) {
                log.info("从 Word 文档提取到 {} 个表格", tables.size());
                StringBuilder tableSection = new StringBuilder("\n\n--- 文档中的表格 ---\n\n");
                for (TableExtractorService.TableInfo table : tables) {
                    tableSection.append("【表格 ").append(table.getTableIndex() + 1)
                            .append(" (共").append(table.getRowCount())
                            .append("行x").append(table.getColCount()).append("列)】\n");
                    tableSection.append(table.getMarkdownTable()).append("\n");
                }
                text = text + tableSection.toString();
            }
        } else {
            text = tika.parseToString(filePath.toFile());
        }

        return text;
    }

    /**
     * 【2026-06-29 P1-05】将文本写入临时文件（支持大文本）
     */
    private Path writeTextToTempFile(String text, UUID documentId) throws Exception {
        Path tempDirPath = Paths.get(tempDir).toAbsolutePath().normalize();
        Files.createDirectories(tempDirPath);  // 【修复】先创建临时目录

        Path tempFile = Files.createTempFile(
            tempDirPath,
            "text_" + documentId + "_",
            ".txt"
        );
        Files.writeString(tempFile, text);
        return tempFile;
    }

    /**
     * 【2026-06-29 P1-05】估算 chunks 数量（不实际切分）
     */
    private int countChunks(String text) {
        // 简单估算：文本长度 / 平均 chunk 大小
        int avgChunkSize = textSplitter.getChunkSize();
        return Math.max(1, (text.length() + avgChunkSize - 1) / avgChunkSize);
    }

    /**
     * 【2026-06-29 P1-05】处理一批 chunks
     */
    private record BatchResult(int successCount, int failCount) {}

    private BatchResult processBatch(UUID documentId, List<String> chunks,
                                     int startIndex, Document document) {
        int successCount = 0;
        int failCount = 0;

        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            int globalIndex = startIndex + i;

            try {
                float[] embedding = embeddingService.embed(chunk);

                if (embedding.length == 0) {
                    log.error("切片 {} 向量化失败（空向量）", globalIndex + 1);
                    failCount++;
                    continue;
                }

                // 存入 MySQL
                DocumentChunk docChunk = new DocumentChunk();
                docChunk.setDocumentId(documentId);
                docChunk.setChunkIndex(globalIndex);
                docChunk.setContent(chunk);
                docChunk.setEmbedding(Arrays.toString(embedding));
                documentChunkRepository.save(docChunk);

                // 存入 Chroma（携带 knowledgeBaseId，供查询期按知识库过滤）
                chromaService.addDocument(documentId, document.getKnowledgeBaseId(), globalIndex, chunk, embedding);

                // 存入 BM25
                String chunkId = documentId.toString() + "_" + globalIndex;
                bm25Service.addDocument(chunkId, chunk, documentId.toString(), globalIndex);

                successCount++;

            } catch (Exception e) {
                log.error("切片 {} 向量化异常: {}", globalIndex + 1, e.getMessage());
                failCount++;
            }
        }

        return new BatchResult(successCount, failCount);
    }

    /**
     * 【2026-06-29 P1-05】清理临时文件
     */
    private void cleanupTempFile(Path tempFile) {
        if (tempFile != null && Files.exists(tempFile)) {
            try {
                Files.deleteIfExists(tempFile);
                log.debug("已清理临时文件: {}", tempFile);
            } catch (Exception e) {
                log.warn("清理临时文件失败: {}, err={}", tempFile, e.getMessage());
            }
        }
    }
}
