# Feature #22 — Eval A/B 对比 + Wave 1 ST 测试用例

| 项目 | 内容 |
|------|------|
| **Feature ID** | #22 |
| **关联类** | `eval/EvalService.abCompare`、`eval/EvalController.abCompare`、`docs/test-cases/feature-17~22.md` |
| **关联需求** | FR-012 / FR-013 / FR-014 |
| **前置依赖** | F21（trace 落库，给 agentic 提供可观测锚点） |
| **优先级** | P2（评估与回归） |
| **编写日期** | 2026-07-07 |

---

## 1. 功能概述

### 1.1 背景

Wave 1 引入 agentic 模式，需要量化对比 linear vs agentic。同题、同 KB、同历史：
- linear：现有流水线，单次检索 + rerank + generate
- agentic：LLM 自主调多 tool

需要一份对比报告（耗时、回答内容、agent_rounds、degraded、错误），帮团队做下一阶段的产品决策（默认 linear 还是 agentic、按 KB 切换……）。

### 1.2 A/B 接口

- `POST /api/admin/eval/ab` body `{"question":"...", "kbId":"...", "historyWindow":3}` → `AbCompareResult`

### 1.3 AbCompareResult 报告字段

```json
{
  "question": "产品A价格",
  "kbId": "uuid",
  "timestamp": 1751846400000,
  "linear": {
    "mode": "linear",
    "answer": "2999元",
    "latencyMs": 1234,
    "retrievedChunkCount": 3,
    "sourceCount": 1,
    "agentRounds": null,
    "degraded": null,
    "error": null
  },
  "agentic": {
    "mode": "agentic",
    "answer": "2999元（KB+Web 验证）",
    "latencyMs": 4521,
    "retrievedChunkCount": 6,
    "sourceCount": 2,
    "agentRounds": 2,
    "degraded": false,
    "error": null
  }
}
```

---

## 2. 验收用例

### 2.1 ST-22-1 A/B 正常双侧成功

| Step | 操作 | 期望 |
|---|---|---|
| 1 | `curl -X POST http://localhost:8080/api/admin/eval/ab -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"question":"产品A价格","kbId":"<uuid>"}'` | 200，response body 如上格式 |
| 2 | 两边 `error=null` | 报告可解读 |
| 3 | linear.latencyMs < agentic.latencyMs（默认 agent 更慢） | 多数情况如此 |
| 4 | agentic.agentRounds ≥ 1 | 至少 1 轮 tool |

### 2.2 ST-22-2 agentic 降级 → report 标 degraded=true

**前置**：强制 agentic 失败（注入异常 / timeout=10ms）

| Step | 操作 | 期望 |
|---|---|---|
| 1 | 触发 A/B | 200 |
| 2 | `agentic.error=null`（agent 内部 catch 并降级到 linear，最终产出可用 answer） | — |
| 3 | `agentic.degraded=true` | 报告标了 |
| 4 | `agentic.agentRounds=0` | agentic 没真跑 |
| 5 | `agentic.mode='agentic'` | 仍标识为 agentic（区分"配置" vs "实际跑"） |

### 2.3 ST-22-3 单侧失败不阻塞对侧

**前置**：让 RagService 抛异常

| Step | 操作 | 期望 |
|---|---|---|
| 1 | 触发 A/B | 200 |
| 2 | `linear.error="Chroma 突然挂了"` | 错误填进 error 字段 |
| 3 | `linear.answer=null` | — |
| 4 | `agentic.answer="..."`（agentic 仍跑了，或自身降级但有 answer） | 对侧不受影响 |

### 2.4 ST-22-4 ST 测试用例落地

**前置**：Wave 1 F17~F21 + F23 全部完成

| 文件 | 覆盖 |
|---|---|
| `feature-17-tool-abstraction.md` | Tool 抽象 / KB Tool / ThreadLocal 注入 kbId |
| `feature-18-websearch-directanswer-tool.md` | WebSearchTool / DirectAnswerTool |
| `feature-19-agentic-rag-service.md` | AgenticRagService / 多 tool 编排 / 降级 |
| `feature-20-rag-mode-routing.md` | rag.mode 路由 / per-conv / 持久化 |
| `feature-21-agent-trace-sse.md` | agent_trace 落库 / SSE agent_step / degraded |
| `feature-22-eval-ab-comparison.md`（本文件）| Eval A/B |

通过条件：6 文件 commit 入 `docs/test-cases/`

---

## 3. 自动化测试覆盖

| 层 | 通过条件 |
|---|---|
| 单测 | `EvalServiceAbCompareTest` 3 例：双成功 / agentic 降级 / linear 异常吞噬 |

---

## 4. 性能与可观测

- A/B 默认串行（先 linear 后 agentic）：避免线程池竞争 + ThreadLocal 错乱
- 总耗时上限 ≈ max(linear.latencyMs, agentic.latencyMs) + agentic 自身 30s 超时
- `ab-` 前缀的 chatId 写入 agent_trace，但 kebab-case 易识别（"ab-uuid"），可与真实对话区分

---

## 5. 不在范围内

- A/B 在浏览器端按钮触发（前端 toggle 已有，但自动跑 A/B 留给运维）
- A/B 报告持久化（DB / 文件）：当前同步返回，留 P3
- 多 KB × 多 query 批跑：仍是 `POST /api/admin/eval/run` 数据集跑批

---

## 6. 关联

- Wave 1 总 PR：`PR-2026-07-04-agentic-rag-wave1.md`
- 设计：Design §11（评估章节 + 数据模型 §11.5）
- F17~F21 实现 commits：见 `task-progress.md` Session 5/6/7
