package com.ragqa.service;

import com.ragqa.dto.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * QueryRewriteService 单元测试
 *
 * 覆盖:
 *   1. 首轮/无历史 → 直接返回原 query（零 LLM 调用）
 *   2. mode=none → 直接返回原 query
 *   3. mode=simple → simpleConcat 拼接
 *   4. mode=llm + LLM 成功 → 返回清理后的 LLM 输出
 *   5. mode=llm + LLM 抛异常 → 降级到 simple 拼接
 *   6. mode=llm + LLM 超时 → 降级到 simple 拼接
 *   7. clean() 工具方法（去引号/取首行）
 *   8. 改写专用历史窗口 min 逻辑
 */
@ExtendWith(MockitoExtension.class)
class QueryRewriteServiceTest {

    @Mock
    private ChatClient.Builder chatClientBuilder;

    private QueryRewriteService service;

    @BeforeEach
    void setUp() {
        service = new QueryRewriteService(chatClientBuilder);

        // @Value 字段手动注入（Spring 上下文测试不加载）
        ReflectionTestUtils.setField(service, "mode", "llm");
        ReflectionTestUtils.setField(service, "timeoutMs", 500L);
        ReflectionTestUtils.setField(service, "temperature", 0.0);
        ReflectionTestUtils.setField(service, "maxTokens", 200);
        ReflectionTestUtils.setField(service, "rewriteHistoryWindow", 2);
        ReflectionTestUtils.setField(service, "rewriteModel", "MiniMax-M2.7");
    }

    @Test
    void shouldReturnOriginalWhenHistoryEmpty() {
        // 首轮（无历史）— 不走 LLM，直接返回
        String result = service.rewrite("它有什么功能", List.of(), 3);
        assertThat(result).isEqualTo("它有什么功能");
        verifyNoInteractions(chatClientBuilder);
    }

    @Test
    void shouldReturnOriginalWhenHistoryNull() {
        String result = service.rewrite("它有什么功能", null, 3);
        assertThat(result).isEqualTo("它有什么功能");
        verifyNoInteractions(chatClientBuilder);
    }

    @Test
    void shouldReturnOriginalWhenModeIsNone() {
        ReflectionTestUtils.setField(service, "mode", "none");

        List<ChatMessage> history = List.of(
                new ChatMessage("user", "介绍产品A"),
                new ChatMessage("assistant", "产品A 是..."),
                new ChatMessage("user", "它有什么功能")
        );
        String result = service.rewrite("有优惠吗", history, 3);
        assertThat(result).isEqualTo("有优惠吗");
        verifyNoInteractions(chatClientBuilder);
    }

    @Test
    void shouldConcatWhenModeIsSimple() {
        ReflectionTestUtils.setField(service, "mode", "simple");

        List<ChatMessage> history = List.of(
                new ChatMessage("user", "介绍产品A"),
                new ChatMessage("assistant", "产品A 是..."),
                new ChatMessage("user", "它有什么功能"),
                new ChatMessage("assistant", "产品A 功能有 X、Y、Z"),
                new ChatMessage("user", "价格多少")
        );
        // simpleConcat 取最近 window 个 user 提问 + 当前
        String result = service.rewrite("有优惠吗", history, 2);
        // window=2 → 最近 2 个 user: ["它有什么功能", "价格多少"] + 当前 "有优惠吗"
        assertThat(result).isEqualTo("它有什么功能 价格多少 有优惠吗");
        verifyNoInteractions(chatClientBuilder);
    }

    @Test
    void shouldCallLlmAndReturnCleanedWhenModeIsLlm() {
        // 模拟 LLM 返回：含引号包装 + 换行（实际 LLM 输出常见格式）
        stubLlmResponse("\"产品A 价格多少，有哪些优惠\"");

        List<ChatMessage> history = List.of(
                new ChatMessage("user", "介绍产品A"),
                new ChatMessage("assistant", "产品A 是..."),
                new ChatMessage("user", "它有什么功能")
        );
        String result = service.rewrite("有优惠吗", history, 3);

        // clean 后应去掉首尾引号
        assertThat(result).isEqualTo("产品A 价格多少，有哪些优惠");
        verify(chatClientBuilder, times(1)).build();
    }

    @Test
    void shouldFallbackToSimpleWhenLlmThrows() {
        // LLM 抛异常 → 降级到 simple 拼接
        stubLlmToThrow(new RuntimeException("LLM 服务不可用"));

        List<ChatMessage> history = List.of(
                new ChatMessage("user", "介绍产品A"),
                new ChatMessage("assistant", "产品A 是..."),
                new ChatMessage("user", "它有什么功能")
        );
        String result = service.rewrite("有优惠吗", history, 3);

        // 降级到 simple 拼接（取最近 3 个 user + 当前）
        assertThat(result).isEqualTo("介绍产品A 它有什么功能 有优惠吗");
    }

