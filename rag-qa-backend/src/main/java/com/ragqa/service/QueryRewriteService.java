package com.ragqa.service;

import com.ragqa.dto.ChatMessage;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * 查询改写服务（LLM Rewrite）
 *
 * 把多轮对话中的用户当前问题改写为独立、可直接用于向量检索的查询，
 * 解决代词指代("它"、"那个"、"前一条")和上下文缺失问题。
 *
 * 三种模式（由 {@code rag.query.rewrite.mode} 控制）：
 * <ul>
 *   <li>llm    — 默认。调 LLM 改写；失败/超时降级到 simple 拼接</li>
 *   <li>simple — 直接用空格拼接最近 N 轮 user 提问 + 当前提问（老逻辑）</li>
 *   <li>none   — 不改写，原 query 返回（首轮等无历史场景等价于 none）</li>
 * </ul>
 *
 * 触发条件：history 非空时才执行改写；首轮（无历史）直接返回原 query，零开销。
 *
 * <p>【2026-07-03 调整】改写模型用 MiniMax-M2.7（云端，延迟稳定），通过 ChatOptions.model 单独指定，
 * 不影响主回答模型。三层超时保护：弹性线程池(8-32) + future.get(4500ms) 软超时 + cancel(true)。
 *
 * <p>异步 + 超时：LLM 调用走独立线程池（弹性 8-32）+ Future.get(timeout)，超时后 cancel 释放线程，
 * 避免阻塞主流程或拖垮 SSE 首字延迟。
 *
 * @since 2026-07-02 增量：替换 RagService.rewriteQueryWithHistory() 的简单拼接
 */
@Service
@Slf4j
public class QueryRewriteService {

    private final ChatClient.Builder chatClientBuilder;

    /** 改写模式：llm | simple | none */
    @Value("${rag.query.rewrite.mode:llm}")
    private String mode;

    /** LLM 改写超时（毫秒）；超时后降级到 simple 拼接 */
    @Value("${rag.query.rewrite.timeout-ms:10000}")
    private long timeoutMs;

    /** 改写温度（0 = 完全确定性，避免同 query 改写出不同结果影响检索一致性） */
    @Value("${rag.query.rewrite.temperature:0.0}")
    private double temperature;

    /** 改写输出 max tokens（query 改写一般几十字，64 已足够） */
    @Value("${rag.query.rewrite.max-tokens:500}")
    private int maxTokens;

    /** 改写专用历史窗口：改写只需消解指代，2 轮足够；独立于 rag.history.turns（后者还用于 prompt 注入）。
     *  prompt 更小 → LLM 更快，降低超时概率。buildPrompt 取 min(historyWindow, 本值)。 */
    @Value("${rag.query.rewrite.history-window:2}")
    private int rewriteHistoryWindow;

    /** 改写专用模型（MiniMax-M2.7），通过 ChatOptions.model 覆盖全局 chat 模型，不影响主回答 */
    @Value("${rag.query.rewrite.model:MiniMax-M2.7}")
    private String rewriteModel;

    /**
     * 独立线程池，避免 LLM 改写阻塞 Tomcat 工作线程。
     *
     * <p>【2026-07-03 修复】原 newFixedThreadPool(2) 在「超时未 cancel」时被前几轮慢调用占死，
     * 导致同一对话第 3、4 轮起持续超时降级。现改为弹性池：
     * <ul>
     *   <li>核心 8 / 最大 32 / 有界队列 64：扩容抗偶发线程占用</li>
     *   <li>AbortPolicy：队列满抛 RejectedExecutionException → 被 {@code catch(Exception)} 兜底降级 simple，
     *       不静默丢弃（DiscardPolicy 会让 future 永不完成、白等 timeoutMs）</li>
     * </ul>
     */
    private final ExecutorService executor = new ThreadPoolExecutor(
            8, 32, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(64),
            r -> { Thread t = new Thread(r, "query-rewrite"); t.setDaemon(true); return t; },
            new ThreadPoolExecutor.AbortPolicy()
    );

    private static final String REWRITE_PROMPT = """
            你是一个 RAG 检索查询改写助手。任务是把多轮对话中的用户最新问题改写为独立、可直接用于向量检索的查询。

            要求:
            1. 解决代词指代（"它"、"那个"、"前一条"等），用上文中的具体实体替换
            2. 整合多轮上下文，使改写后的查询语义完整、无需对话历史也能理解
            3. 严格保留用户原始意图，不臆测新信息、不补充答案
            4. 只输出改写后的查询本身，单行，不含任何前缀/引号/标点包装/解释

            输入:
            === 对话历史（最近 %d 轮）===
            %s
            === 用户最新问题 ===
            %s

            输出（仅一行，改写后的查询）:
            """;

    public QueryRewriteService(ChatClient.Builder chatClientBuilder) {
        this.chatClientBuilder = chatClientBuilder;
    }

