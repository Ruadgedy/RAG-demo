package com.ragqa.agent.trace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AgentTraceCollector} 单元测试（F21）。
 *
 * <p>覆盖：record() 落库 / 异常吞掉、truncate 500 字、sseData JSON 拼接、getTraces 透传 repo。
 */
@ExtendWith(MockitoExtension.class)
class AgentTraceCollectorTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Mock
    AgentTraceRepository repo;

    @Test
    void recordShouldSaveEntityWithSerializedArgsAndTruncatedSummary() {
        AgentTraceCollector collector = new AgentTraceCollector(repo);
        Map<String, Object> args = Map.of("query", "产品A");

        collector.record("chat-001", 1, "kb_search", args, "命中 3 条", 320, "done");

        ArgumentCaptor<AgentTrace> captor = ArgumentCaptor.forClass(AgentTrace.class);
        verify(repo).save(captor.capture());
        AgentTrace saved = captor.getValue();
        assertThat(saved.getChatId()).isEqualTo("chat-001");
        assertThat(saved.getRound()).isEqualTo(1);
        assertThat(saved.getToolName()).isEqualTo("kb_search");
        assertThat(saved.getToolArgs()).contains("query").contains("产品A");
        assertThat(saved.getResultSummary()).isEqualTo("命中 3 条");
        assertThat(saved.getDurationMs()).isEqualTo(320);
        assertThat(saved.getStatus()).isEqualTo("done");
    }

    @Test
    void recordShouldTruncateSummaryAt500Chars() {
        AgentTraceCollector collector = new AgentTraceCollector(repo);
        String longText = "x".repeat(800);

        collector.record("chat-002", 1, "kb_search", null, longText, 100, "done");

        ArgumentCaptor<AgentTrace> captor = ArgumentCaptor.forClass(AgentTrace.class);
        verify(repo).save(captor.capture());
        String summary = captor.getValue().getResultSummary();
        // 500 字 + 省略号
        assertThat(summary.length()).isEqualTo(501);
        assertThat(summary).endsWith("…");
    }

    @Test
    void recordShouldSwallowExceptions() {
        AgentTraceCollector collector = new AgentTraceCollector(repo);
        org.mockito.Mockito.doThrow(new RuntimeException("DB down"))
                .when(repo).save(any());

        // 不应抛 —— 主链路不能被 trace 落库拖累
        collector.record("chat-003", 1, "kb_search", Map.of(), "summary", 10, "done");
    }

    @Test
    void sseDataShouldProduceValidJsonWithAllFields() throws Exception {
        AgentTraceCollector collector = new AgentTraceCollector(repo);
        Map<String, Object> extra = Map.of("durationMs", "320", "summary", "命中 3 条");

        String json = collector.sseData("chat-004", 1, "kb_search", "done", extra);

        JsonNode node = M.readTree(json);
        assertThat(node.get("chatId").asText()).isEqualTo("chat-004");
        assertThat(node.get("round").asInt()).isEqualTo(1);
        assertThat(node.get("tool").asText()).isEqualTo("kb_search");
        assertThat(node.get("status").asText()).isEqualTo("done");
        assertThat(node.get("durationMs").asText()).isEqualTo("320");
        assertThat(node.get("summary").asText()).isEqualTo("命中 3 条");
    }

    @Test
    void sseDataWithNullExtraShouldStillReturnBaseJson() throws Exception {
        AgentTraceCollector collector = new AgentTraceCollector(repo);

        String json = collector.sseData("chat-005", 2, "web_search", "start", null);

        JsonNode node = M.readTree(json);
        assertThat(node.get("round").asInt()).isEqualTo(2);
        assertThat(node.get("tool").asText()).isEqualTo("web_search");
        assertThat(node.get("status").asText()).isEqualTo("start");
        // 没 extra 也不应有空 key，puts 跳过 null 值
        assertThat(node.has("durationMs")).isFalse();
    }

    @Test
    void getTracesShouldDelegateToRepo() {
        AgentTraceCollector collector = new AgentTraceCollector(repo);
        AgentTrace stub = new AgentTrace();
        when(repo.findByChatIdOrderByRound("chat-006")).thenReturn(List.of(stub));

        List<AgentTrace> result = collector.getTraces("chat-006");

        assertThat(result).hasSize(1).contains(stub);
        verify(repo).findByChatIdOrderByRound("chat-006");
    }
}
