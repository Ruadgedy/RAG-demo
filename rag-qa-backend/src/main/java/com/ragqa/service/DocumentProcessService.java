package com.ragqa.service;

import com.ragqa.model.Document;
import com.ragqa.model.DocumentChunk;
import com.ragqa.repository.DocumentChunkRepository;
import com.ragqa.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Arrays;
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
 * 4. 存储（Storage）：同时存入MySQL和Chroma
 * 
 * 特点：
 * - 使用@Async异步执行，不阻塞主线程
 * - 每个阶段更新进度，便于前端显示
 * - 支持多种文档格式（PDF、Word、TXT等）
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

    /** Apache Tika - 文档解析库，支持PDF、Word、TXT等格式 */
    private final Tika tika = new Tika();

    /**
     * 异步处理文档
     * 
     * 这是文档处理的入口方法，由上传接口调用
     * 使用@Async注解在后台线程执行，避免阻塞HTTP请求
     * 
     * @param documentId 文档ID（数据库中的记录ID）
     * @param filePath 上传文件的存储路径
     */
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processDocumentAsync(UUID documentId, Path filePath) {
        try {
            // 从数据库获取文档记录
            Document document = documentRepository.findById(documentId).orElseThrow(() -> 
                new RuntimeException("文档不存在: " + documentId));
            
            // ========== 阶段1: 解析文档 ==========
            document.setStatus(Document.DocumentStatus.PARSING);  // 状态：解析中
            document.setProgress(30);                           // 进度：30%
            documentRepository.save(document);
            
            // 检查文件是否存在
            if (!Files.exists(filePath)) {
                throw new RuntimeException("文件不存在: " + filePath);
            }
            
            log.info("开始解析文件: {}, 大小: {} bytes", filePath, Files.size(filePath));
            
            // 根据文件类型选择解析方式
            String text;
            String fileName = filePath.getFileName().toString().toLowerCase();
            
            if (fileName.endsWith(".txt")) {
                // TXT文件：尝试多种编码（UTF-8优先，GBK备选）
                try {
                    text = Files.readString(filePath);
                } catch (Exception e) {
                    try {
                        text = new String(Files.readAllBytes(filePath), "GBK");
                    } catch (Exception e2) {
                        text = new String(Files.readAllBytes(filePath), "UTF-8");
                    }
                }
            } else {
                // 其他格式（PDF、Word等）：使用Tika解析
                text = tika.parseToString(filePath.toFile());
            }
            
            log.info("解析文档成功，文本长度: {}", text.length());
            
            // 检查是否成功提取文本
            if (text == null || text.trim().isEmpty()) {
                throw new RuntimeException("无法从文档中提取文字");
            }

            // ========== 阶段2: 文本切分 ==========
            document.setStatus(Document.DocumentStatus.CHUNKING);  // 状态：切分中
            document.setProgress(50);                               // 进度：50%
            documentRepository.save(document);
            
            // 调用TextSplitter将文本切分为小块
            List<String> chunks = textSplitter.split(text);
            log.info("文本切片完成，切片数量: {}, 策略: {}", chunks.size(), textSplitter.getChunkStrategy());

            // ========== 阶段3&4: 向量化与存储 ==========
            document.setStatus(Document.DocumentStatus.EMBEDDING);  // 状态：向量化中
            document.setProgress(70);                               // 进度：70%
            documentRepository.save(document);

            int successCount = 0;  // 成功计数的切片数
            int failCount = 0;     // 失败计数的切片数
            
            // 遍历每个切片，依次向量化并存储
            for (int i = 0; i < chunks.size(); i++) {
                String chunk = chunks.get(i);
                log.info("开始向量化第 {} 个切片", i + 1);
                
                try {
                    // 1. 调用EmbeddingService将文本转换为向量
                    float[] embedding = embeddingService.embed(chunk);
                    
                    // 检查向量化是否成功
                    if (embedding.length == 0) {
                        log.error("切片 {} 向量化失败", i + 1);
                        failCount++;
                        continue;
                    }

                    // 2. 创建切片记录，存入MySQL
                    DocumentChunk docChunk = new DocumentChunk();
                    docChunk.setDocumentId(documentId);
                    docChunk.setChunkIndex(i);                           // 切片索引
                    docChunk.setContent(chunk);                           // 切片文本
                    docChunk.setEmbedding(Arrays.toString(embedding));   // 存储向量字符串
                    documentChunkRepository.save(docChunk);

                    // 3. 同时存入Chroma向量数据库（用于快速检索）
                    chromaService.addDocument(documentId, i, chunk, embedding);
                    
                    successCount++;
                    log.info("切片 {} 向量化完成", i + 1);
                    
                } catch (Exception e) {
                    log.error("切片 {} 向量化异常: {}", i + 1, e.getMessage());
                    failCount++;
                }
                
                // 更新进度（70% -> 100%）
                int embedProgress = chunks.isEmpty() ? 100 : 70 + ((i + 1) * 30 / chunks.size());
                document.setProgress(embedProgress);
                documentRepository.save(document);
            }

            // ========== 完成 ==========
            log.info("向量化完成，成功: {}, 失败: {}", successCount, failCount);
            document.setChunkCount(successCount);  // 记录成功切片数
            
            // 根据处理结果设置最终状态
            if (successCount == 0) {
                // 全部失败
                document.setStatus(Document.DocumentStatus.FAILED);
                document.setErrorMessage("所有切片向量化失败");
            } else if (failCount > 0) {
                // 部分成功
                document.setStatus(Document.DocumentStatus.COMPLETED);
                document.setErrorMessage("部分切片向量化失败，成功: " + successCount + ", 失败: " + failCount);
            } else {
                // 全部成功
                document.setStatus(Document.DocumentStatus.COMPLETED);
            }
            
            document.setProgress(100);                              // 进度：100%
            document.setProcessedAt(LocalDateTime.now());           // 处理完成时间
            documentRepository.save(document);
            log.info("文档处理完成，切片数量: {}", successCount);

        } catch (Exception e) {
            // 异常处理：更新文档状态为失败
            log.error("文档处理失败: {}", e.getMessage(), e);
            try {
                Document document = documentRepository.findById(documentId).orElseThrow();
                document.setStatus(Document.DocumentStatus.FAILED);
                document.setErrorMessage(e.getMessage());
                documentRepository.save(document);
            } catch (Exception ex) {
                log.error("更新文档状态失败: {}", ex.getMessage());
            }
        }
    }
}
