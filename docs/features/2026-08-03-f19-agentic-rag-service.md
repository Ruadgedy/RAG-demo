# Feature #19 详细设计：AgenticRagService + agent loop + 降级

## Context

Feature #19 为 Agentic RAG 的核心服务。`AgenticRagService` 接收用户问题，调用 Spring AI `ChatClient.tools(...)` 让 LLM 自主编排工具（KnowledgeBaseSearchTool / WebSearchTool / DirectAnswerTool）；当 LLM 不支持 tool-calling、总超时（默认 30s）或调用异常时，自动降级到 `RagService.chat()` 线性流水线。降级结果通过 `degraded=true` 字段记录。

## Design Alignment

- SRS FR-012（Agentic 问答模式）
- Design §11.3.5 AgenticRagService / §11.3.6 Agent Loop 时序 / §11.3.7 降级矩阵

## SRS Requirement

### FR-012 验收标准

1. `rag.mode=agentic` 且 KB 有文档 → agent 调 ≥2 次 tool 返回综合回答。
2. agent 总超时（默认 30s）→ 降级 linear RAG。
3. LLM 不支持 tool-calling → 回退 `RagService.chat()`。
4. KB 无文档 → 允许 agent 调用 Web/直答，不直接返回"暂无文档"。
5. 线性模式完全等价 `RagService.chat()`。

## Component Data-Flow Diagram

```
ChatService (上层路由)
  ↓
AgenticRagService.chat(chatId, message, kbId, history, window)
  ↓
KnowledgeBaseContext.set(kbId)
  ↓
CompletableFuture.supplyAsync(...)
  ↓
ChatClient.prompt().user(...).tools(kbTool, webTool, directTool)
  ↓
LLM 推理 → tool call → 框架执行 → 结果回填 → 多轮直到不再 tool call
  ↓
返回 answer
  ↓
[异常/超时] → markDegraded() → 降级 RagService.chat() 返回
```

## Interface Contract

| Method | Signature | Behavior | Returns | Raises |
|---|---|---|---|---|
| `AgenticRagService.chat` | `public RagService.ChatResult chat(String chatId, String message, UUID kbId, List<ChatMessage> history, int window)` | 设置 kb 上下文 + trace，超时兜底运行 agent loop；失败/超时降级 linear | ChatResult (含 agentMode/agentRounds/degraded) | 不抛出，异常 → 降级 |
| `AgenticRagService.retrieveForStreaming` | `public RagService.ChatResult retrieveForStreaming(String chatId, String message, UUID kbId, List<ChatMessage> history, int window)` | 同 chat，但适配流式：失败时返回 (null, list, null) 让 ChatService 决定降级 | ChatResult | 不抛出 |
| `AgenticRagService.shutdown` | `public void shutdown()` | 关闭 executor | — | — |

构造依赖：RagService、KnowledgeBaseSearchTool、WebSearchTool (可空)、DirectAnswerTool、ChatClient.Builder、AgentTraceCollector + 3 个配置（model/timeout/temperature）。

## Visual Rendering Contract (ui: true only)

N/A — backend-only feature, no visual output.

## Internal Sequence Diagram

```
ChatService → AgenticRagService.chat
  ├── KnowledgeBaseContext.set(kbId)
  ├── TraceContext.set(chatId)
  ├── CompletableFuture.supplyAsync
  │     ├── ChatClient.prompt().user().tools(kb, web, direct)
  │     │     ├── Round 1: LLM 推理 → 调用 kb_search(query) → 返回 ToolResult
  │     │     ├── Round 2: LLM 推理 → 调用 web_search(query) → 返回 ToolResult
  │     │     └── Round 3: LLM 推理 → 不再调 tool → 返回 answer
  │     └── (超时时) future.cancel(true) → 降级
  ├── (异常时) markDegraded() → 调用 RagService.chat()
  └── 返回 ChatResult (mode=agentic/linear, degraded=true/false, rounds=N)
```

## Algorithm / Core Logic

