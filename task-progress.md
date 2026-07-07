# Task Progress — rag-qa

## Current State
Progress: 16/22 · Last: F23 前端 mode toggle UI · Next: F22 Eval A/B + ST 测试用例（F21/F23 ST 用例已出）

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
