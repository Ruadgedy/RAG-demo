package com.ragqa.controller;

import com.ragqa.dto.AuthResponse;
import com.ragqa.dto.LoginRequest;
import com.ragqa.dto.RegisterRequest;
import com.ragqa.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "认证", description = "用户注册和登录接口")
public class AuthController {

    private final UserService userService;

    @Operation(summary = "用户注册", description = "注册新用户，成功后返回JWT令牌")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "注册成功",
                    content = @Content(schema = @Schema(implementation = AuthResponse.class))),
            @ApiResponse(responseCode = "400", description = "请求参数错误"),
            @ApiResponse(responseCode = "409", description = "用户名已存在")
    })
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@RequestBody RegisterRequest request) {
        return ResponseEntity.ok(userService.register(request));
    }

    @Operation(summary = "用户登录", description = "用户登录，验证成功后返回JWT令牌")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "登录成功",
                    content = @Content(schema = @Schema(implementation = AuthResponse.class))),
            @ApiResponse(responseCode = "401", description = "用户名或密码错误"),
            @ApiResponse(responseCode = "400", description = "请求参数错误")
    })
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@RequestBody LoginRequest request) {
        return ResponseEntity.ok(userService.login(request));
    }
}
