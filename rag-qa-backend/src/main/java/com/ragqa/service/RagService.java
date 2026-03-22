package com.ragqa.service;

import com.ragqa.model.Document;
import com.ragqa.repository.DocumentChunkRepository;
import com.ragqa.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class RagService {

    private final DocumentChunkRepository documentChunkRepository;
    private final DocumentRepository documentRepository;
    private final ChatClient.Builder chatClientBuilder;
    private final EmbeddingService embeddingService;
    private final ChromaService chromaService;

    @Value("${chunk.size:3}")
    private int TOP_K;

    public record RetrievalResult(String content, String source, double score) {}

    public String chat(String message, UUID knowledgeBaseId) {
        log.info("RAG问答: {}", message);

        List<Document> documents = documentRepository.findByKnowledgeBaseId(knowledgeBaseId);
        
        boolean hasCompletedDocs = documents.stream()
                .anyMatch(doc -> doc.getStatus() == Document.DocumentStatus.COMPLETED);
        
        if (!hasCompletedDocs) {
            return "该知识库暂无文档，请先上传文档。";
        }

        List<RetrievalResult> retrieved = retrieve(message, knowledgeBaseId);
        
        if (retrieved.isEmpty()) {
            return "该知识库暂无文档，请先上传文档。";
        }
        
        String context = buildContext(retrieved);
        String prompt = buildPrompt(context, message);

        try {
            String response = chatClientBuilder.build()
                    .prompt(prompt)
                    .call()
                    .content();
            
            if (response == null || response.isEmpty()) {
                log.warn("LLM返回空响应，可能余额不足");
                return "AI服务余额不足，请联系管理员充值后继续使用。";
            }
            
            return response;
        } catch (Exception e) {
            log.error("LLM调用失败: {}", e.getMessage());
            
            String errorMsg = e.getMessage();
            if (errorMsg != null && (errorMsg.contains("insufficient_balance") || 
                errorMsg.contains("insufficient balance") || errorMsg.contains("1008"))) {
                return "AI服务余额不足，请联系管理员充值后继续使用。";
            }
            
            return "抱歉，AI服务暂时不可用，请稍后重试。";
        }
    }

    /**
     * 使用Chroma进行向量检索
     */
    private List<RetrievalResult> retrieve(String query, UUID knowledgeBaseId) {
        try {
            List<ChromaService.SearchResult> results = chromaService.similaritySearch(query, TOP_K);
            
            return results.stream()
                    .filter(r -> {
                        try {
                            UUID docId = UUID.fromString(r.documentId());
                            return documentRepository.findByKnowledgeBaseId(knowledgeBaseId).stream()
                                    .anyMatch(doc -> doc.getId().equals(docId) && 
                                        doc.getStatus() == Document.DocumentStatus.COMPLETED);
                        } catch (Exception e) {
                            return false;
                        }
                    })
                    .map(r -> new RetrievalResult(
                        r.content(),
                        r.documentId() + "_" + r.chunkIndex(),
                        r.score()
                    ))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Chroma检索失败，回退到数据库检索: {}", e.getMessage());
            return fallbackRetrieve(query, knowledgeBaseId);
        }
    }

    /**
     * 流式检索 - 公开方法供ChatService调用
     */
    public List<RetrievalResult> retrieveForStreaming(String query, UUID knowledgeBaseId) {
        return retrieve(query, knowledgeBaseId);
    }

    /**
     * 回退方案：从数据库检索
     */
    private List<RetrievalResult> fallbackRetrieve(String query, UUID knowledgeBaseId) {
        List<RetrievalResult> results = new ArrayList<>();
        
        float[] queryEmbedding = embeddingService.embed(query);
        
        var documents = documentRepository.findByKnowledgeBaseId(knowledgeBaseId);
        
        for (var doc : documents) {
            if (doc.getStatus() != Document.DocumentStatus.COMPLETED) {
                continue;
            }
            
            var chunks = documentChunkRepository.findByDocumentId(doc.getId());
            
            for (var chunk : chunks) {
                String embeddingStr = chunk.getEmbedding();
                float[] chunkEmbedding = parseEmbedding(embeddingStr);
                
                if (chunkEmbedding.length > 0) {
                    double similarity = cosineSimilarity(queryEmbedding, chunkEmbedding);
                    results.add(new RetrievalResult(
                        chunk.getContent(),
                        chunk.getDocumentId() + "_" + chunk.getChunkIndex(),
                        similarity
                    ));
                }
            }
        }
        
        results.sort((a, b) -> Double.compare(b.score(), a.score()));
        return results.stream().limit(TOP_K).toList();
    }

    private String buildContext(List<RetrievalResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("参考文档：\n\n");
        for (int i = 0; i < results.size(); i++) {
            RetrievalResult r = results.get(i);
            sb.append("【文档").append(i + 1).append("】\n");
            sb.append(r.content()).append("\n\n");
        }
        return sb.toString();
    }

    private String buildPrompt(String context, String question) {
        return """
            你是一个智能问答助手，请根据以下参考文档回答用户的问题。
            如果参考文档中没有相关信息，请如实告知用户。
            
            %s
            用户问题：%s
            """.formatted(context, question);
    }

    private float[] parseEmbedding(String embeddingStr) {
        if (embeddingStr == null || embeddingStr.isEmpty()) {
            return new float[0];
        }
        
        try {
            String[] parts = embeddingStr.substring(1, embeddingStr.length() - 1).split(", ");
            float[] result = new float[parts.length];
            for (int i = 0; i < parts.length; i++) {
                result[i] = Float.parseFloat(parts[i]);
            }
            return result;
        } catch (Exception e) {
            log.error("解析向量失败: {}", e.getMessage());
            return new float[0];
        }
    }

    private double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) return 0;
        
        double dotProduct = 0;
        double normA = 0;
        double normB = 0;
        
        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        
        if (normA == 0 || normB == 0) return 0;
        
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
