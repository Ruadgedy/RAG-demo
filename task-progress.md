# Task Progress — rag-qa

## Current State
Progress: 21/23 · Last: F21 agent_trace + SSE agent_step（2026-08-03） · Next: F23 前端对话模式切换 UI

---

## Session Log

### Session 0 — 初始化 (2026-03-15)

**已完成：**
- ✅ SRS需求文档
- ✅ UCD样式指南
- ✅ 设计文档
- ✅ 项目脚手架

### Session 1 — Feature #1 完成 (2026-03-15)

**完成内容：**
- ✅ Spring Boot项目初始化
- ✅ 修复Spring AI依赖版本

### Session 2 — 完成Feature #2 #3 #4 #5 #6 #7 #8 #9 #10 (2026-03-15)

**后端完成：**
- ✅ Feature #2: 知识库创建API
- ✅ Feature #3: 知识库列表API  
- ✅ Feature #4: RAG检索引擎
- ✅ Feature #5: 单轮问答API
- ✅ Feature #6: 流式问答API

**前端完成：**
- ✅ Feature #7: Vue3前端项目初始化
- ✅ Feature #8: 前端布局组件
- ✅ Feature #9: 知识库列表组件
- ✅ Feature #10: 聊天界面组件

**总计：10/15 features passed**

### Session 3 — ST系统测试 (2026-03-21)

**执行内容：**
- ✅ 创建ST测试计划 (docs/plans/2026-03-21-st-plan.md)
- ✅ 回归测试: Maven单元测试 5/5 通过
- ✅ 集成测试: API测试 - 知识库创建成功
- ❌ 发现Critical缺陷: 后端API全部挂起(Hang)

**Critical缺陷详情：**
- 问题: 所有后端API请求(HTTP)挂起无响应
- 原因: MiniMax API集成问题导致Spring Boot应用阻塞
- 影响范围: Features 4,5,6,10,14,15 标记为failing

**转换到Work阶段进行修复**

### Session 4 — Feature #16 用户注册登录 (2026-03-22)

**完成内容：**
- ✅ 添加Spring Security和JWT依赖
- ✅ 更新User模型实现UserDetails接口
- ✅ 创建JwtService生成/验证JWT令牌
- ✅ 创建JwtAuthenticationFilter处理请求认证
- ✅ 创建SecurityConfig配置安全策略
- ✅ 实现/api/auth/register和/api/auth/login接口
- ✅ 前端添加LoginView.vue登录页面
- ✅ 添加路由守卫和Axios拦截器
- ✅ 修复MiniMax模型名称(abab5.5-chat → MiniMax-M2.5)

**测试结果：**
- 注册API: 返回JWT令牌 ✅
- 登录API: 返回JWT令牌 ✅
- 受保护API: 需要Authorization头 ✅
- 前端: 登录页自动跳转 ✅
- RAG问答: 正常工作 ✅

**总计：16/16 features passed**

### Session 5 — Increment Wave 1 (Agentic RAG) 设计+PoC (2026-07-03)

**Phase**: Increment
**Scope**: 升级到 Agentic RAG（P1+P2）—— Tool 抽象 + 多源检索 + agent loop + trace 落库

**完成内容**：
- ✅ PoC 验证：MiniMax-M3 + Spring AI 1.1.3 tool-calling 4 用例全过（`MiniMaxToolCallingPoCTest`，`@EnabledIfSystemProperty(named="rag.poc")` 守护）
- ✅ Design §11 增量：AgenticRagService + 3 工具（KB/Tavily Web/直答）+ agent_trace 表 + 降级矩阵 + PoC 结论
- ✅ SRS §3.5 增量：FR-012 Agentic 问答模式 / FR-013 工具抽象与多源检索 / FR-014 Agent 可观测与 trace 落库
- ✅ feature-list.json 增量：F17-F22（wave 1, failing）+ waves[] 数组 + required_configs TAVILY_API_KEY
- ✅ .env.example 加 Tavily 配置块

**关键决策**：Spring AI 原生 tool-calling（非 LangGraph4j）；maxIterations 无 API 靠 CompletableFuture 超时（30s）兜底；rag.mode 默认 linear 灰度；agent_trace 新表；Tavily Web 搜索

**Documents updated**: SRS, Design, feature-list.json, .env.example
**Next**: Worker cycles 实现 F17-F22（TDD，按 long-task-guide Red→Green→Coverage→Mutation→Persist）

### Session 6 — F21 agent_trace 落库 + SSE agent_step + degraded (2026-07-07)

**Phase**: Worker (TDD Red→Green→Persist)
**Scope**: Wave 1 内 F21 闭环（FR-014 Agent 可观测）

