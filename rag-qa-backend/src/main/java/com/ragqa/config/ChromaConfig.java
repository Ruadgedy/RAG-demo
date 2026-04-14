package com.ragqa.config;

import org.springframework.ai.chroma.vectorstore.ChromaApi;
import org.springframework.ai.chroma.vectorstore.ChromaVectorStore;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Chroma向量数据库配置
 *
 * 作用：配置ChromaVectorStore Bean，供Spring AI使用
 *
 * 配置项：
 * - spring.ai.vectorstore.chroma.url: Chroma服务地址（默认http://localhost:8000）
 * - spring.ai.vectorstore.chroma.collection-name: 集合名称（默认rag-qa-collection）
 *
 * Chroma API版本：
 * - Spring AI 1.1.3 使用 Chroma v2 API
 * - 需要显式指定 tenant 和 database（v1不需要）
 *
 * tenant/database：
 * - Spring AI 1.1.x 会自动创建不存在的 tenant/database
 * - 如果Chroma服务已存在，可以通过API手动创建：
 *   POST /api/v2/tenants/{tenantName}/databases/{databaseName}
 *
 * 注意事项：
 * - initializeSchema(true): 自动创建集合（如果不存在）
 * - initializeImmediately(false): 延迟初始化，避免启动时连接失败
 * - collectionName必须与手动创建的集合名称一致
 */
@Configuration
public class ChromaConfig {

    /** Chroma服务地址 */
    @Value("${spring.ai.vectorstore.chroma.url:http://localhost:8000}")
    private String chromaUrl;

    /** Chroma集合名称 */
    @Value("${spring.ai.vectorstore.chroma.collection-name:rag-qa-collection}")
    private String collectionName;

    /**
     * 创建ChromaVectorStore Bean
     *
     * @param embeddingModel 嵌入模型（由Spring AI自动注入，Ollama配置在application.properties中）
     * @return ChromaVectorStore实例
     */
    @Bean
    public ChromaVectorStore chromaVectorStore(EmbeddingModel embeddingModel) {
        // 构建Chroma API客户端
        ChromaApi chromaApi = ChromaApi.builder()
                .baseUrl(chromaUrl)
                .restClientBuilder(RestClient.builder())
                .build();

        // 构建VectorStore
        ChromaVectorStore store = ChromaVectorStore.builder(chromaApi, embeddingModel)
                .collectionName(collectionName)
                .tenantName("SpringAiTenant")
                .databaseName("SpringAiDatabase")
                .initializeSchema(true)        // 自动创建集合
                .initializeImmediately(false)  // 延迟初始化
                .build();

        return store;
    }
}