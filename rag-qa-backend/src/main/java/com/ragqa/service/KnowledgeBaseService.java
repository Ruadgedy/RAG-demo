package com.ragqa.service;

import com.ragqa.dto.CreateKnowledgeBaseRequest;
import com.ragqa.model.Document;
import com.ragqa.model.KnowledgeBase;
import com.ragqa.repository.DocumentRepository;
import com.ragqa.repository.KnowledgeBaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
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
 * - DELETE /api/knowledge-bases/{id} - 删除知识库（含级联清理）
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KnowledgeBaseService {

    private final KnowledgeBaseRepository repository;
    private final DocumentRepository documentRepository;
    private final ChromaService chromaService;
    private final Bm25SearchService bm25Service;

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
     * 删除知识库（含级联清理）
     *
     * 【清理链路】
     * 1. 查出该 KB 下所有文档
     * 2. 对每个文档：
     *    a. 清理 Chroma 向量（避免孤立向量被检索召回造成幻觉）
     *    b. 清理 BM25 内存索引
     *    c. 删除本地文件（uploads/{kbId}/{fileName}）
     *    d. MySQL document/document_chunk 由 FK CASCADE 在最后 repository.delete(kb) 时级联清理
     * 3. 删除 KB 记录本身（触发 MySQL CASCADE）
     *
     * 【错误处理】
     * - Chroma/BM25/文件清理单个失败不影响整体删除（最多遗留少量垃圾）
     * - 但 MySQL 删除失败必须整体回滚（@Transactional）
     *
     * 【原实现问题】
     * 之前只调 repository.delete(kb)，导致：
     * - Chroma 中该 KB 的所有向量变成孤儿数据
     * - BM25 内存索引无限增长
     * - uploads 目录下文件无限堆积
     * 修复日期：2026-06-27
     */
    @Transactional
    public void delete(UUID id) {
        KnowledgeBase kb = findById(id);
        log.info("删除知识库: {}（含级联清理）", kb.getName());

        // 1. 查出该 KB 下所有文档（在删除 KB 前查询，避免 CASCADE 清空）
        List<Document> documents = documentRepository.findByKnowledgeBaseId(id);

        // 2. 循环清理每个文档的外部资源
        for (Document doc : documents) {
            UUID docId = doc.getId();
            try {
                // a. Chroma 向量（关键：否则向量库会有孤立向量污染检索）
                chromaService.deleteByDocumentId(docId);
            } catch (Exception e) {
                log.warn("清理 Chroma 向量失败: documentId={}, err={}", docId, e.getMessage());
            }

            try {
                // b. BM25 内存索引
                bm25Service.removeByDocumentId(docId.toString());
            } catch (Exception e) {
                log.warn("清理 BM25 索引失败: documentId={}, err={}", docId, e.getMessage());
            }

            try {
                // c. 本地文件
                if (doc.getFilePath() != null) {
                    Files.deleteIfExists(Paths.get(doc.getFilePath()));
                }
            } catch (IOException e) {
                log.warn("删除本地文件失败: documentId={}, path={}, err={}",
                        docId, doc.getFilePath(), e.getMessage());
            }
        }

        // 3. 删除 KB 记录（MySQL FK ON DELETE CASCADE 自动级联删除 document + document_chunk）
        repository.delete(kb);

        log.info("知识库删除完成: id={}, 共清理 {} 个文档的外部资源", id, documents.size());
    }
}