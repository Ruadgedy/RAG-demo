package com.ragqa.eval;

import com.ragqa.model.EvalRun;
import com.ragqa.model.EvalRunItem;
import com.ragqa.repository.EvalRunItemRepository;
import com.ragqa.repository.EvalRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 评估 REST 接口
 *
 * - POST /api/admin/eval/run        启动跑批（同步返回结果；跑批通常 < 2 分钟）
 * - GET  /api/admin/eval/run/{id}   查询历史跑批
 * - GET  /api/admin/eval/runs        列出某 KB 的所有跑批（按时间倒序）
 *
 * 【2026-06-29 新增 P2-01】
 *
 * 鉴权：复用现有 SecurityConfig 的 .requestMatchers("/api/**").authenticated()
 * 任何登录用户可访问（如果未来需要限制，加 @PreAuthorize("hasRole('ADMIN')")）
 */
@RestController
@RequestMapping("/api/admin/eval")
@RequiredArgsConstructor
@Slf4j
public class EvalController {

    private final EvalService evalService;
    private final EvalRunRepository runRepository;
    private final EvalRunItemRepository itemRepository;

    /**
     * 启动一次评估跑批
     */
    @PostMapping("/run")
    public ResponseEntity<?> run(@RequestBody EvalService.RunRequest request) {
        log.info("收到评估请求: {}", request);
        try {
            EvalService.RunResult result = evalService.run(request);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            log.warn("评估参数错误: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("评估跑批失败", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "评估跑批失败: " + e.getMessage()));
        }
    }

    /**
     * 查询历史跑批（含 summary）
     */
    @GetMapping("/run/{id}")
    public ResponseEntity<?> getRun(@PathVariable String id) {
        return runRepository.findById(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 列出某 KB 的所有跑批
     */
    @GetMapping("/runs")
    public List<EvalRun> listRuns(@RequestParam String kbId) {
        return runRepository.findByKbIdOrderByStartedAtDesc(kbId);
    }

    /**
     * 查询某次跑批的所有明细
     */
    @GetMapping("/run/{id}/items")
    public List<EvalRunItem> getItems(@PathVariable String id) {
        return itemRepository.findByRunId(id);
    }
}