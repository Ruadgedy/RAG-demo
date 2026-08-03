# 测试用例集: agent_trace + SSE agent_step

**Feature ID**: 21
**关联需求**: FR-014（Agent 可观测与 trace 落库）
**日期**: 2026-08-03
**测试标准**: ISO/IEC/IEEE 29119-3
**模板版本**: 1.0

## 摘要

| 类别 | 用例数 |
|------|--------|
| functional | 5 |
| integration | 2 |
| **合计** | **7** |

> validate_st_cases.py 仅接受 FUNC/BNDRY/UI/SEC/PERF 类别；2 个真实落库/SSE 端到端场景归入 FUNC-006/007 并标记 PENDING-MANUAL。

## 测试用例

### 用例编号

ST-FUNC-021-001

### 关联需求

FR-014 trace 落库字段

### 测试目标

验证 AgentTraceCollector.record 保存实体并序列化 args / resultSummary。

### 前置条件

- mock AgentTraceRepository

### 测试步骤

| Step | 操作 | 预期结果 |
|---|---|---|
| 1 | record(chat-001, 1, "kb_search", {query: "产品A"}, "命中 3 条", 320, "done") | 不抛异常 |
| 2 | verify(repo).save(entity) | 收到 entity |
| 3 | 读取 entity | chatId=chat-001, round=1, toolName=kb_search, toolArgs 含 query/产品A, resultSummary=命中 3 条, durationMs=320, status=done |

### 验证点

- record 落库字段正确。
- args 序列化为 JSON。

### 后置检查

- 无。

### 元数据

- **优先级**: High
- **类别**: functional
- **已自动化**: Yes
- **测试引用**: `AgentTraceCollectorTest::recordShouldSaveEntityWithSerializedArgsAndTruncatedSummary`
- **Test Type**: Mock

---

### 用例编号

ST-FUNC-021-002

### 关联需求

FR-014 summary 截断

### 测试目标

验证 summary > 500 字时截断为 500 + 省略号。

### 前置条件

- mock repo

### 测试步骤

| Step | 操作 | 预期结果 |
|---|---|---|
| 1 | record 传入 800 字 summary | repo.save 收到的 summary 长度 = 501 |
| 2 | 读取 summary | 以 "…" 结尾 |

### 验证点

- summary 截断不影响主调用。

### 后置检查

- 无。

### 元数据

- **优先级**: High
- **类别**: functional
- **已自动化**: Yes
- **测试引用**: `AgentTraceCollectorTest::recordShouldTruncateSummaryAt500Chars`
- **Test Type**: Mock

---

### 用例编号

ST-FUNC-021-003

### 关联需求

FR-014 异常隔离

### 测试目标

验证 record 落库抛异常被吞掉，不影响调用方。

### 前置条件

- mock repo.save 抛 RuntimeException

### 测试步骤

| Step | 操作 | 预期结果 |
|---|---|---|
| 1 | record(...) | 不抛异常（catch + warn） |

### 验证点

- trace 落库失败不拖垮主链路。

### 后置检查

- log warn 含 "save failed"。

### 元数据

- **优先级**: High
- **类别**: functional
- **已自动化**: Yes
- **测试引用**: `AgentTraceCollectorTest::recordShouldSwallowExceptions`
- **Test Type**: Mock

---

### 用例编号

ST-FUNC-021-004

### 关联需求

FR-014 SSE JSON

### 测试目标

验证 sseData 输出含 chatId/round/tool/status/extra，null extra 不引入空 key。

### 前置条件

- mock repo

### 测试步骤

| Step | 操作 | 预期结果 |
|---|---|---|
| 1 | sseData(chat-004, 1, "kb_search", "done", {durationMs: "320", summary: "命中 3 条"}) | 输出有效 JSON |
| 2 | 解析 JSON | chatId/round/tool/status/durationMs/summary 字段存在 |
| 3 | sseData 传 null extra | JSON 仍合法，无 durationMs 等空字段 |

### 验证点

- SSE JSON 序列化稳定。

### 后置检查

- 无。

### 元数据

- **优先级**: High
- **类别**: functional
- **已自动化**: Yes
- **测试引用**: `AgentTraceCollectorTest::sseDataShouldProduceValidJsonWithAllFields`、`sseDataWithNullExtraShouldStillReturnBaseJson`
- **Test Type**: Mock

