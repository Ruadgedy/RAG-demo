package com.ragqa.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 创建知识库请求DTO
 *
 * 作用：封装创建知识库的参数
 */
@Data
public class CreateKnowledgeBaseRequest {

    /** 知识库名称（不能为空，唯一） */
    @NotBlank(message = "知识库名称不能为空")
    private String name;

    /** 知识库描述（可选） */
    private String description;
}
