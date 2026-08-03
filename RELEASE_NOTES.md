# Release Notes — rag-qa

## [Unreleased]

### Added
- F17（2026-08-03）：Tool 抽象 + KnowledgeBaseSearchTool 验收闭环
  - 新增 `docs/features/2026-08-03-f17-tool-abstraction.md` 详细设计文档
  - `KnowledgeBaseSearchToolTest` 扩展至 11 例，覆盖 kbId 上下文注入、空结果、来源去重、Spring AI `@Tool` 注册、trace start/done 分支
  - Feature-ST 文档更新为 ISO/IEC/IEEE 29119-3 格式，12 个用例；自动化用例 11/11 通过，3 个真实外部依赖场景标记 PENDING-MANUAL
  - Quality Gates：F17 行覆盖 100%、分支覆盖 92.3%、变异分数 90%
- F22 (2026-07-07): EvalService A/B 对比 + Wave 1 ST 测试用例
  - `EvalService.abCompare(question, kbId, history, historyWindow)` → AbCompareResult 双侧产物对比报告
  - `ModeOutcome` record（answer / latencyMs / retrievedChunkCount / sourceCount / agentRounds / degraded / error）
  - `POST /api/admin/eval/ab` REST 入口（同步返回 AbCompareResult）
  - `EvalServiceAbCompareTest` 3 例：双成功 / agentic 降级 / linear 异常吞噬
  - 6 篇 Wave 1 ST 用例（feature-17~22），覆盖 FR-012~014 验收标准
  - 单边失败隔离：linear 抛了不阻塞 agentic；`ab-` 前缀 chatId 与真实对话区分
  - 测试：111/111 全过
- F23 (2026-07-07): 前端对话模式切换 UI（per-conversation）
  - 后端 `GET /api/config` 暴露全局 `rag.mode` / `defaultHistoryWindow`，避免前端硬编码
  - 前端 `RagModeToggle.vue` 两-pill 切换组件（lucide ListOrdered + Sparkles），agentic 选中态用品牌渐变
  - 前端 `stores/chat.js`：保留 `ragMode` 映射 + `currentConversation` / `effectiveRagMode`（conv.rag_mode ?? global）/ `updateRagMode` action（乐观 + 回滚 + Toast）
  - 前端 `stores/config.js` 新 store，单次加载 + fallback linear
  - `ChatView` 顶部挂 toggle，流式问答中 disabled（防止半路切换错配）
  - 交互：点击立即乐观更新 → PATCH `/api/conversations/{id}/rag-mode` → 失败回滚
  - 视觉效果：linear = 白色卡片 / agentic = 紫渐变高亮
  - linear 路径行为零回归（默认 rag.mode=linear，前端 fallback 一致）
- F21 (2026-07-07): agent_trace 表 + trace 落库 + SSE agent_step
  - Flyway V8：`agent_trace` 表（chat_id / round / tool_name / tool_args / result_summary / duration_ms / status）+ 索引 `(chat_id, round)`
  - `AgentTraceCollector`：每轮 tool 调用 start/done 两条落库，异常吞掉不拖垮主链路，summary 截断 500 字
  - `TraceContext` ThreadLocal 持有 chatId + 自增 round，参考 `KnowledgeBaseContext` 模式
  - 三个 Tool（kb_search / web_search / direct_answer）注入 collector，调用前后各记一条
  - `RagService.ChatResult` 扩展 `agentMode` / `agentRounds` / `degraded`（4 参构造委托，linear 路径零变化）
  - `AgenticRagService` 注入 collector，签名加 `chatId`；超时/异常降级用 `markDegraded()` 标 `degraded=true`
  - `ChatService` 一次性生成 `chatId`（agent trace + DB 对齐，可 join），流式路径查 trace → 拼 SSE `event: agent_step` 事件，在 chunk 流之前发
  - `chat_history.rag_metadata` 增 `agent_mode` / `agent_rounds` / `degraded` 三字段
  - linear 路径行为零回归（默认 `rag.mode=linear`）；新增 6 单测，全量 106/106 通过
- Increment Wave 1 (2026-07-03): Agentic RAG 升级（P1+P2）
  - 新增 FR-012 Agentic 问答模式 / FR-013 工具抽象与多源检索 / FR-014 Agent 可观测与 trace 落库
  - 新增 F17-F22：Tool 抽象+KB工具 / Web工具(Tavily)+直答 / AgenticRagService+降级 / rag.mode 路由 / agent_trace+SSE / Eval A/B+ST
  - Design §11 + SRS §3.5 增量文档；PoC 验证 MiniMax-M3 tool-calling 可行（4 用例全过）
  - `rag.mode` 默认 linear 灰度，agentic 显式启用；agent 总超时 30s 降级 linear
- Feature #1: Spring Boot项目初始化 - 应用可以启动在8080端口
- Spring AI 1.0.0-M6依赖配置
- Maven settings.xml解决阿里云mirror问题
- Spring Boot Actuator健康检查端点
- Feature #16: 用户注册登录功能
  - Spring Security + JWT认证
  - 后端API: /api/auth/register, /api/auth/login
  - 前端登录页面和路由守卫
