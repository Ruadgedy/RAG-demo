package com.ragqa.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 对话组响应 DTO
 *
 * 【V6 2026-06-30】
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConversationDto {
    private String id;              // conversation_id
    private String userId;
    private String title;           // 对话组标题（大模型生成）
    private String firstQuery;      // 第一轮原始提问
    private String knowledgeBaseId;
    private Integer historyWindow;  // 滑动窗口大小
    private Integer turnCount;      // 对话轮次
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * 用于历史列表展示的摘要
     */
    private String summary;
}