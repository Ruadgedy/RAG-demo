# 测试用例集: AgenticRagService + agent loop + 降级

**Feature ID**: 19
**关联需求**: FR-012（Agentic 问答模式）
**日期**: 2026-08-03
**测试标准**: ISO/IEC/IEEE 29119-3
**模板版本**: 1.0

## 摘要

| 类别 | 用例数 |
|------|--------|
| functional | 5 |
| integration | 1 |
| **合计** | **6** |

> validate_st_cases.py 仅接受 FUNC/BNDRY/UI/SEC/PERF 类别；真实 LLM Agent 场景归入 FUNC-006 并标记 PENDING-MANUAL。

## 测试用例

### 用例编号

ST-FUNC-019-001

### 关联需求

FR-012 工具编排与多轮调用

### 测试目标

验证 AgenticRagService.chat 在 agent loop 正常返回时给出 agentic 模式且无降级。

### 前置条件

- KnowledgeBaseSearchTool / DirectAnswerTool 已注入
- WebSearchTool 可空（isAvailable()==false 时不参与）
- 模拟 ChatClient 返回非空 answer

### 测试步骤

| Step | 操作 | 预期结果 |
|---|---|---|
| 1 | 调用 chat(chatId, message, kbId, history, 5) | 不抛异常 |
| 2 | 读取 ChatResult.answer | 非空 |
| 3 | 读取 ChatResult.agentMode | 等于 "agentic" |
| 4 | 读取 ChatResult.degraded | false |
| 5 | 读取 ChatResult.agentRounds | ≥ 1 |

### 验证点

- agent loop 成功路径标记 agentMode=agentic。
- KnowledgeBaseContext 与 TraceContext 已被清理。

### 后置检查

- 清理 ThreadLocal。

### 元数据

- **优先级**: High
- **类别**: functional
- **已自动化**: Yes
- **测试引用**: `AgenticRagServiceTest::chatShouldReturnAgenticAnswerOnSuccess`
- **Test Type**: Mock

---

### 用例编号

ST-FUNC-019-002

### 关联需求

FR-012 超时降级

### 测试目标

验证 agent loop 超时（默认 30s）触发降级到线性 RAG。

### 前置条件

- 模拟 ChatClient 让 future.get(timeout) 抛 TimeoutException

### 测试步骤

| Step | 操作 | 预期结果 |
|---|---|---|
| 1 | 调用 chat | 不抛异常 |
| 2 | 读取 ChatResult.agentMode | "linear" |
| 3 | 读取 ChatResult.degraded | true |
| 4 | 读取 ChatResult.agentRounds | 0 |
| 5 | 验证 RagService.chat 被调用 | 降级路径触发 |

### 验证点

- 超时不向上抛，被 future.cancel 兜底。
- degraded=true 表明 agentic 触发但实际走线性。

### 后置检查

- 关闭线程池。

### 元数据

- **优先级**: High
- **类别**: functional
- **已自动化**: Yes
- **测试引用**: `AgenticRagServiceTest::chatShouldDegradeOnTimeout`
- **Test Type**: Mock

---

### 用例编号

ST-FUNC-019-003

### 关联需求

FR-012 异常降级

### 测试目标

验证 agent loop 抛异常时降级线性 RAG。

### 前置条件

- 模拟 ChatClient 抛 RuntimeException

### 测试步骤

| Step | 操作 | 预期结果 |
|---|---|---|
| 1 | 调用 chat | 不抛异常 |
| 2 | 读取 ChatResult.agentMode | "linear" |
| 3 | 读取 ChatResult.degraded | true |
| 4 | 验证 RagService.chat 被调用 | 降级路径触发 |

### 验证点

- 异常被捕获并降级，不影响主链路。

### 后置检查

- 关闭线程池。

### 元数据

- **优先级**: High
- **类别**: functional
- **已自动化**: Yes
- **测试引用**: `AgenticRagServiceTest::chatShouldDegradeOnException`
- **Test Type**: Mock

---

### 用例编号

ST-FUNC-019-004

### 关联需求

FR-012 流式降级

### 测试目标

验证 retrieveForStreaming 在异常时返回 ChatResult(null, list, null) 而不抛出。

### 前置条件

- 模拟 ChatClient 抛异常

### 测试步骤

| Step | 操作 | 预期结果 |
|---|---|---|
| 1 | 调用 retrieveForStreaming | 不抛异常 |
| 2 | 读取 ChatResult.answer | null（让 ChatService 决定降级） |
| 3 | 验证 RagService.chat 被调用 | 降级路径触发 |

