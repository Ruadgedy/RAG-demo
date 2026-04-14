package com.ragqa.dto;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 知识库响应DTO
 *
 * 作用：封装知识库的API响应数据
 *
 * 字段说明：
 * - id: 知识库唯一标识
 * - name: 知识库名称
 * - description: 描述
 * - createdAt: 创建时间
 * - updatedAt: 更新时间
 *
 * from()方法：将实体对象转换为响应DTO（解耦entity和API）
 */
@Data
public class KnowledgeBaseResponse {

    /** 知识库UUID */
    private UUID id;

    /** 知识库名称 */
    private String name;

    /** 知识库描述 */
    private String description;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;

    /**
     * 将KnowledgeBase实体转换为响应DTO
     *
     * @param kb 知识库实体
     * @return 响应DTO
     */
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
