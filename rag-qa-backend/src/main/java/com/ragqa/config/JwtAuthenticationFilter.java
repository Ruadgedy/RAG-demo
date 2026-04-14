package com.ragqa.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Lazy;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import com.ragqa.service.JwtService;

import java.io.IOException;

/**
 * JWT认证过滤器
 *
 * 作用：拦截每个请求，解析JWT令牌并设置认证信息
 *
 * 工作流程：
 * 1. 从请求头提取 Authorization: Bearer <token>
 * 2. 解析JWT令牌，验证签名和有效期
 * 3. 从令牌获取用户名，加载用户详情
 * 4. 验证通过后，将认证信息存入SecurityContext
 * 5. 后续Spring Security根据SecurityContext判断权限
 *
 * 特点：
 * - OncePerRequestFilter：每个请求只执行一次
 * - 使用@Lazy避免循环依赖
 * - 无效令牌不影响请求继续（静默忽略）
 *
 * 注意事项：
 * - 不验证时放行，让请求继续（permitAll的接口不需要认证）
 * - 异常不抛出，避免阻断正常的公开请求
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;

    public JwtAuthenticationFilter(JwtService jwtService, @Lazy UserDetailsService userDetailsService) {
        this.jwtService = jwtService;
        this.userDetailsService = userDetailsService;
    }

    /**
     * 过滤器核心方法
     *
     * @param request HTTP请求
     * @param response HTTP响应
     * @param filterChain 过滤器链
     */
    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        // 从请求头提取JWT令牌
        final String authHeader = request.getHeader("Authorization");
        final String jwt;
        final String username;

        // 没有令牌或格式不对，放行（公开接口不需要认证）
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        // 提取令牌（去掉 "Bearer " 前缀）
        jwt = authHeader.substring(7);

        try {
            // 从令牌解析用户名
            username = jwtService.extractUsername(jwt);

            // 用户名有效且当前没有认证信息
            if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                // 加载用户详情
                UserDetails userDetails = this.userDetailsService.loadUserByUsername(username);

                // 验证令牌是否有效（签名正确 + 未过期）
                if (jwtService.isTokenValid(jwt, userDetails)) {
                    // 创建认证Token（不包含凭证，只有用户信息和权限）
                    UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                            userDetails,       // 用户信息
                            null,              // 凭证（密码等，为null表示已认证）
                            userDetails.getAuthorities()  // 权限列表
                    );
                    // 设置请求详情（IP、sessionId等）
                    authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    // 将认证信息存入SecurityContext
                    SecurityContextHolder.getContext().setAuthentication(authToken);
                }
            }
        } catch (Exception e) {
            // JWT解析或验证失败，静默处理（可能是过期或伪造的令牌）
            // 不抛出异常，让请求继续（未认证的请求会根据URL权限判断是否允许）
        }

        // 继续过滤器链
        filterChain.doFilter(request, response);
    }
}