### 验证点

- 流式路径异常隔离。

### 后置检查

- 关闭线程池。

### 元数据

- **优先级**: High
- **类别**: functional
- **已自动化**: Yes
- **测试引用**: `AgenticRagServiceTest::retrieveForStreamingShouldDegradeOnException`
- **Test Type**: Mock

---

### 用例编号

ST-FUNC-019-005

### 关联需求

FR-012 流式成功

### 测试目标

验证 retrieveForStreaming 成功时返回 ChatResult 含 answer。

### 前置条件

- 模拟 ChatClient 正常返回

### 测试步骤

| Step | 操作 | 预期结果 |
|---|---|---|
| 1 | 调用 retrieveForStreaming | 不抛异常 |
| 2 | 读取 ChatResult.answer | 非空 |
| 3 | 读取 ChatResult.agentMode | "agentic" |
| 4 | 读取 ChatResult.degraded | false |

### 验证点

- 流式路径与 chat 行为一致。

### 后置检查

- 关闭线程池。

### 元数据

- **优先级**: High
- **类别**: functional
- **已自动化**: Yes
- **测试引用**: `AgenticRagServiceTest::retrieveForStreamingShouldReturnToolContextOnSuccess`
- **Test Type**: Mock

---

### 用例编号

ST-FUNC-019-006

### 关联需求

FR-012 真实 LLM Agent 端到端

### 测试目标

验证在真实 LLM + 后端 + DB + Chroma 环境下，AgenticRagService 通过 tool-calling 完成多源问答。

### 前置条件

- 后端、数据库、Chroma 已启动
- 有效 OPENAI_API_KEY + 可用 LLM 支持 tool-calling
- 知识库存在 COMPLETED 文档

### 测试步骤

| Step | 操作 | 预期结果 |
|---|---|---|
| 1 | 调 `/api/chat` 走 agentic 模式 | 返回 agentic 回答 |
| 2 | 查询 agent_trace 表 | 出现 tool_name=kb_search 行 |
| 3 | 检查 ChatResult | agentMode=agentic, degraded=false, rounds≥1 |

### 验证点

- 真实 LLM tool-calling 工作。
- 端到端 trace 落库。

### 后置检查

- 删除 trace/对话数据，停止服务。

### 元数据

- **优先级**: High
- **类别**: functional
- **已自动化**: No
- **手动测试原因**: external-action: 需真实 LLM、数据库、Chroma 与登录凭据。
- **测试引用**: N/A
- **Test Type**: Real

## 可追溯矩阵

| 用例 ID | 关联需求 | verification_step | 自动化测试 | Test Type | 结果 |
|---------|----------|-------------------|-----------|----------|------|
| ST-FUNC-019-001 | FR-012 | verification_step[0] | AgenticRagServiceTest::chatShouldReturnAgenticAnswerOnSuccess | Mock | PASS |
| ST-FUNC-019-002 | FR-012 | verification_step[1] | AgenticRagServiceTest::chatShouldDegradeOnTimeout | Mock | PASS |
| ST-FUNC-019-003 | FR-012 | verification_step[2] | AgenticRagServiceTest::chatShouldDegradeOnException | Mock | PASS |
| ST-FUNC-019-004 | FR-012 | verification_step[1] | AgenticRagServiceTest::retrieveForStreamingShouldDegradeOnException | Mock | PASS |
| ST-FUNC-019-005 | FR-012 | verification_step[0] | AgenticRagServiceTest::retrieveForStreamingShouldReturnToolContextOnSuccess | Mock | PASS |
| ST-FUNC-019-006 | FR-012 | verification_step[0] | N/A | Real | PENDING-MANUAL |

## Real Test Case Execution Summary

| Metric | Count |
|--------|-------|
| Total Real Test Cases | 1 |
| Passed | 0 |
| Failed | 0 |
| Pending | 1 |

## Manual Test Case Summary

| Metric | Count |
|--------|-------|
| Total Manual Test Cases | 1 |
| Manual Passed (MANUAL-PASS) | 0 |
| Manual Failed (MANUAL-FAIL) | 0 |
| Blocked | 0 |
| Pending (PENDING-MANUAL) | 1 |

> 真实 LLM/DB/Chroma 场景需完整服务环境，本次未执行。

## 自动化验收执行证据

- `mvn test -Dtest=AgenticRagServiceTest -o`：5/5 通过。
- 自动化验收 PASS；1 个真实外部依赖场景 PENDING-MANUAL。
