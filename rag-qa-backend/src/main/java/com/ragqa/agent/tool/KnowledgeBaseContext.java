package com.ragqa.agent.tool;

import java.util.UUID;

/**
 * 知识库上下文（Agentic RAG, F17）。
 *
 * <p>agent loop 期间持有当前 {@code knowledgeBaseId}，供 {@link KnowledgeBaseSearchTool} 取用。
 * 用 ThreadLocal 而非 tool 参数，避免把 kbId 暴露给 LLM（防止 LLM 瞎填导致跨库检索）。
 *
 * <p>使用：进入 agentic 问答前 {@link #set(UUID)}，结束后 {@link #clear()}。
 */
public final class KnowledgeBaseContext {

    private static final ThreadLocal<UUID> KB_ID = new ThreadLocal<>();

    private KnowledgeBaseContext() {
    }

    public static void set(UUID kbId) {
        KB_ID.set(kbId);
    }

    public static UUID get() {
        return KB_ID.get();
    }

    public static void clear() {
        KB_ID.remove();
    }
}
