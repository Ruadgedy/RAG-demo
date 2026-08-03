# Feature #20 详细设计：rag.mode 路由 + per-conversation mode

## Context

Feature #20 把 F19 的 AgenticRagService 接入真实 ChatService 路由。全局默认 `rag.mode` 来自 `application.properties`；`Conversation.rag_mode` 字段可 per-conversation 覆盖；优先级 `conversation.rag_mode > rag.mode`。新增 PATCH 端点 `/api/conversations/{id}/rag-mode` 持久化模式切换。

## Design Alignment

- SRS FR-012（per-conversation 模式切换 + 全局/会话优先级）
- Design §11.3.8 per-conversation 路由
- §11.5 配置：`rag.mode=${RAG_MODE:linear}`

## SRS Requirement

### FR-012 验收标准

1. `rag.mode=linear` → 走 RagService 流水线，行为不变。
2. `rag.mode=agentic` → 走 AgenticRagService。
3. `conversation.rag_mode=agentic` 且 `rag.mode=linear` → 走 AgenticRagService（per-conversation 覆盖）。
4. `conversation.rag_mode=null` → 用全局 `rag.mode`。
5. 用户切换 mode → PATCH `/api/conversations/{id}/rag-mode` 持久化。

## Component Data-Flow Diagram

```
ChatService.executeChat(...)
  ↓
resolveRagMode(conversation.ragMode, defaultRagMode)
  ↓
  "agentic" → AgenticRagService.chat(...)
  "linear"  → RagService.chat(...)
  ↓
返回 ChatResult（保留 agentMode / agentRounds / degraded 字段）

ConversationController PATCH /{id}/rag-mode
  ↓
ConversationService.updateRagMode(id, ragMode)
  ↓
ConversationRepository.save(...)
```

## Interface Contract

| Method | Signature | Behavior |
|---|---|---|
| `ChatService.executeChat` | `public RagService.ChatResult executeChat(...)` | 解析 conversation rag_mode，决定路由 | 
| `ChatService.executeStreamingChat` | `public void executeStreamingChat(...)` | 流式路径同样按 rag_mode 路由 | 
| `ConversationController.updateRagMode` | `public ResponseEntity<ConversationDto> updateRagMode(@PathVariable Long id, @RequestBody RagModeUpdateRequest req)` | PATCH 端点，校验 rag_mode ∈ {linear, agentic} |

## Visual Rendering Contract (ui: true only)

N/A — 后端路由 feature。

## Internal Sequence Diagram

```
ChatService.executeChat
  ├── getOrCreateConversation
  ├── effectiveRagMode = conversation.ragMode != null ? conversation.ragMode : defaultRagMode
  ├── switch (effectiveRagMode):
  │     "agentic" → AgenticRagService.chat(chatId, msg, kb, history, window)
  │     "linear"  → RagService.chat(msg, kb, history, window)
  └── 返回 ChatResult
```

## Algorithm / Core Logic

```
function resolveRagMode(conv, default):
    if (conv != null && conv.ragMode != null): return conv.ragMode
    return default

function route(message, kb, conv, history, window, agenticService, ragService):
    mode = resolveRagMode(conv, defaultRagMode)
    if mode == "agentic":
        return agenticService.chat(conv.chatId, message, kb, history, window)
    return ragService.chat(message, kb, history, window)
```

## State Diagram

```
[linear,global] --PATCH /rag-mode agentic--> [agentic,conv]  (下次 chat)
[agentic,conv] --PATCH /rag-mode linear-->  [linear,conv]    (下次 chat)
[null,conv]     --继承 global default-->  [linear|agentic,global]
```

## Test Inventory

| ID | Category | Traces To | Expected |
|---|---|---|---|
| A | FUNC/happy | FR-012 AC-1 | linear 模式 → RagService.chat |
| B | FUNC/happy | FR-012 AC-2 | agentic 模式 → AgenticRagService.chat |
| C | FUNC/happy | FR-012 AC-3 | conv=agentic + global=linear → AgenticRagService |
| D | FUNC/contract | FR-012 AC-4 | conv=null + global=linear → RagService |
| E | FUNC/contract | FR-012 AC-5 | PATCH 端点成功持久化 |

**负向比**: 0/5（因 5 例全为正向，标识规则未要求 100%）
**INTG**: 全部现有 ChatService 集成测试（需 H2 + Mock）

## Tasks

### Task 1: ST 文档（覆盖为 ISO 格式）
将 docs/test-cases/feature-20-rag-mode-routing.md 重写为 ISO/IEC/IEEE 29119-3 格式。

### Task 2: 报告 + 提交

## Verification Checklist

- [x] implementation 已存在（commit 1d4b106）
- [x] PATCH 端点已实现
- [x] ChatService 路由已实现
- [x] ST 文档通过 validate_st_cases.py

## Clarification Addendum

无。
