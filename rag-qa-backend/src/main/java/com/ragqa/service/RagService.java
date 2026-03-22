package com.ragqa.service;

import com.ragqa.model.Document;
import com.ragqa.model.DocumentChunk;
import com.ragqa.repository.DocumentChunkRepository;
import com.ragqa.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * RAG检索服务
 * 
 * 作用：检索相关文档片段并调用LLM生成回答
 * 
 * RAG流程（Retrieval-Augmented Generation）：
 * 1. 接收用户问题
 * 2. 将问题转换为向量
 * 3. 在向量数据库中检索相似文档（Top-K）
 * 4. 拼接检索到的文档作为上下文
 * 5. 调用LLM生成回答
 * 
 * 检索算法：余弦相似度
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RagService {

    private final DocumentChunkRepository documentChunkRepository;
    private final DocumentRepository documentRepository;
    private final ChatClient.Builder chatClientBuilder;
    private final EmbeddingService embeddingService;

    /** 检索Top-K个最相似的文档片段 */
    private static final int TOP_K = 3;

    /**
     * 检索结果记录
     * 
     * @param content 文档片段内容
     * @param source 来源标识
     * @param score  相似度分数
     */
    public record RetrievalResult(String content, String source, double score) {}

    /**
     * RAG问答
     * 
     * @param message        用户问题
     * @param knowledgeBaseId 知识库ID
     * @return AI回答
     */
    public String chat(String message, UUID knowledgeBaseId) {
        log.info("RAG问答: {}", message);

        // 1. 获取该知识库下所有已完成的文档切片
        List<DocumentChunk> allChunks = getCompletedChunks(knowledgeBaseId);

        if (allChunks.isEmpty()) {
            return "该知识库暂无文档，请先上传文档。";
        }

        // 2. 检索Top-K相关文档
        List<RetrievalResult> retrieved = retrieve(message, allChunks);
        
        // 3. 构建上下文
        String context = buildContext(retrieved);
        
        // 4. 构建Prompt并调用LLM
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
            
            // 检查是否是余额不足错误
            String errorMsg = e.getMessage();
            if (errorMsg != null && (errorMsg.contains("insufficient_balance") || errorMsg.contains("insufficient balance") || errorMsg.contains("1008"))) {
                return "AI服务余额不足，请联系管理员充值后继续使用。";
            }
            
            return "抱歉，AI服务暂时不可用，请稍后重试。";
        }
    }

    /**
     * 获取知识库下所有已完成的文档切片
     */
    private List<DocumentChunk> getCompletedChunks(UUID knowledgeBaseId) {
        List<DocumentChunk> allChunks = new ArrayList<>();
        var documents = documentRepository.findByKnowledgeBaseId(knowledgeBaseId);
        
        for (var doc : documents) {
            // 只处理已完成的文档
            if (doc.getStatus() == Document.DocumentStatus.COMPLETED) {
                allChunks.addAll(documentChunkRepository.findByDocumentId(doc.getId()));
            }
        }
        return allChunks;
    }

    /**
     * 检索最相似的文档片段
     * 
     * 算法：余弦相似度
     * 
     * @param query 用户问题
     * @param chunks 所有文档切片
     * @return Top-K检索结果
     */
    public List<RetrievalResult> retrieve(String query, List<DocumentChunk> chunks) {
        // 1. 将问题向量化
        float[] queryEmbedding = embeddingService.embed(query);
        
        // 2. 计算每个切片与问题的相似度
        List<RetrievalResult> results = new ArrayList<>();
        
        for (DocumentChunk chunk : chunks) {
            String embeddingStr = chunk.getEmbedding();
            float[] chunkEmbedding = parseEmbedding(embeddingStr);
            
            if (chunkEmbedding.length > 0) {
                // 计算余弦相似度
                double similarity = cosineSimilarity(queryEmbedding, chunkEmbedding);
                results.add(new RetrievalResult(
                    chunk.getContent(),
                    "chunk_" + chunk.getChunkIndex(),
                    similarity
                ));
            }
        }

        // 3. 按相似度降序排序
        results.sort((a, b) -> Double.compare(b.score(), a.score()));
        
        // 4. 返回Top-K结果
        return results.stream().limit(TOP_K).toList();
    }

    /**
     * 构建上下文（将检索结果拼接成可读格式）
     */
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

    /**
     * 构建Prompt模板
     * 
     * 格式：
     * 你是一个智能问答助手，请根据以下参考文档回答用户的问题。
     * 如果参考文档中没有相关信息，请如实告知用户。
     * 
     * 参考文档：xxx
     * 
     * 用户问题：xxx
     */
    private String buildPrompt(String context, String question) {
        return """
            你是一个智能问答助手，请根据以下参考文档回答用户的问题。
            如果参考文档中没有相关信息，请如实告知用户。
            
            %s
            用户问题：%s
            """.formatted(context, question);
    }

    /**
     * 解析向量字符串
     * 向量存储格式：[0.1, 0.2, 0.3, ...]
     */
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

    /**
     * 计算余弦相似度
     * 
     * 公式：cos(A, B) = (A·B) / (||A|| × ||B||)
     * 
     * @param a 向量A
     * @param b 向量B
     * @return 相似度（-1到1，1表示完全相同）
     */
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
