package com.ragqa.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragqa.config.JwtAuthenticationFilter;
import com.ragqa.config.SecurityConfig;
import com.ragqa.dto.CreateKnowledgeBaseRequest;
import com.ragqa.dto.KnowledgeBaseResponse;
import com.ragqa.model.KnowledgeBase;
import com.ragqa.service.KnowledgeBaseService;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * KnowledgeBaseController MockMvc 测试
 */
@WebMvcTest(KnowledgeBaseController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class KnowledgeBaseControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private KnowledgeBaseService knowledgeBaseService;

    @MockBean
    private UserService userService;

    @Autowired
    private ObjectMapper objectMapper;

    private KnowledgeBase testKnowledgeBase;
    private CreateKnowledgeBaseRequest createRequest;

    @BeforeEach
    void setUp() {
        // 模拟已认证用户
        UsernamePasswordAuthenticationToken auth = 
            new UsernamePasswordAuthenticationToken("testuser", null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);

        testKnowledgeBase = new KnowledgeBase();
        testKnowledgeBase.setId(UUID.randomUUID());
        testKnowledgeBase.setName("测试知识库");
        testKnowledgeBase.setDescription("这是一个测试知识库");
        testKnowledgeBase.setCreatedAt(LocalDateTime.now());

        createRequest = new CreateKnowledgeBaseRequest();
        createRequest.setName("新知识库");
        createRequest.setDescription("新知识库描述");
    }

    @Test
    void shouldCreateKnowledgeBase() throws Exception {
        when(knowledgeBaseService.create(any(CreateKnowledgeBaseRequest.class)))
                .thenReturn(testKnowledgeBase);

        mockMvc.perform(post("/api/knowledge-bases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("测试知识库"));
    }

    @Test
    void shouldListKnowledgeBases() throws Exception {
        when(knowledgeBaseService.findAll()).thenReturn(List.of(testKnowledgeBase));

        mockMvc.perform(get("/api/knowledge-bases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("测试知识库"));
    }

    @Test
    void shouldGetKnowledgeBaseById() throws Exception {
        UUID kbId = testKnowledgeBase.getId();
        when(knowledgeBaseService.findById(kbId)).thenReturn(testKnowledgeBase);

        mockMvc.perform(get("/api/knowledge-bases/" + kbId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("测试知识库"));
    }

    @Test
    void shouldDeleteKnowledgeBase() throws Exception {
        UUID kbId = testKnowledgeBase.getId();

        mockMvc.perform(delete("/api/knowledge-bases/" + kbId))
                .andExpect(status().isNoContent());
    }
}
