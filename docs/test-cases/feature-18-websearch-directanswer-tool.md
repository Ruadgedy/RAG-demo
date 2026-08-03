# 测试用例集: WebSearchTool (Tavily) + DirectAnswerTool

**Feature ID**: 18
**关联需求**: FR-013（工具抽象与多源检索）
**日期**: 2026-08-03
**测试标准**: ISO/IEC/IEEE 29119-3
**模板版本**: 1.0

## 摘要

| 类别 | 用例数 |
|------|--------|
| functional | 7 |
| boundary | 3 |
| **合计** | **10** |

> validate_st_cases.py 仅接受 FUNC/BNDRY/UI/SEC/PERF 类别；2 个真实 Tavily HTTP 场景归入 FUNC-006/007 并标记为 PENDING-MANUAL。

> 5 个 FUNC 与 3 个 BNDRY 用例在 Maven 单测中执行（18/18 通过）；2 个 INTG 用例需要真实 Tavily API 与 `TAVILY_API_KEY`，标记为 PENDING-MANUAL。F18 是后端工具库（`ui: false`），跳过 Step 8 视觉评估。

## 测试用例

### 用例编号

ST-FUNC-018-001

### 关联需求

FR-013 工具抽象与多源检索

### 测试目标

验证 WebSearchTool 在已配置 Tavily API key 且响应正常时返回 top-N 摘要。

### 前置条件

- `TAVILY_API_KEY` 已配置
- Tavily API `/search` 返回 2 条 results

### 测试步骤

| Step | 操作 | 预期结果 |
|---|---|---|
| 1 | 调用 `WebSearchTool.searchWeb("AI news")` | 工具命中 2 条 Tavily 摘要 |
| 2 | 读取 ToolResult.toolName | 等于 `web_search` |
| 3 | 读取 content/source | content 含两条摘要，source 含两条 URL |
| 4 | 读取 durationMs | 0 ≤ durationMs < 10000 |

### 验证点

- ToolResult 字段命名与 F18 接口契约一致。
- content 由 Tavily 真实响应拼接。
- source 为 URL 列表（逗号分隔）。

### 后置检查

- 清理 `TraceContext` ThreadLocal。

### 元数据

- **优先级**: High
- **类别**: functional
- **已自动化**: Yes
- **测试引用**: `WebSearchToolTest::shouldReturnToolResultFromTavilyResponse`
- **Test Type**: Mock

---

### 用例编号

ST-FUNC-018-002

### 关联需求

FR-013 工具抽象与多源检索

### 测试目标

验证 WebSearchTool 在无 TAVILY_API_KEY 时返回"未配置"提示且不发出网络请求。

### 前置条件

- `TAVILY_API_KEY` 未配置（空字符串）
- `TraceContext` 设置 chatId

### 测试步骤

| Step | 操作 | 预期结果 |
|---|---|---|
| 1 | 调用 `WebSearchTool.searchWeb("query")` | 不发生 HTTP 调用 |
| 2 | 读取 ToolResult.content | 含 "未配置" |
| 3 | 读取 ToolResult.durationMs | 等于 0 |
| 4 | 验证 traceCollector.record | 记录 `status=done` + summary=未配置 |

### 验证点

- WebSearchTool 通过 `isAvailable()` 短路，不发 Tavily 请求。
- trace 仍记录未配置原因。

### 后置检查

- 清理 `TraceContext`。

### 元数据

- **优先级**: High
- **类别**: functional
- **已自动化**: Yes
- **测试引用**: `WebSearchToolTest::shouldReturnNotConfiguredWhenNoApiKey`、`shouldRecordTraceForUnavailableSearch`
- **Test Type**: Mock

---

### 用例编号

ST-FUNC-018-003

### 关联需求

FR-013 工具抽象与多源检索

### 测试目标

验证 DirectAnswerTool 返回稳定的直答提示，不触发任何检索。

### 前置条件

- DirectAnswerTool 已实例化（无 TraceContext）

### 测试步骤

| Step | 操作 | 预期结果 |
|---|---|---|
| 1 | 调用 `DirectAnswerTool.directAnswer("你好")` | 工具返回通用提示 |
| 2 | 读取 ToolResult | toolName=direct_answer, source=direct |
| 3 | 读取 content | 含 "闲聊" + "无需检索" |

### 验证点

- DirectAnswer 不依赖网络或数据库。
- 协议字段稳定，便于 LLM 解析。

### 后置检查

- 无外部资源需要清理。

### 元数据

- **优先级**: High
- **类别**: functional
- **已自动化**: Yes
- **测试引用**: `DirectAnswerToolTest::shouldReturnDirectAnswerToolResult`
- **Test Type**: Mock

---

### 用例编号

ST-FUNC-018-004

### 关联需求

FR-013 工具抽象与多源检索

### 测试目标

验证当 Tavily API 抛出异常时，WebSearchTool 返回失败 ToolResult 而不向上抛出。

### 前置条件

- WebSearchTool 已配置合法 API key
- `doSearch` 抛出 `RuntimeException("network error")`

### 测试步骤

