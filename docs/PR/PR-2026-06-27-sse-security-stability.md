# PR 改动说明 — 2026-06-27 安全加固 / 稳定性修复 / SSE 实时推送

> **日期**：2026-06-27
> **分支**：`dev`（**所有改动尚未提交**，仅在工作区）
> **规模**：33 个文件修改 / 11 个文件新增 / 1849 行新增 / 279 行删除
> **关联 RELEASE_NOTES**：Feature #26 / #27 / #28 / #29 / #30 / #31 / #32
> **关联测试用例**：`docs/test-cases/feature-26 ~ feature-32`

---

## 一、TL;DR

本次增量集中在**两条主线 + 一条暗线**：

1. **🔴 必修的主线一：把系统从"演示状态"提升到"准生产状态"**
   - 解决了 3 个生产必爆的 🔴 高危风险（见 `项目结构分析06-27.md` 第 7.1 节）
   - 新增 7 项 P0 修复（FIX-001 ~ FIX-010，FIX-017），涵盖 BM25 并发、级联清理、N+1、JWT、路径遍历、卡死恢复、Chroma 409

2. **🚀 体验主线二：文档状态实时推送（SSE）**
   - Feature #31：从 2 秒轮询升级为 EventSource + 指数退避 + 降级轮询
   - 前端 `alert()` 全数替换为 Toast 组件（解决 alert 阻塞主线程导致 UI 卡死）

3. **🛠 暗线：测试与文档同步**
   - 测试从 27 个 → 44 个（+63%）
   - 6 份特性测试用例文档新增
   - 设计文档（design.md）和 ST 计划同步更新 FIX-001 ~ FIX-017

---

## 二、变更主题分类

| # | 主题 | 涉及文件数 | 性质 |
|---|------|-----------|------|
| 1 | 异步执行器 + 调度器基础设施 | 3 | 🆕 新增/迁移 |
| 2 | BM25 并发安全 + 删除接口 | 2 | 🔧 重构 + 🆕 新方法 |
| 3 | 级联清理（KB 删除 / 文档删除） | 2 | 🐛 修复 |
| 4 | RAG 性能优化（N+1 + OOM 防护） | 2 | ⚡ 性能 |
| 5 | EmbeddingService 超时防护 | 1 | 🛡 稳定性 |
| 6 | Chroma v2 409 修复 | 1 | 🐛 P0 功能阻塞 |
| 7 | 路径遍历安全漏洞修复 | 1 | 🔒 安全 |
| 8 | JWT 弱密钥修复 | 3 | 🔒 安全 |
| 9 | 文档状态实时推送（SSE 事件总线） | 6 | 🆕 新功能 |
| 10 | 前端 Toast + EventSource composable | 5 | ✨ UX 升级 |
| 11 | DTO Bean Validation | 5 | 🛡 健壮性 |
| 12 | 测试加固（新增 + Mock 补齐） | 9 | 🧪 测试 |
| 13 | 一键启动脚本（backend + 全栈） | 2 | 🛠 工具链 |
| 14 | 文档同步（design + ST plan + release notes） | 4 | 📚 文档 |

---

## 三、详细改动清单

### 主题 1：异步执行器 + 调度器基础设施

| 文件 | 改动 |
|------|------|
| `rag-qa-backend/src/main/java/com/ragqa/config/AsyncConfig.java` | **新增**：`documentProcessExecutor` Bean，core=4/max=8/queue=100，CallerRunsPolicy，60s 优雅停机 |
| `rag-qa-backend/src/main/java/com/ragqa/RagQaApplication.java` | `@EnableAsync` 迁移至 `AsyncConfig`；新增 `@EnableScheduling` |
| `rag-qa-backend/src/main/java/com/ragqa/service/DocumentProcessRecoveryScheduler.java` | **新增**：每 5 分钟扫描卡死的 PROCESSING 文档（> 30 分钟），自动置为 FAILED |

**Why**：替代 Spring 默认的 `SimpleAsyncTaskExecutor`（每次新建线程、无队列、无背压 → 线程耗尽），并解决"服务重启后永远卡在 PROCESSING 状态"孤儿任务问题。

---

### 主题 2：BM25 并发安全 + 删除接口

