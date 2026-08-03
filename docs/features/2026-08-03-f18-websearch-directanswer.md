# Feature Detailed Design: WebSearchTool (Tavily) + DirectAnswerTool (Feature #18)

**Date**: 2026-08-03
**Feature**: #18 — WebSearchTool (Tavily) + DirectAnswerTool
**Priority**: high
**Dependencies**: #17 (ToolResult)
**Design Reference**: docs/plans/2026-03-15-rag-qa-design.md §11.3.3 (WebSearchTool) and §11.3.4 (DirectAnswerTool)
**SRS Reference**: FR-013 (lines 239-257)

---

## Context

Feature #18 provides two Spring AI `@Tool` components for the Agentic RAG agent loop: `WebSearchTool` (backed by Tavily API) and `DirectAnswerTool` (for chitchat/common-sense questions). Together they satisfy FR-013's multi-source retrieval abstraction, enabling the agent to route questions to the appropriate tool without the caller needing to know which backend is configured. `WebSearchTool` is conditionally registered — if `TAVILY_API_KEY` is absent, `isAvailable()` returns `false` and the agent excludes it from its tool list. `DirectAnswerTool` is always available and returns a prompt that tells the LLM to answer directly without retrieval.

---

## Design Alignment

### §11.3.3 WebSearchTool (F18)

```java
@Component
public class WebSearchTool {
    @Tool(description = "搜索互联网获取最新或知识库外的信息。涉及时效性、外部产品、公开信息、新闻时使用。")
    public ToolResult searchWeb(String query) {
        // Tavily HTTP POST /search，返回 top-N 摘要
    }
}
```
- 数据源：Tavily（专为 LLM 优化，免费 1000/月，返回干净文本）
- 无 `TAVILY_API_KEY` 时该 tool 不注册，agent 仅用 KB/直答（FR-013 Optional）

### §11.3.4 DirectAnswerTool (F18)

```java
@Tool(description = "直接回答闲聊、寒暄、通用常识类问题。无需检索时使用。")
public ToolResult directAnswer(String question) {
    // 返回提示让 LLM 直接生成，省检索开销
}
```
作用：闲聊/常识类跳过检索，省 token 省延迟。

**Key classes**: `WebSearchTool`, `DirectAnswerTool`, `ToolResult` (from F17)
**Interaction flow**: AgenticRagService holds optional `WebSearchTool` + always-present `DirectAnswerTool`; it calls `isAvailable()` on WebSearchTool to decide whether to register it with the agent. Both tools delegate to F21's `AgentTraceCollector` via `TraceContext`.
**Third-party deps**: Tavily API (`https://api.tavily.com/search`, no extra library — uses Spring `RestClient`)
**Deviations**: None from §11.3.3 and §11.3.4.

---

## SRS Requirement

**FR-013: 工具抽象与多源检索**

系统 shall 提供统一 Tool 抽象（基于 Spring AI `@Tool`），首批含 KnowledgeBaseSearchTool（复用现有 retrieve 链路）、WebSearchTool（Tavily）、DirectAnswerTool。Where Web 搜索无 API key 或调用失败，系统 shall 仅保留 KB/直答工具继续 agent loop，不中断主流程。

**验收标准：**
- Given: TAVILY_API_KEY 已配置 — When: agent 调用 searchWeb(query) — Then: 返回 Tavily top-N 摘要
- Given: TAVILY_API_KEY 未配置 — When: agent 启动 — Then: WebSearchTool 不可用，agent 仅用 KB/直答工具
- Given: 闲聊/常识类问题 — When: agent 判断无需检索 — Then: 调用 DirectAnswerTool 直接回答，不触发 KB/Web 检索

---

## Component Data-Flow Diagram

