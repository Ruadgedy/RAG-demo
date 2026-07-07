# Feature #17 — Tool 抽象 + KnowledgeBaseSearchTool

| 项目 | 内容 |
|------|------|
| **Feature ID** | #17 |
| **关联类** | `agent/tool/ToolResult`、`agent/tool/KnowledgeBaseContext`、`agent/tool/KnowledgeBaseSearchTool` |
| **关联需求** | FR-013（工具抽象与多源检索） |
| **前置依赖** | F4（RAG 检索引擎） |
| **优先级** | P0（agentic 地基） |
| **编写日期** | 2026-07-07 |

---

## 1. 功能概述

### 1.1 背景

Agentic RAG 需要 LLM 自主调用检索工具，但不能让 LLM 看到 `kbId`（会编造），也不能破坏现有 RagService 召回+rerank+fallback 链路。

### 1.2 新增

- `ToolResult(String toolName, String content, String source, long durationMs)` —— 所有 @Tool 方法的统一返回 record
- `KnowledgeBaseContext` —— ThreadLocal 持有 `kbId`（参考 KBContext 模式），避免暴露给 LLM
- `KnowledgeBaseSearchTool.searchKnowledgeBase(query)` —— @Tool 包装 `RagService.retrieve(query, kbId)`，复用现有召回链路

---

## 2. 验收用例

### 2.1 ST-17-1 单测覆盖（已自动化）

| 测试 | 文件 |
|---|---|
| 工具调用返回 ToolResult + 正确字段 | `KnowledgeBaseSearchToolTest` |
| ThreadLocal 注入 kbId | `KnowledgeBaseSearchToolTest.searchShouldInjectKbIdFromContext` |
| kbId 缺失（线程上下文未设）NPE 兜底 | `KnowledgeBaseSearchToolTest` |

通过条件：`mvn test -Dtest='KnowledgeBaseSearchToolTest'` 全过

### 2.2 ST-17-2 Agent 调用 kb_search 实际生效

**前置**：本机启动后端、登录、选 KB 且 KB 有文档

| Step | 操作 | 期望 |
|---|---|---|
| 1 | 后端加启动参数 `-Drag.mode=agentic` + env 加 `TAVILY_API_KEY`（可空） | 应用启动，agent 路径生效 |
| 2 | DB 配一个对话组：`INSERT INTO conversation (rag_mode) VALUES ('agentic')` | — |
| 3 | `curl -X POST http://localhost:8080/api/admin/eval/ab -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"question":"产品A价格","kbId":"<your-kb-id>"}'` | 200，body 含 `agentic.answer` 字段（可空） |
| 4 | 同时查 `SELECT * FROM agent_trace WHERE chat_id LIKE 'ab-%' ORDER BY id DESC LIMIT 5` | 至少有 1 行 `tool_name='kb_search'` 的记录（含 status=done + durationMs） |

### 2.3 ST-17-3 kbId 不暴露给 LLM

| Step | 操作 | 期望 |
|---|---|---|
| 1 | 触发 agent 跑（任一 kb_search 工具调用） | — |
| 2 | DB：`SELECT tool_args FROM agent_trace WHERE tool_name='kb_search' ORDER BY id DESC LIMIT 1` | tool_args JSON 里只有 `{"query":"..."}`，**不包含 kbId 字段** |
| 3 | 在 agent_memory / 重新问同样问题 | LLM 没把 kbId 写入回答或 new query（无幻觉） |

### 2.4 ST-17-4 检索为空时返回空 + 引导

| Step | 操作 | 期望 |
|---|---|---|
| 1 | 选一个无文档的 KB | — |
| 2 | 触发 agentic 问答 | ToolResult.content 为空字符串，source 为空 |
| 3 | LLM 基于 Agent loop 累积 context 返回"该 KB 暂无文档，请上传" | 合理兜底，不抛 NPE |

---

## 3. 自动化测试覆盖

| 层 | 通过条件 |
|---|---|
| 单测 | `mvn test -Dtest='KnowledgeBaseSearchToolTest'` 6 例全过 |

---

## 4. 不在范围内

- 多 KB 并行检索：当前 KBContext 一对一
- kb_search 结果缓存：F4 层面 RagService 已做 rerank + fallback，本 feature 不再优化

---

## 5. 关联

- 设计：Design §11.5（数据模型 + 配置）
- PoC：`MiniMaxToolCallingPoCTest` 4 用例全过
- Wave 1 总 PR：`PR-2026-07-04-agentic-rag-wave1.md`
