package com.ragqa.agent.tool;

import com.ragqa.service.RagService;
import com.ragqa.service.RagService.RetrievalResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 知识库检索工具（Agentic RAG, F17）。
 *
 * <p>包装 {@link RagService#retrieve(String, UUID)}，复用现有召回 + rerank + fallback 链路。
 * agent 通过 tool-calling 自主决定何时检索。
 *
 * <p>kbId 从 {@link KnowledgeBaseContext} 注入（不作为 {@code @Tool} 参数暴露给 LLM），
 * 避免跨知识库串答。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class KnowledgeBaseSearchTool {

    private final RagService ragService;

    @Tool(description = "在企业知识库中检索内部文档。涉及已上传的产品手册、规范、内部资料时使用。参数 query 为检索关键词或问题。")
    public ToolResult searchKnowledgeBase(String query) {
        UUID kbId = KnowledgeBaseContext.get();
        log.debug("[kb_search] query='{}', kbId={}", query, kbId);
        long start = System.currentTimeMillis();
        List<RetrievalResult> results = ragService.retrieve(query, kbId);
        long duration = System.currentTimeMillis() - start;

        String content = results.stream()
                .map(RetrievalResult::content)
                .collect(Collectors.joining("\n\n"));
        String source = results.stream()
                .map(RetrievalResult::fileName)
                .distinct()
                .collect(Collectors.joining(", "));

        log.info("[kb_search] 命中 {} 条, 耗时 {}ms, query='{}'", results.size(), duration, query);
        return new ToolResult("kb_search", content, source, duration);
    }
}
