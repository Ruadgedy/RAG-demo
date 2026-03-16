package com.ragqa.service;

import com.ragqa.model.User;
import com.ragqa.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 用户服务
 * 
 * 作用：处理用户注册、登录
 * 
 * API接口：
 * - POST /api/auth/register - 用户注册
 * - POST /api/auth/login    - 用户登录
 * - GET  /api/auth/users/{id} - 获取用户信息
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    /**
     * 用户注册
     * 
     * @param username 用户名（唯一）
     * @param password 密码
     * @param email    邮箱（可选）
     * @return 创建的用户对象
     */
    public User register(String username, String password, String email) {
        // 检查用户名是否已存在
        if (userRepository.existsByUsername(username)) {
            throw new RuntimeException("用户名已存在");
        }
        // 检查邮箱是否已被使用
        if (email != null && userRepository.existsByEmail(email)) {
            throw new RuntimeException("邮箱已被使用");
        }
        
        // 创建用户
        User user = new User();
        user.setUsername(username);
        user.setPassword(password);  // 注意：生产环境应该加密
        user.setEmail(email);
        
        return userRepository.save(user);
    }

    /**
     * 用户登录
     * 
     * @param username 用户名
     * @param password 密码
     * @return 登录成功的用户对象
     */
    public User login(String username, String password) {
        // 查找用户
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("用户不存在"));
        
        // 验证密码
        if (!user.getPassword().equals(password)) {
            throw new RuntimeException("密码错误");
        }
        
        return user;
    }

    /**
     * 根据ID获取用户
     */
    public User getUserById(UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("用户不存在"));
    }
}
