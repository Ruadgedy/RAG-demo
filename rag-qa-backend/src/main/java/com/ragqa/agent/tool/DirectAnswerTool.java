package com.ragqa.agent.tool;

import com.ragqa.agent.trace.AgentTraceCollector;
import com.ragqa.agent.trace.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 直答工具（Agentic RAG, F18 + F21）。
 *
 * <p>处理闲聊、寒暄、通用常识类问题——这类问题无需检索知识库或网络，
 * 调用此 tool 后 LLM 基于自身通用知识直接回答，省检索开销与 token。
 *
 * <p>返回引导提示，告知 LLM "无需检索，直接回答"。
 *
 * <p>F21：调用前后各记一条 trace，chatId 从 {@link TraceContext} 取，round 自增。
 */
@Component
@Slf4j
public class DirectAnswerTool {

    private final AgentTraceCollector traceCollector;

    public DirectAnswerTool(AgentTraceCollector traceCollector) {
        this.traceCollector = traceCollector;
    }

    @Tool(description = "直接回答闲聊、寒暄、通用常识类问题。当问题不涉及企业知识库内部资料或最新外部信息时使用，无需检索。")
    public ToolResult directAnswer(String question) {
        String chatId = TraceContext.getChatId();
        int round = TraceContext.nextRound();
        Map<String, Object> args = Map.of("question", question);

        if (chatId != null) {
            traceCollector.record(chatId, round, "direct_answer", args, null, 0, "start");
        }
        long start = System.currentTimeMillis();
        log.debug("[direct_answer] round={}, question='{}'", round, question);
        String content = "这是一个闲聊或常识类问题。无需检索知识库或网络，请直接基于你的通用知识回答。";
        long duration = System.currentTimeMillis() - start;

        if (chatId != null) {
            traceCollector.record(chatId, round, "direct_answer", args,
                    "闲聊/常识，无需检索", (int) duration, "done");
        }
        return new ToolResult("direct_answer", content, "direct", duration);
    }
}
