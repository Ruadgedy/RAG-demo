# RAG-QA 项目优化待办清单

> **状态**：Backlog（待办）
> **创建日期**：2026-06-29
> **对应项目**：`rag-qa-backend` + `rag-qa-frontend`
> **来源**：项目结构全面审计（参见同目录 `项目结构分析06-28-*` 系列文档）

---

## 1. 项目当前架构快照

```
┌─────────────────────────────────────────────────────────────────┐
│  Frontend (Vue 3 + Pinia + Vite)                              │
│  rag-qa-frontend/                                              │
│  ├─ stores/  (auth / ui / knowledgeBase / chat)                │
│  ├─ api/     (5 个模块，懒加载防循环引用)                      │
│  ├─ composables/ (useToast / useDocumentStream / useChatScroll)│
│  ├─ components/  (layout / knowledge / chat / common — 16 个)  │
│  └─ views/  (ChatView 编排层 + LoginView)                      │
└─────────────────────────────────────────────────────────────────┘
                    │ Bearer JWT (localStorage)
                    ▼
┌─────────────────────────────────────────────────────────────────┐
│  Backend (Spring Boot 3.3 + Java 17)                          │
│  rag-qa-backend/                                               │
│  ├─ Security: JWT (HS256, BCrypt) + CORS + Stateless          │
│  ├─ Document pipeline: Tika → OCR(Tess4J) → Tables(POI)        │
│  ├─ Chunking: TextSplitter (paragraph/recursive/fixed)         │
│  ├─ Embedding: Ollama qwen3-embedding:4b (4096 dim, REST)      │
│  ├─ Vector store: Chroma REST v2 (collection: rag-qa)         │
│  ├─ Retrieval: Chroma + BM25 (RRF k=60, 默认关闭)              │
│  ├─ Rerank: 已实现但未接入 RagService                          │
│  └─ Generation: Spring AI MiniMax-M2.5 + SSE 流式              │
└─────────────────────────────────────────────────────────────────┘
                    │
                    ▼
┌─────────────────────────────────────────────────────────────────┐
│  存储                                                           │
│  ├─ MySQL 8: User/KB/Document/DocumentChunk/ChatHistory         │
│  ├─ Chroma: 向量（rag-qa-collection, 4096 dim）                │
│  ├─ BM25: 进程内存 Map<docId, List<chunk>>                     │
│  └─ Local FS: uploads/ (PDF/Word/TXT 原文)                     │
└─────────────────────────────────────────────────────────────────┘
```

**RAG 数据流**：用户问 → `RagService.retrieve()` → `ChromaService.similaritySearch()` + 内存过滤 → top-K 文档片段 → `buildContext()` → `buildPrompt()` → `ChatClient.call()` → 流式输出

---

## 2. 优先级分级

| 级别 | 含义 | 标准 |
|------|------|------|
| **P0** | 必修 | 有 bug / 安全风险 / 立即影响体验 / 核心功能缺失 |
| **P1** | 高价值 | 明显提升 RAG 质量或用户体验 |
| **P2** | 锦上添花 | DX、可观测性、长期演进 |

---

## 3. P0 — 必修（4 项）

### P0-01 · 来源引用没透出前端

**问题**：LLM 被要求输出 `【文档X】` 标注来源，但前端永远收不到 `X` 是哪个文档。
- `ChatResponse` 只有 `{sessionId, answer}`
- `RetrievalResult` 列表只写进了 DB 的 `chat_history.rag_metadata` JSON，从未读出

**影响**：用户看到 AI 答得好，但**不知道依据来自哪份文档的第几页**——回答可信度为 0。

**涉及文件**：
- `rag-qa-backend/.../controller/ChatController.java`
- `rag-qa-backend/.../dto/ChatResponse.java`
- `rag-qa-backend/.../service/RagService.java`
- `rag-qa-frontend/src/components/chat/MessageBubble.vue`
- `rag-qa-frontend/src/api/chat.js`

**修复方向**：
```java
public class ChatResponse {
    String sessionId;
    String answer;
    List<SourceRef> sources;  // 新增
}
public class SourceRef {
    String documentId;
    String fileName;
    int chunkIndex;
    String snippet;          // 前 200 字摘要
    double score;
}
```
前端 `MessageBubble` 渲染为"参考 3 篇文档 ▾"可展开卡片。

**工作量**：1d ｜ **价值**：极高

---

### P0-02 · `ChatRequest.history` 字段被完全忽略

**问题**：前端老老实实把 `messages.slice(0,-2)` 当 history 传过来，但 `RagService.chat(message, kbId)` **根本不读 `history`**。多轮对话时用户问"那第二条呢？"——LLM 不知道前文，只能用当前 query 单点检索。

