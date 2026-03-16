package com.ragqa.controller;

import com.ragqa.dto.CreateKnowledgeBaseRequest;
import com.ragqa.dto.KnowledgeBaseResponse;
import com.ragqa.service.KnowledgeBaseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/knowledge-bases")
@RequiredArgsConstructor
public class KnowledgeBaseController {
    
    private final KnowledgeBaseService service;
    
    @PostMapping
    public ResponseEntity<KnowledgeBaseResponse> create(
            @Valid @RequestBody CreateKnowledgeBaseRequest request) {
        var kb = service.create(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(KnowledgeBaseResponse.from(kb));
    }
    
    @GetMapping
    public ResponseEntity<List<KnowledgeBaseResponse>> list() {
        var list = service.findAll().stream()
                .map(KnowledgeBaseResponse::from)
                .toList();
        return ResponseEntity.ok(list);
    }
    
    @GetMapping("/{id}")
    public ResponseEntity<KnowledgeBaseResponse> get(@PathVariable UUID id) {
        var kb = service.findById(id);
        return ResponseEntity.ok(KnowledgeBaseResponse.from(kb));
    }
    
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
