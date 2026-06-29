package com.ragqa.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;
import java.util.List;

/**
 * CORS（跨域资源共享）配置
 *
 * 作用：控制哪些外部域名可以访问本服务的 API。
 *
 * 【2026-06-29 修复 P0-03】
 * 原配置使用 {@code addAllowedOriginPattern("*")} + {@code setAllowCredentials(true)}，
 * 按 CORS 规范是非法的（spec 明确禁止 wildcard + credentials 组合），
 * 浏览器会在请求时直接拒绝带 cookie/Authorization 的跨域请求。
 *
 * 修复方案：
 * 1. 改为从环境变量 {@code ALLOWED_ORIGINS} 读取允许的 origin 列表（逗号分隔）
 * 2. 提供开发环境默认（localhost:5173 是 Vite dev 端口，localhost:8080 是后端直连）
 * 3. credentials=true 保留（前端用 Bearer token 不受影响，但兼容性更好）
 *
 * 配置示例（.env）：
 *   ALLOWED_ORIGINS=http://localhost:5173,http://localhost:8080
 *
 * 生产部署建议：
 *   ALLOWED_ORIGINS=https://your-production-domain.com
 */
@Configuration
@Slf4j
public class CorsConfig {

    /**
     * 允许的 origin 列表，多个用英文逗号分隔
     * 默认值覆盖三种常见本地开发场景：Vite dev (5173)、后端直连 (8080)、Docker nginx (80)
     */
    @Value("${ALLOWED_ORIGINS:http://localhost:5173,http://localhost:8080,http://localhost}")
    private String allowedOrigins;

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();

        // 1) 解析 origin 列表 —— 不能用 wildcard + credentials，所以逐个 addAllowedOrigin
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        origins.forEach(config::addAllowedOrigin);
        log.info("CORS allowed origins: {}", origins);

        // 2) headers / methods 全开（API 层面已经有 JWT 鉴权保护，不依赖 CORS 做安全控制）
        config.addAllowedHeader("*");
        config.addAllowedMethod("*");

        // 3) credentials=true：让浏览器允许带 cookie / Authorization 跨域
        // 前端目前用 Bearer token 不需要 cookie，但保留以兼容未来 Session 化方案
        config.setAllowCredentials(true);

        // 4) 预检请求缓存 1 小时，避免每次跨域请求都触发 OPTIONS
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        return new CorsFilter(source);
    }
}