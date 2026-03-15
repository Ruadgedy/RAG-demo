package com.ragqa.service;

import com.ragqa.dto.CreateKnowledgeBaseRequest;
import com.ragqa.model.KnowledgeBase;
import com.ragqa.repository.KnowledgeBaseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class KnowledgeBaseService {
    
    private final KnowledgeBaseRepository repository;
    
    @Transactional
    public KnowledgeBase create(CreateKnowledgeBaseRequest request) {
        if (repository.existsByName(request.getName())) {
            throw new IllegalArgumentException("知识库名称已存在: " + request.getName());
        }
        
        KnowledgeBase kb = new KnowledgeBase();
        kb.setName(request.getName());
        kb.setDescription(request.getDescription());
        
        return repository.save(kb);
    }
    
    public List<KnowledgeBase> list() {
        return repository.findAll();
    }
    
    public KnowledgeBase getById(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("知识库不存在: " + id));
    }
    
    @Transactional
    public void delete(UUID id) {
        if (!repository.existsById(id)) {
            throw new IllegalArgumentException("知识库不存在: " + id);
        }
        repository.deleteById(id);
    }
}
