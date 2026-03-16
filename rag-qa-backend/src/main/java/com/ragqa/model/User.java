package com.ragqa.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 用户实体类
 * 
 * 对应数据库表：users
 * 
 * 作用：存储用户注册信息
 */
@Data
@Entity
@Table(name = "users")
public class User {
    
    /** 用户唯一标识(UUID) */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    
    /** 用户名（唯一，不能重复） */
    @Column(nullable = false, unique = true)
    private String username;
    
    /** 密码（明文存储，生产环境应加密） */
    @Column(nullable = false)
    private String password;
    
    /** 邮箱（可选） */
    private String email;
    
    /** 创建时间 */
    @Column(name = "created_at")
    private LocalDateTime createdAt;
    
    /**
     * 在创建记录前自动设置创建时间
     */
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
