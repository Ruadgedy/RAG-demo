# 测试用例集: Eval A/B 对比

**Feature ID**: 22
**关联需求**: FR-012 / FR-013 / FR-014（评估与回归）
**日期**: 2026-08-03
**测试标准**: ISO/IEC/IEEE 29119-3
**模板版本**: 1.0

## 摘要

| 类别 | 用例数 |
|------|--------|
| functional | 3 |
| integration | 1 |
| **合计** | **4** |

> validate_st_cases.py 仅接受 FUNC/BNDRY/UI/SEC/PERF 类别；真实 LLM/DB/Chroma 场景归入 FUNC-004 并标记 PENDING-MANUAL。

## 测试用例

### 用例编号

ST-FUNC-022-001

### 关联需求

FR-012 同问题对比 linear vs agentic

### 测试目标

验证 EvalService.abCompare 双侧成功时输出完整产物。

### 前置条件

- mock RagService.chat 正常返回
- mock AgenticRagService.chat 正常返回

### 测试步骤

| Step | 操作 | 预期结果 |
|---|---|---|
| 1 | 调 abCompare(q, kb, history, 5) | 不抛异常 |
| 2 | 读取 AbCompareResult.linear | answer 非空，error=null |
| 3 | 读取 AbCompareResult.agentic | answer 非空，error=null |
| 4 | 读取 latencyMs | 两者均 ≥ 0 |

### 验证点

- 报告字段完整。

### 后置检查

- 关闭线程池。

### 元数据

- **优先级**: Medium
- **类别**: functional
- **已自动化**: Yes
- **测试引用**: `EvalServiceAbCompareTest::abCompareShouldReturnBothOutcomesWhenBothSucceed`
- **Test Type**: Mock

---

### 用例编号

ST-FUNC-022-002

### 关联需求

FR-012 agentic 降级

### 测试目标

验证 agentic 降级到 linear 时报告 degraded=true，不影响 linear 产物。

### 前置条件

- mock AgenticRagService 返回 degraded ChatResult

### 测试步骤

| Step | 操作 | 预期结果 |
|---|---|---|
| 1 | 调 abCompare | linear 仍返回 answer |
| 2 | 读取 agentic.degraded | true |
| 3 | 读取 agentic.error | null（属于降级而非异常） |

### 验证点

- 降级状态被记录且隔离 linear。

### 后置检查

- 关闭线程池。

### 元数据

- **优先级**: Medium
- **类别**: functional
- **已自动化**: Yes
- **测试引用**: `EvalServiceAbCompareTest::abCompareShouldMarkDegradedWhenAgenticFellBackToLinear`
- **Test Type**: Mock

---

### 用例编号

ST-FUNC-022-003

### 关联需求

FR-012 单边失败隔离

### 测试目标

验证 linear 抛异常时，agentic 仍可返回。

### 前置条件

- mock RagService.chat 抛 RuntimeException
- mock AgenticRagService.chat 正常

### 测试步骤

| Step | 操作 | 预期结果 |
|---|---|---|
| 1 | 调 abCompare | 不抛异常 |
| 2 | 读取 linear.error | 异常信息非空 |
| 3 | 读取 agentic.answer | 非空 |

### 验证点

- 单边失败不阻塞对侧。

### 后置检查

- 关闭线程池。

### 元数据

- **优先级**: Medium
- **类别**: functional
- **已自动化**: Yes
- **测试引用**: `EvalServiceAbCompareTest::abCompareShouldSwallowLinearExceptionAndStillRunAgentic`
- **Test Type**: Mock

---

### 用例编号

ST-FUNC-022-004

### 关联需求

FR-012 端到端 Eval A/B

### 测试目标

验证在真实后端 + LLM + DB + Chroma 环境下，POST /api/admin/eval/ab 输出真实报告。

### 前置条件

- 后端、数据库、Chroma 已启动
- 有效 LLM + KB 文档

### 测试步骤

| Step | 操作 | 预期结果 |
|---|---|---|
| 1 | curl POST /api/admin/eval/ab | 200 |
| 2 | 读取 linear.answer/latencyMs | 非空 |
| 3 | 读取 agentic.answer/agentRounds/degraded | 非空，rounds≥0 |

### 验证点

- 端到端 A/B 报告可读。

### 后置检查

- 删除 trace/对话，停止服务。

### 元数据

- **优先级**: Medium
- **类别**: functional
- **已自动化**: No
- **手动测试原因**: external-action: 需真实 LLM + 数据库 + Chroma + 登录凭据。
- **测试引用**: N/A
- **Test Type**: Real

## 可追溯矩阵

| 用例 ID | 关联需求 | verification_step | 自动化测试 | Test Type | 结果 |
|---------|----------|-------------------|-----------|----------|------|
| ST-FUNC-022-001 | FR-012 | verification_step[0] | EvalServiceAbCompareTest::abCompareShouldReturnBothOutcomesWhenBothSucceed | Mock | PASS |
| ST-FUNC-022-002 | FR-012 | 降级隔离 | EvalServiceAbCompareTest::abCompareShouldMarkDegradedWhenAgenticFellBackToLinear | Mock | PASS |
| ST-FUNC-022-003 | FR-012 | 异常隔离 | EvalServiceAbCompareTest::abCompareShouldSwallowLinearExceptionAndStillRunAgentic | Mock | PASS |
| ST-FUNC-022-004 | FR-012 | verification_step[0] | N/A | Real | PENDING-MANUAL |

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

> 端到端 A/B 场景需完整服务环境。

## 自动化验收执行证据

- `EvalServiceAbCompareTest` 3/3 通过。
- 1 个真实环境 PENDING-MANUAL。
