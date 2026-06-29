package com.ragqa.service;

import com.ragqa.model.Document;
import com.ragqa.repository.DocumentChunkRepository;
import com.ragqa.repository.DocumentRepository;
import com.ragqa.repository.KnowledgeBaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 文档服务
 * 
 * 作用：处理文档上传、查询、删除等操作
 * 
 * API接口：
 * - POST /api/knowledge-bases/{kbId}/documents - 上传文档
 * - GET  /api/knowledge-bases/{kbId}/documents - 获取文档列表
 * - GET  /api/documents/{id} - 获取单个文档
 * - DELETE /api/documents/{id} - 删除文档
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentService {

    private final DocumentRepository documentRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final DocumentProcessService documentProcessService;
    private final ChromaService chromaService;
    private final Bm25SearchService bm25Service;

    /** 文件上传目录 */
    @Value("${file.upload-dir:./uploads}")
    private String uploadDir;

    /**
     * 上传文档
     *
     * 流程：
     * 1. 验证知识库存在
     * 2. 验证文件类型
     * 3. 安全解析文件名（防止路径遍历攻击）
     * 4. 保存文件到本地
     * 5. 创建Document记录（状态为UPLOADING）
     * 6. 异步触发文档处理（解析、切片、向量化）
     *
     * 【安全加固 2026-06-27】原本直接用 file.getOriginalFilename() 拼接路径，
     * 攻击者可上传 ../../etc/passwd 等文件名逃出 uploads 目录。
     * 现改为：getFileName() 取最后一段 + normalize() + 边界校验。
     *
     * @param knowledgeBaseId 知识库ID
     * @param file 上传的文件
     * @return 创建的文档对象
     */
    @Transactional
    public Document uploadDocument(UUID knowledgeBaseId, MultipartFile file) throws IOException {
        // 验证知识库存在
        if (!knowledgeBaseRepository.existsById(knowledgeBaseId)) {
            throw new IllegalArgumentException("知识库不存在: " + knowledgeBaseId);
        }

        // 获取文件信息
        String fileName = file.getOriginalFilename();
        String fileType = getFileType(fileName);

        // 验证文件类型
        if (!isSupportedFileType(fileType)) {
            throw new IllegalArgumentException("不支持的文件类型: " + fileType + "，仅支持 PDF、DOC、DOCX、XLS、XLSX、PPT、PPTX、TXT");
        }

        // 【2026-06-29 增量 P1-04/P1-05】文件内容 SHA-256 做内容级去重
        // 【P1-05 升级】改为流式计算 + 先写文件再算哈希，避免 200MB 文件 OOM
        // 流程：1) 创建上传目录 2) 流式保存文件到磁盘 3) 从磁盘流式计算 SHA-256
        Path uploadRoot = Paths.get(uploadDir).toAbsolutePath().normalize();
        Path uploadPath = uploadRoot.resolve(knowledgeBaseId.toString()).normalize();

        // 【路径遍历防护】
        if (fileName != null && (fileName.contains("/") || fileName.contains("\\"))) {
            throw new IllegalArgumentException("非法文件名: " + fileName);
        }
        Path filePath = uploadPath.resolve(Paths.get(fileName).getFileName()).normalize();
        if (!filePath.startsWith(uploadRoot)) {
            throw new IllegalArgumentException("非法文件名: " + fileName);
        }

        Files.createDirectories(uploadPath);

        // 流式保存文件到磁盘（不占用堆内存）
        try {
            Files.copy(file.getInputStream(), filePath);
        } catch (IOException e) {
            throw new IllegalArgumentException("文件保存失败: " + e.getMessage());
        }

        // 流式计算 SHA-256（从磁盘文件读取，不占用堆内存）
        String fileHash = sha256HexStream(filePath);

        Optional<Document> existingByHash = documentRepository.findByKnowledgeBaseIdAndFileHash(knowledgeBaseId, fileHash);
        if (existingByHash.isPresent()) {
            throw new IllegalArgumentException(
                "文件内容已存在（哈希 " + fileHash.substring(0, 8) + "...），重复上传将浪费 embedding 算力。"
                + "如需替换请先删除旧文档: " + existingByHash.get().getFileName());
        }

        // 第一层校验：按文件名再查一次（保护老的逻辑，未来可以考虑去除）
        Optional<Document> existingDoc = documentRepository.findByKnowledgeBaseIdAndFileName(knowledgeBaseId, fileName);
        if (existingDoc.isPresent()) {
            throw new IllegalArgumentException("文件名已存在: " + fileName);
        }

        // 创建文档记录
        Document document = new Document();
        document.setKnowledgeBaseId(knowledgeBaseId);
        document.setFileName(fileName);
        document.setFileType(fileType);
        document.setFilePath(filePath.toString());
        document.setFileHash(fileHash);   // 【P1-04】持久化内容哈希
        document.setStatus(Document.DocumentStatus.UPLOADING);
        document.setProgress(10);  // 10%进度

        // saveAndFlush 确保数据立即写入数据库，让异步线程能读到
        document = documentRepository.saveAndFlush(document);

        // 异步触发后续处理（解析、切片、向量化）
        // 注意：这里调用的是另一个Service的@Async方法
        documentProcessService.processDocumentAsync(document.getId(), filePath);

        return document;
    }

    /**
     * 获取知识库下的所有文档
     */
    public List<Document> getDocumentsByKnowledgeBase(UUID knowledgeBaseId) {
        return documentRepository.findByKnowledgeBaseId(knowledgeBaseId);
    }

    /**
     * 获取单个文档
     */
    public Document getDocument(UUID id) {
        return documentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("文档不存在: " + id));
    }

    /**
     * 删除文档
     *
     * 会同时删除：
     * - Chroma 向量数据库中的向量
     * - BM25 内存索引（避免索引膨胀 + IDF 统计被污染）
     * - MySQL 中的切片记录
     * - 本地文件
     * - 文档记录
     *
     * 【修复日期 2026-06-27】补充了 BM25 索引清理。
     */
    @Transactional
    public void deleteDocument(UUID id) {
        Document doc = getDocument(id);

        // 删除Chroma向量（关键：否则向量数据库会有孤立的向量）
        chromaService.deleteByDocumentId(id);

        // 删除 BM25 内存索引（关键：否则索引会膨胀，且 IDF 统计被已删除的 chunk 污染）
        try {
            bm25Service.removeByDocumentId(id.toString());
        } catch (Exception e) {
            log.warn("清理 BM25 索引失败: documentId={}, err={}", id, e.getMessage());
        }

        // 删除MySQL切片记录
        documentChunkRepository.deleteByDocumentId(id);

        // 删除本地文件
        try {
            Files.deleteIfExists(Paths.get(doc.getFilePath()));
        } catch (IOException e) {
            log.warn("删除文件失败: {}", doc.getFilePath());
        }

        // 删除文档记录
        documentRepository.delete(doc);
    }

    /**
     * 根据文件名获取文件类型
     */
    private String getFileType(String fileName) {
        if (fileName == null) return "";
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf")) return "pdf";
        if (lower.endsWith(".doc")) return "doc";
        if (lower.endsWith(".docx")) return "docx";
        if (lower.endsWith(".xls")) return "xls";
        if (lower.endsWith(".xlsx")) return "xlsx";
        if (lower.endsWith(".ppt")) return "ppt";
        if (lower.endsWith(".pptx")) return "pptx";
        if (lower.endsWith(".txt")) return "txt";
        return "";
    }

    /**
     * 检查是否支持该文件类型
     * 支持的格式：PDF、Word(doc/docx)、Excel(xls/xlsx)、PowerPoint(ppt/pptx)、TXT
     */
    private boolean isSupportedFileType(String fileType) {
        return "pdf".equals(fileType) || "doc".equals(fileType) || "docx".equals(fileType)
            || "xls".equals(fileType) || "xlsx".equals(fileType)
            || "ppt".equals(fileType) || "pptx".equals(fileType)
            || "txt".equals(fileType);
    }

    /**
     * 【2026-06-29 增量 P1-05】流式计算文件的 SHA-256（从磁盘读取，分块处理不占堆内存）
     *
     * 原理：DigestInputStream 包装文件输入流，逐块读取并更新 MessageDigest，
     *       最终生成 64 字符的 hex 字符串。
     *
     * @param filePath 文件路径
     * @return 64 字符 hex 字符串（小写）
     */
    private String sha256HexStream(Path filePath) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            try (java.security.DigestInputStream dis = new java.security.DigestInputStream(
                    Files.newInputStream(filePath), digest)) {
                // 逐块读取，触发摘要更新
                byte[] buf = new byte[8192];  // 8KB buffer，不占堆
                while (dis.read(buf) != -1) {
                    // DigestInputStream.update() 已由 read() 自动调用
                }
            }
            byte[] hash = digest.digest();
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 算法不可用", e);
        } catch (IOException e) {
            throw new RuntimeException("计算文件哈希失败: " + e.getMessage(), e);
        }
    }
}
