package com.ragqa.agent.tool;

import com.ragqa.agent.trace.AgentTraceCollector;
import com.ragqa.agent.trace.TraceContext;
import com.ragqa.service.RagService;
import com.ragqa.service.RagService.RetrievalResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link KnowledgeBaseSearchTool} 单元测试（F17）。
 *
 * <p>mock {@link RagService#retrieve}，验证：
 * <ul>
 *   <li>tool 调用 retrieve 并返回 ToolResult</li>
 *   <li>kbId 从 KnowledgeBaseContext 注入（非 LLM 参数）</li>
 *   <li>来源文件名去重</li>
 *   <li>无结果时返回空 content</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeBaseSearchToolTest {

    @Mock
    RagService ragService;

    @Mock
    AgentTraceCollector traceCollector;

    @InjectMocks
    KnowledgeBaseSearchTool tool;

    @AfterEach
    void cleanup() {
        KnowledgeBaseContext.clear();
        TraceContext.clear();
    }

    @Test
    void shouldCallRetrieveAndReturnToolResult() {
        UUID kbId = UUID.randomUUID();
        KnowledgeBaseContext.set(kbId);
        when(ragService.retrieve("产品A", kbId)).thenReturn(List.of(
                new RetrievalResult("产品A价格¥2999", "doc1_0", 0.9, "产品手册.pdf")
        ));

        ToolResult result = tool.searchKnowledgeBase("产品A");

        verify(ragService).retrieve("产品A", kbId);
        assertThat(result.toolName()).isEqualTo("kb_search");
        assertThat(result.content()).contains("产品A价格¥2999");
        assertThat(result.source()).contains("产品手册.pdf");
        assertThat(result.durationMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void shouldReturnEmptyWhenNoResults() {
        UUID kbId = UUID.randomUUID();
        KnowledgeBaseContext.set(kbId);
        when(ragService.retrieve(anyString(), eq(kbId))).thenReturn(List.of());

        ToolResult result = tool.searchKnowledgeBase("不存在的内容");

        assertThat(result.content()).isEmpty();
        assertThat(result.source()).isEmpty();
        assertThat(result.toolName()).isEqualTo("kb_search");
    }

    @Test
    void shouldUseKnowledgeBaseContextKbId() {
        UUID kbId = UUID.randomUUID();
        KnowledgeBaseContext.set(kbId);
        when(ragService.retrieve(anyString(), eq(kbId))).thenReturn(List.of());

        tool.searchKnowledgeBase("test");

        // kbId 从 KnowledgeBaseContext 注入，非 LLM 参数
        verify(ragService).retrieve(anyString(), eq(kbId));
    }

    @Test
    void shouldDeduplicateSourceFileNames() {
        UUID kbId = UUID.randomUUID();
        KnowledgeBaseContext.set(kbId);
        when(ragService.retrieve(anyString(), eq(kbId))).thenReturn(List.of(
                new RetrievalResult("片段1", "doc1_0", 0.9, "产品手册.pdf"),
                new RetrievalResult("片段2", "doc1_1", 0.8, "产品手册.pdf"),
                new RetrievalResult("片段3", "doc2_0", 0.7, "FAQ.pdf")
        ));

        ToolResult result = tool.searchKnowledgeBase("产品");

        // 同名文件去重
        assertThat(result.source()).isEqualTo("产品手册.pdf, FAQ.pdf");
    }

    @Test
    void clearShouldRemoveKbId() {
        UUID kbId = UUID.randomUUID();
        KnowledgeBaseContext.set(kbId);
        assertThat(KnowledgeBaseContext.get()).isEqualTo(kbId);

        KnowledgeBaseContext.clear();

        // clear 后 get 返回 null，kill "移除 KB_ID.remove()" 的 VoidMethodCallMutator
        assertThat(KnowledgeBaseContext.get()).isNull();
    }

    @Test
    void durationShouldBeReasonable() {
        UUID kbId = UUID.randomUUID();
        KnowledgeBaseContext.set(kbId);
        when(ragService.retrieve(anyString(), eq(kbId))).thenReturn(List.of(
                new RetrievalResult("内容", "doc1_0", 0.9, "file.pdf")
        ));

        ToolResult result = tool.searchKnowledgeBase("test");

        // mock 检索瞬时返回，duration 应在小范围；kill MathMutator（-→+ 会让 duration 爆大到 ~3.4e12）
        assertThat(result.durationMs()).isGreaterThanOrEqualTo(0).isLessThan(10_000);
    }

    // === Test Inventory G: BNDRY/single-result source 格式（无尾随逗号） ===
    @Test
    void singleResultShouldFormatSourceWithoutTrailingComma() {
        UUID kbId = UUID.randomUUID();
        KnowledgeBaseContext.set(kbId);
        when(ragService.retrieve(anyString(), eq(kbId))).thenReturn(List.of(
                new RetrievalResult("单条内容", "doc_0", 0.9, "only.pdf")
        ));

        ToolResult result = tool.searchKnowledgeBase("单条");

        // 单条结果 source 不应有尾随逗号 / 前后空格
        assertThat(result.source()).isEqualTo("only.pdf");
        assertThat(result.source()).doesNotEndWith(",");
        assertThat(result.source()).doesNotStartWith(" ");
        assertThat(result.source()).doesNotEndWith(" ");
    }

    // === Test Inventory H: BNDRY/null-kbId 透传 ===
    @Test
    void shouldPassNullKbIdToRetrieveWhenContextUnset() {
        // 不 set KnowledgeBaseContext（get() 返回 null）
        when(ragService.retrieve(anyString(), isNull())).thenReturn(List.of());

        ToolResult result = tool.searchKnowledgeBase("any");

        // kbId=null 必须透传给 retrieve，不允许默认 UUID 或抛 NPE
        verify(ragService).retrieve(anyString(), isNull());
        assertThat(result.content()).isEmpty();
        assertThat(result.toolName()).isEqualTo("kb_search");
    }

    // === Test Inventory J: INTG/spring-tool-contract（轻量集成：@Component + @Tool 注解契约） ===
    @Test
    void shouldExposeSpringAiToolAnnotationWithKnowledgeBaseDescription() throws NoSuchMethodException {
        // 验证 Spring AI 工具扫描契约：类上 @Component + 方法上有 @Tool + 描述含"知识库"
        assertThat(KnowledgeBaseSearchTool.class.isAnnotationPresent(Component.class))
                .as("KnowledgeBaseSearchTool must be a Spring @Component for bean wiring")
                .isTrue();

        Method method = KnowledgeBaseSearchTool.class.getDeclaredMethod("searchKnowledgeBase", String.class);
        Tool toolAnnotation = method.getAnnotation(Tool.class);
        assertThat(toolAnnotation)
                .as("searchKnowledgeBase must be annotated with @Tool for Spring AI tool-calling")
                .isNotNull();
        assertThat(toolAnnotation.description())
                .as("Tool description must mention '知识库' so LLM knows when to invoke it")
                .contains("知识库");
    }

    // === F21 trace 路径覆盖：KnowledgeBaseSearchTool 文件内嵌 trace 记录代码 ===
    // 触发条件：TraceContext.setChatId → chatId 非 null → 调 traceCollector.record(start + done)
    @Test
    void shouldRecordStartAndDoneTraceWhenChatIdSet() {
        UUID kbId = UUID.randomUUID();
        KnowledgeBaseContext.set(kbId);
        TraceContext.set("test-chat-1");
        when(ragService.retrieve(anyString(), eq(kbId))).thenReturn(List.of(
                new RetrievalResult("命中内容", "doc_0", 0.9, "trace-doc.pdf")
        ));

        ToolResult result = tool.searchKnowledgeBase("trace-query");

        // trace start 记录（含 tool_name=k b_search + args.query + status=start）
        verify(traceCollector).record(
                eq("test-chat-1"), anyInt(), eq("kb_search"),
                eq(Map.of("query", "trace-query")),
                isNull(), eq(0), eq("start"));
        // trace done 记录（status=done + summary 含命中数 + duration）
        verify(traceCollector).record(
                eq("test-chat-1"), anyInt(), eq("kb_search"),
                any(), anyString(), anyInt(), eq("done"));
        assertThat(result.toolName()).isEqualTo("kb_search");
    }

    @Test
    void shouldSkipTraceWhenChatIdUnset() {
        UUID kbId = UUID.randomUUID();
        KnowledgeBaseContext.set(kbId);
        // 不调 TraceContext.set → chatId 为 null → 跳过 trace 记录
        when(ragService.retrieve(anyString(), eq(kbId))).thenReturn(List.of());

        tool.searchKnowledgeBase("no-trace");

        // chatId=null 时不应调 traceCollector.record
        verify(traceCollector, org.mockito.Mockito.never()).record(
                anyString(), anyInt(), anyString(), any(), any(), anyInt(), anyString());
    }
}