    @Test
    void shouldFallbackToSimpleWhenLlmTimesOut() {
        // LLM 调用阻塞超过 timeoutMs → 触发超时降级
        ReflectionTestUtils.setField(service, "timeoutMs", 100L);

        stubLlmToBlock(500L);

        List<ChatMessage> history = List.of(
                new ChatMessage("user", "介绍产品A"),
                new ChatMessage("assistant", "产品A 是..."),
                new ChatMessage("user", "它有什么功能")
        );
        long start = System.currentTimeMillis();
        String result = service.rewrite("有优惠吗", history, 3);
        long elapsed = System.currentTimeMillis() - start;

        // 应该几乎立即返回（不真的等 500ms）
        assertThat(elapsed).isLessThan(400L);
        // 降级到 simple
        assertThat(result).isEqualTo("介绍产品A 它有什么功能 有优惠吗");
    }

    @Test
    void shouldFallbackToSimpleWhenLlmReturnsEmpty() {
        stubLlmResponse("");

        List<ChatMessage> history = List.of(
                new ChatMessage("user", "介绍产品A"),
                new ChatMessage("assistant", "产品A 是..."),
                new ChatMessage("user", "它有什么功能")
        );
        String result = service.rewrite("有优惠吗", history, 3);

        // 空响应 → 降级
        assertThat(result).isEqualTo("介绍产品A 它有什么功能 有优惠吗");
    }

    @Test
    void shouldUseRewriteHistoryWindowWhenSmallerThanConversationWindow() {
        // 改写专用窗口=2，conversation.historyWindow=5 → buildPrompt 应只取最近 2 轮（min 逻辑）
        ReflectionTestUtils.setField(service, "rewriteHistoryWindow", 2);

        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        when(chatClientBuilder.build()).thenReturn(chatClient);
        when(chatClient.prompt(anyString())).thenReturn(requestSpec);
        when(requestSpec.options(any())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn("改写结果");

        List<ChatMessage> history = List.of(
                new ChatMessage("user", "介绍产品A"), new ChatMessage("assistant", "A是..."),
                new ChatMessage("user", "产品B呢"),   new ChatMessage("assistant", "B是..."),
                new ChatMessage("user", "产品C呢"),   new ChatMessage("assistant", "C是..."),
                new ChatMessage("user", "产品D呢"),   new ChatMessage("assistant", "D是..."),
                new ChatMessage("user", "产品E呢"),   new ChatMessage("assistant", "E是...")
        );

        service.rewrite("有优惠吗", history, 5);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatClient).prompt(promptCaptor.capture());
        String prompt = promptCaptor.getValue();

        // 取 min(5, 2) = 2 轮 → prompt 标注"最近 2 轮"，只含最近 2 轮（D、E），不含 A、B、C
        assertThat(prompt).contains("最近 2 轮");
        assertThat(prompt).contains("产品D呢").contains("产品E呢");
        assertThat(prompt).doesNotContain("产品A").doesNotContain("产品B").doesNotContain("产品C");
    }

    @Test
    void cleanShouldStripQuotesAndTakeFirstLine() {
        // 模拟 LLM 的常见输出格式
        assertThat(QueryRewriteService.clean("\"产品A 价格\"")).isEqualTo("产品A 价格");
        assertThat(QueryRewriteService.clean("「产品A 价格」")).isEqualTo("产品A 价格");
        assertThat(QueryRewriteService.clean("'产品A 价格'")).isEqualTo("产品A 价格");
        // 多行只取第一行
        assertThat(QueryRewriteService.clean("改写后: 产品A 价格\n第二行")).isEqualTo("改写后: 产品A 价格");
        // MiniMax M2 reasoning：<think>...</think> 包裹思考，提取 </think> 后内容
        assertThat(QueryRewriteService.clean("<think>\n思考过程\n</think>\n\n如何使用它")).isEqualTo("如何使用它");
        assertThat(QueryRewriteService.clean("<think>abc</think>产品A 价格")).isEqualTo("产品A 价格");
        // null / 空
        assertThat(QueryRewriteService.clean(null)).isEmpty();
        assertThat(QueryRewriteService.clean("")).isEmpty();
        assertThat(QueryRewriteService.clean("   ")).isEmpty();
    }

    // ==================== 辅助方法 ====================

    /**
     * Mock ChatClient 链式调用，让 LLM 返回指定内容
     * ChatClient 调用链：builder.build().prompt(text).options(opts).call().content()
     */
    private void stubLlmResponse(String content) {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);

        when(chatClientBuilder.build()).thenReturn(chatClient);
        when(chatClient.prompt(anyString())).thenReturn(requestSpec);
        when(requestSpec.options(any())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn(content);
    }

    private void stubLlmToThrow(RuntimeException e) {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);

        when(chatClientBuilder.build()).thenReturn(chatClient);
        when(chatClient.prompt(anyString())).thenReturn(requestSpec);
        when(requestSpec.options(any())).thenReturn(requestSpec);
        when(requestSpec.call()).thenThrow(e);
    }

    private void stubLlmToBlock(long ms) {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);

        when(chatClientBuilder.build()).thenReturn(chatClient);
        when(chatClient.prompt(anyString())).thenReturn(requestSpec);
        when(requestSpec.options(any())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        // 阻塞 ms 毫秒模拟慢响应
        when(callSpec.content()).thenAnswer(inv -> {
            Thread.sleep(ms);
            return "来不及返回";
        });
    }
}
