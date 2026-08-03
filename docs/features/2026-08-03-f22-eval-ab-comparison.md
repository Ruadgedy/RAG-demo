# Feature #22 详细设计：Eval A/B + ST 测试用例

## Context

Feature #22 提供 `POST /api/admin/eval/ab` REST 入口，对同一 question 同时跑 linear 与 agentic 两种模式，输出 `AbCompareResult` 报告（双侧产物 + 单边失败隔离）。用于回归对比与产品决策。

## Design Alignment

- SRS FR-012 / FR-013 / FR-014 验收基线
- F17~F21 工具链
- Design §11 Agentic RAG

## SRS Requirement

### FR-012 验收标准（节选）

- 同问题对比 linear vs agentic，输出量化对比报告。

## Component Data-Flow Diagram

```
EvalController POST /api/admin/eval/ab
  ├── { question, kbId, history, historyWindow }
  ├── EvalService.abCompare(...)
  │     ├── linearOutcome = try { RagService.chat(...) } catch { record error }
  │     ├── agenticOutcome = try { AgenticRagService.chat(ab-chatId, ...) } catch { record error }
  │     └── return AbCompareResult(linear, agentic)
  └── 返回 JSON 报告
```

## Interface Contract

| Method | Signature | Behavior |
|---|---|---|
| `EvalService.abCompare` | `public AbCompareResult abCompare(String question, UUID kbId, List<ChatMessage> history, int historyWindow)` | 串行 linear → agentic，单边 try/catch |
| `EvalController.ab` | `public ResponseEntity<AbCompareResult> ab(@RequestBody AbCompareRequest req)` | POST 入口 |
| `AbCompareResult` | record | 含 linear: ModeOutcome, agentic: ModeOutcome |
| `ModeOutcome` | record | answer / latencyMs / retrievedChunkCount / sourceCount / agentRounds / degraded / error |

## Visual Rendering Contract (ui: true only)

N/A — 后端评估服务。

## Internal Sequence Diagram

```
POST /api/admin/eval/ab
  → EvalService.abCompare(q, kb, history, window)
  → linear 串行: 调 RagService.chat (ab-linear-chatId, ...)
  → agentic 串行: 调 AgenticRagService.chat (ab-agentic-chatId, ...)
  → 组装 AbCompareResult
  → 返回 JSON
```

## Algorithm / Core Logic

```
function abCompare(q, kb, history, window):
    linear = safeRun("linear") { RagService.chat(q, kb, history, window) }
    agentic = safeRun("agentic") { AgenticRagService.chat(chatId, q, kb, history, window) }
    return AbCompareResult(linear, agentic)

function safeRun(mode, fn):
    try:
        return new ModeOutcome(answer=fn.answer, latencyMs=..., ..., error=null)
    catch e:
        return new ModeOutcome(answer=null, latencyMs=..., ..., error=e.message)
```

## State Diagram

N/A — 无状态服务调用。

## Test Inventory

| ID | Category | Traces To | Expected |
|---|---|---|---|
| A | FUNC/happy | FR-012 AC | 双成功：linear + agentic 均返回 answer 与 latency |
| B | FUNC/error | FR-012 AC | agentic 降级：agentic.degraded=true 不影响 linear |
| C | FUNC/contract | FR-012 AC | linear 抛异常被吞，agentic 仍可返回 |
| D | FUNC/contract | 字段 | 报告字段完整：answer / latencyMs / retrievedChunkCount / sourceCount / agentRounds / degraded / error |

**负向比**: 1/4 = 25% (C)
**INTG**: 真实 LLM/DB/Chroma 场景 PENDING-MANUAL

## Tasks

### Task 1: ST 文档（覆盖 ISO 格式）
### Task 2: 报告 + 提交

## Verification Checklist

- [x] EvalService.abCompare 已实现
- [x] EvalController 入口已就绪
- [x] 单边失败隔离
- [x] ST 文档通过 validate_st_cases.py

## Clarification Addendum

无。
