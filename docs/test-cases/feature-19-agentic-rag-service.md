# Feature #19 — AgenticRagService + agent loop + 降级

| 项目 | 内容 |
|------|------|
| **Feature ID** | #19 |
| **关联类** | `agent/AgenticRagService` |
| **关联需求** | FR-012（Agentic 问答模式：LLM 自主编排 + 降级） |
| **前置依赖** | F17 / F18 |
| **优先级** | P0（agentic 主体） |
| **编写日期** | 2026-07-07 |

---

## 1. 功能概述

### 1.1 背景

要让 LLM 作为 controller 自主编排 3 个 tool（KB / Web / 直答）。Spring AI 1.1.3 提供 `internalToolExecutionEnabled` 但无 `maxIterations` API，靠 `CompletableFuture` 总超时（30s）兜底。

### 1.2 关键实现

- `ChatClient.tools(kbTool, webTool, directTool).options(ToolCallingChatOptions.builder().internalToolExecutionEnabled(true).build())`
- `MessageChatMemoryAdvisor` 累积每轮 messages（含 tool 结果），LLM 最终基于累积 context 生成回答
- `CompletableFuture.supplyAsync(executor)` + `.get(30s)` 超时 → 取消 future + 降级 linear
- 异常路径：捕获后调 `RagService.chat()` 兜底
- `degraded` ThreadLocal 标记降级，ChatResult.degraded=true 暴露给 ChatService 写 `rag_metadata`

### 1.3 降级矩阵

| 触发条件 | 降级动作 | ChatResult.degraded |
|---|---|---|
| LLM 不支持 tool-calling | 异常 → catch → 降级 linear | true |
| agent 总超时 30s | cancel(true) → 降级 linear | true |
| tool 单次失败 | 跳过该 tool，继续累积（agent 仍可能成功） | false |
| `rag.mode=linear` 配置 | 直接走 RagService（不进 agent） | false |

---

## 2. 验收用例

### 2.1 ST-19-1 多 tool 编排（多跳）

**前置**：KB 有产品A文档；`TAVILY_API_KEY` 已配

| Step | 操作 | 期望 |
|---|---|---|
| 1 | 触发 agentic 问答 "产品A 国内售价 vs 国际售价" | LLM 至少调 2 次 tool |
| 2 | `SELECT round, tool_name FROM agent_trace WHERE chat_id=? ORDER BY round` | round=1 → round=2，两次 tool 调用，可能 kb_search+web_search 组合 |
| 3 | `ChatResult.agentMode='agentic'` + `agentRounds=2` + `degraded=false` | chat_history.rag_metadata agent_mode 字段为 "agentic" |
| 4 | 回答中含 KB 与 Web 双源 | 综合回答，不是单一源 |

### 2.2 ST-19-2 闲聊 → direct_answer

| Step | 操作 | 期望 |
|---|---|---|
| 1 | 触发 agentic 问答 "你好" | LLM 调 direct_answer |
| 2 | `SELECT * FROM agent_trace WHERE tool_name='direct_answer'` | 1 行，summary="闲聊/常识，无需检索" |
| 3 | ChatResult.agentRounds=1 | rag_metadata agent_rounds=1 |

### 2.3 ST-19-3 降级 - 超时

**前置**：把 `rag.agent.timeout-ms` 调极短（如 1ms）；或 mock LLM sleep >30s

| Step | 操作 | 期望 |
|---|---|---|
| 1 | 触发 agentic 问答 | 30s 后（或配置超时时间后）降级 |
| 2 | 日志搜 `agent loop 异常` 或 `总超时` | 命中降级路径 |
| 3 | ChatResult.degraded=true | rag_metadata degraded=true |
| 4 | 回答还是非空（来自 RagService） | 不挂 |

### 2.4 ST-19-4 降级 - LLM 不支持 tool-calling

**前置**：临时改 model 为不识 tool-calling 的模型（mock 抛异常即可）

| Step | 操作 | 期望 |
|---|---|---|
| 1 | 触发 agentic 问答 | catch RuntimeException |
| 2 | 日志 `agent loop 异常` | — |
| 3 | ChatResult.degraded=true | rag_metadata degraded=true |
| 4 | 回答来自 RagService（线性） | 不挂 |

### 2.5 ST-19-5 流式入口 retrieveForStreaming

| Step | 操作 | 期望 |
|---|---|---|
| 1 | `POST /api/chat/stream` 触发 | ChatService.streamChat 调 retrieveForStreaming |
| 2 | 流开始前 SSE 推送 `agent_step` 事件（F21） | — |
| 3 | 流完成落库：chat_history.rag_metadata.agent_mode='agentic' | — |

---

## 3. 自动化测试覆盖

| 层 | 通过条件 |
|---|---|
| 单测 | `AgenticRagServiceTest` 5 例：成功 / 超时降级 / 异常降级 / 流式成功 / 流式异常降级 |

---

## 4. 风险与可观测

- 超时上限 30s 影响流式首字延迟：F19 verification step 强调
- LLM 工具调用 token 消耗大：tool 返回截断 + 总超时
- agent_memory 自增会撑爆 context window：MessageWindowChatMemory.maxMessages=50 兜底

---

## 5. 关联

- 设计：Design §11.5 + 降级矩阵 §11.6
- Wave 1 总 PR：`PR-2026-07-04-agentic-rag-wave1.md`
- F20 路由：F20 commit `1d4b106`
- F21 trace：F21 commit `eccf4db`
