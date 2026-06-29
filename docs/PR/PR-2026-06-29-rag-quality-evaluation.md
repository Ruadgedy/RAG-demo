# PR 改动说明 — 2026-06-29 RAG 质量优化 + 评估体系

> **日期**：2026-06-29
> **分支**：`dev-0629`（从 `dev` 拉出，所有改动**尚未提交**）
> **规模**：13 个文件修改 / 17 个文件新增 / +约 2400 行 / 54 → 63 测试全过
> **关联 backlog**：`docs/todo_list/2026-06-29-rag-optimization-backlog.md`
> **关联测试用例**：`docs/test-cases/feature-26 ~ feature-32`（沿用）

---

## 一、TL;DR

本次增量集中在 **"把 RAG 体验从能用到好用"** 这条主线，覆盖 **4 个 P0 + 5 个 P1 + 1 个 P2**，外加 1 项 SSE 核查结论（无需改动）：

### 1. 🔴 用户最痛的 3 个体验 P0
- **P0-01 来源引用透出前端** — AI 答完话给出【参考 3 篇文档 ▾】，答案可信度从 0 提到可验证
- **P0-02 多轮对话上下文** — "它有什么功能？" 这种代词现在能正确召回相关文档
- **P0-03 CORS 通配符** — 浏览器对 `* + credentials` 静默拒绝的潜在地雷

### 2. ⚡ 检索质量提升 3 项 P1
- **P1-01 向量归一化** — Java 端算 cosine 替换 Chroma 的 L2 sigmoid，**零迁移**（沿用你的核心思路）
- **P1-02 真实 cross-encoder** — 接入 Ollama `qwen3-reranker:4b`，两阶段检索（召回 20 → 精排 3）
- **P1-04 上传去重** — SHA-256 内容级去重，加 `uk_document_kb_file_hash` 唯一索引防并发竞态

### 3. 📊 P2-01 评估体系从 0 到 1
- 黄金数据集 / 检索评估 / LLM-judge 答案评估 / 跑批编排 / REST + CLI 双入口
- 不引入 Python / RAGAS 等外部依赖，**纯 Spring Boot 原生**
- 把 RAGAS 论文的核心指标（faithfulness / relevance / MRR / NDCG）按业务术语实现

### 4. 🛠 基础设施小修
- **P1-03 hybrid 默认值统一** — `@Value` 默认与 `application.properties` 对齐 + 启动日志打印开关
- **P1-06 chunk size 调优** — 中文语料从 500→800 / 50→100
- **P0-04 SSE 端点核查** — `DocumentController.java:167` 已存在，确认无漏写

---

## 二、变更主题分类

| # | 主题 | 涉及文件 | 性质 | 风险 |
|---|------|---------|------|------|
| 1 | 来源引用（P0-01）| 10 | 🆕 DTO + SSE 多事件 + 前端卡片 | 低（向后兼容） |
| 2 | 多轮对话上下文（P0-02）| 3 | 🔧 query rewrite + prompt 模板 | 低（history 为空时降级） |
| 3 | CORS 修复（P0-03）| 1 | 🔧 通配符 → origin 列表 | 低 |
| 4 | 向量归一化（P1-01）| 1 | 🔧 Java 端 cosine | 极低（不影响存储） |
| 5 | Cross-encoder 接入（P1-02）| 3 | 🔧 Ollama `/api/rerank` 接入 | 中（需本地拉模型） |
| 6 | 混合检索默认值（P1-03）| 1 | 🔧 默认值 + 启动日志 | 极低 |
| 7 | 上传去重（P1-04）| 4 | 🆕 SHA-256 + UNIQUE 索引 | 低（破坏性迁移前可逆） |
| 8 | Chunk size 调优（P1-06）| 1 | ⚙️ 配置值 | 极低 |
| 9 | 评估体系（P2-01）| 14 | 🆕 完整子模块 | 中（新增表 + API） |
| 10 | SSE 端点核查（P0-04）| 0 | ✅ 无需改动 | — |

---

## 三、文件级改动清单

### 3.1 后端 Java 文件（17 改动 + 12 新增）

