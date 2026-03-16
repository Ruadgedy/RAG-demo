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
            
            String text = tika.parseToString(filePath.toFile());
            log.info("解析文档成功，文本长度: {}", text.length());

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
            for (int i = 0; i < chunks.size(); i++) {
                String chunk = chunks.get(i);
                log.info("开始向量化第 {} 个切片", i + 1);
                
                // 调用Embedding服务获取向量
                float[] embedding = embeddingService.embed(chunk);
                
                if (embedding.length == 0) {
                    log.error("向量化失败，返回空向量");
                    throw new RuntimeException("向量化返回空结果");
                }
                
                // 保存切片和向量到数据库
                DocumentChunk docChunk = new DocumentChunk();
                docChunk.setDocumentId(documentId);
                docChunk.setChunkIndex(i);
                docChunk.setContent(chunk);
                docChunk.setEmbedding(Arrays.toString(embedding));
                documentChunkRepository.save(docChunk);
                
                // 更新进度（70% - 100%）
                int embedProgress = 70 + (i * 30 / chunks.size());
                document.setProgress(embedProgress);
                documentRepository.save(document);
                
                log.info("切片 {} 向量化完成", i + 1);
            }

            // ====== 处理完成 ======
            document.setChunkCount(chunks.size());
            document.setStatus(Document.DocumentStatus.COMPLETED);
            document.setProgress(100);
            document.setProcessedAt(LocalDateTime.now());
            documentRepository.save(document);
            log.info("文档处理完成，切片数量: {}", chunks.size());

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
