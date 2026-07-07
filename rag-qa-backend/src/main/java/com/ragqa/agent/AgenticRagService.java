package com.ragqa.agent;

import com.ragqa.agent.tool.DirectAnswerTool;
import com.ragqa.agent.tool.KnowledgeBaseContext;
import com.ragqa.agent.tool.KnowledgeBaseSearchTool;
import com.ragqa.agent.tool.WebSearchTool;
import com.ragqa.dto.ChatMessage;
import com.ragqa.service.RagService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * Agentic RAG 服务（Agentic RAG, F19）。
 *
 * <p>LLM 作为 controller，通过 Spring AI {@code tool-calling} 自主编排工具（KB检索/Web搜索/直答）。
 * 使用 {@link MessageChatMemoryAdvisor} 累积每轮 messages（含 tool 调用结果），
 * LLM 最终基于累积 context 生成回答，无需手写 memory 逻辑。
 *
 * <p>总超时兜底：CompletableFuture 总超时（默认 30s）触发 cancel(true) + 降级 linear RAG，
 * 防止 agent loop 死循环或 tool 调用过慢拖垮流式首字延迟。
 *
 * <p>kbId 通过 {@link KnowledgeBaseContext}（ThreadLocal）注入 tool，不暴露给 LLM。
 */
@Service
@Slf4j
public class AgenticRagService {

    private final ChatClient.Builder chatClientBuilder;
    private final RagService ragService;
    private final KnowledgeBaseSearchTool kbTool;
    private final WebSearchTool webTool;
    private final DirectAnswerTool directTool;

    @Value("${rag.agent.timeout-ms:30000}")
    private long timeoutMs;

    @Value("${rag.agent.model:${OPENAI_MODEL:MiniMax-M3}}")
    private String agentModel;

    private final ExecutorService executor = new ThreadPoolExecutor(
            4, 16, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(32),
            r -> { Thread t = new Thread(r, "agentic-rag"); t.setDaemon(true); return t; },
            new ThreadPoolExecutor.AbortPolicy()
    );

    private final ThreadLocal<Boolean> degraded = ThreadLocal.withInitial(() -> false);

    public AgenticRagService(
            ChatClient.Builder chatClientBuilder,
            RagService ragService,
            KnowledgeBaseSearchTool kbTool,
            WebSearchTool webTool,
            DirectAnswerTool directTool) {
        this.chatClientBuilder = chatClientBuilder;
        this.ragService = ragService;
        this.kbTool = kbTool;
        this.webTool = webTool;
        this.directTool = directTool;
    }

    /**
     * Agentic RAG 问答（非流式）。
     * LLM 自主调用 tool（KB/Web/直答），累积 tool 结果后生成最终回答。
     */
    public RagService.ChatResult chat(String message, UUID knowledgeBaseId,
                                       List<ChatMessage> history, int historyWindow) {
        KnowledgeBaseContext.set(knowledgeBaseId);
        degraded.set(false);
        CompletableFuture<RagService.ChatResult> future = null;
        try {
            future = CompletableFuture.supplyAsync(
                    () -> {
                        long start = System.currentTimeMillis();
                        String response = doAgenticChat(message, history, historyWindow);
                        long duration = System.currentTimeMillis() - start;
                        return new RagService.ChatResult(response, List.of(), duration, message);
                    },
                    executor);
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            if (future != null) future.cancel(true);
            log.warn("[agentic] 总超时 ({}ms)，降级 linear RAG", timeoutMs);
            degraded.set(true);
            return ragService.chat(message, knowledgeBaseId, history, historyWindow);
        } catch (Exception e) {
            log.warn("[agentic] agent loop 异常 ({})，降级 linear RAG", e.getMessage());
            degraded.set(true);
            return ragService.chat(message, knowledgeBaseId, history, historyWindow);
        } finally {
            KnowledgeBaseContext.clear();
            degraded.remove();
        }
    }