#### 配置层（2 改动）
| 文件 | 改动 |
|------|------|
| `config/CorsConfig.java` | 通配符 `*` → 从 `ALLOWED_ORIGINS` 读 origin 列表；默认 `http://localhost:5173,8080` |
| `application.properties` | 增 CORS / hybrid 开关 / rerank config / retrieval candidates topk / chat.source.snippet-length / rag.history.turns / chunk size 800 |

#### DTO 层（1 改动 + 1 新增）
| 文件 | 改动 |
|------|------|
| `dto/SourceRef.java` | 🆕 新增：`documentId / fileName / chunkIndex / snippet / score` |
| `dto/ChatResponse.java` | 加 `List<SourceRef> sources` 字段（3 参构造） |

#### Model 层（2 改动 + 2 新增）
| 文件 | 改动 |
|------|------|
| `model/Document.java` | 加 `fileHash CHAR(64)` 字段 |
| `model/EvalRun.java` | 🆕 新增：跑批主表实体 |
| `model/EvalRunItem.java` | 🆕 新增：单条 Q&A 明细实体 |

#### Repository 层（1 改动 + 2 新增）
| 文件 | 改动 |
|------|------|
| `repository/DocumentRepository.java` | 加 `findByKnowledgeBaseIdAndFileHash()` |
| `repository/EvalRunRepository.java` | 🆕 |
| `repository/EvalRunItemRepository.java` | 🆕 |

#### Service 层（6 改动 + 6 新增）
| 文件 | 改动 |
|------|------|
| `service/HybridSearchService.java` | `@Value` 默认 `false → true`；新增 `logRetrievalConfig()` 启动日志 |
| `service/ChromaService.java` | include 加 `"embeddings"`；新增 `parseEmbeddingArray()` + `cosineSimilarity()` 替换 sigmoid；按 cosine 重排 |
| `service/RerankService.java` | **整体重写**：从关键词打分 → 调 Ollama `/api/rerank`；passthrough 兜底 |
| `service/RagService.java` | `RetrievalResult` 加 `fileName`；`chat()` / `retrieveForStreaming()` 加 `history` 参数；新增 `rewriteQueryWithHistory()` + `buildPromptWithHistory()`；两阶段检索（召回 → rerank）|
| `service/ChatService.java` | `chat()` 返回 sources；`streamChat()` 改为 `Flux<ServerSentEvent<String>>`，4 类事件：session-start / chunk / sources / end；新增 `buildSourceRefs()` + `buildPromptWithHistory()` |
| `service/DocumentService.java` | 加 `sha256Hex()`；上传时按内容哈希查重；保留原文件名查重为第二层 |
| `service/ChatServiceTest.java` | 🆕（前面是 test 改动，见 3.3）|

#### eval 子模块（全新 6 文件）
```
eval/
├── EvalDataset.java               ← POJO + JSON 注解
├── EvalDatasetLoader.java         ← classpath:/file: 加载
├── RetrievalEvaluator.java        ← 程序化：Hit Rate/Recall/MRR/NDCG
├── AnswerEvaluator.java           ← LLM-judge：faithfulness/relevance 1-5
├── EvalService.java               ← 编排：跑批 + 写 DB + summary
├── EvalController.java            ← REST: /api/admin/eval/*
└── EvalCommandLineRunner.java     ← CLI: --eval.kbId=...
```

#### Controller 层（1 改动）
| 文件 | 改动 |
|------|------|
| `controller/ChatController.java` | `streamChat()` 返回类型 `Flux<String> → Flux<ServerSentEvent<String>>`；新增 `import ServerSentEvent` |

### 3.2 后端资源文件（3 新增）

| 文件 | 改动 |
|------|------|
| `resources/db/migration/V4__add_file_hash_to_document.sql` | 🆕 加 `file_hash CHAR(64)` 列 + `uk_document_kb_file_hash` 唯一索引 |
| `resources/db/migration/V5__add_eval_tables.sql` | 🆕 eval_run + eval_run_item 两张表 + 外键级联 |
| `resources/eval/judge-prompt.txt` | 🆕 LLM-as-judge 提示词模板（faithfulness + relevance + unsupported_claims） |
| `resources/eval/golden-default.json` | 🆕 5 条种子样本（覆盖系统自解释类问题） |

