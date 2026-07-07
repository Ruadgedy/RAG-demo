package com.ragqa.agent.tool;

/**
 * Tool 统一返回结果（Agentic RAG, F17）。
 *
 * <p>所有 {@code @Tool} 方法返回此 record，便于 agent trace 落库 + LLM 格式统一。
 *
 * @param toolName   工具名（如 kb_search / web_search / direct_answer）
 * @param content    工具返回的主要内容（供 LLM 参考）
 * @param source     来源标识（如文件名、URL，供引用展示）
 * @param durationMs 工具执行耗时（毫秒，供 trace 落库）
 */
public record ToolResult(String toolName, String content, String source, long durationMs) {
}
