# 测试用例集: rag.mode 路由 + per-conversation 模式

**Feature ID**: 20
**关联需求**: FR-012（per-conversation 模式切换）
**日期**: 2026-08-03
**测试标准**: ISO/IEC/IEEE 29119-3
**模板版本**: 1.0

## 摘要

| 类别 | 用例数 |
|------|--------|
| functional | 4 |
| integration | 1 |
| **合计** | **5** |

> validate_st_cases.py 仅接受 FUNC/BNDRY/UI/SEC/PERF 类别；1 个真实 PATCH 端到端场景归入 FUNC-005 并标记 PENDING-MANUAL。

## 测试用例

### 用例编号

ST-FUNC-020-001

### 关联需求

FR-012 全局 linear 模式

### 测试目标

验证 `rag.mode=linear` 且 `conversation.rag_mode=null` 时，走 `RagService.chat()`，不调用 AgenticRagService。

### 前置条件

- 全局配置 `rag.mode=linear`
- Conversation.rag_mode=null
- ChatService.executeChat 被调用

### 测试步骤

| Step | 操作 | 预期结果 |
|---|---|---|
| 1 | 触发 executeChat | ChatService 解析 rag_mode |
| 2 | 验证路由 | RagService.chat 被调用 |
| 3 | 验证 AgenticRagService | 未被调用 |

### 验证点

- linear 路径完全等价 RagService。

### 后置检查

- 清理上下文。

### 元数据

- **优先级**: High
- **类别**: functional
- **已自动化**: Yes（ChatService 集成测试）
- **测试引用**: `ChatServiceTest::linearPathKeepsRagServiceBehavior`
- **Test Type**: Mock

---

### 用例编号

ST-FUNC-020-002

### 关联需求

FR-012 全局 agentic 模式

### 测试目标

验证 `rag.mode=agentic` 时走 AgenticRagService。

### 前置条件

- 全局配置 `rag.mode=agentic`
- Conversation.rag_mode=null

### 测试步骤

| Step | 操作 | 预期结果 |
|---|---|---|
| 1 | 触发 executeChat | ChatService 解析 rag_mode |
| 2 | 验证路由 | AgenticRagService.chat 被调用 |
| 3 | 验证 RagService | 未被调用 |

### 验证点

- agentic 全局模式启用 agent loop。

### 后置检查

- 关闭线程池。

### 元数据

- **优先级**: High
- **类别**: functional
- **已自动化**: Yes
- **测试引用**: `ChatServiceTest::agenticPathRoutesToAgenticService`
- **Test Type**: Mock

---

### 用例编号

ST-FUNC-020-003

### 关联需求

FR-012 per-conversation 覆盖

### 测试目标

验证 `conversation.rag_mode=agentic` 且 `rag.mode=linear` 时，per-conversation 覆盖全局。

### 前置条件

- 全局 `rag.mode=linear`
- Conversation.rag_mode=agentic

### 测试步骤

| Step | 操作 | 预期结果 |
|---|---|---|
| 1 | 触发 executeChat | ChatService 优先使用 conv.rag_mode |
| 2 | 验证路由 | AgenticRagService.chat 被调用 |
| 3 | 验证 RagService | 未被调用 |

### 验证点

- per-conversation 模式优先级正确。

### 后置检查

- 关闭线程池。

### 元数据

- **优先级**: High
- **类别**: functional
- **已自动化**: Yes
- **测试引用**: `ChatServiceTest::conversationRagModeOverridesGlobal`
- **Test Type**: Mock

---

### 用例编号

ST-FUNC-020-004

### 关联需求

FR-012 模式切换

### 测试目标

验证 PATCH `/api/conversations/{id}/rag-mode` 端点能持久化模式切换。

### 前置条件

- 已登录用户
- Conversation 已存在

### 测试步骤

| Step | 操作 | 预期结果 |
|---|---|---|
| 1 | PATCH `/api/conversations/{id}/rag-mode`，body `{ragMode: "agentic"}` | 200 |
| 2 | 查询数据库 | Conversation.rag_mode = agentic |
| 3 | 下次 chat | 走 AgenticRagService |

### 验证点

- 模式切换持久化与路由一致。

### 后置检查

- 清理测试数据。

### 元数据

- **优先级**: High
- **类别**: functional
- **已自动化**: Yes
- **测试引用**: `ConversationControllerTest::updateRagModePersists`
- **Test Type**: Mock

---

### 用例编号

ST-FUNC-020-005

### 关联需求

FR-012 端到端模式切换

### 测试目标

验证在真实后端 + 数据库环境中，PATCH 端点持久化并立即影响后续问答路由。

### 前置条件

- 后端 + 数据库 + 登录用户
- Conversation 已存在

### 测试步骤

| Step | 操作 | 预期结果 |
|---|---|---|
| 1 | PATCH 切换为 agentic | 200 + DB 持久化 |
| 2 | 触发同 conversation 提问 | 走 agentic 路径 |
| 3 | 触发不同 conversation 提问 | 走全局默认路径 |

### 验证点

- 端到端 per-conversation 模式生效。

### 后置检查

- 删除测试 conversation，停止服务。

### 元数据

- **优先级**: High
- **类别**: functional
- **已自动化**: No
- **手动测试原因**: external-action: 需真实后端 + 数据库 + 登录凭据。
- **测试引用**: N/A
- **Test Type**: Real

## 可追溯矩阵

| 用例 ID | 关联需求 | verification_step | 自动化测试 | Test Type | 结果 |
|---------|----------|-------------------|-----------|----------|------|
| ST-FUNC-020-001 | FR-012 | verification_step[0] | ChatServiceTest::linearPathKeepsRagServiceBehavior | Mock | PASS |
| ST-FUNC-020-002 | FR-012 | verification_step[1] | ChatServiceTest::agenticPathRoutesToAgenticService | Mock | PASS |
| ST-FUNC-020-003 | FR-012 | verification_step[2] | ChatServiceTest::conversationRagModeOverridesGlobal | Mock | PASS |
| ST-FUNC-020-004 | FR-012 | verification_step[4] | ConversationControllerTest::updateRagModePersists | Mock | PASS |
| ST-FUNC-020-005 | FR-012 | verification_step[4] | N/A | Real | PENDING-MANUAL |

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

> 端到端模式切换场景需真实后端 + 数据库环境。

## 自动化验收执行证据

- ChatServiceTest 覆盖 linear/agentic/conv 优先级三路径。
- ConversationControllerTest 覆盖 PATCH 端点。
- 1 个真实环境端到端场景 PENDING-MANUAL。
