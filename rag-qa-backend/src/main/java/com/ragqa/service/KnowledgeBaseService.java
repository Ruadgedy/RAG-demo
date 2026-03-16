package com.ragqa.service;

import com.ragqa.dto.CreateKnowledgeBaseRequest;
import com.ragqa.model.KnowledgeBase;
import com.ragqa.repository.KnowledgeBaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 知识库服务
 * 
 * 作用：管理知识库的创建、查询、删除
 * 
 * API接口：
 * - POST   /api/knowledge-bases       - 创建知识库
 * - GET    /api/knowledge-bases       - 获取知识库列表
 * - GET    /api/knowledge-bases/{id}  - 获取单个知识库
 * - DELETE /api/knowledge-bases/{id} - 删除知识库
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KnowledgeBaseService {

    private final KnowledgeBaseRepository repository;

    /**
     * 创建知识库
     * 
     * @param request 创建请求（包含name和description）
     * @return 创建的知识库对象
     */
    @Transactional
    public KnowledgeBase create(CreateKnowledgeBaseRequest request) {
        // 检查名称是否已存在
        if (repository.existsByName(request.getName())) {
            throw new IllegalArgumentException("知识库名称已存在: " + request.getName());
        }

        // 创建知识库
        KnowledgeBase kb = new KnowledgeBase();
        kb.setName(request.getName());
        kb.setDescription(request.getDescription());

        log.info("创建知识库: {}", kb.getName());
        return repository.save(kb);
    }

    /**
     * 获取所有知识库
     */
    public List<KnowledgeBase> findAll() {
        return repository.findAll();
    }

    /**
     * 根据ID获取知识库
     */
    public KnowledgeBase findById(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("知识库不存在: " + id));
    }

    /**
     * 删除知识库
     * 
     * 注意：级联删除需要在Controller中处理
     */
    @Transactional
    public void delete(UUID id) {
        KnowledgeBase kb = findById(id);
        log.info("删除知识库: {}", kb.getName());
        repository.delete(kb);
    }
}
