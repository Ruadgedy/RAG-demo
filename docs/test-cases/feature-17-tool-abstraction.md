# 测试用例集: Tool 抽象 + KnowledgeBaseSearchTool

**Feature ID**: 17
**关联需求**: FR-013（工具抽象与多源检索）
**日期**: 2026-08-03
**测试标准**: ISO/IEC/IEEE 29119-3
**模板版本**: 1.0

## 摘要

| 类别 | 用例数 |
|------|--------|
| functional | 7 |
| boundary | 5 |
| **合计** | **12** |

> 自动化验收覆盖 9 个 Mock 测试，另有 2 个 trace 分支测试；3 个真实 LLM/DB/Chroma 场景保持 PENDING-MANUAL。

## 测试用例

### 用例编号

ST-FUNC-017-001

### 关联需求

FR-013（工具抽象与多源检索）

### 测试目标

验证知识库工具调用 RagService 检索并返回统一 ToolResult。

### 前置条件

- `KnowledgeBaseSearchToolTest` 可编译。
- 测试使用 Mockito 模拟 RagService 边界。

### 测试步骤

| Step | 操作 | 预期结果 |
|---|---|---|
| 1 | 设置 KnowledgeBaseContext 为测试 kb UUID | 上下文保存 kb UUID |
| 2 | 配置 RagService.retrieve 返回产品片段 | 模拟检索结果包含产品价格和文件名 |
| 3 | 调用 `searchKnowledgeBase("产品A")` | 返回 `ToolResult`，toolName 为 `kb_search` |
| 4 | 检查 content/source/durationMs | content 包含产品片段，source 包含文件名，durationMs 非负 |

### 验证点

- `RagService.retrieve("产品A", kbId)` 被调用。
- `ToolResult.toolName()` 等于 `kb_search`。
- 内容和来源字段保持检索结果信息。

### 后置检查

- 清理 KnowledgeBaseContext 和 TraceContext。

### 元数据

- **优先级**: High
- **类别**: functional
- **已自动化**: Yes
- **测试引用**: `KnowledgeBaseSearchToolTest::shouldCallRetrieveAndReturnToolResult`
- **Test Type**: Mock

---

### 用例编号

ST-FUNC-017-002

### 关联需求

FR-013（知识库上下文注入）

### 测试目标

验证 kbId 从 KnowledgeBaseContext 注入，而不是作为 LLM tool 参数传入。

### 前置条件

- KnowledgeBaseContext 设置测试 kb UUID。
- RagService.retrieve 已配置为空结果。

### 测试步骤

| Step | 操作 | 预期结果 |
|---|---|---|
| 1 | 设置上下文 kbId | kbId 可由工具内部读取 |
| 2 | 调用 `searchKnowledgeBase("test")` | 方法只接收 query 参数 |
| 3 | 验证 RagService 调用参数 | retrieve 使用上下文中的 kbId |
| 4 | 检查工具公开方法签名 | 不存在 LLM 可填写的 kbId 参数 |

### 验证点

- retrieve 的第二个参数等于 Context 中的 UUID。
- `searchKnowledgeBase` 的公开参数仅为 query。

### 后置检查

- 清理上下文。

### 元数据

- **优先级**: High
- **类别**: functional
- **已自动化**: Yes
- **测试引用**: `KnowledgeBaseSearchToolTest::shouldUseKnowledgeBaseContextKbId`
- **Test Type**: Mock

---

### 用例编号

ST-FUNC-017-003

### 关联需求

FR-013（工具注册）

### 测试目标

验证 KnowledgeBaseSearchTool 具备 Spring Component 和 Spring AI Tool 注册契约。

### 前置条件

- 后端编译依赖可用。

### 测试步骤

| Step | 操作 | 预期结果 |
|---|---|---|
| 1 | 读取 KnowledgeBaseSearchTool 类注解 | 存在 `@Component` |
| 2 | 读取 searchKnowledgeBase 方法注解 | 存在 `@Tool` |
| 3 | 检查 Tool description | 描述包含知识库检索语义 |

### 验证点

- Spring 可扫描该类。
- Spring AI 可发现 searchKnowledgeBase 工具。
- 描述明确工具适用于企业知识库。

