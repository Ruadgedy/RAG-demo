# PR 改动说明 — 2026-07-07 F21 agent_trace 表 + trace 落库 + SSE agent_step

> **日期**：2026-07-07
> **分支**：`dev-agentic`
> **规模**：3 新文件 + 8 改动 + 4 测试更新
> **上游**：F19 AgenticRagService + F20 rag.mode 路由（已落地）
> **性质**：F21 Worker cycle 闭环（Red → Green → Persist）

---

## 一、TL;DR

完成 Wave 1 F21「agent 可观测与 trace 落库」全栈接线：

- **DB** 新增 `agent_trace` 表（Flyway V8），每轮 tool 调用存一行
- **写** 三个 Tool（kb_search / web_search / direct_answer）在每次调用前后各记一条 trace（status=start / done）
- **读** ChatService 流式通道拿 trace 后转 SSE `agent_step` 事件，前端可订阅
- **元数据** `rag_metadata` 增 `agent_mode` / `agent_rounds` / `degraded` 三字段，前端 / 评估可还原"用户选了 agentic，agent 实际跑几轮、是否降级"
- **chatId 对齐** agent_trace.chat_id 与 chat_history.chat_id 用同一个 UUID，可 join 追溯

未触动：linear RAG 路径行为不变（默认 `rag.mode=linear`），FR-012~013 现有回归不受影响。

---

## 二、改动清单

### 2.1 新增

| 文件 | 作用 |
|---|---|
| `V8__add_agent_trace.sql` | Flyway：建 `agent_trace` 表（id / chat_id / round / tool_name / tool_args / result_summary / duration_ms / status）+ 索引 `(chat_id)`、`(chat_id, round)` |
| `AgentTrace.java` | JPA 实体，映射 agent_trace 表 |
| `AgentTraceRepository.java` | JpaRepository，加 `findByChatIdOrderByRound(chatId)` |
| `AgentTraceCollector.java` | 核心服务：`record(chatId, round, toolName, args, summary, duration, status)` 落库 + 异常吞掉（不拖垮主链路）；`sseData(...)` 拼 SSE JSON；`getTraces(chatId)` 读取 + `truncate(s, 500)` 防 summary 爆 |
| `TraceContext.java` | ThreadLocal 持有 `chatId` + 自增 `round` 计数器（参考 `KnowledgeBaseContext` 模式） |
| `AgentTraceCollectorTest.java` | 单测 6 例：record 落库 / truncate 500 / 异常吞掉 / sseData JSON 字段 / 空 extra / getTraces 透传 repo |

### 2.2 改动

| 文件 | 改动要点 |
|---|---|
| `RagService.ChatResult` | 增 3 字段 `agentMode` / `agentRounds` / `degraded`；保留原 4 参构造委托到 7 参，linear 路径零修改 |
| `AgenticRagService` | 注入 `AgentTraceCollector`；`chat(...)` / `retrieveForStreaming(...)` 签名加 `chatId` 参数；入口 `TraceContext.set(chatId)`，出口 `clear()`；超时/异常降级后用 `markDegraded()` 标记 `degraded=true` |
| `KnowledgeBaseSearchTool` | 注入 collector；调用前后各记一条；`chatId` 从 `TraceContext.getChatId()` 取，`null` 时跳过（防御） |
| `WebSearchTool` | 同上 + 无 TAVILY_API_KEY 时单条 done trace |
| `DirectAnswerTool` | 同上 |
| `ChatService.chat()` | 一次性生成 `chatId` 传给 agent + 落 DB；`buildRagMetadataJson(...)` 加 3 字段；`saveTurn(...)` 签名加 `chatId` 入参 |
| `ChatService.streamChat()` | chatId 提早生成（agent / DB 共用）；agent 完成后查 `agentTraceCollector.getTraces(chatId)` → `buildAgentStepEvents(...)` 拼 SSE `agent_step` 事件，在 chunk 流之前发出 |
| `ChatService.handleEmptyKnowledgeBase(...)` | 签名加 `chatId` + `retrievalResult` 透传 agent 字段 |
| `buildRagMetadataJson(...)` | 增 `agent_mode` / `agent_rounds` / `degraded` 三键 |
| `AgenticRagServiceTest` | mock `AgentTraceCollector`；新增签名 `chat(chatId, ...)`；新断言 `agentMode=agentic` / `agentRounds` / `degraded` |
| `DirectAnswerToolTest` / `WebSearchToolTest` / `ChatServiceTest` | 适配新构造器签名（collector 用 `null` 或 mock，因测试不走 agentic 上下文） |

---

## 三、数据模型

### 3.1 agent_trace 表

```sql
CREATE TABLE agent_trace (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    chat_id VARCHAR(36) NOT NULL,           -- 关联 chat_history.id
    round INT NOT NULL,                     -- 从 1 开始
    tool_name VARCHAR(64) NOT NULL,         -- kb_search / web_search / direct_answer
    tool_args TEXT,                         -- JSON
    result_summary TEXT,                    -- 截断 500 字
    duration_ms INT,                        -- done 时填
    status VARCHAR(16) NOT NULL DEFAULT 'done',  -- start / done
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_chat (chat_id),
    INDEX idx_chat_round (chat_id, round)
);
```

