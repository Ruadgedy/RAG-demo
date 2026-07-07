package com.ragqa.agent.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * 直答工具（Agentic RAG, F18）。
 *
 * <p>处理闲聊、寒暄、通用常识类问题——这类问题无需检索知识库或网络，
 * 调用此 tool 后 LLM 基于自身通用知识直接回答，省检索开销与 token。
 *
 * <p>返回引导提示，告知 LLM "无需检索，直接回答"。
 */
@Component
@Slf4j
public class DirectAnswerTool {

    @Tool(description = "直接回答闲聊、寒暄、通用常识类问题。当问题不涉及企业知识库内部资料或最新外部信息时使用，无需检索。")
    public ToolResult directAnswer(String question) {
        long start = System.currentTimeMillis();
        String content = "这是一个闲聊或常识类问题。无需检索知识库或网络，请直接基于你的通用知识回答。";
        long duration = System.currentTimeMillis() - start;
        log.debug("[direct_answer] question='{}'", question);
        return new ToolResult("direct_answer", content, "direct", duration);
    }
}
