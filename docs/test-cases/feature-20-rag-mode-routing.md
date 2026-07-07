# Feature #20 — rag.mode 路由 + per-conversation 模式

| 项目 | 内容 |
|------|------|
| **Feature ID** | #20 |
| **关联类** | `service/ChatService`、`controller/ConversationController.updateRagMode`、V7 迁移、`model/Conversation.ragMode` |
| **关联需求** | FR-012（Agentic 问答模式） |
| **前置依赖** | F19 |
| **优先级** | P0（agentic 路由） |
| **编写日期** | 2026-07-07 |

---

## 1. 功能概述

### 1.1 背景

需要全局模式开关 + per-conversation 覆盖，灰度切换避免一次性引入风险。

### 1.2 路由公式

```
最终 mode =
    conversation.rag_mode (per-cover)
    ?? application.rag.mode (全局默认值)
```

即 `ChatService` 内：

```java
String ragMode = conv.getRagMode() != null ? conv.getRagMode() : globalRagMode;
if ("agentic".equals(ragMode)) { agenticRagService... } else { ragService... }
```

### 1.3 接口

- `PATCH /api/conversations/{id}/rag-mode` body `{"ragMode":"linear"|"agentic"|null}` —— 持久化；`null` 恢复全局默认
- `application.properties` `rag.mode=${RAG_MODE:linear}` —— 全局默认

### 1.4 Flyway V7

```sql
ALTER TABLE conversation ADD COLUMN rag_mode VARCHAR(16) DEFAULT NULL;
COMMENT '对话级 RAG 模式：linear | agentic；null=继承全局默认值'
```

---

## 2. 验收用例

### 2.1 ST-20-1 全局默认 linear（行为不变）

**前置**：`rag.mode=linear`（默认）；不配置 conversation.rag_mode

| Step | 操作 | 期望 |
|---|---|---|
| 1 | 触发线性问答 | 走 `RagService.chat()`，无 agent |
| 2 | `SELECT JSON_EXTRACT(rag_metadata, '$.agent_mode') FROM chat_history WHERE chat_id=?` | `"linear"` |
| 3 | `SELECT COUNT(*) FROM agent_trace WHERE chat_id=?` | 0 |

### 2.2 ST-20-2 全局默认 linear + 单对话 agentic

**前置**：全局 default linear；某对话 `rag_mode='agentic'`

| Step | 操作 | 期望 |
|---|---|---|
| 1 | 在该对话问 | 走 `AgenticRagService.chat` |
| 2 | `agent_mode` 落库 = "agentic" | — |
| 3 | 切到另一个对话（rag_mode=null）问 | 走 linear，agent_mode 落库 = "linear" |

### 2.3 ST-20-3 全局 agentic + 单对话 linear

**前置**：`rag.mode=agentic`；某对话覆盖 `rag_mode='linear'`

| Step | 操作 | 期望 |
|---|---|---|
| 1 | 在该对话问 | 走 linear |
| 2 | agent_mode 落库 = "linear" | 覆盖生效 |

### 2.4 ST-20-4 PATCH 切换持久化

| Step | 操作 | 期望 |
|---|---|---|
| 1 | `curl -X PATCH /api/conversations/{id}/rag-mode -d '{"ragMode":"agentic"}'` | 200，body 含 `ragMode:"agentic"` |
| 2 | DB：`SELECT rag_mode FROM conversation WHERE id=?` | `agentic` |
| 3 | 同一对话问下一题 | 走 agentic |

| Step | 操作 | 期望 |
|---|---|---|
| 4 | `curl -X PATCH .../rag-mode -d '{"ragMode":null}'`（null = 恢复全局） | 200 |
| 5 | DB：`SELECT rag_mode ...` | `NULL` |

### 2.5 ST-20-5 非法值拒绝

| Step | 操作 | 期望 |
|---|---|---|
| 1 | `PATCH .../rag-mode -d '{"ragMode":"foo"}'` | 400/IllegalArgumentException |

---

## 3. 自动化测试覆盖

| 层 | 通过条件 |
|---|---|
| 单测 | `ChatServiceRoutingTest` 覆盖：linear / agentic / conversation.rag_mode 优先 |
| 集成 | 后端启动 + curl 直接验证 |

---

## 4. 关联

- F23：前端 mode toggle UI（commit `cd36912`）
- F21：rag_metadata 增 agent_mode/rounds/degraded 字段（commit `eccf4db`）
- 设计：Design §11.1（architecture）
