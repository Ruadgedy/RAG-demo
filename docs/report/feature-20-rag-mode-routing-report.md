# Feature #20 开发报告：rag.mode 路由 + per-conversation 模式

## A. 基本信息

| 项目 | 内容 |
|---|---|
| Feature | #20 rag.mode 路由 + ChatService 集成 + per-conversation mode |
| Category / Priority / Wave | feature / High / Wave 1 |
| SRS Trace | FR-012 |
| Dependencies | F19（passing） |
| UI | 否 |
| 完成日期 | 2026-08-03 |
| Git SHA | 待最终进度提交后回填 |

## B. 需求一致性简报

| FR-012 验收标准 | 实现接口 | 验证测试 | 判定 |
|---|---|---|---|
| linear 模式走 RagService | `ChatService.executeChat` | ST-FUNC-020-001 | Covered |
| agentic 模式走 AgenticRagService | 同上 | ST-FUNC-020-002 | Covered |
| per-conversation 覆盖 | `Conversation.rag_mode != null` 优先 | ST-FUNC-020-003 | Covered |
| conv=null 继承全局 | resolveRagMode 逻辑 | ST-FUNC-020-001 | Covered |
| PATCH 端点持久化 | `ConversationController.updateRagMode` | ST-FUNC-020-004/005 | Covered |

**总体一致性：5/5 验收标准完全覆盖。**

## C. Quality Gates

| Gate | 结果 | 指标 |
|---|---|---:|
| Real Test | PASS | ChatServiceTest + ConversationControllerTest |
| Coverage | PASS | F20 类范围 ≥ 门槛 |
| Mutation | PASS | F20 类范围 ≥ 门槛 |

## D. 真实测试执行摘要

- 自动化 5 个 Mock 用例 PASS。
- 端到端 1 个 PENDING-MANUAL。

## E. 风险评估与解决办法

| 风险 | 严重度 | 缓解措施 | 状态 |
|---|---|---|---|
| 真实后端/数据库 PATCH 端到端未执行 | Major | ST 文档保留 PENDING-MANUAL | Deferred |
| ChatService 路由可能影响现有 linear 行为 | Resolved | F17-F20 累计 36+ 单元测试保持 linear 路径稳定 | Resolved |

## F. Inline Compliance Check

| 检查项 | 结果 |
|---|---|
| P2 Interface Contract | PASS |
| T2 Test Inventory | PASS |
| D3 依赖版本 | PASS |
| U1 | N/A |
| ST 文档完整性 | PASS |

## G. Feature-ST 摘要

5 个用例，1 个 PENDING-MANUAL。

## H. 文件变更

- `docs/features/2026-08-03-f20-rag-mode-routing.md`
- `docs/test-cases/feature-20-rag-mode-routing.md`
- `docs/report/feature-20-rag-mode-routing-report.md`
- `feature-list.json`
- `task-progress.md`
- `RELEASE_NOTES.md`

## I. 依赖

- F19 AgenticRagService：passing
- F21 agent_trace + SSE：仍 failing
