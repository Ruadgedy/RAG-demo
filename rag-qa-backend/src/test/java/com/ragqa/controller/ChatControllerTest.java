package com.ragqa.controller;

import com.ragqa.config.JwtAuthenticationFilter;
import com.ragqa.config.SecurityConfig;
import com.ragqa.dto.ChatRequest;
import com.ragqa.service.ChatService;
import com.ragqa.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * ChatController MockMvc 测试
 */
@WebMvcTest(ChatController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class ChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ChatService chatService;

    @MockBean
    private UserService userService;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        // 模拟已认证用户
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken("testuser", null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    void shouldChatSuccessfully() throws Exception {
        ChatRequest request = new ChatRequest();
        request.setMessage("什么是 RAG？");
        request.setKnowledgeBaseId(UUID.randomUUID());

        when(chatService.chat(any(ChatRequest.class))).thenReturn("RAG 是检索增强生成...");

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(content().string("RAG 是检索增强生成..."));
    }

    @Test
    void shouldReturnBadRequestWhenMessageMissing() throws Exception {
        ChatRequest request = new ChatRequest();
        // message 缺失
        request.setKnowledgeBaseId(UUID.randomUUID());

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldReturnBadRequestWhenKnowledgeBaseIdMissing() throws Exception {
        ChatRequest request = new ChatRequest();
        request.setMessage("什么是 RAG？");
        // knowledgeBaseId 缺失

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }
}
