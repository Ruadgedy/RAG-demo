# Feature #23 — 前端对话模式切换 UI（per-conversation RAG 模式）

| 项目 | 内容 |
|------|------|
| **Feature ID** | #23 |
| **关联类** | `ConfigController`、`stores/config.js`、`stores/chat.js`、`RagModeToggle.vue` |
| **关联需求** | FR-012（Agentic 问答模式） |
| **前置依赖** | F17~F21（agent loop + trace 落库）+ F20（rag.mode 路由 + per-conversation 覆盖） |
| **优先级** | P1（用户体验） |
| **编写日期** | 2026-07-07 |

---

## 1. 功能概述

### 1.1 背景

Wave 1 Agentic RAG 在后端已具备 per-conversation RAG 模式（conv.rag_mode 字段，PATCH /api/conversations/{id}/rag-mode）。前端缺少 UI 入口，用户只能在对话组维度切换模式（不可见）。

### 1.2 新增

- **后端** `GET /api/config` 暴露 `rag.mode` 全局默认值，前端无需硬编码
- **前端** `RagModeToggle` 两-pill 切换组件，挂在 ChatView 顶部消息区上方
- **store** 新增 `configStore`（全局默认值）+ `chat.currentConversation` / `effectiveRagMode` / `updateRagMode()`
- **生效逻辑**：前端 `effectiveRagMode = conv.rag_mode ?? globalRagMode`，后端 `ChatService` 同样：`conversation.rag_mode > application.rag.mode`

### 1.3 用户路径

1. 用户登录，进入对话界面，看 KB 列表
2. 选知识库后 ChatView 顶部出现"传统 / 智能体"切换条
3. 当前未选对话 → 默认显示全局值（如 linear），disabled
4. 选/新建对话 → toggle 可用，显示当前生效模式
5. 点击"智能体"→ 立即乐观更新 UI（无需等后端），发 PATCH，失败回滚 + Toast

---

## 2. 验收用例（devtools 手动）

### 2.1 ST-23-1 toggle 显示与默认态

**前置**：用户登录、`application.properties` 中 `rag.mode=linear`

| Step | 操作 | 期望 |
|---|---|---|
| 1 | 打开 `/`，选任一 KB | ChatView 顶部出现"传统 / 智能体"pill 切换条 |
| 2 | 此时未选/无对话组 | toggle disabled；"传统"高亮（linear=white bg） |
| 3 | 浏览器控制台 `useConfigStore().ragMode` | 返回 `"linear"` |
| 4 | 浏览器控制台 `useChatStore().effectiveRagMode` | 返回 `"linear"`（fallback 全局默认） |

### 2.2 ST-23-2 切换到智能体模式并持久化

**前置**：已有对话组（任一 KB）

| Step | 操作 | 期望 |
|---|---|---|
| 1 | 点击 toggle 上的"智能体" | 立即变色（品牌紫渐变 + 白文）；"传统"恢复正常态 |
| 2 | 控制台 `useChatStore().currentConversation.ragMode` | 立即返回 `"agentic"`（乐观更新） |
| 3 | DevTools Network → PATCH `/api/conversations/{id}/rag-mode` | 200，body 含 `"ragMode":"agentic"` |
| 4 | DB：`SELECT rag_mode FROM conversation WHERE id=?` | 该会话 rag_mode = `agentic` |
| 5 | 刷新页面 | toggle 仍显示"智能体"（已持久化） |
| 6 | 切换回"传统" | 同理，rag_mode = `linear` |

### 2.3 ST-23-3 切换后实际路由生效

**前置**：已完成 ST-23-2 第 1 步（切到智能体）

| Step | 操作 | 期望 |
|---|---|---|
| 1 | 发提问"产品A价格"（KB 内无答案） | 后端走 AgenticRagService 路径 |
| 2 | DB：`SELECT round, tool_name FROM agent_trace WHERE chat_id=?` | 至少 1 行（kb_search 或 web_search） |
| 3 | DB：`SELECT JSON_EXTRACT(rag_metadata, '$.agent_mode') FROM chat_history WHERE chat_id=?` | `"agentic"` |
| 4 | DevTools Network → POST `/api/chat/stream` | 流式响应里出现 `event: agent_step` 行 |
| 5 | 切回 linear 再发同样的提问 | agent_trace 不再写入；rag_metadata.agent_mode = `linear` |

### 2.4 ST-23-4 新对话默认继承全局

**前置**：控制台 `useConfigStore().ragMode="agentic"`（操作员改后端配置 + 重启服务；前端 store 缓存 cold start）

| Step | 操作 | 期望 |
|---|---|---|
| 1 | 在线性对话旁点"新建对话" | toggle 仍 disabled（需要先切到该对话） |
| 2 | 切换到新对话 | toggle 可用，"智能体"高亮（继承全局） |
| 3 | 控制台 `useChatStore().effectiveRagMode` | `"agentic"` |
| 4 | 控制台 `useChatStore().currentConversation.ragMode` | `null`（未覆盖） |
| 5 | DB `SELECT rag_mode FROM conversation WHERE id=?` | `NULL` |

### 2.5 ST-23-5 失败回滚

**前置**：用浏览器 Network Throttling / Mock Server 改 PATCH 返回 500

| Step | 操作 | 期望 |
|---|---|---|
| 1 | 点击另一模式 | UI 先变（乐观） |
| 2 | 等 ~1s，请求返回 500 | 触发 store 内的 catch：UI 回滚到原值 + 全局 Toast 红条"切换失败：..." |
| 3 | 控制台 `currentConversation.ragMode` | 仍是旧值（非乐观值） |
| 4 | DB `SELECT rag_mode FROM conversation WHERE id=?` | 仍是旧值（未持久化） |

### 2.6 ST-23-6 流式锁定

**前置**：发一长问题触发流式

| Step | 操作 | 期望 |
|---|---|---|
| 1 | 流式问答进行中 | toggle `disabled`（视觉上半透明），不可点击 |
| 2 | 流结束 | toggle 恢复可点 |
| 3 | 切换模式立即生效 | 进入下一轮问答用新 mode |

---

## 3. 自动化测试覆盖

| 层级 | 文件 | 用例 |
|---|---|---|
| 后端单测 | `ConfigControllerTest.java` | `ragMode` 注入 + 历史窗口注入 |
| 前端构建 | `npm run build` | vite production build 成功，无新增警告 |
| 后端回归 | `mvn test` | 108/108（+2 F23，新增 ConfigControllerTest） |

---

## 4. 非功能验证

| 项 | 校验 |
|---|---|
| 鉴权 | `/api/config` 与 `/api/**` 同规，受 Spring Security 保护（需已登录） |
| 性能 | `fetchConfig` 只在 ChatView onMounted 拉一次，store 内 `loaded` flag 防重复 |
| 失败隔离 | `updateRagMode` store catch：回滚 + Toast；不抛 axios 错误给上层 |
| 离线 | config fetch 失败时 fallback `linear`（前端默认值兜底） |
| 跨对话 | 切换对话 → effectiveRagMode 自动重算（computed 依赖 currentConversation） |
| 流式锁定 | streaming 期间 toggle disabled，避免半路切 mode 引起流混乱 |

---

## 5. 不在范围内

- 不同 KB 的 mode 偏好（per-KB）：当前模型用 conversation 维度
- mode 切换的快捷键（如 ⌘⇧M）：未实现
- per-message 维度（单条问答 override）：F19/F20 模型只支持 conversation 维度
- F21 SSE `agent_step` 事件的前端可视化：本 ST 仅验证 event 行存在；UI 动画留 P3