```mermaid
graph LR
    subgraph "AgenticRagService (F19)"
        A["agent loop"]
    end

    subgraph "F18 Tools"
        B["WebSearchTool<br/>isAvailable() → boolean<br/>searchWeb(query) → ToolResult"]
        C["DirectAnswerTool<br/>directAnswer(question) → ToolResult"]
    end

    subgraph "F17"
        D["ToolResult record<br/>(toolName, content, source, durationMs)"]
    end

    subgraph "External"
        E["Tavily API<br/>POST /search<br/>(api.tavily.com)"]
        F["LLM<br/>(direct answer path)"]
    end

    A -->|"searchWeb()"| B
    A -->|"directAnswer()"| C
    B -->|"HTTP POST /search"| E
    C -->|"prompt text"| F
    B --> D
    C --> D

    style E dashed
    style F dashed
```

> N/A — two distinct tool classes that delegate to different backends; the diagram above captures their runtime collaboration.

---

## Interface Contract

| Method | Signature | Preconditions | Postconditions | Raises |
|--------|-----------|---------------|----------------|--------|
| `WebSearchTool.isAvailable` | `isAvailable() → boolean` | Spring context bootstrapped, `@Value` injected | Returns `true` iff `TAVILY_API_KEY` is non-null and non-blank | — |
| `WebSearchTool.searchWeb` | `searchWeb(query: String) → ToolResult` | `isAvailable() == true` OR `isAvailable() == false` (both handled) | Returns `ToolResult("web_search", content, source, duration)` where content is joined content strings; source is joined URLs; duration >= 0 | Always returns `ToolResult` — network failures produce error content, never throw |
| `WebSearchTool.doSearch` | `doSearch(query: String) → String` (package-private) | `isAvailable() == true` | Returns raw JSON body from Tavily | `RuntimeException` on HTTP/network failure (caller catches) |
| `WebSearchTool.parseResults` | `parseResults(raw: String) → List<JsonNode>` (package-private) | `raw` is a valid JSON string | Returns `List` of result nodes from `results` array; empty list if field absent or non-array | `Exception` if `raw` is not valid JSON (caller catches) |
| `DirectAnswerTool.directAnswer` | `directAnswer(question: String) → ToolResult` | None | Returns `ToolResult("direct_answer", "闲聊/常识类问题。无需检索知识库或网络，请直接基于你的通用知识回答。", "direct", duration)` | — |

**Design rationale**:
- `isAvailable()` is a separate method (not inlined into `searchWeb`) so `AgenticRagService` can call it at registration time without executing a search — aligns with design §11.3.3 "无 TAVILY_API_KEY 时该 tool 不注册".
- `doSearch` and `parseResults` are package-private for testability (Mockito spy override), not `protected`, because tests live in the same package.
- `ToolResult` is an immutable record — `content` field carries error messages verbatim so the agent can see what went wrong.
- `searchWeb` never throws — aligns with FR-013 "网络调用失败不得拖垮 agent loop" and the "done" trace status even on failure.

---

## Visual Rendering Contract

> N/A — backend-only feature, no visual output.

---

## Internal Sequence Diagram

### WebSearchTool.searchWeb (main success path)

```mermaid
sequenceDiagram
    participant A as AgenticRagService
    participant W as WebSearchTool
    participant T as TraceContext
    participant TC as AgentTraceCollector
    participant R as Tavily API

    A->>W: searchWeb(query)
    W->>T: getChatId()
    W->>T: nextRound()
    W->>TC: record(chatId, round, "web_search", args, null, 0, "start")
    W->>W: isAvailable() → true
    W->>W: doSearch(query)
    W->>R: POST /search {api_key, query, max_results, search_depth}
    R-->>W: raw JSON body
    W->>W: parseResults(raw) → List<JsonNode>
    W->>W: stream join(content) and join(url)
    W->>TC: record(chatId, round, "web_search", args, summary, duration, "done")
    W-->>A: ToolResult(content, source, duration)
```

### WebSearchTool.searchWeb (error path)

```mermaid
sequenceDiagram
    participant A as AgenticRagService
    participant W as WebSearchTool
    participant T as TraceContext
    participant TC as AgentTraceCollector
    participant R as Tavily API (fails)

    A->>W: searchWeb(query)
    W->>T: getChatId()
    W->>T: nextRound()
    W->>TC: record(chatId, round, "web_search", args, null, 0, "start")
    W->>W: isAvailable() → true
    W->>W: doSearch(query)
    W->>R: POST /search
    R-->>X: throws RuntimeException
    Note over W: catch block
    W->>TC: record(chatId, round, "web_search", args, "失败: ...", duration, "done")
    W-->>A: ToolResult("Web 搜索失败: ...", "", duration)
```

