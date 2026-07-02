package com.ragqa.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 问答响应DTO（V6 重构版）
 *
 * 【V6 2026-06-30】
 * - sessionId 改为 conversationId + chatId
 *
 * 字段说明：
 * - conversationId: 对话组ID
 * - chatId: 单次问答ID
 * - answer: LLM 完整回答
 * - sources: 本次回答引用的文档来源列表
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatResponse {
    /** 对话组ID */
    private String conversationId;

    /** 单次问答ID */
    private String chatId;

    /** LLM 完整回答 */
    private String answer;

    /** 本次回答引用的文档来源列表 */
    private List<SourceRef> sources;
}