| 文件 | 改动 |
|------|------|
| `rag-qa-backend/src/main/java/com/ragqa/service/Bm25SearchService.java` | 加 `ReentrantReadWriteLock`（读锁包 search、写锁包 addDocument/removeByDocumentId/clear）；`idfCache` 改 `ConcurrentHashMap`；**新增** `removeByDocumentId(String documentId)` 方法（按前缀匹配清理 chunk + 倒排索引 + 聚合统计） |
| `rag-qa-backend/src/test/java/com/ragqa/service/Bm25SearchServiceTest.java` | **新增**：8 个单元测试（含 1 个并发读写压测：8 reader + 2 writer × 100 iterations） |

**Why**：修复 BM25 索引在并发读写下的 `ConcurrentModificationException` / 死循环；为级联清理提供删除接口。

---

### 主题 3：级联清理（KB 删除 / 文档删除）

| 文件 | 改动 |
|------|------|
| `rag-qa-backend/src/main/java/com/ragqa/service/KnowledgeBaseService.java` | `delete()` 重写：先查所有文档 → 逐个清理 Chroma + BM25 + 本地文件 → 最后 `repository.delete(kb)` 触发 MySQL FK CASCADE |
| `rag-qa-backend/src/main/java/com/ragqa/service/DocumentService.java` | `deleteDocument()` 新增 `bm25Service.removeByDocumentId()` 调用（try-catch 容错） |
| `rag-qa-backend/src/test/java/com/ragqa/service/KnowledgeBaseServiceTest.java` | 新增 4 个测试：级联清理、空 KB、Chroma 失败容忍、不存在 KB |
| `rag-qa-backend/src/test/java/com/ragqa/service/DocumentServiceTest.java` | 新增 2 个测试：BM25 清理、BM25 失败容忍 |

**Why**：修复"删除知识库不清理 Chroma / BM25 / 本地文件"的孤儿数据问题（🔴 高危风险）。

---

### 主题 4：RAG 性能优化（N+1 + OOM 防护）

| 文件 | 改动 |
|------|------|
| `rag-qa-backend/src/main/java/com/ragqa/service/RagService.java` | `retrieve()`：把"N+1 次 `findByKnowledgeBaseId`"改为"1 次 + Set 过滤"；`fallbackRetrieve()`：新增 `retrieval.fallback.max-chunks` 上限（默认 5000）防单请求 OOM |

**Why**：TopK=20 场景下，检索时多打 19 次 DB；4096 维 × 5000 chunk ≈ 80MB/请求，无上限会吃光 JVM 堆。

---

### 主题 5：EmbeddingService 超时防护

| 文件 | 改动 |
|------|------|
| `rag-qa-backend/src/main/java/com/ragqa/service/EmbeddingService.java` | `RestTemplate` 显式配置连接超时 5s / 读取超时 30s；新增 `ResourceAccessException` 分支 |

**Why**：默认 RestTemplate 无超时，Ollama 挂起时永久阻塞 HTTP 线程 → Tomcat 线程池雪崩。

---

### 主题 6：Chroma v2 409 修复（P0 功能阻塞）

| 文件 | 改动 |
|------|------|
| `rag-qa-backend/src/main/java/com/ragqa/service/ChromaService.java` | `getOrCreateCollectionId()` 新增：先 GET 列表按 `name` 命中复用 id，不存在才 POST 创建；`cachedCollectionId`（volatile）+ `resolveLocks`（tenant 级锁）保证并发首调安全；新增 `getFromChroma()` GET helper；新增 `invalidateCollectionIdCache()` |

**实测影响**（RELEASE_NOTES Feature #32）：每文档 71 切片场景下，向量化从「成功 0 / 失败 71」→「成功 71 / 失败 0」，日志 0 个 409。

**Why**：Chroma v2 强制同名 collection 唯一，原实现无脑 POST 创建导致全部 add 失败，整个检索链路瘫痪。

---

### 主题 7：路径遍历安全漏洞修复

