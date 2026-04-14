package com.ragqa.controller;

import com.ragqa.dto.CreateKnowledgeBaseRequest;
import com.ragqa.dto.KnowledgeBaseResponse;
import com.ragqa.service.KnowledgeBaseService;
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
public class KnowledgeBaseController {

    private final KnowledgeBaseService service;

    /**
     * 创建知识库
     *
     * @param request 包含name、description
     * @return 201 Created，返回知识库信息
     */
    @PostMapping
    public ResponseEntity<KnowledgeBaseResponse> create(
            @Valid @RequestBody CreateKnowledgeBaseRequest request) {
        var kb = service.create(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(KnowledgeBaseResponse.from(kb));
    }

    /**
     * 获取所有知识库
     *
     * @return 知识库列表（包含ID、名称、描述、创建时间）
     */
    @GetMapping
    public ResponseEntity<List<KnowledgeBaseResponse>> list() {
        var list = service.findAll().stream()
                .map(KnowledgeBaseResponse::from)
                .toList();
        return ResponseEntity.ok(list);
    }

    /**
     * 获取指定知识库
     *
     * @param id 知识库UUID
     * @return 知识库详情
     */
    @GetMapping("/{id}")
    public ResponseEntity<KnowledgeBaseResponse> get(@PathVariable UUID id) {
        var kb = service.findById(id);
        return ResponseEntity.ok(KnowledgeBaseResponse.from(kb));
    }

    /**
     * 删除知识库
     *
     * 级联删除：
     * 1. 删除知识库下的所有文档
     * 2. 删除Chroma中的向量数据
     * 3. 删除数据库中的切片记录
     *
     * @param id 知识库UUID
     * @return 204 No Content
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
