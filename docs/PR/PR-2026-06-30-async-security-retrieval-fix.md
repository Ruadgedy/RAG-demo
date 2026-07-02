# PR 改动说明 — 2026-06-30 ASYNC 403 修复 + 流式容错 + 多知识库串答修复

> **日期**：2026-06-30
> **分支**：`dev-0629`（**所有改动尚未提交**，仅在工作区）
> **规模**：6 个文件修改 / 1 个文件删除（+ 1 个新增 endpoint）
> **前置依赖**：无；可独立部署
> **关联历史**：`docs/PR/PR-2026-06-27-sse-security-stability.md`、`docs/PR/PR-2026-06-29-rag-quality-evaluation.md`

---

## 一、TL;DR

本次增量解决用户提问后暴露的 **3 个串联问题**，根因分属后端安全配置、前端容错、检索隔离三层。问题 1 是因，问题 2 部分是果；问题 3 是独立检索缺陷，与 1/2 无因果但同期暴露：

1. **🔴 问题 1：`AccessDeniedException: Access Denied`** — `/api/chat/stream` 返回 `Flux<ServerSentEvent>` 触发 Servlet ASYNC dispatch，二次进入过滤器链时 JWT 过滤器不重跑 → 认证被覆盖为 anonymous → `/api/**` 403。
2. **🔴 问题 2：一次提问回答两次，第二次稳定 `timeout of 30000ms exceeded`** — 问题 1 的 403 连累 `sendMessage` 流后的 axios 调用（`fetchConversations`/`refreshCurrentConversation`，timeout=30s），失败被 `catch` 兜底新增第二条 assistant 气泡。
3. **🔴 问题 3：同一对话组连续提问，第三次偶发"该知识库暂无文档，请先上传文档。"** — Chroma collection 全局共享，查询期不带知识库过滤，全局召回命中别的知识库切片后被 Java 端 `validDocIds` 滤空 → 空 → 误导性提示。

修复策略见下文 §三。**问题 1/2 已代码修复并验证；问题 3 代码已修，需操作回填老向量后生效（见 §六 操作步骤）。**

---

## 二、问题定位（含诊断证据）

### 2.1 问题 1：ASYNC dispatch 403

**日志时序**（关键证据）：
```
Started async request for "/api/chat/stream"          ← 初始请求：JWT 认证成功
...
Performing async dispatch for "/api/chat/stream"      ← 异步再入过滤器链
Set SecurityContextHolder to anonymous SecurityContext ← 认证被覆盖为 anonymous
AccessDeniedException: Access Denied                   ← /api/** 要求 authenticated → 403
Unable to handle the Spring Security Exception because the response is already committed.
```

**根因链**：
- `JwtAuthenticationFilter extends OncePerRequestFilter`，默认 `shouldNotFilterAsyncDispatch()=true` → ASYNC 阶段不重新解析 JWT。
- STATELESS 模式 + `NullSecurityContextRepository`，`SecurityContext` 不跨 dispatch 保存；ASYNC dispatch 在新线程执行（`ThreadLocal` 策略），新线程 holder 为空。
- 此前的 `PreservingSecurityContextRepository` 修复**无效**：其 `loadDeferredContext()` 返回 `SecurityContextHolder.getContext()` 当前值，但新线程 holder 为空 → 仍返回 anonymous。

**关键认知**：`14:51:46` 流开始、`14:51:54` LLM 返回完整 200 答案 —— **服务端本次请求其实成功完成**，答案已发给前端。403 发生在 ASYNC dispatch 阶段（response 已 committed），写不进响应体。

### 2.2 问题 2：回答两次 + 30s timeout

- 第一条 assistant 气泡：stream 正常 chunk 累积的真实答案（已落库、已显示）。
- 第二条 assistant 气泡：`stores/chat.js` 的 `sendMessage` 在 `await sendStream()` 之后调用 `refreshCurrentConversation()` → `fetchConversations()`，走 `api/client.js` 的 axios 实例（`timeout: 30_000`）。问题 1 的 403 / 连接异常波及后续 axios 调用，30s 后抛 axios 文案 `timeout of 30000ms exceeded`（raw `fetch` 不产生此文案），被 `sendMessage` 的 `catch (e)` 兜底 `messages.value.push({ role: 'assistant', ... })` 新增为第二条气泡。

### 2.3 问题 3：多知识库串答

**诊断日志（精准命中）**：
```
Chroma 召回 3 条但无一条属于当前知识库 COMPLETED 文档
  kbId=6b0ca872-...（当前知识库）
  validDocIds=[c5c5a03f-..., 09f73172-..., 47404d63-...]   ← 本知识库 COMPLETED 文档
  命中 documentIds=[965e96c4-..., 9aa78e14-..., 6cd00d36-...]  ← 全是别的知识库的切片，无一重合
```

Chroma collection `rag-qa-collection` **全局共享**，查询时不带知识库过滤 → 全局召回。某次 query rewrite 后向量漂移，命中恰好全是别的知识库切片 → 被 Java 端 `validDocIds` 过滤光 → 空 → "暂无文档"。前两次恰好命中自己的，所以表现为"前两次正常、第三次空"的偶发感。

