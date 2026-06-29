package com.ragqa.service;

import com.ragqa.dto.ChatRequest;
import com.ragqa.dto.ChatResponse;
import com.ragqa.repository.ChatHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import reactor.core.publisher.Flux;

import java.util.Collections;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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

    private ChatService chatService;

    @BeforeEach
    void setUp() {
        chatService = new ChatService(ragService, chatClientBuilder, chatHistoryRepository);
    }

    @Test
    void shouldReturnAnswerFromRagService() {
        UUID kbId = UUID.randomUUID();
        ChatRequest request = new ChatRequest();
        request.setMessage("测试问题");
        request.setKnowledgeBaseId(kbId);

        // 【V3】RagService.chat() 现在返回 ChatResult(answer, retrievedDocs, retrievalDurationMs)
        // 【2026-06-29 P0-02】RagService.chat 新增 history 参数
        when(ragService.chat(eq("测试问题"), eq(kbId), any()))
                .thenReturn(new com.ragqa.service.RagService.ChatResult(
                        "这是测试回答", java.util.List.of(), 0L));

        ChatResponse result = chatService.chat(request);

        assertThat(result.getAnswer()).isEqualTo("这是测试回答");
        assertThat(result.getSessionId()).isNotNull();
        // 【V3】一个回合 = 一条记录（query + content + rag_metadata）
        verify(chatHistoryRepository, times(1)).saveAndFlush(any());
        verify(ragService).chat(eq("测试问题"), eq(kbId), any());
    }

    @Test
    void shouldReturnFallbackWhenNoDocuments() {
        UUID kbId = UUID.randomUUID();
        ChatRequest request = new ChatRequest();
        request.setMessage("测试问题");
        request.setKnowledgeBaseId(kbId);

        // 【V3】RagService.chat() 返回 ChatResult
        when(ragService.chat(eq("测试问题"), eq(kbId), any()))
                .thenReturn(new com.ragqa.service.RagService.ChatResult(
                        "该知识库暂无文档，请先上传文档。", java.util.List.of(), 0L));

        ChatResponse result = chatService.chat(request);

        assertThat(result.getAnswer()).contains("暂无文档");
    }
}