```
function chat(chatId, message, kbId, history, window):
    KnowledgeBaseContext.set(kbId)
    TraceContext.set(chatId)
    try:
        future = executor.submit(() ->
            chatClient.prompt()
                .user(message)
                .tools(kbTool, webTool, directTool)
                .advisors(MessageChatMemoryAdvisor.of(memory, chatId, window))
                .options(ToolCallingChatOptions.builder()
                    .model(agentModel)
                    .internalToolExecutionEnabled(true)
                    .temperature(0.0)
                    .build())
                .call()
                .content())
        answer = future.get(agentTimeoutMs, MILLISECONDS)
        return new ChatResult(answer, [], "agentic", rounds, false)
    catch TimeoutException:
        future.cancel(true)
        return degrade(message, kbId, history, window)
    catch Exception:
        return degrade(message, kbId, history, window)
    finally:
        KnowledgeBaseContext.clear()
        TraceContext.clear()

function degrade(...):
    return ragService.chat(message, kbId, history, window)
        .withAgentMode("linear").withDegraded(true).withAgentRounds(0)
```

降级矩阵（§11.3.7）：

| 触发条件 | 降级动作 |
|---|---|
| LLM/模型不支持 tool-calling | 回退 `RagService.chat()` |
| 单次 tool 超时 | 跳过该 tool，用已累积 context 继续 |
| 总超时（30s） | `future.cancel(true)` + 降级 linear |
| Web 搜索无 key/失败 | KB/直答仍可用，照常 agent loop |
| `rag.mode=linear` | 不进入 agent |

## State Diagram

```
[IDLE] --chat(message, kb)--> [RUNNING]
  [RUNNING] --正常完成--> [IDLE] (mode=agentic, degraded=false, rounds=N)
  [RUNNING] --TimeoutException--> [IDLE] (mode=linear, degraded=true, rounds=0)
  [RUNNING] --其他异常--> [IDLE] (mode=linear, degraded=true, rounds=0)
  [RUNNING] --LLM 不支持 tool-calling--> [IDLE] (mode=linear, degraded=true, rounds=0)
```

## Test Inventory

| ID | Category | Traces To | Input / Setup | Expected | Kills Which Bug? |
|---|---|---|---|---|---|
| A | FUNC/happy | FR-012 AC-1 | chat(chatId, q, kb, history, 5)，agent loop 返回非空 answer | ChatResult.agentMode=="agentic", degraded==false, rounds>=1 | agent loop 异常时 degrades 错标 |
| B | FUNC/timeout | FR-012 AC-2 | agent loop 超时 30s | ChatResult.agentMode=="linear", degraded==true, rounds==0 | 30s 超时未触发降级 |
| C | FUNC/exception | FR-012 AC-3 | agent 抛 RuntimeException | ChatResult.agentMode=="linear", degraded==true, rounds==0 | 异常被吞掉未降级 |
| D | FUNC/streaming-degrade | FR-012 AC-3 | retrieveForStreaming 异常 | 返回 ChatResult(null, list, null) | 流式路径异常直接抛出 |
| E | FUNC/streaming-success | FR-012 AC-1 | retrieveForStreaming 正常 | ChatResult 含 answer + retrieved | 工具结果与回答拼接错 |
| F | FUNC/cleanup | FR-012 AC-1 | 任何 chat 后 KnowledgeBaseContext/TraceContext | 二者 clear | ThreadLocal 泄漏（影响下次请求） |

**负向比**: B/C/D = 3/6 = 50%（≥ 40% 阈值）
**INTG**: 已有 5 个 Mockito 单测覆盖核心；真实 LLM 场景 PENDING-MANUAL（需要真 LLM key + 后端）

## Tasks

### Task 1: 验证测试覆盖（已实现）
- AgenticRagServiceTest 5 例已存在：chat/retrieveForStreaming happy/timeout/exception。
- 本轮确保 KnowledgeBaseContext/TraceContext 清理（Task F），并补 AgenticRagService 内的 trace 一致性。

### Task 2: Inline Check
- 核对 public chat/retrieveForStreaming/shutdown 三个方法签名匹配。

### Task 3: Feature-ST 文档
- ISO/IEC/IEEE 29119 格式，10 用例。

### Task 4: 报告
- 写入 docs/report/feature-19-agentic-rag-service-report.md。

### Task 5: 提交
- git commit 包含所有交付物。

## Verification Checklist

- [x] 5 个现有单测全部通过
- [x] 公共方法签名匹配（chat/retrieveForStreaming/shutdown）
- [x] 异常/超时/降级路径有覆盖
- [x] 真实 LLM Agent 场景 PENDING-MANUAL
- [x] Feature-ST 文档通过 validate_st_cases.py
- [x] Report 文件存在

## Clarification Addendum

无。
