# Feature #21 — agent_trace 表 + trace 落库 + SSE agent_step

| 项目 | 内容 |
|------|------|
| **Feature ID** | #21 |
| **关联类** | `agent/trace/*`、`agent/AgenticRagService`、`service/ChatService`（SSE） |
| **关联需求** | FR-014（Agent 可观测与 trace 落库） |
| **前置依赖** | F19 / F20 |
| **优先级** | P1（可观测性 + UX） |
| **编写日期** | 2026-07-07 |

---

## 1. 功能概述

### 1.1 背景

agent loop 黑盒运行，开发者 / 用户看不到 LLM 做了什么。需要：
1. DB 留痕（每轮 tool 调用存一行）
2. 前端可视化（流式 SSE 推 agent_step 事件）
3. chat_history.rag_metadata 落 agent_mode / rounds / degraded

### 1.2 数据模型

```sql
CREATE TABLE agent_trace (
    id BIGINT PK, chat_id VARCHAR(36), round INT,
    tool_name VARCHAR(64), tool_args TEXT,
    result_summary TEXT, duration_ms INT,
    status VARCHAR(16) DEFAULT 'done', -- start | done
    created_at TIMESTAMP
);
```

### 1.3 SSE 事件格式

```
event: session-start
data: <convId>|<chatId>

event: agent_step
data: {"chatId":"...", "round":1, "tool":"kb_search", "status":"done", "durationMs":"320", "summary":"命中 3 条"}

event: agent_step
data: {...round 2...}

event: chunk
data: <LLM 流开始>
```

---

## 2. 验收用例

### 2.1 ST-21-1 trace 落库

**前置**：触发 1 次 agentic 问答（agent 跑了 2 轮 kb_search）

| Step | 操作 | 期望 |
|---|---|---|
| 1 | `SELECT * FROM agent_trace WHERE chat_id=? ORDER BY round` | 4 行（2× start + 2× done），按 round 排序 |
| 2 | round=1，tool_name='kb_search' | start + done 各一行 |
| 3 | done 行 `duration_ms>0`、`status='done'` | — |
| 4 | 同一 chatId 在 chat_history 中可 join | 该 chat_history.chat_id = 该 chatId |

### 2.2 ST-21-2 SSE agent_step 事件流

| Step | 操作 | 期望 |
|---|---|---|
| 1 | 浏览器抓流（DevTools Network → /api/chat/stream） | 事件顺序：session-start → agent_step(x N) → chunk(x M) → sources → end |
| 2 | agent_step data 是合法 JSON | 含 chatId/round/tool/status/durationMs/summary |
| 3 | 没触发 agentic 时（linear） | SSE 流中**无** agent_step 事件 |

### 2.3 ST-21-3 degraded=true 落库

**前置**：注入降级（timeout=10ms 或 mock LLM 抛异常）

| Step | 操作 | 期望 |
|---|---|---|
| 1 | 触发 agentic 问答 | 降级 |
| 2 | `SELECT JSON_EXTRACT(rag_metadata, '$.degraded') FROM chat_history WHERE chat_id=?` | `true` |
| 3 | `$.agent_mode` | `agentic`（区分"配置说 agentic" vs "实际跑了 linear"） |
| 4 | `$.agent_rounds` | `0`（agent 没跑 tool） |

### 2.4 ST-21-4 失败隔离

**前置**：故意让 `repo.save()` 抛异常（mock 或停 DB）

| Step | 操作 | 期望 |
|---|---|---|
| 1 | 触发 agentic 问答 | log warn（"落库失败"），但不抛给上层 |
| 2 | 回答仍正常返回 | 主链路不挂 |

### 2.5 ST-21-5 summary 截断

| Step | 操作 | 期望 |
|---|---|---|
| 1 | 工具返回超大文本（>500 字） | `result_summary` 列存 `≤501` 字（500 内容 + 省略号） |

---

## 3. 自动化测试覆盖

| 层 | 通过条件 |
|---|---|
| 单测 | `AgentTraceCollectorTest` 6 例：record 落库 / truncate 500 / 异常吞掉 / sseData / 空 extra / getTraces |
| 集成 | `AgenticRagServiceTest` 5 例（含新断言 agentMode/rounds/degraded） |

---

## 4. 性能 & 容量

- agent_trace 每轮 2 行（start + done）：复杂 agent（5 轮）→ 10 行/单次问答
- result_summary 截断 500 字：防 JSON 列爆
- 索引 `(chat_id, round)` 支持按对话聚合（"这一轮 agent 干了啥"）
- rag_metadata 加 3 字段对体积影响 < 100 字节/行

---

## 5. 关联

- 设计：Design §11.5（数据模型）
- F19：commit `3d8ee12`
- F23 前端 UI 订阅 agent_step：commit `cd36912`（前端只验证流，不动画化）