    /**
     * 改写入口
     *
     * @param currentMessage 用户当前消息
     * @param history        对话历史（user/assistant 交错）；为空时直接返回 currentMessage
     * @param historyWindow  改写引入的历史轮数（与 Conversation.historyWindow 对齐）
     * @return 改写后的检索 query
     */
    public String rewrite(String currentMessage, List<ChatMessage> history, int historyWindow) {
        // 首轮 / 无历史：直接返回，跳过 LLM 节省延迟
        if (history == null || history.isEmpty()) {
            return currentMessage;
        }

        return switch (mode) {
            case "none" -> currentMessage;
            case "simple" -> simpleConcat(currentMessage, history, historyWindow);
            default -> llmRewriteOrFallback(currentMessage, history, historyWindow);
        };
    }

    /**
     * LLM 改写，失败/超时降级到 simple 拼接。
     * 任一异常路径都返回可用结果，主流程不被中断。
     */
    private String llmRewriteOrFallback(String cur, List<ChatMessage> history, int window) {
        String prompt = buildPrompt(cur, history, window);
        long start = System.currentTimeMillis();

        CompletableFuture<String> future = null;
        try {
            future = CompletableFuture.supplyAsync(
                    () -> chatClientBuilder.build()
                            .prompt(prompt)
                            .options(ChatOptions.builder()
                                    .model(rewriteModel)
                                    .temperature(temperature)
                                    .maxTokens(maxTokens)
                                    .build())
                            .call()
                            .content(),
                    executor);

            String raw = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            String cleaned = clean(raw);
            if (cleaned.isBlank()) {
                throw new IllegalStateException("LLM 返回空字符串");
            }
            log.info("[query-rewrite] llm ok ({}ms): '{}' → '{}'",
                    System.currentTimeMillis() - start, cur, cleaned);
            return cleaned;

        } catch (TimeoutException e) {
            // 关键：超时后 cancel，尝试中断底层调用，避免被占线程继续阻塞
            // （旧实现漏掉这步 → 2 线程池被前几轮慢调用占死，后续持续超时降级）
            if (future != null) {
                future.cancel(true);
            }
            log.warn("[query-rewrite] LLM 改写超时 ({}ms 预算)，降级到 simple 拼接", timeoutMs);
            return simpleConcat(cur, history, window);
        } catch (Exception e) {
            // ExecutionException(Ollama 调用异常) / RejectedExecutionException(队列满 AbortPolicy) 等统一降级
            log.warn("[query-rewrite] LLM 改写失败 ({}),降级到 simple 拼接", e.getMessage());
            return simpleConcat(cur, history, window);
        }
    }

    /**
     * 简单拼接（fallback + simple 模式共用）
     * 拼接最近 N 轮 user 提问 + 当前提问，空格分隔。
     */
    String simpleConcat(String cur, List<ChatMessage> history, int window) {
        List<String> recent = new ArrayList<>();
        for (int i = history.size() - 1; i >= 0 && recent.size() < window; i--) {
            ChatMessage m = history.get(i);
            if ("user".equals(m.getRole()) && m.getContent() != null && !m.getContent().isBlank()) {
                recent.add(0, m.getContent());
            }
        }
        if (recent.isEmpty()) return cur;

        StringBuilder sb = new StringBuilder();
        for (String msg : recent) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(msg);
        }
        sb.append(" ").append(cur);
        return sb.toString();
    }

    /**
     * 构造 LLM 改写 prompt
     * 取最近 historyWindow * 2 条消息（user + assistant 各一轮）
     * 单条截断 300 字符防 prompt 过大
     */
    private String buildPrompt(String cur, List<ChatMessage> history, int window) {
        // 改写专用窗口：取 min(传入窗口, 改写窗口)。改写不需要完整历史，2 轮够消解指代，
        // prompt 更小 → LLM 更快，降低超时概率。
        int w = Math.min(window, rewriteHistoryWindow);
        StringBuilder historySection = new StringBuilder();
        int n = Math.min(history.size(), w * 2);
        int start = history.size() - n;
        for (int i = start; i < history.size(); i++) {
            ChatMessage m = history.get(i);
            String role = "user".equals(m.getRole()) ? "用户" : "助手";
            String content = m.getContent() == null ? "" : truncate(m.getContent(), 300);
            historySection.append(role).append(": ").append(content).append("\n");
        }
        return REWRITE_PROMPT.formatted(w, historySection.toString(), cur);
    }

    /**
     * 清理 LLM 输出：
     * - 去掉首尾空白
     * - 去掉首尾常见包装（引号、书名号、句号）
     * - 只取第一行（防 LLM 输出"改写后: ..."多行格式）
     */
    static String clean(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        // 兼容 MiniMax M2 reasoning 模型：<think>...</think> 包裹思考过程，实际答案在 </think> 之后
        int thinkEnd = s.indexOf("</think>");
        if (thinkEnd >= 0) {
            s = s.substring(thinkEnd + "</think>".length()).trim();
        }
        // 去首尾成对/单个的引号、书名号、中文句末标点
        s = s.replaceAll("^[\\\"'“‘\"「『《]+", "")
             .replaceAll("[\\\"'”’\"」』》'。.\\!?！？]+$", "")
             .trim();
        int nl = s.indexOf('\n');
        if (nl > 0) s = s.substring(0, nl).trim();
        return s;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}