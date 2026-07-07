package com.ragqa.agent.tool;

import com.ragqa.service.RagService;
import com.ragqa.service.RagService.RetrievalResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

    @InjectMocks
    KnowledgeBaseSearchTool tool;

    @AfterEach
    void cleanup() {
        KnowledgeBaseContext.clear();
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
}
