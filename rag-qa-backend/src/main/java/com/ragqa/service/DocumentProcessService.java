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
 * 文档处理服务（异步）
 * 
 * 作用：处理文档的后台任务
 * 
 * 处理流程：
 * 1. 解析文档（使用Apache Tika提取文本）
 * 2. 文本切片（使用TextSplitter）
 * 3. 向量化（使用EmbeddingService）
 * 
 * 注意：使用@Async注解的方法会在独立线程中执行
 * 这确保了文件上传API可以快速返回，不需要等待耗时的处理
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentProcessService {

    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final TextSplitter textSplitter;
    private final EmbeddingService embeddingService;

    private final Tika tika = new Tika();

    /**
     * 异步处理文档
     * 
     * 该方法在独立线程中执行，不阻塞主请求
     * 
     * @param documentId 文档ID
     * @param filePath  文件路径
     */
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processDocumentAsync(UUID documentId, Path filePath) {
        try {
            Document document = documentRepository.findById(documentId).orElseThrow(() -> 
                new RuntimeException("文档不存在: " + documentId));
            
            // ====== 阶段1：解析文档 ======
            // 使用Apache Tika从PDF/DOCX/TXT中提取纯文本
            document.setStatus(Document.DocumentStatus.PARSING);
            document.setProgress(30);  // 30%进度
            documentRepository.save(document);
            
            // 检查文件是否存在
            if (!Files.exists(filePath)) {
                throw new RuntimeException("文件不存在: " + filePath);
            }
            
            log.info("开始解析文件: {}, 大小: {} bytes", filePath, Files.size(filePath));
            
            // 对于文本文件，直接读取；对于其他文件使用Tika
            String text;
            String fileName = filePath.getFileName().toString().toLowerCase();
            
            if (fileName.endsWith(".txt")) {
                // 文本文件直接读取，处理中文编码
                try {
                    text = Files.readString(filePath);
                } catch (Exception e) {
                    // 尝试GBK编码（常见于中文Windows）
                    try {
                        text = new String(Files.readAllBytes(filePath), "GBK");
                    } catch (Exception e2) {
                        text = new String(Files.readAllBytes(filePath), "UTF-8");
                    }
                }
            } else {
                // PDF/DOCX使用Tika
                text = tika.parseToString(filePath.toFile());
            }
            
            log.info("解析文档成功，文本长度: {}", text.length());
            
            // 如果文本为空，抛出异常
            if (text == null || text.trim().isEmpty()) {
                throw new RuntimeException("无法从文档中提取文字，该文档可能是图片扫描件或加密文档");
            }

            // ====== 阶段2：文本切片 ======
            // 将长文本切分成小块，便于检索和LLM处理
            document.setStatus(Document.DocumentStatus.CHUNKING);
            document.setProgress(50);  // 50%进度
            documentRepository.save(document);
            
            List<String> chunks = textSplitter.split(text);
            log.info("文本切片完成，切片数量: {}", chunks.size());

            // ====== 阶段3：向量化 ======
            // 将每个切片转换为向量，存入数据库
            document.setStatus(Document.DocumentStatus.EMBEDDING);
            document.setProgress(70);  // 70%进度
            documentRepository.save(document);

            // 逐个处理切片
            int successCount = 0;
            int failCount = 0;
            
            for (int i = 0; i < chunks.size(); i++) {
                String chunk = chunks.get(i);
                log.info("开始向量化第 {} 个切片", i + 1);
                
                try {
                    // 调用Embedding服务获取向量
                    float[] embedding = embeddingService.embed(chunk);
                    
                    if (embedding.length == 0) {
                        log.error("切片 {} 向量化失败，返回空向量", i + 1);
                        failCount++;
                        continue;
                    }
                    
                    // 保存切片和向量到数据库
                    DocumentChunk docChunk = new DocumentChunk();
                    docChunk.setDocumentId(documentId);
                    docChunk.setChunkIndex(i);
                    docChunk.setContent(chunk);
                    docChunk.setEmbedding(Arrays.toString(embedding));
                    documentChunkRepository.save(docChunk);
                    
                    successCount++;
                    log.info("切片 {} 向量化完成", i + 1);
                    
                } catch (Exception e) {
                    log.error("切片 {} 向量化异常: {}", i + 1, e.getMessage());
                    failCount++;
                }
                
                // 更新进度（70% - 100%）
                int embedProgress = 70 + ((i + 1) * 30 / chunks.size());
                document.setProgress(embedProgress);
                documentRepository.save(document);
            }

            // ====== 处理完成 ======
            log.info("向量化完成，成功: {}, 失败: {}", successCount, failCount);
            document.setChunkCount(successCount);
            
            // 如果全部失败，标记为失败
            if (successCount == 0) {
                document.setStatus(Document.DocumentStatus.FAILED);
                document.setErrorMessage("所有切片向量化失败");
            } else if (failCount > 0) {
                // 部分成功，标记完成但有警告
                document.setStatus(Document.DocumentStatus.COMPLETED);
                document.setErrorMessage("部分切片向量化失败，成功: " + successCount + ", 失败: " + failCount);
            } else {
                document.setStatus(Document.DocumentStatus.COMPLETED);
            }
            
            document.setProgress(100);
            document.setProcessedAt(LocalDateTime.now());
            documentRepository.save(document);
            log.info("文档处理完成，切片数量: {}", successCount);

        } catch (Exception e) {
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