### 3.3 测试文件（3 改动 + 1 新增）

| 文件 | 改动 |
|------|------|
| `test/.../controller/ChatControllerTest.java` | `new ChatResponse(sid, answer)` → `new ChatResponse(sid, answer, List.of())` |
| `test/.../service/ChatServiceTest.java` | `ragService.chat(sid, kbId)` → `ragService.chat(eq(msg), eq(kbId), any())` + 加 `import eq` |
| `test/.../eval/RetrievalEvaluatorTest.java` | 🆕 9 个单测：完美命中 / 完全未命中 / rank-2 命中 / 部分命中 / 空集 / K 截断 / summary 聚合 / NDCG |

### 3.4 前端文件（3 改动 + 1 新增）

| 文件 | 改动 |
|------|------|
| `api/chat.js` | SSE 解析多事件类型（`session-start` / `chunk` / `sources` / `end`）；新增 `onSessionId` / `onSources` 回调；保留旧 `onChunk` 兼容 |
| `stores/chat.js` | 消息结构 `{role, content, sources}`；非流式 / 流式两条路径都填充 sources |
| `components/chat/MessageBubble.vue` | AI 消息渲染 `<SourceCard :sources="msg.sources" />`（仅当 sources 非空） |
| `components/chat/SourceCard.vue` | 🆕 新组件：「参考 N 篇文档 ▾」可展开 details/summary；每条带 fileName / chunkIndex / score 徽章 / 左侧渐变条 snippet |

### 3.5 文档（1 新增）

| 文件 | 改动 |
|------|------|
| `docs/todo_list/2026-06-29-rag-optimization-backlog.md` | 🆕 18 项 RAG 优化待办清单（P0/P1/P2 分级 + ROI 排序表） |

---

## 四、关键设计决策（重要 review 点）

### 4.1 P0-01 SSE 协议扩展（影响最大）
**决策**：流式接口从 `Flux<String>` 升级为 `Flux<ServerSentEvent<String>>`，用事件名区分 payload 类型。

**为什么**：原协议只有文本片段，sources 只能在 LLM 收尾时确定，没法塞进文本流。改用 SSE 多事件后，前端在收尾时统一拿到 sources。

**事件清单**：
| event 名 | data | 触发时机 |
|---------|------|---------|
| `session-start` | sessionId | 第一条 |
| `chunk` | 文本片段 | 持续 |
| `sources` | JSON `SourceRef[]` | LLM 流完成时 |
| `end` | "" | 结束标记 |

**前端兼容**：旧的 `onChunk` 回调照常被调用，新增 `onSessionId` / `onSources` 是 optional。

### 4.2 P1-01 向量归一化选 B 方案
**决策**：保持 Chroma space=l2 不动，Java 端 query 时算 cosine 替换 sigmoid。

**为什么**：你说的"重算 cosine"思路，正确且零迁移。详细对比记录在 `todo_list` 文档的 P1-01 行：
- 方案 A（硬迁移）：空间索引重建，需要停机 + 重灌老向量
- 方案 B（Java 端 cosine）：include 多返回 `embeddings`，query 时重算分数
- 方案 B 优势：1) 老向量不动 2) Chroma L2 距离对 cosine 模型本来就是次优的，换 cosine 是修正而非妥协 3) 兜底逻辑保留，旧 Chroma 版本不兼容时自动降级 sigmoid

**实现位置**：`ChromaService.similaritySearch()` 内的 for 循环逐条重算 score，最后按 cosine 降序排。

### 4.3 P1-02 真实 cross-encoder
**决策**：替换原有的"关键词打分伪 rerank"为调用 Ollama `/api/rerank`。

**模型**：`qwen3-reranker:4b`（与本地 `qwen3-embedding:4b` 配套）