**涉及文件**：
- `rag-qa-backend/.../service/RagService.java`
- `rag-qa-backend/.../dto/ChatRequest.java`
- `rag-qa-backend/.../controller/ChatController.java`

**修复方向**：
```java
public ChatResult chat(String message, UUID kbId, List<ChatMessage> history) {
    // 1) query rewrite: 用最近 3 轮上下文重写当前 query
    String rewrittenQuery = queryRewriter.rewrite(message, history);
    // 2) 检索 + 生成
    var retrieved = retrieve(rewrittenQuery, kbId);
    // 3) 把 history 注入 prompt
    String prompt = buildPromptWithHistory(retrieved.context, history, message);
}
```

**工作量**：1d ｜ **价值**：极高（核心功能）

---

### P0-03 · CORS 配置违法

**问题**：
```java
config.addAllowedOriginPattern("*");
config.setAllowCredentials(true);  // ← 与通配符冲突
```
按 CORS 规范，`*` + `credentials=true` 非法，浏览器会拒绝带 cookie 的请求。

**涉及文件**：`rag-qa-backend/.../config/CorsConfig.java:15`

**影响**：当前用 token header 不受影响，但加了 cookie 鉴权 / OAuth / 第三方嵌入立刻爆。

**修复方向**：
```java
config.addAllowedOriginPattern("http://localhost:5173");
config.addAllowedOriginPattern("http://localhost:8080");
// 或从配置读: ${ALLOWED_ORIGINS:http://localhost:5173}
```

**工作量**：0.5h ｜ **价值**：中（潜在 bug）

---

### P0-04 · 文档状态 SSE 端点缺失核查

**问题**：`DocumentStatusEventService` 实现了事件推送（`ApplicationEventPublisher`），但前端 `useDocumentStream` 连的是 `/api/knowledge-bases/{id}/documents/stream`——**这个端点在 controller 里没找到**。

**涉及文件**：
- `rag-qa-backend/.../controller/DocumentController.java`（核查）
- `rag-qa-frontend/src/composables/useDocumentStream.js`

**修复方向**：先 grep 确认是否漏写了 SSE controller；不存在则补上 `@GetMapping(value = "/{kbId}/documents/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)`。

**工作量**：0.5h（核查 + 修复） ｜ **价值**：中

---

## 4. P1 — 高价值（6 项）

### P1-01 · Chroma 用 L2 距离，没归一化向量

**问题**：
```java
// ChromaService.java:275
score = 1.0 / (1.0 + distance)   // L2 距离，不是余弦
```
`qwen3-embedding:4b` 通常按 cosine 训练，但**直接存 L2 没归一化**。同样的 query，L2 距离会被向量模长污染。

**涉及文件**：
- `rag-qa-backend/.../service/ChromaService.java:275`
- `rag-qa-backend/.../service/EmbeddingService.java`

**修复方向**：embedding 生成后立刻 L2 normalize 再存；或调 Chroma collection 的 `space="cosine"`。

**工作量**：2h ｜ **价值**：高（准确率）

---

### P1-02 · `RerankService` 没接入 `RagService`

**问题**：`RerankService` 已写好，但 `RagService.retrieve()` 根本不调用。即使开启 `rerank.enabled=true` 也无效。

**涉及文件**：
- `rag-qa-backend/.../service/RerankService.java`
- `rag-qa-backend/.../service/RagService.java:156`

**修复方向**：在 `retrieve()` 末尾加一行：
```java
List<RetrievalResult> reranked = rerankService.rerank(query, retrieved, topK);
```

**工作量**：2h ｜ **价值**：高（top-3 质量）

---

### P1-03 · 混合检索（hybrid retrieval）默认值矛盾

**问题**：
- `HybridSearchService` 读 `${hybrid.retrieval.enabled:false}`（默认 false）
- `application.properties` 写 `${HYBRID_ENABLED:true}`
- 运行时实际是 false，注释 `// NOTE: defaults TRUE` 也承认了问题

**涉及文件**：
- `rag-qa-backend/.../service/HybridSearchService.java`
- `rag-qa-backend/src/main/resources/application.properties`

**修复方向**：统一两个默认值；启动时打日志打印当前开关。

**工作量**：0.5h ｜ **价值**：中

---

### P1-04 · 上传相同文档会重复向量化

**问题**：没有按 `fileName + size` 或 SHA-256 去重。用户误传同一份合同 5 次，会存 5 份向量，浪费 embedding 算力与存储。

