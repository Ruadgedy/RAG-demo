# 应用架构总览（Application Architecture Overview）

> 4+1 视图中的"+1 场景"——以五张核心场景串联整个系统的层、模块与跨进程边界。
> 详细分层、数据、运行与部署分别见同目录其他文档。

## 1. 系统层级

```mermaid
flowchart TB
  subgraph Client["客户端 / 浏览器"]
    SPA["Vue3 SPA<br/>Vite + Pinia"]
  end

  subgraph Edge["API 网关层（Spring Boot）"]
    Filter["JwtAuthenticationFilter"]
    Controllers["Controllers<br/>(Auth/Chat/KB/Document/...)"]
  end

  subgraph App["应用服务层"]
    ChatService["ChatService<br/>(rag.mode 路由)"]
    AgenticRagService["AgenticRagService<br/>(Spring AI tool-calling)"]
    RagService["RagService<br/>(线性流水线)"]
    OtherSvc["其他服务<br/>(KB/Document/Embedding/...)"]
  end

  subgraph AgentTool["Agentic 工具集（com.ragqa.agent.tool）"]
    KBSTool["KnowledgeBaseSearchTool"]
    WebTool["WebSearchTool (Tavily)"]
    DirectTool["DirectAnswerTool"]
  end

  subgraph Data["数据与基础设施层"]
    MySQL[("MySQL<br/>(Spring Data JPA)")]
    Chroma[("Chroma<br/>(HTTP)")]
  end

  subgraph AI["AI 模型服务层"]
    Ollama["Ollama<br/>(Embedding HTTP)"]
    LLM["LLM API<br/>(OpenAI 兼容 / MiniMax)"]
    Tavily["Tavily API<br/>(可选 Web 搜索)"]
  end

  SPA -- "HTTP / SSE / JWT" --> Filter --> Controllers
  Controllers --> ChatService
  Controllers --> OtherSvc
  ChatService -- "linear" --> RagService
  ChatService -- "agentic" --> AgenticRagService
  AgenticRagService --> KBSTool --> RagService
  AgenticRagService --> WebTool --> Tavily
  AgenticRagService --> DirectTool
  RagService --> MySQL
  RagService --> Chroma
  RagService --> Ollama
  AgenticRagService --> LLM
  OtherSvc --> MySQL
  OtherSvc --> Chroma
```

> **修正说明（F-007）**：原版把 Ollama 放在"数据与基础设施层"，与 MySQL/Chroma 同列。但 Ollama 是 HTTP 推理服务（提供 Embedding 模型），与 LLM API、Tavily 同属"AI 模型服务层"。架构上将其独立成层，体现"数据 vs 模型"的分离。

## 2. 容器视图（C4 Container）

```mermaid
flowchart LR
  Browser["浏览器<br/>Vue3 + Pinia + Element Plus"]
  Backend["Spring Boot 后端<br/>JDK 21 / Tomcat 8080"]
  MySQL[("MySQL 8.x<br/>schema/chat/agent_trace")]
  Chroma[("Chroma 0.5.x<br/>vector store")]
  Ollama["Ollama<br/>本地 embedding 模型"]
  LLM["LLM API<br/>OpenAI 兼容 (MiniMax-M3)"]
  Tavily["Tavily<br/>(可选 Web 搜索)"]

  Browser -- "HTTPS + JWT" --> Backend
  Backend -- "JDBC" --> MySQL
  Backend -- "HTTP" --> Chroma
  Backend -- "HTTP" --> Ollama
  Backend -- "HTTPS" --> LLM
  Backend -- "HTTPS (可选)" --> Tavily
```

## 3. 关键场景流程

### 场景 S1：朴素 RAG（linear）

```mermaid
sequenceDiagram
  autonumber
  participant U as User
  participant V as ChatView
  participant S as ChatService
  participant R as RagService
  participant H as HybridSearchService
  participant C as Chroma
  participant M as MySQL
  participant L as LLM

  U->>V: 输入问题
  V->>S: POST /api/chat (mode=linear)
  S->>R: chat(message, kb, history, window)
  R->>R: query rewrite + history window
  R->>H: 混合检索
  H->>C: 向量召回
  H->>M: BM25 召回
  R->>R: rerank + fallback
  R->>L: prompt + context → answer
  R-->>S: ChatResult (mode=linear)
  S-->>V: SSE chunks / JSON
  V-->>U: 渲染回答 + 来源
```

### 场景 S2：Agentic RAG（agentic）

```mermaid
sequenceDiagram
  autonumber
  participant U as User
  participant V as ChatView
  participant S as ChatService
  participant A as AgenticRagService
  participant L as LLM (Spring AI)
  participant K as KnowledgeBaseSearchTool
  participant W as WebSearchTool
  participant D as DirectAnswerTool
  participant T as AgentTraceCollector
  participant DB as MySQL agent_trace

  U->>V: 输入问题
  V->>S: POST /api/chat (mode=agentic)
  S->>A: chat(chatId, msg, kb, history, window)
  A->>T: start (chatId, round=1)
  A->>L: ChatClient.prompt().tools(...)
  loop 多轮 tool-calling
    L-->>A: tool_call (kb_search | web_search | direct)
    alt kb_search
      A->>K: searchKnowledgeBase(query)
      K->>A: ToolResult(content, source, duration)
    else web_search
      A->>W: searchWeb(query)
      W-->>A: ToolResult
    else direct
      A->>D: directAnswer(question)
      D-->>A: ToolResult
    end
    A->>T: done (chatId, round, tool, summary, duration)
    T->>DB: save AgentTrace
  end
  A-->>S: ChatResult (mode=agentic, rounds=N, degraded=false)
  alt 超时或异常
    A->>S: ChatResult (mode=linear, degraded=true)
  end
  S-->>V: SSE chunks（含 event: agent_step）
```