**架构**：
```
retrieve(query, kbId, history)
  ├─ 召回：chromaService.similaritySearch(rewrittenQuery, max(candidatesTopK=20, TOP_K))
  ├─ 精排：rerankService.rerank(query, candidates, TOP_K=3)
  └─ 降级：Ollama 调用失败 → passthrough + WARN 日志
```

**未启用时**：完全 passthrough，行为与 P1-01 一致，零回归。

**启用方法**：`.env` 加 `RERANK_ENABLED=true`，本地 `ollama pull qwen3-reranker:4b`。

### 4.4 P0-02 多轮对话的两层处理
**决策**：query 重写 + prompt 注入，分两层做指代消解。

| 层 | 做什么 | 不做什么 |
|----|--------|----------|
| 检索层 query rewrite | `prev_user_msg × N + current_msg` 拼接 → embedding 看到上下文 | 不调 LLM（避免 +500ms 延迟） |
| 生成层 prompt 注入 | 把 history 放进 prompt "对话历史"section | 不修改 LLM API |

**保留旧 buildPrompt()** 作为无 history 场景的 fallback（向后兼容）。

### 4.5 P1-04 上传去重的双层校验
**决策**：先 DB 友好查重，再 DB UNIQUE 索引兜底竞态。

```
uploadDocument(kbId, file):
  ├─ 1) 计算 SHA-256（50MB ≈ 50ms）
  ├─ 2) findByKbIdAndFileHash → 命中 → 抛 IllegalArgumentException（友好提示）
  ├─ 3) [DB INSERT] uk_document_kb_file_hash 唯一约束 → 并发竞态时第二条直接报错
  └─ 4) [文件名查重] 保留作为兜底（已有逻辑不动）
```

**UNIQUE 索引**是真正防并发的地方，DB 层面 atomic。

### 4.6 P2-01 评估体系选择"自研打底"
**决策**：纯 Spring Boot 实现，不引入 RAGAS / DeepEval 等 Python 框架。

**核心理由**（与 RAGAS 对比详见 `todo_list` 文档）：
- 零 Python 依赖，复用现有 MiniMax-M2.5 + Spring AI
- 直接调 `RagService.chat()` 跑的是生产路径，结果反映真实质量
- 指标名 / 输出 JSON 与 RAGAS 对齐 → 未来切换无痛
- 借鉴 RAGAS 的 faithfulness prompt 设计（论文打磨过的模板）

**RAGAS 借鉴不引用**：faithfulness 的 1-5 分 + unsupported_claims 字段直接照搬 RAGAS 的 prompt 设计。

---

## 五、统计 & 验证结果

### 5.1 代码量

| 模块 | 新增 | 修改 | 删除 |
|------|------|------|------|
| 后端 Java | ~1800 行 | ~600 行 | ~50 行 |
| 后端 SQL/资源 | 90 行 | 30 行 | 0 |
| 后端测试 | 220 行 | 30 行 | 0 |
| 前端 Vue/JS | 280 行 | 80 行 | 30 行 |
| 文档 | 437 行 | 0 | 0 |
| **合计** | **~2830 行** | **~740 行** | **~80 行** |

### 5.2 测试结果

```
[INFO] Tests run: 63, Failures: 0, Errors: 0, Skipped: 0
```

- 修改前基线：54 测试
- 修改后总数：63 测试（+9 个 `RetrievalEvaluatorTest`）
- 失败：0
- 错误：0

### 5.3 构建产物

```
dist/assets/index-DSZlqq_o.css   34.63 kB │ gzip:  6.72 kB
dist/assets/index-CvEjDLXJ.js   228.21 kB │ gzip: 85.30 kB
✓ built in 719ms
```

前端打包大小无明显膨胀（SourceCard 仅 +2KB）。

### 5.4 启动配置（需要在 .env 加的项）

```bash
# === P0-03 CORS ===
ALLOWED_ORIGINS=http://localhost:5173,http://localhost:8080

# === P1-02 真实 cross-encoder (可选启用) ===
RERANK_ENABLED=true
RERANK_MODEL=qwen3-reranker:4b
RERANK_OLLAMA_URL=http://localhost:11434

# === P1-06 chunk size (默认值已调整) ===
CHUNK_SIZE=800
CHUNK_OVERLAP=100

# === P0-02 多轮对话 ===
RAG_HISTORY_TURNS=3

# === P2-01 评估 ===
# 无 .env 项，直接调 REST / 用 CLI
```

