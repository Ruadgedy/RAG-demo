# PR 改动说明 — 2026-07-07 F22 Eval A/B + Wave 1 ST 测试用例

> **日期**：2026-07-07
> **分支**：`dev-agentic`
> **规模**：4 后端改动 + 6 ST 用例 = 10 文件
> **上游**：F21（trace 落库，给 agentic 提供可观测锚点）
> **关联**：Wave 1 最后一环；后续无新实现需求
> **性质**：F22 Worker cycle 闭环（评估能力 + 系统测试）

---

## 一、TL;DR

完成 Wave 1 F22「Eval A/B + Wave 1 ST 用例」收尾：

- **A/B 对比**：同题 `linear vs agentic` 同跑一次，对比报告含双模式产物（answer / latencyMs / retrievedChunkCount / sourceCount / agentRounds / degraded / error）
- **REST 入口**：`POST /api/admin/eval/ab` body `{question, kbId, historyWindow}` → 同步返回 `AbCompareResult`
- **单边失败隔离**：linear 抛了不阻塞 agentic，对照报告仍可读
- **Wave 1 ST 用例**：feature-17~22 共 6 篇 devtools 用例，覆盖 FR-012/013/014 验收标准

---

## 二、改动清单

### 2.1 后端

| 文件 | 改动要点 |
|---|---|
| `eval/EvalService.java` | 注入 `AgenticRagService`；新增 `ModeOutcome` / `AbCompareResult` 嵌套 record；`abCompare(question, kbId, history, historyWindow)` 串行执行双模式 |
| `eval/EvalController.java` | 加 `POST /api/admin/eval/ab` REST 入口（复用 `/api/admin/eval` 前缀，与 run() 同规鉴权） |
| `test/.../EvalServiceAbCompareTest.java` 🆕 | 3 例：双模式成功 / agentic 降级 / linear 异常吞噬 |

### 2.2 文档

| 文件 | 覆盖 |
|---|---|
| `docs/test-cases/feature-17-tool-abstraction.md` 🆕 | FR-013：Tool 抽象 + KB Tool；含 ST-17-1~4 |
| `docs/test-cases/feature-18-websearch-directanswer-tool.md` 🆕 | FR-013：Web + 直答；ST-18-1~4 |
| `docs/test-cases/feature-19-agentic-rag-service.md` 🆕 | FR-012：agent loop + 降级矩阵；ST-19-1~5 |
| `docs/test-cases/feature-20-rag-mode-routing.md` 🆕 | FR-012：rag.mode 路由 + per-conv；ST-20-1~5 |
| `docs/test-cases/feature-21-agent-trace-sse.md` 🆕 | FR-014：trace 落库 + SSE agent_step；ST-21-1~5 |
| `docs/test-cases/feature-22-eval-ab-comparison.md` 🆕 | FR-012~014 评估闭环：Eval A/B + 本批 ST 用例自身；ST-22-1~4 |

---

## 三、AbCompareResult 报告模型（API 契约）

```java
public record AbCompareResult(
    String question,
    String kbId,
    long timestamp,
    ModeOutcome linear,
    ModeOutcome agentic
) {}

public record ModeOutcome(
    String mode,                  // "linear" | "agentic"
    String answer,
    long latencyMs,
    int retrievedChunkCount,
    int sourceCount,
    Integer agentRounds,          // 仅 agentic 模式有意义
    Boolean degraded,             // 仅 agentic 模式有意义
    String error                  // null = OK
) {
    public boolean isOk() { return error == null; }
}
```

实际返回示例：

```json
{
  "question": "产品A价格",
  "kbId": "uuid",
  "timestamp": 1751846400000,
  "linear":  {"mode":"linear",  "answer":"2999元",                       "latencyMs":1234,"retrievedChunkCount":3,"sourceCount":1,"agentRounds":null,"degraded":null,"error":null},
  "agentic": {"mode":"agentic", "answer":"2999元（KB+Web 验证）",       "latencyMs":4521,"retrievedChunkCount":6,"sourceCount":2,"agentRounds":2,"degraded":false,"error":null}
}
```

