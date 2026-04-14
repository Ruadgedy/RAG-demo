package com.ragqa.dto;

import lombok.Data;

/**
 * 注册请求DTO
 *
 * 作用：封装新用户注册的信息
 */
@Data
public class RegisterRequest {
    /** 用户名（唯一） */
    private String username;

    /** 密码（会加密存储） */
    private String password;

    /** 邮箱（可选） */
    private String email;
}
