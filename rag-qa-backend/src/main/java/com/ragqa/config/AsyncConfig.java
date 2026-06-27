package com.ragqa.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步任务执行器配置
 *
 * 作用：为 @Async 注解提供生产级线程池，替代 Spring 默认的
 *      SimpleAsyncTaskExecutor（每次新建线程，无复用、无队列、无背压）
 *
 * 使用方式：
 *   在 @Async 注解中指定本 Bean 名称即可：
 *   {@code @Async("documentProcessExecutor")}
 *
 * 设计要点：
 * 1. 核心线程常驻（避免冷启动延迟）
 * 2. 队列缓冲（突发上传不至于立即扩容）
 * 3. CallerRunsPolicy 背压（队列满时让调用方线程执行，给上游 HTTP 响应施压）
 * 4. 优雅停机（容器关闭时等待任务完成，避免数据不一致）
 * 5. 自定义线程名前缀（thread dump 可定位业务）
 *
 * 容量规划（与 MySQL 连接池、Ollama HTTP 连接配合）：
 * - core=4：常态 4 个文档并行处理
 * - max=8：突发时最多 8 个
 * - queue=100：缓冲 100 个待处理任务
 * - MySQL HikariCP 默认 max=10，需保证并发任务数 ≤ DB 连接数
 */
@Configuration
@EnableAsync
@Slf4j
public class AsyncConfig {

    /** 文档处理异步执行器 Bean 名称，供 @Async 引用 */
    public static final String DOCUMENT_PROCESS_EXECUTOR = "documentProcessExecutor";

    @Bean(name = DOCUMENT_PROCESS_EXECUTOR)
    public Executor documentProcessExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("doc-async-");
        // 队列满 + 线程达 maxPoolSize 时，让调用方线程执行，给上游施压避免雪崩
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 容器关闭时等待队列内任务执行完毕（避免强杀导致文档处理到一半）
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();

        log.info("已初始化文档处理线程池: core={}, max={}, queue={}",
                executor.getCorePoolSize(), executor.getMaxPoolSize(), executor.getQueueCapacity());

        return executor;
    }
}
