package com.ragqa.dto;

import lombok.Data;

/**
 * 登录请求DTO
 *
 * 作用：封装用户登录的用户名和密码
 */
@Data
public class LoginRequest {
    /** 用户名 */
    private String username;

    /** 密码 */
    private String password;
}