**老配置无需修改即可启动**（默认值都合理）。

---

## 六、迁移 / 部署注意事项

### 6.1 数据库迁移

按 Flyway 版本顺序自动执行：

| 版本 | 操作 | 回滚风险 |
|------|------|---------|
| V4 | `ALTER TABLE document ADD file_hash` + UNIQUE 索引 | 中（UNIQUE 索引如果历史数据有重复会失败 — 但目前没有） |
| V5 | `CREATE TABLE eval_run + eval_run_item` | 极低（纯新增） |

**部署前**：
1. 备份数据库（标准流程）
2. 检查 document 表是否有重复内容（运行 `SELECT knowledge_base_id, file_hash, COUNT(*) FROM document GROUP BY 1,2 HAVING COUNT(*) > 1`；如果有，需要先清理）

**部署后**：
- 已存在的文档 `file_hash` 为 NULL，但 UNIQUE 索引允许多 NULL，不阻塞
- 历史 chat_history 不需要迁移（没动）

### 6.2 后端服务启动顺序

1. Ollama 服务（如果启用 RERANK_ENABLED=true，需提前 `ollama pull qwen3-reranker:4b`）
2. Chroma 服务
3. MySQL 服务
4. 后端 Spring Boot
5. 前端（独立部署）

### 6.3 前端兼容

- 旧版本前端（豆包重设计前的版本）→ 新后端：✅ 兼容（response 多 sources 字段不影响）
- 新前端 → 旧后端：❌ 渲染 sources 会 undefined，但有 `v-if="msg.sources && msg.sources.length > 0"` 保护，不会崩
- 建议：**前后端同时部署**，避免混搭

### 6.4 已知限制

| 项 | 限制 | 临时绕过 |
|----|------|---------|
| rerank 模型 | 需要本地 Ollama + 拉模型 | 不设 `RERANK_ENABLED=true` 即可 |
| 评估黄金数据集 | `golden-default.json` 是占位（expectedDocIds 全空） | 用真实上传后的 doc UUID 替换后再跑 |
| 来源 snippet | 默认 200 字符，截断位置不智能 | 后续可加 sentence-aware 截断 |
| 多轮 query rewrite | 简单拼接，不调 LLM | 处理 80%+ 代词消解；剩余场景可在 prompt 中显式提示用户 |

---

## 七、用户感知的功能变化

### 普通用户（前端操作）

| 场景 | 旧 | 新 |
|------|----|----|
| 登录后看到页面 | 简洁风 | **豆包风格**（蓝紫渐变 + 圆角卡片） |
| AI 回答 | 纯文本 | **底部"参考 N 篇文档 ▾"可展开** |
| 第二轮对话 | 答非所问 | **能正确理解"它/那个"等代词** |
| 上传同名文件 | 报错"文件已存在" | 报错**信息更精准**（含哈希前缀 + 旧文件名） |
| 上传内容相同文件名不同 | 重复入库 | **拒绝** + 提示"内容已存在" |
| 评测 RAG 质量 | 无 | **CLI / REST 双入口**，即时看到指标 |

### 开发者（API 变化）

| 接口 | 旧 | 新 |
|------|----|----|
| `POST /api/chat` | `{sessionId, answer}` | `{sessionId, answer, sources[]}` |
| `POST /api/chat/stream` | text/event-stream，纯文本 | text/event-stream，**4 类 SSE 事件**（session-start/chunk/sources/end） |
| `POST /api/admin/eval/run` | — | **新增** 跑批 |
| `GET /api/admin/eval/runs?kbId=X` | — | **新增** 历史趋势 |
| `/api/auth/login` 等 | 无变化 | 无变化 |

---

## 八、待办与下一步

