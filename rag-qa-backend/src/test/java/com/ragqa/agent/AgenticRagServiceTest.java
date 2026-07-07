package com.ragqa.agent;

import com.ragqa.agent.tool.DirectAnswerTool;
import com.ragqa.agent.tool.KnowledgeBaseSearchTool;
import com.ragqa.agent.tool.WebSearchTool;
import com.ragqa.service.RagService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link AgenticRagService} 单元测试（F19）。
 *
 * <p>策略：mock `ChatClient.Request`（fluent 链返回对象），直接 stub `.call().content()` 预返回值，
 * 避免 Spring AI builder interface 类型层次问题。验证降级逻辑、ThreadLocal 清理。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgenticRagServiceTest {

    @Mock
    ChatClient.Builder chatClientBuilder;

    @Mock
    ChatClient chatClient;

    @Mock
    ChatClient.ChatClientRequestSpec requestSpec;

    @Mock
    ChatClient.CallResponseSpec responseSpec;

    @Mock
    ChatResponse chatResponse;

    @Mock
    RagService ragService;

    @Mock
    KnowledgeBaseSearchTool kbTool;

    @Mock
    WebSearchTool webTool;

    @Mock
    DirectAnswerTool directTool;

    private AgenticRagService newService(long timeoutMs) {
        AgenticRagService svc = new AgenticRagService(
                chatClientBuilder, ragService, kbTool, webTool, directTool);
        ReflectionTestUtils.setField(svc, "timeoutMs", timeoutMs);
        ReflectionTestUtils.setField(svc, "agentModel", "MiniMax-M3");
        return svc;
    }

    /** 通用 mock 链：build → prompt → user → advisors → tools → options → call → content */
    private void stubSuccess(String content) {
        when(chatClientBuilder.build()).thenReturn(chatClient);
        when(chatClient.prompt(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.advisors((org.springframework.ai.chat.client.advisor.api.Advisor[]) any())).thenReturn(requestSpec);
        when(requestSpec.tools(any(), any(), any())).thenReturn(requestSpec);
        when(requestSpec.options(any())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn(content);   // kill doAgenticChat: response == null branch
    }

    // ---- 超时降级 ----

    @Test
    void chatShouldDegradeOnTimeout() {
        AgenticRagService svc = newService(10); // 极短超时
        UUID kbId = UUID.randomUUID();
        // LLM 调用慢，不设置 call 响应 → future.get(10ms) 抛 TimeoutException
        when(chatClientBuilder.build()).thenReturn(chatClient);
        when(chatClient.prompt(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.advisors((org.springframework.ai.chat.client.advisor.api.Advisor[]) any())).thenReturn(requestSpec);
        when(requestSpec.tools(any(), any(), any())).thenReturn(requestSpec);
        when(requestSpec.options(any())).thenReturn(requestSpec);
        when(requestSpec.call()).thenAnswer(inv -> {
            Thread.sleep(50); // 模拟慢调用
            return responseSpec;
        });
        when(ragService.chat(anyString(), eq(kbId), any(), anyInt()))
                .thenReturn(new RagService.ChatResult("降级回答", List.of(), 100L, "q"));

        RagService.ChatResult result = svc.chat("q", kbId, List.of(), 3);

        assertThat(result.answer()).isEqualTo("降级回答");
        verify(ragService).chat(anyString(), eq(kbId), any(), anyInt());
    }

    // ---- 异常降级 ----

    @Test
    void chatShouldDegradeOnException() {
        AgenticRagService svc = newService(30_000);
        UUID kbId = UUID.randomUUID();
        stubSuccess("never");
        when(requestSpec.call()).thenThrow(new RuntimeException("LLM error"));
        when(ragService.chat(anyString(), eq(kbId), any(), anyInt()))
                .thenReturn(new RagService.ChatResult("降级回答", List.of(), 100L, "q"));

        RagService.ChatResult result = svc.chat("q", kbId, List.of(), 3);

        assertThat(result.answer()).isEqualTo("降级回答");
        verify(ragService).chat(anyString(), eq(kbId), any(), anyInt());
    }

    // ---- 成功返回 ----

    @Test
    void chatShouldReturnAgenticAnswerOnSuccess() {
        AgenticRagService svc = newService(30_000);
        UUID kbId = UUID.randomUUID();
        stubSuccess("agentic 回答");

        RagService.ChatResult result = svc.chat("q", kbId, List.of(), 3);

        assertThat(result.answer()).isEqualTo("agentic 回答");
        assertThat(result.retrievalDurationMs()).isGreaterThanOrEqualTo(0);
        assertThat(result.rewrittenQuery()).isEqualTo("q");
    }

    // ---- retrieveForStreaming 异常降级 ----

    @Test
    void retrieveForStreamingShouldDegradeOnException() {
        AgenticRagService svc = newService(30_000);
        UUID kbId = UUID.randomUUID();
        stubSuccess("never");
        when(requestSpec.call()).thenThrow(new RuntimeException("LLM error"));
        when(ragService.retrieveForStreaming(anyString(), eq(kbId), any(), anyInt()))
                .thenReturn(new RagService.ChatResult(null, List.of(), 50L, "q"));

        RagService.ChatResult result = svc.retrieveForStreaming("q", kbId, List.of(), 3);

        assertThat(result.answer()).isNull();
        verify(ragService).retrieveForStreaming(anyString(), eq(kbId), any(), anyInt());
    }

    // ---- retrieveForStreaming 成功返回 tool context ----

    @Test
    void retrieveForStreamingShouldReturnToolContextOnSuccess() {
        AgenticRagService svc = newService(30_000);
        UUID kbId = UUID.randomUUID();
        stubSuccess("KB: 产品A ¥2999\nWeb: 竞品X ¥4599");

        RagService.ChatResult result = svc.retrieveForStreaming("产品A和竞品X价格对比", kbId, List.of(), 3);

        assertThat(result.answer()).isNull(); // 流式场景 answer=null
        assertThat(result.retrievalDurationMs()).isGreaterThanOrEqualTo(0);
        assertThat(result.rewrittenQuery()).contains("产品A");
    }
}
