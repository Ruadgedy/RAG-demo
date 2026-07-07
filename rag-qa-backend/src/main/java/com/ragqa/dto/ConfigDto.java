package com.ragqa.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 全局配置 DTO（Agentic RAG F23）。
 *
 * <p>前端 {@code GET /api/config} 拿到的运行时默认值：
 * <ul>
 *   <li>{@code ragMode} —— 全局默认 RAG 模式（{@code "linear"} / {@code "agentic"}），
 *       新对话未覆盖时使用。后端配置 {@code rag.mode}。</li>
 *   <li>{@code defaultHistoryWindow} —— 全局默认滑动窗口，参考值。前端仍允许 per-KB 覆盖。</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConfigDto {
    private String ragMode;
    private Integer defaultHistoryWindow;
}
