package com.ragqa.service;

import com.ragqa.model.Document;
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
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentService {

    private final DocumentRepository documentRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;

    @Value("${file.upload-dir:./uploads}")
    private String uploadDir;

    @Transactional
    public Document uploadDocument(UUID knowledgeBaseId, MultipartFile file) throws IOException {
        if (!knowledgeBaseRepository.existsById(knowledgeBaseId)) {
            throw new IllegalArgumentException("知识库不存在: " + knowledgeBaseId);
        }

        String fileName = file.getOriginalFilename();
        String fileType = getFileType(fileName);

        if (!isSupportedFileType(fileType)) {
            throw new IllegalArgumentException("不支持的文件类型: " + fileType + "，仅支持 PDF、DOCX、TXT");
        }

        Path uploadPath = Paths.get(uploadDir, knowledgeBaseId.toString());
        Files.createDirectories(uploadPath);

        String storedFileName = UUID.randomUUID() + "_" + fileName;
        Path filePath = uploadPath.resolve(storedFileName);
        Files.copy(file.getInputStream(), filePath);

        Document document = new Document();
        document.setKnowledgeBaseId(knowledgeBaseId);
        document.setFileName(fileName);
        document.setFileType(fileType);
        document.setFilePath(filePath.toString());
        document.setStatus(Document.DocumentStatus.PENDING);

        return documentRepository.save(document);
    }

    public List<Document> getDocumentsByKnowledgeBase(UUID knowledgeBaseId) {
        return documentRepository.findByKnowledgeBaseId(knowledgeBaseId);
    }

    public Document getDocument(UUID id) {
        return documentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("文档不存在: " + id));
    }

    @Transactional
    public void deleteDocument(UUID id) {
        Document doc = getDocument(id);
        try {
            Files.deleteIfExists(Paths.get(doc.getFilePath()));
        } catch (IOException e) {
            log.warn("删除文件失败: {}", doc.getFilePath());
        }
        documentRepository.delete(doc);
    }

    private String getFileType(String fileName) {
        if (fileName == null) return "";
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf")) return "pdf";
        if (lower.endsWith(".docx")) return "docx";
        if (lower.endsWith(".txt")) return "txt";
        return "";
    }

    private boolean isSupportedFileType(String fileType) {
        return "pdf".equals(fileType) || "docx".equals(fileType) || "txt".equals(fileType);
    }
}