---

## 四、关键设计

### 4.1 串行执行

`abCompare` 先 linear 后 agentic（不并发）：
- agentic 内部线程池 + TraceContext ThreadLocal，并行可能竞争
- linear 通常 < 1.5s，agentic < 30s，总耗时仍可接受

### 4.2 F21 chatId 前缀

abCompare 用 `ab-` 前缀生成 chatId（如 `ab-<uuid>`），让 agent_trace 行可与真实对话区分（运维 grep 方便）。

### 4.3 失败隔离

runLinearOutcome / runAgenticOutcome 各自 try/catch，单边失败不影响对侧：
- 字段 `error` 填异常 message
- 字段 `answer` 为 null
- 对侧 `error` 仍为 null

### 4.4 ST 用例口径

6 篇 ST 用例按 [devtools] + [curl] 双口径：
- **API 测试**：curl 直接打，方便 dev/staging 验证
- **DB 校验**：通过 `SELECT JSON_EXTRACT(rag_metadata, '$.agent_mode')` 验证落库
- **降级可观测**：注入超时/异常，验证报告 degraded 字段

---

## 五、测试

| 层级 | 通过 | 备注 |
|---|---|---|
| 后端单测 | 111/111（+3 F22） | `mvn test` —— EvalServiceAbCompareTest 3 例全过 |
| 后端回归 | 全量通过 | 0 失败 / 0 错误 / 4 跳过（PoC 守护） |
| 自动化覆盖 | 24 agentic 模块单测 | 含 F17 Tool / F18 / F19 / F21 全部 |

---

## 六、影响分析

| Change | Affected | Impact | Action |
|---|---|---|---|
| `EvalService` 新构造器字段 | Sprint 测试（mock EvalService） | **None**（测试不走 EvalService） | 无 |
| `EvalController` 加 /ab 端点 | 已有的 /api/admin/eval/run 调用方 | **Internal**（新增端点） | OpenAPI 文档同步 |
| A/B 跑 AgenticRagService.chat | 生产 Assistant 不会跑 | **None**（需登录 + admin 路由） | 鉴权已就位 |

---

## 七、Wave 1 整体落地状态

| Feature | 实现 commit | ST 用例 |
|---|---|---|
| F17 Tool 抽象 + KB Tool | `59c86e4` | `feature-17-tool-abstraction.md` 🆕 |
| F18 Web + 直答 | `886f7d1` | `feature-18-websearch-directanswer-tool.md` 🆕 |
| F19 AgenticRagService | `3d8ee12` | `feature-19-agentic-rag-service.md` 🆕 |
| F20 rag.mode 路由 | `1d4b106` | `feature-20-rag-mode-routing.md` 🆕 |
| F21 trace + SSE | `eccf4db` | `feature-21-agent-trace-sse.md` 🆕 |
| F22 Eval A/B | 本 PR | `feature-22-eval-ab-comparison.md` 🆕 |
| F23 前端 UI | `cd36912` | `feature-23-frontend-mode-toggle.md`（已出） |

Wave 1 完整落地：F17~F22 后端全过，F23 前端闭环，6 ST 用例覆盖 FR-012~014。

---

## 八、后续（非 F22）

- `feature-list.json` F17~F22 翻 passing（F23 已翻 passing 需 devtools 验证；F17~F22 因未有 user-driven E2E 仍 failing，行为验证全在本批 ST 用例）
- 前端 agent_step 事件可视化（流式动画进度条）
- A/B 报告持久化（DB / 文件）方便历史回溯
- per-KB 默认模式（当前仅 per-conv）

---

## 九、关联

- SRS：FR-012~014（docs/plans/2026-03-15-rag-qa-srs.md §3.5）
- Design：§11 全面（docs/plans/2026-03-15-rag-qa-design.md）
- Feature 分解：feature-list.json F22
- Wave 1 总 PR：`PR-2026-07-04-agentic-rag-wave1.md`
- 前序 PR：
  - `PR-2026-07-07-f21-agent-trace.md`
  - `PR-2026-07-07-f23-frontend-mode-toggle.md`
