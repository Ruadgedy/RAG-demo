package com.ragqa.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 问答响应DTO
 *
 * 作用：封装非流式问答接口（POST /api/chat）的响应数据
 *
 * 字段说明：
 * - sessionId: 本次问答的会话ID，用于关联同一轮对话的所有消息
 *   （user 问题与 assistant 回答共享同一 sessionId，已由后端落库到 chat_history）
 * - answer: LLM 基于知识库生成的完整回答
 *
 * 设计说明：
 * - 旧版本 /api/chat 仅返回纯文本 String，无法把 sessionId 带回前端，
 *   导致前端无法将当前问答归并到某个历史会话。
 * - 升级为对象响应后，前端可据此刷新侧边栏「聊天历史」并高亮当前会话。
 *
 * 注解说明：@Data + @AllArgsConstructor 会抑制 @Data 隐式无参构造器，
 * 显式加 @NoArgsConstructor 恢复，用于 Jackson 反序列化与测试构建对象
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatResponse {
    /** 本次问答的会话ID（同一轮 user/assistant 消息共享） */
    private String sessionId;

    /** LLM 完整回答 */
    private String answer;
}
