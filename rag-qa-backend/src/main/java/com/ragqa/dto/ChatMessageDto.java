package com.ragqa.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 对话消息 DTO（用于 GET /conversations/{id}/messages）
 *
 * 【V6 2026-06-30】
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessageDto {
    private String chatId;       // 单次问答ID
    private Integer turnIndex;   // 第几轮
    private String query;        // 用户提问
    private String content;      // AI 回答
    private List<SourceRef> sources;  // 参考文档
    private String ragMetadata;  // RAG 元数据
    private String createdAt;    // 创建时间
}