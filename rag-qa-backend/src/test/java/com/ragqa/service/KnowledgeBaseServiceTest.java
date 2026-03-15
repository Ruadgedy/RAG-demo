package com.ragqa.service;

import com.ragqa.dto.CreateKnowledgeBaseRequest;
import com.ragqa.model.KnowledgeBase;
import com.ragqa.repository.KnowledgeBaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
        
        var result = service.list();
        
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
        
        KnowledgeBase result = service.getById(id);
        
        assertThat(result.getId()).isEqualTo(id);
    }
    
    @Test
    void shouldThrowExceptionWhenNotFound() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());
        
        assertThatThrownBy(() -> service.getById(id))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("知识库不存在");
    }
}
