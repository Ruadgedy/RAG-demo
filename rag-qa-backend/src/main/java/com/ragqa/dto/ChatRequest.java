package com.ragqa.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.util.List;
import java.util.UUID;

/**
 * 聊天请求DTO（V6 重构版）
 *
 * 【V6 2026-06-30】
 * - conversationId：对话组ID（可选，为空则创建新对话组）
 * - history：现在由后端根据 conversationId + historyWindow 自动管理
 *
 * 字段说明：
 * - conversationId: 对话组ID（为空则创建新对话组）
 * - message: 用户问题
 * - knowledgeBaseId: 知识库ID
 * - history: （V6 废弃，由后端自动管理）
 */
@Data
public class ChatRequest {
    /** 对话组ID（可选，为空则创建新对话组） */
    private String conversationId;

    /** 用户问题 */
    @NotBlank(message = "问题不能为空")
    private String message;

    /** 知识库ID */
    @NotNull(message = "知识库ID不能为空")
    private UUID knowledgeBaseId;

    /** 滑动窗口大小（可选，默认 3，可覆盖对话组设置） */
    private Integer historyWindow;

    /**
     * @deprecated V6 已废弃，历史由后端根据 conversationId + historyWindow 自动管理
     */
    @Deprecated
    private List<ChatMessage> history;
}