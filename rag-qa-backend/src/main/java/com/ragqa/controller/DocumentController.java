package com.ragqa.controller;

import com.ragqa.model.Document;
import com.ragqa.service.DocumentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

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
@Tag(name = "文档", description = "文档管理接口")
public class DocumentController {

    private final DocumentService documentService;

    @Operation(summary = "上传文档", description = "上传文档到指定知识库，系统自动进行解析和向量化")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "上传成功",
                    content = @Content(schema = @Schema(implementation = Document.class))),
            @ApiResponse(responseCode = "400", description = "请求参数错误"),
            @ApiResponse(responseCode = "401", description = "未认证"),
            @ApiResponse(responseCode = "404", description = "知识库不存在")
    })
    @PostMapping("/knowledge-bases/{kbId}/documents")
    public ResponseEntity<Document> uploadDocument(
            @Parameter(description = "知识库ID") @PathVariable UUID kbId,
            @Parameter(description = "上传的文件") @RequestParam("file") MultipartFile file) throws Exception {
        Document doc = documentService.uploadDocument(kbId, file);
        return ResponseEntity.ok(doc);
    }

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
}
