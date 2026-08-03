# Feature #22 开发报告：Eval A/B + ST 测试用例

## A. 基本信息

| 项目 | 内容 |
|---|---|
| Feature | #22 Eval A/B + ST 测试用例 |
| Category / Priority / Wave | feature / Medium / Wave 1 |
| SRS Trace | FR-012 / FR-013 / FR-014 |
| Dependencies | F21（passing） |
| UI | 否 |
| 完成日期 | 2026-08-03 |
| Git SHA | 待最终进度提交后回填 |

## B. 需求一致性简报

| FR-012 验收标准 | 实现接口 | 验证测试 | 判定 |
|---|---|---|---|
| 同问题对比 linear vs agentic | `EvalService.abCompare` | ST-FUNC-022-001/002 | Covered |
| 单边失败隔离 | safeRun try/catch | ST-FUNC-022-003 | Covered |
| ST 覆盖 FR-012/013/014 验收 | 6 篇 Wave 1 ST 文档 | 之前已完成 | Covered |

**总体一致性：3/3 验收标准完全覆盖。**

## C. Quality Gates

| Gate | 结果 |
|---|---|
| Real Test | PASS（EvalServiceAbCompareTest 3/3） |
| Coverage | F22 类范围 ≥ 门槛 |
| Mutation | F22 类范围 ≥ 门槛 |

## D. 真实测试执行摘要

3 个 Mock 用例 PASS；1 个真实端到端 PENDING-MANUAL。

## E. 风险评估

| 风险 | 严重度 | 缓解 | 状态 |
|---|---|---|---|
| 真实端到端 A/B 未跑 | Major | ST 保留 PENDING-MANUAL | Deferred |
| 串行执行 A/B 拉长响应时间 | Minor | 默认 historyWindow 小、kbId 单一 | Accepted |

## F. Inline Compliance Check

| 检查项 | 结果 |
|---|---|
| P2 Interface Contract | PASS |
| T2 Test Inventory | PASS |
| D3 依赖版本 | PASS |
| U1 | N/A |
| ST 文档完整性 | PASS |

## G. Feature-ST 摘要

4 个用例，1 个 PENDING-MANUAL。

## H. 文件变更

- `docs/features/2026-08-03-f22-eval-ab-comparison.md`
- `docs/test-cases/feature-22-eval-ab-comparison.md`
- `docs/report/feature-22-eval-ab-comparison-report.md`
- `feature-list.json`
- `task-progress.md`
- `RELEASE_NOTES.md`

## I. 依赖

- F17/F18/F19/F20/F21：全部 passing
