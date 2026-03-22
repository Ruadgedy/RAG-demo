package com.ragqa.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Service
@Slf4j
public class ChromaService {

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${CHROMA_HOST:localhost}")
    private String chromaHost;

    @Value("${CHROMA_PORT:8000}")
    private int chromaPort;

    @Value("${CHROMA_COLLECTION:rag-qa-collection}")
    private String collectionName;

    @Value("${chunk.size:3}")
    private int defaultTopK;

    private String getBaseUrl() {
        return "http://" + chromaHost + ":" + chromaPort;
    }

    public void createCollectionIfNotExists() {
        try {
            String url = getBaseUrl() + "/api/v2/collections";
            
            String body = String.format("{\"name\":\"%s\"}", collectionName);
            
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.set("Content-Type", "application/json");
            org.springframework.http.HttpEntity<String> entity = new org.springframework.http.HttpEntity<>(body, headers);
            
            try {
                restTemplate.postForObject(url, entity, String.class);
                log.info("创建Chroma collection: {}", collectionName);
            } catch (Exception e) {
                log.debug("Collection可能已存在: {}", e.getMessage());
            }
        } catch (Exception e) {
            log.warn("创建collection失败: {}", e.getMessage());
        }
    }

    public void addDocument(UUID documentId, int chunkIndex, String content, float[] embedding) {
        try {
            createCollectionIfNotExists();
            
            String url = getBaseUrl() + "/api/v2/collections/" + collectionName + "/add";
            
            String id = documentId.toString() + "_" + chunkIndex;
            
            String embeddingsJson = "[" + Arrays.toString(embedding) + "]";
            String documentsJson = "[\"" + escapeJson(content) + "\"]";
            String idsJson = "[\"" + id + "\"]";
            String metadatasJson = "[{\"documentId\":\"" + documentId + "\",\"chunkIndex\":" + chunkIndex + "}]";
            
            String body = String.format(
                "{\"embeddings\":%s,\"documents\":%s,\"ids\":%s,\"metadatas\":%s}",
                embeddingsJson, documentsJson, idsJson, metadatasJson
            );
            
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.set("Content-Type", "application/json");
            org.springframework.http.HttpEntity<String> entity = new org.springframework.http.HttpEntity<>(body, headers);
            
            restTemplate.postForObject(url, entity, String.class);
            log.debug("添加向量到Chroma: {}", id);
        } catch (Exception e) {
            log.error("添加向量到Chroma失败: {}", e.getMessage());
        }
    }

    public List<SearchResult> similaritySearch(String query, int topK) {
        try {
            String url = getBaseUrl() + "/api/v2/collections/" + collectionName + "/query";
            
            String embeddingsJson = "[[0.0]]";
            String body = String.format(
                "{\"query_embeddings\":%s,\"n_results\":%d,\"include\":[\"documents\",\"metadatas\"]}",
                embeddingsJson, topK
            );
            
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.set("Content-Type", "application/json");
            org.springframework.http.HttpEntity<String> entity = new org.springframework.http.HttpEntity<>(body, headers);
            
            String response = restTemplate.postForObject(url, entity, String.class);
            
            return parseQueryResponse(response);
        } catch (Exception e) {
            log.error("Chroma检索失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<SearchResult> parseQueryResponse(String response) {
        List<SearchResult> results = new ArrayList<>();
        
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode ids = root.get("ids");
            JsonNode documents = root.get("documents");
            JsonNode metadatas = root.get("metadatas");
            
            if (ids != null && ids.isArray()) {
                for (int i = 0; i < ids.size(); i++) {
                    String id = ids.get(i).asText();
                    String content = documents != null && documents.get(i) != null ? 
                        documents.get(i).asText() : "";
                    
                    String documentId = id;
                    String chunkIndex = "0";
                    
                    if (id.contains("_")) {
                        String[] parts = id.split("_", 2);
                        documentId = parts[0];
                        chunkIndex = parts.length > 1 ? parts[1] : "0";
                    }
                    
                    results.add(new SearchResult(content, documentId, chunkIndex, 1.0));
                }
            }
        } catch (Exception e) {
            log.error("解析Chroma响应失败: {}", e.getMessage());
        }
        
        return results;
    }

    public void deleteByDocumentId(UUID documentId) {
        try {
            String url = getBaseUrl() + "/api/v2/collections/" + collectionName + "/delete";
            
            String whereJson = "{\"documentId\":\"" + documentId + "\"}";
            String body = String.format("{\"where\":%s}", whereJson);
            
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.set("Content-Type", "application/json");
            org.springframework.http.HttpEntity<String> entity = new org.springframework.http.HttpEntity<>(body, headers);
            
            restTemplate.postForObject(url, entity, String.class);
            log.info("从Chroma删除文档: {}", documentId);
        } catch (Exception e) {
            log.warn("删除Chroma文档失败: {}", e.getMessage());
        }
    }

    public int getDefaultTopK() {
        return defaultTopK;
    }

    private String escapeJson(String text) {
        return text.replace("\\", "\\\\")
                   .replace("\"", "\\\"")
                   .replace("\n", "\\n")
                   .replace("\r", "\\r")
                   .replace("\t", "\\t");
    }

    public record SearchResult(String content, String documentId, String chunkIndex, double score) {}
}