### 后置检查

- 无。

### 元数据

- **优先级**: High
- **类别**: functional
- **已自动化**: Yes
- **测试引用**: `KnowledgeBaseSearchToolTest::shouldExposeSpringAiToolAnnotationWithKnowledgeBaseDescription`
- **Test Type**: Mock

---

### 用例编号

ST-FUNC-017-004

### 关联需求

FR-013（trace 兼容性）

### 测试目标

验证存在 chatId 时工具记录 start/done 两阶段 trace。

### 前置条件

- TraceContext 设置 chatId。
- AgentTraceCollector 为测试替身。

### 测试步骤

| Step | 操作 | 预期结果 |
|---|---|---|
| 1 | 设置 TraceContext chatId | 当前 tool 调用可关联会话 |
| 2 | 配置 retrieve 返回一条结果 | 工具可生成摘要 |
| 3 | 调用 searchKnowledgeBase | 返回 kb_search ToolResult |
| 4 | 验证 collector.record 调用 | 分别有 status=start 和 status=done 记录 |

### 验证点

- start 记录包含 query 参数。
- done 记录包含命中数和 duration。

### 后置检查

- 清理 TraceContext。

### 元数据

- **优先级**: Medium
- **类别**: functional
- **已自动化**: Yes
- **测试引用**: `KnowledgeBaseSearchToolTest::shouldRecordStartAndDoneTraceWhenChatIdSet`
- **Test Type**: Mock

---

### 用例编号

ST-FUNC-017-005

### 关联需求

FR-013（Agent 调用知识库工具）

### 测试目标

验证 Agentic RAG 在线运行时实际调用 kb_search 并落 trace。

### 前置条件

- 后端、数据库和 Chroma 已启动。
- 已配置可用 LLM API Key。
- 已登录并准备含 COMPLETED 文档的知识库。

### 测试步骤

| Step | 操作 | 预期结果 |
|---|---|---|
| 1 | 以 agentic 模式提问内部资料问题 | 请求成功返回回答 |
| 2 | 查询 agent_trace | 出现 `tool_name=kb_search` 的记录 |
| 3 | 检查 trace 参数 | tool_args 只包含 query，不含 kbId |

### 验证点

- Agent 实际完成工具调用。
- trace 记录 status=start/done。
- kbId 未暴露给 LLM。

### 后置检查

- 删除测试 trace/对话数据，停止本次启动的服务。

### 元数据

- **优先级**: High
- **类别**: functional
- **已自动化**: No
- **手动测试原因**: external-action: 需要真实 LLM、数据库、Chroma 和登录凭据
- **测试引用**: N/A
- **Test Type**: Real

---

### 用例编号

ST-FUNC-017-006

### 关联需求

FR-013（空知识库 Agent 行为）

### 测试目标

验证无文档时 Agent 不因 KB 空而短路，可继续使用其他工具或直答。

### 前置条件

- 后端和 LLM 服务可用。
- 准备无 COMPLETED 文档的知识库。

### 测试步骤

| Step | 操作 | 预期结果 |
|---|---|---|
| 1 | 以 agentic 模式提交常识问题 | 请求返回回答 |
| 2 | 检查响应 | 不直接返回“暂无文档”错误 |
| 3 | 查询 trace | 记录直答或 Web 工具调用 |

### 验证点

- Agent loop 未因空检索结果抛错。
- 回答可由直答/Web 工具完成。

### 后置检查

- 清理测试数据并停止服务。

### 元数据

- **优先级**: Medium
- **类别**: functional
- **已自动化**: No
- **手动测试原因**: external-action: 需要真实 LLM 和服务环境
- **测试引用**: N/A
- **Test Type**: Real

---

### 用例编号

ST-FUNC-017-007

### 关联需求

FR-013（真实 RagService 检索链路）

### 测试目标

验证工具在线上路径复用实际 RagService.retrieve 的召回结果。

### 前置条件

- 数据库、Chroma、后端均已启动。
- 知识库存在已处理文档。

### 测试步骤

| Step | 操作 | 预期结果 |
|---|---|---|
| 1 | 上传测试文档并等待 COMPLETED | 文档切片和向量可用 |
| 2 | 触发 Agentic 问答 | Agent 调用 kb_search |
| 3 | 对比回答与文档片段 | 回答基于实际检索内容 |

