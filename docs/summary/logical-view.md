# 逻辑视图（Logical View）

> 描绘后端与前端的模块划分、关键类、RAG 模式路由、Agent 工具集。
> 重点说明朴素 RAG 与 Agentic RAG 在同一系统内的并存与边界。

## 1. 后端模块（com.ragqa.*）

```mermaid
flowchart TB
  subgraph ctrl["controller"]
    AuthC["AuthController"]
    ChatC["ChatController"]
    ChatHC["ChatHistoryController"]
    ConfC["ConfigController"]
    ConvC["ConversationController"]
    DocC["DocumentController"]
    KBC["KnowledgeBaseController"]
    EvalC["EvalController"]
  end

  subgraph app["application service"]
    ChatS["ChatService<br/>(rag.mode 路由)"]
    AgentS["AgenticRagService<br/>(tool-calling loop + 降级)"]
    RagS["RagService<br/>(query rewrite + retrieve + augment + generate)"]
    EvalS["EvalService.abCompare"]
  end

  subgraph kb["知识库服务"]
    KBSvc["KnowledgeBaseService"]
    DocS["DocumentService"]
    DocPS["DocumentProcessService"]
    EmbS["EmbeddingService"]
    HSvc["HybridSearchService"]
    Bm25["Bm25SearchService"]
    RerankS["RerankService"]
    ChromaS["ChromaService"]
  end

  subgraph auth["鉴权"]
    USvc["UserService"]
    JwtS["JwtService"]
  end

  subgraph ingest["辅助处理"]
    QRSvc["QueryRewriteService"]
    OcrS["OcrService"]
    TES["TableExtractorService"]
    DSE["DocumentStatusEventService (SSE)"]
  end

  subgraph agent["agent 子系统 (F17-F22)"]
    KBSearchTool["KnowledgeBaseSearchTool"]
    WebTool["WebSearchTool (Tavily)"]
    DirectTool["DirectAnswerTool"]
    KBCTX["KnowledgeBaseContext (ThreadLocal)"]
    TC["TraceContext (ThreadLocal)"]
    ATC["AgentTraceCollector"]
    ATR["AgentTraceRepository"]
  end

  subgraph cfg["config"]
    SecCfg["SecurityConfig"]
    CorsCfg["CorsConfig"]
    AsyncCfg["AsyncConfig"]
    ChromaCfg["ChromaConfig"]
    OpenApiCfg["OpenApiConfig"]
  end

  ctrl --> ChatS
  ctrl --> KBSvc
  ctrl --> DocS
  ctrl --> USvc
  ctrl --> EvalS
  ctrl --> ConfC

  ChatS --> RagS
  ChatS --> AgentS

  AgentS --> KBSearchTool
  AgentS --> WebTool
  AgentS --> DirectTool
  AgentS --> RagS
  AgentS --> ATC

  KBSearchTool --> RagS
  WebTool -.可选.-> TavilyAPI[(Tavily HTTP)]
  DirectTool -.旁路.-> AgentS

  RagS --> HSvc
  RagS --> QRSvc
  RagS --> RerankS
  RagS --> ChromaS
  RagS --> EmbS

  HSvc --> Bm25
  HSvc --> ChromaS
  RerankS --> EmbS
  RerankS -. 可选 .-> RerankExt[(Ollama 重排模型)]

  DocS --> DocPS
  DocS --> ChromaS
  DocS --> KBSvc
  DocS --> DSE

  DocPS --> EmbS
  DocPS --> ChromaS
  DocPS --> OcrS
  DocPS --> TES

  ATC --> ATR
  KBCTX --> KBSearchTool
  TC --> ATC
```

## 2. 关键类与契约

### 2.1 Tool 抽象（agent.tool 包）

