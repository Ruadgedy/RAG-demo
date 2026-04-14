package com.ragqa.dto;

import lombok.Data;
import java.util.List;
import java.util.UUID;

/**
 * 聊天请求DTO
 *
 * 作用：封装用户问答请求的参数
 *
 * 字段说明：
 * - message: 用户问题
 * - knowledgeBaseId: 知识库ID（指定在哪个知识库中问答）
 * - history: 对话历史（用于多轮对话，目前未完全使用）
 */
@Data
public class ChatRequest {
    /** 用户问题 */
    private String message;

    /** 知识库ID */
    private UUID knowledgeBaseId;

    /** 对话历史（可选，用于多轮对话上下文） */
    private List<ChatMessage> history;
}
