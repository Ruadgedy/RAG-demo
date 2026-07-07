package com.ragqa.agent.tool;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DirectAnswerTool} 单元测试（F18）。
 */
class DirectAnswerToolTest {

    @Test
    void shouldReturnDirectAnswerToolResult() {
        DirectAnswerTool tool = new DirectAnswerTool();

        ToolResult result = tool.directAnswer("你好");

        assertThat(result.toolName()).isEqualTo("direct_answer");
        assertThat(result.content()).contains("闲聊").contains("无需检索");
        assertThat(result.source()).isEqualTo("direct");
    }

    @Test
    void durationShouldBeReasonable() {
        DirectAnswerTool tool = new DirectAnswerTool();

        ToolResult result = tool.directAnswer("你是谁");

        // kill MathMutator（-→+ 会让 duration 爆大）
        assertThat(result.durationMs()).isGreaterThanOrEqualTo(0).isLessThan(10_000);
    }
}
