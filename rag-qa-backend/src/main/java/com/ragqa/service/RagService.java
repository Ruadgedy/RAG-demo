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

/**
 * RAG（检索增强生成）服务
 *
 * 作用：实现基于知识库的智能问答
 *
 * RAG工作流程：
 * 1. 检索（Retrieval）：根据用户问题从知识库中查找相关文档
 * 2. 增强（Augmentation）：将检索到的文档作为上下文
 * 3. 生成（Generation）：调用LLM基于上下文生成回答
 *
 * 核心流程：
 * chat() → retrieve() → buildContext() → buildPrompt() → LLM生成
 *
 * 检索策略：
 * - 默认使用混合检索（向量 + BM25），提供更准确的检索结果
 * - 通过 HybridSearchService 统一管理检索逻辑
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RagService {

    /** 文档切片数据库仓库 */
    private final DocumentChunkRepository documentChunkRepository;
    /** 文档数据库仓库 */
    private final DocumentRepository documentRepository;
    /** Spring AI ChatClient构建器，用于调用LLM */
    private final ChatClient.Builder chatClientBuilder;
    /** 向量化服务 */
    private final EmbeddingService embeddingService;
    /** Chroma向量数据库服务 */
    private final ChromaService chromaService;
    /** 混合检索服务（向量 + BM25） */
    private final HybridSearchService hybridSearchService;

    /** 检索返回的结果数量 */
    @Value("${retrieval.topk:3}")
    private int TOP_K;

    /**
     * 检索结果记录
     * @param content 文档切片内容
     * @param source 来源标识（documentId_chunkIndex）
     * @param score 相似度得分
     */
    public record RetrievalResult(String content, String source, double score) {}

    /**
     * 处理用户问答（非流式）
     * 
     * @param message 用户问题
     * @param knowledgeBaseId 知识库ID
     * @return LLM生成的回答
     */
    public String chat(String message, UUID knowledgeBaseId) {
        log.info("RAG问答: {}", message);

        // 1. 检查知识库是否有已处理的文档
        List<Document> documents = documentRepository.findByKnowledgeBaseId(knowledgeBaseId);
        
        boolean hasCompletedDocs = documents.stream()
                .anyMatch(doc -> doc.getStatus() == Document.DocumentStatus.COMPLETED);
        
        if (!hasCompletedDocs) {
            return "该知识库暂无文档，请先上传文档。";
        }

        // 2. 检索相关文档（从Chroma向量数据库）
        List<RetrievalResult> retrieved = retrieve(message, knowledgeBaseId);
        
        if (retrieved.isEmpty()) {
            return "该知识库暂无文档，请先上传文档。";
        }
        
        // 3. 构建上下文：将检索到的文档拼接成上下文字符串
        String context = buildContext(retrieved);
        
        // 4. 构建提示词：将上下文和问题组合成完整提示
        String prompt = buildPrompt(context, message);

        // 5. 调用LLM生成回答
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
            if (errorMsg != null && (errorMsg.contains("insufficient_balance") || 
                errorMsg.contains("insufficient balance") || errorMsg.contains("1008"))) {
                return "AI服务余额不足，请联系管理员充值后继续使用。";
            }
            
            return "抱歉，AI服务暂时不可用，请稍后重试。";
        }
    }

    /**
     * 检索相关文档
     * 
     * 优先使用Chroma向量数据库检索，如果失败则回退到数据库检索
     * 
     * @param query 用户问题
     * @param knowledgeBaseId 知识库ID
     * @return 检索结果列表
     */
    private List<RetrievalResult> retrieve(String query, UUID knowledgeBaseId) {
        try {
            // 1. 从Chroma获取最相似的文档切片
            List<ChromaService.SearchResult> results = chromaService.similaritySearch(query, TOP_K);
            
            // 2. 过滤：只保留属于该知识库且状态为COMPLETED的文档
            return results.stream()
                    .filter(r -> {
                        try {
                            UUID docId = UUID.fromString(r.documentId());
                            // 检查文档是否属于该知识库且已完成处理
                            return documentRepository.findByKnowledgeBaseId(knowledgeBaseId).stream()
                                    .anyMatch(doc -> doc.getId().equals(docId) && 
                                        doc.getStatus() == Document.DocumentStatus.COMPLETED);
                        } catch (Exception e) {
                            return false;
                        }
                    })
                    .map(r -> new RetrievalResult(
                        r.content(),                              // 切片文本
                        r.documentId() + "_" + r.chunkIndex(),    // 来源标识
                        r.score()                                 // 相似度得分
                    ))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            // Chroma检索失败时，尝试从数据库检索（回退方案）
            log.warn("Chroma检索失败，回退到数据库检索: {}", e.getMessage());
            return fallbackRetrieve(query, knowledgeBaseId);
        }
    }

    /**
     * 流式检索 - 公开方法供ChatService调用
     * 与普通retrieve()逻辑相同，供流式响应使用
     */
    public List<RetrievalResult> retrieveForStreaming(String query, UUID knowledgeBaseId) {
        return retrieve(query, knowledgeBaseId);
    }

    /**
     * 回退方案：从MySQL数据库检索
     * 
     * 当Chroma不可用时，直接从数据库加载所有文档切片，
     * 在内存中计算相似度（效率较低，但作为备份方案）
     */
    private List<RetrievalResult> fallbackRetrieve(String query, UUID knowledgeBaseId) {
        List<RetrievalResult> results = new ArrayList<>();
        
        // 1. 将问题转换为向量
        float[] queryEmbedding = embeddingService.embed(query);
        
        // 2. 遍历知识库中的所有文档
        var documents = documentRepository.findByKnowledgeBaseId(knowledgeBaseId);
        
        for (var doc : documents) {
            // 只处理已完成处理的文档
            if (doc.getStatus() != Document.DocumentStatus.COMPLETED) {
                continue;
            }
            
            // 3. 获取该文档的所有切片
            var chunks = documentChunkRepository.findByDocumentId(doc.getId());
            
            // 4. 计算每个切片与问题的相似度
            for (var chunk : chunks) {
                // 从数据库读取存储的向量字符串
                String embeddingStr = chunk.getEmbedding();
                float[] chunkEmbedding = parseEmbedding(embeddingStr);
                
                if (chunkEmbedding.length > 0) {
                    // 计算余弦相似度
                    double similarity = cosineSimilarity(queryEmbedding, chunkEmbedding);
                    results.add(new RetrievalResult(
                        chunk.getContent(),
                        chunk.getDocumentId() + "_" + chunk.getChunkIndex(),
                        similarity
                    ));
                }
            }
        }
        
        // 5. 按相似度降序排序，返回TopK个结果
        results.sort((a, b) -> Double.compare(b.score(), a.score()));
        return results.stream().limit(TOP_K).toList();
    }

    /**
     * 构建上下文字符串
     * 
     * 将检索到的多个文档切片拼接成连续的上下文
     * 格式：
     * 参考文档：
     * 
     * 【文档1】
     * xxx内容xxx
     * 
     * 【文档2】
     * xxx内容xxx
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
     * 构建提示词
     * 
     * 将上下文和问题组合成LLM可理解的提示词
     * 采用结构化格式，提升回答质量
     */
    private String buildPrompt(String context, String question) {
        return """
            你是一个专业的智能问答助手，擅长从提供的文档中准确提取信息并清晰回答用户问题。

            === 参考文档 ===
            %s

            === 用户问题 ===
            %s

            === 回答要求 ===
            1. 只基于参考文档内容回答，不要编造信息
            2. 如果文档中没有相关信息，回答："抱歉，知识库中没有找到与您问题相关的内容。"
            3. 引用文档时使用【文档X】标注来源
            4. 回答结构：先给出结论，再引用证据，最后补充说明（如有）
            5. 对于复杂问题，用分点或编号的方式回答

            === 回答 ===
            """.formatted(context, question);
    }

    /**
     * 解析向量字符串
     * 
     * 数据库中向量存储格式为 "[0.1, 0.2, 0.3]"
     * 需要解析为 float[] 数组
     */
    private float[] parseEmbedding(String embeddingStr) {
        if (embeddingStr == null || embeddingStr.isEmpty()) {
            return new float[0];
        }
        
        try {
            // 去掉首尾的方括号，按逗号分隔
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
     * 余弦相似度衡量两个向量在方向上的相似程度
     * 取值范围 [-1, 1]，越接近1表示越相似
     * 
     * 公式: cos(A,B) = (A·B) / (||A|| × ||B||)
     * 
     * @param a 向量A
     * @param b 向量B
     * @return 相似度得分
     */
    private double cosineSimilarity(float[] a, float[] b) {
        // 向量维度不同，无法比较
        if (a.length != b.length) return 0;
        
        double dotProduct = 0;  // 点积：a·b
        double normA = 0;       // 向量A的模
        double normB = 0;       // 向量B的模
        
        // 计算点积和各向量的模
        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        
        // 避免除零
        if (normA == 0 || normB == 0) return 0;
        
        // 计算余弦相似度
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