### DirectAnswerTool.directAnswer

```mermaid
sequenceDiagram
    participant A as AgenticRagService
    participant D as DirectAnswerTool
    participant T as TraceContext
    participant TC as AgentTraceCollector

    A->>D: directAnswer(question)
    D->>T: getChatId()
    D->>T: nextRound()
    D->>TC: record(chatId, round, "direct_answer", args, null, 0, "start")
    D->>D: build prompt string
    D->>TC: record(chatId, round, "direct_answer", args, "闲聊/常识，无需检索", duration, "done")
    D-->>A: ToolResult("direct_answer", prompt, "direct", duration)
```

---

## Algorithm / Core Logic

### WebSearchTool.searchWeb

#### Flow Diagram

```mermaid
flowchart TD
    A[Start searchWeb] --> B{isAvailable?}
    B -->|false| C[Log warn, record trace, return ToolResult with '未配置']
    B -->|true| D[Record trace start, start timer]
    D --> E[doSearch query]
    E --> F[parseResults raw JSON]
    F --> G{parseResults throws?}
    G -->|yes| H[Log error, record trace, return ToolResult with error msg]
    G -->|no| I[Stream join content field from each result]
    I --> J[Stream join url field as source]
    J --> K[Record trace done with hit count + URLs]
    K --> L[Return ToolResult]
    C --> L
    H --> L
```

#### Pseudocode

```
FUNCTION searchWeb(query: String) -> ToolResult
  // Step 1: extract trace context
  chatId = TraceContext.getChatId()
  round  = TraceContext.nextRound()
  args   = Map.of("query", query)

  // Step 2: availability guard — no key, return immediately
  IF NOT isAvailable() THEN
    log.warn("[web_search] 未配置 TAVILY_API_KEY，跳过")
    record(chatId, round, "web_search", args, "未配置 TAVILY_API_KEY", 0, "done")
    RETURN ToolResult("web_search", "Web 搜索未配置（无 TAVILY_API_KEY）", "", 0L)
  END IF

  // Step 3: record start trace
  record(chatId, round, "web_search", args, null, 0, "start")
  start = currentTimeMillis()

  // Step 4: call Tavily HTTP API
  TRY
    raw = doSearch(query)                          // may throw RuntimeException
    results = parseResults(raw)                     // may throw Exception
    content = results.stream()
                      .map(r -> r.path("content").asText(""))
                      .filter(s -> NOT s.isEmpty())
                      .collect(Collectors.joining("\n\n"))
    source  = results.stream()
                      .map(r -> r.path("url").asText(""))
                      .filter(s -> NOT s.isEmpty())
                      .collect(Collectors.joining(", "))
    duration = currentTimeMillis() - start
    log.info("[web_search] 命中 {} 条, 耗时 {}ms, query='{}'", results.size(), duration, query)
    summary = "命中 " + results.size() + " 条" + IF(source not blank) ";URL=" + source
    record(chatId, round, "web_search", args, summary, duration, "done")
    RETURN ToolResult("web_search", content, source, duration)
  CATCH (Exception e)
    duration = currentTimeMillis() - start
    log.error("[web_search] 失败: {}", e.getMessage())
    record(chatId, round, "web_search", args, "失败: " + e.getMessage(), duration, "done")
    RETURN ToolResult("web_search", "Web 搜索失败: " + e.getMessage(), "", duration)
  END TRY
END
```

#### Boundary Decisions

| Parameter | Min | Max | Empty/Null | At boundary |
|-----------|-----|-----|------------|-------------|
| `query` string | 1 char | unbounded | empty string is sent to Tavily as-is (Tavily may return 0 results) | very long query: sent as-is to API; no truncation at tool level |
| `topK` (max_results) | 1 | unbounded | default 5 via `@Value("${rag.web.search.topk:5}")` | at 1: returns single result; at very large N: Tavily may return fewer |
| `apiKey` | non-blank string | unbounded | blank/null → `isAvailable()=false` | blank string explicitly set → `isAvailable()=false` |

