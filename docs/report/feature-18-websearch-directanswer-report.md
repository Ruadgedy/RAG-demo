# Feature #18 开发报告：WebSearchTool（Tavily）+ DirectAnswerTool

## A. 基本信息

| 项目 | 内容 |
|---|---|
| Feature | #18 WebSearchTool（Tavily）+ DirectAnswerTool |
| Category / Priority / Wave | feature / High / Wave 1 |
| SRS Trace | FR-013 |
| Dependencies | F17（passing） |
| UI | 否 |
| 完成日期 | 2026-08-03 |
| Git SHA | 待最终进度提交后回填 |

## B. 需求一致性简报

| FR-013 验收标准 | 实现接口 | 验证测试 | 判定 |
|---|---|---|---|
| 配置 Tavily key 时搜索并返回 Top-N 摘要 | `WebSearchTool.searchWeb`、`doSearch`、`parseResults` | `shouldReturnToolResultFromTavilyResponse`、`shouldApplyConfiguredTopKToTavilyRequest`、`parseResultsShouldExtractResultsArray` | Covered |
| 未配置 key 时 WebSearchTool 不可用 | `WebSearchTool.isAvailable`、`searchWeb` guard | `isAvailableShouldReturnFalseWhenApiKeyAbsent`、`shouldReturnNotConfiguredWhenNoApiKey`、`shouldRecordTraceForUnavailableSearch` | Covered |
| 闲聊/常识问题使用 DirectAnswerTool | `DirectAnswerTool.directAnswer` | `shouldReturnDirectAnswerToolResult`、`directSearchShouldKeepPromptForEmptyQuestion` | Covered |
| Tavily 网络失败不拖垮 Agent | `searchWeb` catch/failure ToolResult | `shouldHandleSearchFailureGracefully`、`shouldConvertTimeoutFailureToToolResult`、`shouldRecordTraceForFailedSearch` | Covered |

**总体一致性：4/4 验收标准完全覆盖。**

## C. Quality Gates

| Gate | 结果 | 指标 | 门槛 |
|---|---|---:|---:|
| Real Test | PASS | 18/18 | 无失败/跳过 |
| Line Coverage | PASS | 98.8% | ≥90% |
| Branch Coverage | PASS | 89.3% | ≥80% |
| Mutation | PASS | 84% | ≥80% |

已知风险：WebSearchTool 的部分 timeout setter 变异属于等价变异；少量 stream 条件变异存活，但整体 mutation 仍超过门槛。

## D. 真实测试执行摘要

| 类型 | 数量 | 结果 |
|---|---:|---|
| Mock 单元测试 | 18 | 18 PASS |
| 真实 Tavily HTTP 测试 | 0 | 未执行 |
| 手工真实场景 | 2 | PENDING-MANUAL |

| 自动化测试组 | 结果 |
|---|---|
| WebSearchToolTest | 14/14 PASS |
| DirectAnswerToolTest | 4/4 PASS |
| 成功/失败/未配置 trace 分支 | PASS |
| DirectAnswer 空输入与耗时边界 | PASS |

## E. 风险评估与解决办法

| 风险 | 严重度 | 缓解措施 | 状态 |
|---|---|---|---|
| `TAVILY_API_KEY` 当前为空，真实 Tavily 成功与 401 场景未执行 | Major | ST 文档保留两个 PENDING-MANUAL 用例；上线前用测试 key/无效 key 在隔离环境补测 | Deferred |
| WebSearchTool timeout setter 存在等价变异 | Minor | 保留配置化 timeout；后续可用本地 HTTP 延迟服务器补充集成测试 | Accepted |
| 两个 lambda 条件变异存活 | Minor | 已覆盖正常、空结果、失败和 trace 分支；mutation 84% 通过门槛 | Accepted |
| Tavily 外部服务配额/网络可用性 | Major | 对失败做 catch 降级，生产环境配置超时与监控；不把外部调用作为单测前置 | Accepted |

## F. Inline Compliance Check

| 检查项 | 结果 | 证据 |
|---|---|---|
| P2 Interface Contract | PASS | 5/5 公共方法契约存在 |
| T2 Test Inventory | PASS | FUNC/BNDRY/INTG 行均有对应测试或 PENDING-MANUAL 追踪 |
| D3 依赖版本 | PASS | Spring AI `@Tool`、Spring Web `RestClient` 与 pom 一致 |
| U1 UI | N/A | backend-only feature |
| ST 文档完整性 | PASS | `validate_st_cases.py`：10 cases valid |

## G. Feature-ST 摘要

- ST 用例总数：10。
- 自动化测试：18/18 PASS。
- FUNC：7 个（其中 2 个真实外部场景 PENDING-MANUAL）。
- BNDRY：3 个，自动化通过。
- 真实 Tavily HTTP：2 个场景未执行，已明确标记为 PENDING-MANUAL。
- UI 视觉评估：N/A。

## H. 文件变更

- `rag-qa-backend/src/main/java/com/ragqa/agent/tool/WebSearchTool.java`（既有实现，F18 验收确认）
- `rag-qa-backend/src/main/java/com/ragqa/agent/tool/DirectAnswerTool.java`（既有实现，F18 验收确认）
- `rag-qa-backend/src/test/java/com/ragqa/agent/tool/WebSearchToolTest.java`
- `rag-qa-backend/src/test/java/com/ragqa/agent/tool/DirectAnswerToolTest.java`
- `docs/features/2026-08-03-f18-websearch-directanswer.md`
- `docs/test-cases/feature-18-websearch-directanswer-tool.md`
- `feature-list.json`
- `task-progress.md`
- `RELEASE_NOTES.md`

## I. 依赖

- F17 Tool 抽象 + KnowledgeBaseSearchTool：passing
- F19 AgenticRagService：仍为 failing，等待 F18 元数据闭环后推进
