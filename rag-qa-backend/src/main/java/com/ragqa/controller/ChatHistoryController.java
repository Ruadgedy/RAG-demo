package com.ragqa.controller;

import com.ragqa.model.ChatHistory;
import com.ragqa.repository.ChatHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ChatHistoryController {

    private final ChatHistoryRepository chatHistoryRepository;

    @GetMapping("/chat-history")
    public ResponseEntity<List<ChatHistory>> getAllSessions() {
        List<ChatHistory> history = chatHistoryRepository.findAll();
        return ResponseEntity.ok(history);
    }

    @GetMapping("/chat-history/{sessionId}")
    public ResponseEntity<List<ChatHistory>> getHistoryBySession(@PathVariable String sessionId) {
        List<ChatHistory> history = chatHistoryRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        return ResponseEntity.ok(history);
    }

    @GetMapping("/knowledge-bases/{kbId}/chat-history")
    public ResponseEntity<List<ChatHistory>> getHistoryByKnowledgeBase(@PathVariable UUID kbId) {
        List<ChatHistory> history = chatHistoryRepository.findByKnowledgeBaseIdOrderByCreatedAtDesc(kbId);
        return ResponseEntity.ok(history);
    }

    @PostMapping("/chat-history")
    public ResponseEntity<ChatHistory> saveMessage(@RequestBody ChatHistory chatHistory) {
        ChatHistory saved = chatHistoryRepository.save(chatHistory);
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/chat-history/{sessionId}")
    public ResponseEntity<Void> deleteSession(@PathVariable String sessionId) {
        chatHistoryRepository.deleteBySessionId(sessionId);
        return ResponseEntity.noContent().build();
    }
}