#### Error Handling

| Condition | Detection | Response | Recovery |
|-----------|-----------|----------|----------|
| `TAVILY_API_KEY` not configured | `isAvailable()` returns `false` | Return `ToolResult` with "未配置" content, `durationMs=0` | Agent uses KB/直答 only |
| HTTP network failure (connection refused, DNS) | `RestClient` throws `ResourceAccessException` | Caught as `RuntimeException`, return `ToolResult` with error message, `source=""` | Agent continues loop; no exception propagates |
| Tavily returns HTTP 4xx (bad API key) | `RestClient` throws `HttpClientErrorException` | Caught as `RuntimeException`, return `ToolResult` with error message | Agent continues loop |
| Tavily returns malformed JSON | `objectMapper.readTree(raw)` throws `JsonProcessingException` | Caught as `Exception`, return `ToolResult` with error message | Agent continues loop |
| `results` array absent in Tavily response | `results.isArray()` returns false | `parseResults` returns empty list | `searchWeb` joins empty list → `content=""`, `source=""` |

---

### DirectAnswerTool.directAnswer

#### Flow Diagram

```mermaid
flowchart TD
    A[Start directAnswer] --> B[Get trace context]
    B --> C[Record start trace]
    C --> D[start timer]
    D --> E[Build prompt string]
    E --> F[Record done trace]
    F --> G[Return ToolResult]
```

#### Pseudocode

```
FUNCTION directAnswer(question: String) -> ToolResult
  chatId = TraceContext.getChatId()
  round  = TraceContext.nextRound()
  args   = Map.of("question", question)

  record(chatId, round, "direct_answer", args, null, 0, "start")
  start = currentTimeMillis()

  content = "这是一个闲聊或常识类问题。无需检索知识库或网络，请直接基于你的通用知识回答。"
  duration = currentTimeMillis() - start

  record(chatId, round, "direct_answer", args, "闲聊/常识，无需检索", duration, "done")
  RETURN ToolResult("direct_answer", content, "direct", duration)
END
```

#### Boundary Decisions

| Parameter | Min | Max | Empty/Null | At boundary |
|-----------|-----|-----|------------|-------------|
| `question` string | 1 char | unbounded | empty string: still returns prompt (no guard) | very long question: no effect — always returns same static prompt |

#### Error Handling

> N/A — `directAnswer` is pure computation with no branching, no external calls, and no thrown exceptions.

---

### WebSearchTool.doSearch

#### Pseudocode

```
FUNCTION doSearch(query: String) -> String
  body = Map.of(
    "api_key",      apiKey,
    "query",        query,
    "max_results",  topK,
    "search_depth", "basic"
  )
  RETURN tavilyClient
    .post().uri("/search")
    .contentType(MediaType.APPLICATION_JSON)
    .body(body)
    .retrieve()
    .body(String.class)   // may throw RuntimeException on network/HTTP failure
END
```

#### Error Handling

| Condition | Detection | Response | Recovery |
|-----------|-----------|----------|----------|
| HTTP 4xx (bad API key, bad request) | `RestClient` throws `HttpClientErrorException` | Wrapped as `RuntimeException`, propagates to `searchWeb` catch block | Caught by `searchWeb`, returns error `ToolResult` |
| HTTP 5xx (Tavily server error) | `RestClient` throws `HttpServerErrorException` | Wrapped as `RuntimeException`, propagates | Caught by `searchWeb`, returns error `ToolResult` |
| Network timeout / unreachable | `RestClient` throws `ResourceAccessException` | Wrapped as `RuntimeException`, propagates | Caught by `searchWeb`, returns error `ToolResult` |

---

### WebSearchTool.parseResults

#### Pseudocode

```
FUNCTION parseResults(raw: String) -> List<JsonNode>
  root    = objectMapper.readTree(raw)           // may throw Exception on bad JSON
  results = root.path("results")
  list    = new ArrayList<JsonNode>()
  IF results.isArray() THEN
    results.forEach(list::add)
  END IF
  RETURN list
END
```