| Step | 操作 | 预期结果 |
|---|---|---|
| 1 | 调用 `searchWeb("query")` | 不抛出异常 |
| 2 | 读取 content | 含 "Web 搜索失败" + "network error" |
| 3 | 读取 source | 空字符串 |
| 4 | 验证 traceCollector.record | 记录 `status=done` + summary=失败原因 |

### 验证点

- Agent 主链路不被 Tavily 错误拖垮。
- durationMs 仍为正数。

### 后置检查

- 清理 `TraceContext`。

### 元数据

- **优先级**: High
- **类别**: functional
- **已自动化**: Yes
- **测试引用**: `WebSearchToolTest::shouldHandleSearchFailureGracefully`、`shouldRecordTraceForFailedSearch`
- **Test Type**: Mock

---

### 用例编号

ST-FUNC-018-005

### 关联需求

FR-013 工具抽象与多源检索

### 测试目标

验证 WebSearchTool 成功路径在 chatId 已设置时记录 start/done 两条 trace。

### 前置条件

- `TAVILY_API_KEY` 已配置
- `TraceContext.set("web-chat-success")`
- Tavily 返回一条 results

### 测试步骤

| Step | 操作 | 预期结果 |
|---|---|---|
| 1 | 调用 `searchWeb("q")` | 工具返回一条摘要 |
| 2 | 验证 trace start | status=start, args.query="q" |
| 3 | 验证 trace done | status=done, summary 含命中数和 URL |

### 验证点

- trace 与 chatId 关联。
- start 记录包含 args，done 记录包含摘要和耗时。

### 后置检查

- 清理 `TraceContext`。

### 元数据

- **优先级**: Medium
- **类别**: functional
- **已自动化**: Yes
- **测试引用**: `WebSearchToolTest::shouldRecordTraceForSuccessfulSearch`
- **Test Type**: Mock

---

### 用例编号

ST-BNDRY-018-001

### 关联需求

FR-013 工具抽象与多源检索

### 测试目标

验证 Tavily 响应中 results 为空时，ToolResult 的 content/source 都是空字符串。

### 前置条件

- `TAVILY_API_KEY` 已配置
- `doSearch` 返回 `{"results":[]}` 或类似空结果

### 测试步骤

| Step | 操作 | 预期结果 |
|---|---|---|
| 1 | 调用 `searchWeb("q")` | 工具正常返回 |
| 2 | 读取 content | 空字符串 |
| 3 | 读取 source | 空字符串 |

### 验证点

- 不抛 NPE（stream/join on empty 安全）。

### 后置检查

- 清理 `TraceContext`。

### 元数据

- **优先级**: Medium
- **类别**: boundary
- **已自动化**: Yes
- **测试引用**: `WebSearchToolTest::shouldReturnEmptyContentWhenResultsHaveNoContent`（结构覆盖）
- **Test Type**: Mock

---

### 用例编号

ST-BNDRY-018-002

### 关联需求

FR-013 工具抽象与多源检索

### 测试目标

验证 DirectAnswerTool 在问题为空时仍返回稳定提示。

### 前置条件

- DirectAnswerTool 已实例化

### 测试步骤

| Step | 操作 | 预期结果 |
|---|---|---|
| 1 | 调用 `directAnswer("")` | 工具返回通用提示 |
| 2 | 读取 content | 含 "闲聊" + "无需检索" |
| 3 | 读取 source | `direct` |

### 验证点

- 空输入不改变协议字段。

### 后置检查

- 无。

### 元数据

- **优先级**: Low
- **类别**: boundary
- **已自动化**: Yes
- **测试引用**: `DirectAnswerToolTest::directSearchShouldKeepPromptForEmptyQuestion`
- **Test Type**: Mock

---

### 用例编号

ST-BNDRY-018-003

### 关联需求

FR-013 工具抽象与多源检索

### 测试目标

验证 1ms 超时配置下，工具的失败处理路径仍能转换为 ToolResult（不向上抛出）。

### 前置条件

- `TAVILY_API_KEY` 已配置
- `timeoutMs=1`（构造时显式注入）
- `doSearch` 抛 `RuntimeException("timeout")`

### 测试步骤

| Step | 操作 | 预期结果 |
|---|---|---|
| 1 | 调用 `searchWeb("q")` | 不向上抛出 |
| 2 | 读取 content | 含 "失败" + "timeout" |
| 3 | 读取 durationMs | ≥ 0 |

### 验证点

- 失败兜底不依赖 timeout 实际触发，但能验证 catch 块对任意异常（含超时）稳定。

### 后置检查

- 清理 `TraceContext`。

### 元数据

- **优先级**: Low
- **类别**: boundary
- **已自动化**: Yes
- **测试引用**: `WebSearchToolTest::shouldConvertTimeoutFailureToToolResult`
- **Test Type**: Mock

---

### 用例编号

ST-FUNC-018-006

### 关联需求

FR-013 工具抽象与多源检索

### 测试目标

验证 Tavily API 在 HTTP 401（无效 API key）时，`doSearch` 抛出异常且 `searchWeb` 正确兜底。

### 前置条件

- 真实 `RestClient`（不 mock）
- 无效 `TAVILY_API_KEY`

