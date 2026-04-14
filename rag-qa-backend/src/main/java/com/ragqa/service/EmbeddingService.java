package com.ragqa.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * 向量化服务
 *
 * 作用：将文本转换为向量（embedding）
 *
 * 为什么需要向量化：
 * - 计算机无法直接"理解"文本
 * - 向量化将文本转换为数值数组，在数学空间表示语义
 * - 相似的文本在向量空间中距离较近
 *
 * 技术选型：
 * - 使用Ollama本地部署embedding模型（qwen3-embedding:4b）
 * - Ollama提供REST API，直接调用获取向量
 * - 支持Sentence Transformers格式的embedding模型
 *
 * 工作流程：
 * 1. 接收文本输入（清洗格式）
 * 2. 构建JSON请求体（model + prompt）
 * 3. 调用Ollama /api/embeddings 接口
 * 4. 解析返回的JSON，提取embedding数组
 * 5. 返回float[]形式的向量
 *
 * 向量维度：
 * - qwen3-embedding:4b 生成 4096 维向量
 * - 每个维度是 -1 到 1 之间的浮点数
 *
 * 配置项：
 * - OLLAMA_BASE_URL: Ollama服务地址（默认localhost:11434）
 * - OLLAMA_EMBEDDING_MODEL: 模型名称（默认qwen3-embedding:4b）
 */
@Service
@Slf4j
public class EmbeddingService {

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Ollama服务地址，默认 http://localhost:11434 */
    @Value("${OLLAMA_BASE_URL:http://localhost:11434}")
    private String ollamaUrl;

    /** embedding模型名称，默认 qwen3-embedding:4b */
    @Value("${OLLAMA_EMBEDDING_MODEL:qwen3-embedding:4b}")
    private String modelName;

    /**
     * 将单个文本转换为向量
     * 
     * @param text 输入文本
     * @return 向量数组（float[]）
     */
    public float[] embed(String text) {
        try {
            // 调用Ollama的embeddings API
            String url = ollamaUrl + "/api/embeddings";

            // 清洗文本，适配Embedding接口的JSON格式要求
            if (!StringUtils.isBlank(text)) {
                text = text
                        // 1. 移除换页符、垂直制表符等特殊控制字符
                        .replaceAll("\\f", "")
                        // 2. 将换行符/回车符替换为空格（或转义为\\n，二选一，推荐替换为空格更易读）
                        .replaceAll("\\r|\\n", " ")
                        // 4. 合并多个连续空格为单个空格
                        .replaceAll("\\s+", " ")
                        // 5. 移除首尾空白
                        .trim();
            }
            
            // 构建请求体
            String requestBody = String.format("""
                {
                    "model": "%s",
                    "prompt": "%s"
                }
                """, modelName, text
                    .replace("\\", "\\\\")   // 反斜杠
                    .replace("\"", "\\\"")   // 双引号
                    .replace("\b", "\\b")    // 退格
                    .replace("\f", "\\f")    // 换页
                    .replace("\n", "\\n")    // 换行
                    .replace("\r", "\\r")    // 回车
                    .replace("\t", "\\t"));  // 制表符

            // 设置请求头
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.set("Content-Type", "application/json");

            org.springframework.http.HttpEntity<String> entity = 
                new org.springframework.http.HttpEntity<>(requestBody, headers);

            // 发送POST请求
            String response = restTemplate.postForObject(url, entity, String.class);
            
            // 解析返回的JSON
            JsonNode root = objectMapper.readTree(response);
            JsonNode embedding = root.get("embedding");
            
            if (embedding != null && embedding.isArray()) {
                float[] result = new float[embedding.size()];
                for (int i = 0; i < embedding.size(); i++) {
                    result[i] = (float) embedding.get(i).asDouble();
                }
                return result;
            }
        } catch (Exception e) {
            log.error("向量化失败: {}", e.getMessage());
        }
        return new float[0];
    }

    /**
     * 批量将多个文本转换为向量
     * 
     * @param texts 输入文本列表
     * @return 向量列表（List<float[]>）
     */
    public List<float[]> embed(List<String> texts) {
        List<float[]> results = new ArrayList<>();
        for (String text : texts) {
            results.add(embed(text));
        }
        return results;
    }
}
