# AI 应用架构优化路线图（AI Architect Roadmap）

> **作者视角**：AI 应用架构师
> **日期**：2026-08-04
> **定位**：与 `2026-06-29-rag-optimization-backlog.md`（P0-P2 技术债/底层 bug）互补，本文档聚焦 AI 应用架构演进、可靠性、可观测性、可维护性、性能与产品体验。
> **来源**：基于 [docs/summary](../summary/application-architecture.md) 4+1 视图与已交付 23/23 feature 现状推导。
> **方法论**：从 8 个维度（架构分层、Agentic 设计、可观测性、可靠性、安全、可维护性、性能、产品）按 P0-P5 优先级梳理。

---

## 目录

1. [现状快照](#1-现状快照)
2. [优先级分级总览](#2-优先级分级总览)
3. [P0 — 生产就绪](#3-p0--生产就绪-4-项)
4. [P1 — 可靠性与可观测性](#4-p1--可靠性与可观测性-5-项)
5. [P2 — 可维护性与模块化](#5-p2--可维护性与模块化-5-项)
6. [P3 — 性能与可扩展性](#6-p3--性能与可扩展性-5-项)
7. [P4 — 产品体验](#7-p4--产品体验-4-项)
8. [P5 — 可演进性](#8-p5--可演进性-3-项)
9. [立即可做（高 ROI 3 项）](#9-立即可做高-roi-3-项)
10. [实施路线图](#10-实施路线图)
11. [状态追踪表](#11-状态追踪表)
12. [关联文档](#12-关联文档)

---

## 1. 现状快照

- **23/23 feature passing**，Wave 1 收尾，ST 阶段就绪
- **朴素 RAG**：HybridSearch（向量 + BM25 + Rerank）+ Fallback
- **Agentic RAG**：Spring AI tool-calling + 30s 超时降级 + agent_trace 落库
- **per-conversation 模式切换**：UI + 后端路由 + PATCH 端点
- **可观测**：agent_trace 表 + Spring Boot Actuator
- **架构文档**：[docs/summary](../summary/) 4+1 视图完整

主要痛点：

- 真实环境验证仍 PENDING-MANUAL
- 无 OpenTelemetry 链路追踪
- LLM 调用无熔断/重试
- 无统一限流
- 模块耦合较紧（AgenticRagService 直接依赖 ChatClient.Builder）

---

## 2. 优先级分级总览

| 优先级 | 类别 | 项数 | 目标 |
|---|---|---:|---|
| P0 | 生产就绪 | 4 | 防止生产事故、保障 SLA |
| P1 | 可靠性与可观测性 | 5 | 故障可发现、可恢复、可回溯 |
| P2 | 可维护性与模块化 | 5 | 降低变更成本、便于替换 |
| P3 | 性能与可扩展性 | 5 | 提升用户体验、降低单位成本 |
| P4 | 产品体验 | 4 | 增强用户粘性 |
| P5 | 可演进性 | 3 | 为下一阶段 AI 平台打基础 |
| **合计** | | **26** | |

---

## 3. P0 — 生产就绪（4 项）

### P0-01 · 真实环境全链路验证

- **背景**：F17/F18/F19/F21/F22/F23 都有 PENDING-MANUAL 真实环境场景
- **目标**：补做真实 LLM/Tavily/前端 UI 端到端测试，建立 staging 持续验证
- **范围**：
  - 搭建 staging 环境（独立 MySQL/Chroma/Ollama + 真实 API Key）
  - 引入 GitHub Actions 集成测试（仅在有 secret 时跑）
  - 把 PENDING-MANUAL 用例补成自动化（Playwright/Cypress + curl SSE）
- **验收**：
  - 0 个 PENDING-MANUAL 遗留
  - staging 每周跑通端到端冒烟
- **依赖**：SLA 资源预算、secret 注入流程
- **ROI**：极高（防止带病上线）
- **预估**：1-2 周

### P0-02 · 限流与防滥用

- **背景**：无任何限流；Agent 单次可多次 LLM 调用；无 token 配额
- **目标**：保护后端与外部 API 配额
- **范围**：
  - `Bucket4j` 限流：`/api/chat*` 每用户每分钟 10 次；`/api/admin/eval/ab` 每分钟 1 次
  - 文档上传：每用户每天 50 个文件
  - LLM Token 配额：每用户每天 100k tokens
  - Agent 嵌套深度硬限（虽然有 30s 超时）
- **验收**：
  - 超限返回 429
  - 限流计数器指标化（Prometheus）
- **依赖**：无
- **ROI**：高（防止恶意刷量 / 配额失控）
- **预估**：1 周

### P0-03 · LLM 调用韧性（重试 + 熔断 + 降级）

- **背景**：LLM 调用无重试、无熔断；MiniMax-M3 不可用时无降级模型
- **目标**：单点外部依赖故障不中断主链路
- **范围**：
  - `Resilience4j` 装饰 LLM：`Retry(3 次指数退避)` + `CircuitBreaker(5min 50% 失败熔断)` + `Bulkhead(N 并发)`
  - 多模型降级：M3 → M2.5 → 静态文本兜底
  - 缓存常见问题答案（`Caffeine` + `Spring Cache`）
- **验收**：
  - 模拟 LLM 503 持续 5 分钟 → 自动熔断
  - M3 故障 → M2.5 接管 1 分钟
- **依赖**：无
- **ROI**：高（防止上游故障雪崩）
- **预估**：1 周

### P0-04 · RAG 检索质量可量化

- **背景**：混合检索 + Rerank 缺端到端离线评估；Eval A/B 只对比 linear vs agentic
- **目标**：建立可量化的检索质量基线
- **范围**：
  - 引入 `Ragas` / `TruLens` 评测框架
  - 为每个 KB 准备 30-100 条 question-context-answer 三元组
  - CI 中跑 `EvalService.evalRagQuality`，产出 Recall@5 / MRR / Faithfulness
  - 失败用例进入 RAG 训练数据，反哺查询改写与切片策略
- **验收**：
  - 核心 KB Recall@5 ≥ 0.8
  - 评测报告入 docs/report/rag-quality/
- **依赖**：领域专家标注
- **ROI**：极高（直接提升产品核心价值）
- **预估**：2 周

---

## 4. P1 — 可靠性与可观测性（5 项）

### P1-01 · 统一可观测性栈（OpenTelemetry）

- **背景**：仅 Spring Boot Actuator + agent_trace 表；无分布式追踪
- **范围**：
  - 接入 OpenTelemetry SDK：HTTP → Service → Tool → LLM 调用链
  - trace 关联到 chat_id（与 agent_trace 互为补充）
  - 导出到 Jaeger/Tempo/Datadog
  - Micrometer 指标：tool 调用次数、LLM 延迟、token、降级率
  - 结构化日志（logback JSON encoder），含 trace_id / chat_id / user_id
- **验收**：
  - 一次 agent 调用可在 Jaeger UI 看到完整调用树
  - Grafana 仪表盘上线
- **预估**：2 周

### P1-02 · Chroma 高可用与缓存

- **背景**：单实例 Chroma；`cachedCollectionId` 内存缓存
- **范围**：
  - 生产 Chroma 集群化或迁 Qdrant/Milvus
  - `cachedCollectionId` → Redis 共享缓存
  - 引入 query cache（相同 query + KB 缓存检索结果 5 分钟）
  - Collection 备份与恢复脚本
- **预估**：2 周

### P1-03 · 文档处理流水线分阶段队列

- **背景**：当前 `documentProcessExecutor` 单阶段；卡死文档靠 scheduler 回收
- **范围**：
  - 拆分为多阶段队列（解析 → 切片 → Embedding → 入库），各阶段独立 WorkerPool
  - 死信队列（DLQ）保存多次失败文档
  - 大文档（>50MB）单独通道
  - 优先级队列：用户最近提问的 KB 优先
  - 进度 SSE 应包含 ETA + stage 信息
- **预估**：1-2 周

### P1-04 · 错误处理与降级一致性

- **背景**：错误处理风格分散，部分 RuntimeException，部分降级 ChatResult
- **范围**：
  - 统一 `BizException` + `ErrorCode` 体系（按 FR-012 业务域分类）
  - 统一 `GlobalExceptionHandler` 映射标准化错误响应
  - 错误日志结构化：code / message / context / suggestion
  - 客户端统一 `ErrorBoundary` + Toast 组件
  - 后端降级策略白皮书
- **预估**：1 周

### P1-05 · 测试金字塔完善

- **背景**：单元 + 集成；E2E 是 PENDING-MANUAL
- **范围**：
  - `Testcontainers`：测试用真实 MySQL/Chroma/Ollama
  - `Karate` / `REST Assured` API 契约测试
  - `Spring Cloud Contract` 消费者驱动契约
  - 前端 `Vitest` + `@vue/test-utils` 组件测试
  - 前端 `Playwright` E2E
  - Mutation 测试纳入 CI
- **预估**：2 周持续投入

---

## 5. P2 — 可维护性与模块化（5 项）

### P2-01 · 抽象 Provider 接口（LLM / Vector / WebSearch / Embedding）

- **背景**：直接依赖 ChatClient.Builder / ChromaService / Tavily / Ollama
- **范围**：
  - `LlmProvider` 接口（OpenAI / Anthropic / MiniMax 适配器）
  - `VectorStore` 接口（Chroma / Qdrant / Milvus 适配器）
  - `WebSearchProvider` 接口（Tavily / SerpAPI / Bing 适配器）
  - `EmbeddingProvider` 接口（OpenAI / Ollama / Cohere 适配器）
- **预估**：1-2 周

### P2-02 · Tool Registry 动态加载

- **背景**：Tool 硬编码到 AgenticRagService 构造
- **范围**：
  - `ToolRegistry` 通过 SPI/Java ServiceLoader 动态加载
  - 新增 Tool 不需要改 AgenticRagService
  - 工具元信息（name / description / required scopes）注册化
- **预估**：1 周

### P2-03 · 配置管理升级

- **背景**：`.env` + `application.properties`，无配置中心
- **范围**：
  - 引入 `Spring Cloud Config` / `Nacos` / `Consul KV`
  - 不同环境 profile（dev / staging / prod）
  - 敏感配置走 KMS / Vault
  - 动态刷新 `@RefreshScope`
- **预估**：2 周

### P2-04 · 模块化与可替换性

- **背景**：service / repository / agent 包内耦合较紧
- **范围**：
  - 抽象 `ConversationService`（会话状态机）
  - 抽象 `RagService` 为可插拔检索策略
  - 抽象 `AgentOrchestrator`（ReAct / Plan-Execute 可切换）
  - 模块化包结构：`com.ragqa.{platform, knowledge, agent, llm, infra}`
- **预估**：3-4 周

### P2-05 · Prompt 模板化

- **背景**：Prompt 硬编码在 `RagService` / `AgenticRagService`
- **范围**：
  - 引入 `PromptTemplate` 资源文件（`resources/prompts/*.st`）
  - 版本化（Git LFS / 独立仓库）
  - A/B 测试框架
  - 安全审查（避免 prompt injection）
- **预估**：1-2 周

---

## 6. P3 — 性能与可扩展性（5 项）

### P3-01 · LLM 调用优化

- **范围**：
  - `PromptTemplate` 预编译 + LRU 缓存
  - Query rewrite 结果缓存（query + history 哈希）
  - Streaming 优化（SSE 首字延迟）
  - 引入 `vLLM` / `TGI` 本地推理
  - Tool call 结果 LRU 缓存（kb_search top-K 60s）
- **预估**：2 周

### P3-02 · 并发与连接池

- **范围**：
  - Tomcat 线程数 = CPU * 2（当前过宽）
  - MySQL 连接池：`hikari.maximum-pool-size=20`, `connection-timeout=3000`
  - `WebClient` 替代 `RestClient`（响应式、连接池）
  - SSE 连接数监控 + 超时回收
- **预估**：1 周

### P3-03 · 多 KB 并行检索

- **范围**：
  - 支持 `multi-kb` 检索（用户可选多个 KB 联合检索）
  - 跨 KB 结果合并 + Rerank
  - KB 联邦检索（企业多业务线）
- **预估**：2 周

### P3-04 · Web 搜索融合

- **范围**：
  - 多搜索引擎融合（Tavily + Brave + DuckDuckGo）
  - 搜索结果去重 + 排序（相关度 + 来源权威）
  - 搜索结果缓存（query 哈希 30 分钟）
  - 关键词提取 + 查询改写集成
- **预估**：2 周

### P3-05 · 前端性能优化

- **范围**：
  - 消息列表虚拟滚动
  - SSE chunk 渲染节流
  - 路由懒加载
  - Web Vitals 监控（LCP / FID / CLS）
  - 静态资源 CDN
- **预估**：1-2 周

---

## 7. P4 — 产品体验（4 项）

### P4-01 · Agent 思考过程可视化

- **范围**：
  - 前端渲染 `agent_step` 事件：KB/Web/直答三色标识
  - 工具调用链折叠展开
  - token 消耗与耗时展示
- **预估**：1-2 周

### P4-02 · 协作能力

- **范围**：
  - 共享 KB / 共享 conversation
  - 角色权限（管理员 / 编辑者 / 查看者）
  - 审计日志
  - 知识库版本管理（KB 快照/回滚）
- **预估**：3-4 周

### P4-03 · 主动能力

- **范围**：
  - 定时 KB 巡检（自动发现文档变更，重新向量化）
  - 主动推荐（基于历史问题推荐文档）
  - 自动 FAQ 生成
- **预估**：2-3 周

### P4-04 · 反馈闭环

- **范围**：
  - 用户点赞/点踩（消息级）
  - 反馈数据反哺 Ragas 评测
  - A/B 报告看板
- **预估**：1 周

---

## 8. P5 — 可演进性（3 项）

### P5-01 · 多模态与跨语言

- **范围**：
  - 图像理解（CLIP / GPT-4V）
  - 语音输入输出（Whisper / TTS）
  - 多语言 Embedding（`bge-m3` / `multilingual-e5`）
- **预估**：4-6 周

### P5-02 · Agent 能力扩展

- **范围**：
  - SQL 工具
  - 计算器工具
  - 动态 HTTP API 调用
  - 代码执行沙箱
  - 多 Agent 协作（ReAct + 反思）
- **预估**：4-8 周持续

### P5-03 · 知识库管理升级

- **范围**：
  - 文档版本对比
  - 自动文档分类
  - KB 知识图谱（实体 + 关系）
  - 多模态文档（PDF/Word/PPT/图片）
- **预估**：6-8 周

---

## 9. 立即可做（高 ROI 3 项）

如果只能选 3 项立即做，按 ROI 排序：

| 优先级 | 项 | ROI | 预估 |
|---|---|---|---|
| 1 | **P0-02 限流** | 防止恶意刷量与配额失控，1 周可完成 | ⭐⭐⭐⭐⭐ |
| 2 | **P0-03 LLM 韧性** | 单点故障不雪崩，多模型降级直接提升可用性 | ⭐⭐⭐⭐⭐ |
| 3 | **P0-04 RAG 评测** | 把 RAG 质量从"感觉好"变为可量化指标 | ⭐⭐⭐⭐⭐ |

这三项都满足：**高 ROI、低风险、不破坏现有架构**。

---

## 10. 实施路线图

| 阶段 | 时长 | 重点 | 期望交付 |
|---|---|---|---|
| 阶段 1（P0） | 1-2 月 | 真实环境全链路验证、限流、LLM 韧性、RAG 评测 | 4 项 |
| 阶段 2（P1） | 3-4 月 | OpenTelemetry、Chroma HA、文档分阶段队列、错误一致性、测试金字塔 | 5 项 |
| 阶段 3（P2） | 5-6 月 | Provider 抽象、Tool Registry、配置管理、模块化、Prompt 模板化 | 5 项 |
| 阶段 4（P3） | 7-9 月 | LLM 优化、连接池、多 KB 检索、Web 搜索融合、前端性能 | 5 项 |
| 阶段 5（P4） | 10-12 月 | Agent 可视化、协作能力、主动能力、反馈闭环 | 4 项 |
| 阶段 6（P5） | 13+ 月 | 多模态、Agent 能力扩展、知识库管理升级 | 3 项 |

---

## 11. 状态追踪表

> 每项完成后更新：`[ ]` → `[x]`，并记录 PR / commit。

| ID | 标题 | 优先级 | 状态 | PR | 完成日期 | 备注 |
|---|---|---|---|---|---|---|
| P0-01 | 真实环境全链路验证 | P0 | [ ] | | | |
| P0-02 | 限流与防滥用 | P0 | [ ] | | | |
| P0-03 | LLM 调用韧性 | P0 | [ ] | | | |
| P0-04 | RAG 检索质量可量化 | P0 | [ ] | | | |
| P1-01 | OpenTelemetry 接入 | P1 | [ ] | | | |
| P1-02 | Chroma 高可用 | P1 | [ ] | | | |
| P1-03 | 文档处理分阶段队列 | P1 | [ ] | | | |
| P1-04 | 错误处理与降级一致性 | P1 | [ ] | | | |
| P1-05 | 测试金字塔完善 | P1 | [ ] | | | |
| P2-01 | Provider 接口抽象 | P2 | [ ] | | | |
| P2-02 | Tool Registry 动态加载 | P2 | [ ] | | | |
| P2-03 | 配置管理升级 | P2 | [ ] | | | |
| P2-04 | 模块化与可替换性 | P2 | [ ] | | | |
| P2-05 | Prompt 模板化 | P2 | [ ] | | | |
| P3-01 | LLM 调用优化 | P3 | [ ] | | | |
| P3-02 | 并发与连接池 | P3 | [ ] | | | |
| P3-03 | 多 KB 并行检索 | P3 | [ ] | | | |
| P3-04 | Web 搜索融合 | P3 | [ ] | | | |
| P3-05 | 前端性能优化 | P3 | [ ] | | | |
| P4-01 | Agent 思考过程可视化 | P4 | [ ] | | | |
| P4-02 | 协作能力 | P4 | [ ] | | | |
| P4-03 | 主动能力 | P4 | [ ] | | | |
| P4-04 | 反馈闭环 | P4 | [ ] | | | |
| P5-01 | 多模态与跨语言 | P5 | [ ] | | | |
| P5-02 | Agent 能力扩展 | P5 | [ ] | | | |
| P5-03 | 知识库管理升级 | P5 | [ ] | | | |

---

## 12. 关联文档

- 架构视图：[docs/summary/application-architecture.md](../summary/application-architecture.md)
- 逻辑视图：[docs/summary/logical-view.md](../summary/logical-view.md)
- 数据视图：[docs/summary/data-view.md](../summary/data-view.md)
- 运行视图：[docs/summary/runtime-view.md](../summary/runtime-view.md)
- 部署视图：[docs/summary/deployment-view.md](../summary/deployment-view.md)
- 底层技术债 backlog：[2026-06-29-rag-optimization-backlog.md](./2026-06-29-rag-optimization-backlog.md)
- 任务进度：[../task-progress.md](../task-progress.md)

---

## 备注

- **本文档与现有 backlog 互补**：现有 `2026-06-29-rag-optimization-backlog.md` 关注底层技术债（Bug/性能/Prompt 等 18 项），本文档关注架构演进。
- **优先级定义**：
  - P0：影响生产就绪或安全
  - P1：影响可靠性与可观测性
  - P2：影响可维护性
  - P3：影响性能与可扩展性
  - P4：影响产品体验
  - P5：影响可演进性
- **维护频率**：每季度 review 一次，根据业务目标调整
- **执行原则**：单 PR 单项；每项有独立验收标准
