# rag-qa 应用架构视图

> 本目录按 4+1 视图模型（4+1 Architectural View Model）描绘 rag-qa 项目的应用架构。
> 涵盖朴素 RAG 与 Agentic RAG 两条问答路径在同一系统内的并存与路由。

## 文档索引

| 视图 | 文档 | 关注点 |
|---|---|---|
| 4+1 总览 | [application-architecture.md](./application-architecture.md) | 系统分层、跨进程边界、关键场景串联 |
| 逻辑视图 | [logical-view.md](./logical-view.md) | 后端/前端模块、关键类、RAG 模式路由、Agent 工具集 |
| 数据视图 | [data-view.md](./data-view.md) | MySQL Schema、Chroma 集合、Flyway 迁移、文件存储 |
| 运行视图 | [runtime-view.md](./runtime-view.md) | 启动顺序、线程模型、SSE 流、配置加载 |
| 部署视图 | [deployment-view.md](./deployment-view.md) | 本地 / Docker 部署拓扑、端口与依赖 |

## 视图方法论

- **4+1 视图**：逻辑、开发、过程、物理 + 场景（本文档作为场景串联）。
- **C4 风格**：Context → Container → Component → Code 逐层细化。
- **Mermaid 渲染**：所有架构图使用 Mermaid 语法，GitHub / IDE 预览原生支持。

## 关键场景

| 场景 | 描述 | 涉及路径 |
|---|---|---|
| S1 朴素 RAG | 用户提问 → 单轮线性检索 → 生成回答 | ChatService → RagService → HybridSearch → Chroma |
| S2 Agentic RAG | 用户提问 → LLM 自主编排工具 → 多源检索 → 综合回答 | ChatService → AgenticRagService → Spring AI tool-calling → KnowledgeBaseSearchTool / WebSearchTool / DirectAnswerTool |
| S3 模式切换 | 用户在 UI 切换 per-conversation 模式 | ChatView → chatStore → PATCH /conversations/{id}/rag-mode → ChatService 路由 |
| S4 文档处理 | 用户上传 PDF → 解析 → 切片 → Embedding → Chroma 入库 | DocumentController → DocumentService → DocumentProcessService → EmbeddingService → ChromaService |
| S5 Eval A/B | 评估脚本同题对比 linear vs agentic | EvalController → EvalService.abCompare → RagService + AgenticRagService |

## 关键边界

```text
[Browser] -- HTTPS / SSE --> [Vue3 SPA] -- HTTP/JWT --> [Spring Boot Backend]
                                                                  |
                                                                  +-- [MySQL] (schema, history, trace)
                                                                  +-- [Chroma] (vector)
                                                                  +-- [Ollama] (embedding)
                                                                  +-- [LLM API] (OpenAI compatible, MiniMax)
                                                                  +-- [Tavily API] (optional WebSearch)
```

## 项目定位

- **朴素 RAG**：基于混合检索（向量 + BM25 + 重排序）的单轮线性流水线（feature #4–#6、#11–#15）。
- **Agentic RAG**：基于 Spring AI tool-calling 的多轮工具编排（feature #17–#23）。
- **Eval A/B**：两种模式对比（feature #22）。
- **生产部署**：本地或 Docker；MySQL + Chroma + Ollama 三件基础设施。
