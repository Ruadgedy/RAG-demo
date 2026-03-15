package com.ragqa.controller;

import com.ragqa.model.Document;
import com.ragqa.service.DocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;

    @PostMapping("/knowledge-bases/{kbId}/documents")
    public ResponseEntity<Document> uploadDocument(
            @PathVariable UUID kbId,
            @RequestParam("file") MultipartFile file) throws Exception {
        Document doc = documentService.uploadDocument(kbId, file);
        return ResponseEntity.ok(doc);
    }

    @GetMapping("/knowledge-bases/{kbId}/documents")
    public ResponseEntity<List<Document>> getDocuments(@PathVariable UUID kbId) {
        List<Document> docs = documentService.getDocumentsByKnowledgeBase(kbId);
        return ResponseEntity.ok(docs);
    }

    @GetMapping("/documents/{id}")
    public ResponseEntity<Document> getDocument(@PathVariable UUID id) {
        Document doc = documentService.getDocument(id);
        return ResponseEntity.ok(doc);
    }

    @DeleteMapping("/documents/{id}")
    public ResponseEntity<Void> deleteDocument(@PathVariable UUID id) {
        documentService.deleteDocument(id);
        return ResponseEntity.noContent().build();
    }
}