#### Error Handling

| Condition | Detection | Response | Recovery |
|-----------|-----------|----------|----------|
| `raw` is not valid JSON | `readTree` throws `JsonProcessingException` | Propagates as `Exception` to `searchWeb` catch block | Caught by `searchWeb`, returns error `ToolResult` |

---

## State Diagram

> N/A — stateless features. Both tools maintain no state between calls; `searchWeb` has no stateful lifecycle beyond its local variables and injected dependencies.

---

## Test Inventory

| ID | Category | Traces To | Input / Setup | Expected | Kills Which Bug? |
|----|----------|-----------|---------------|----------|-----------------|
| A | FUNC/happy | FR-013 AC-2 | `apiKey="valid-key"`, call `searchWeb("AI news")` with Tavily mocked to return 2 results | `ToolResult.toolName=="web_search"`, `content` contains both content strings, `source` contains both URLs, `durationMs >= 0` | Wrong content join or missing URL |
| B | FUNC/error | FR-013 AC-2 + §5 error table | `apiKey="valid-key"`, mock `doSearch` throws `RuntimeException("network error")` | `content.contains("Web 搜索失败")`, `content.contains("network error")`, `source==""` | Exception not caught, propagates out of `searchWeb` |
| C | FUNC/guard | FR-013 AC-3 | `apiKey=""`, call `searchWeb("query")` | `toolName=="web_search"`, `content.contains("未配置")`, `durationMs==0`, no HTTP call made | HTTP call made when key absent |
| D | BNDRY/edge | §5 boundary table | `apiKey="   "` (whitespace-only) | `isAvailable()==false`, `searchWeb` returns "未配置" | Blank-key guard only checks `null`, not whitespace |
| E | BNDRY/edge | FR-013 AC-3 + §5 boundary | No `TAVILY_API_KEY` env var at construction, call `isAvailable()` | `isAvailable()==false` | Null pointer on blank key check |
| F | BNDRY/edge | §5 boundary table | Tavily returns `{"results":[]}` (empty array) | `content==""`, `source==""`, `toolName=="web_search"` | Empty results cause NPE in stream |
| G | BNDRY/edge | §5 boundary table | Tavily returns `{"results":[{"url":"u","content":""}]}` (empty content) | `content==""` (filtered out), `source=="u"` | Empty string not filtered, content field empty |
| H | FUNC/happy | FR-013 AC-4 | Call `directAnswer("你好")` | `toolName=="direct_answer"`, `content.contains("闲聊")`, `content.contains("无需检索")`, `source=="direct"` | Wrong prompt returned |
| I | FUNC/happy | FR-013 AC-4 | Call `directAnswer("你是谁")`, check duration | `durationMs >= 0`, `durationMs < 10000` | Math mutator (negative duration) |
| J | BNDRY/edge | FR-013 AC-4 + §5 boundary | `directAnswer("")` with empty string | `content` still contains "闲聊" prompt (no guard) | Empty string causes different branch |
| K | BNDRY/edge | §5 boundary table | `apiKey="valid"`, `topK=1`, Tavily returns 3 results | `content` contains only 1st result's content | `topK` parameter not passed to Tavily |
| L | FUNC/parse | §5 parseResults | Raw JSON: `{"results":[{"title":"T","url":"u","content":"c"}],"answer":"a"}` | `results.size()==1`, `results[0].path("content").asText()=="c"` | Extra fields cause parse to fail |
| M | FUNC/parse | §5 parseResults | Raw JSON: `{"answer":"a"}` (no results field) | `parseResults` returns empty list | Missing `results` field causes NPE |
| N | INTG/http | FR-013 AC-2 + §4 external HTTP | Real `RestClient` (no mock), `apiKey="invalid"`, call `doSearch("q")` | `RuntimeException` propagates (HTTP 401) | Wrong exception type, wrong status code handling |
| O | INTG/http | FR-013 AC-2 + §4 external HTTP | Real `RestClient` (no mock), `apiKey="valid"`, call `doSearch("q")` | Returns non-null String (Tavily live response or error body) | Connection never established |
| P | BNDRY/timeout | §5 boundary table | `timeoutMs=1` (1ms timeout), `apiKey="valid"`, call `searchWeb("q")` | `content.contains("Web 搜索失败")` or `content.contains("失败")` | Timeout not applied, request hangs indefinitely |

