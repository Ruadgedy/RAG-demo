package com.ragqa.agent.trace;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Agent tool 调用轨迹记录（Agentic RAG F21）。
 *
 * <p>每轮 tool 调用存一行，供前端展示思考过程 + 质量回溯。
 */
@Data
@Entity
@Table(name = "agent_trace")
public class AgentTrace {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 关联 chat_history.id */
    @Column(name = "chat_id", length = 36, nullable = false)
    private String chatId;

    @Column(name = "round", nullable = false)
    private Integer round;

    @Column(name = "tool_name", length = 64, nullable = false)
    private String toolName;

    /** JSON 格式入参 */
    @Column(name = "tool_args", columnDefinition = "TEXT")
    private String toolArgs;

    /** tool 返回摘要（截断 500 字） */
    @Column(name = "result_summary", columnDefinition = "TEXT")
    private String resultSummary;

    @Column(name = "duration_ms")
    private Integer durationMs;

    @Column(name = "status", length = 16, nullable = false)
    private String status = "done";

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
