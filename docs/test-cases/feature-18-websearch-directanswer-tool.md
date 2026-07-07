# Feature #18 — WebSearchTool（Tavily）+ DirectAnswerTool

| 项目 | 内容 |
|------|------|
| **Feature ID** | #18 |
| **关联类** | `agent/tool/WebSearchTool`、`agent/tool/DirectAnswerTool` |
| **关联需求** | FR-013（工具抽象与多源检索） |
| **前置依赖** | F17（Tool 抽象） |
| **优先级** | P0（agentic 地基） |
| **编写日期** | 2026-07-07 |

---

## 1. 功能概述

### 1.1 背景

单源检索（仅 KB）不够。WebSearchTool 让 agent 调 Tavily 补足长尾/时效性；DirectAnswerTool 让闲聊/常识题直接答，省检索开销。

### 1.2 新增

- **WebSearchTool**：
  - 调 `POST https://api.tavily.com/search`（top-K=5 默认，可配）
  - 超时（默认 8s）、失败 → ToolResult.content="Web 搜索失败: ..."
  - 无 `TAVILY_API_KEY` → `isAvailable()=false`，AgenticRagService 启动时**不注册**此 tool
- **DirectAnswerTool**：
  - 返回引导文本"无需检索，直接回答"
  - 闲聊场景让 LLM 用通用知识回答，省 token

---

## 2. 验收用例

### 2.1 ST-18-1 web_search 成功路径

**前置**：`TAVILY_API_KEY` 已配

| Step | 操作 | 期望 |
|---|---|---|
| 1 | 触发 agentic 问答 "今天的 AI 新闻" | 至少调 1 次 `web_search` tool |
| 2 | DB：`SELECT round, tool_name, duration_ms FROM agent_trace WHERE tool_name='web_search' ORDER BY id DESC LIMIT 1` | 1 行，duration_ms > 0，status=done |
| 3 | `result_summary` 含"命中 N 条；URL=..." | N ≥ 1 |

### 2.2 ST-18-2 无 TAVILY_API_KEY（tool 不注册）

**前置**：env 不含 `TAVILY_API_KEY`

| Step | 操作 | 期望 |
|---|---|---|
| 1 | 启动 backend，日志搜 `"WebSearchTool"` | 应见到 `isAvailable()=false` 之类 |
| 2 | 触发 agentic 问答 | LLM 只能看到 kb_search 和 direct_answer 两个 tool，没有 web_search |
| 3 | DB：`SELECT COUNT(*) FROM agent_trace WHERE tool_name='web_search'` | 0 |

### 2.3 ST-18-3 web_search 失败兜底

**前置**：env 有 key，但 Tavily 502 / 超时

| Step | 操作 | 期望 |
|---|---|---|
| 1 | 触发 agentic 问答 | web_search tool 调一次 |
| 2 | `SELECT result_summary FROM agent_trace WHERE tool_name='web_search' AND chat_id=?` | summary 含"失败: ..." |
| 3 | Agent 不抛异常，继续生成回答 | 整体对话不挂，agent_rounds 包括这次失败调用 |

### 2.4 ST-18-4 direct_answer 闲聊场景

| Step | 操作 | 期望 |
|---|---|---|
| 1 | 触发 agentic 问答 "你好" | agent 调用 direct_answer tool |
| 2 | `SELECT * FROM agent_trace WHERE tool_name='direct_answer'` | 至少 1 行 |
| 3 | `result_summary` = "闲聊/常识，无需检索" | — |

---

## 3. 自动化测试覆盖

| 层 | 通过条件 |
|---|---|
| 单测 | `WebSearchToolTest` 9 例（含 spy mock doSearch + 未配置 + 失败兜底 + parseResults）；`DirectAnswerToolTest` 2 例 |

---

## 4. 不在范围内

- 多种 Web 搜索引擎（Google CSE / Bing / DuckDuckGo）：当前只接 Tavily
- 搜索结果缓存：F22 Eval A/B 阶段补
- 搜索 cost 监控：Tavily 免费层够用，未引入

---

## 5. 关联

- 配置：`rag.web.search.api-key=${TAVILY_API_KEY:}`（.env.example）
- 设计：Design §11.5
- Wave 1 总 PR：`PR-2026-07-04-agentic-rag-wave1.md`