- Feature #26 (2026-06-27): P0 安全与稳定性修复
  - `Bm25SearchService` 线程安全：ReentrantReadWriteLock + ConcurrentHashMap
  - `Bm25SearchService.removeByDocumentId()` 新增（按 documentId 删除 chunk）
  - `DocumentProcessRecoveryScheduler` 新增：定时清理卡死的 PROCESSING 文档
  - 线程池配置：`AsyncConfig.documentProcessExecutor`（core=4, max=8, queue=100）
  - `DocumentRepository.findStuckDocuments()` 新增（JPQL 自定义查询）
- Feature #32 (2026-06-27): Chroma Collection 409 修复（P0 功能阻塞）
  - `ChromaService.getOrCreateCollectionId()` 改造：先 GET 列表按 `name` 命中复用 id，不存在才 POST 创建
  - 新增 `cachedCollectionId`（volatile）+ `resolveLocks`（tenant 级锁）保证并发首调安全
  - 新增 `getFromChroma(endpoint)` 通用 GET helper（之前只有 POST helper）
  - 新增 `invalidateCollectionIdCache()` 供外部（KB 删除/重建）手动失效缓存
  - 影响：每文档 71 切片场景下，向量化从 `成功: 0, 失败: 71` → `成功: 71, 失败: 0`，Chroma 实际入库 71 条向量，日志 0 个 409
  - 测试：54/54 后端单元测试通过 + E2E `cloud.pdf` 完整跑通（COMPLETED）

- Feature #31 (2026-06-27): 文档状态实时同步（SSE 推送）
  - **后端**：
    - `DocumentStatusEvent` record 新增（不可变事件对象）
    - `DocumentStatusEventService` 新增：基于 Reactor `Sinks.Many` 的事件总线（multicast + buffer(100)）
    - `DocumentController.streamDocumentStatus()` 新增：`GET /api/knowledge-bases/{kbId}/documents/stream`，返回 `Flux<ServerSentEvent<String>>`，自定义事件名 `doc-status`
    - `DocumentProcessService` 在 5 个状态变更点（PARSING/CHUNKING/EMBEDDING/循环/COMPLETED/FAILED）调用 `eventService.emit()`
    - SSE 端点鉴权：Spring Security + 控制器内 query param token 双重保障（兼容 EventSource 无法设置 header）
    - `GlobalExceptionHandler` 新增 `SecurityException → 401` 处理
  - **前端**：
    - `useToast` composable 新增：全局非阻塞 Toast 通知队列（替代 `alert()`）
    - `ToastContainer.vue` 组件新增：右上角弹出，`<TransitionGroup>` 平滑动画，4 种类型（success/error/warning/info）
    - `useDocumentStream` composable 新增：EventSource + 指数退避重连（1s/2s/4s/8s/15s/30s，最多 6 次）+ 失败降级轮询（3s）
    - `ChatView.vue` 重构：
      - 删除 `alert()` 调用（0 个剩余）
      - 删除 `setInterval` 轮询（替换为 SSE composable）
      - 上传后乐观插入：立即把后端返回的 Document push 到列表（无需等待 SSE）
      - 新增 `onUnmounted` 清理 EventSource 和 setInterval
      - API_BASE 改用 Vite proxy 同源路径 `/api`

### Changed
- MiniMax模型配置修复: abab5.5-chat → MiniMax-M2.5
- Spring AI 升级 1.0.x → 1.1.x（参考 BOM）
- `KnowledgeBaseService.delete()` 增加级联清理：Chroma 向量 + BM25 索引 + 本地文件
- `DocumentService.deleteDocument()` 同步清理 BM25 索引
- `DocumentService.uploadDocument()` 增加路径遍历双层防御
- `RagService.retrieve()` 消除 N+1 数据库查询
- `RagService.fallbackRetrieve()` 增加 `fallbackMaxChunks` 上限防 OOM
- `EmbeddingService` 增加 5s/30s 超时配置
- `JwtService` 启动时强制校验 JWT_SECRET 长度 ≥ 32 字节
- `application.properties` 的 `jwt.secret` 改为 `${JWT_SECRET:}`（必须环境变量注入）
- `RagQaApplication` 加 `@EnableScheduling`

### Fixed
- MiniMax API调用失败问题（模型名称错误）
- 用户注册登录功能
- 删除知识库时 Chroma/BM25/本地文件残留（2026-06-27）
- 删除文档时 BM25 索引残留（2026-06-27）
- BM25 多线程并发导致 ConcurrentModificationException / 死循环（2026-06-27）
- RAG 检索 N+1 数据库查询性能问题（2026-06-27）
- EmbeddingService 无超时导致 Tomcat 线程池雪崩风险（2026-06-27）
- 文件上传路径遍历安全漏洞（2026-06-27）
- 文档处理异步任务因服务重启永远卡在 PROCESSING（2026-06-27）
- JWT Secret 硬编码导致生产部署密钥泄露风险（2026-06-27）
- Chroma v2 API 强制同名 collection 唯一导致向量化 71/71 失败（2026-06-27）

### Security
- 🔴 修复路径遍历漏洞（CVE 类别：Path Traversal）
- 🔴 修复 JWT 弱密钥配置（CVE 类别：Hard-coded Credentials）

### Tests
- 测试覆盖从 27 个 → 44 个
- 新增 `Bm25SearchServiceTest` 完整单元测试（含并发安全测试）
- 新增路径遍历 3 个攻击向量测试
- 新增 KnowledgeBaseService 级联清理 4 个测试

---

## [已发布版本]

_暂无_

---

_Format: [Keep a Changelog](https://keepachangelog.com/) — Updated after every git commit._