**顺带发现的死代码 bug**：`ChromaService.similaritySearch`（line 326）catch 所有异常返回空 list 不抛 → `RagService.retrieve` 的 `try/catch` fallback 永不触发、`fallbackRetrieve` 形同虚设。本轮用诊断日志标注，未改动其行为。

---

## 三、方案

| 问题 | 修法 | 状态 |
|---|---|---|
| 1 ASYNC 403 | 方案 A：覆写 `shouldNotFilterAsyncDispatch()` 返回 false，ASYNC 阶段重新解析 JWT 重建认证；删除无效 `PreservingSecurityContextRepository` | ✅ 已改 + 验证 |
| 2 回答两次 + 30s | `sendMessage.catch` 不再新增第二条气泡，合并进占位消息；流后 `refresh/fetchConversations` 移出主 try、独立容错不连累主流程 | ✅ 已改 |
| 3 多知识库串答 | 方案 B：Chroma 切片 metadata 补 `knowledgeBaseId`，查询期 `where={knowledgeBaseId}` 过滤召回；新增 `reprocess` 端点回填老向量 | ✅ 已改，需操作回填 |

---

## 四、改动清单

### 4.1 后端

**`config/JwtAuthenticationFilter.java`** — 问题 1 修复
- 覆写 `shouldNotFilterAsyncDispatch()` 返回 `false`，使 ASYNC dispatch 阶段重新解析 `Authorization` header 重建认证。STATELESS 下语义清晰，stream 请求量小，多一次 JWT 解析可接受。

**`config/SecurityConfig.java`** — 问题 1 清理
- 删除无效的 `.securityContext(... PreservingSecurityContextRepository ...)` 配置块（无 import 需清理，恢复默认 `NullSecurityContextRepository`）。

**`config/PreservingSecurityContextRepository.java`** — 删除
- 实现无效（ASYNC 在新线程，`ThreadLocal` holder 为空，返回的仍是 anonymous），方案 A 已覆盖。

**`service/ChromaService.java`** — 问题 3 修复（写入 + 查询）
- `addDocument` 新增重载 `addDocument(documentId, knowledgeBaseId, chunkIndex, content, embedding)`，metadata 写入 `knowledgeBaseId`；老签名保留兼容（委托新方法，传 null）。
- `similaritySearch` 新增重载 `similaritySearch(query, knowledgeBaseId, topK)`，`knowledgeBaseId != null` 时请求体加 `where={knowledgeBaseId}` 过滤召回；老签名保留。
- 异常日志升级，明确标注"返回空将导致上层提示该知识库暂无文档"，带 query/topK/err（诊断价值，非噪音）。

**`service/DocumentProcessService.java`** — 问题 3（写入侧调用）
- `processBatch` 调 `addDocument` 时传入 `document.getKnowledgeBaseId()`。
- ⚠️ 注：本文件 `streamParseToTempFile` 的大段改动**不是本次引入**，是工作区既有未提交改动，按最小变更原则未触碰。

**`service/RagService.java`** — 问题 3（查询侧调用 + 诊断日志）
- `retrieve` 召回调用改为 `similaritySearch(query, knowledgeBaseId, fetchSize)` 按知识库过滤。
- `candidates.isEmpty()` 与 `validCandidates.isEmpty()` 两个分支各加 `log.warn`，打出现象/query/kbId/validDocIds/命中 documentIds（保留，供后续诊断）。
- `retrieveForStreaming` 加 `log.info` 输出检索结果数量。

**`service/DocumentService.java`** — 新增 `reprocessDocument(UUID id)`
- 清旧 Chroma 向量 / BM25 索引 / MySQL chunk 记录 → 重置状态为 UPLOADING（不删 Document 记录、不删本地文件）→ 用原始 `filePath` 重新触发 `processDocumentAsync`。用于回填老向量的 `knowledgeBaseId` metadata。

**`controller/DocumentController.java`** — 新增 `POST /api/documents/{id}/reprocess`
- 触发 `reprocessDocument`；原始文件不存在时返回 409。

**`controller/ChatController.java` / `service/ChatService.java`** — 无改动
- `streamChat` 已在初始请求阶段捕获 `Authentication` 并传入，userId 取自该方法参数，不依赖 async 阶段 holder。

### 4.2 前端

**`stores/chat.js` — `sendMessage`** — 问题 2 修复
- `catch (e)` 不再 `push` 第二条 assistant 气泡：改为若末尾已有占位 assistant 气泡则把错误合并进去（与 `sendStream` 的 `onError` 行为一致），避免"回答两次"。
- `refreshCurrentConversation()` / `fetchConversations()` 移出主 try、各自独立 try/catch：失败只 `console.warn`，不再抛 axios 30s timeout 进主 catch 制造第二条气泡，也不再连累已成功展示的答案。

**`api/chat.js` / `api/client.js`** — 无改动
- `streamChat` 的 `onError` 已合并进占位消息且 `onDone`、无 rethrow，行为正确。`client.js` 的 `timeout: 30_000` 保持（普通 REST 接口合理，问题在调用容错而非全局超时值）。

