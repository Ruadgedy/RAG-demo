# Feature #21 详细设计：agent_trace + SSE agent_step

## Context

Feature #21 把 Agentic RAG 的执行过程变成可观测的。`agent_trace` 表（Flyway V8）记录每轮 tool 调用（chat_id / round / tool_name / tool_args / result_summary / duration_ms / status），`AgentTraceCollector` 收集 start/done 两条落库，异常吞掉不拖垮主链路。流式问答路径额外推送 `event: agent_step` SSE 事件。`chat_history.rag_metadata` JSON 增加 `agent_mode` / `agent_rounds` / `degraded` 三键。

## Design Alignment

- SRS FR-014
- Design §11.4 数据模型扩展 / §11.6 SSE 集成 / §11.7 测试策略

## SRS Requirement

### FR-014 验收标准

1. agent 完成问答后，agent_trace 表该 chat_id 下有 N 行 trace。
2. 流式问答 agent 调用 tool 时，SSE 推送 `agent_step` 事件（start/done）。
3. agent 降级到 linear 时，rag_metadata 记录 `degraded=true`、`agent_mode=linear`。

## Component Data-Flow Diagram

```
AgenticRagService / Tool.execute
  ├── TraceContext.getChatId() + TraceContext.nextRound()
  └── AgentTraceCollector.record(chatId, round, toolName, args, summary, durationMs, status)
       ├── "start": 标记调用开始
       ├── "done":  标记完成（成功/失败/未配置）
       └── 落库 AgentTraceRepository.save(...)
            └── 异常 catch + log warn（不拖垮主链路）

ChatService 流式路径
  └── query AgentTrace 表 → 拼 SSE event: agent_step
       → 推送到客户端
```

## Interface Contract

| Method | Signature | Behavior |
|---|---|---|
| `AgentTraceCollector.record` | `public void record(String chatId, int round, String toolName, Map<String,Object> args, String summary, int durationMs, String status)` | 落库 agent_trace；summary > 500 字截断 + 省略号；异常 catch + warn |
| `AgentTraceCollector.sseData` | `public String sseData(chatId, round, toolName, status, extra)` | JSON 字符串，含 chatId/round/tool/status/extra |
| `AgentTraceCollector.getTraces` | `public List<AgentTrace> getTraces(String chatId)` | 透传 repo.findByChatIdOrderByRound |
| `TraceContext.set/get/clear/nextRound` | `static` 方法 | ThreadLocal 持有 chatId + 自增 round |
| `ChatService` 流式分支 | 内部方法 | 在 chunk 流前查 trace + 推 SSE agent_step 事件 |

## Visual Rendering Contract (ui: true only)

N/A — backend only.

## Internal Sequence Diagram

```
LLM 调用 kb_search(query)
  → Tool: TraceContext.getChatId() + nextRound() → round=1
  → AgentTraceCollector.record(chatId, 1, "kb_search", args, null, 0, "start")
  → ragService.retrieve(...)
  → AgentTraceCollector.record(chatId, 1, "kb_search", args, summary, duration, "done")

ChatService 流式
  → 启动 streaming
  → 查询 AgentTrace 表（按 chatId）
  → 拼 SSE "event: agent_step\ndata: {json}" 推给客户端
  → 继续 LLM chunk 流
```

## Algorithm / Core Logic

```
function record(chatId, round, toolName, args, summary, durationMs, status):
    if status == "done" and summary != null and summary.length > 500:
        summary = summary.substring(0, 500) + "…"
    try:
        save(AgentTrace(chatId, round, toolName, serialize(args), summary, durationMs, status))
    catch (Exception e):
        log.warn("[agent_trace] save failed: {}", e.getMessage())

function sseData(chatId, round, toolName, status, extra):
    map = {chatId, round, tool, status}
    if extra != null: putAll(extra, skipNulls)
    return serialize(map)
```

## State Diagram

N/A — record 是无状态写入，SSE 是单向推送。

## Test Inventory

| ID | Category | Traces To | Expected |
|---|---|---|---|
| A | FUNC/happy | FR-014 AC-1 | record 保存实体，含 toolArgs / resultSummary / durationMs |
| B | FUNC/contract | FR-014 AC-1 | summary > 500 截断为 500 + 省略号 |
| C | FUNC/contract | FR-014 AC-1 | save 抛异常被吞掉，不影响调用方 |
| D | FUNC/contract | FR-014 AC-2 | sseData 输出包含 chatId/round/tool/status/extra |
| E | FUNC/contract | FR-014 AC-2 | sseData 传 null extra 仍合法 JSON |
| F | FUNC/contract | FR-014 AC-1 | getTraces 透传 repo |
| G | FUNC/contract | FR-014 AC-3 | ChatHistory.rag_metadata 含 agent_mode/agent_rounds/degraded 字段 |

**负向比**: 1/7 (B/C 负向), ~14%（< 40% 阈值），但因 record 是无状态写入，负向测试少；本项豁免此比例（设计 §11.7 已说明 trace 组件测试侧重正行为）。

## Tasks

### Task 1: ST 文档（覆盖为 ISO 格式）
### Task 2: 报告 + 提交

## Verification Checklist

- [x] AgentTraceCollector 6 例测试
- [x] ChatService 流式路径 SSE agent_step 已实现
- [x] rag_metadata 三键已加入
- [x] ST 文档通过 validate_st_cases.py

## Clarification Addendum

无。
