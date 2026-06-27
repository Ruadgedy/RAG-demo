package com.ragqa.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 注册请求DTO
 *
 * 作用：封装新用户注册的信息
 *
 * 校验说明：
 * - username/password 使用 @NotBlank（必填、不能全空格）
 * - password 至少 6 位
 * - email 选填，若填写则需符合邮箱格式
 */
@Data
public class RegisterRequest {
    /** 用户名（唯一） */
    @NotBlank(message = "用户名不能为空")
    private String username;

    /** 密码（会加密存储） */
    @NotBlank(message = "密码不能为空")
    @Size(min = 6, message = "密码至少 6 位")
    private String password;

    /** 邮箱（可选） */
    @Email(message = "邮箱格式不正确")
    private String email;
}
