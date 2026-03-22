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

@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentProcessService {

    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final TextSplitter textSplitter;
    private final EmbeddingService embeddingService;
    private final ChromaService chromaService;

    private final Tika tika = new Tika();

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processDocumentAsync(UUID documentId, Path filePath) {
        try {
            Document document = documentRepository.findById(documentId).orElseThrow(() -> 
                new RuntimeException("文档不存在: " + documentId));
            
            document.setStatus(Document.DocumentStatus.PARSING);
            document.setProgress(30);
            documentRepository.save(document);
            
            if (!Files.exists(filePath)) {
                throw new RuntimeException("文件不存在: " + filePath);
            }
            
            log.info("开始解析文件: {}, 大小: {} bytes", filePath, Files.size(filePath));
            
            String text;
            String fileName = filePath.getFileName().toString().toLowerCase();
            
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
            } else {
                text = tika.parseToString(filePath.toFile());
            }
            
            log.info("解析文档成功，文本长度: {}", text.length());
            
            if (text == null || text.trim().isEmpty()) {
                throw new RuntimeException("无法从文档中提取文字");
            }

            document.setStatus(Document.DocumentStatus.CHUNKING);
            document.setProgress(50);
            documentRepository.save(document);
            
            List<String> chunks = textSplitter.split(text);
            log.info("文本切片完成，切片数量: {}, 策略: {}", chunks.size(), textSplitter.getChunkStrategy());

            document.setStatus(Document.DocumentStatus.EMBEDDING);
            document.setProgress(70);
            documentRepository.save(document);

            int successCount = 0;
            int failCount = 0;
            
            for (int i = 0; i < chunks.size(); i++) {
                String chunk = chunks.get(i);
                log.info("开始向量化第 {} 个切片", i + 1);
                
                try {
                    float[] embedding = embeddingService.embed(chunk);
                    
                    if (embedding.length == 0) {
                        log.error("切片 {} 向量化失败", i + 1);
                        failCount++;
                        continue;
                    }

                    DocumentChunk docChunk = new DocumentChunk();
                    docChunk.setDocumentId(documentId);
                    docChunk.setChunkIndex(i);
                    docChunk.setContent(chunk);
                    docChunk.setEmbedding(Arrays.toString(embedding));
                    documentChunkRepository.save(docChunk);

                    chromaService.addDocument(documentId, i, chunk, embedding);
                    
                    successCount++;
                    log.info("切片 {} 向量化完成", i + 1);
                    
                } catch (Exception e) {
                    log.error("切片 {} 向量化异常: {}", i + 1, e.getMessage());
                    failCount++;
                }
                
                int embedProgress = 70 + ((i + 1) * 30 / chunks.size());
                document.setProgress(embedProgress);
                documentRepository.save(document);
            }

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
