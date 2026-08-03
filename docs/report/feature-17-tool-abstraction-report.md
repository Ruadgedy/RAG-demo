# Feature #17 开发报告：Tool 抽象 + KnowledgeBaseSearchTool

## A. 基本信息

| 项目 | 内容 |
|---|---|
| Feature | #17 Tool 抽象 + KnowledgeBaseSearchTool |
| Category / Priority / Wave | feature / High / Wave 1 |
| SRS Trace | FR-013 |
| Dependencies | F4（当前 passing） |
| UI | 否 |
| 完成日期 | 2026-08-03 |
| Git SHA | 待最终进度提交后回填 |

## B. 需求一致性简报

FR-013 要求提供统一 Spring AI `@Tool` 抽象，首批工具包含 KnowledgeBaseSearchTool；KnowledgeBaseSearchTool 必须复用 `RagService.retrieve`；知识库 ID 不作为 LLM 参数暴露，而由上下文注入。

| 验收标准 | 实现接口 | 验证测试 | 判定 |
|---|---|---|---|
| 注册 KnowledgeBaseSearchTool 并调用 `searchKnowledgeBase(query)` | `KnowledgeBaseSearchTool.searchKnowledgeBase(String)`、`@Component`、`@Tool` | `shouldExposeSpringAiToolAnnotationWithKnowledgeBaseDescription`、`shouldCallRetrieveAndReturnToolResult` | Covered |
| kbId 从上下文注入，不暴露给 LLM | `KnowledgeBaseContext.set/get/clear`；`searchKnowledgeBase` 仅接收 query | `shouldUseKnowledgeBaseContextKbId`、`shouldPassNullKbIdToRetrieveWhenContextUnset`、`clearShouldRemoveKbId` | Covered |
| 复用 RagService.retrieve 并返回统一 ToolResult | `RagService.retrieve(String, UUID)`、`ToolResult` record | `shouldCallRetrieveAndReturnToolResult`、`shouldReturnEmptyWhenNoResults`、`singleResultShouldFormatSourceWithoutTrailingComma` | Covered |

**总体一致性：3/3 验收标准完全覆盖。**

## C. Quality Gates

| Gate | 结果 | 指标 | 门槛 |
|---|---|---:|---:|
| Real Test | PASS | 11/11 F17 tests passed | 无失败/跳过 |
| Line Coverage | PASS | 100%（F17 classes） | ≥90% |
| Branch Coverage | PASS | 92.3%（F17 classes） | ≥80% |
| Mutation | PASS | 90%（9/10 killed） | ≥80% |

Mutation 报告存在 1 个 surviving conditional mutant（trace summary 的来源条件），但分数仍超过门槛；建议后续将 trace summary 使用精确 matcher 断言。

## D. 真实测试执行摘要

本次自动化测试全部运行在后端 Maven 测试环境：

| 类型 | 数量 | 结果 |
|---|---:|---|
| Mock 单元测试 | 11 | 11 PASS |
| 真实外部依赖测试 | 0 | 未执行 |
| 手工真实场景 | 3 | PENDING-MANUAL |

| 用例组 | 测试引用 | 结果 |
|---|---|---|
| 正常检索、ToolResult 字段、retrieve 委托 | `shouldCallRetrieveAndReturnToolResult` | PASS |
| 空检索结果 | `shouldReturnEmptyWhenNoResults` | PASS |
| Context kbId 注入 | `shouldUseKnowledgeBaseContextKbId` | PASS |
| 来源去重、单来源格式 | `shouldDeduplicateSourceFileNames`、`singleResultShouldFormatSourceWithoutTrailingComma` | PASS |
| Context 清理、null kbId | `clearShouldRemoveKbId`、`shouldPassNullKbIdToRetrieveWhenContextUnset` | PASS |
| duration 边界 | `durationShouldBeReasonable` | PASS |
| Spring AI Tool 注解契约 | `shouldExposeSpringAiToolAnnotationWithKnowledgeBaseDescription` | PASS |
| F21 trace start/done 路径 | `shouldRecordStartAndDoneTraceWhenChatIdSet`、`shouldSkipTraceWhenChatIdUnset` | PASS |

3 个需要真实 LLM、数据库、Chroma 的场景已在 ST 文档中明确标记 `PENDING-MANUAL`，未将 Mock 测试伪称为 Real 测试。

## E. 风险评估与解决办法

| 风险 | 严重度 | 缓解措施 | 状态 |
|---|---|---|---|
| Trace 路径与 F17 工具代码耦合，初始覆盖不足 | Major | 新增 chatId 设置/未设置两条测试，覆盖 start/done 与跳过分支；质量门已通过 | Resolved |
| 1 个 trace summary 条件变异存活 | Minor | 后续补精确 summary matcher；当前 mutation 90% 已达门槛 | Accepted |
| 真实 LLM/DB/Chroma 场景尚未执行 | Major | ST 文档保留 3 个 PENDING-MANUAL 场景；上线前需在完整环境补测 | Deferred |
| Pitest 提示未安装 Arcmutate Spring plugin | Minor | 当前为非阻塞提示；后续若需要 Spring 感知变异，再评估插件引入 | Accepted |

## F. Inline Compliance Check

| 检查项 | 结果 | 证据 |
|---|---|---|
| P2 Interface Contract | PASS | 4/4 契约组存在 |
| T2 Test Inventory | PASS | 10/10 设计清单行均有对应测试；新增 trace 覆盖 |
| D3 依赖版本 | PASS | Spring AI `@Tool` 与 pom 依赖一致 |
| U1 UI | N/A | backend-only feature |
| ST 文档完整性 | PASS | `validate_st_cases.py`：12 cases valid |

## G. Feature-ST 摘要

- 自动化用例：12 个文档用例，其中 9 个 Mock 自动化用例 + 3 个 PENDING-MANUAL 外部场景。
- 已执行自动化：11/11 F17 Maven tests PASS。
- 自动化通过率：100%。
- 真实外部依赖场景：未执行，保持 PENDING-MANUAL。
- UI 视觉评估：N/A（非 UI feature）。

## H. 文件变更

- `rag-qa-backend/src/test/java/com/ragqa/agent/tool/KnowledgeBaseSearchToolTest.java`
- `docs/features/2026-08-03-f17-tool-abstraction.md`
- `docs/test-cases/feature-17-tool-abstraction.md`
- `feature-list.json`
- `task-progress.md`
- `RELEASE_NOTES.md`

## I. 依赖

- F4 RAG 检索引擎：passing
- F17 不向 F18/F19 反向引入新的 API 变更。
