# Feature #23 详细设计：前端对话模式切换 UI

## Context

Feature #23 在 ChatView 顶部挂载 RagModeToggle，提供传统/智能体两-pill 切换。toggle 点击触发 PATCH `/api/conversations/{id}/rag-mode` 持久化到 Conversation.rag_mode；前端立即乐观更新，失败回滚 + Toast。流式问答期间 toggle 禁用。`configStore` 暴露全局默认 `rag.mode`，`effectiveRagMode = conv.rag_mode ?? globalRagMode` 公式与后端一致。

## Design Alignment

- UCD Style Guide（lucide ListOrdered + Sparkles 图标 + 紫渐变品牌色）
- F20 ChatService 路由约定
- F7 Vue3 前端框架

## SRS Requirement

### FR-012 验收标准

1. 用户点击 mode toggle → 切换传统/智能体模式并持久化到 Conversation.rag_mode。
2. 切换到智能体模式后，后续提问走 AgenticRagService。
3. 新对话的 mode 默认继承全局 rag.mode。

## Component Data-Flow Diagram

```
ChatView onMounted
  ├── configStore.load() → GET /api/config
  └── (toggle 渲染) RagModeToggle v-model=effectiveRagMode

RagModeToggle click
  ├── store.updateRagMode(convId, newMode) (乐观更新本地 state)
  ├── api.updateRagMode(convId, newMode) (PATCH)
  │     ├── 200 → 提交
  │     └── 非 200 → 回滚 + Toast 错误
  └── 锁定 isStreaming 时禁用

ChatView.sendMessage
  └── 使用 effectiveRagMode 决定 /api/chat?...&ragMode=... 或后端默认
```

## Interface Contract

| 组件 / Store | Method | Behavior |
|---|---|---|
| `RagModeToggle` | `@change` emit | 通知父组件模式变更 |
| `chat.js` (Pinia) | `updateRagMode(convId, mode)` | 乐观更新 + API 调用 + 失败回滚 + Toast |
| `config.js` (Pinia) | `load()` | GET /api/config，写入 `ragMode` 与 `historyWindow` |
| `api/conversation.js` | `updateRagMode(id, mode)` | PATCH `/api/conversations/{id}/rag-mode` |

## Visual Rendering Contract (ui: true only)

| 元素 | 选择器 | 渲染技术 | 断言 |
|---|---|---|---|
| 模式切换组件 | `[data-test="rag-mode-toggle"]` | Vue3 + 紫渐变背景 | 存在且可见，含两个 pill（listordered + sparkles 图标） |
| 当前模式高亮 | `[data-test="rag-mode-toggle"][data-active="agentic"]` | DOM class | 当前 active = effectiveRagMode |
| 流式锁定 | `[data-test="rag-mode-toggle"][data-disabled="true"]` | DOM attr | isStreaming=true 时 disabled |

## Internal Sequence Diagram

```
ChatView mounted → configStore.load → GET /api/config → configStore.ragMode
RagModeToggle 渲染 → effectiveRagMode = conv.ragMode ?? configStore.ragMode
用户点击 agentic pill → 派发 updateRagMode action
  → 乐观更新 currentConversation.ragMode
  → PATCH /api/conversations/{id}/rag-mode
  → 成功：commit
  → 失败：rollback + Toast
```

## Algorithm / Core Logic

```
function effectiveRagMode(conv, global):
    return conv?.ragMode != null ? conv.ragMode : global

function updateRagMode(convId, mode):
    prev = conv.ragMode
    conv.ragMode = mode (optimistic)
    try:
        await api.updateRagMode(convId, mode)
    catch e:
        conv.ragMode = prev
        toast.error(...)
```

## State Diagram

```
[pending] --PATCH 成功--> [committed]
[pending] --PATCH 失败--> [rolled back] (回到原值)
```

## Test Inventory

| ID | Category | Traces To | Expected |
|---|---|---|---|
| A | FUNC/happy | FR-012 AC-1 | 点击 toggle 切换，调用 PATCH 持久化 |
| B | FUNC/happy | FR-012 AC-2 | 切换后下次 sendMessage 走 agentic 模式 |
| C | FUNC/contract | FR-012 AC-3 | 新对话 mode 继承全局 default |
| D | FUNC/error | FR-012 AC-1 | PATCH 失败回滚 + Toast |
| E | FUNC/guard | UCD 锁定 | isStreaming=true 时 toggle disabled |
| F | UI/render | UCD | toggle 渲染、active 高亮、disabled 属性 |

**负向比**: 1/6 ≈ 17%（< 40%）— UI feature 主要以可观察行为为主，错误路径偏少；UCD 验证由 E2E 完成。

## Tasks

### Task 1: ST 文档（覆盖 ISO 格式）
### Task 2: 报告 + 提交

## Verification Checklist

- [x] 前端组件已实现
- [x] store + API 已就绪
- [x] 后端 PATCH 端点已就绪（F20）
- [x] ST 文档通过 validate_st_cases.py

## Clarification Addendum

无。