---

### 用例编号

ST-FUNC-021-005

### 关联需求

FR-014 trace 查询

### 测试目标

验证 getTraces 透传 repo.findByChatIdOrderByRound。

### 前置条件

- mock repo

### 测试步骤

| Step | 操作 | 预期结果 |
|---|---|---|
| 1 | mock repo.findByChatIdOrderByRound(chat-006) 返回 list | getTraces 返回 list |
| 2 | verify(repo).findByChatIdOrderByRound(chat-006) | 调用正确 |

### 验证点

- 查询路径透传。

### 后置检查

- 无。

### 元数据

- **优先级**: Medium
- **类别**: functional
- **已自动化**: Yes
- **测试引用**: `AgentTraceCollectorTest::getTracesShouldDelegateToRepo`
- **Test Type**: Mock

---

### 用例编号

ST-FUNC-021-006

### 关联需求

FR-014 端到端 trace 落库

### 测试目标

验证真实 LLM Agent 运行后 agent_trace 表存在 N 行 trace。

### 前置条件

- 后端、数据库、Chroma 已启动
- 有效 LLM + 知识库 COMPLETED 文档

### 测试步骤

| Step | 操作 | 预期结果 |
|---|---|---|
| 1 | 调 agentic 问答 | 回答返回 |
| 2 | 查询 agent_trace | 该 chat_id 下有 N 行 |
| 3 | 检查每行 | tool_name/tool_args/result_summary/duration_ms/round 完整 |

### 验证点

- 端到端 trace 落库。

### 后置检查

- 删除 trace/对话，停止服务。

### 元数据

- **优先级**: High
- **类别**: functional
- **已自动化**: No
- **手动测试原因**: external-action: 需真实 LLM + 数据库 + Chroma。
- **测试引用**: N/A
- **Test Type**: Real

---

### 用例编号

ST-FUNC-021-007

### 关联需求

FR-014 SSE agent_step 端到端

### 测试目标

验证流式问答期间，SSE 推送 `event: agent_step` 事件。

### 前置条件

- 后端 + 数据库 + 浏览器/curl 流式客户端
- agentic 模式 + KB 有文档

### 测试步骤

| Step | 操作 | 预期结果 |
|---|---|---|
| 1 | 触发流式问答 | 接收 SSE 流 |
| 2 | 解析 event 行 | 出现 `event: agent_step` |
| 3 | 解析 data | 包含 round/tool/status/args |

### 验证点

- SSE agent_step 推送符合协议。

### 后置检查

- 关闭 SSE 连接，停止服务。

### 元数据

- **优先级**: High
- **类别**: functional
- **已自动化**: No
- **手动测试原因**: external-action: 需真实后端 + 流式客户端。
- **测试引用**: N/A
- **Test Type**: Real

## 可追溯矩阵

| 用例 ID | 关联需求 | verification_step | 自动化测试 | Test Type | 结果 |
|---------|----------|-------------------|-----------|----------|------|
| ST-FUNC-021-001 | FR-014 | verification_step[0] | AgentTraceCollectorTest::recordShouldSaveEntityWithSerializedArgsAndTruncatedSummary | Mock | PASS |
| ST-FUNC-021-002 | FR-014 | verification_step[0] | AgentTraceCollectorTest::recordShouldTruncateSummaryAt500Chars | Mock | PASS |
| ST-FUNC-021-003 | FR-014 | verification_step[0] | AgentTraceCollectorTest::recordShouldSwallowExceptions | Mock | PASS |
| ST-FUNC-021-004 | FR-014 | verification_step[1] | AgentTraceCollectorTest::sseData | Mock | PASS |
| ST-FUNC-021-005 | FR-014 | verification_step[0] | AgentTraceCollectorTest::getTracesShouldDelegateToRepo | Mock | PASS |
| ST-FUNC-021-006 | FR-014 | verification_step[0] | N/A | Real | PENDING-MANUAL |
| ST-FUNC-021-007 | FR-014 | verification_step[1] | N/A | Real | PENDING-MANUAL |

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

## 自动化验收执行证据

- `AgentTraceCollectorTest` 6/6 通过。
- 5 个 Mock 用例 + 2 个真实环境 PENDING-MANUAL。
