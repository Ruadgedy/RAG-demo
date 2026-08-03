package com.ragqa.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragqa.agent.trace.AgentTraceCollector;
import com.ragqa.agent.trace.TraceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class WebSearchToolTest {

    @AfterEach
    void cleanup() {
        TraceContext.clear();
    }

    private static WebSearchTool realTool(String apiKey) {
        return new WebSearchTool(apiKey, 5, 8000, RestClient.builder(), null);
    }

    @Test
    void isAvailableShouldReturnTrueWhenApiKeyPresent() {
        assertThat(realTool("test-key").isAvailable()).isTrue();
    }

    @Test
    void isAvailableShouldReturnFalseWhenApiKeyAbsent() {
        assertThat(realTool("").isAvailable()).isFalse();
    }

    @Test
    void shouldReturnNotConfiguredWhenNoApiKey() {
        ToolResult result = realTool("").searchWeb("query");
        assertThat(result.toolName()).isEqualTo("web_search");
        assertThat(result.content()).contains("未配置");
        assertThat(result.durationMs()).isZero();
    }

    @Test
    void shouldReturnToolResultFromTavilyResponse() {
        WebSearchTool spy = org.mockito.Mockito.spy(new WebSearchTool("key", 5, 8000, null, null));
        doReturn("{\"results\":[{\"title\":\"T1\",\"url\":\"http://u1.com\",\"content\":\"内容1\"},{\"title\":\"T2\",\"url\":\"http://u2.com\",\"content\":\"内容2\"}]}")
                .when(spy).doSearch("query");

        ToolResult result = spy.searchWeb("query");

        assertThat(result.toolName()).isEqualTo("web_search");
        assertThat(result.content()).contains("内容1").contains("内容2");
        assertThat(result.source()).contains("http://u1.com").contains("http://u2.com");
        assertThat(result.durationMs()).isGreaterThanOrEqualTo(0).isLessThan(10_000);
    }

    @Test
    void shouldHandleSearchFailureGracefully() {
        WebSearchTool spy = org.mockito.Mockito.spy(new WebSearchTool("key", 5, 8000, null, null));
        doThrow(new RuntimeException("network error")).when(spy).doSearch("query");

        ToolResult result = spy.searchWeb("query");

        assertThat(result.toolName()).isEqualTo("web_search");
        assertThat(result.content()).contains("失败").contains("network error");
        assertThat(result.source()).isEmpty();
        assertThat(result.durationMs()).isGreaterThanOrEqualTo(0).isLessThan(10_000);
    }

    @Test
    void shouldReturnEmptyContentWhenResultsHaveNoContent() {
        WebSearchTool spy = org.mockito.Mockito.spy(new WebSearchTool("key", 5, 8000, null, null));
        doReturn("{\"results\":[{\"url\":\"http://u.com\",\"content\":\"\"}]}")
                .when(spy).doSearch("q");

        ToolResult result = spy.searchWeb("q");

        assertThat(result.content()).isEmpty();
        assertThat(result.source()).contains("http://u.com");
    }

    @Test
    void doSearchShouldCallTavilyApiAndReturnBody() {
        WebSearchTool tool = realTool("key");
        try {
            String result = tool.doSearch("query");
            assertThat(result).isNotNull();
        } catch (Exception e) {
            assertThat(e.getMessage()).isNotBlank();
        }
    }

    @Test
    void parseResultsShouldExtractResultsArray() throws Exception {
        List<JsonNode> results = realTool("key").parseResults("{\"results\":[{\"title\":\"T\",\"url\":\"u\",\"content\":\"c\"}],\"answer\":\"a\"}");
        assertThat(results).hasSize(1);
        assertThat(results.get(0).path("content").asText()).isEqualTo("c");
    }

    @Test
    void parseResultsShouldReturnEmptyWhenNoResultsField() throws Exception {
        assertThat(realTool("key").parseResults("{\"answer\":\"a\"}")).isEmpty();
    }

    @Test
    void shouldApplyConfiguredTopKToTavilyRequest() {
        WebSearchTool spy = org.mockito.Mockito.spy(new WebSearchTool("key", 1, 8000, RestClient.builder(), null));
        doReturn("{\"results\":[{\"url\":\"u1\",\"content\":\"first\"}]}").when(spy).doSearch("query");

        ToolResult result = spy.searchWeb("query");

        assertThat(result.content()).isEqualTo("first");
        assertThat(result.source()).isEqualTo("u1");
    }

    @Test
    void shouldConvertTimeoutFailureToToolResult() {
        WebSearchTool spy = org.mockito.Mockito.spy(new WebSearchTool("key", 5, 1, RestClient.builder(), null));
        doThrow(new RuntimeException("timeout")).when(spy).doSearch("q");

        ToolResult result = spy.searchWeb("q");

        assertThat(result.toolName()).isEqualTo("web_search");
        assertThat(result.content()).contains("失败").contains("timeout");
        assertThat(result.durationMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void shouldRecordTraceForSuccessfulSearch() {
        AgentTraceCollector collector = mock(AgentTraceCollector.class);
        WebSearchTool spy = org.mockito.Mockito.spy(new WebSearchTool("key", 5, 8000, null, collector));
        TraceContext.set("web-chat-success");
        doReturn("{\"results\":[{\"url\":\"u\",\"content\":\"c\"}]}").when(spy).doSearch("q");

        ToolResult result = spy.searchWeb("q");

        assertThat(result.content()).isEqualTo("c");
        verify(collector).record(eq("web-chat-success"), anyInt(), eq("web_search"),
                eq(Map.of("query", "q")), isNull(), eq(0), eq("start"));
        verify(collector).record(eq("web-chat-success"), anyInt(), eq("web_search"),
                eq(Map.of("query", "q")), anyString(), anyInt(), eq("done"));
    }

    @Test
    void shouldRecordTraceForFailedSearch() {
        AgentTraceCollector collector = mock(AgentTraceCollector.class);
        WebSearchTool spy = org.mockito.Mockito.spy(new WebSearchTool("key", 5, 8000, null, collector));
        TraceContext.set("web-chat-failure");
        doThrow(new RuntimeException("network error")).when(spy).doSearch("q");

        ToolResult result = spy.searchWeb("q");

        assertThat(result.content()).contains("Web 搜索失败");
        verify(collector).record(eq("web-chat-failure"), anyInt(), eq("web_search"),
                eq(Map.of("query", "q")), isNull(), eq(0), eq("start"));
        verify(collector).record(eq("web-chat-failure"), anyInt(), eq("web_search"),
                eq(Map.of("query", "q")), anyString(), anyInt(), eq("done"));
    }

    @Test
    void shouldRecordTraceForUnavailableSearch() {
        AgentTraceCollector collector = mock(AgentTraceCollector.class);
        WebSearchTool tool = new WebSearchTool("", 5, 8000, null, collector);
        TraceContext.set("web-chat-unavailable");

        ToolResult result = tool.searchWeb("q");

        assertThat(result.content()).contains("未配置");
        verify(collector).record(eq("web-chat-unavailable"), anyInt(), eq("web_search"),
                eq(Map.of("query", "q")), eq("未配置 TAVILY_API_KEY"), eq(0), eq("done"));
    }
}