**完成内容**：
- ✅ Flyway V8：建 `agent_trace` 表（chat_id / round / tool_name / tool_args / result_summary / duration_ms / status）+ 双索引
- ✅ `AgentTrace` / `AgentTraceRepository` / `AgentTraceCollector` + `TraceContext` ThreadLocal
- ✅ 三个 Tool 埋点（start / done 两条 record / 调用），注入 collector
- ✅ `RagService.ChatResult` 扩展 `agentMode` / `agentRounds` / `degraded` 字段（4 参构造委托兼容 linear）
- ✅ `AgenticRagService` 注入 collector，签名加 `chatId`，`markDegraded()` 降级标记
- ✅ `ChatService` 一次性生成 `chatId`（agent + DB 对齐），流式路径推 SSE `agent_step` 事件
- ✅ `buildRagMetadataJson` 加 `agent_mode` / `agent_rounds` / `degraded` 三键
- ✅ `AgentTraceCollectorTest` 6 例 / `AgenticRagServiceTest` 5 例更新（含新断言）

**测试结果**：
- F21 新增 + 改动：24/24 单测通过
- 全量回归：mvn test → `Tests run: 106, Failures: 0, Errors: 0, Skipped: 4`（4 个跳过是 PoC @EnabledIfSystemProperty 守护）
- linear 路径行为不变（4 参 ChatResult 委托构造 hard-code mode=linear）

**关键决策**：chatId 唯一性贯穿 ChatService；trace 落库失败 catch + log warn 不拖垮主链路；degraded=true 表"agentic 触发但实际跑 linear"

**Documents updated**: docs/PR/PR-2026-07-07-f21-agent-trace.md
**Next**: F22 Eval A/B + ST 测试用例 / docs/test-cases/feature-21.md

### Session 7 — F23 前端对话模式切换 UI (2026-07-07)

**Phase**: Worker (UI 落地 + 后端最小支撑)
**Scope**: Wave 1 内 F23 闭环

**完成内容**：
- ✅ 后端：`GET /api/config`（读 `rag.mode` / `rag.history.turns`）+ `ConfigDto` + `ConfigControllerTest`（2 例）
- ✅ 前端：`api/config.js` + `stores/config.js`（全局默认 Pinia store）
- ✅ 前端：`api/conversation.js` 加 `updateRagMode(id, mode)`（PATCH 端点 F20 已就绪）
- ✅ 前端 `stores/chat.js`：保留 `ragMode` 映射 / `currentConversation` / `effectiveRagMode` / `updateRagMode` action（乐观 + 回滚 + Toast）
- ✅ 前端 `RagModeToggle.vue`：两-pill 切换组件（lucide ListOrdered + Sparkles，agentic 选中态品牌渐变）
- ✅ 前端 `views/ChatView.vue`：onMounted 并行拉 config；顶部挂 toggle，stream / !convId 时 disabled
- ✅ ST 用例：docs/test-cases/feature-23-frontend-mode-toggle.md（6 例 devtools）
- ✅ npm run build 通过；mvn test 108/108 通过

**关键决策**：effectiveRagMode = conv.rag_mode ?? globalRagMode（前后端统一公式）；乐观更新失败回滚 + Toast；流式锁定避免半路切换错配；configStore `loaded` flag 单次加载 + fallback linear 兜底

**linear 路径零回归**：默认 `rag.mode=linear`，新对话 fallback 到 linear

**Documents updated**: docs/PR/PR-2026-07-07-f23-frontend-mode-toggle.md、docs/test-cases/feature-23-frontend-mode-toggle.md
**Next**: F22 Eval A/B linear vs agentic + docs/test-cases/feature-17~21.md 一组 ST 用例

### Session 8 — F22 Eval A/B + Wave 1 ST 测试用例 (2026-07-07)

**Phase**: Worker（评估能力 + 系统测试收尾）
**Scope**: Wave 1 闭环（FR-012/013/014 评估与回归）

**完成内容**：
- ✅ EvalService.abCompare(question, kbId, history, historyWindow) → AbCompareResult（双侧产物 + 单边失败隔离）
- ✅ ModeOutcome + AbCompareResult 嵌套 record（清晰的报告字段）
- ✅ EvalController `POST /api/admin/eval/ab` REST 入口
- ✅ EvalServiceAbCompareTest 3 例：双成功 / agentic 降级 / linear 异常吞噬
- ✅ 6 篇 Wave 1 ST 用例（feature-17/18/19/20/21/22）：覆盖 FR-012~014 验收标准
- ✅ mvn test 111/111 全过（+3 F22）

**关键决策**：A/B 串行执行（避免 agentic 线程池+ThreadLocal 竞争）；ab- 前缀 chatId 与真实对话区分；单边 try/catch 不阻塞对侧

**Wave 1 整体落地**：
| Feature | 实现 commit | ST 用例 |
|---|---|---|
| F17~F22 | 59c86e4 / 886f7d1 / 3d8ee12 / 1d4b106 / eccf4db / 本 PR | feature-17~22 ✨ |
| F23 前端 UI | cd36912 | feature-23 ✨ |

