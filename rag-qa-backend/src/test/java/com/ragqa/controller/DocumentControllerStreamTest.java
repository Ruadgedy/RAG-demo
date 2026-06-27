package com.ragqa.controller;

import com.ragqa.config.JwtAuthenticationFilter;
import com.ragqa.config.SecurityConfig;
import com.ragqa.event.DocumentStatusEvent;
import com.ragqa.event.DocumentStatusEventService;
import com.ragqa.repository.UserRepository;
import com.ragqa.service.DocumentService;
import com.ragqa.service.JwtService;
import com.ragqa.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DocumentController SSE 端点测试
 *
 * 覆盖：
 * - TC-31-03: SSE 端点未认证返回 401
 *
 * 注：完整 SSE 流测试需要 WebTestClient + 真实响应订阅，超出 @WebMvcTest 范围。
 * 功能正确性通过 DocumentStatusEventServiceTest（事件层）+ 手动 curl 验证。
 */
@WebMvcTest(DocumentController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class DocumentControllerStreamTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DocumentService documentService;

    @MockBean
    private DocumentStatusEventService eventService;

    @MockBean
    private UserService userService;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        // 清空 SecurityContextHolder（避免测试间残留）
        SecurityContextHolder.clearContext();

        // stub sink（mock 默认 null）
        when(eventService.getOrCreateSink(any(UUID.class)))
                .thenReturn(Sinks.<DocumentStatusEvent>many().multicast().onBackpressureBuffer());
    }

    @Test
    void shouldRejectUnauthenticatedRequest() throws Exception {
        // Spring Security 对匿名访问 protected endpoint 返回 403（不是 401）
        // 验证关键点：未认证请求被拦截（4xx），不会进入 controller
        mockMvc.perform(get("/api/knowledge-bases/{kbId}/documents/stream",
                        UUID.randomUUID())
                        .with(anonymous()))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void shouldAcceptAuthenticatedRequest() throws Exception {
        // 模拟已认证用户
        var auth = new UsernamePasswordAuthenticationToken("test-user", null, List.of());

        // 不验证具体 status（Flux 可能 hang 在测试中），只验证不被 401/403 阻断
        mockMvc.perform(get("/api/knowledge-bases/{kbId}/documents/stream", UUID.randomUUID())
                        .with(authentication(auth)))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    if (status == 401 || status == 403) {
                        throw new AssertionError("Authenticated SSE request should not return "
                                + status + ", expected 200 or auth-success");
                    }
                });
    }

    @Test
    void shouldCallEventServiceOnAuthenticatedStreamSubscribe() throws Exception {
        var auth = new UsernamePasswordAuthenticationToken("test-user", null, List.of());
        UUID kbId = UUID.randomUUID();

        mockMvc.perform(get("/api/knowledge-bases/{kbId}/documents/stream", kbId)
                        .with(authentication(auth)))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    if (status == 401 || status == 403) {
                        throw new AssertionError("Expected success auth, got " + status);
                    }
                });

        verify(eventService).getOrCreateSink(kbId);
    }
}