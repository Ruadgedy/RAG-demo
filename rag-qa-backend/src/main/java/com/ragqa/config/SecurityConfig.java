package com.ragqa.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import com.ragqa.repository.UserRepository;

/**
 * Spring Security安全配置
 *
 * 作用：配置应用的认证和授权规则
 *
 * 安全策略：
 * 1. JWT无状态认证：不使用session，每次请求携带令牌
 * 2. 密码加密：BCrypt算法，不可逆加密
 * 3. 路径权限：
 *    - /api/auth/** : 公开（注册、登录）
 *    - /api/** : 需要认证
 *    - 其他 : 公开
 *
 * 认证流程：
 * 1. JwtAuthenticationFilter 拦截请求，解析JWT令牌
 * 2. 令牌有效则设置SecurityContext中的认证信息
 * 3. Spring Security根据SecurityContext判断是否有权限访问
 *
 * 依赖说明：
 * - 使用@Lazy解决循环依赖问题（JwtAuthenticationFilter → UserDetailsService → AuthenticationManager → SecurityConfig）
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final UserRepository userRepository;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public SecurityConfig(UserRepository userRepository, JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.userRepository = userRepository;
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    /**
     * 用户详情服务
     *
     * 根据用户名从数据库查找用户
     */
    @Bean
    public UserDetailsService userDetailsService() {
        return username -> userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("用户不存在"));
    }

    /**
     * 密码加密器
     *
     * 使用BCrypt加密，不可逆
     * 相同密码每次加密结果不同（自带盐值）
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 认证提供者
     *
     * 负责验证用户名密码是否匹配
     */
    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider();
        authProvider.setUserDetailsService(userDetailsService());
        authProvider.setPasswordEncoder(passwordEncoder());
        return authProvider;
    }

    /**
     * 认证管理器
     *
     * 处理登录时的用户名密码验证
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    /**
     * 安全过滤器链
     *
     * 配置URL权限和认证流程
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // 禁用CSRF（因为使用JWT，不需要防止CSRF）
            .csrf(AbstractHttpConfigurer::disable)
            // 配置URL权限
            .authorizeHttpRequests(auth -> auth
                // 认证接口公开
                .requestMatchers("/api/auth/**").permitAll()
                // Swagger UI 和 API 文档公开
                .requestMatchers("/swagger-ui/**").permitAll()
                .requestMatchers("/swagger-ui.html").permitAll()
                .requestMatchers("/v3/api-docs/**").permitAll()
                .requestMatchers("/v3/api-docs.yaml").permitAll()
                // Actuator 健康检查公开
                .requestMatchers("/actuator/health").permitAll()
                // API接口需要认证
                .requestMatchers("/api/**").authenticated()
                // 其他请求放行
                .anyRequest().permitAll()
            )
            // 禁用session（使用JWT无状态认证）
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )
            // 注册认证提供者
            .authenticationProvider(authenticationProvider())
            // 在用户名密码过滤器之前添加JWT过滤器
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
