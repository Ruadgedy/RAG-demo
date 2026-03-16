package com.ragqa;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * RAG智能问答系统 - 主程序入口
 * 
 * 功能：提供知识库管理、文档上传、RAG问答的REST API服务
 * 
 * 启动流程：
 * 1. 加载.env配置文件（API密钥、数据库配置等）
 * 2. 初始化Spring Boot应用
 * 3. 自动创建数据库表（根据@Entity注解）
 * 
 * 主要模块：
 * - 知识库管理：创建、删除、查询知识库
 * - 文档处理：上传、解析、切片、向量化
 * - RAG问答：检索相关文档、调用LLM生成回答
 * - 用户管理：注册、登录
 * - 对话历史：保存和查询聊天记录
 */
@SpringBootApplication
@EnableAsync  // 启用异步处理，用于文档后台上传处理
public class RagQaApplication {
    
    public static void main(String[] args) {
        // 1. 加载.env配置文件
        // 将.env中的变量注入到系统属性中，供Spring配置使用
        Dotenv dotenv = Dotenv.configure()
            .ignoreIfMissing()  // 如果.env文件不存在则忽略
            .load();
        
        // 将.env中的配置注入到System.setProperty中
        // 后续Spring读取${VAR}格式的配置时会从系统属性中获取
        dotenv.entries().forEach(entry -> 
            System.setProperty(entry.getKey(), entry.getValue())
        );
        
        // 2. 启动Spring Boot应用
        SpringApplication.run(RagQaApplication.class, args);
    }
}