### 验证点

- 实际 Chroma 检索链路可用。
- rerank/fallback 行为未被工具绕过。

### 后置检查

- 删除测试文档和知识库，停止服务。

### 元数据

- **优先级**: Medium
- **类别**: functional
- **已自动化**: No
- **手动测试原因**: external-action: 需要真实数据库、Chroma 与上传数据
- **测试引用**: N/A
- **Test Type**: Real

---

### 用例编号

ST-BNDRY-017-001

### 关联需求

FR-013（空检索结果）

### 测试目标

验证无检索命中时返回空 content 和空 source，不抛异常。

### 前置条件

- RagService.retrieve 返回空列表。
- Context 设置有效 kbId。

### 测试步骤

| Step | 操作 | 预期结果 |
|---|---|---|
| 1 | 配置空检索结果 | 返回 `List.of()` |
| 2 | 调用工具 | 调用正常结束 |
| 3 | 检查 ToolResult | content/source 均为空字符串 |

### 验证点

- toolName 仍为 `kb_search`。
- 不发生 NullPointerException。

### 后置检查

- 清理上下文。

### 元数据

- **优先级**: High
- **类别**: boundary
- **已自动化**: Yes
- **测试引用**: `KnowledgeBaseSearchToolTest::shouldReturnEmptyWhenNoResults`
- **Test Type**: Mock

---

### 用例编号

ST-BNDRY-017-002

### 关联需求

FR-013（单条检索结果）

### 测试目标

验证单条来源格式无尾随逗号或多余空格。

### 前置条件

- retrieve 返回一个文件片段。

### 测试步骤

| Step | 操作 | 预期结果 |
|---|---|---|
| 1 | 配置单条结果 `only.pdf` | 工具收到单个来源 |
| 2 | 调用工具 | 返回成功 |
| 3 | 检查 source | 精确等于 `only.pdf` |

### 验证点

- source 不以逗号结尾。
- source 不含前后空格。

### 后置检查

- 清理上下文。

### 元数据

- **优先级**: Medium
- **类别**: boundary
- **已自动化**: Yes
- **测试引用**: `KnowledgeBaseSearchToolTest::singleResultShouldFormatSourceWithoutTrailingComma`
- **Test Type**: Mock

---

### 用例编号

ST-BNDRY-017-003

### 关联需求

FR-013（Context 未设置）

### 测试目标

验证未设置 kbId 时 null 被安全透传给 RagService，不凭空生成知识库 ID。

### 前置条件

- 不设置 KnowledgeBaseContext。
- RagService 支持测试中的 null 参数。

### 测试步骤

| Step | 操作 | 预期结果 |
|---|---|---|
| 1 | 清除 KnowledgeBaseContext | get() 返回 null |
| 2 | 调用工具 | 不抛出 NPE |
| 3 | 检查 retrieve 参数 | 第二参数为 null |
| 4 | 检查结果 | 返回空 ToolResult |

### 验证点

- `retrieve(anyString(), null)` 被调用。
- toolName 为 `kb_search`。

### 后置检查

- 清理 ThreadLocal。

### 元数据

- **优先级**: Medium
- **类别**: boundary
- **已自动化**: Yes
- **测试引用**: `KnowledgeBaseSearchToolTest::shouldPassNullKbIdToRetrieveWhenContextUnset`
- **Test Type**: Mock

---

### 用例编号

ST-BNDRY-017-004

### 关联需求

FR-013（Context 清理）

### 测试目标

验证 clear 后不会在线程复用时残留上一个 kbId。

### 前置条件

- KnowledgeBaseContext 可设置和清理 UUID。

### 测试步骤

| Step | 操作 | 预期结果 |
|---|---|---|
| 1 | set(kbId) | get() 返回 kbId |
| 2 | 调用 clear() | ThreadLocal 被 remove |
| 3 | 再次 get() | 返回 null |

### 验证点

- clear 后上下文确实为空。

### 后置检查

- 重复 clear，确保幂等。

### 元数据

