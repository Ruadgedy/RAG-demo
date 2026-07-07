# PR 改动说明 — 2026-07-07 F23 前端对话模式切换 UI（per-conversation）

> **日期**：2026-07-07
> **分支**：`dev-agentic`
> **规模**：2 后端 + 6 前端 = 8 文件
> **上游**：F20 (rag.mode 路由 / PATCH /api/conversations/{id}/rag-mode) + F21 (agent_trace 落库)
> **关联**：Wave 1 PR `PR-2026-07-04-agentic-rag-wave1.md`；F21 PR `PR-2026-07-07-f21-agent-trace.md`
> **性质**：F23 Worker cycle 闭环（UI 落地 + 后端最小支撑）

---

## 一、TL;DR

让用户能在对话界面**即时切换"传统 RAG / 智能体模式"**，持久化到 `conversation.rag_mode`，前端按 `conv.rag_mode ?? globalRagMode` 显示当前生效模式：

- **后端**：`GET /api/config` 暴露 `rag.mode` 全局默认，避免前端硬编码 + 让新对话默认态可见
- **前端**：`RagModeToggle` 两-pill 组件（线性灰 + 智能体品牌渐变），挂在 ChatView 主区顶部
- **store**：`configStore`（全局默认）+ `chat.currentConversation/effectiveRagMode/updateRagMode`（per-conv 状态）
- **交互**：乐观更新 UI + PATCH 持久化 + 失败回滚 + 流式中锁定

不触动 linear RAG（默认 `rag.mode=linear`），已有 16 features + F17~F21 全部回归。

---

## 二、改动清单

### 2.1 后端（新增 2 文件）

| 文件 | 作用 |
|---|---|
| `dto/ConfigDto.java` | `{ ragMode, defaultHistoryWindow }` 全局配置 DTO |
| `controller/ConfigController.java` | `GET /api/config` 读 `@Value` 注解读出；当前只读，不写 |
| `test/.../ConfigControllerTest.java` | 2 例：默认值注入 + agentic 路径 |

### 2.2 前端（新增 4 + 改 2 = 6 文件）

| 文件 | 改动 |
|---|---|
| `api/config.js` 🆕 | `getConfig()` → `GET /api/config` |
| `api/conversation.js` 改 | 加 `updateRagMode(id, ragMode)`，PATCH body `{ ragMode: "linear" | "agentic" | null }` |
| `stores/config.js` 🆕 | Pinia store：`ragMode` + `defaultHistoryWindow` + `fetchConfig()` |
| `stores/chat.js` 改 | 1) `fetchConversations` 映射保留 `ragMode` 2) 新增 `currentConversation` computed 3) `effectiveRagMode` computed（conv.rag_mode ?? global） 4) `updateRagMode(convId, mode)` action（乐观 + 回滚 + Toast） |
| `components/chat/RagModeToggle.vue` 🆕 | 两-pill 切换组件；lucide `ListOrdered`（传统） + `Sparkles`（智能体）；agentic 选中态用品牌渐变 |
| `views/ChatView.vue` 改 | onMounted 拉 `configStore.fetchConfig()`；顶部 `<RagModeToggle :disabled="isStreaming \|\| !convId" />` |

### 2.3 文档

| 文件 | 作用 |
|---|---|
| `docs/test-cases/feature-23-frontend-mode-toggle.md` 🆕 | devtools ST 用例 6 个（toggle 显示 / 切换持久化 / 路由生效 / 默认继承 / 失败回滚 / 流式锁定） |

---

## 三、关键设计

### 3.1 effectiveRagMode 计算

```
effective = currentConversation?.ragMode ?? configStore.ragMode
```

确保：
- 新对话（conv.rag_mode=null）展示全局默认
- 已配置对话展示用户自己的偏好
- 切换对话自动重算（computed 依赖 reactivity）

### 3.2 乐观更新 + 回滚

`chat.updateRagMode(convId, mode)`：
1. 立即改 `conv.ragMode = mode`（前端 UI 翻动，零延迟感）
2. 异步发 PATCH 请求
3. 成功：用后端返回值覆盖（防止服务端做 coerce，如传 null 时回 `null`）
4. 失败：catch 内 `conv.ragMode = prev` 回滚 + toast.error

### 3.3 流式锁定

`disabled = chat.isStreaming || !chat.currentConversationId`

流式问答进行中 toggle 半透明 + 不可点。理由：流式中途切 mode 会让流与 SSE agent_step 错配，用户体感混乱。当前实现强制等流结束再切。

### 3.4 configStore 单次加载

`fetchConfig(force)` 用 `loaded.value` flag 防重复；fallback `linear` 当 401 / 网络断。

### 3.5 linear 路径零回归

- 默认 `rag.mode=linear`，前端默认显示线性
- 切到智能体只影响**当前对话**；新对话不继承已切过的（旧 conv.rag_mode=null）
- 用户可一键切回 linear（覆盖回 null 也行，传 `null` 后端会把它存为 null=继承全局）

---

## 四、SSE 集成（与 F21 配合）

F21 在流式通道发了 `event: agent_step` 事件；F23 mode toggle 让用户切到"智能体"后才会在 SSE 看到 agent_step（linear 路径无），前后端语义一致：用户看到 toggle 高亮，且看到思考过程动画。

---

## 五、测试

| 层级 | 通过 | 备注 |
|---|---|---|
| 后端单测 | 108/108（+2 F23） | `mvn test` |
| 前端 build | ✅ | `vite build` 234 kB / gzip 87 kB，无 F23 引入的新警告 |
| ST 用例 | 6 例 devtools | docs/test-cases/feature-23-frontend-mode-toggle.md |

---

## 六、影响分析

| Change | Affected | Impact | Action |
|---|---|---|---|
| 后端 `GET /api/config` 新增 | 前端 ChatView onMounted | **Soft** | 加 fetch；fallback linear 防 401 不影响主流程 |
| 前端 chat store 新增字段 | currentConversation + effectiveRagMode + updateRagMode | **Internal** | 其他使用方不受影响 |
| api/conversation updateRagMode | 组件 RagModeToggle 调用 | **New** | 调用前已 PATCH 端点（F20）就绪 |
| linear 路径 ragMode hard-coded | ChatResult 4-arg 构造 | **None** | F21 已 hard-code `linear` |
| ConversationDto.ragMode 透传 | chat.fetchConversations 映射 | **None** | 后端一直返回，前端现在用上 |

---

## 七、未覆盖（留待后续）

- 前端 mode toggle 在消息气泡旁的"当前轮 mode"小角标（F21 已落库，可后续 PR 做 UI）
- per-KB 模式偏好（目前 conversation 维度）
- 切换 mode 时的快捷键

---

## 八、关联

- SRS FR-012：Agentic 问答模式
- Design §11：架构 / 切换路由
- Feature 分解：feature-list.json F23（wave 1, ui=true）
- Wave 1 总 PR：docs/PR/PR-2026-07-04-agentic-rag-wave1.md
- F20：commit `1d4b106`（PATCH /api/conversations/{id}/rag-mode + ChatService 路由）
- F21：commit `eccf4db`（agent_trace 落库 + SSE agent_step + rag_metadata.agent_mode）
- ST 用例：docs/test-cases/feature-23-frontend-mode-toggle.md