```mermaid
classDiagram
  class ToolResult {
    <<record>>
    +String toolName
    +String content
    +String source
    +long durationMs
  }

  class KnowledgeBaseContext {
    <<utility>>
    +set(UUID)
    +get() UUID
    +clear()
  }

  class TraceContext {
    <<utility>>
    +set(String chatId)
    +getChatId() String
    +nextRound() int
    +clear()
  }

  class KnowledgeBaseSearchTool {
    -RagService ragService
    -AgentTraceCollector traceCollector
    +searchKnowledgeBase(query) ToolResult
  }

  class WebSearchTool {
    -String apiKey
    -int topK
    -RestClient tavilyClient
    -AgentTraceCollector traceCollector
    +isAvailable() boolean
    +searchWeb(query) ToolResult
  }

  class DirectAnswerTool {
    -AgentTraceCollector traceCollector
    +directAnswer(question) ToolResult
  }

  KnowledgeBaseSearchTool ..> ToolResult
  WebSearchTool ..> ToolResult
  DirectAnswerTool ..> ToolResult
  KnowledgeBaseSearchTool ..> KnowledgeBaseContext
  KnowledgeBaseSearchTool ..> TraceContext
  WebSearchTool ..> TraceContext
  DirectAnswerTool ..> TraceContext
```

### 2.2 RAG 模式路由

```mermaid
flowchart LR
  Req[POST /api/chat] --> ChatS[ChatService.executeChat]
  ChatS --> Resolve{resolveRagMode<br/>conv.rag_mode != null ?}
  Resolve -- "有" --> Conv[Conversation.rag_mode]
  Resolve -- "无" --> Global[rag.mode 全局默认]
  Conv --> Switch{rag.mode 取值}
  Global --> Switch
  Switch -- "linear" --> RagS[RagService.chat]
  Switch -- "agentic" --> AgentS[AgenticRagService.chat]
  RagS --> Resp[ChatResult mode=linear]
  AgentS --> RespA[ChatResult mode=agentic<br/>+ rounds + degraded]
```

优先级：`conversation.rag_mode` 非 null > 全局 `rag.mode`；null 继承全局。

### 2.3 Agent Loop 与降级

```mermaid
flowchart TB
  Start[AgenticRagService.chat] --> Ctx[KnowledgeBaseContext.set(kbId)<br/>TraceContext.set(chatId)]
  Ctx --> Future[CompletableFuture.supplyAsync]
  Future --> Loop{Spring AI<br/>tool-calling loop}
  Loop -- "多轮工具调用" --> Tools[KnowledgeBaseSearchTool / WebSearchTool / DirectAnswerTool]
  Tools --> Loop
  Loop -- "完成" --> Resp[ChatResult mode=agentic<br/>rounds=N, degraded=false]
  Loop -- "TimeoutException" --> Cancel[future.cancel(true)]
  Cancel --> Degrade
  Future -- "其他异常" --> Degrade[markDegraded() + RagService.chat]
  Degrade --> RespL[ChatResult mode=linear<br/>degraded=true, rounds=0]
  Resp --> Ctx2[finally clear ThreadLocal]
  RespL --> Ctx2
```

降级触发：30s 总超时 / LLM 不支持 tool-calling / 任何未捕获异常 / 单边 try/catch。

## 3. 前端模块（Vue3 + Pinia）

```mermaid
flowchart TB
  subgraph views["views/"]
    LoginV["LoginView"]
    ChatV["ChatView"]
    KBV["KnowledgeView"]
  end

  subgraph comps["components/"]
    subgraph chat["chat/"]
      MsgList["MessageList"]
      MsgBubble["MessageBubble"]
      Composer["ComposerInput"]
      RagToggle["RagModeToggle"]
      SrcCard["SourceCard"]
      History["ChatHistoryList/Item"]
    end
    subgraph kb["knowledge/"]
      KBList["KnowledgeBaseList/Item"]
      DocList["DocumentList"]
      Upload["UploadDocModal"]
    end
    subgraph layout["layout/"]
      Shell["AppShell"]
      Sidebar["AppSidebar"]
    end
    subgraph common["common/"]
      Toast["ToastContainer"]
      Brand["BrandMark"]
    end
  end

  subgraph stores["stores/ (Pinia)"]
    Auth["auth.js"]
    Chat["chat.js<br/>+ effectiveRagMode"]
    Conf["config.js<br/>+ globalRagMode"]
    KB["knowledgeBase.js"]
    UI["ui.js"]
  end

  subgraph api["api/"]
    AuthA["auth.js"]
    ChatA["chat.js"]
    HistA["chatHistory.js"]
    ConvA["conversation.js<br/>updateRagMode"]
    ConfA["config.js"]
    KBA["knowledgeBase.js"]
  end

  LoginV --> Auth
  LoginV --> AuthA

  ChatV --> Chat
  ChatV --> Conf
  ChatV --> UI
  ChatV --> ChatA
  ChatV --> ConfA
  ChatV --> ConvA
  ChatV --> msgList & Composer & RagToggle & SrcCard & History

  RagToggle --> Chat
  Chat --> ConvA
  Conf --> ConfA
  ConfA -.GET /api/config.-> Backend

  KBV --> KB
  KBV --> KBA
  KBV --> KBList & DocList & Upload
```