### 场景 S3：per-conversation 模式切换

```mermaid
sequenceDiagram
  autonumber
  participant U as User
  participant V as ChatView
  participant CS as chatStore
  participant API as api/conversation
  participant CC as ConversationController
  participant DB as MySQL conversation
  participant S as ChatService

  U->>V: 点击 RagModeToggle (agentic)
  V->>CS: updateRagMode(convId, 'agentic') (乐观)
  CS->>API: PATCH /api/conversations/{id}/rag-mode
  API->>CC: HTTP PATCH
  CC->>DB: Conversation.rag_mode = 'agentic'
  alt 成功
    DB-->>CC: OK
    CC-->>API: 200
    API-->>CS: 提交
  else 失败
    CC-->>API: 5xx
    API-->>CS: 失败
    CS->>CS: 回滚 rag_mode
    CS->>U: Toast 错误
  end
  U->>V: 发送新提问
  V->>S: POST /api/chat
  S->>S: resolveRagMode(conv, global) = 'agentic'
  S->>S: 路由 AgenticRagService
```

### 场景 S4：文档上传与向量化

```mermaid
sequenceDiagram
  autonumber
  participant U as User
  participant V as KnowledgeView
  participant DC as DocumentController
  participant DS as DocumentService
  participant DPS as DocumentProcessService
  participant ES as EmbeddingService
  participant C as ChromaService
  participant DB as MySQL document/chunk
  participant FS as 本地文件存储

  U->>V: 上传 PDF
  V->>DC: POST /api/knowledge-bases/{id}/documents
  DC->>DS: 保存文件 + 写 document 记录
  DS->>FS: 落盘
  DS->>DB: INSERT document (status=PROCESSING)
  DC-->>V: 200 + document 对象
  V->>U: 乐观插入到列表
  par 异步处理
    DPS->>DPS: 解析（PDFBox / docx4j / Tika）
    DPS->>DPS: 切片 (fixed / paragraph)
    DPS->>DB: 写 document_chunks
    DPS->>ES: embedBatch(chunks)
    ES->>Ollama: HTTP embed
    DPS->>C: addDocument(vectors)
    DPS->>DB: UPDATE document (status=COMPLETED)
    DPS-->>V: SSE doc-status event
  end
```

### 场景 S5：Eval A/B 对比

```mermaid
sequenceDiagram
  autonumber
  participant Op as 评估脚本 / 管理员
  participant EC as EvalController
  participant ES as EvalService
  participant R as RagService
  participant A as AgenticRagService

  Op->>EC: POST /api/admin/eval/ab
  EC->>ES: abCompare(q, kb, history, window)
  ES->>R: linear 串行
  R-->>ES: ModeOutcome (answer / latency / error)
  ES->>A: agentic 串行
  A-->>ES: ModeOutcome (rounds / degraded / error)
  ES-->>EC: AbCompareResult
  EC-->>Op: JSON 报告
```

## 4. 横切关注点

| 关注点 | 实现位置 |
|---|---|
| 鉴权与 JWT | `JwtService` + `JwtAuthenticationFilter` + `SecurityConfig` |
| 异步任务 | `AsyncConfig.documentProcessExecutor` (core=4, max=8, queue=100) |
| SSE 流 | `ChatController.streamChat` + `DocumentController.streamDocumentStatus` + `AgentTraceCollector.sseData` |
| 线程上下文 | `KnowledgeBaseContext` / `TraceContext` (ThreadLocal) |
| 异常隔离 | Agent 工具 `try/catch` + `AgenticRagService` 30s 超时降级 |
| 配置管理 | `application.properties` 占位符 + `dotenv-spring-boot` 加载 `rag-qa-backend/.env` |
| 可观测性 | `agent_trace` 表 + `degraded` 字段 + Spring Boot Actuator + springdoc-openapi |

## 5. 架构原则

- **零重复实现**：KnowledgeBaseSearchTool 复用 RagService.retrieve 链路（召回 + rerank + fallback）。
- **协议稳定**：所有 `@Tool` 公开方法返回统一 `ToolResult(toolName, content, source, durationMs)`。
- **降级优先**：Agent 失败/超时/不支持 tool-calling 一律降级 `RagService.chat()`，主链路不挂。
- **可观测**：每轮 tool 调用 2 条 `agent_trace` (start/done)，落库失败 catch + warn 不影响主链路。
- **per-conversation 隔离**：kbId、chatId 均不暴露给 LLM（通过 ThreadLocal 注入）。

> **修正说明（F-008）**：`可观测性` 实施补充 `springdoc-openapi`（`springdoc-openapi-starter-webmvc-ui` 引入 Swagger UI），与 `spring-boot-starter-actuator` 并存。前者提供 `/swagger-ui.html` API 文档，后者提供 `/actuator/health` 健康检查与 `/actuator/prometheus` 指标。

## 6. 视图交叉索引

| 关注 | 主文档 |
|---|---|
| 模块与关键类 | [logical-view.md](./logical-view.md) |
| 数据 Schema 与迁移 | [data-view.md](./data-view.md) |
| 启动/线程/配置 | [runtime-view.md](./runtime-view.md) |
| 部署拓扑 | [deployment-view.md](./deployment-view.md) |
