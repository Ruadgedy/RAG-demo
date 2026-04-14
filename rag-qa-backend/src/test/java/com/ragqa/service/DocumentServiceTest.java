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

        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));

        documentService.deleteDocument(docId);

        verify(documentRepository).deleteById(docId);
    }
}
