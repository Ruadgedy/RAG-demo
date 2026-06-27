package com.ragqa.service;

import com.ragqa.model.Document;
import com.ragqa.model.KnowledgeBase;
import com.ragqa.repository.DocumentChunkRepository;
import com.ragqa.repository.DocumentRepository;
import com.ragqa.repository.KnowledgeBaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * DocumentService 单元测试
 */
@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private KnowledgeBaseRepository knowledgeBaseRepository;

    @Mock
    private DocumentChunkRepository documentChunkRepository;

    @Mock
    private DocumentProcessService documentProcessService;

    @Mock
    private ChromaService chromaService;

    @Mock
    private Bm25SearchService bm25Service;

    @InjectMocks
    private DocumentService documentService;

    private UUID kbId;
    private KnowledgeBase knowledgeBase;

    @BeforeEach
    void setUp() {
        kbId = UUID.randomUUID();
        knowledgeBase = new KnowledgeBase();
        knowledgeBase.setId(kbId);
        knowledgeBase.setName("测试知识库");
        // @InjectMocks 不会初始化 @Value 字段，手动注入避免测试 NPE
        ReflectionTestUtils.setField(documentService, "uploadDir", "/tmp/test-uploads");
    }

    @Test
    void shouldGetDocumentsByKnowledgeBase() {
        Document doc1 = new Document();
        doc1.setId(UUID.randomUUID());
        doc1.setKnowledgeBaseId(kbId);
        doc1.setFileName("文档1.pdf");
        doc1.setStatus(Document.DocumentStatus.COMPLETED);

        Document doc2 = new Document();
        doc2.setId(UUID.randomUUID());
        doc2.setKnowledgeBaseId(kbId);
        doc2.setFileName("文档2.txt");
        doc2.setStatus(Document.DocumentStatus.UPLOADING);

        when(documentRepository.findByKnowledgeBaseId(kbId)).thenReturn(List.of(doc1, doc2));

        List<Document> result = documentService.getDocumentsByKnowledgeBase(kbId);

        assertThat(result).hasSize(2);
        verify(documentRepository).findByKnowledgeBaseId(kbId);
    }

    @Test
    void shouldGetDocumentById() {
        UUID docId = UUID.randomUUID();
        Document doc = new Document();
        doc.setId(docId);
        doc.setFileName("测试文档.pdf");
        doc.setStatus(Document.DocumentStatus.COMPLETED);

        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));

        Document result = documentService.getDocument(docId);

        assertThat(result.getId()).isEqualTo(docId);
        assertThat(result.getFileName()).isEqualTo("测试文档.pdf");
    }

    @Test
    void shouldThrowExceptionWhenDocumentNotFound() {
        UUID docId = UUID.randomUUID();
        when(documentRepository.findById(docId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> documentService.getDocument(docId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文档不存在");
    }

    @Test
    void shouldDeleteDocument() {
        UUID docId = UUID.randomUUID();
        Document doc = new Document();
        doc.setId(docId);
        doc.setKnowledgeBaseId(kbId);
        // 必须设置 filePath，否则 DocumentService.deleteDocument 内部
        // Paths.get(doc.getFilePath()) 会因 null 抛 NullPointerException
        doc.setFilePath("/tmp/fake-test-file.pdf");

        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));

        documentService.deleteDocument(docId);

        // 生产代码 DocumentService.deleteDocument() 调用的是 documentRepository.delete(entity)
        // 而不是 deleteById(id)，断言需要与之对齐
        verify(documentRepository).delete(doc);
        // 2026-06-27 修复：删除文档时必须同步清理 BM25 索引
        verify(bm25Service).removeByDocumentId(docId.toString());
        // Chroma 也必须清理（已有断言，未变化）
        verify(chromaService).deleteByDocumentId(docId);
    }

    @Test
    void shouldContinueDeletionEvenIfBm25CleanupFails() {
        UUID docId = UUID.randomUUID();
        Document doc = new Document();
        doc.setId(docId);
        doc.setKnowledgeBaseId(kbId);
        doc.setFilePath("/tmp/test.pdf");

        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));
        // BM25 清理抛异常（防御性容错：不应阻塞整体删除）
        doThrow(new RuntimeException("BM25 不可用")).when(bm25Service).removeByDocumentId(docId.toString());

        // 即使 BM25 失败，整体删除仍应成功
        documentService.deleteDocument(docId);

        verify(documentRepository).delete(doc);
        verify(chromaService).deleteByDocumentId(docId);
    }

    // ============================================================
    // 路径遍历防护（2026-06-27 修复）
    // ============================================================

    @Test
    void shouldRejectPathTraversalFilename() throws Exception {
        // 攻击者上传文件名带 ../ 前缀 + 合法扩展名（绕过文件类型检查后被路径遍历检查拦截）
        MockMultipartFile evilFile = new MockMultipartFile(
                "file",
                "../../etc/passwd.pdf",  // 恶意文件名（带合法扩展名才能通过文件类型校验）
                "application/pdf",
                "evil content".getBytes()
        );

        when(knowledgeBaseRepository.existsById(kbId)).thenReturn(true);

        // 应该抛出 IllegalArgumentException（路径遍历被拦截）
        assertThatThrownBy(() -> documentService.uploadDocument(kbId, evilFile))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("非法文件名");

        // 验证：没有创建 Document 记录
        verify(documentRepository, never()).save(any());
    }

    @Test
    void shouldRejectAbsolutePathFilename() throws Exception {
        // 攻击者尝试用绝对路径（如 /etc/passwd.pdf）
        MockMultipartFile evilFile = new MockMultipartFile(
                "file",
                "/etc/passwd.pdf",
                "application/pdf",
                "evil content".getBytes()
        );

        when(knowledgeBaseRepository.existsById(kbId)).thenReturn(true);

        assertThatThrownBy(() -> documentService.uploadDocument(kbId, evilFile))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("非法文件名");
    }

    @Test
    void shouldRejectBackslashPathFilename() throws Exception {
        // 攻击者尝试用 Windows 风格路径分隔符
        MockMultipartFile evilFile = new MockMultipartFile(
                "file",
                "..\\..\\windows\\evil.pdf",
                "application/pdf",
                "evil content".getBytes()
        );

        when(knowledgeBaseRepository.existsById(kbId)).thenReturn(true);

        assertThatThrownBy(() -> documentService.uploadDocument(kbId, evilFile))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("非法文件名");
    }
}