### 3.2 chat_history.rag_metadata 增量

```json
{
  "retrieved_doc_count": 3,        // 已有
  "retrieved_chunk_count": 7,      // 已有
  "retrieved_doc_ids": ["..."],    // 已有
  "retrieval_duration_ms": 245,   // 已有
  "rewritten_query": "...",        // 已有
  "agent_mode": "agentic",        // F21 新
  "agent_rounds": 2,              // F21 新
  "degraded": false               // F21 新
}
```

---

## 四、SSE 事件格式（F21 SSE agent_step）

`streamChat` 在 chunk 流之前发出 `agent_step` 事件（每行 trace 一条）：

```
event: session-start
data: <conversationId>|<chatId>

event: agent_step
data: {"chatId":"<chatId>","round":1,"tool":"kb_search","status":"done","durationMs":"320","summary":"命中 3 条；来源=...}

event: agent_step
data: {...round 2...}

event: chunk
data: <LLM 流开始>
```

前端订阅 `event.name === 'agent_step'` 即可拿到"思考过程"动画数据。

---

## 五、关键设计

### 5.1 chatId 唯一性

整个 ChatService 层只生成一次 `chatId = UUID.randomUUID().toString()`，传给：
1. `AgenticRagService` 写入 `TraceContext` → tool 落地 `agent_trace.chat_id`
2. `ChatHistory` 写入 `chat_history.chat_id`

**保证 agent_trace 与 chat_history 可 join 追溯**。

### 5.2 TraceContext ThreadLocal 模式

参考 `KnowledgeBaseContext`：
- 入口 `set(chatId)` 初始化 chatId + round=0
- `@Tool` 方法体内 `nextRound()` 自增
- 出口 `clear()` 清理

`AgenticRagService.chat` 走 `CompletableFuture.supplyAsync(executor)` —— ThreadLocal 不跨线程，故在 `supplyAsync` 内重新 `set(chatId)`。

### 5.3 失败隔离

`AgentTraceCollector.record()` 用 try/catch 吞掉所有异常：
- DB down / 数据格式错误 → log warn，主链路不挂
- tool 仍返回 `ToolResult`，agent loop 继续

### 5.4 degraded 语义

`agent_mode` 保留用户请求模式（"agentic"），`degraded=true` 配合表示"实际跑的是 linear"：
- 正常：`agentic, rounds=N, degraded=false`
- 降级：`agentic, rounds=0, degraded=true`（fallback 到 RagService，无 tool 调用）

---

## 六、测试

| 测试 | 用例数 | 结果 |
|---|---|---|
| `AgentTraceCollectorTest` | 6 | ✅ |
| `AgenticRagServiceTest` | 5（含 2 新断言 agentMode/rounds/degraded） | ✅ |
| `DirectAnswerToolTest` | 2 | ✅ |
| `WebSearchToolTest` | 9 | ✅ |
| `ChatServiceTest` | 2 | ✅ |
| `KnowledgeBaseSearchToolTest` | 6 | ✅（未改动） |
| 其他既有测试 | 75 | ✅（零回归） |

**汇总**：mvn test → `Tests run: 106, Failures: 0, Errors: 0, Skipped: 4`（4 个跳过是 PoC 测试 `@EnabledIfSystemProperty` 守护）

---

## 七、影响分析

| Change | Affected | Impact | Action |
|---|---|---|---|
| ChatResult 新字段 | RagService.chat / retrieveForStreaming 所有调用点 | **None**（保留 4 参委托构造） | 无需修改其它调用方 |
| Tool 构造器加 collector | 单元测试 4 个 | **Test-only** | mock / null 适配完成 |
| ChatService 构造器加 collector | 单元测试 1 个 | **Test-only** | mock 适配完成 |
| saveTurn 签名加 chatId | 当前仅 ChatService 内部调用 | **None** | 调用方 3 处已更新 |
| buildRagMetadataJson 签名加 3 字段 | 当前仅 ChatService 内部调用 | **None** | 调用方 3 处已更新 |

**linear RAG 路径行为零变化**：`rag.mode=linear`（默认）走 `RagService`，`agentMode=linear` / `agentRounds=0` / `degraded=false` 直接 hard-code 在 ChatResult 4-arg 委托构造里。

---

## 八、未覆盖（留待后续）

- **前端 SSE 订阅 agent_step**：F23（前端 UI）范围内
- **agent_trace 查询 API**：GET /api/chat-history/{chatId}/traces（F22 评估范围可选）
- **ST 测试用例**：docs/test-cases/feature-21.md（待 F22 一起出）

---

## 九、关联

- 上游 SRS：FR-014 Agent 可观测与 trace 落库（docs/plans/2026-03-15-rag-qa-srs.md §3.5）
- 设计：docs/plans/2026-03-15-rag-qa-design.md §11
- Feature 分解：feature-list.json F21（wave 1）
- Wave 1 总体 PR：docs/PR/PR-2026-07-04-agentic-rag-wave1.md
- F19 PR：commit `3d8ee12 feat(agent): F19 AgenticRagService + agent loop + 降级`
- F20 PR：commit `1d4b106 feat(agent): F20 rag.mode 路由 + per-conversation mode`