**Documents updated**: docs/PR/PR-2026-07-07-f22-eval-ab-st-cases.md、6 篇 docs/test-cases/feature-17~22.md
**Next**: (Wave 1 收尾；feature-list.json F17~F22 状态翻 passing 由 ST 执行确认 + feature-list.json 更新流程触发)

### Feature #17: Tool 抽象 + KnowledgeBaseSearchTool — PASS
- Completed: 2026-08-03
- Service dependencies: No runtime service startup required for automated scope; unit tests mock RagService boundary. Real LLM/DB/Chroma scenarios remain PENDING-MANUAL in Feature-ST.
- TDD: green ✓ (11/11 F17 tests; Wave 1 regression group 36/36)
- Quality Gates: 100% line, 92.3% branch, 90% mutation
- Feature-ST: 12 cases documented; 9 automated Mock cases PASS; 3 external Real cases PENDING-MANUAL
- Inline Check: PASS (P2: 4/4 contract groups, T2: 10/10 inventory rows, D3: OK, U1: N/A)
- Git: 6f00cf3 feat: Tool 抽象 + KnowledgeBaseSearchTool (#17)
#### Risks
- ⚠ [Mutant/Minor] `KnowledgeBaseSearchTool.java:64` — trace summary source condition has one surviving mutant; mutation score remains 90%, follow up with exact summary matcher.
- ⚠ [Dependency/Major] 3 个真实 LLM/数据库/Chroma 场景尚未执行；上线前需在完整服务环境补做。

### Feature #18: WebSearchTool（Tavily）+ DirectAnswerTool — PASS
- Completed: 2026-08-03
- Service dependencies: Tavily Web API（外部 HTTP）。自动化测试通过 Mockito spy + 真实 RestClient.Builder 完成，不调用外部网络。
- TDD: green ✓ (18/18 F18 tests; Wave 1 regression 36/36)
- Quality Gates: 98.8% line, 89.3% branch, 84% mutation
- Feature-ST: 10 cases documented; 8 自动化 Mock cases PASS；2 真实 Tavily HTTP 场景 PENDING-MANUAL
- Inline Check: PASS (P2: 5/5 contract methods, T2: 16/16 inventory rows covered, D3: OK, U1: N/A)
- Git: 08e066a feat: WebSearchTool + DirectAnswerTool (#18)
#### Risks
- ⚠ [Dependency/Major] `TAVILY_API_KEY` 当前为空；真实 Tavily 401/200 场景 PENDING-MANUAL，需有 key 后在隔离环境补测。
- ⚠ [Mutant/Minor] WebSearchTool 存在 2 个 timeout setter 等价变异 + 2 个 stream 条件变异；Mutation 84% 已达门槛。

### Feature #19: AgenticRagService + agent loop + 降级 — PASS
- Completed: 2026-08-03
- Service dependencies: 真实 LLM tool-calling + DB + Chroma。自动化测试通过 Mockito mock 边界。
- TDD: green ✓ (5/5 F19 tests; Wave 1 regression 16/16)
- Feature-ST: 6 cases documented; 5 自动化 Mock cases PASS；1 真实 LLM Agent 场景 PENDING-MANUAL
- Inline Check: PASS (P2: 3/3 public methods, T2: 5/5 inventory rows, D3: OK, U1: N/A)
- Git: 349af27 feat: AgenticRagService + agent loop + 降级 (#19)
#### Risks
- ⚠ [Dependency/Major] 真实 LLM Agent 端到端 PENDING-MANUAL，需完整服务环境补测。
- ⚠ [Performance/Accepted] 30s 超时上限对流式首字延迟有影响，已通过降级 + null 返回隔离。

### Feature #20: rag.mode 路由 + per-conversation mode — PASS
- Completed: 2026-08-03
- Service dependencies: 无新增；依赖 ChatService + ConversationController。
- TDD: PASS (ChatService 路由 + Controller PATCH)
- Feature-ST: 5 cases documented; 4 自动化 Mock PASS；1 真实端到端 PENDING-MANUAL
- Inline Check: PASS
- Git: 6e6d399 feat: rag.mode 路由 + per-conversation mode (#20)
#### Risks
- ⚠ [Dependency/Major] 真实后端 + 数据库 PATCH 端到端 PENDING-MANUAL。

### Feature #21: agent_trace + SSE agent_step — PASS
- Completed: 2026-08-03
- Service dependencies: 数据库表 + SSE 客户端。
- TDD: green ✓ (AgentTraceCollectorTest 6/6)
- Feature-ST: 7 cases documented; 5 自动化 Mock PASS；2 真实端到端 PENDING-MANUAL
- Inline Check: PASS
- Git: 85c567e feat: agent_trace + SSE agent_step (#21)
#### Risks
- ⚠ [Dependency/Major] 真实落库端到端 + SSE 推送 PENDING-MANUAL。
