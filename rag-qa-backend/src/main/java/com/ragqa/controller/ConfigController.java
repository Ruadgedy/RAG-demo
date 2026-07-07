package com.ragqa.controller;

import com.ragqa.dto.ConfigDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 全局运行时配置（Agentic RAG F23）。
 *
 * <p>暴露前端需要的全局默认值，避免把后端配置硬编码到前端。当前只读：
 * <ul>
 *   <li>{@code rag.mode} 默认值（linear/agentic），给前端 mode toggle 当新对话默认态</li>
 *   <li>{@code rag.history.turns} 默认窗口</li>
 * </ul>
 *
 * <p>鉴权：受 Spring Security 保护（与 {@code /api/**} 同规），已登录用户才能取。
 */
@RestController
@RequestMapping("/api/config")
@Tag(name = "Config", description = "全局运行时配置")
@Slf4j
public class ConfigController {

    @Value("${rag.mode:linear}")
    private String defaultRagMode;

    @Value("${rag.history.turns:3}")
    private int defaultHistoryWindow;

    @Operation(summary = "取全局默认值")
    @GetMapping
    public ResponseEntity<ConfigDto> getConfig() {
        log.debug("[config] 返回全局默认值: ragMode={}, defaultHistoryWindow={}",
                defaultRagMode, defaultHistoryWindow);
        return ResponseEntity.ok(new ConfigDto(defaultRagMode, defaultHistoryWindow));
    }
}
