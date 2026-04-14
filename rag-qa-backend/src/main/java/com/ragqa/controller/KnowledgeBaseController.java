package com.ragqa.controller;

import com.ragqa.dto.CreateKnowledgeBaseRequest;
import com.ragqa.dto.KnowledgeBaseResponse;
import com.ragqa.service.KnowledgeBaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

/**
 * 知识库控制器
 *
 * 作用：管理知识库的CRUD操作
 *
 * 接口说明：
 * - POST /api/knowledge-bases: 创建新知识库
 * - GET /api/knowledge-bases: 获取所有知识库列表
 * - GET /api/knowledge-bases/{id}: 获取指定知识库详情
 * - DELETE /api/knowledge-bases/{id}: 删除知识库及其所有关联数据
 *
 * 认证要求：
 * - 所有接口需要JWT认证
 *
 * 业务逻辑：
 * - 创建时检查名称是否重复
 * - 删除时会同时删除关联的文档、向量化数据
 */
@RestController
@RequestMapping("/api/knowledge-bases")
@RequiredArgsConstructor
@Tag(name = "知识库", description = "知识库管理接口")
public class KnowledgeBaseController {

    private final KnowledgeBaseService service;

    @Operation(summary = "创建知识库", description = "创建一个新的知识库，用于存储和管理文档")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "创建成功",
                    content = @Content(schema = @Schema(implementation = KnowledgeBaseResponse.class))),
            @ApiResponse(responseCode = "400", description = "请求参数错误"),
            @ApiResponse(responseCode = "401", description = "未认证")
    })
    @PostMapping
    public ResponseEntity<KnowledgeBaseResponse> create(
            @Valid @RequestBody CreateKnowledgeBaseRequest request) {
        var kb = service.create(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(KnowledgeBaseResponse.from(kb));
    }

    @Operation(summary = "获取知识库列表", description = "获取当前用户的所有知识库")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "获取成功"),
            @ApiResponse(responseCode = "401", description = "未认证")
    })
    @GetMapping
    public ResponseEntity<List<KnowledgeBaseResponse>> list() {
        var list = service.findAll().stream()
                .map(KnowledgeBaseResponse::from)
                .toList();
        return ResponseEntity.ok(list);
    }

    @Operation(summary = "获取知识库详情", description = "根据ID获取指定知识库的详细信息")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "获取成功",
                    content = @Content(schema = @Schema(implementation = KnowledgeBaseResponse.class))),
            @ApiResponse(responseCode = "401", description = "未认证"),
            @ApiResponse(responseCode = "404", description = "知识库不存在")
    })
    @GetMapping("/{id}")
    public ResponseEntity<KnowledgeBaseResponse> get(
            @Parameter(description = "知识库ID") @PathVariable UUID id) {
        var kb = service.findById(id);
        return ResponseEntity.ok(KnowledgeBaseResponse.from(kb));
    }

    @Operation(summary = "删除知识库", description = "删除指定知识库及其所有关联数据（文档、向量）")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "删除成功"),
            @ApiResponse(responseCode = "401", description = "未认证"),
            @ApiResponse(responseCode = "404", description = "知识库不存在")
    })
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @Parameter(description = "知识库ID") @PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
