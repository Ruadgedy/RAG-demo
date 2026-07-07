package com.ragqa.agent;

import com.ragqa.agent.tool.DirectAnswerTool;
import com.ragqa.agent.tool.KnowledgeBaseSearchTool;
import com.ragqa.agent.tool.WebSearchTool;
import com.ragqa.agent.trace.AgentTrace;
import com.ragqa.agent.trace.AgentTraceCollector;
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
 * {@link AgenticRagService} 单元测试（F19 + F21）。
 *
 * <p>F21 增量：mock {@link AgentTraceCollector}，验证 chatId 透传、degraded 标记、
 * rounds 计算、ThreadLocal 清理等新增契约。
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

    @Mock
    AgentTraceCollector traceCollector;

    private AgenticRagService newService(long timeoutMs) {
        AgenticRagService svc = new AgenticRagService(
                chatClientBuilder, ragService, kbTool, webTool, directTool, traceCollector);
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
        // 不在这里 stub getTraces，由各测试自己指定（避免 anyString() 与具体 chatId 互相覆盖）
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
        // F21：超时路径走线性，rounds=0
        when(traceCollector.getTraces("chat-001")).thenReturn(List.of());

        RagService.ChatResult result = svc.chat("chat-001", "q", kbId, List.of(), 3);

        assertThat(result.answer()).isEqualTo("降级回答");
        assertThat(result.degraded()).isTrue();   // F21：超时降级标记
        assertThat(result.agentMode()).isEqualTo("agentic"); // mode 保留请求值
        assertThat(result.agentRounds()).isZero();
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
        when(traceCollector.getTraces("chat-002")).thenReturn(List.of());

        RagService.ChatResult result = svc.chat("chat-002", "q", kbId, List.of(), 3);

        assertThat(result.answer()).isEqualTo("降级回答");
        assertThat(result.degraded()).isTrue();
        verify(ragService).chat(anyString(), eq(kbId), any(), anyInt());
    }

    // ---- 成功返回 ----

    @Test
    void chatShouldReturnAgenticAnswerOnSuccess() {
        AgenticRagService svc = newService(30_000);
        UUID kbId = UUID.randomUUID();
        // F21：模拟 agent 跑了 2 轮（kb_search × 2）
        when(traceCollector.getTraces("chat-003")).thenReturn(List.of(
                traceStub("chat-003", 1, "kb_search"),
                traceStub("chat-003", 2, "kb_search")
        ));
        stubSuccess("agentic 回答");

        RagService.ChatResult result = svc.chat("chat-003", "q", kbId, List.of(), 3);

        assertThat(result.answer()).isEqualTo("agentic 回答");
        assertThat(result.retrievalDurationMs()).isGreaterThanOrEqualTo(0);
        assertThat(result.rewrittenQuery()).isEqualTo("q");
        assertThat(result.agentMode()).isEqualTo("agentic");
        assertThat(result.agentRounds()).isEqualTo(2);
        assertThat(result.degraded()).isFalse();
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
        when(traceCollector.getTraces("chat-004")).thenReturn(List.of());

        RagService.ChatResult result = svc.retrieveForStreaming("chat-004", "q", kbId, List.of(), 3);

        assertThat(result.answer()).isNull();
        assertThat(result.degraded()).isTrue();
        verify(ragService).retrieveForStreaming(anyString(), eq(kbId), any(), anyInt());
    }

    // ---- retrieveForStreaming 成功返回 tool context ----

    @Test
    void retrieveForStreamingShouldReturnToolContextOnSuccess() {
        AgenticRagService svc = newService(30_000);
        UUID kbId = UUID.randomUUID();
        // F21：流式场景，agent 跑了 3 轮（kb + web + direct）
        when(traceCollector.getTraces("chat-005")).thenReturn(List.of(
                traceStub("chat-005", 1, "kb_search"),
                traceStub("chat-005", 2, "web_search"),
                traceStub("chat-005", 3, "direct_answer")
        ));
        stubSuccess("KB: 产品A ¥2999\nWeb: 竞品X ¥4599");

        RagService.ChatResult result = svc.retrieveForStreaming("chat-005", "产品A和竞品X价格对比", kbId, List.of(), 3);

        assertThat(result.answer()).isNull(); // 流式场景 answer=null
        assertThat(result.retrievalDurationMs()).isGreaterThanOrEqualTo(0);
        assertThat(result.rewrittenQuery()).contains("产品A");
        assertThat(result.agentMode()).isEqualTo("agentic");
        assertThat(result.agentRounds()).isEqualTo(3);
        assertThat(result.degraded()).isFalse();
    }

    /**
     * 构造一个最小的 AgentTrace（@Data 类，无 builder）。
     * AgentTrace 字段对测试而言只需 chatId/round/toolName 即可。
     */
    private static AgentTrace traceStub(String chatId, int round, String toolName) {
        AgentTrace t = new AgentTrace();
        t.setChatId(chatId);
        t.setRound(round);
        t.setToolName(toolName);
        return t;
    }
}