- **优先级**: Medium
- **类别**: boundary
- **已自动化**: Yes
- **测试引用**: `KnowledgeBaseSearchToolTest::clearShouldRemoveKbId`
- **Test Type**: Mock

---

### 用例编号

ST-BNDRY-017-005

### 关联需求

FR-013（耗时边界）

### 测试目标

验证快速检索的 durationMs 非负且单位正确。

### 前置条件

- retrieve 立即返回一条结果。

### 测试步骤

| Step | 操作 | 预期结果 |
|---|---|---|
| 1 | 调用工具 | 调用成功 |
| 2 | 读取 durationMs | 大于等于 0 |
| 3 | 检查上界 | 小于 10000ms |

### 验证点

- duration 使用毫秒。
- 不出现负数或异常大数。

### 后置检查

- 清理上下文。

### 元数据

- **优先级**: Low
- **类别**: boundary
- **已自动化**: Yes
- **测试引用**: `KnowledgeBaseSearchToolTest::durationShouldBeReasonable`
- **Test Type**: Mock

---

## 可追溯矩阵

| 用例 ID | 关联需求 | verification_step | 自动化测试 | Test Type | 结果 |
|---------|----------|-------------------|-----------|----------|------|
| ST-FUNC-017-001 | FR-013 | verification_step[0] | KnowledgeBaseSearchToolTest::shouldCallRetrieveAndReturnToolResult | Mock | PASS |
| ST-FUNC-017-002 | FR-013 | verification_step[1] | KnowledgeBaseSearchToolTest::shouldUseKnowledgeBaseContextKbId | Mock | PASS |
| ST-FUNC-017-003 | FR-013 | tool 注册 | KnowledgeBaseSearchToolTest::shouldExposeSpringAiToolAnnotationWithKnowledgeBaseDescription | Mock | PASS |
| ST-FUNC-017-004 | FR-013 | trace 兼容 | KnowledgeBaseSearchToolTest::shouldRecordStartAndDoneTraceWhenChatIdSet | Mock | PASS |
| ST-FUNC-017-005 | FR-013 | agent kb_search | N/A | Real | PENDING-MANUAL |
| ST-FUNC-017-006 | FR-013 | empty KB agent | N/A | Real | PENDING-MANUAL |
| ST-FUNC-017-007 | FR-013 | actual RagService/Chroma | N/A | Real | PENDING-MANUAL |
| ST-BNDRY-017-001 | FR-013 | 空结果 | KnowledgeBaseSearchToolTest::shouldReturnEmptyWhenNoResults | Mock | PASS |
| ST-BNDRY-017-002 | FR-013 | 单条来源 | KnowledgeBaseSearchToolTest::singleResultShouldFormatSourceWithoutTrailingComma | Mock | PASS |
| ST-BNDRY-017-003 | FR-013 | null kbId | KnowledgeBaseSearchToolTest::shouldPassNullKbIdToRetrieveWhenContextUnset | Mock | PASS |
| ST-BNDRY-017-004 | FR-013 | clear | KnowledgeBaseSearchToolTest::clearShouldRemoveKbId | Mock | PASS |
| ST-BNDRY-017-005 | FR-013 | duration | KnowledgeBaseSearchToolTest::durationShouldBeReasonable | Mock | PASS |

## Real Test Case Execution Summary

| Metric | Count |
|--------|-------|
| Total Real Test Cases | 3 |
| Passed | 0 |
| Failed | 0 |
| Pending | 3 |

## Manual Test Case Summary

| Metric | Count |
|--------|-------|
| Total Manual Test Cases | 3 |
| Manual Passed (MANUAL-PASS) | 0 |
| Manual Failed (MANUAL-FAIL) | 0 |
| Blocked | 0 |
| Pending (PENDING-MANUAL) | 3 |

> 手工场景需要真实 LLM、数据库、Chroma 和登录凭据，本次未执行，需上线前补做。

## 自动化验收执行证据

- `mvn test -Dtest=KnowledgeBaseSearchToolTest -o`：11/11 通过。
- Quality Gates：行覆盖 100%，分支覆盖 92.3%，变异分数 90%。
- 当前自动化验收 PASS；3 个真实外部依赖场景保持 PENDING-MANUAL。
