package com.ragqa.controller;

import com.ragqa.event.DocumentStatusEvent;
import com.ragqa.event.DocumentStatusEventService;
import com.ragqa.model.Document;
import com.ragqa.service.DocumentService;
import com.ragqa.service.JwtService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.UUID;

/**
 * 文档控制器
 *
 * 作用：处理文档的上传、查询和删除
 *
 * 接口说明：
 * - POST /api/knowledge-bases/{kbId}/documents: 上传文档到指定知识库
 * - GET /api/knowledge-bases/{kbId}/documents: 获取知识库的所有文档
 * - GET /api/documents/{id}: 获取指定文档详情
 * - DELETE /api/documents/{id}: 删除指定文档
 *
 * 认证要求：
 * - 所有接口需要JWT认证
 *
 * 上传说明：
 * - 支持 multipart/form-data 格式
 * - 文件参数名必须为 "file"
 * - 支持格式：PDF、Word、TXT等（由Apache Tika解析）
 * - 上传后异步处理（解析→切分→向量化）
 *
 * 文档状态：
 * - UPLOADING: 上传中
 * - PARSING: 解析中
 * - CHUNKING: 切分中
 * - EMBEDDING: 向量化中
 * - COMPLETED: 处理完成
 * - FAILED: 处理失败
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "文档", description = "文档管理接口")
public class DocumentController {

    private final DocumentService documentService;
    /** 【2026-06-27 增量】文档状态事件总线 — 用于 SSE 推送 */
    private final DocumentStatusEventService eventService;
    /** JSON 序列化器 */
    private final ObjectMapper objectMapper;
    /** 【2026-06-27 增量】JWT 服务 — SSE 端点鉴权（EventSource 不支持 header） */
    private final JwtService jwtService;

    @Operation(summary = "上传文档", description = "上传文档到指定知识库，系统自动进行解析和向量化")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "上传成功",
                    content = @Content(schema = @Schema(implementation = Document.class))),
            @ApiResponse(responseCode = "400", description = "请求参数错误"),
            @ApiResponse(responseCode = "401", description = "未认证"),
            @ApiResponse(responseCode = "404", description = "知识库不存在")
    })
    @PostMapping("/knowledge-bases/{kbId}/documents")
    public ResponseEntity<?> uploadDocument(
            @Parameter(description = "知识库ID") @PathVariable UUID kbId,
            @Parameter(description = "上传的文件") @RequestParam("file") MultipartFile file) {
        try {
            Document doc = documentService.uploadDocument(kbId, file);
            return ResponseEntity.ok(doc);
        } catch (IllegalArgumentException e) {
            // 【2026-06-30 修复】返回友好的错误信息给前端
            log.warn("文档上传失败: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("UPLOAD_FAILED", e.getMessage()));
        } catch (Exception e) {
            log.error("文档上传异常: {}", e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(new ErrorResponse("INTERNAL_ERROR", "服务器内部错误: " + e.getMessage()));
        }
    }

    /**
     * 【2026-06-30 增量】上传错误响应体
     */
    public record ErrorResponse(String code, String message) {}

    @Operation(summary = "获取文档列表", description = "获取指定知识库中的所有文档")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "获取成功"),
            @ApiResponse(responseCode = "401", description = "未认证"),
            @ApiResponse(responseCode = "404", description = "知识库不存在")
    })
    @GetMapping("/knowledge-bases/{kbId}/documents")
    public ResponseEntity<List<Document>> getDocuments(
            @Parameter(description = "知识库ID") @PathVariable UUID kbId) {
        List<Document> docs = documentService.getDocumentsByKnowledgeBase(kbId);
        return ResponseEntity.ok(docs);
    }

    @Operation(summary = "获取文档详情", description = "根据ID获取指定文档的详细信息")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "获取成功",
                    content = @Content(schema = @Schema(implementation = Document.class))),
            @ApiResponse(responseCode = "401", description = "未认证"),
            @ApiResponse(responseCode = "404", description = "文档不存在")
    })
    @GetMapping("/documents/{id}")
    public ResponseEntity<Document> getDocument(
            @Parameter(description = "文档ID") @PathVariable UUID id) {
        Document doc = documentService.getDocument(id);
        return ResponseEntity.ok(doc);
    }

    @Operation(summary = "删除文档", description = "删除指定文档及其所有关联的切片和向量数据")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "删除成功"),
            @ApiResponse(responseCode = "401", description = "未认证"),
            @ApiResponse(responseCode = "404", description = "文档不存在")
    })
    @DeleteMapping("/documents/{id}")
    public ResponseEntity<Void> deleteDocument(
            @Parameter(description = "文档ID") @PathVariable UUID id) {
        documentService.deleteDocument(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 【2026-06-27 增量】SSE 端点：实时推送文档状态变更
     *
     * 用法：
     * <pre>
     * const es = new EventSource('/api/knowledge-bases/{kbId}/documents/stream');
     * es.addEventListener('doc-status', (e) =&gt; {
     *   const event = JSON.parse(e.data);
     *   console.log(event.documentId, event.status, event.progress);
     * });
     * </pre>
     *
     * 事件格式（每个 SSE message）：
     * <pre>
     * event: doc-status
     * data: {"documentId":"...","knowledgeBaseId":"...","status":"PARSING","progress":30,...}
     * </pre>
     *
     * 鉴权：
     * - 由 SecurityConfig 的 {@code .requestMatchers("/api/**").authenticated()} 统一拦截
     * - 前端 EventSource 携带的 JWT 由 Spring Security 在 filter chain 校验
     *
     * 【已知限制 - 2026-06-27】
     * 浏览器 EventSource API 不支持自定义 header（W3C 规范），
     * 故 token 暂时通过 axios 默认走 localStorage + 自动 Bearer header 的方式不可用。
     * 当前实现：依赖前端在 URL 中携带 token（query param）— 见 SDD §10.4。
     *
     * @param kbId 知识库 ID
     * @return SSE 流
     */
    @Operation(summary = "订阅文档状态变更 (SSE)",
            description = "通过 Server-Sent Events 实时推送文档处理状态变更。前端使用 EventSource 订阅。")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "SSE 连接建立成功"),
            @ApiResponse(responseCode = "401", description = "未认证")
    })
    @GetMapping(value = "/knowledge-bases/{kbId}/documents/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamDocumentStatus(
            @Parameter(description = "知识库ID") @PathVariable UUID kbId,
            @Parameter(description = "JWT token（EventSource 不支持 header，故通过 query 传递）", required = false)
            @RequestParam(value = "token", required = false) String tokenQuery) {

        // 【2026-06-27 增量】SSE 鉴权：浏览器 EventSource 无法设置 Authorization header
        // 故支持 query param 传 token；同时兼容 Spring Security 过滤器已注入的认证上下文
        if (SecurityContextHolder.getContext().getAuthentication() == null
                || !SecurityContextHolder.getContext().getAuthentication().isAuthenticated()) {
            // 尝试从 query param 验证 — extractUsername 会在 token 无效/过期时抛异常
            if (tokenQuery == null || tokenQuery.isEmpty()) {
                log.warn("SSE request without authentication");
                // 同步抛异常，让 Spring MVC 立即返回 401（Flux.error 是异步的，状态码不会被应用）
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
            }
            try {
                String username = jwtService.extractUsername(tokenQuery);
                if (username == null || username.isEmpty()) {
                    log.warn("SSE token has no username claim");
                    throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid token");
                }
                log.debug("SSE authenticated via query token for user={}", username);
            } catch (ResponseStatusException e) {
                throw e;
            } catch (Exception e) {
                log.warn("SSE token validation failed: {}", e.getMessage());
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "Token validation failed: " + e.getMessage());
            }
        }

        log.debug("New SSE subscriber for kbId={}", kbId);
        return eventService.getOrCreateSink(kbId)
                .asFlux()
                .map(this::toSseEvent)
                .doOnCancel(() -> log.debug("SSE client disconnected from kbId={}", kbId))
                .doOnError(e -> log.warn("SSE stream error for kbId={}: {}", kbId, e.getMessage()));
    }

    /**
     * 包装为自定义事件名 "doc-status" 的 ServerSentEvent。
     * 前端通过 {@code es.addEventListener('doc-status', handler)} 订阅。
     * 若序列化失败，发送空 data（避免阻塞订阅者）。
     */
    private ServerSentEvent<String> toSseEvent(DocumentStatusEvent event) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize DocumentStatusEvent: {}", e.getMessage());
            payload = "";
        }
        return ServerSentEvent.<String>builder()
                .event("doc-status")
                .id(event.documentId().toString())
                .data(payload)
                .build();
    }
}