| 文件 | 改动 |
|------|------|
| `rag-qa-backend/src/main/java/com/ragqa/service/DocumentService.java` | `uploadDocument()` 加双层防御：① 拒绝包含 `/` 或 `\` 的文件名；② `Paths.get(fileName).getFileName()` + `normalize()` + `startsWith(uploadRoot)` 边界校验 |
| `rag-qa-backend/src/test/java/com/ragqa/service/DocumentServiceTest.java` | 新增 3 个攻击向量测试：相对路径、绝对路径、Windows 反斜杠 |

**Why**：攻击者可上传 `../../etc/passwd.pdf` 文件名逃出 `uploads/` 目录（CVE 类别：Path Traversal，🔴 高危）。

---

### 主题 8：JWT 弱密钥修复

| 文件 | 改动 |
|------|------|
| `rag-qa-backend/src/main/java/com/ragqa/service/JwtService.java` | 去掉默认值 `mySecretKeyForJWTTokenGeneration...`；`@PostConstruct` 启动时校验密钥 Base64 解码后 ≥ 32 字节，否则 `IllegalStateException` 拒启动 |
| `rag-qa-backend/src/main/resources/application.properties` | `jwt.secret=mySecretKey...` → `jwt.secret=${JWT_SECRET:}`（必须环境变量注入） |
| `rag-qa-backend/start.sh` | Step 4 新增：自动 `openssl rand -base64 32` 生成 JWT_SECRET 并 export；已有则校验长度 |

**Why**：硬编码密钥 = 公开密钥，攻击者可伪造任意用户 token（🔴 高危）。

---

### 主题 9：文档状态实时推送（SSE 事件总线）

| 文件 | 改动 |
|------|------|
| `rag-qa-backend/src/main/java/com/ragqa/event/DocumentStatusEvent.java` | **新增**：不可变 `record`，包含 documentId/kbId/status/progress/errorMessage/updatedAt |
| `rag-qa-backend/src/main/java/com/ragqa/event/DocumentStatusEventService.java` | **新增**：基于 Reactor `Sinks.Many.multicast().onBackpressureBuffer(100)` 的事件总线（多订阅者、线程安全、懒创建 sink） |
| `rag-qa-backend/src/main/java/com/ragqa/controller/DocumentController.java` | **新增** `GET /api/knowledge-bases/{kbId}/documents/stream` SSE 端点（`Flux<ServerSentEvent<String>>`，事件名 `doc-status`）；支持 query param token（兼容 EventSource 无法设置 header） |
| `rag-qa-backend/src/main/java/com/ragqa/service/DocumentProcessService.java` | 5 个状态变更点（PARSING / CHUNKING / EMBEDDING / 循环进度 / COMPLETED / FAILED）改为 `saveAndEmit(document)`：先 save 再 emit（emit 失败不影响主流程） |
| `rag-qa-backend/src/test/java/com/ragqa/controller/DocumentControllerStreamTest.java` | **新增**：3 个 MockMvc 测试（未认证拦截、已认证通过、eventService 被调用） |
| `rag-qa-backend/src/test/java/com/ragqa/event/DocumentStatusEventServiceTest.java` | **新增**：7 个测试（单订阅、多订阅 fan-out、并发 emit、removeSink 等） |

**Why**：原 `setInterval(2s)` 轮询存在延迟高、流量浪费、状态错位三大问题；SSE < 100ms 实时推送且带宽友好。

---

### 主题 10：前端 Toast + EventSource composable

| 文件 | 改动 |
|------|------|
| `rag-qa-frontend/src/composables/useDocumentStream.js` | **新增**：EventSource 订阅 + 指数退避重连（1s/2s/4s/8s/15s/30s 上限）+ 失败降级轮询（3s）；资源清理 stop() |
| `rag-qa-frontend/src/composables/useToast.js` | **新增**：模块级单例 Toast 队列（success/error/warning/info），自动消失，可手动 dismiss |
| `rag-qa-frontend/src/components/common/ToastContainer.vue` | **新增**：右上角弹出容器，`<TransitionGroup>` 平滑动画，4 种类型 + 配色 |
| `rag-qa-frontend/src/App.vue` | 挂载 `<ToastContainer />` |
| `rag-qa-frontend/src/main.js` | 全局注册 `ToastContainer` 组件；`axios.defaults.baseURL = ''`（使用 Vite proxy） |
| `rag-qa-frontend/src/views/ChatView.vue` | 重构：`alert()` 全数替换为 Toast；`setInterval` 轮询替换为 SSE composable；切换 KB 重置 documents；上传后乐观插入；`onUnmounted` 清理资源；API_BASE 改 `/api`（Vite proxy 同源） |

**Why**：alert 阻塞主线程导致 UI 卡死；轮询延迟高、流量浪费；硬编码 `localhost:8080` 在生产环境失效。

---

### 主题 11：DTO Bean Validation

| 文件 | 改动 |
|------|------|
| `rag-qa-backend/src/main/java/com/ragqa/dto/ChatRequest.java` | `@NotBlank message`、`@NotNull knowledgeBaseId` |
| `rag-qa-backend/src/main/java/com/ragqa/dto/LoginRequest.java` | `@NotBlank` username / password |
| `rag-qa-backend/src/main/java/com/ragqa/dto/RegisterRequest.java` | `@NotBlank` + `@Size(min=6)` password + `@Email` email |
| `rag-qa-backend/src/main/java/com/ragqa/dto/AuthResponse.java` | 加 `@NoArgsConstructor` |
| `rag-qa-backend/src/main/java/com/ragqa/dto/ChatResponse.java` | **新增**：`{ sessionId, answer }`，替代原来的 `String` 返回，使前端可关联聊天历史 |
| `rag-qa-backend/src/main/java/com/ragqa/controller/AuthController.java` | `register/login` 加 `@Valid` |
| `rag-qa-backend/src/main/java/com/ragqa/controller/ChatController.java` | `chat/streamChat` 加 `@Valid`；`/api/chat` 返回类型 `String` → `ChatResponse` |
| `rag-qa-backend/src/main/java/com/ragqa/service/ChatService.java` | `chat()` 返回 `ChatResponse`，并把 user 问题 + assistant 回答两条记录持久化到 `chat_history`（共享 sessionId） |

**Why**：原实现空字段会一路传到 DB 才报 500；问答未落库导致"聊天历史"侧边栏为空。

---

### 主题 12：测试加固

| 文件 | 改动 |
|------|------|
| `rag-qa-backend/pom.xml` | 加 `spring-security-test` 依赖 |
| `rag-qa-backend/src/test/java/com/ragqa/controller/AuthControllerTest.java` | 加 `@Import(SecurityConfig, JwtAuthenticationFilter)` + Mock JwtService / UserRepository |
| `rag-qa-backend/src/test/java/com/ragqa/controller/ChatControllerTest.java` | 同上 + 用 `.with(authentication(...))` 显式传 SecurityContext；`/api/chat` 测试改为断言 `ChatResponse` 结构 |
| `rag-qa-backend/src/test/java/com/ragqa/controller/KnowledgeBaseControllerTest.java` | 同上 + 4 个测试方法全部加 `.with(authentication(...))` |
| `rag-qa-backend/src/test/java/com/ragqa/service/ChatServiceTest.java` | 适配 `ChatResponse` 返回；断言 `verify(historyRepo, times(2)).save(...)` |
| `rag-qa-backend/src/test/java/com/ragqa/service/UserServiceTest.java` | 新增 `AuthenticationManager` + `UserDetails` 依赖注入（55 行） |

**统计**：测试数 27 → 44（+63%）。

---

### 主题 13：一键启动脚本

| 文件 | 改动 |
|------|------|
| `start.sh`（项目根） | **新增**：全栈一键启动（前置检查 → 后端 → 前端 → 健康检查 → 优雅退出） |
| `rag-qa-backend/start.sh` | **新增**：后端一键启动（前置检查 → 加载 .env → 启 Chroma → 生成 JWT Secret → mvn package → java -jar） |

**Why**：解决"git clone 后跑不起来"的环境配置门槛。

---

### 主题 14：文档同步

| 文件 | 改动 |
|------|------|
| `RELEASE_NOTES.md` | 新增 Feature #26/#27/#28/#29/#30/#31/#32 章节 + Changed/Fixed/Security/Tests 段；新增「已发布版本」分隔 |
| `docs/plans/2026-03-15-rag-qa-design.md` | 611 行大幅修订（+546 / -65）：同步架构图、新增 SSE 事件总线、AsyncConfig、Chroma 409 修复、JWT 安全策略等 |
| `docs/plans/2026-03-21-st-plan.md` | RTM 表更新已通过的 FR/NFR 状态；新增「2.1 增量修复 RTM（2026-06-27）」17 行 FIX 表 |
| `docs/test-cases/feature-26-bm25-concurrency.md` | **新增** |
| `docs/test-cases/feature-27-cascade-cleanup.md` | **新增** |
| `docs/test-cases/feature-28-rag-performance.md` | **新增** |
| `docs/test-cases/feature-29-security.md` | **新增** |
| `docs/test-cases/feature-30-stuck-recovery.md` | **新增** |
| `docs/test-cases/feature-31-doc-status-sse.md` | **新增** |
| `docs/test-cases/feature-32-chroma-409-fix.md` | **新增** |

---

## 四、潜在风险与遗留

| 风险 | 说明 | 建议 |
|------|------|------|
| **`@EnableScheduling` 默认单线程** | 所有 `@Scheduled` 共享一个单线程调度器；当前只有 1 个定时任务但未来扩展需注意 | 后续可加 `SchedulingConfigurer` 显式配置 `ThreadPoolTaskScheduler` |
| **`Sinks.Many` 在弱网下可能丢事件** | 当前 `BUFFER_SIZE=100` + multicast 不保证所有事件到达；前端有降级轮询兜底，但实时性会被轮询拖到 3s | 高频状态变更时考虑改 `unicast` + ACK |
| **`JwtAuthenticationFilter` 与 `JwtService` 存在循环依赖**（旧问题） | 当前用 `@Lazy` 解决 | 后续可把 JWT 解析逻辑下沉到独立组件 |
| **`ChatHistory` 落库失败只 warn 不阻断** | 是有意为之（避免辅助功能拖垮主流程），但需监控告警 | 接入 Micrometer 统计落库失败率 |
| **SSE 端点 query param token 鉴权有被 referer 日志泄漏风险** | URL 会被 Nginx / 浏览器历史记录 | 生产环境建议改用短期 ticket 中转模式（详见后续设计） |
| **`bm25Service.removeByDocumentId` 在 100 万级 chunk 下 O(n) 扫描** | `documents.keySet()` 遍历 + `tokenize` 调用 | 未来考虑引入 LRU Cache 或 RocksDB |

---

## 五、提交建议

由于本次改动横跨多个特性，建议**拆分为以下 7 个提交**（保持 commit history 清晰、便于回滚与 code review）：

```
1. feat(config): 引入 AsyncConfig 线程池 + @EnableScheduling
   └── AsyncConfig.java, RagQaApplication.java, DocumentProcessRecoveryScheduler.java, DocumentRepository.java