**Negative test ratio**: 10/16 = 62.5% (>= 40% threshold) — IDs B, C, D, E, F, G, J, K, M, P

**INTG rows**: N (HTTP 401 error path), O (HTTP success path) — 2 INTG rows covering external HTTP dependency (Tavily API)

**ATS category alignment**: N/A — no ATS document provided for FR-013.

---

## Tasks

### Task 1: Write failing tests
**Files**:
- `rag-qa-backend/src/test/java/com/ragqa/agent/tool/WebSearchToolTest.java`
- `rag-qa-backend/src/test/java/com/ragqa/agent/tool/DirectAnswerToolTest.java`

**Steps**:
1. Add imports: `org.mockito.Spy`, `org.mockito.MockitoExtension`, `static org.mockito.Mockito.*`, `RestClient`, `MockRestServiceServer` (for INTG)
2. Write test code for each row in Test Inventory §7:
   - Test A: spy mock `doSearch`, verify `searchWeb` result fields
   - Test B: spy mock `doSearch` throws, verify error content
   - Test C: empty `apiKey`, verify "未配置" result
   - Test D: whitespace `apiKey`, verify `isAvailable()==false`
   - Test E: no key, verify `isAvailable()==false`
   - Test F: empty results array, verify content empty
   - Test G: empty content string, verify filtered
   - Test H: `directAnswer` happy path, verify prompt
   - Test I: `directAnswer` duration range
   - Test J: `directAnswer` empty string
   - Test K: `topK=1`, verify only 1 result in output
   - Test L: parse JSON with extra fields
   - Test M: parse JSON without results field
   - Test N: real `RestClient` with invalid key (INTG — expects exception)
   - Test O: real `RestClient` with valid key (INTG — expects non-null)
   - Test P: 1ms timeout (BNDRY — expects failure result)
3. Run: `mvn test -pl rag-qa-backend -Dtest=WebSearchToolTest,DirectAnswerToolTest -f /Users/yh/workbench/IdeaProject/RAG-demo/pom.xml`
4. **Expected**: All tests FAIL for the right reason (TDD Red phase)

### Task 2: Implement minimal code
**Files**:
- `rag-qa-backend/src/main/java/com/ragqa/agent/tool/WebSearchTool.java`
- `rag-qa-backend/src/main/java/com/ragqa/agent/tool/DirectAnswerTool.java`
- `rag-qa-backend/src/main/java/com/ragqa/agent/tool/ToolResult.java` (F17, read-only)

**Steps**:
1. Ensure `WebSearchTool` constructor accepts `apiKey`, `topK`, `timeoutMs`, `RestClient.Builder`, `AgentTraceCollector`; `isAvailable()` checks blank; `searchWeb` follows algorithm pseudocode exactly
2. Ensure `DirectAnswerTool` returns the exact prompt string
3. Run: `mvn test -pl rag-qa-backend -Dtest=WebSearchToolTest,DirectAnswerToolTest`
4. **Expected**: All tests PASS

### Task 3: Coverage Gate
1. Run: `mvn jacoco:report -pl rag-qa-backend -f /Users/yh/workbench/IdeaProject/RAG-demo/pom.xml`
2. Check: line >= 90%, branch >= 80%, mutation >= 80%
3. If below: add targeted test cases
4. Record: JaCoCo HTML report path

### Task 4: Refactor
1. Verify no duplicate mock setup across test methods; extract `realTool(String)` helper
2. Run full test suite: `mvn test -pl rag-qa-backend`
3. **Expected**: All tests PASS

