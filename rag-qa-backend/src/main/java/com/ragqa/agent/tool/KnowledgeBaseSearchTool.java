package com.ragqa.agent.tool;

import com.ragqa.agent.trace.AgentTraceCollector;
import com.ragqa.agent.trace.TraceContext;
import com.ragqa.service.RagService;
import com.ragqa.service.RagService.RetrievalResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 知识库检索工具（Agentic RAG, F17 + F21）。
 *
 * <p>包装 {@link RagService#retrieve(String, UUID)}，复用现有召回 + rerank + fallback 链路。
 * agent 通过 tool-calling 自主决定何时检索。
 *
 * <p>kbId 从 {@link KnowledgeBaseContext} 注入（不作为 {@code @Tool} 参数暴露给 LLM），
 * 避免跨知识库串答。
 *
 * <p>F21：调用前后各记一条 trace（{@code status=start} / {@code status=done}），
 * chatId 从 {@link TraceContext} 取，round 自增。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class KnowledgeBaseSearchTool {

    private final RagService ragService;
    private final AgentTraceCollector traceCollector;

    @Tool(description = "在企业知识库中检索内部文档。涉及已上传的产品手册、规范、内部资料时使用。参数 query 为检索关键词或问题。")
    public ToolResult searchKnowledgeBase(String query) {
        String chatId = TraceContext.getChatId();
        int round = TraceContext.nextRound();
        Map<String, Object> args = Map.of("query", query);

        if (chatId != null) {
            traceCollector.record(chatId, round, "kb_search", args, null, 0, "start");
        }
        long start = System.currentTimeMillis();
        UUID kbId = KnowledgeBaseContext.get();
        log.debug("[kb_search] round={}, query='{}', kbId={}", round, query, kbId);
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

        if (chatId != null) {
            String summary = "命中 " + results.size() + " 条" +
                    (source.isBlank() ? "" : "；来源=" + source);
            traceCollector.record(chatId, round, "kb_search", args, summary, (int) duration, "done");
        }
        return new ToolResult("kb_search", content, source, duration);
    }
}
