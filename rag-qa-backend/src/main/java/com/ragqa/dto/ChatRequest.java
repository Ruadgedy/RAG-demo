package com.ragqa.dto;

import lombok.Data;
import java.util.List;
import java.util.UUID;

@Data
public class ChatRequest {
    private String message;
    private UUID knowledgeBaseId;
    private List<ChatMessage> history;
}
