package com.ragqa.agent.tool;

import com.ragqa.agent.trace.AgentTraceCollector;
import com.ragqa.agent.trace.TraceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * {@link DirectAnswerTool} 单元测试（F18 + F21）。
 */
class DirectAnswerToolTest {

    @AfterEach
    void cleanup() {
        TraceContext.clear();
    }

    @Test
    void shouldReturnDirectAnswerToolResult() {
        DirectAnswerTool tool = new DirectAnswerTool(null);

        ToolResult result = tool.directAnswer("你好");

        assertThat(result.toolName()).isEqualTo("direct_answer");
        assertThat(result.content()).contains("闲聊").contains("无需检索");
        assertThat(result.source()).isEqualTo("direct");
    }

    @Test
    void durationShouldBeReasonable() {
        ToolResult result = new DirectAnswerTool(null).directAnswer("你是谁");

        assertThat(result.durationMs()).isGreaterThanOrEqualTo(0).isLessThan(10_000);
    }

    @Test
    void directSearchShouldKeepPromptForEmptyQuestion() {
        ToolResult result = new DirectAnswerTool(null).directAnswer("");

        // BNDRY/J: 空问题仍返回稳定的直答提示，不因输入为空改变协议
        assertThat(result.toolName()).isEqualTo("direct_answer");
        assertThat(result.content()).contains("闲聊").contains("无需检索");
        assertThat(result.source()).isEqualTo("direct");
    }

    @Test
    void shouldRecordStartAndDoneTraceWhenChatIdSet() {
        AgentTraceCollector collector = mock(AgentTraceCollector.class);
        DirectAnswerTool tool = new DirectAnswerTool(collector);
        TraceContext.set("direct-chat");

        ToolResult result = tool.directAnswer("你好");

        assertThat(result.toolName()).isEqualTo("direct_answer");
        ArgumentCaptor<Map<String, Object>> args = ArgumentCaptor.forClass(Map.class);
        verify(collector).record(eq("direct-chat"), anyInt(), eq("direct_answer"),
                args.capture(), isNull(), eq(0), eq("start"));
        assertThat(args.getValue()).containsEntry("question", "你好");
        verify(collector).record(eq("direct-chat"), anyInt(), eq("direct_answer"),
                eq(Map.of("question", "你好")), eq("闲聊/常识，无需检索"), anyInt(), eq("done"));
    }
}