**涉及文件**：
- `rag-qa-backend/.../service/DocumentService.java`
- `rag-qa-backend/.../model/Document.java`（新加 `file_hash` 字段）
- Flyway 新增 migration

**修复方向**：
```java
String hash = sha256(fileBytes);
if (documentRepo.existsByKbIdAndFileHash(kbId, hash)) {
    throw new DuplicateFileException();
}
```
新加 `file_hash CHAR(64)` 字段。

**工作量**：0.5d ｜ **价值**：中（成本）

---

### P1-05 · OCR 语言写死

**问题**：`OCR_LANGUAGE` 默认 `chi_sim+eng`，日文 / 繁体 / 欧洲语言直接 OCR 失败。

**涉及文件**：`rag-qa-backend/.../service/OcrService.java`

**修复方向**：把 OCR 语言做成 KB 级别字段，每个知识库可指定。

**工作量**：1d ｜ **价值**：中

---

### P1-06 · Chunk size 500 字偏小

**问题**：500 字对合同、法律文档、产品手册偏小，跨章节"概念解释"会被切断。中文 embedding 通常推荐 800-1500 字。

**涉及文件**：
- `rag-qa-backend/src/main/resources/application.properties`
- `rag-qa-backend/.../service/TextSplitter.java`

**修复方向**：默认 `CHUNK_SIZE=800`，加配置开关；同步调 `CHUNK_OVERLAP` 到 100。

**工作量**：0.5h ｜ **价值**：中

---

## 5. P2 — 锦上添花（8 项）

### P2-01 · 无 RAG 评估体系

**问题**：没有 context precision/recall、answer faithfulness、answer relevance 指标。每次改 prompt / embedding 模型都无法量化实际收益。

**修复方向**：加 `/api/eval/run` 端点，跑 golden Q&A 集，输出 hit-rate、MRR、faithfulness（LLM-as-judge）。配套 `docs/eval/` 维护数据集。

**工作量**：3d ｜ **价值**：长期重要

---

### P2-02 · 无 RAG 调用链路追踪

**问题**：无 trace_id，无法关联"用户提问 → 检索了哪 N 个 chunk → 走了哪个 prompt 模板"。

**修复方向**：用 Micrometer Tracing + Zipkin；把 `chat_history.rag_metadata` 扩展为完整 trace。

**工作量**：2d ｜ **价值**：长期重要

---

### P2-03 · Chunk ID 拼接方案对边界情况脆弱

**问题**：
```java
String docId = documentId.toString() + "_" + chunkIndex;  // "uuid_0"
```
UUID 是 hex+hyphen 不含下划线，前缀匹配安全；但**如果将来改用 snowflake ID（含下划线），立刻出问题**。

**涉及文件**：
- `rag-qa-backend/.../service/ChromaService.java:206`
- `rag-qa-backend/.../service/Bm25SearchService.java:244`

**修复方向**：用 `DocumentChunk.id` 实体作为 Chroma ID，别用字符串拼接。

**工作量**：1h ｜ **价值**：中（防御性）

---

### P2-04 · BM25 索引只存内存

**问题**：
```java
private final Map<UUID, Map<String, List<BM25Doc>>> bm25Index = new ConcurrentHashMap<>();
```
服务重启 → 索引空 → hybrid 检索退化为纯向量检索。

**涉及文件**：`rag-qa-backend/.../service/Bm25SearchService.java`

**修复方向**：要么持久化（MySQL 或 LevelDB），要么启动时从 Chroma 重建；禁用时显式提示。

**工作量**：1d ｜ **价值**：中

---

### P2-05 · 文档删除未清理 BM25

**问题**：KB 删除时级联删了 MySQL，但 BM25 内存索引可能残留（取决于调用顺序）。

**涉及文件**：
- `rag-qa-backend/.../service/DocumentService.java`
- `rag-qa-backend/.../service/Bm25SearchService.java`

**修复方向**：用 `@TransactionalEventListener(AFTER_COMMIT)` 确保 BM25 在 DB 事务提交后才清理。

**工作量**：2h ｜ **价值**：中

---

### P2-06 · Embedding 模型不能按 KB 配置

**问题**：`OLLAMA_EMBEDDING_MODEL` 是全局属性，所有 KB 都用同一个 `qwen3-embedding:4b`。

**修复方向**：`knowledge_base` 表加 `embedding_model VARCHAR(64)` 字段；`EmbeddingService` 接受模型名参数。

**工作量**：1d ｜ **价值**：中

---

### P2-07 · 前端变量命名混乱

**问题**：有的文件 `kbStore`，有的直接 `chat`，命名风格不统一——之前 P0-03（chatStore 不存在）就是这个引起的。