    /**
     * Agentic RAG 流式检索入口。
     *
     * agent loop 检索 → 累积 tool 结果 → 构建含 context 的 prompt → 返回，
     * SSE 流式生成由调用方在 RagService 层做。
     */
    public RagService.ChatResult retrieveForStreaming(String message, UUID knowledgeBaseId,
                                                      List<ChatMessage> history, int historyWindow) {
        KnowledgeBaseContext.set(knowledgeBaseId);
        degraded.set(false);
        try {
            String toolContext = doAgenticChat(message, history, historyWindow);
            String context = toolContext.isBlank()
                    ? "（无检索结果，请基于通用知识诚实回答）"
                    : "=== Agent 检索结果 ===\n" + toolContext;
            log.info("[agentic:stream] tool 检索完成，context 字数={}", context.length());
            return new RagService.ChatResult(null, List.of(), 0L, context);
        } catch (Exception e) {
            log.warn("[agentic:stream] agentic 检索异常，降级: {}", e.getMessage());
            degraded.set(true);
            return ragService.retrieveForStreaming(message, knowledgeBaseId, history, historyWindow);
        } finally {
            KnowledgeBaseContext.clear();
            degraded.remove();
        }
    }

    /**
     * tool-calling loop。
     *
     * <p>框架自动处理：
     * LLM 返回 tool_call → 框架执行 @Tool → 结果加到 memory → LLM 再推理……
     * 直到 LLM 不再请求 tool。{@code internalToolExecutionEnabled=true} 启用此行为。
     *
     * <p>调用链：ChatClientRequestSpec.advisors(Advisor...) → MessageChatMemoryAdvisor
     * 在 before() 把 memory 中 messages 加到 prompt，
     * after() 把 LLM 返回加回 memory（tool 结果也在此阶段存入）。
     *
     * <p>{@link ToolCallingChatOptions} 有 internalToolExecutionEnabled；
     * 通过 ChatOptions 的 Builder 向下转型或直接 build ToolCallingChatOptions。
     */
    private String doAgenticChat(String message, List<ChatMessage> history, int historyWindow) {
        String conversationId = UUID.randomUUID().toString();

        MessageWindowChatMemory memory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(50)
                .build();

        // 注入 history 到 memory（LLM 看到历史上下文）
        if (history != null && !history.isEmpty()) {
            int n = Math.min(history.size(), historyWindow * 2);
            int start = history.size() - n;
            for (int i = start; i < history.size(); i++) {
                ChatMessage m = history.get(i);
                String role = m.getRole();
                if ("user".equals(role)) {
                    memory.add(conversationId, List.of(new UserMessage(m.getContent())));
                } else {
                    memory.add(conversationId, List.of(new AssistantMessage(m.getContent())));
                }
            }
        }

        MessageChatMemoryAdvisor memoryAdvisor = MessageChatMemoryAdvisor.builder(memory)
                .conversationId(conversationId)
                .build();

        String systemPrompt = buildSystemPrompt();

        try {
            String response = chatClientBuilder.build()
                    .prompt(systemPrompt)
                    .user(message)
                    .advisors(memoryAdvisor)                          // Advisor 在 before/after 自动管理 memory
                    .tools(kbTool, webTool, directTool)             // @Tool 方法
                    .options(ToolCallingChatOptions.builder()          // ToolCallingChatOptions 有 internalToolExecutionEnabled
                            .model(agentModel)
                            .internalToolExecutionEnabled(true)         // 框架自动执行 @Tool
                            .build())
                    .call()
                    .content();

            log.info("[agentic] tool loop 完成，LLM 最终回答字数={}",
                    response == null ? 0 : response.length());
            return response != null ? response : "";

        } catch (Exception e) {
            log.warn("[agentic:loop] tool-calling loop 异常: {}", e.getMessage());
            throw e;
        }
    }

    private String buildSystemPrompt() {
        return """
                你是一个专业的智能问答助手，擅长使用工具来回答问题。

                可用工具：
                - searchKnowledgeBase(query): 在企业知识库检索内部文档。涉及产品手册，规范、内部资料时优先使用。
                - searchWeb(query): 搜索互联网获取最新或外部信息。涉及时效性、公开信息时使用。
                - directAnswer(question): 直接回答闲聊，寒暄、通用常识类问题。

                要求：
                1. 只基于工具返回的信息回答，不要编造信息
                2. 必要时可连续调用多个工具（如 KB 检索后再 Web 搜索对比）
                3. 工具返回空时应诚实说明，不要臆造
                4. 引用工具结果时使用【来源】标注
                """;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