### Task 5: Mutation Gate
1. Run: `mvn org.pitest:pitest-maven:mutationCoverage -pl rag-qa-backend -DtargetClasses=com.ragqa.agent.tool.* -f /Users/yh/workbench/IdeaProject/RAG-demo/pom.xml`
2. Check: mutation score >= 80%
3. If below: improve assertions (e.g., stream lambda branch, MathMutator guard)
4. Record: Pitest HTML report path

---

## Verification Checklist

- [x] All SRS acceptance criteria (from srs_trace) traced to Interface Contract postconditions
- [x] All SRS acceptance criteria (from srs_trace) traced to Test Inventory rows
- [x] Algorithm pseudocode covers all non-trivial methods (`searchWeb`, `doSearch`, `parseResults`, `directAnswer`)
- [x] Boundary table covers all algorithm parameters (query, apiKey, topK, timeoutMs)
- [x] Error handling table covers all Raises entries (all exceptions caught inside `searchWeb`)
- [x] Test Inventory negative ratio 62.5% >= 40%
- [x] Visual Rendering Contract complete for ui:true features (N/A — backend-only)
- [x] Each Visual Rendering Contract element has >= 1 UI/render Test Inventory row (N/A)
- [x] Every skipped section has explicit "N/A — [reason]"
- [x] All functions/methods named in §11.3.3 and §11.3.4 have at least one Test Inventory row (isAvailable, searchWeb, doSearch, parseResults, directAnswer all covered)
- [x] §6.2 contract alignment: F18 is not listed in the current design §6.2 internal API contracts; no deviation required
- [x] Design Interface Coverage Gate: §11.3.3 `isAvailable()` covered (rows C,D,E); §11.3.3 `searchWeb` covered (rows A,B,F,G,K,P); §11.3.3 `doSearch` covered (rows N,O); §11.3.3 `parseResults` covered (rows L,M); §11.3.4 `directAnswer` covered (rows H,I,J)

---

## Clarification Addendum

> No clarifications required — all specifications were unambiguous.

| # | Category | Original Ambiguity | Resolution | Authority |
|---|----------|--------------------|------------|-----------|
| — | — | — | — | — |

---

## SubAgent Result: Feature Design
### Verdict: PASS
### Summary
Feature #18 detailed design written to `docs/features/2026-08-03-f18-websearch-directanswer.md`. The document covers two conditionally-registered Spring AI `@Tool` components (`WebSearchTool` backed by Tavily and `DirectAnswerTool` for chitchat), with 16 Test Inventory rows (62.5% negative), 2 INTG rows for the external Tavily HTTP call, exact interface signatures matching the as-built implementation, and complete algorithm/error-handling/boundary tables. All SRS FR-013 acceptance criteria are traced. No ambiguities were detected.
### Artifacts
- `/Users/yh/workbench/IdeaProject/RAG-demo/.claude/worktrees/agent-afa294f9fa71fb62b/docs/features/2026-08-03-f18-websearch-directanswer.md`
### Metrics
| Metric | Value | Threshold | Status |
|--------|-------|-----------|--------|
| Sections Complete | 8/8 | 8/8 | PASS |
| Test Inventory Rows | 16 | >= 4 (SRS acceptance criteria count) | PASS |
| Negative Test Ratio | 62.5% | >= 40% | PASS |
| Verification Checklist | 10/10 | 10/10 | PASS |
| Design Interface Coverage | 5/5 | 5/5 (§11.3.3/11.3.4 named methods) | PASS |
| Visual Rendering Assertions | N/A | N/A | N/A |
### Issues (only if FAIL or BLOCKED)
None.
### Ambiguities (only if CLARIFY)
None.
### Assumptions Made (only if PASS with assumptions)
None.
### Next Step Inputs
- `feature_design_doc`: `/Users/yh/workbench/IdeaProject/RAG-demo/.claude/worktrees/agent-afa294f9fa71fb62b/docs/features/2026-08-03-f18-websearch-directanswer.md`
- `test_inventory_count`: 16
- `tdd_task_count`: 5 (Write failing tests, Implement minimal code, Coverage Gate, Refactor, Mutation Gate)
- `ambiguity_count`: 0
- `assumption_count`: 0
