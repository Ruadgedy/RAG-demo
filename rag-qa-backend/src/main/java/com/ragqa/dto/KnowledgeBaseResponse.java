package com.ragqa.dto;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
public class KnowledgeBaseResponse {
    
    private UUID id;
    private String name;
    private String description;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    
    public static KnowledgeBaseResponse from(com.ragqa.model.KnowledgeBase kb) {
        KnowledgeBaseResponse response = new KnowledgeBaseResponse();
        response.setId(kb.getId());
        response.setName(kb.getName());
        response.setDescription(kb.getDescription());
        response.setCreatedAt(kb.getCreatedAt());
        response.setUpdatedAt(kb.getUpdatedAt());
        return response;
    }
}
