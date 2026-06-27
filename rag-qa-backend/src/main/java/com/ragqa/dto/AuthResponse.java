package com.ragqa.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 认证响应DTO
 *
 * 作用：封装登录/注册成功的响应数据
 *
 * 字段说明：
 * - token: JWT令牌（用于后续请求的认证）
 * - username: 用户名
 *
 * 使用方式：
 * - 登录/注册成功后返回
 * - 客户端将token存储，后续请求携带在Authorization头中
 *
 * 注解说明：
 * - @Data + @AllArgsConstructor 会抑制 @Data 隐式生成的无参构造器，
 *   显式加 @NoArgsConstructor 恢复，用于 Jackson 反序列化与测试构建对象
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AuthResponse {
    /** JWT认证令牌 */
    private String token;

    /** 用户名 */
    private String username;
}