**涉及文件**：`rag-qa-frontend/src/components/`、`src/views/`、`src/stores/`

**修复方向**：统一为 `xxxStore` 或裸名；ESLint 加 `unicorn/prevent-abbreviations` 规则强制。

**工作量**：0.5d ｜ **价值**：维护性

---

### P2-08 · Prompt 模板硬编码

**问题**：`RagService.buildPrompt()` 把模板字符串写死在 Java 里，改 prompt 要重新部署。

**涉及文件**：`rag-qa-backend/.../service/RagService.java:300-319`

**修复方向**：模板移到 `resources/prompts/rag-default.st`，`@Value("classpath:...")` 注入；热更新只需换文件。

**工作量**：2h ｜ **价值**：中

---

## 6. 实施顺序建议（按 ROI 排序）

| 序号 | ID | 标题 | 工作量 | 累计价值 | 推荐批次 |
|------|-----|------|--------|----------|----------|
| 1 | P0-01 | 来源引用透出前端 | 1d | 极高 | 批次 A |
| 2 | P0-02 | 多轮对话上下文 | 1d | 极高 | 批次 A |
| 3 | P1-02 | 接入 rerank | 2h | 高 | 批次 A |
| 4 | P1-01 | 向量归一化 | 2h | 高 | 批次 A |
| 5 | P1-06 | chunk size 调优 | 0.5h | 中 | 批次 B |
| 6 | P1-04 | 上传去重 | 0.5d | 中 | 批次 B |
| 7 | P0-03 | CORS 配置 | 0.5h | 中（潜在） | 批次 B |
| 8 | P0-04 | SSE 端点核查 | 0.5h | 中 | 批次 B |
| 9 | P1-03 | hybrid 默认值 | 0.5h | 中 | 批次 B |
| 10 | P1-05 | OCR 多语言 | 1d | 中 | 批次 C |
| 11 | P2-08 | Prompt 模板外置 | 2h | 中 | 批次 C |
| 12 | P2-05 | BM25 清理时序 | 2h | 中 | 批次 C |
| 13 | P2-03 | Chunk ID 改造 | 1h | 中（防御） | 批次 C |
| 14 | P2-04 | BM25 持久化 | 1d | 中 | 批次 D |
| 15 | P2-06 | embedding 按 KB | 1d | 中 | 批次 D |
| 16 | P2-07 | 命名规范 | 0.5d | 维护性 | 批次 D |
| 17 | P2-02 | 链路追踪 | 2d | 长期重要 | 批次 E |
| 18 | P2-01 | 评估体系 | 3d | 长期重要 | 批次 E |

**推荐先做批次 A**：来源引用 + 多轮对话 + rerank + 向量归一化 — 大约一周工作量，是 RAG 体验的"质变"组合。

---

## 7. 状态追踪表

> 每完成一项请在此处更新：`[ ]` → `[x]`，并写上 PR 号或日期。

| ID | 状态 | 完成时间 | PR / 备注 |
|----|------|----------|-----------|
| P0-01 | [ ] | | |
| P0-02 | [ ] | | |
| P0-03 | [ ] | | |
| P0-04 | [ ] | | |
| P1-01 | [ ] | | |
| P1-02 | [ ] | | |
| P1-03 | [ ] | | |
| P1-04 | [ ] | | |
| P1-05 | [ ] | | |
| P1-06 | [ ] | | |
| P2-01 | [ ] | | |
| P2-02 | [ ] | | |
| P2-03 | [ ] | | |
| P2-04 | [ ] | | |
| P2-05 | [ ] | | |
| P2-06 | [ ] | | |
| P2-07 | [ ] | | |
| P2-08 | [ ] | | |

---

## 8. 关联文档

- `docs/项目结构分析06-28-v3-chat-history-redesign.md` — 最近一次 chat_history V3 表结构重构
- `docs/test-cases/feature-*.md` — 已有的功能测试用例（覆盖 BM25 / cascade / RAG perf / 安全 / SSE 等）
- `docs/plans/2026-03-15-rag-qa-design.md` — 早期架构设计
- `feature-list.json` — 项目级功能清单（注意：本清单不与 feature-list.json 重复，本清单聚焦**质量改进**，非新功能）

---

## 9. 备注

- 本清单每条都已**精确定位到文件**（避免后续考古成本）
- 每条都有**可执行的修复方向**（非纯抱怨型）
- 工作量估算是基于中等熟悉度的开发者；实际可能 ±30%
- 实施时建议一次提交一个 P0/P1 项到独立 PR，方便回滚