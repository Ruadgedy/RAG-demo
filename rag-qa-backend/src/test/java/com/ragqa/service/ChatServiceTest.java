package com.ragqa.service;

import com.ragqa.dto.ChatRequest;
import com.ragqa.dto.ChatResponse;
import com.ragqa.model.Conversation;
import com.ragqa.agent.AgenticRagService;
import com.ragqa.agent.trace.AgentTraceCollector;
import com.ragqa.repository.ChatHistoryRepository;
import com.ragqa.repository.ConversationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import reactor.core.publisher.Flux;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * ChatService 单元测试
 *
 * 注意：这些测试使用 Mockito 模拟依赖项
 * 完整的集成测试需要实际的 LLM API
 */
@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    @Mock
    private RagService ragService;

    @Mock
    private ChatClient.Builder chatClientBuilder;

    @Mock
    private ChatHistoryRepository chatHistoryRepository;

    /**
     * 【2026-06-30 V6】Conversation 对话组模型重构后新增的依赖
     */
    @Mock
    private ConversationRepository conversationRepository;

    @Mock
    private AgenticRagService agenticRagService;

    /**
     * 【2026-07-07 F21】Agent trace 落库服务（线性 chat 路径不触发，留 mock 防 NPE）
     */
    @Mock
    private AgentTraceCollector agentTraceCollector;

    private ChatService chatService;

    @BeforeEach
    void setUp() {
        chatService = new ChatService(ragService, chatClientBuilder, chatHistoryRepository, conversationRepository, agentTraceCollector, agenticRagService);

        // 设置认证上下文（ChatService.getCurrentUserId 需要）
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        "testuser", null, Collections.emptyList()));

        // ChatService.chat() 走 getOrCreateConversation → save 新对话
        // mock save 行为：自动给 conv 赋 id，并返回
        when(conversationRepository.save(any(Conversation.class)))
                .thenAnswer(inv -> {
                    Conversation conv = inv.getArgument(0);
                    if (conv.getId() == null) {
                        conv.setId(UUID.randomUUID().toString());
                    }
                    return conv;
                });

        // getHistory 调用 findRecentByConversationId（仅在 historyWindow > 0 时调用）
        // ChatService 测试场景下 defaultHistoryWindow 默认为 0，getHistory 走 early return，
        // 故该 stub 在 ChatServiceTest 中可能不被触发。用 lenient 避免严格模式报错。
        lenient().when(chatHistoryRepository.findRecentByConversationId(any(), any()))
                .thenReturn(Collections.emptyList());

        // getNextTurnIndex stub（saveTurn 一定调用）
        when(chatHistoryRepository.getNextTurnIndex(any())).thenReturn(0);

        // saveAndFlush 返回传入的对象，避免 NPE
        lenient().when(chatHistoryRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void shouldReturnAnswerFromRagService() {
        UUID kbId = UUID.randomUUID();
        ChatRequest request = new ChatRequest();
        request.setMessage("测试问题");
        request.setKnowledgeBaseId(kbId);

        // 【V3】RagService.chat() 现在返回 ChatResult(answer, retrievedDocs, retrievalDurationMs, rewrittenQuery)
        // 【2026-06-29 P0-02】RagService.chat 新增 history 参数
        // 【2026-07-02】新增 historyWindow 参数 + rewrittenQuery 字段
        when(ragService.chat(eq("测试问题"), eq(kbId), any(), anyInt()))
                .thenReturn(new com.ragqa.service.RagService.ChatResult(
                        "这是测试回答", List.of(), 0L, "测试问题"));

        ChatResponse result = chatService.chat(request);

        assertThat(result.getAnswer()).isEqualTo("这是测试回答");
        assertThat(result.getConversationId()).isNotNull();
        // 【V3】一个回合 = 一条记录（query + content + rag_metadata）
        verify(chatHistoryRepository, times(1)).saveAndFlush(any());
        verify(ragService).chat(eq("测试问题"), eq(kbId), any(), anyInt());
    }

    @Test
    void shouldReturnFallbackWhenNoDocuments() {
        UUID kbId = UUID.randomUUID();
        ChatRequest request = new ChatRequest();
        request.setMessage("测试问题");
        request.setKnowledgeBaseId(kbId);

        // 【V3】RagService.chat() 返回 ChatResult
        when(ragService.chat(eq("测试问题"), eq(kbId), any(), anyInt()))
                .thenReturn(new com.ragqa.service.RagService.ChatResult(
                        "该知识库暂无文档，请先上传文档。", List.of(), 0L, "测试问题"));

        ChatResponse result = chatService.chat(request);

        assertThat(result.getAnswer()).contains("暂无文档");
    }
}
