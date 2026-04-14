package com.ragqa.service;

import com.ragqa.dto.ChatRequest;
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

    private ChatService chatService;

    @BeforeEach
    void setUp() {
        chatService = new ChatService(ragService, chatClientBuilder);
    }

    @Test
    void shouldReturnAnswerFromRagService() {
        UUID kbId = UUID.randomUUID();
        ChatRequest request = new ChatRequest();
        request.setMessage("测试问题");
        request.setKnowledgeBaseId(kbId);

        when(ragService.chat("测试问题", kbId)).thenReturn("这是测试回答");

        String result = chatService.chat(request);

        assertThat(result).isEqualTo("这是测试回答");
        verify(ragService).chat("测试问题", kbId);
    }

    @Test
    void shouldReturnFallbackWhenNoDocuments() {
        UUID kbId = UUID.randomUUID();
        ChatRequest request = new ChatRequest();
        request.setMessage("测试问题");
        request.setKnowledgeBaseId(kbId);

        when(ragService.chat("测试问题", kbId)).thenReturn("该知识库暂无文档，请先上传文档。");

        String result = chatService.chat(request);

        assertThat(result).contains("暂无文档");
    }
}