2. fix(bm25): BM25 索引线程安全改造 + removeByDocumentId 新增
   └── Bm25SearchService.java, Bm25SearchServiceTest.java

3. fix(cleanup): 知识库/文档删除级联清理 Chroma + BM25 + 本地文件
   └── KnowledgeBaseService.java, DocumentService.java, *Test.java

4. perf(rag): 消除 RagService N+1 查询 + Fallback OOM 防护
   └── RagService.java

5. feat(security): 路径遍历 + JWT 弱密钥修复
   └── DocumentService.java, JwtService.java, application.properties, start.sh

6. fix(chroma): Chroma v2 collection 409 修复
   └── ChromaService.java

7. feat(sse): 文档状态实时推送 (SSE) + 前端 Toast/EventSource
   └── event/*, DocumentController.java, DocumentProcessService.java,
       ChatService.java, dto/ChatResponse.java, dto/* (validation),
       frontend composables/*, ToastContainer.vue, ChatView.vue,
       App.vue, main.js
```

---

## 六、验证清单（提交前自检）

- [ ] `cd rag-qa-backend && mvn clean test` — 全部 44 个测试通过
- [ ] `mvn spring-boot:run` 启动后 `curl http://localhost:8080/actuator/health` 返回 `{"status":"UP"}`
- [ ] `.env` 缺失 JWT_SECRET → 启动应抛 `IllegalStateException` 拒绝启动
- [ ] 上传恶意文件名（如 `../../etc/passwd.pdf`）→ 立即返回 400 "非法文件名"
- [ ] 上传 PDF → 前端 Toast "上传成功"；文档状态实时变化（UPLOADING → PARSING → ... → COMPLETED）
- [ ] 关闭 Chroma 服务再重启 → 上传 PDF 不再 71/71 失败
- [ ] 删除知识库 → Chroma collection size 减小、本地 uploads/{kbId} 目录被清空
- [ ] 删除文档 → BM25 检索结果同步减少（不留孤儿）
- [ ] 强制 kill -9 后端 → 重启后 DocumentProcessRecoveryScheduler 在 60s 后清理卡死文档
- [ ] 前端 Vite dev server 启动后浏览器访问 http://localhost:5173 → 无 console error

---

> 文档生成于 2026-06-27 22:11 CST，基于 `git status`（33 个 modified + 11 个 untracked）+ 完整 diff 阅读 + RELEASE_NOTES / 设计文档交叉对照。