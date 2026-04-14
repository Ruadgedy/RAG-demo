package com.ragqa.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Chroma向量数据库服务
 *
 * 作用：管理文档向量的存储和检索
 *
 * 实现说明：
 * - 使用HttpURLConnection直接调用Chroma REST API，绕过ChromaVectorStore
 * - 这样可以完全控制embedding的生成过程，使用本地Ollama服务
 * - ChromaVectorStore会自动调用EmbeddingModel生成embedding，
 *   但我们已经有EmbeddingService生成的embedding，不需要重复调用
 */
@Service
@Slf4j
public class ChromaService {

    /** JSON解析器 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 向量化服务 - 使用Ollama生成embedding */
    private final EmbeddingService embeddingService;

    /** Chroma服务地址 */
    @Value("${spring.ai.vectorstore.chroma.url:http://localhost:8000}")
    private String chromaUrl;

    /** Chroma集合ID (UUID) */
    @Value("${spring.ai.vectorstore.chroma.collection-id:f1218ee8-1d3a-4e10-bc99-1db28817f896}")
    private String collectionId;

    /** Chroma Tenant名称 */
    @Value("${spring.ai.vectorstore.chroma.tenant-name:SpringAiTenant}")
    private String tenantName;

    /** Chroma Database名称 */
    @Value("${spring.ai.vectorstore.chroma.database-name:SpringAiDatabase}")
    private String databaseName;

    /** Spring AI的VectorStore接口（保留但不使用，用于依赖注入） */
    private final VectorStore vectorStore;

    /** 检索时返回的最相似结果数量，默认3个 */
    @Getter
    @Value("${retrieval.topk:3}")
    private int defaultTopK;

    /**
     * 构造方法
     * @param vectorStore Spring AI的VectorStore接口（保留但不使用）
     * @param embeddingService Ollama向量化服务
     */
    public ChromaService(VectorStore vectorStore, EmbeddingService embeddingService) {
        this.vectorStore = vectorStore;
        this.embeddingService = embeddingService;
    }

    /**
     * 发送HTTP POST请求到Chroma API
     */
    private String postToChroma(String endpoint, String jsonBody) throws IOException {
        URL url = new URL(chromaUrl + endpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(60000);

        try (OutputStream os = conn.getOutputStream()) {
            byte[] input = jsonBody.getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }

        int responseCode = conn.getResponseCode();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(
                        responseCode >= 400 ? conn.getErrorStream() : conn.getInputStream(),
                        StandardCharsets.UTF_8))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                response.append(line);
            }
            if (responseCode >= 400) {
                throw new IOException("Chroma API error: " + responseCode + " - " + response);
            }
            return response.toString();
        } finally {
            conn.disconnect();
        }
    }

    /**
     * 添加文档切片到向量数据库
     * 
     * 使用Chroma REST API直接添加，带有预计算的embedding
     */
    public void addDocument(UUID documentId, int chunkIndex, String content, float[] embedding) {
        String docId = documentId.toString() + "_" + chunkIndex;

        try {
            String endpoint = "/api/v2/tenants/" + tenantName + "/databases/" + databaseName + "/collections/" + collectionId + "/add";

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("documentId", documentId.toString());
            metadata.put("chunkIndex", chunkIndex);

            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("ids", List.of(docId));
            requestBody.put("embeddings", List.of(embedding));
            requestBody.put("metadatas", List.of(metadata));
            requestBody.put("documents", List.of(content));

            String jsonBody = objectMapper.writeValueAsString(requestBody);
            String response = postToChroma(endpoint, jsonBody);
            
            log.debug("添加向量到Chroma成功: docId={}, chunkIndex={}", documentId, chunkIndex);

        } catch (Exception e) {
            log.error("添加向量到Chroma异常: docId={}, error={}", docId, e.getMessage());
            throw new RuntimeException("添加向量到Chroma失败: " + e.getMessage(), e);
        }
    }

    /**
     * 相似度检索
     * 
     * 根据用户问题，从向量数据库中检索最相似的文档切片
     * 使用EmbeddingService生成查询向量，然后调用Chroma API进行检索
     */
    public List<SearchResult> similaritySearch(String query, int topK) {
        try {
            float[] queryEmbedding = embeddingService.embed(query);

            if (queryEmbedding.length == 0) {
                log.error("查询向量化失败，返回空结果");
                return Collections.emptyList();
            }

            String endpoint = "/api/v2/tenants/" + tenantName + "/databases/" + databaseName + "/collections/" + collectionId + "/query";

            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("query_embeddings", List.of(queryEmbedding));
            requestBody.put("n_results", topK);
            requestBody.put("include", List.of("documents", "metadatas", "distances"));

            String jsonBody = objectMapper.writeValueAsString(requestBody);
            String response = postToChroma(endpoint, jsonBody);

            JsonNode root = objectMapper.readTree(response);
            List<SearchResult> results = new ArrayList<>();

            if (root.has("ids") && root.get("ids").isArray() && root.get("ids").size() > 0) {
                JsonNode ids = root.get("ids").get(0);
                JsonNode documents = root.get("documents").get(0);
                JsonNode metadatas = root.get("metadatas").get(0);
                JsonNode distances = root.get("distances").get(0);

                for (int i = 0; i < ids.size(); i++) {
                    String document = documents.get(i).asText();
                    String documentId = metadatas.get(i).has("documentId")
                        ? metadatas.get(i).get("documentId").asText() : "";
                    String chunkIndex = metadatas.get(i).has("chunkIndex")
                        ? metadatas.get(i).get("chunkIndex").asText() : "0";
                    double distance = distances.get(i).asDouble();
                    double score = 1.0 / (1.0 + distance);

                    results.add(new SearchResult(document, documentId, chunkIndex, score));
                }
            }

            return results;

        } catch (Exception e) {
            log.error("Chroma检索异常: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 删除文档的所有向量
     */
    public void deleteByDocumentId(UUID documentId) {
        try {
            String getEndpoint = "/api/v2/tenants/" + tenantName + "/databases/" + databaseName + "/collections/" + collectionId + "/get";

            Map<String, Object> getRequestBody = new LinkedHashMap<>();
            Map<String, String> whereClause = new HashMap<>();
            whereClause.put("documentId", documentId.toString());
            getRequestBody.put("where", whereClause);
            getRequestBody.put("limit", 10000);

            String getJsonBody = objectMapper.writeValueAsString(getRequestBody);
            String getResponse = postToChroma(getEndpoint, getJsonBody);

            JsonNode root = objectMapper.readTree(getResponse);

            if (!root.has("ids") || !root.get("ids").isArray() || root.get("ids").size() == 0) {
                log.info("Chroma中未找到文档 {} 的切片", documentId);
                return;
            }

            List<String> idsToDelete = new ArrayList<>();
            root.get("ids").forEach(id -> idsToDelete.add(id.asText()));

            String deleteEndpoint = "/api/v2/tenants/" + tenantName + "/databases/" + databaseName + "/collections/" + collectionId + "/delete";
            Map<String, Object> deleteRequestBody = new LinkedHashMap<>();
            deleteRequestBody.put("ids", idsToDelete);

            String deleteJsonBody = objectMapper.writeValueAsString(deleteRequestBody);
            postToChroma(deleteEndpoint, deleteJsonBody);

            log.info("从Chroma删除文档: {}, 切片数: {}", documentId, idsToDelete.size());

        } catch (Exception e) {
            log.warn("从Chroma删除文档异常: {}", e.getMessage());
        }
    }

    /**
     * 检索结果记录
     */
    public record SearchResult(String content, String documentId, String chunkIndex, double score) {}
}