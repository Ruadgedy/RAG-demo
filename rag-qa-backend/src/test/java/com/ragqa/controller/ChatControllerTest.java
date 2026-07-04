package com.ragqa.controller;

import com.ragqa.config.JwtAuthenticationFilter;
import com.ragqa.config.SecurityConfig;
import com.ragqa.dto.ChatRequest;
import com.ragqa.dto.ChatResponse;
import com.ragqa.repository.UserRepository;
import com.ragqa.service.ChatService;
import com.ragqa.service.JwtService;
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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
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

    /**
     * JwtAuthenticationFilter 在 SecurityFilterChain 中需要 JwtService Bean。
     * @WebMvcTest 切片不加载 @Service Bean，因此需要显式 Mock。
     */
    @MockBean
    private JwtService jwtService;

    /**
     * SecurityConfig.userDetailsService() 依赖 UserRepository。
     * @WebMvcTest 不加载 JPA Repositories，需 Mock。
     */
    @MockBean
    private UserRepository userRepository;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        // 模拟已认证用户。Spring Security 6 的 SecurityContextHolderFilter
        // 不再从 ThreadLocal 读取 SecurityContext，必须通过 .with(authentication(...))
        // 显式传给 MockMvc 请求。
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken("testuser", null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    void shouldChatSuccessfully() throws Exception {
        ChatRequest request = new ChatRequest();
        request.setMessage("什么是 RAG？");
        request.setKnowledgeBaseId(UUID.randomUUID());

        when(chatService.chat(any(ChatRequest.class)))
                .thenReturn(new ChatResponse("conv-123", "chat-123", "RAG 是检索增强生成...", List.of()));

        mockMvc.perform(post("/api/chat")
                        .with(authentication(new UsernamePasswordAuthenticationToken(
                                "testuser", null, List.of())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId").value("conv-123"))
                .andExpect(jsonPath("$.answer").value("RAG 是检索增强生成..."));
    }

    @Test
    void shouldReturnBadRequestWhenMessageMissing() throws Exception {
        ChatRequest request = new ChatRequest();
        // message 缺失
        request.setKnowledgeBaseId(UUID.randomUUID());

        mockMvc.perform(post("/api/chat")
                        .with(authentication(new UsernamePasswordAuthenticationToken(
                                "testuser", null, List.of())))
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
                        .with(authentication(new UsernamePasswordAuthenticationToken(
                                "testuser", null, List.of())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }
}
