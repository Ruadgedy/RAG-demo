package com.ragqa.controller;

import com.ragqa.dto.AuthResponse;
import com.ragqa.dto.LoginRequest;
import com.ragqa.dto.RegisterRequest;
import com.ragqa.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 认证控制器
 *
 * 作用：处理用户注册和登录
 *
 * 接口说明：
 * - /api/auth/register: 用户注册，成功后返回JWT令牌
 * - /api/auth/login: 用户登录，验证成功后返回JWT令牌
 *
 * 认证机制：
 * - 使用JWT（JSON Web Token）实现无状态认证
 * - 注册/登录接口无需认证（permitAll）
 * - 其他API需要携带JWT令牌访问
 *
 * JWT令牌使用：
 * - 在请求头的 Authorization 字段中携带
 * - 格式：Bearer <token>
 * - JwtAuthenticationFilter 自动验证令牌
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;

    /**
     * 用户注册
     *
     * @param request 包含username、password、email
     * @return AuthResponse(token, username)
     */
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@RequestBody RegisterRequest request) {
        return ResponseEntity.ok(userService.register(request));
    }

    /**
     * 用户登录
     *
     * @param request 包含username、password
     * @return AuthResponse(token, username)
     */
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@RequestBody LoginRequest request) {
        return ResponseEntity.ok(userService.login(request));
    }
}