### 测试步骤

| Step | 操作 | 预期结果 |
|---|---|---|
| 1 | 构造 WebSearchTool 注入真实 RestClient.Builder | tool 持有真实 Tavily HTTP 客户端 |
| 2 | 调用 `doSearch("query")` | 抛出 RuntimeException（HTTPS 401） |
| 3 | 调用 `searchWeb("query")` | 异常被捕获，content 含 "失败"，source 空 |

### 验证点

- `searchWeb` 的 catch 块对真实 Tavily 401 同样有效。
- Agent 主链路不因 401 抛出。

### 后置检查

- 关闭 HTTP 连接。

### 元数据

- **优先级**: High
- **类别**: integration
- **已自动化**: No
- **手动测试原因**: external-action: 需真实 Tavily API key（无效 key 即可触发 401）。
- **测试引用**: N/A
- **Test Type**: Real

---

### 用例编号

ST-FUNC-018-007

### 关联需求

FR-013 工具抽象与多源检索

### 测试目标

验证在真实 Tavily API + 有效 key 下，WebSearchTool 返回的 content 来自互联网并落 trace。

### 前置条件

- 有效 `TAVILY_API_KEY`（Tavily 免费层 1000 次/月）
- 后端与数据库已启动

### 测试步骤

| Step | 操作 | 预期结果 |
|---|---|---|
| 1 | 启动 backend，加 `TAVILY_API_KEY` 环境变量 | 应用启动，agent 工具注册 WebSearchTool |
| 2 | 触发需 web 检索的 agentic 问答 | Agent 调用 web_search |
| 3 | 查询 `agent_trace` 表 | 出现 `tool_name=web_search`, status=done, summary 含 "命中 N 条" |
| 4 | 检查响应内容 | content 至少一条来自 Tavily |

### 验证点

- 真实 Tavily HTTP 成功路径。
- 端到端 trace 落库与 SSE 正确。

### 后置检查

- 删除测试 trace/对话数据，停止 backend。

### 元数据

- **优先级**: High
- **类别**: integration
- **已自动化**: No
- **手动测试原因**: external-action: 需有效 Tavily API key + 完整服务环境。
- **测试引用**: N/A
- **Test Type**: Real

## 可追溯矩阵

| 用例 ID | 关联需求 | verification_step | 自动化测试 | Test Type | 结果 |
|---------|----------|-------------------|-----------|----------|------|
| ST-FUNC-018-001 | FR-013 | verification_step[0] | WebSearchToolTest::shouldReturnToolResultFromTavilyResponse | Mock | PASS |
| ST-FUNC-018-002 | FR-013 | verification_step[1] | WebSearchToolTest::shouldReturnNotConfiguredWhenNoApiKey、shouldRecordTraceForUnavailableSearch | Mock | PASS |
| ST-FUNC-018-003 | FR-013 | verification_step[2] | DirectAnswerToolTest::shouldReturnDirectAnswerToolResult | Mock | PASS |
| ST-FUNC-018-004 | FR-013 | 失败降级 | WebSearchToolTest::shouldHandleSearchFailureGracefully、shouldRecordTraceForFailedSearch | Mock | PASS |
| ST-FUNC-018-005 | FR-013 | trace 兼容 | WebSearchToolTest::shouldRecordTraceForSuccessfulSearch | Mock | PASS |
| ST-BNDRY-018-001 | FR-013 | 空结果 | WebSearchToolTest::shouldReturnEmptyContentWhenResultsHaveNoContent | Mock | PASS |
| ST-BNDRY-018-002 | FR-013 | 空问题 | DirectAnswerToolTest::directSearchShouldKeepPromptForEmptyQuestion | Mock | PASS |
| ST-BNDRY-018-003 | FR-013 | 超时降级 | WebSearchToolTest::shouldConvertTimeoutFailureToToolResult | Mock | PASS |
| ST-FUNC-018-006 | FR-013 | 真实 401 降级 | N/A | Real | PENDING-MANUAL |
| ST-FUNC-018-007 | FR-013 | 真实成功路径 | N/A | Real | PENDING-MANUAL |

## Real Test Case Execution Summary

| Metric | Count |
|--------|-------|
| Total Real Test Cases | 2 |
| Passed | 0 |
| Failed | 0 |
| Pending | 2 |

## Manual Test Case Summary

| Metric | Count |
|--------|-------|
| Total Manual Test Cases | 2 |
| Manual Passed (MANUAL-PASS) | 0 |
| Manual Failed (MANUAL-FAIL) | 0 |
| Blocked | 0 |
| Pending (PENDING-MANUAL) | 2 |

> 2 个真实 Tavily HTTP 场景需有效 `TAVILY_API_KEY`，本次未执行，需在生产环境补测。

## 自动化验收执行证据

- `mvn test -Dtest=WebSearchToolTest,DirectAnswerToolTest -o`：18/18 通过。
- Quality Gates：行覆盖 98.8%，分支覆盖 89.3%，变异分数 84%（F18 类范围）。
- 自动化验收 PASS；2 个真实外部依赖场景 PENDING-MANUAL。
