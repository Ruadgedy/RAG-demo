package com.ragqa.controller;

import com.ragqa.model.Document;
import com.ragqa.service.DocumentService;
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
public class DocumentController {

    private final DocumentService documentService;

    /**
     * 上传文档
     *
     * 上传文件到指定知识库，系统自动进行解析和向量化
     *
     * @param kbId 知识库ID
     * @param file 上传的文件（multipart/form-data）
     * @return 创建的文档记录（状态为UPLOADING）
     */
    @PostMapping("/knowledge-bases/{kbId}/documents")
    public ResponseEntity<Document> uploadDocument(
            @PathVariable UUID kbId,
            @RequestParam("file") MultipartFile file) throws Exception {
        Document doc = documentService.uploadDocument(kbId, file);
        return ResponseEntity.ok(doc);
    }

    /**
     * 获取知识库的所有文档
     *
     * @param kbId 知识库ID
     * @return 文档列表（包含状态、进度等信息）
     */
    @GetMapping("/knowledge-bases/{kbId}/documents")
    public ResponseEntity<List<Document>> getDocuments(@PathVariable UUID kbId) {
        List<Document> docs = documentService.getDocumentsByKnowledgeBase(kbId);
        return ResponseEntity.ok(docs);
    }

    /**
     * 获取指定文档
     *
     * @param id 文档ID
     * @return 文档详情
     */
    @GetMapping("/documents/{id}")
    public ResponseEntity<Document> getDocument(@PathVariable UUID id) {
        Document doc = documentService.getDocument(id);
        return ResponseEntity.ok(doc);
    }

    /**
     * 删除文档
     *
     * 删除文档及其所有关联的切片和向量数据
     *
     * @param id 文档ID
     * @return 204 No Content
     */
    @DeleteMapping("/documents/{id}")
    public ResponseEntity<Void> deleteDocument(@PathVariable UUID id) {
        documentService.deleteDocument(id);
        return ResponseEntity.noContent().build();
    }
}
