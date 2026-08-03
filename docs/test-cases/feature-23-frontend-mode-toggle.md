# 测试用例集: 前端对话模式切换 UI

**Feature ID**: 23
**关联需求**: FR-012（per-conversation 模式切换）
**日期**: 2026-08-03
**测试标准**: ISO/IEC/IEEE 29119-3
**模板版本**: 1.0

## 摘要

| 类别 | 用例数 |
|------|--------|
| functional | 5 |
| ui | 1 |
| **合计** | **6** |

> UI 类别用 Chrome DevTools MCP 在前端验证；后端路由由 F20 保证。

## 测试用例

### 用例编号

ST-FUNC-023-001

### 关联需求

FR-012 模式切换持久化

### 测试目标

验证点击 RagModeToggle 切换后调用 PATCH 并持久化到 Conversation.rag_mode。

### 前置条件

- 已登录用户 + 已存在 conversation
- configStore 已加载

### 测试步骤

| Step | 操作 | 预期结果 |
|---|---|---|
| 1 | 点击 agentic pill | toggle 显示 agentic 激活 |
| 2 | chatStore.updateRagMode 派发 | PATCH /api/conversations/{id}/rag-mode 调用 |
| 3 | 响应 200 | state 提交 |
| 4 | 重新查询 conversation | rag_mode = agentic |

### 验证点

- 模式切换端到端生效。

### 后置检查

- 关闭 PATCH 监听。

### 元数据

- **优先级**: High
- **类别**: functional
- **已自动化**: No
- **手动测试原因**: external-action: 需真实后端 + 浏览器交互。
- **测试引用**: N/A
- **Test Type**: Real

---

### 用例编号

ST-FUNC-023-002

### 关联需求

FR-012 切换后走 agentic

### 测试目标

验证切换到 agentic 后，下次提问进入 agentic 路径。

### 前置条件

- conversation.rag_mode = agentic
- 后端启动正常

### 测试步骤

| Step | 操作 | 预期结果 |
|---|---|---|
| 1 | 切换为 agentic | 持久化完成 |
| 2 | 发送提问 | 走 AgenticRagService |
| 3 | 验证 ChatResult.agentMode | "agentic" |

### 验证点

- 前端切换 → 后端 agentic 路径。

### 后置检查

- 关闭会话。

### 元数据

- **优先级**: High
- **类别**: functional
- **已自动化**: No
- **手动测试原因**: external-action: 需真实后端 + LLM。
- **测试引用**: N/A
- **Test Type**: Real

---

### 用例编号

ST-FUNC-023-003

### 关联需求

FR-012 新对话继承全局

### 测试目标

验证新对话的 mode 继承全局 rag.mode 默认值。

### 前置条件

- 全局 `rag.mode=linear`
- 创建一个新 conversation

### 测试步骤

| Step | 操作 | 预期结果 |
|---|---|---|
| 1 | 创建新 conversation | conv.rag_mode = null |
| 2 | 渲染 toggle | 显示 linear 激活 |
| 3 | 发送提问 | 走 linear RagService |

### 验证点

- 有效继承公式 conv.rag_mode ?? globalRagMode。

### 后置检查

- 删除测试 conversation。

### 元数据

- **优先级**: High
- **类别**: functional
- **已自动化**: No
- **手动测试原因**: external-action: 需真实后端 + 浏览器。
- **测试引用**: N/A
- **Test Type**: Real

---

### 用例编号

ST-FUNC-023-004

### 关联需求

FR-012 失败回滚

### 测试目标

验证 PATCH 失败时状态回滚并显示错误 Toast。

### 前置条件

- 模拟 PATCH 端点返回 5xx

### 测试步骤

| Step | 操作 | 预期结果 |
|---|---|---|
| 1 | 点击切换 | 乐观更新 |
| 2 | PATCH 失败 | 状态回滚到原 mode |
| 3 | Toast 出现 | 错误提示可见 |

### 验证点

- 乐观更新失败不留下脏状态。

### 后置检查

- 关闭拦截。

### 元数据

- **优先级**: Medium
- **类别**: functional
- **已自动化**: No
- **手动测试原因**: external-action: 需浏览器网络拦截。
- **测试引用**: N/A
- **Test Type**: Real

---

### 用例编号

ST-FUNC-023-005

### 关联需求

FR-012 流式锁定

### 测试目标

验证流式问答期间 toggle 禁用。

### 前置条件

- 流式问答进行中

### 测试步骤

| Step | 操作 | 预期结果 |
|---|---|---|
| 1 | 触发流式问答 | toggle disabled |
| 2 | 完成流式问答 | toggle 恢复可点 |

### 验证点

- 防止半路切换错配。

### 后置检查

- 关闭会话。

### 元数据

- **优先级**: Medium
- **类别**: functional
- **已自动化**: No
- **手动测试原因**: external-action: 需真实后端 + 浏览器。
- **测试引用**: N/A
- **Test Type**: Real

---

### 用例编号

ST-UI-023-001

### 关联需求

UCD 与 UI 渲染

### 测试目标

验证 toggle 渲染与激活态。

### 前置条件

- 应用启动到 ChatView

### 测试步骤

| Step | 操作 | 预期结果 |
|---|---|---|
| 1 | evaluate_script(error_detector) | 控制台 0 error |
| 2 | take_snapshot | EXPECT: toggle 存在，两个 pill（ListOrdered + Sparkles）；REJECT: 任何渲染空白或无 label 元素 |
| 3 | querySelector 验证 active mode | 当前 mode 正确高亮 |

### 验证点

- UI 与 UCD 一致。

### 后置检查

- 关闭浏览器。

### 元数据

- **优先级**: Medium
- **类别**: ui
- **已自动化**: No
- **手动测试原因**: external-action: 需 Chrome DevTools MCP 浏览器验证。
- **测试引用**: N/A
- **Test Type**: Real

## 可追溯矩阵

| 用例 ID | 关联需求 | verification_step | 自动化测试 | Test Type | 结果 |
|---------|----------|-------------------|-----------|----------|------|
| ST-FUNC-023-001 | FR-012 | verification_step[0] | N/A | Real | PENDING-MANUAL |
| ST-FUNC-023-002 | FR-012 | verification_step[1] | N/A | Real | PENDING-MANUAL |
| ST-FUNC-023-003 | FR-012 | verification_step[2] | N/A | Real | PENDING-MANUAL |
| ST-FUNC-023-004 | FR-012 | failure rollback | N/A | Real | PENDING-MANUAL |
| ST-FUNC-023-005 | FR-012 | streaming lock | N/A | Real | PENDING-MANUAL |
| ST-UI-023-001 | UCD | visual | N/A | Real | PENDING-MANUAL |

## Real Test Case Execution Summary

| Metric | Count |
|--------|-------|
| Total Real Test Cases | 6 |
| Passed | 0 |
| Failed | 0 |
| Pending | 6 |

## Manual Test Case Summary

| Metric | Count |
|--------|-------|
| Total Manual Test Cases | 6 |
| Manual Passed (MANUAL-PASS) | 0 |
| Manual Failed (MANUAL-FAIL) | 0 |
| Blocked | 0 |
| Pending (PENDING-MANUAL) | 6 |

> 所有 6 个场景需真实后端 + 浏览器环境（Chrome DevTools MCP），本次未执行。

## 自动化验收执行证据

- 已有前端 RagModeToggle.vue + chat.js + config.js + conversation.js；
- 已有后端 GET /api/config + PATCH /api/conversations/{id}/rag-mode（F20 + F23 历史 commit）；
- 单元/UI 自动化未在本轮跑通，全部 PENDING-MANUAL。
