package com.ragqa.model;

import jakarta.persistence.*;
import lombok.Data;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * 用户实体类
 *
 * 对应数据库表：users
 *
 * 作用：存储系统用户信息，用于身份认证
 *
 * 实现UserDetails接口：Spring Security用于获取用户认证信息
 * - getAuthorities(): 返回用户的权限列表
 * - getPassword()/setPassword(): 密码（加密存储）
 * - getUsername()/setUsername(): 用户名（唯一）
 * - isEnabled(): 账户是否启用
 *
 * 认证流程：
 * 1. 用户登录 → 输入用户名密码
 * 2. JwtAuthenticationFilter 拦截请求，验证JWT令牌
 * 3. 或通过UsernamePasswordAuthenticationToken直接认证
 * 4. 认证成功后，Spring Security将用户信息存入SecurityContext
 */
@Entity
@Table(name = "users")
@Data
public class User implements UserDetails {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    
    @Column(nullable = false, unique = true)
    private String username;
    
    @Column(nullable = false)
    private String password;
    
    private String email;
    
    @Column(name = "created_at")
    private LocalDateTime createdAt;
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_USER"));
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