## 4. 关键设计决策

| 决策 | 原因 | 影响 |
|---|---|---|
| Tool 抽象统一返回 `ToolResult` record | 多工具统一格式便于 trace 与 LLM schema 生成 | 工具集可插拔；新增 tool 不影响 ChatClient |
| kbId / chatId 用 ThreadLocal | 不暴露给 LLM schema generation | 避免 LLM 瞎填或跨库串答 |
| `internalToolExecutionEnabled=true` + `CompletableFuture` 30s 超时 | Spring AI 1.1.3 无 `maxIterations` API | 避免 agent 死循环；超时降级 linear |
| RerankService + HybridSearchService + Bm25SearchService | 向量 + BM25 双路召回 + 重排序 | 提升检索召回率与精排质量 |
| Fallback 到 MySQL 余弦相似度 | Chroma 不可用时不挂主链路 | 提升可用性 |
| 文档处理走 `@Async` 线程池 | 长任务不阻塞 HTTP 线程 | 提升响应；P0 修复过线程安全问题 |
| Conversation 替代旧的 ChatHistory | 减少 chat_history.rag_metadata JSON 膨胀 | 数据模型清晰 |
| TraceContext 自增 round | 同一 chat 多轮 tool 调用可区分 | trace 落库可按 round 排序 |
| WebSearchTool 在无 TAVILY_API_KEY 时不注册 | agent 不再尝试不可用工具 | 优雅降级 |

## 5. 跨模块调用矩阵

| 源模块 | 目标模块 | 触发场景 |
|---|---|---|
| ChatController | ChatService | 问答请求 |
| ChatController | AgentTraceCollector | 流式推送 agent_step |
| ChatService | RagService | linear 路径 |
| ChatService | AgenticRagService | agentic 路径 |
| AgenticRagService | KnowledgeBaseSearchTool | kb_search |
| AgenticRagService | WebSearchTool | web_search |
| AgenticRagService | DirectAnswerTool | direct_answer |
| KnowledgeBaseSearchTool | RagService | 复用 retrieve 链路 |
| WebSearchTool | Tavily API | 外部 HTTP |
| RagService | HybridSearchService | 混合检索（向量 + BM25 双路） |
| RagService | RerankService | 召回重排（TOP_K 精排；Ollama 失败自动降级） |
| RagService | QueryRewriteService | 查询改写（多轮上下文感知） |
| RagService | ChromaService | fallback 检索（Chroma 不可用时 MySQL 余弦相似度） |
| RagService | EmbeddingService | embedding 向量计算 |
| HybridSearchService | ChromaService | 向量召回 |
| HybridSearchService | Bm25SearchService | BM25 召回 |
| RerankService | EmbeddingService | 重排 embedding（rerank model） |
| DocumentProcessService | EmbeddingService | 向量化 |
| DocumentProcessService | ChromaService | 向量入库 |
| DocumentProcessService | DocumentStatusEventService | 状态广播 |
| EvalService | RagService | linear 模式 |
| EvalService | AgenticRagService | agentic 模式 |

## 6. 模块依赖原则

- **controller → service 单向**：controller 不直接调用 repository。
- **service → repository 单向**：跨表操作封装在 service 内。
- **agent 子系统复用 service**：KnowledgeBaseSearchTool 复用 RagService，不重写检索。
- **trace 解耦**：AgentTraceCollector 注入到所有 tool，但与主链路 catch 隔离。
- **事件总线**：DocumentStatusEventService 使用 Reactor Sinks，不阻塞状态写。
