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
import java.util.concurrent.ConcurrentHashMap;

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

    /** Chroma集合名称 */
    @Value("${spring.ai.vectorstore.chroma.collection-name:rag-qa-collection}")
    private String collectionName;

    /** Chroma Tenant名称 */
    @Value("${spring.ai.vectorstore.chroma.tenant-name:SpringAiTenant}")
    private String tenantName;

    /** Chroma Database名称 */
    @Value("${spring.ai.vectorstore.chroma.database-name:SpringAiDatabase}")
    private String databaseName;

    /** Spring AI的VectorStore接口（保留但不使用，用于依赖注入） */
    private final VectorStore vectorStore;

    /** 已解析的 collectionId 缓存（避免每次 add/query 都打 Chroma 一次 GET） */
    private volatile String cachedCollectionId;

    /** 缓存与 Chroma 实际状态同步用的租户级锁：保证同一进程内并发首调安全 */
    private final ConcurrentHashMap<String, Object> resolveLocks = new ConcurrentHashMap<>();

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
     * 获取或创建集合，返回collection ID
     *
     * 修复说明（2026-06-27）：
     * - 原实现无脑 POST 创建，Chroma v2 在 collection 已存在时返回 409，导致
     *   每个切片第 1 次 add 失败、后续 add 也连带失败。
     * - 新实现先按 collectionName 在 GET 列表中查找，找到直接返回；
     *   找不到再 POST 创建；用 cachedCollectionId 缓存避免每次 add 都打 Chroma。
     * - 通过 resolveLocks 保证并发首调只有一个线程去 Chroma 创建。
     */
    private String getOrCreateCollectionId() throws IOException {
        String cached = cachedCollectionId;
        if (cached != null) {
            return cached;
        }

        Object lock = resolveLocks.computeIfAbsent(collectionName, k -> new Object());
        synchronized (lock) {
            if (cachedCollectionId != null) {
                return cachedCollectionId;
            }

            String listEndpoint = "/api/v2/tenants/" + tenantName + "/databases/" + databaseName + "/collections";
            String listResponse = getFromChroma(listEndpoint);
            JsonNode listRoot = objectMapper.readTree(listResponse);
            if (listRoot.isArray()) {
                for (JsonNode node : listRoot) {
                    if (node.hasNonNull("name") && collectionName.equals(node.get("name").asText())
                            && node.hasNonNull("id")) {
                        cachedCollectionId = node.get("id").asText();
                        log.debug("复用已存在Chroma collection: name={}, id={}", collectionName, cachedCollectionId);
                        return cachedCollectionId;
                    }
                }
            }

            // 不存在则创建
            String createEndpoint = listEndpoint;
            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("name", collectionName);

            String jsonBody = objectMapper.writeValueAsString(requestBody);
            String createResponse = postToChroma(createEndpoint, jsonBody);
            JsonNode createRoot = objectMapper.readTree(createResponse);
            String newId = createRoot.get("id").asText();
            cachedCollectionId = newId;
            log.info("新建Chroma collection: name={}, id={}", collectionName, newId);
            return newId;
        }
    }

    /**
     * 发送HTTP GET请求到Chroma API
     */
    private String getFromChroma(String endpoint) throws IOException {
        URL url = new URL(chromaUrl + endpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(60000);

        try {
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
            }
        } finally {
            conn.disconnect();
        }
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
            String collectionId = getOrCreateCollectionId();
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
     * 根据用户问题，从向量数据库中检索最相似的文档切片。
     *
     * 【2026-06-29 增量 P1-01】距离计算改用余弦相似度
     *
     * 背景：
     *   Chroma collection 默认 space=l2（平方欧氏距离），但 qwen3-embedding:4b
     *   这类现代 embedding 模型训练时按 cosine 优化。直接用 L2 距离会同时受
     *   向量"方向"和"模长"影响，导致两个语义一致但长度不同的文档被错位排名。
     *
     * 解决方案（无需重建 collection、零停机）：
     *   1. query 字段增加 "embeddings" —— 让 Chroma 把候选的存储向量也返回
     *   2. 在 Java 端用 cosineSimilarity(queryVec, storedVec) 重算分数
     *   3. 用 cosine 作为最终 score 返回给上游（RagService / HybridSearchService）
     *
     * 为什么 Chroma 的 L2 排名还能给出"差不多对"的 top-K？
     *   因为训练良好的 embedding 模型输出的向量模长分布集中（多数接近 1），
     *   L2 距离虽然不是最优但排名顺序与 cosine 高度相关。这次改动只是把
     *   "差不多对" 升级成 "完全对"。
     *
     * 兼容性 / 回滚：
     *   - 老逻辑 score = 1.0 / (1.0 + distance) 仍然有效，只是排序精度低
     *   - 如需回滚，把 include 改回 ["documents", "metadatas", "distances"]，
     *     并把 score 计算改回 sigmoid 形式即可
     *
     * @param query 用户问题
     * @param topK  返回前 K 个最相似结果
     * @return SearchResult 列表，score 字段为余弦相似度（范围 [-1, 1]，越接近 1 越相似）
     */
    public List<SearchResult> similaritySearch(String query, int topK) {
        try {
            float[] queryEmbedding = embeddingService.embed(query);

            if (queryEmbedding.length == 0) {
                log.error("查询向量化失败，返回空结果");
                return Collections.emptyList();
            }

            String collectionId = getOrCreateCollectionId();
            String endpoint = "/api/v2/tenants/" + tenantName + "/databases/" + databaseName
                    + "/collections/" + collectionId + "/query";

            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("query_embeddings", List.of(queryEmbedding));
            requestBody.put("n_results", topK);
            // 关键：include 必须含 "embeddings"，否则后续 cosine 计算拿不到存储向量
            requestBody.put("include", List.of("documents", "metadatas", "distances", "embeddings"));

            String jsonBody = objectMapper.writeValueAsString(requestBody);
            String response = postToChroma(endpoint, jsonBody);

            JsonNode root = objectMapper.readTree(response);
            List<SearchResult> results = new ArrayList<>();

            if (root.has("ids") && root.get("ids").isArray() && root.get("ids").size() > 0) {
                JsonNode ids = root.get("ids").get(0);
                JsonNode documents = root.get("documents").get(0);
                JsonNode metadatas = root.get("metadatas").get(0);
                JsonNode distances = root.get("distances").get(0);
                // 候选向量数组，可能为 null（老版本 Chroma 不支持 include embeddings）
                JsonNode embeddings = root.has("embeddings") ? root.get("embeddings").get(0) : null;

                for (int i = 0; i < ids.size(); i++) {
                    String document = documents.get(i).asText();
                    String documentId = metadatas.get(i).has("documentId")
                            ? metadatas.get(i).get("documentId").asText() : "";
                    String chunkIndex = metadatas.get(i).has("chunkIndex")
                            ? metadatas.get(i).get("chunkIndex").asText() : "0";

                    // 距离 + 相似度双轨：cosine 优先，回退到 L2 sigmoid
                    double score;
                    if (embeddings != null && embeddings.isArray() && embeddings.size() > i) {
                        float[] storedVec = parseEmbeddingArray(embeddings.get(i));
                        score = cosineSimilarity(queryEmbedding, storedVec);
                    } else {
                        // 兜底：Chroma 不返回向量时退化为 L2 sigmoid
                        // 这样即使升级 Chroma 后 include 字段不被支持也不会崩
                        double distance = distances.get(i).asDouble();
                        score = 1.0 / (1.0 + distance);
                        log.debug("Chroma 未返回 embedding，使用 L2 sigmoid 兜底 score={}", score);
                    }

                    results.add(new SearchResult(document, documentId, chunkIndex, score));
                }

                // 按 cosine 分数降序排（Chroma 返回顺序是按距离升序，现在按相似度降序）
                results.sort((a, b) -> Double.compare(b.score(), a.score()));
            }

            return results;

        } catch (Exception e) {
            log.error("Chroma检索异常: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 解析 Chroma 返回的向量数组（JSON array of numbers → float[]）
     *
     * Chroma REST v2 的 embeddings 字段格式：
     *   [[0.1, 0.2, ...], [0.3, 0.4, ...], ...]
     *
     * @param node 单个文档的 embedding 数组节点
     * @return float[]，解析失败返回空数组（不抛异常）
     */
    private float[] parseEmbeddingArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return new float[0];
        }
        float[] result = new float[node.size()];
        for (int i = 0; i < node.size(); i++) {
            JsonNode v = node.get(i);
            // 兼容 INT / FLOAT / DOUBLE 三种 JSON 数字类型
            if (v.isNumber()) {
                result[i] = (float) v.asDouble();
            } else {
                log.warn("embedding[{}] 不是数字类型: {}", i, v.getNodeType());
                return new float[0];
            }
        }
        return result;
    }

    /**
     * 余弦相似度计算（cosine similarity）
     *
     * 公式：cos(A, B) = (A · B) / (||A|| × ||B||)
     *
     * 取值范围 [-1, 1]：
     *   - 1.0  完全相同方向（最相似）
     *   - 0.0  正交（无相关性）
     *   - -1.0 完全相反方向（最不相似）
     *
     * 【2026-06-29 增量 P1-01】用于替换 Chroma 默认 L2 距离下的 score 计算。
     *
     * 与 RagService.cosineSimilarity 的区别：
     *   - 这里用于实时检索（O(1) 候选 × 向量维度）
     *   - RagService 那个用于 fallback 检索（遍历数据库里所有 chunk）
     *   - 数学等价，独立维护避免跨包耦合
     *
     * @param a 查询向量（embeddingService 生成）
     * @param b 存储向量（Chroma 返回）
     * @return cosine 值；维度不一致 / 零向量返回 0（不抛异常）
     */
    private double cosineSimilarity(float[] a, float[] b) {
        // 维度不一致 → 不可比
        if (a == null || b == null || a.length != b.length || a.length == 0) {
            return 0.0;
        }

        double dotProduct = 0.0;  // A · B
        double normA = 0.0;       // ||A||²
        double normB = 0.0;       // ||B||²

        // 单次循环同时算点积和两个模长的平方，避免三次遍历
        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }

        // 零向量保护：||A||=0 或 ||B||=0 时余弦无定义，返回 0 表示"无相似度"
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }

        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /**
     * 删除文档的所有向量
     */
    public void deleteByDocumentId(UUID documentId) {
        try {
            String collectionId = getOrCreateCollectionId();
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

    /**
     * 清除 collectionId 缓存（外部在删除/重建 collection 后调用，避免命中已失效 id）
     */
    public void invalidateCollectionIdCache() {
        this.cachedCollectionId = null;
    }
}