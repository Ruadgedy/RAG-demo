package com.ragqa.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragqa.agent.trace.AgentTraceCollector;
import com.ragqa.agent.trace.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Web 搜索工具（Agentic RAG, F18 + F21）。
 *
 * <p>调用 Tavily API（POST /search）获取互联网信息，补足知识库没有的长尾/时效性内容。
 * 无 {@code TAVILY_API_KEY} 时 {@link #isAvailable()} 返回 false，agent 不注册此 tool。
 *
 * <p>注入 {@link RestClient.Builder}（Spring 自动提供）便于测试用 MockRestServiceServer 替换 HTTP 层，
 * 覆盖 {@link #doSearch(String)} 的真实调用链。
 *
 * <p>F21：调用前后各记一条 trace，chatId 从 {@link TraceContext} 取，round 自增。
 */
@Component
@Slf4j
public class WebSearchTool {

    private static final String TAVILY_BASE_URL = "https://api.tavily.com";

    private final String apiKey;
    private final int topK;
    private final RestClient tavilyClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AgentTraceCollector traceCollector;

    public WebSearchTool(
            @Value("${rag.web.search.api-key:}") String apiKey,
            @Value("${rag.web.search.topk:5}") int topK,
            @Value("${rag.web.search.timeout-ms:8000}") int timeoutMs,
            RestClient.Builder restClientBuilder,
            AgentTraceCollector traceCollector) {
        this.apiKey = apiKey;
        this.topK = topK;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        RestClient.Builder builder = restClientBuilder != null ? restClientBuilder : RestClient.builder();
        this.tavilyClient = builder
                .baseUrl(TAVILY_BASE_URL)
                .requestFactory(factory)
                .build();
        this.traceCollector = traceCollector;
    }

    /**
     * 是否可用（配置了 TAVILY_API_KEY）。agent 据此决定是否注册此 tool。
     */
    public boolean isAvailable() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Tool(description = "搜索互联网获取最新或知识库外的信息。涉及时效性、外部产品、公开信息、新闻时使用。参数 query 为搜索关键词。")
    public ToolResult searchWeb(String query) {
        String chatId = TraceContext.getChatId();
        int round = TraceContext.nextRound();
        Map<String, Object> args = Map.of("query", query);

        if (!isAvailable()) {
            log.warn("[web_search] 未配置 TAVILY_API_KEY，跳过");
            if (chatId != null) {
                traceCollector.record(chatId, round, "web_search", args,
                        "未配置 TAVILY_API_KEY", 0, "done");
            }
            return new ToolResult("web_search", "Web 搜索未配置（无 TAVILY_API_KEY）", "", 0L);
        }

        if (chatId != null) {
            traceCollector.record(chatId, round, "web_search", args, null, 0, "start");
        }
        long start = System.currentTimeMillis();
        try {
            String raw = doSearch(query);
            List<JsonNode> results = parseResults(raw);
            String content = results.stream()
                    .map(r -> r.path("content").asText(""))
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.joining("\n\n"));
            String source = results.stream()
                    .map(r -> r.path("url").asText(""))
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.joining(", "));
            long duration = System.currentTimeMillis() - start;
            log.info("[web_search] 命中 {} 条, 耗时 {}ms, query='{}'", results.size(), duration, query);
            if (chatId != null) {
                String summary = "命中 " + results.size() + " 条" +
                        (source.isBlank() ? "" : "；URL=" + source);
                traceCollector.record(chatId, round, "web_search", args, summary, (int) duration, "done");
            }
            return new ToolResult("web_search", content, source, duration);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.error("[web_search] 失败: {}", e.getMessage());
            if (chatId != null) {
                traceCollector.record(chatId, round, "web_search", args,
                        "失败: " + e.getMessage(), (int) duration, "done");
            }
            return new ToolResult("web_search", "Web 搜索失败: " + e.getMessage(), "", duration);
        }
    }

    /**
     * 实际 HTTP 调用 Tavily。package-private 便于测试直接覆盖。
     */
    String doSearch(String query) {
        Map<String, Object> body = Map.of(
                "api_key", apiKey,
                "query", query,
                "max_results", topK,
                "search_depth", "basic"
        );
        return tavilyClient.post()
                .uri("/search")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
    }

    /**
     * 解析 Tavily 响应的 results 数组。package-private 便于测试。
     */
    List<JsonNode> parseResults(String raw) throws Exception {
        JsonNode root = objectMapper.readTree(raw);
        JsonNode results = root.path("results");
        List<JsonNode> list = new ArrayList<>();
        if (results.isArray()) {
            results.forEach(list::add);
        }
        return list;
    }
}
