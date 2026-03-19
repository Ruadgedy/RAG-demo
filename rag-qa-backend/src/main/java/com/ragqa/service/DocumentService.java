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

    /** 文件上传目录 */
    @Value("${file.upload-dir:./uploads}")
    private String uploadDir;

    /**
     * 上传文档
     * 
     * 流程：
     * 1. 验证知识库存在
     * 2. 验证文件类型
     * 3. 保存文件到本地
     * 4. 创建Document记录（状态为UPLOADING）
     * 5. 异步触发文档处理（解析、切片、向量化）
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
            throw new IllegalArgumentException("不支持的文件类型: " + fileType + "，仅支持 PDF、DOCX、TXT");
        }

        // 检查同名文件是否已存在
        Optional<Document> existingDoc = documentRepository.findByKnowledgeBaseIdAndFileName(knowledgeBaseId, fileName);
        if (existingDoc.isPresent()) {
            throw new IllegalArgumentException("文件已存在: " + fileName);
        }

        // 创建上传目录
        Path uploadPath = Paths.get(uploadDir, knowledgeBaseId.toString());
        Files.createDirectories(uploadPath);

        // 生成唯一文件名并保存
        String storedFileName = UUID.randomUUID() + "_" + fileName;
        Path filePath = uploadPath.resolve(storedFileName);
        
        try {
            Files.copy(file.getInputStream(), filePath);
        } catch (IOException e) {
            // 上传失败，创建失败记录
            Document doc = new Document();
            doc.setKnowledgeBaseId(knowledgeBaseId);
            doc.setFileName(fileName);
            doc.setFileType(fileType);
            doc.setStatus(Document.DocumentStatus.UPLOAD_FAILED);
            doc.setErrorMessage("文件上传失败: " + e.getMessage());
            return documentRepository.save(doc);
        }

        // 创建文档记录
        Document document = new Document();
        document.setKnowledgeBaseId(knowledgeBaseId);
        document.setFileName(fileName);
        document.setFileType(fileType);
        document.setFilePath(filePath.toString());
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
     * - 文档记录
     * - 文档切片记录
     * - 本地文件
     */
    @Transactional
    public void deleteDocument(UUID id) {
        Document doc = getDocument(id);
        
        // 删除切片记录
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
        if (lower.endsWith(".docx")) return "docx";
        if (lower.endsWith(".txt")) return "txt";
        return "";
    }

    /**
     * 检查是否支持该文件类型
     */
    private boolean isSupportedFileType(String fileType) {
        return "pdf".equals(fileType) || "docx".equals(fileType) || "txt".equals(fileType);
    }
}
