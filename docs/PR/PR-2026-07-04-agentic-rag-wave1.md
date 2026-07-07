# PR 改动说明 — 2026-07-04 Agentic RAG 升级 Wave 1（PoC + 设计 + 增量文档）

> **日期**：2026-07-04
> **分支**：`dev-agentic`（从 `dev-0629` 的 `f20577c` 分出，**未 push**）
> **规模**：6 个 commit / 7 个文件（1 新增 PoC 测试 + Design/SRS 增量 + feature-list + .env.example + task-progress + RELEASE_NOTES）
> **前置依赖**：基于 `dev-0629`（含 Query Rewrite 模块）；可独立合并
> **关联历史**：`docs/PR/PR-2026-06-30-async-security-retrieval-fix.md`
> **性质**：**增量文档 + PoC，不含业务代码实现**（F17-F22 已分解为 failing，留待 Worker cycles TDD 实现）

---

## 一、TL;DR

本次改动是 Agentic RAG 升级的 **Wave 1 增量**：完成技术地基 PoC 验证 + 落地 Design/SRS/feature-list 增量文档，为后续 LLM 自主编排工具（KB检索 + Web搜索 + 直答）替代固定 linear 流水线做铺垫。

**核心价值**：解决三个痛点——① 知识源单一（仅 Chroma 文档库）② 知识库无文档时直接短路"问不出" ③ 复杂问题单次检索不足。

**未实现**：F17-F22 的代码实现留待 Worker cycles（TDD），本 PR 只交付设计 + PoC + 需求分解。

---

## 二、背景与动机

### 2.1 现状（Linear RAG）

`RagService.chat()` 是固定流水线：`rewrite → retrieve(Chroma 召回 + rerank) → augment → generate`，单次检索，知识源仅上传文档。

### 2.2 三个痛点

1. **知识源单一**：只有 Chroma 文档库，无 Web 搜索 / 外部数据源。
2. **无文档即短路**：知识库无 COMPLETED 文档时，`RagService.chat()` 直接返回"该知识库暂无文档"，不调 LLM。
3. **单次检索不足**：复杂/多约束问题（如"产品A和竞品X价格对比"）一次检索无法覆盖多源信息。

### 2.3 升级目标

LLM 作为 controller 自主编排工具，支持多跳检索 + 反思，知识源扩展到 Web。

---

## 三、改动清单（6 个 commit）

| Commit | 类型 | 内容 |
|---|---|---|
| `a9d7b43` | test(poc) | 新增 `MiniMaxToolCallingPoCTest`——MiniMax-M3 + Spring AI 1.1.3 tool-calling 4 用例验证 |
| `4b87b75` | docs(design) | Design §11 Agentic RAG 设计（架构/组件/数据模型/配置/SSE/测试/任务分解/风险/PoC结论） |
| `8660ca5` | docs(srs) | SRS §3.5 追加 FR-012~014（EARS + Given/When/Then） |
| `cebf6e0` | feat(feature-list) | feature-list.json 追加 F17-F22（wave 1, failing）+ waves[] 数组 |
| `9db2c36` | chore(config) | required_configs 加 TAVILY_API_KEY + .env.example 加 Tavily 配置块 |
| `87a3462` | chore(progress) | task-progress.md Session 5 + RELEASE_NOTES [Unreleased] |

**验证**：`validate_features.py` → `VALID — 22 features (16 passing, 6 failing) | Waves: 1`

---

## 四、PoC 验证结论（技术地基）

`MiniMaxToolCallingPoCTest`（`@EnabledIfSystemProperty(named="rag.poc")` 守护，手动 `-Drag.poc=true` 运行，不污染 CI/质量门槛）4 用例全过：

| 用例 | 验证点 | 结果 |
|---|---|---|
| 单 tool | MiniMax-M3 发起 function call + 框架自动执行 | ✅ 调 `getServerTime` 1 次 |
| KB 风格 | 基于 tool 结果回答 | ✅ 调 `searchProduct`，回答含价格 |
| 多 tool | **多轮 tool-calling（P2 agent loop 地基）** | ✅ 连续调 2 次 `searchProduct` |
| 无需 tool | LLM 判断不调 | ✅ 0 次调用，直接自答 |

**结论**：MiniMax-M3 + Spring AI 1.1.3 tool-calling 完全可用——发起调用、自动执行、多轮编排、智能跳过都正常。

### 两个影响设计的关键发现

1. **`ToolCallingChatOptions` 在 1.1.3 无 `maxIterations` API**（只有 `internalToolExecutionEnabled`）→ 防死循环改用 **CompletableFuture + 总超时（30s）兜底**（同 QueryRewriteService 模式），不靠框架迭代上限。
2. **`MiniMaxChatModel` 依赖 `ToolCallingManager` + `RetryTemplate`** → 必须 `@EnableAutoConfiguration` 全量加载（主应用 `@SpringBootApplication` 天然满足）。

---

## 五、设计要点（Design §11）

### 5.1 整体架构

`AgenticRagService` 与 `RagService` **并存**，`rag.mode` 开关灰度切换：
- `linear`（默认）：走现有 `RagService`，行为完全不变
- `agentic`：走 `AgenticRagService`，LLM 自主编排工具

