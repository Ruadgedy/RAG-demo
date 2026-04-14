package com.ragqa.dto;

import lombok.Data;

/**
 * 聊天消息DTO
 *
 * 作用：封装单条聊天消息
 *
 * 字段说明：
 * - role: 消息角色，user（用户）或 assistant（AI）
 * - content: 消息内容
 */
@Data
public class ChatMessage {
    /** 消息角色：user 或 assistant */
    private String role;

    /** 消息内容 */
    private String content;
}