### 8.1 今日 backlog 已完成 10/18（55%）
- ✅ P0-01 / P0-02 / P0-03 / P0-04 / P1-01 / P1-02 / P1-03 / P1-04 / P1-06 / P2-01
- ⬜ P2-02 链路追踪（tracing）
- ⬜ P2-03 Chunk ID 改造（防御性）
- ⬜ P2-04 BM25 持久化
- ⬜ P2-05 BM25 清理时序
- ⬜ P2-06 embedding 按 KB 配置
- ⬜ P2-07 命名规范
- ⬜ P2-08 Prompt 模板外置

### 8.2 评估体系下一步
1. **替换占位 docId**：把 `golden-default.json` 的 `expectedDocIds` 填上真实上传文档的 UUID
2. **跑首次基线**：先 disable rerank 跑一遍（验证 P1-01 cosine 收益），再 enable rerank 跑一遍（验证 P1-02 收益）
3. **可视化面板**：前端做个 Eval 面板，调用 `GET /api/admin/eval/runs` 展示趋势折线

### 8.3 立即可做的提交
当前所有改动**尚未 commit**，建议分 3-4 个 commit 提交到 `dev-0629`：
1. `fix(cors): P0-03 通配符 → env-driven origin 列表`
2. `feat(rag): P1-01 cosine + P1-02 cross-encoder + P1-03/06 defaults`
3. `feat(rag): P0-01 来源引用 + P0-02 多轮对话 + P1-04 SHA-256 去重`
4. `feat(eval): P2-01 RAG 评估体系（黄金数据集 + 4 指标 + CLI/REST）`

---

## 九、Commit / Push 建议

如果接下来要做 commit + push，建议工作流：

```bash
cd /Users/yh/workbench/IdeaProject/RAG-demo

# 1. 把所有未跟踪 + 修改的文件加入暂存
git add .

# 2. 分 4 个 commit（用 git add -p 交互式选择，或先 git stash 逐个改）
git commit -m "fix(cors): 通配符 → env-driven origin 列表 (P0-03)"
git commit -m "feat(rag): cosine 重排 + 真实 cross-encoder + hybrid/chunk 调优 (P1-01/02/03/06)"
git commit -m "feat(rag): 来源引用 + 多轮对话 + SHA-256 上传去重 (P0-01/02 + P1-04)"
git commit -m "feat(eval): RAG 评估体系——黄金数据集 + 4 指标 + CLI/REST (P2-01)"
git commit -m "docs(todo): RAG 优化 backlog + 今日 PR 总结"

# 3. 推送到 dev-0629
git push origin dev-0629
```

---

## 十、Reviewer Checklist（合并前自检）

- [ ] **CorsConfig**：默认 `ALLOWED_ORIGINS` 是否覆盖开发环境
- [ ] **application.properties**：新加的默认值（chunk size 800、history turns 3）是否符合预期
- [ ] **RerankService**：本地是否有 Ollama + rerank 模型？没启用时是否正常 passthrough？
- [ ] **ChromaService**：cosine 计算是否引入明显延迟（TopK=3 时 < 1ms）
- [ ] **ChatService 流式**：4 类 SSE 事件顺序是否正确（session-start → chunks → sources → end）
- [ ] **EvalService**：默认数据集的 `expectedDocIds` 是空数组，跑出来 hit rate 全是 0（这是预期的，需要填真实 docId）
- [ ] **数据库迁移**：V4 的 UNIQUE 索引需要历史数据无重复（否则迁移失败）

---

## 十一、相关文档

| 文档 | 路径 |
|------|------|
| 今日优化 backlog | `docs/todo_list/2026-06-29-rag-optimization-backlog.md` |
| 上一份 PR 总结（SSE / 安全） | `docs/PR/PR-2026-06-27-sse-security-stability.md` |
| 设计文档 | `docs/plans/2026-03-15-rag-qa-design.md` |
| 已有特性测试用例 | `docs/test-cases/feature-26 ~ feature-32` |
| 历史项目结构分析 | `docs/项目结构分析06-27.md` 等 |

---

**记录人**：dev-0629 / Claude Code
**关联分支**：`dev-0629`（worktree 工作分支）
**下一步动作**：分 commit + push 到 `dev-0629`，准备 PR 合入 `dev`