`KnowledgeBaseSearchTool` 注入 `RagService` 复用现有 retrieve 链路（召回 + rerank + fallback + OOM 防护），零重复实现。

### 5.2 首批 3 个工具

| 工具 | 作用 | 数据源 |
|---|---|---|
| KnowledgeBaseSearchTool | 检索内部文档 | 复用 RagService.retrieve（Chroma） |
| WebSearchTool | 搜索互联网 | Tavily（免费 1000/月，无 key 不注册） |
| DirectAnswerTool | 闲聊/常识直答 | 跳过检索，省 token |

### 5.3 降级矩阵（决定可靠性）

| 触发条件 | 降级动作 |
|---|---|
| LLM 不支持 tool-calling | 回退 linear RAG |
| 单次 tool 超时 | 跳过该 tool，用已累积 context 继续 |
| 总超时（30s） | cancel + 降级 linear |
| Web 搜索无 key/失败 | 仅 KB/直答 tool 可用 |
| `rag.mode=linear` | 直接走 RagService |

### 5.4 数据模型

新增 `agent_trace` 表（Flyway 迁移），每轮 tool 调用一行，避免 `rag_metadata` JSON 膨胀；`chat_history.rag_metadata` 加 `agent_mode`/`agent_rounds`/`degraded`。

### 5.5 SSE 集成

复用现有 SSE 通道，新增 `agent_step` 事件（round/tool/status/durationMs），前端 P3 做 UI。

---

## 六、需求与 feature 分解

### 6.1 SRS 新增需求（FR-012~014）

| FR | 标题 | 优先级 |
|---|---|---|
| FR-012 | Agentic 问答模式（LLM 自主编排 + 降级） | Must |
| FR-013 | 工具抽象与多源检索（KB/Web/直答） | Must |
| FR-014 | Agent 可观测与 trace 落库 | Should |

### 6.2 feature-list 新增（F17-F22, wave 1, failing）

| Feature | 内容 | srs_trace | 依赖 |
|---|---|---|---|
| F17 | Tool 抽象 + KnowledgeBaseSearchTool | FR-013 | F4 |
| F18 | WebSearchTool(Tavily) + DirectAnswerTool | FR-013 | F17 |
| F19 | AgenticRagService + agent loop + 降级 | FR-012 | F17, F18 |
| F20 | rag.mode 路由 + ChatService 集成 | FR-012 | F19 |
| F21 | agent_trace 表 + trace 落库 + SSE agent_step | FR-014 | F19 |
| F22 | Eval A/B + ST 测试用例 | FR-012~014 | F21 |

---

## 七、影响分析

| Change | Type | Affected | Impact | Action |
|---|---|---|---|---|
| FR-012 | New | F5, F6 | **Soft** | 加 rag.mode 路由，linear 不变，re-verify |
| FR-013 | New | F4 | **Soft** | KnowledgeBaseSearchTool 复用 retrieve，re-verify |
| FR-014 | New | F14 | **Soft** | agent_trace 关联 chat_history，re-verify |

**无 Hard impact**——只新增 agentic 路径 + 复用现有链路，不改现有 feature 契约。现有 16 features 保持 passing，不 reset。

---

## 八、关键决策

| 决策点 | 选择 | 理由 |
|---|---|---|
| Agent 编排框架 | Spring AI 原生 tool-calling | 少依赖，与现有集成自然，MVP 够用 |
| Web 搜索源 | Tavily | 专为 LLM 优化，免费 1000/月 |
| 直答工具 | 含 DirectAnswerTool | 闲聊/常识跳检索，省 token |
| trace 存储 | 新增 agent_trace 表 | 避免 rag_metadata JSON 膨胀 |
| rag.mode 默认 | linear 灰度 | 回归风险低，可灰度验证 |
| 首期范围 | P1+P2 一起做 | 一步到位多跳+反思 |

---

## 九、后续 Worker cycles

F17-F22 已分解为 failing，按 `long-task-guide.md` 的 TDD 流程实现：
```
Orient（读 SRS FR + Design §11）→ Red（写测试）→ Green（实现）
→ Coverage（jacoco 90/80）→ Mutation（pitest 80）→ Persist（commit）
```
建议每个 feature 一个 Red→Green→Persist 周期，分 session 推进。

---

## 十、风险与应对

| 风险 | 应对 |
|---|---|
| Agent loop 死循环/超时 | 总超时 30s + cancel + 强制降级 |
| Web search API 成本 | Tavily 免费额度；缓存查询结果 |
| Token 消耗增大 | tool 返回摘要截断；总超时限制 |
| linear 行为回归 | rag.mode=linear 默认，灰度切换 |
| MiniMax tool-calling 稳定性 | PoC 已验证（4 用例通过） |

---

## 十一、Long-Task 流程说明

本次增量按 `long-task-increment` skill 流程手动执行（plugin 已启用但 skills 未注册为可调用 skill，手动按 SKILL.md 8 步走完）：Orient → 需求收集（EARS）→ 影响分析（用户批准）→ Design 修订 → SRS 更新 → feature 分解 → 辅助文件 → Finalize。`increment-request.json` 信号已消费删除。