### 4.3 改动规模

```
 rag-qa-backend/.../config/JwtAuthenticationFilter.java         |  +19
 rag-qa-backend/.../config/SecurityConfig.java                  |  -6
 rag-qa-backend/.../config/PreservingSecurityContextRepository.java | 删除
 rag-qa-backend/.../service/ChromaService.java                  |  +46
 rag-qa-backend/.../service/DocumentProcessService.java         |  +2（仅 addDocument 调用）
 rag-qa-backend/.../service/RagService.java                     |  +17
 rag-qa-backend/.../service/DocumentService.java                |  +44
 rag-qa-backend/.../controller/DocumentController.java          |  +26
 rag-qa-frontend/src/stores/chat.js                             |  +19（sendMessage 容错）
```

---

## 五、验证

1. **编译**：`mvn -q -o compile`（rag-qa-backend）通过，删除文件无残留引用。
2. **问题 1 验证**：提问时后端日志 `Performing async dispatch` 后**不再**出现 `Set SecurityContextHolder to anonymous` 与 `AccessDeniedException`，`"ASYNC" dispatch` 后 `status 200`（已由 15:31 日志证实）。
3. **问题 2 验证**：一次提问只出现**一条** assistant 气泡（流式答案），无 `timeout of 30000ms exceeded` 第二条气泡；多轮连续提问同理。
4. **问题 3 验证**（**需先回填**）：回填后第三次提问正常返回答案，日志 `[stream] 检索完成: retrieved=N`（N>0），不再出现"召回 N 条但无一条属于当前知识库"；A 知识库提问召回的全是 A 的文档。
5. **容错验证**（可选）：中断 stream 时只在占位消息内显示中断提示，不新增第二条气泡。

---

## 六、操作步骤（问题 3 回填 —— 部署后必须执行）

> 老切片写入时无 `knowledgeBaseId` metadata，加了 `where` 过滤后会**全部漏召回**（比修前更糟），**必须先回填**。

对每个历史 COMPLETED 文档调用一次（带 JWT）：

```
POST /api/documents/{id}/reprocess
Authorization: Bearer <token>
```

当前需回填的文档 id（来自诊断日志，本知识库 3 个，其他知识库的全部文档也要回填）：
- `c5c5a03f-74dc-44c8-a1a9-823a550ac614`
- `09f73172-9785-4dc9-8b8e-3ca0f3af1f82`
- `47404d63-799d-4915-a555-03e0a7d0f8e0`

调用后：清旧向量/chunk → 状态重置 UPLOADING → 重新走解析/切片/向量化（重跑 Ollama embedding，耗时取决于文档大小）→ 状态变回 `COMPLETED` 即生效。可通过 SSE（`/api/knowledge-bases/{kbId}/documents/stream`）或 `GET /api/documents/{id}` 观察进度。

---

## 七、风险与遗留

1. **`HybridSearchService` 未同步**：其内部 `similaritySearch` 调用（line 271/377）仍是老签名（无知识库过滤），当前 `RagService` 主路径未启用它。日后若切混合检索，**必须同步传 `knowledgeBaseId`**，否则会重现串答。
2. **死代码 bug 未修**：`ChromaService.similaritySearch` catch 异常返回空、不抛，导致 `RagService.retrieve` 的 `fallbackRetrieve` 永不触发。本轮仅加诊断日志标注，未改行为；建议后续把异常改为向上抛，让 fallback 生效，避免 Chroma 偶发异常时误报"暂无文档"。
3. **`DocumentProcessService.streamParseToTempFile`** 的工作区改动非本次引入，未触碰，需由原作者确认其意图后单独提交。
4. **前端流式无超时**：`streamChat` 用 raw `fetch` 无超时，长答案无保护。当前非阻塞主流程，未改，建议后续评估是否加 AbortController 超时。

---

## 八、变更文件索引

| 文件 | 类型 | 说明 |
|---|---|---|
| `rag-qa-backend/.../config/JwtAuthenticationFilter.java` | 修改 | 覆写 `shouldNotFilterAsyncDispatch` |
| `rag-qa-backend/.../config/SecurityConfig.java` | 修改 | 删除无效 securityContext 配置 |
| `rag-qa-backend/.../config/PreservingSecurityContextRepository.java` | **删除** | 实现无效 |
| `rag-qa-backend/.../service/ChromaService.java` | 修改 | metadata + where 过滤 |
| `rag-qa-backend/.../service/DocumentProcessService.java` | 修改 | addDocument 传 knowledgeBaseId |
| `rag-qa-backend/.../service/RagService.java` | 修改 | 查询侧过滤 + 诊断日志 |
| `rag-qa-backend/.../service/DocumentService.java` | 修改 | 新增 reprocessDocument |
| `rag-qa-backend/.../controller/DocumentController.java` | 修改 | 新增 reprocess endpoint |
| `rag-qa-frontend/src/stores/chat.js` | 修改 | sendMessage 容错治理 |
