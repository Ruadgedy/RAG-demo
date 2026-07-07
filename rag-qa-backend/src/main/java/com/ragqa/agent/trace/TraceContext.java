package com.ragqa.agent.trace;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Agent trace 上下文（Agentic RAG F21）。
 *
 * <p>agent loop 期间持有当前 {@code chatId} 与本轮 tool 调用计数器，
 * 供 {@link KnowledgeBaseSearchTool} / {@code WebSearchTool} / {@code DirectAnswerTool}
 * 取用，写 {@link AgentTrace}。用 ThreadLocal 而非 tool 参数，避免把 chatId 暴露给 LLM
 * （与 {@code KnowledgeBaseContext} 同模式）。
 *
 * <p>使用：进入 agentic 问答前 {@link #set(String)}，结束后 {@link #clear()}。
 * 工具内部自增 round：{@link #nextRound()}。
 */
public final class TraceContext {

    private static final ThreadLocal<String> CHAT_ID = new ThreadLocal<>();
    private static final ThreadLocal<AtomicInteger> ROUND = new ThreadLocal<>();

    private TraceContext() {
    }

    public static void set(String chatId) {
        CHAT_ID.set(chatId);
        ROUND.set(new AtomicInteger(0));
    }

    public static String getChatId() {
        return CHAT_ID.get();
    }

    /**
     * 自增并返回新轮次（从 1 开始）。
     */
    public static int nextRound() {
        AtomicInteger r = ROUND.get();
        if (r == null) {
            r = new AtomicInteger(0);
            ROUND.set(r);
        }
        return r.incrementAndGet();
    }

    public static void clear() {
        CHAT_ID.remove();
        ROUND.remove();
    }
}
