package com.ragqa.service;

import com.ragqa.dto.CreateKnowledgeBaseRequest;
import com.ragqa.model.Document;
import com.ragqa.model.KnowledgeBase;
import com.ragqa.repository.DocumentRepository;
import com.ragqa.repository.KnowledgeBaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KnowledgeBaseServiceTest {

    @Mock
    private KnowledgeBaseRepository repository;

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private ChromaService chromaService;

    @Mock
    private Bm25SearchService bm25Service;

    @InjectMocks
    private KnowledgeBaseService service;

    private CreateKnowledgeBaseRequest createRequest;

    @BeforeEach
    void setUp() {
        createRequest = new CreateKnowledgeBaseRequest();
        createRequest.setName("测试知识库");
        createRequest.setDescription("测试描述");
    }

    @Test
    void shouldCreateKnowledgeBase() {
        when(repository.existsByName("测试知识库")).thenReturn(false);

        KnowledgeBase saved = new KnowledgeBase();
        saved.setId(UUID.randomUUID());
        saved.setName("测试知识库");
        saved.setDescription("测试描述");
        when(repository.save(any(KnowledgeBase.class))).thenReturn(saved);

        KnowledgeBase result = service.create(createRequest);

        assertThat(result.getName()).isEqualTo("测试知识库");
        verify(repository).save(any(KnowledgeBase.class));
    }

    @Test
    void shouldThrowExceptionWhenNameExists() {
        when(repository.existsByName("测试知识库")).thenReturn(true);

        assertThatThrownBy(() -> service.create(createRequest))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("知识库名称已存在");

        verify(repository, never()).save(any());
    }

    @Test
    void shouldListKnowledgeBases() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(UUID.randomUUID());
        kb.setName("测试");
        when(repository.findAll()).thenReturn(java.util.List.of(kb));

        var result = service.findAll();

        assertThat(result).hasSize(1);
        verify(repository).findAll();
    }

    @Test
    void shouldGetById() {
        UUID id = UUID.randomUUID();
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(id);
        kb.setName("测试");
        when(repository.findById(id)).thenReturn(Optional.of(kb));

        KnowledgeBase result = service.findById(id);

        assertThat(result.getId()).isEqualTo(id);
    }

    @Test
    void shouldThrowExceptionWhenNotFound() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(id))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("知识库不存在");
    }

    // ============================================================
    // 级联删除相关测试（2026-06-27 新增）
    // ============================================================

    @Test
    void shouldCascadeCleanupOnDelete() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();

        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(kbId);
        kb.setName("待删除知识库");

        Document doc = new Document();
        doc.setId(docId);
        doc.setKnowledgeBaseId(kbId);
        doc.setFileName("test.pdf");
        doc.setFilePath("/tmp/test.pdf");

        when(repository.findById(kbId)).thenReturn(Optional.of(kb));
        when(documentRepository.findByKnowledgeBaseId(kbId)).thenReturn(java.util.List.of(doc));

        service.delete(kbId);

        // 关键断言：Chroma 和 BM25 必须被清理
        verify(chromaService).deleteByDocumentId(docId);
        verify(bm25Service).removeByDocumentId(docId.toString());
        // KB 记录本身必须删除（MySQL FK CASCADE 自动级联清理 document / document_chunk）
        verify(repository).delete(kb);
    }

    @Test
    void shouldHandleEmptyKnowledgeBaseDeletion() {
        UUID kbId = UUID.randomUUID();
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(kbId);
        kb.setName("空知识库");

        when(repository.findById(kbId)).thenReturn(Optional.of(kb));
        when(documentRepository.findByKnowledgeBaseId(kbId)).thenReturn(Collections.emptyList());

        service.delete(kbId);

        // 空知识库也应该能正常删除
        verify(repository).delete(kb);
        // 不应该有 Chroma/BM25 调用
        verify(chromaService, never()).deleteByDocumentId(any());
        verify(bm25Service, never()).removeByDocumentId(anyString());
    }

    @Test
    void shouldContinueDeletionEvenIfChromaCleanupFails() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();

        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(kbId);
        kb.setName("知识库");

        Document doc = new Document();
        doc.setId(docId);
        doc.setKnowledgeBaseId(kbId);
        doc.setFileName("test.pdf");

        when(repository.findById(kbId)).thenReturn(Optional.of(kb));
        when(documentRepository.findByKnowledgeBaseId(kbId)).thenReturn(java.util.List.of(doc));
        // Chroma 清理抛异常
        doThrow(new RuntimeException("Chroma 不可用")).when(chromaService).deleteByDocumentId(docId);

        // 即使 Chroma 失败，整体删除仍应成功（防御性容错）
        service.delete(kbId);

        verify(repository).delete(kb);
        // BM25 仍被调用
        verify(bm25Service).removeByDocumentId(docId.toString());
    }

    @Test
    void shouldThrowExceptionWhenDeletingNonExistentKnowledgeBase() {
        UUID kbId = UUID.randomUUID();
        when(repository.findById(kbId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(kbId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("知识库不存在");

        verify(repository, never()).delete(any());
        verify(chromaService, never()).deleteByDocumentId(any());
    }
}
